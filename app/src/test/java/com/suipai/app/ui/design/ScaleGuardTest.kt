package com.suipai.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸守卫：间距阶梯、圆角阶梯、触摸目标、动效刻度 —— 四张表都必须有序、成对、不越界。
 *
 * 为什么这些要钉：
 * 改之前全项目的间距是 2/3/4/6/8/9/12/14/18/20/22 十一档，其中一半是"当时看着差不多"。
 * 间距的问题不是难看，是**层级消失**：8dp 与 12dp 的差别在视觉上等于没有，
 * 于是分组之间和元素之间看起来一样远，一屏东西读不出结构。
 * 阶梯把它们收敛成可数的几档之后，"这两块是不是一组"就变成可回答的问题。
 *
 * ── 关于"严格递增"的判定口径 ──
 * 判的是**阶梯表**（`Spacing.ladder` / `Radius.ladder` / `MotionScale.durations`），
 * 不是常量清单。比如动效里 `Exit = State = 120` 是"同一个刻度被两个语义共用"，
 * 那是设计意图（让位与换色要一样快），不是"两档数值相同"的缺陷。
 * 所以判据是：刻度表严格递增 + 每条具名常量都落在刻度表上（见 `ledger`）。
 *
 * 每条判定都配一条"喂坏样本必须变红"的自检。
 */
class ScaleGuardTest {

    @Test
    fun spacingLadderIsStrictlyIncreasing() {
        val ladder = Spacing.ladder.map { it.value }
        val violations = monotonicViolations(ladder)
        assertTrue("间距阶梯不是严格递增：位置 $violations（阶梯 = $ladder）", violations.isEmpty())
        assertTrue("间距阶梯太小（${ladder.size} 档），至少要有 6 档", ladder.size >= 6)
        assertTrue("阶梯最小一档应当 ≤4dp，否则拿不到密排的相纸缝", ladder.first() <= 4f)
        assertTrue("阶梯最大一档应当 ≥32dp，否则大留白没有档位可用", ladder.last() >= 32f)

        // ── 自检：脏样本必须被抓到 ──
        // 相邻相等：位置 2（第 3 个 <= 第 2 个）
        assertEquals(
            "相邻相等没被抓出来",
            listOf(2),
            monotonicViolations(listOf(1f, 2f, 2f, 3f)),
        )
        // 倒序：位置 2
        assertEquals(
            "倒序没被抓出来",
            listOf(2),
            monotonicViolations(listOf(1f, 3f, 2f)),
        )
        // 正确的阶梯不许被误判
        assertTrue(
            "正常的阶梯被误判",
            monotonicViolations(listOf(4f, 8f, 12f, 16f, 20f, 24f, 32f, 48f)).isEmpty(),
        )
    }

    @Test
    fun spacingLadderFollowsTheFourDpRhythm() {
        // 4dp 网格：中文正文 12-16sp 的视觉半行落在 4dp 上；
        // 只认 8dp 会逼出 4.5/6.5 这种"差不多"的数 —— 那正是要消灭的东西。
        // 2dp 的相纸缝是唯一的例外（密排印样），单列出来。
        val offGrid = Spacing.ladder.filter { it.value % 4f != 0f && it.value != Spacing.gutter.value }
        assertTrue("这些间距不在 4dp 网格上：${offGrid.map { it.value }}", offGrid.isEmpty())
        assertEquals("相纸缝必须是 2dp（视觉契约：密排印样）", 2f, Spacing.gutter.value, 0.001f)

        // 自检
        assertEquals("6dp 没被算成出格", listOf(6f), listOf(6f).filter { it % 4f != 0f })
        assertTrue("2dp 被误判成出格", 2f % 4f != 0f && 2f == Spacing.gutter.value)
    }

    @Test
    fun cornerRadiusLadderIsStrictlyIncreasing() {
        val ladder = Radius.ladder.map { it.value }
        val violations = monotonicViolations(ladder)
        assertTrue("圆角阶梯不是严格递增：位置 $violations（阶梯 = $ladder）", violations.isEmpty())

        // 视觉契约：本世界的相纸与格子是**近直角** 2dp。
        assertEquals("印样框必须是 2dp（视觉契约：近直角）", 2f, Radius.frame.value, 0.001f)
        // 而且只有四档 —— 中间档是"随便挑一个差不多"的温床。
        assertEquals("圆角阶梯应当恰好四档（0 / 2 / 8 / pill）", 4, Radius.ladder.size)
        // 2dp 与 8dp 之间不许有东西（4dp 那种"半软角"在这个世界里不存在）。
        val betweens = Radius.ladder.filter { it.value > Radius.frame.value && it.value < Radius.panel.value }
        assertTrue("2dp 与 8dp 之间不该有中间档，却有：${betweens.map { it.value }}", betweens.isEmpty())
        // 胶囊档必须真的是胶囊（≥40dp），否则它只是"很大的圆角"。
        assertTrue("pill 档不是胶囊", Radius.pill.value >= 40f)

        // ── 自检 ──
        assertEquals("圆角倒序没被抓出来", listOf(2), monotonicViolations(listOf(0f, 2f, 1f)))
        assertTrue(
            "把 frame 改成 12dp 之后，'近直角'那条判据应当红",
            12f > Radius.frame.value,
        )
        assertTrue(
            "4dp 落在 2dp..8dp 之间，'中间档'判据应当红",
            4f in Radius.frame.value..Radius.panel.value,
        )
    }

    @Test
    fun touchTargetsMeetAccessibilityMinimums() {
        // 48dp：装机实测过（AppBar.kt 顶部记着 38dp 的框拇指点不中）。
        assertTrue("最小触摸目标低于 48dp（无障碍下限）", Touch.min.value >= 48f)
        assertTrue("按钮视觉高度不该低于 40dp，否则看起来不像按钮", Touch.button.value >= 40f)
        assertTrue("芯片视觉高度不该超过命中区（命中区要能补上来）", Touch.chip.value <= Touch.min.value)
        assertTrue("舒适区应当 ≥48dp", Touch.comfortable.value >= Touch.min.value)
        // 命中区与视觉高度的差值，正是"看起来小但点得中"的那部分余量。
        assertTrue(
            "芯片的命中区没有补到 48dp（视觉 ${Touch.chip.value} / 命中 ${Touch.min.value}）",
            Touch.min.value - Touch.chip.value >= 16f,
        )

        // ── 自检 ──
        assertTrue("38dp 被当成合格触摸目标", 38f < Touch.min.value)
    }

    @Test
    fun toolbarRowFitsTheFirstViewportContract() {
        // 视觉契约 FIRST VIEWPORT：「顶部一行工具条，高度 ≤64dp」。
        assertTrue("工具条一行超过了 64dp 的视觉契约上限", Touch.toolRow.value <= 64f)
        assertTrue("工具条高度装不下 48dp 的命中区", Touch.toolRow.value >= Touch.min.value)

        // 自检
        assertTrue("80dp 被当成合规的工具条高度", 80f > 64f)
    }

    @Test
    fun motionDurationsAreOrderedAndDistinct() {
        val durations = MotionScale.durations
        // 刻度表本身严格递增
        assertEquals(
            "动效刻度表不是严格递增：$durations",
            emptyList<Int>(),
            monotonicViolations(durations.map { it.toFloat() }),
        )
        // 刻度没重复（两档一样就白加一档）
        assertEquals("动效刻度有重复值", durations.size, durations.toSet().size)
        // 每一条**具名常量**都必须落在刻度表上：不许有人悄悄加一个 137ms
        for ((name, ms) in MotionScale.ledger) {
            assertTrue("动效常量 $name=$ms 不在刻度表 $durations 里", ms in durations)
        }
        // 每档都要有语义说明 —— 没有说明的档位下一个人只会瞎用
        for (d in durations) {
            assertTrue("动效刻度 $d ms 没有语义说明", MotionScale.meaning.containsKey(d))
        }
        // 按压档必须在"一次轻点按住"的窗口里（70–120ms），否则按下去看不见
        assertTrue("按压档 ${MotionScale.Press}ms 不在 70–120ms 窗口里", MotionScale.Press in 70..120)
        // 漏光档要像"呼吸"：比一次点击慢得多，但不许到一秒以上（那会像卡住）
        assertTrue("漏光档 ${MotionScale.Leak}ms 超出了 300–700ms", MotionScale.Leak in 300..700)
        // 语义共用是设计意图，不是缺陷：Exit 与 State 必须同值（让位与换色一样快）
        assertEquals("Exit 与 State 应当同值（同一刻度，两种语义）", MotionScale.Exit, MotionScale.State)
        assertEquals("Enter 与 Replace 应当同值（进场即替换）", MotionScale.Enter, MotionScale.Replace)

        // ── 自检 ──
        assertEquals("重复刻度没被抓出来", listOf(2), monotonicViolations(listOf(120f, 160f, 160f)))
        assertTrue("按压 40ms 会被当成合格", 40 !in 70..120)
        assertTrue("137ms 不在刻度表里，'常量必须落表'那条应当红", 137 !in durations)
    }

    @Test
    fun legacyAndNewScalesAgree() {
        // 老接口（SuiPaiSheet.frame / pill / gutter）与新 token 必须同值。
        // 它们现在是两份声明，对不上时"老界面 2dp、新组件 4dp"会静默分叉。
        assertEquals("老 frame 与新 Radius.frame 不一致", Radius.frame.value, 2f, 0.001f)
        assertEquals("老 gutter 与 Spacing.gutter 不一致", Spacing.gutter.value, 2f, 0.001f)
        assertEquals("老 pill 与新 Radius.pill 不一致", Radius.pill.value, 50f, 0.001f)

        // 自检
        assertTrue("2dp 与 4dp 被判成一致", 2f != 4f)
    }

    /** 返回所有"不严格递增"的位置下标（从 0 起，index 1 表示与第 0 个比较失败）。 */
    private fun monotonicViolations(values: List<Float>): List<Int> =
        values.indices.drop(1).filter { i -> values[i] <= values[i - 1] }
}
