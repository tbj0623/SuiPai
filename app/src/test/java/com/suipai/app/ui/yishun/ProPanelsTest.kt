package com.suipai.app.ui.yishun

import com.suipai.app.data.CustomLook
import com.suipai.app.engine.Shifts
import com.suipai.app.ui.yishun.pro.CurveChannels
import com.suipai.app.ui.yishun.pro.CurvePoints
import com.suipai.app.ui.yishun.pro.HslBandKind
import com.suipai.app.ui.yishun.pro.HslChannel
import com.suipai.app.ui.yishun.pro.HslModel
import com.suipai.app.ui.yishun.pro.MaskAdjustChannel
import com.suipai.app.ui.yishun.pro.MaskModel
import com.suipai.app.ui.yishun.pro.ProOps
import com.suipai.app.ui.yishun.pro.buildCustomLook
import com.suipai.app.ui.yishun.pro.defaultRecipeName
import com.suipai.app.ui.yishun.pro.recipeBadge
import com.suipai.app.ui.yishun.pro.summaryLine
import com.suipai.app.ui.yishun.pro.toApplied
import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.RadialMask
import com.suipai.core.engine.RecipeHsl
import com.suipai.core.engine.RecipeMask
import com.suipai.core.engine.RecipePro
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves
import com.suipai.core.engine.YishunRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 专业面板的纯函数：曲线控制点、HSL、蒙版钳制、配方 ↔ 状态往返。
 *
 * 为什么这些值得单测而不是靠设备手点：它们全部是**手指打进来的外部输入**
 * （拖动、粘贴、从 EXIF 读回），而错法的共同特征是"不崩、不报错、只是结果不对"——
 * 曲线少了一个点、蒙版半径被削成 0、存出去的配方丢了曲线。
 * 每一条都配了「喂坏例子必须变红」的自检，照 `docs/engine/COLOR-PRO.md` §9 的纪律。
 *
 * 与 `shared` 侧测试的分工：那边钉数学（单调性、逐位一致、往返误差），
 * 这边钉**编辑器行为**（加点/删点/拖动/钳制/往返），不重复验证引擎。
 */
class ProPanelsTest {

    // ---- 曲线控制点 -----------------------------------------------------------

    @Test
    fun addInsertsSortedAndReportsIndex() {
        val (next, idx) = CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.75f)
        assertEquals(3, next.size)
        assertEquals(listOf(0f, 0.5f, 1f), next.map { it.x })
        assertEquals(1, idx)
        assertEquals(0.75f, next[idx].y, 1e-6f)

        // 自检：拒绝的两种情形必须回 -1，而不是悄悄返回一个旧下标
        val (full, rejected) = CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.5f)
        assertTrue(rejected >= 0)
        val (tooNear, dupIdx) = CurvePoints.add(full, 0.51f, 0.5f)
        assertEquals("距已有点 < MIN_GAP 仍被加入", -1, dupIdx)
        assertEquals(tooNear.size, full.size)
    }

    @Test
    fun addIntoEndpointsMovesTheEndpointInsteadOfCreatingOne() {
        // 在 x≈0 处点一下：必须只改端点 y，不能变成 3 个点（否则曲线左端永远多一个台阶）
        val (next, idx) = CurvePoints.add(CurvePoints.IDENTITY, 0.001f, 0.2f)
        assertEquals(2, next.size)
        assertEquals(0, idx)
        assertEquals(0f, next[0].x, 1e-6f)
        assertEquals(0.2f, next[0].y, 1e-6f)
        // 自检：右端点同理，且不能把左端点一起改掉
        val (right, ridx) = CurvePoints.add(next, 0.999f, 0.8f)
        assertEquals(2, right.size)
        assertEquals(1, ridx)
        assertEquals(0.2f, right[0].y, 1e-6f)
    }

    @Test
    fun addStopsAtSixteenAndSaysSoByReturningMinusOne() {
        var points = CurvePoints.IDENTITY
        repeat(CurvePoints.MAX_POINTS) { i ->
            val x = 0.05f + i * 0.055f
            val (next, _) = CurvePoints.add(points, x, 0.5f)
            points = next
        }
        assertEquals("允许的最大点数与引擎不一致", CurvePoints.MAX_POINTS, points.size)
        val (same, idx) = CurvePoints.add(points, 0.5f, 0.9f)
        assertEquals(-1, idx)
        assertEquals("满了还改了列表", points, same)
        // 自检：MAX_POINTS 必须真的等于引擎那边的常量，各改各的会在这里红
        assertEquals(ToneCurve.MAX_POINTS, CurvePoints.MAX_POINTS)
        assertEquals(ToneCurve.MIN_POINTS, CurvePoints.MIN_POINTS)
    }

    @Test
    fun removeNeverDropsBelowTwoOrTakesAnEndpoint() {
        val five = listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.3f), CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.7f), CurvePoint(1f, 1f),
        )
        assertEquals(4, CurvePoints.remove(five, 2).size)
        // 端点不可删
        assertEquals(5, CurvePoints.remove(five, 0).size)
        assertEquals(5, CurvePoints.remove(five, 4).size)
        // 越界不抛
        assertEquals(5, CurvePoints.remove(five, 99).size)
        // 只剩 2 点时不能再删
        assertEquals(2, CurvePoints.remove(CurvePoints.IDENTITY, 0).size)
        // 自检：删中间点时删掉的确实是它，不是第一个
        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), CurvePoints.remove(five, 1).map { it.x })
    }

    @Test
    fun moveLocksEndpointXButNotMiddlePoints() {
        val three = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        val left = CurvePoints.move(three, 0, 0.4f, 0.2f)
        assertEquals("端点 x 被拖走了", 0f, left[0].x, 1e-6f)
        assertEquals(0.2f, left[0].y, 1e-6f)
        val right = CurvePoints.move(three, 2, 0.6f, 0.9f)
        assertEquals(1f, right[2].x, 1e-6f)
        assertEquals(0.9f, right[2].y, 1e-6f)

        val mid = CurvePoints.move(three, 1, 0.2f, 0.8f)
        assertEquals("中间点该跟着 x 走", 0.2f, mid[1].x, 1e-6f)
        assertEquals(0.8f, mid[1].y, 1e-6f)
    }

    @Test
    fun moveClampsMiddlePointBetweenNeighbours() {
        val three = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        // 拖过邻居不许换序：顺序一变下标失效，下一次拖动就跳
        val past = CurvePoints.move(three, 1, 1.5f, 0.5f)
        assertTrue("中间点越过了右邻点：${past.map { it.x }}", past[1].x < past[2].x)
        val before = CurvePoints.move(three, 1, -0.5f, 0.5f)
        assertTrue("中间点越过了左邻点", before[1].x > before[0].x)
        // 自检：合法区间内的值不许被改
        val inside = CurvePoints.move(three, 1, 0.4f, 0.5f)
        assertEquals(0.4f, inside[1].x, 1e-6f)
    }

    @Test
    fun moveKeepsCurveAFunctionWhenNeighboursAreTight() {
        // 相邻两点贴得比 2*MIN_GAP 还近时（删点后会出现），区间会反转 —— 不许产生 NaN/越界
        val tight = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.3f), CurvePoint(0.501f, 0.4f), CurvePoint(1f, 1f))
        val moved = CurvePoints.move(tight, 1, 0.9f, 0.5f)
        assertTrue("x 不是有限值", moved[1].x.isFinite())
        assertTrue(moved[1].x in 0f..1f)
        assertTrue("x 越过右邻点", moved[1].x <= moved[2].x)
    }

    @Test
    fun hitTestPicksNearestWithinRadius() {
        val three = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        assertEquals(1, CurvePoints.hitTest(three, 0.52f, 0.48f))
        // 空白处不许命中
        assertEquals(-1, CurvePoints.hitTest(three, 0.25f, 0.8f))
        // 自检：半径是判据的一部分 —— 放宽到足够大时必须命中
        assertTrue(CurvePoints.hitTest(three, 0.25f, 0.8f, radiusGap = 2f) >= 0)
    }

    // ---- 曲线 ↔ 通道 ----------------------------------------------------------

    @Test
    fun identityCurveSerializesToNull() {
        // 与 IDENTITY 逐点相等的曲线不许写进配方：v2 形状里不该凭空多出 curve 键
        assertNull(CurvePoints.toCurve(CurvePoints.IDENTITY))
        val bent = CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.7f).first
        assertNotNull(CurvePoints.toCurve(bent))
    }

    @Test
    fun channelWriteIsScopedToThatChannel() {
        var curves = ToneCurves()
        val bent = CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.7f).first
        curves = CurveChannels.withPoints(curves, CurveChannels.MASTER, bent)
        assertNotNull(curves.master)
        assertNull("改主通道把 R 也写了", curves.r)
        curves = CurveChannels.withPoints(curves, CurveChannels.R, bent)
        assertNotNull(curves.r)
        assertNotNull("清 R 时把主通道一起清了", curves.master)
        // 自检：清了这条，别的不许动
        val cleared = CurveChannels.clearChannel(curves, CurveChannels.MASTER)
        assertNull(cleared.master)
        assertNotNull(cleared.r)
        // 全清
        assertTrue(CurveChannels.clearAll().isNeutral)
    }

    @Test
    fun channelIndexesMatchEngineShape() {
        assertEquals(4, CurveChannels.count)
        // 自检：四条的 label 各不相同（否则抽屉里四颗格子看起来一样）
        val labels = (0 until CurveChannels.count).map { CurveChannels.label(it) }
        assertEquals(labels.size, labels.distinct().size)
    }

    // ---- HSL -----------------------------------------------------------------

    @Test
    fun hslBandOrderMatchesEngineHues() {
        // 界面侧写死了一份中文名与代表色，顺序必须与 HslBands.HUES 一致，
        // 否则「红」那一格动的是橙的色相 —— 不崩、不报错，只是调不准。
        assertEquals(HslBands.BAND_COUNT, HslBandKind.entries.size)
        for ((i, kind) in HslBandKind.entries.withIndex()) {
            assertEquals("第 $i 带的 index 与枚举顺序不一致", i, kind.index)
        }
        // 自检：越界夹到端点（与 HslBands.band 的语义一致）
        assertEquals(HslBandKind.RED, HslBandKind.at(-5))
        assertEquals(HslBandKind.MAGENTA, HslBandKind.at(99))
    }

    @Test
    fun hslWriteOnlyTouchesOneBandAndClamps() {
        val bands = HslBands.NONE
        val next = HslModel.withValue(bands, HslBandKind.ORANGE, HslChannel.HUE, 0.5f)
        assertEquals(0.5f, HslModel.band(next, HslBandKind.ORANGE).hue, 1e-6f)
        assertEquals("动了别的带", 0f, HslModel.band(next, HslBandKind.BLUE).hue, 1e-6f)

        val clamped = HslModel.withValue(next, HslBandKind.ORANGE, HslChannel.HUE, 9f)
        assertEquals("越界没夹回", 1f, HslModel.band(clamped, HslBandKind.ORANGE).hue, 1e-6f)
        // 自检：NaN 不许写成 NaN（写进去会让滑杆显示空白、引擎那边 getter 又夹回 0）
        val nan = HslModel.withValue(next, HslBandKind.ORANGE, HslChannel.HUE, Float.NaN)
        assertEquals(0f, HslModel.band(nan, HslBandKind.ORANGE).hue, 1e-6f)
    }

    @Test
    fun hslClearingAndCounts() {
        val bands = HslModel.withValue(
            HslModel.withValue(HslBands.NONE, HslBandKind.RED, HslChannel.SAT, 0.4f),
            HslBandKind.BLUE,
            HslChannel.LUM,
            -0.3f,
        )
        assertEquals(2, HslModel.touchedBands(bands))
        // 自检：摘要必须点到那两个带的名字，而不是只说"2 个"
        assertTrue("摘要没提到红：${HslModel.summary(bands)}", "红" in HslModel.summary(bands))
        val oneCleared = HslModel.clearBand(bands, HslBandKind.RED)
        assertEquals(1, HslModel.touchedBands(oneCleared))
        assertEquals(0, HslModel.touchedBands(HslModel.clearAll()))
    }

    @Test
    fun hslRoundTripsThroughRecipe() {
        val bands = HslModel.withValue(HslBands.NONE, HslBandKind.CYAN, HslChannel.HUE, 0.2f)
        val recipe = RecipePro.withPro(YishunRecipe(lookId = "daylight", intensity = 0.5f), hsl = bands)
        assertNotNull("分色没进配方", recipe.hsl)
        val back = RecipePro.hslOf(recipe)
        assertEquals(0.2f, HslModel.band(back, HslBandKind.CYAN).hue, 1e-6f)
        // 自检：中性时不许写出 hsl 键（那样 v2 配方会凭空膨胀）
        assertNull(RecipePro.withPro(YishunRecipe(lookId = "daylight", intensity = 0.5f)).hsl)
    }

    // ---- 蒙版 ----------------------------------------------------------------

    @Test
    fun maskCapIsFourAndAddStopsThere() {
        assertEquals(4, MaskModel.MAX_MASKS)
        var masks = emptyList<LocalMask>()
        repeat(6) { masks = MaskModel.add(masks, MaskModel.Kind.RADIAL) }
        assertEquals(4, masks.size)
        // 自检：满了之后再加必须是原样返回，不是削掉别的再塞进来
        assertEquals(masks, MaskModel.add(masks, MaskModel.Kind.LINEAR))
        // 自检：2 个时能加到 3；3 个能到 4；4 个就停
        val three = MaskModel.add(masks.take(2), MaskModel.Kind.LINEAR)
        assertEquals(3, three.size)
        assertEquals(4, MaskModel.add(three, MaskModel.Kind.LINEAR).size)
    }

    @Test
    fun maskRadialClampsRadiusAndFeather() {
        val m = MaskModel.radial(cx = 1.4f, cy = -0.2f, rx = 0f, ry = 5f, feather = 2f, inverted = true)
        assertEquals(1f, m.cx, 1e-6f)
        assertEquals(0f, m.cy, 1e-6f)
        assertEquals(MaskModel.MIN_RADIUS, m.rx, 1e-6f)
        assertEquals(1f, m.ry, 1e-6f)
        assertEquals(1f, m.feather, 1e-6f)
        assertTrue(m.inverted)
        // 自检：负半径按绝对值处理（引擎侧也是 abs），不许变成 0
        assertTrue(MaskModel.radial(0.5f, 0.5f, -0.3f, -0.3f, 0.5f, false).rx > 0f)
    }

    @Test
    fun maskLinearKeepsEndAfterStart() {
        val m = MaskModel.linear(angle = -90f, start = 0.8f, end = 0.2f)
        assertTrue("end 跑到 start 前面：${m.start}..${m.end}", m.end > m.start)
        assertTrue("角度没归一化：${m.angle}", m.angle in 0f..360f)
        // 自检：正常输入不许被改
        val ok = MaskModel.linear(90f, 0.2f, 0.8f)
        assertEquals(0.2f, ok.start, 1e-6f)
        assertEquals(0.8f, ok.end, 1e-6f)
        assertEquals(90f, ok.angle, 1e-6f)
    }

    @Test
    fun maskRemoveAndReplaceAreIndexSafe() {
        val masks = listOf(
            MaskModel.create(MaskModel.Kind.LINEAR),
            MaskModel.create(MaskModel.Kind.RADIAL),
        )
        assertEquals(1, MaskModel.remove(masks, 0).size)
        assertEquals(2, MaskModel.remove(masks, 9).size)
        assertEquals(2, MaskModel.replace(masks, 9, MaskModel.create(MaskModel.Kind.RADIAL)).size)
        // 自检：替换只影响那一个（用 shell 造一个真中性的壳来对比）
        val replaced = MaskModel.replace(masks, 1, MaskModel.shell(MaskModel.Kind.RADIAL))
        assertFalse("第 0 个被改成中性了", replaced[0].adjust.isNeutral)
        assertTrue("替换目标没变成中性", replaced[1].adjust.isNeutral)
    }

    @Test
    fun newMaskIsVisibleOnScreenImmediately() {
        // 产品级判据：新建的蒙版必须**不是中性**。
        // `LocalMaskField` 会把四项全 0 的蒙版直接丢掉（见 LocalMask.kt 类注释），
        // 于是"界面 4 个蒙版、画面一点没变、拖滑杆才有反应"——用户会以为加蒙版的按钮坏了。
        val kinds = MaskModel.Kind.entries
        for (kind in kinds) {
            val fresh = MaskModel.create(kind)
            assertFalse("新建的 ${kind.label} 蒙版是中性，加完画面不会有任何变化", fresh.adjust.isNeutral)
            assertEquals("新建蒙版没被算进生效数", 1, MaskModel.activeCount(listOf(fresh)))
        }
        // 自检：shell 才是那个中性壳，两种语义不许混
        assertTrue(MaskModel.shell(MaskModel.Kind.RADIAL).adjust.isNeutral)
        assertEquals(0, MaskModel.activeCount(listOf(MaskModel.shell(MaskModel.Kind.LINEAR))))
    }

    @Test
    fun maskActiveCountIgnoresNeutralAdjust() {
        // 四项全 0 的蒙版引擎会丢掉，角标就不该把它算成"已动 1 项"
        val neutral = MaskModel.shell(MaskModel.Kind.RADIAL)
        assertEquals(0, MaskModel.activeCount(listOf(neutral)))
        val active = MaskModel.withAdjust(neutral, LocalAdjust(exposure = 0.3f))
        assertEquals(1, MaskModel.activeCount(listOf(active)))
        // 自检：摘要要说清是 0 个
        assertTrue(MaskModel.summary(emptyList()).contains("还没有"))
    }

    // ---- ProOps 与配方往返 ----------------------------------------------------

    @Test
    fun proOpsNeutralFlagDrivesThePipelineGuard() {
        assertTrue(ProOps().isNeutral)
        assertEquals(0, ProOps().activeCount)
        val withCurve = ProOps(
            curves = CurveChannels.withPoints(
                ToneCurves(),
                CurveChannels.MASTER,
                CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.7f).first,
            ),
        )
        assertFalse(withCurve.isNeutral)
        assertEquals(1, withCurve.activeCount)
    }

    @Test
    fun proOpsRoundTripsThroughRecipeV3() {
        val pro = ProOps(
            curves = CurveChannels.withPoints(
                ToneCurves(),
                CurveChannels.MASTER,
                CurvePoints.add(CurvePoints.IDENTITY, 0.4f, 0.3f).first,
            ),
            bands = HslModel.withValue(HslBands.NONE, HslBandKind.GREEN, HslChannel.SAT, -0.3f),
            masks = listOf(
                MaskModel.withAdjust(
                    MaskModel.create(MaskModel.Kind.RADIAL),
                    LocalAdjust(exposure = 0.4f, temperature = -0.2f),
                ),
            ),
        )
        val base = YishunRecipe(lookId = "daylight", intensity = 0.6f, tempShift = 0.05f)
        val recipe = pro.applyTo(base)
        assertEquals(3, recipe.v)
        // v2 的 14 个字段一个都不许动
        assertEquals(0.05f, recipe.tempShift, 1e-6f)
        assertEquals(0.6f, recipe.intensity, 1e-6f)
        assertEquals("daylight", recipe.lookId)

        val back = ProOps.of(recipe)
        assertEquals(3, CurveChannels.points(back.curves, CurveChannels.MASTER).size)
        assertEquals(-0.3f, HslModel.band(back.bands, HslBandKind.GREEN).sat, 1e-6f)
        assertEquals(1, back.masks.size)
        assertEquals(0.4f, back.masks[0].adjust.exposureC, 1e-6f)
    }

    @Test
    fun v2RecipeRestoresToNeutralProOps() {
        // v2 配方（三个字段全 null）必须还原成中性：不能把上一张图残留的曲线留着
        val v2 = YishunRecipe(lookId = "portrait", intensity = 0.5f)
        assertTrue("v2 配方还原出了非中性 ProOps", ProOps.of(v2).isNeutral)
        // 自检：写了一组算子的配方不许被当成中性
        val v3 = ProOps(
            bands = HslModel.withValue(HslBands.NONE, HslBandKind.RED, HslChannel.HUE, 0.1f),
        ).applyTo(v2)
        assertFalse(ProOps.of(v3).isNeutral)
    }

    @Test
    fun neutralProDoesNotAddKeysToRecipe() {
        val recipe = ProOps.NONE.applyTo(YishunRecipe(lookId = "daylight", intensity = 0.5f))
        assertNull(recipe.curve)
        assertNull(recipe.hsl)
        assertNull(recipe.masks)
        // 自检：这一条真的有东西可写时才该写出键
        val written = ProOps(
            masks = listOf(MaskModel.create(MaskModel.Kind.LINEAR)),
        ).applyTo(recipe)
        assertNotNull(written.masks)
    }

    // ---- 自定义配方 -----------------------------------------------------------

    @Test
    fun customLookRoundTripsEveryField() {
        val shifts = Shifts(temp = 0.1f, contrast = -0.05f, sat = 0.2f, shadow = 0.03f, fade = 0.01f, highlight = 0.02f, soft = 0.04f)
        val pro = ProOps(
            bands = HslModel.withValue(HslBands.NONE, HslBandKind.BLUE, HslChannel.LUM, -0.25f),
            masks = listOf(
                MaskModel.withAdjust(
                    MaskModel.create(MaskModel.Kind.RADIAL),
                    LocalAdjust(exposure = 0.3f),
                ),
            ),
        )
        val look = buildCustomLook(
            name = "  我的黄昏  ",
            baseLookId = "daylight",
            intensity = 0.72f,
            shifts = shifts,
            pro = pro,
        )
        assertEquals("名字没 trim", "我的黄昏", look.name)
        assertTrue("id 前缀不对：${look.id}", look.id.startsWith(CustomLook.ID_PREFIX))
        assertEquals(0.72f, look.intensity, 1e-6f)
        assertEquals(0.1f, look.temp, 1e-6f)

        val applied = look.toApplied()
        assertNotNull("基础配方在库里却拆不回来", applied)
        assertEquals("daylight", applied!!.lookId)
        assertEquals(0.72f, applied.intensity, 1e-6f)
        assertEquals(0.1f, applied.shifts.temp, 1e-6f)
        assertEquals(-0.25f, HslModel.band(applied.pro.bands, HslBandKind.BLUE).lum, 1e-6f)
        assertEquals(1, applied.pro.masks.size)
        assertEquals("我的黄昏", applied.name)
    }

    @Test
    fun customLookWithUnknownBaseIsReportedNotSilentlySubstituted() {
        // 基础配方不在库里时返回 null，而不是拿 daylight 顶替：后者会让颜色全变而名字仍叫"我的 xx"
        val orphan = buildCustomLook(
            name = "孤儿",
            baseLookId = "no_such_look_id",
            intensity = 0.5f,
            shifts = Shifts.None,
            pro = ProOps(),
        )
        assertNull(orphan.toApplied())
        // 自检：正常基础配方必须拆得回来（证明上面的 null 不是所有输入都 null）
        assertNotNull(
            buildCustomLook("正常", "portrait", 0.5f, Shifts.None, ProOps()).toApplied(),
        )
    }

    @Test
    fun blankNameFallsBackToReadableDefault() {
        val look = buildCustomLook("   ", "daylight", 0.5f, Shifts.None, ProOps())
        assertEquals(defaultRecipeName("daylight"), look.name)
        assertTrue("默认名不可读：${look.name}", look.name.isNotBlank())
        // 自检：正常名字不许被默认名替换
        assertEquals("黄昏", buildCustomLook("黄昏", "daylight", 0.5f, Shifts.None, ProOps()).name)
    }

    @Test
    fun recipeBadgeDescribesWhatIsActuallyInTheLook() {
        val plain = buildCustomLook("纯预设", "daylight", 0.5f, Shifts.None, ProOps())
        assertEquals("仅预设", recipeBadge(plain))
        val withPro = buildCustomLook(
            "带算子",
            "daylight",
            0.5f,
            Shifts.None,
            ProOps(
                curves = CurveChannels.withPoints(
                    ToneCurves(),
                    CurveChannels.MASTER,
                    CurvePoints.add(CurvePoints.IDENTITY, 0.5f, 0.6f).first,
                ),
                masks = listOf(MaskModel.create(MaskModel.Kind.RADIAL)),
            ),
        )
        val badge = recipeBadge(withPro)
        assertTrue("角标没提曲线：$badge", "曲线" in badge)
        assertTrue("角标没提蒙版：$badge", "1蒙版" in badge)
        assertFalse("没有分色却说有：$badge", "分色" in badge)
    }

    @Test
    fun summaryLineCountsOnlyRealShifts() {
        val none = summaryLine("日光", 0.5f, Shifts.None, ProOps())
        assertTrue("全 0 的微调被报成有：$none", "微调" !in none)
        val two = summaryLine("日光", 0.5f, Shifts(temp = 0.1f, sat = 0.2f), ProOps())
        assertTrue("两项微调没被数到：$two", "微调 2 项" in two)
        // 自检：强度百分数必须真的出现在里面
        assertTrue(two, "强度 50%" in two)
    }

    @Test
    fun masksCapSurvivesTheRecipeBoundary() {
        // 6 个蒙版进 [ProOps]，出去只能是 4 个（引擎上限），再经配方往返仍然是 4 个
        val many = (0 until 6).map {
            MaskModel.withAdjust(
                LocalMask(MaskModel.radial(cx = 0.1f * it, cy = 0.5f, rx = 0.2f, ry = 0.2f, feather = 0.4f, inverted = false)),
                LocalAdjust(exposure = 0.1f),
            )
        }
        val pro = ProOps().withMasks(many)
        assertEquals(4, pro.masks.size)
        val recipe = pro.applyTo(YishunRecipe(lookId = "daylight", intensity = 0.5f))
        assertEquals(4, RecipePro.masksOf(recipe).size)
        // 自检：3 个以内不许被削
        assertEquals(3, ProOps().withMasks(many.take(3)).masks.size)
    }

    @Test
    fun maskShapesRoundTripThroughRecipeKinds() {
        // 两种形状各存一条：只测一种会漏掉 kind 分派（`RecipeMask.toLocalMask` 里有两条臂）
        val pro = ProOps(
            masks = listOf(
                MaskModel.withAdjust(
                    LinearMask(angle = 45f, start = 0.1f, end = 0.9f).let { LocalMask(it) },
                    LocalAdjust(contrast = 0.4f),
                ),
                MaskModel.withAdjust(
                    LocalMask(MaskModel.radial(0.3f, 0.7f, 0.2f, 0.1f, 0.8f, true)),
                    LocalAdjust(saturation = -0.5f),
                ),
            ),
        )
        val back = ProOps.of(pro.applyTo(YishunRecipe(lookId = "daylight", intensity = 0.5f)))
        assertEquals(2, back.masks.size)
        val linear = back.masks[0].shape as LinearMask
        assertEquals(45f, linear.angle, 1e-6f)
        val radial = back.masks[1].shape as RadialMask
        assertTrue("反向没存住", radial.inverted)
        assertTrue("羽化没存住", abs(radial.feather - 0.8f) < 1e-6f)
        // 自检：两条的调整量不许串味
        assertEquals(0.4f, back.masks[0].adjust.contrastC, 1e-6f)
        assertEquals(-0.5f, back.masks[1].adjust.saturationC, 1e-6f)
    }

    // ---- 旧配方的降级 ---------------------------------------------------------

    @Test
    fun corruptedProFieldsDegradeInsteadOfCrashing() {
        // 配方来自外部（EXIF / 导入），三种坏形状都必须退化成中性而不是抛
        val badCurve = YishunRecipe(
            lookId = "daylight",
            intensity = 0.5f,
            curve = com.suipai.core.engine.RecipeCurves(master = listOf(0f, 0f, 1f)), // 奇数长度
        )
        assertTrue(ProOps.of(badCurve).curves.isEmpty)

        val shortHsl = YishunRecipe(
            lookId = "daylight",
            intensity = 0.5f,
            hsl = RecipeHsl(bands = listOf(0.1f, 0.2f)), // 长度 < 24
        )
        assertTrue(ProOps.of(shortHsl).bands.isNeutral)

        val badMask = YishunRecipe(
            lookId = "daylight",
            intensity = 0.5f,
            masks = listOf(RecipeMask(kind = "spiral", p = listOf(1f, 2f, 3f))), // 不认的 kind
        )
        assertTrue("不认识的 kind 被当成了蒙版", ProOps.of(badMask).masks.isEmpty())
        // 自检：合法输入不许被当坏的
        val good = ProOps.of(
            YishunRecipe(
                lookId = "daylight",
                intensity = 0.5f,
                curve = com.suipai.core.engine.RecipeCurves(master = listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f)),
            ),
        )
        assertFalse(good.curves.isEmpty)
    }
}
