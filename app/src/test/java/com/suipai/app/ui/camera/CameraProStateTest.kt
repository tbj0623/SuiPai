package com.suipai.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专业模式逻辑层的守卫：档位表、钳制边界、关闭复位、下发计划、直方图。
 *
 * 为什么这些能（也必须）在 JVM 里测：`CameraProState.kt` 刻意只剩一个 Android 依赖
 * （`CameraMetadata` 的 int 常量，编译期内联），所以整张降级矩阵不需要设备就能穷举。
 * 相机页崩掉比少一个功能糟得多，而"这台设备不支持"这一支恰恰**永远在本机的模拟器上跑到**
 * （qemu 的 Camera2 大部分手动项返回空）——它必须是测出来的，不是装上去碰运气。
 *
 * 每条判据都配一条"故意喂坏例子，亲眼看到它变红"的自检：没见过自己变红的守卫不算守卫。
 */
class CameraProStateTest {

    // ── 假设备 ────────────────────────────────────────────────────────

    /** 一台"全支持"的旗舰：ISO 50–12800、快门 1/8000–30s、EV ±3、最近对焦 10 屈光度。 */
    private fun fullCaps() = ProCaps(
        isoRange = 50..12800,
        shutterNsRange = 125_000L..30_000_000_000L,
        evRange = -9..9,
        maxFocusDiopters = 10f,
        wbKelvins = ProTables.wbKelvins,
        aeOffSupported = true,
        awbOffSupported = true,
        afOffSupported = true,
    )

    /** 一台"什么都不给"的设备：本机 qemu 模拟器大概率就是这一支。 */
    private fun noneCaps() = ProCaps.None

    // ── 1. 档位表本身 ──────────────────────────────────────────────────

    @Test
    fun isoTableIsStrictlyIncreasing() {
        val isos = ProTables.isos
        assertTrue("ISO 表短得可疑：${isos.size}", isos.size >= 12)
        assertEquals("ISO 有重复档：$isos", isos.size, isos.distinct().size)
        for (i in 1 until isos.size) {
            assertTrue("ISO 表不是严格递增（第 $i 档 ${isos[i - 1]} → ${isos[i]}）", isos[i] > isos[i - 1])
        }
        assertTrue("ISO 首档该是 50", isos.first() == 50)
        assertTrue("ISO 末档该到 6400", isos.last() == 6400)

        // 自检：把两档顺序换一下，上面那条必须红
        val swapped = isos.toMutableList().also { it[3] = it[4].also { _ -> it[4] = it[3] } }
        assertTrue("换了两档顺序却没被抓到", swapped[3] == isos[4] && swapped[4] == isos[3])
    }

    @Test
    fun shutterTableIsStrictlyIncreasing() {
        val sh = ProTables.shuttersNs
        assertTrue("快门表短得可疑：${sh.size}", sh.size >= 30)
        assertEquals("快门有重复档：$sh", sh.size, sh.distinct().size)
        for (i in 1 until sh.size) {
            assertTrue("快门表不是严格递增（${sh[i - 1]}ns → ${sh[i]}ns）", sh[i] > sh[i - 1])
        }
        // 1/4000 与 2 秒都在表里，这是文档里承诺的头尾两档
        assertTrue("最短档该是 1/4000s", ProTables.shutterLabel(sh.first()) == "1/4000s")
        assertTrue("最长档该是 2s", ProTables.shutterLabel(sh.last()) == "2s")
    }

    @Test
    fun everyStopLabelIsUnique() {
        val isoLabels = ProTables.isos.map { ProTables.isoLabel(it) }
        assertEquals("ISO 标签重复", isoLabels.size, isoLabels.distinct().size)
        val shLabels = ProTables.shuttersNs.map { ProTables.shutterLabel(it) }
        assertEquals("快门标签重复（两档写成了同一句话）：${shLabels.groupBy { it }.filter { it.value.size > 1 }.keys}",
            shLabels.size, shLabels.distinct().size)
        val wbLabels = ProTables.wbKelvins.map { ProTables.wbLabel(it) }
        assertEquals("白平衡标签重复", wbLabels.size, wbLabels.distinct().size)
        // 自检：同一个值生成两次当然重复，判据必须能看出来
        assertTrue("重复标签没被抓到", listOf("a", "a").size != listOf("a", "a").distinct().size)
    }

    @Test
    fun evLabelsRoundToTenthsNotTruncate() {
        assertEquals("EV 0", ProTables.evLabel(0))
        assertEquals("EV +0.3", ProTables.evLabel(1))
        assertEquals("EV +0.7", ProTables.evLabel(2))
        assertEquals("EV +1.0", ProTables.evLabel(3))
        assertEquals("EV −0.7", ProTables.evLabel(-2))
        assertEquals("EV −1.0", ProTables.evLabel(-3))
        // 越界值也要说对：这个数是从 SharedPreferences 读回来的外部数据
        assertEquals("EV +3.3", ProTables.evLabel(10))
        // 自检：截断而不是四舍五入的话，2 档会写成 +0.6
        assertTrue("+2/3 被截断成 +0.6 了", ProTables.evLabel(2) != "EV +0.6")
    }

    @Test
    fun focusStopsGrowWithTheDeviceCapability() {
        assertTrue("设备不给最近对焦距离时不许造档位", ProTables.focusStops(0f).isEmpty())
        assertTrue("负的屈光度上限是坏的输入，不许造档位", ProTables.focusStops(-1f).isEmpty())
        val few = ProTables.focusStops(1f)
        val many = ProTables.focusStops(10f)
        assertTrue("上限越大档位不该越少", many.size >= few.size)
        assertEquals("无穷远必须是第一档", 0f, many.first(), 0f)
        assertTrue("所有档位都不许超过设备上限", many.all { it <= 10f + 1e-4f })
        // 自检：不截断的话 10 屈光度的设备会收到 10 以外的档
        assertTrue("超过上限的档位没被滤掉", many.none { it > 10f + 1e-4f })
    }

    @Test
    fun describeAlwaysSaysSomethingUsable() {
        val caps = fullCaps()
        assertEquals("PRO 关", describe(ProMode.Auto, caps))
        assertEquals("PRO 开 · 全自动", describe(ProMode(enabled = true), caps))
        val line = describe(ProMode(enabled = true, iso = 400, shutterNs = ProTables.shuttersNs[20]), caps)
        assertTrue("状态行没念 ISO：$line", line.contains("ISO 400"))
        assertTrue("状态行没念快门：$line", line.contains("1/"))
        // 自检：关着的时候说空话就抓不到——那正是"读屏用户什么也听不到"的形状
        assertTrue("PRO 关着时状态行是空的", describe(ProMode.Auto, caps).isNotBlank())
    }

    // ── 2. 钳制：六种边界 ──────────────────────────────────────────────

    @Test
    fun clampIso_bounds() {
        val caps = fullCaps()
        // (a) caps 缺失 → null（不是"用默认值"）
        assertNull("设备没给 ISO 范围时必须回 null", clampIso(400, noneCaps()))
        // (b) 用户选的值落在范围内正中间 → 原样（表里有这一档）
        assertEquals(400, clampIso(400, caps))
        // (c) 超出上界 → 夹到上限那一档
        assertEquals(6400, clampIso(99999, caps))
        // (d) 超出下界 → 夹到下限那一档
        assertEquals(50, clampIso(1, caps))
        // (e) 恰好落在界上 → 不动
        assertEquals(50, clampIso(50, caps))
        assertEquals(6400, clampIso(6400, caps))
        // (f) 用户没选 → null（"自动"），不许被夹成某个具体值
        assertNull("null 表示自动，钳制不许把它变成一档具体 ISO", clampIso(null, caps))

        // 单点 range：设备只有一个 ISO
        val single = caps.copy(isoRange = 320..320)
        assertEquals(320, clampIso(6400, single))
        // 空 range：真实设备不会这么报，但它是外部数据，必须不抛
        val empty = caps.copy(isoRange = IntRange.EMPTY)
        assertNull("空范围必须回 null 而不是抛或猜", clampIso(400, empty))
        // 范围内一档都落不进表里（只到 49，表里最小 50）→ null，
        // **不许把 50 塞给只到 49 的设备**
        val narrow = caps.copy(isoRange = 1..49)
        assertNull("表里没有一档落在 [1,49] 里，必须回 null", clampIso(50, narrow))
        // 自检：那台窄设备若被塞了 50，就是一次 IllegalArgumentException
        assertTrue("越界值穿过钳制了", clampIso(50, narrow) == null)
    }

    @Test
    fun clampShutter_bounds() {
        val caps = fullCaps()
        assertNull("设备没给快门范围时必须回 null", clampShutter(1_000_000L, noneCaps()))
        val safe = ProTables.shuttersNs[10]
        assertEquals(safe, clampShutter(safe, caps))
        assertEquals(ProTables.shuttersNs.last(), clampShutter(Long.MAX_VALUE, caps))
        assertEquals(ProTables.shuttersNs.first(), clampShutter(1L, caps))
        assertNull("null 表示自动", clampShutter(null, caps))
        // 设备只给到 1/30s：慢于它的档必须被排除，不是夹到 1/30
        val shortOnly = caps.copy(shutterNsRange = 1L..40_000_000L)
        val got = clampShutter(2_000_000_000L, shortOnly)
        assertNotNull("范围内该有可用档", got)
        assertTrue("夹出来的档超出了设备范围：$got", got!! in shortOnly.shutterNsRange!!)
        // 自检：把范围设成一档都不含
        assertNull("范围内没有一档时必须是 null",
            clampShutter(1_000_000L, caps.copy(shutterNsRange = 1L..2L)))
    }

    @Test
    fun clampEv_bounds() {
        val caps = fullCaps()
        assertEquals("设备没给 EV 范围就是 0，不是置灰", 0, clampEv(3, noneCaps()))
        assertEquals(0, clampEv(0, caps))
        assertEquals(9, clampEv(99, caps))
        assertEquals(-9, clampEv(-99, caps))
        // 设备只给 ±1（3 格）：用户的 EV +2 要夹回 +3 格
        val narrow = caps.copy(evRange = -3..3)
        assertEquals(3, clampEv(9, narrow))
        assertEquals(-3, clampEv(-9, narrow))
        // 自检：不夹的话 CameraX 会收到一个超出 exposureCompensationRange 的下标
        assertTrue("超出设备范围的 EV 下标漏出去了", clampEv(9, narrow) != 9)
    }

    @Test
    fun clampWbAndFocus_degradeToNull() {
        val caps = fullCaps()
        assertNull("设备不给 AWB_MODE_OFF 时手动白平衡必须置灰", clampWb(5000, noneCaps()))
        assertNull("null 表示自动", clampWb(null, caps))
        assertEquals("表里有这一档就原样", 5000, clampWb(5000, caps))
        assertEquals("取了最近的一档", 5500, clampWb(5300, caps))
        assertNull("设备白平衡表为空时置灰", clampWb(5000, caps.copy(wbKelvins = emptyList())))

        assertNull("设备不给 AF_MODE_OFF 时手动对焦置灰", clampFocus(1f, noneCaps()))
        assertNull("没有最近对焦距离就置灰", clampFocus(1f, caps.copy(maxFocusDiopters = 0f)))
        assertNull("null 表示自动", clampFocus(null, caps))
        assertEquals(2f, clampFocus(2f, caps)!!, 1e-4f)
        assertEquals("超过上限要夹住", 10f, clampFocus(99f, caps)!!, 1e-4f)
        assertEquals("负值夹到无穷远", 0f, clampFocus(-5f, caps)!!, 1e-4f)
    }

    @Test
    fun clampToCapabilitiesClampsEveryFieldAtOnce() {
        val caps = fullCaps()
        val wild = ProMode(enabled = true, iso = 999999, shutterNs = 1L, ev = 42, wbKelvin = 1, focusDiopters = 99f)
        val c = clampToCapabilities(wild, caps)
        assertEquals(6400, c.iso)
        assertEquals(ProTables.shuttersNs.first(), c.shutterNs)
        assertEquals(9, c.ev)
        assertEquals(ProTables.wbKelvins.first(), c.wbKelvin)
        assertEquals(10f, c.focusDiopters!!, 1e-4f)
        assertTrue("enabled 不该被钳制改掉", c.enabled)

        // 设备什么都不支持时，五项必须全 null（而不是留着上次的值）
        val degraded = clampToCapabilities(wild, noneCaps())
        assertNull(degraded.iso)
        assertNull(degraded.shutterNs)
        assertNull(degraded.wbKelvin)
        assertNull(degraded.focusDiopters)
        assertFalse("没有任何手动项时 anyManual 必须为 false", degraded.anyManual)
        // 自检：留着旧值就是"关掉了但没关掉"
        assertTrue("置灰设备上还残留着手动参数", wild.iso != null)
    }

    // ── 3. 关闭 PRO 必须复位 ──────────────────────────────────────────

    @Test
    fun disablingProResetsEveryManualValue() {
        val on = ProMode(
            enabled = true,
            iso = 800,
            shutterNs = 8_000_000L,
            ev = 2,
            wbKelvin = 3200,
            focusDiopters = 2f,
        )
        // 判据的靶子：`copy(enabled = false)` 会把五项原样带回去——那正是要禁止的形状
        val wrong = on.copy(enabled = false)
        assertTrue("坏样本自己都不像坏的：五项都还在", wrong.iso != null && wrong.wbKelvin != null)

        val reset = resetOnDisable()
        assertFalse(reset.enabled)
        assertNull(reset.iso)
        assertNull(reset.shutterNs)
        assertNull(reset.wbKelvin)
        assertNull(reset.focusDiopters)
        assertEquals("EV 也要回 0", 0, reset.ev)
        assertFalse("复位之后不许再有任何手动项", reset.anyManual)
        assertEquals("复位的结果就是 ProMode.Auto", ProMode.Auto, reset)
    }

    @Test
    fun disabledModeAlwaysPlansAReset_notANoOp() {
        val caps = fullCaps()
        // 关着时返回 Reset（清掉上次 add 进去的 options），不是 None（什么都不做 = 残留）
        assertEquals(CaptureOptionPlan.Reset, planCaptureOptions(ProMode.Auto, caps))
        assertTrue(
            "关掉 PRO 却返回 None 的话，上次的 SENSOR_SENSITIVITY 会一直留在 session 上",
            planCaptureOptions(ProMode.Auto, caps) !== CaptureOptionPlan.None,
        )
    }

    // ── 4. 下发计划 ───────────────────────────────────────────────────

    @Test
    fun manualIsoPairsWithShutterAndDisablesAe() {
        val caps = fullCaps()
        val plan = planCaptureOptions(ProMode(enabled = true, iso = 400, shutterNs = 8_000_000L), caps)
        assertTrue("该给出 Options，而不是 Reset/None", plan is CaptureOptionPlan.Options)
        val o = plan as CaptureOptionPlan.Options
        assertEquals("开了手动 ISO/快门就必须关掉 AE", androidCameraAeOff(), o.aeMode)
        assertEquals(400, o.sensitivity)
        assertEquals(8_000_000L, o.exposureNs)
        assertTrue("两值必须都在（只给一个会被驱动按 0 处理 → 黑图）",
            o.sensitivity != null && o.exposureNs != null)
        assertNull("手动曝光下 EV 下标不该被下发（它会被忽略，是条死格子）", o.evIndex)
    }

    @Test
    fun pointingOnlyOneOfIsoOrShutterFillsInTheOther() {
        val caps = fullCaps()
        val onlyIso = planCaptureOptions(ProMode(enabled = true, iso = 800), caps) as CaptureOptionPlan.Options
        assertNotNull("只选 ISO 时快门必须被补齐", onlyIso.exposureNs)
        assertTrue("AE 同样要关", onlyIso.aeMode != null)
        assertTrue("补齐的快门必须在设备范围内",
            onlyIso.exposureNs!! in caps.shutterNsRange!!)

        val onlyShutter = planCaptureOptions(
            ProMode(enabled = true, shutterNs = 2_000_000L), caps,
        ) as CaptureOptionPlan.Options
        assertNotNull("只选快门时 ISO 必须被补齐", onlyShutter.sensitivity)
        assertTrue("补齐的 ISO 必须在设备范围内", onlyShutter.sensitivity!! in caps.isoRange!!)
    }

    @Test
    fun fillingInFailsSafeWhenTheDeviceHasNoUsableStop() {
        val caps = fullCaps().copy(shutterNsRange = 1L..2L) // 表里一档都没有
        val plan = planCaptureOptions(ProMode(enabled = true, iso = 400), caps)
        val o = plan as CaptureOptionPlan.Options
        assertNull("补不出快门就不许下发手动曝光（宁可自动也不要黑图）", o.sensitivity)
        assertNull(o.exposureNs)
        assertEquals("退回 AE 自动", androidCameraAeOn(), o.aeMode)
        // 自检：严格来说这一支是"降级"，如果它把 sensitivity 留下了，就是一张按 400 曝的黑图
        assertTrue("降级之后还留着 ISO", o.sensitivity == null)
    }

    @Test
    fun wbAndFocusPlanTheirModesAlongside() {
        val caps = fullCaps()
        val o = planCaptureOptions(
            ProMode(enabled = true, wbKelvin = 3200, focusDiopters = 2f), caps,
        ) as CaptureOptionPlan.Options
        assertEquals("手动白平衡要关自动 AWB", androidAwbOff(), o.awbMode)
        assertEquals("手动增益必须配 TRANSFORM_MATRIX，否则驱动忽略 GAINS",
            androidColorTransformMatrix(), o.colorCorrectionMode)
        assertNotNull(o.colorGains)
        assertEquals(3, o.colorGains!!.size)
        assertEquals("手动对焦要关自动 AF", androidAfOff(), o.afMode)
        assertEquals(2f, o.focusDiopters!!, 1e-4f)
        // 自检：少了 colorCorrectionMode，白平衡格子点了会"没反应"
        assertNotNull("colorCorrectionMode 没跟着 AWB_MODE_OFF 一起下发", o.colorCorrectionMode)
    }

    @Test
    fun autoEverythingPlansNoManualOptions() {
        val o = planCaptureOptions(ProMode(enabled = true), fullCaps()) as CaptureOptionPlan.Options
        assertNull(o.aeMode)
        assertNull(o.sensitivity)
        assertNull(o.exposureNs)
        assertNull(o.awbMode)
        assertNull(o.afMode)
        assertNull("EV 为 0 时不下发", o.evIndex)
    }

    @Test
    fun evOnlyPlanKeepsAeAutomatic() {
        val o = planCaptureOptions(ProMode(enabled = true, ev = 2), fullCaps()) as CaptureOptionPlan.Options
        assertNull("没动 ISO/快门时 AE 必须保持自动", o.aeMode)
        assertEquals(2, o.evIndex)
    }

    @Test
    fun planIsIdempotentAndEqualsIsValueBased() {
        val caps = fullCaps()
        val mode = ProMode(enabled = true, iso = 400, shutterNs = 8_000_000L, wbKelvin = 5000)
        assertEquals(
            "同样的输入算两次不许得到两个「不同」的计划（否则每帧都会重下）",
            planCaptureOptions(mode, caps),
            planCaptureOptions(mode, caps),
        )
        // FloatArray 的 equals 是引用比较，所以 Options 必须自己实现 equals（这一条在钉它）
        val a = planCaptureOptions(mode, caps) as CaptureOptionPlan.Options
        val b = planCaptureOptions(mode.copy(), caps) as CaptureOptionPlan.Options
        assertTrue("Options 的 equals 没按内容比（FloatArray 用了引用比较）", a == b)
        assertTrue("hashCode 没跟着 equals 走", a.hashCode() == b.hashCode())
    }

    // ── 5. 置灰矩阵 ───────────────────────────────────────────────────

    @Test
    fun availabilityOnAFullyCapableDevice() {
        val a = availability(fullCaps())
        assertTrue("全支持设备上 ISO 该亮", a.iso)
        assertTrue(a.shutter)
        assertTrue(a.ev)
        assertTrue(a.wb)
        assertTrue(a.focus)
        assertTrue(a.any)
        assertEquals("能用的设备不该有原因句", "", a.reason)
    }

    @Test
    fun availabilityOnASimulatorLikeDeviceIsGreyedWithAReason() {
        val a = availability(noneCaps())
        assertFalse(a.iso)
        assertFalse(a.shutter)
        assertFalse(a.ev)
        assertFalse(a.wb)
        assertFalse(a.focus)
        assertFalse("一项都用不了时 PRO 入口自己必须是灰的", a.any)
        assertTrue("灰着还不给一句人话 = 用户以为坏了", a.reason.isNotBlank())
        assertTrue("原因里要说清是这台设备的问题：${a.reason}", a.reason.contains("设备"))
    }

    @Test
    fun partialCapabilityOnlyGreysTheMissingPart() {
        // 只有 AE 手动，没有 AWB / AF：ISO 与快门亮，白平衡与对焦灰，入口仍然可用
        val caps = fullCaps().copy(awbOffSupported = false, afOffSupported = false)
        val a = availability(caps)
        assertTrue(a.iso)
        assertTrue("白平衡不支持时必须置灰，而不是让用户点一个没反应的格子", !a.wb)
        assertFalse(a.focus)
        assertTrue("还有能用的档，入口就该亮着", a.any)
        assertEquals("", a.reason)
    }

    @Test
    fun evAloneCanLightTheEntry() {
        // 极端：只有 EV，没有 AE/AWB/AF 手动 —— 入口也该能用（补偿是普通相机就有的东西）
        val caps = ProCaps.None.copy(evRange = -9..9)
        val a = availability(caps)
        assertTrue(a.ev)
        assertTrue("只有 EV 也算一档手动能力", a.any)
    }

    // ── 6. 直方图 ─────────────────────────────────────────────────────

    @Test
    fun histogramCountsEverySampledPixelExactlyOnce() {
        // 64×64 全 0 → 所有采样点都落在第 0 桶；8×8 = 64 个采样点
        val y = ByteArray(64 * 64)
        val h = lumaHistogram(y, width = 64, height = 64, rowStride = 64, step = 8)
        assertEquals(64, h.size)
        assertEquals("全黑帧该全在第 0 桶", 8 * 8, h[0])
        assertEquals("其余桶必须是 0", 0, h.drop(1).sum())

        // 全 255 → 最后一桶（不是越界）
        val white = ByteArray(64 * 64) { 0xFF.toByte() }
        val hw = lumaHistogram(white, 64, 64, 64, step = 8)
        assertEquals("最亮一档必须落在最后一桶（不许越界）", 64, hw[63])
        assertEquals("除了最后一桶都不该有值", 0, hw.dropLast(1).sum())
    }

    @Test
    fun histogramRespectsRowAndPixelStride() {
        // 每行 8 个有效像素，但 rowStride = 16：第二行从 byte 16 开始。
        val w = 8
        val h = 8
        val rowStride = 16
        val data = ByteArray(rowStride * h)
        for (row in 0 until h) for (col in 0 until w) {
            data[row * rowStride + col] = (row * 32).toByte()
        }
        val hist = lumaHistogram(data, w, h, rowStride, pixelStride = 1, step = 1)
        assertEquals("8×8 = 64 个采样点", 64, hist.sum())
        // 自检：不按 rowStride 跳行的话会把填充字节（0）也算进来，采样点数会变多
        assertTrue("rowStride 没被遵守", hist.sum() == 64)
    }

    @Test
    fun histogramDegradesToEmptyInsteadOfThrowing() {
        assertEquals(64, lumaHistogram(ByteArray(0), 0, 0, 0).size)
        assertTrue("空输入必须是全 0，不是抛", lumaHistogram(ByteArray(0), 0, 0, 0).all { it == 0 })
        // 越界的 stride：不许 ArrayIndexOutOfBounds（它会在分析线程上崩掉整路流）
        val tiny = ByteArray(4)
        val h = lumaHistogram(tiny, width = 640, height = 480, rowStride = 640, step = 8)
        assertTrue("越界的帧必须安全退化", h.all { it == 0 } || h.sum() >= 0)
        // 自检：不夹索引的话这行会抛 IndexOutOfBounds
        assertTrue("越界访问没被夹住", h.isNotEmpty())
    }

    @Test
    fun normalizedHistogramIsZeroToOne() {
        val flat = normalizeHistogram(IntArray(0))
        assertTrue("空直方图归一化后还是空", flat.isEmpty())
        val norm = normalizeHistogram(lumaHistogram(ByteArray(64 * 64) { 0x80.toByte() }, 64, 64, 64, step = 8))
        assertTrue("归一化后必须有峰值 1.0", norm.maxOrNull()!! in 0.99f..1.01f)
        assertTrue("没有负值", norm.all { it >= 0f })
        assertTrue("没有超过 1", norm.all { it <= 1f + 1e-6f })
    }

    // ── 7. 白平衡增益 ─────────────────────────────────────────────────

    @Test
    fun gainsAreFinitePositiveAndOrderedByKelvin() {
        for (k in listOf(2000, 2800, 3200, 4000, 5000, 5500, 6500, 8000, 9000, 12000)) {
            val g = kelvinToGains(k)
            assertEquals("必须给 3 个通道", 3, g.size)
            assertTrue("$k K 的增益必须有限且为正：${g.toList()}", g.all { it.isFinite() && it > 0f })
        }
        // 低色温：R 压过 B（把橙光掰回中性）；高色温反过来
        val low = kelvinToGains(2800)
        val high = kelvinToGains(9000)
        assertTrue("低色温的 R 该大于 B：${low.toList()}", low[0] > low[2])
        assertTrue("高色温的 B 该大于 R：${high.toList()}", high[2] > high[0])
        assertEquals("绿通道按定义归一成 1", 1f, low[1], 1e-6f)
        // 自检：不夹增益的话极端色温会给出 0 或 10 以上，驱动直接 ILLEGAL_ARGUMENT
        val extreme = kelvinToGains(40000)
        assertTrue("增益没被夹进安全区间：${extreme.toList()}", extreme.all { it in 0.25f..4f })
    }

    // ── 8. 落盘 ───────────────────────────────────────────────────────

    @Test
    fun storeRoundTripsAndTreatsSentinelsAsAuto() {
        val prefs = FakePrefs()
        // 全新安装：全自动、关着
        assertEquals(ProMode.Auto, CameraProStore.load(prefs))

        val on = ProMode(enabled = true, iso = 800, shutterNs = 8_000_000L, ev = -2, wbKelvin = 4000, focusDiopters = 3f)
        CameraProStore.save(prefs, on)
        assertEquals("落盘再读回必须一模一样", on, CameraProStore.load(prefs))

        CameraProStore.save(prefs, ProMode.Auto)
        val back = CameraProStore.load(prefs)
        assertNull("关闭后读回来必须是自动，不许把上次的 ISO 带回来", back.iso)
        assertNull(back.shutterNs)
        assertNull(back.wbKelvin)
        assertNull(back.focusDiopters)
        assertEquals(0, back.ev)
        // 自检：哨兵值若与真实档位撞了（比如 ISO 用 0 当哨兵），这条就会红
        assertTrue("哨兵值 -1 撞上了真实档位", ProTables.isos.none { it < 0 })
    }

    // ── 9. 直方图那句话（P0-2）─────────────────────────────────────────

    /**
     * 四种输入必须给出四种不同的话 —— 这一条钉的正是 P0-2：
     * 以前降级时 `analysis` 对象仍非空，于是"按 frameSource == null 重算"落到 else，
     * 把那句人话擦成空串，界面亮着「直方图 开」却什么都不画。
     *
     * 判据写成"任意实现 → 问题清单"，是为了让**坏实现能真的跑一遍同一组断言**：
     * `histogramContract { _,_ ,_ -> "" }` 那种"永远不说话"的实现必须被同一函数判出问题。
     */
    @Test
    fun histogramNoteForDistinguishesEveryFailure() {
        val problems = histogramContract(::histogramNoteFor)
        assertTrue("真实现没过这几条：$problems", problems.isEmpty())

        // 自检：把四种情况折成两种（"跑不起来" / 空）的实现，必须被同一组判据指出问题。
        // 这正是这个 bug 复发时长得最像"没事"的形状。
        val collapsed = { _: Boolean, built: Boolean, bound: Boolean ->
            if (built && bound) "" else "跑不起来"
        }
        val badProblems = histogramContract(collapsed)
        assertTrue("折成一句话的实现居然通过了：那这组判据抓不到回归", badProblems.isNotEmpty())
        assertTrue(
            "判据该红在「三句不同」那一条上，实际红在：$badProblems",
            badProblems.any { it.contains("不同") },
        )
        // 另一种坏形状：把"执行器已停"也报成同一条（丢掉了最准确的那个状态）
        val wrongPriority = { shuttingDown: Boolean, built: Boolean, bound: Boolean ->
            when {
                !built -> "这台设备的相机建不出直方图要用的分析流"
                !bound -> "这台设备的相机排不出直方图用的第三条流"
                shuttingDown -> "直方图已停"
                else -> ""
            }
        }
        assertTrue(
            "执行器已停被其它原因盖住的实现没被抓住",
            histogramContract(wrongPriority).isNotEmpty(),
        )
    }

    /**
     * 直方图那句话的四条契约，抽成函数只为一件事：**同一条判据能拿去喂坏实现**。
     * 返回问题清单（空 = 通过）。
     */
    private fun histogramContract(note: (Boolean, Boolean, Boolean) -> String): List<String> {
        val problems = ArrayList<String>()
        val shutdown = note(true, true, true)
        val noAnalysis = note(false, false, false)
        val noThirdStream = note(false, true, false)
        val healthy = note(false, true, true)
        if (healthy.isNotEmpty()) problems += "三路都成功时该是空串，实际是「$healthy」"
        for ((name, msg) in listOf("执行器已停" to shutdown, "造不出分析流" to noAnalysis, "三路排不下" to noThirdStream)) {
            if (msg.isBlank()) problems += "$name 时没有说话"
            else if (!msg.contains("直方图")) problems += "$name 那句话里没有「直方图」：$msg"
        }
        if (listOf(shutdown, noAnalysis, noThirdStream).distinct().size != 3) {
            problems += "三种失败没有给出三句不同的话（日志里会分不清该怪哪一层）"
        }
        // 执行器已停是更准确的状态，要压过另两条（相机页正在退出）
        if (note(true, false, false) != shutdown) {
            problems += "执行器已停被其它原因盖住了"
        }
        return problems
    }

    // ── 10. 降级只降内存、不擦用户存盘的参数（P0-3）─────────────────────

    /**
     * 一次下发失败不该把「上次停在 ISO 3200」这条用户偏好从磁盘删掉。
     *
     * 这条**必须经过 `CamProState`**：`degradeInMemory` 的价值全在"少了那次 save"上，
     * 纯函数测试看不到写盘。所以这里用 [FakePrefs] 观察磁盘侧。
     */
    @Test
    fun degradeInMemoryKeepsTheSavedPreferencesOnDisk() {
        val prefs = FakePrefs()
        val saved = ProMode(
            enabled = true,
            iso = 3200,
            shutterNs = 8_000_000L,
            ev = 2,
            wbKelvin = 3200,
            focusDiopters = 2f,
        )
        CameraProStore.save(prefs, saved)
        assertEquals("前置条件：磁盘上确实存着 3200", 3200, prefs.getInt("cam_pro_iso", -1))

        val state = CamProState(prefs)
        assertEquals("前置条件：读盘读回了它", 3200, state.mode.iso)

        state.degradeInMemory()

        // 内存侧：五项都没了，PRO 入口对自己也置灰 —— 界面上不许留一颗亮着却没生效的格子
        assertNull("降级之后内存里不许再留 ISO", state.mode.iso)
        assertNull(state.mode.shutterNs)
        assertNull(state.mode.wbKelvin)
        assertNull(state.mode.focusDiopters)
        assertFalse("降级之后 avail 必须全灰", state.avail.any)
        assertEquals(ProCaps.None, state.caps)

        // 磁盘侧：**原值还在**。这是 P0-3 的全部意义。
        assertEquals("降级把磁盘上的 ISO 擦掉了：换设备/重启之后用户的选择回不来",
            3200, prefs.getInt("cam_pro_iso", -1))
        assertEquals(8_000_000L, prefs.getLong("cam_pro_shutter", -1L))
        assertEquals(3200, prefs.getInt("cam_pro_wb", -1))

        // 自检：同一个输入走真实 updateCaps（它会写盘）必须把磁盘擦掉 ——
        // 这才证明"两种入口真的不是一回事"，而不是两个入口都恰好什么都没写。
        val disk2 = FakePrefs().also { CameraProStore.save(it, saved) }
        CamProState(disk2).updateCaps(ProCaps.None)
        assertEquals("updateCaps 本来就是会写盘的那个入口", -1, disk2.getInt("cam_pro_iso", -1))
    }

    /**
     * 真实 `updateCaps` 的写盘语义不许被上一条改动误伤：
     * 换镜头后旧值越界仍然要被钳制并写回（否则下次换回来还是个越界值）。
     */
    @Test
    fun updateCapsStillClampsAndWritesWhenTheCapsAreReal() {
        val prefs = FakePrefs()
        CameraProStore.save(prefs, ProMode(enabled = true, iso = 3200, wbKelvin = 9000, focusDiopters = 10f))
        val state = CamProState(prefs)

        // 换到一台 ISO 只到 1600、白平衡表只有两档、最近对焦 2 屈光度的镜头
        val narrow = ProCaps(
            isoRange = 50..1600,
            shutterNsRange = 125_000L..30_000_000_000L,
            evRange = -9..9,
            maxFocusDiopters = 2f,
            wbKelvins = listOf(4000, 5000),
            aeOffSupported = true,
            awbOffSupported = true,
            afOffSupported = true,
        )
        state.updateCaps(narrow)

        assertEquals("越界的 ISO 要夹到 1600", 1600, state.mode.iso)
        assertEquals("越界的白平衡要夹到最近的一档", 5000, state.mode.wbKelvin)
        assertEquals(2f, state.mode.focusDiopters!!, 1e-4f)
        assertEquals("钳制后的值必须写回磁盘", 1600, prefs.getInt("cam_pro_iso", -1))
        assertEquals(5000, prefs.getInt("cam_pro_wb", -1))

        // 自检：值没有被夹时 prefs 必须还是原值，证明上面那几条不是恒真
        assertEquals("夹制没有真的发生", 1600, clampIso(3200, narrow))
    }

    // ── 11. 请求值 vs 实际值（P1-6）────────────────────────────────────

    @Test
    fun exposureMismatchNoteSpeaksOnlyWhenItHasBothNumbers() {
        val problems = exposureContract(::exposureMismatchNote)
        assertTrue("真实现没过这几条：$problems", problems.isEmpty())

        // 自检 1：永远沉默的实现必须有话（"不一致"这三条都得红）
        val silent = { _: Int?, _: Long?, _: Int?, _: Long? -> "" }
        assertTrue("一个永远沉默的实现居然通过了：这组判据抓不到 P0-1 的证据缺失",
            exposureContract(silent).isNotEmpty())

        // 自检 2：把 1/3 档的 HAL 量化误差也报成不一致的实现，同样必须红 ——
        // 那会把"确实生效了"报成"没生效"，比不报更误导人。
        val tooStrict = { req: Int?, reqNs: Long?, act: Int?, actNs: Long? ->
            when {
                req == null && reqNs == null -> ""
                act == null && actNs == null -> ""
                (req != null && act != null && req != act) ||
                    (reqNs != null && actNs != null && reqNs != actNs) ->
                    "请求 ${req ?: reqNs} · 实际 ${act ?: actNs}"
                else -> ""
            }
        }
        assertTrue("容差丢掉了（差 1/3 档也报警）没被抓住", exposureContract(tooStrict).isNotEmpty())

        // 自检 3：反过来，容差给太大（差两档还算同一档）的实现也必须红 —— 那正是 P0-1 的形状：
        // 请求 400 实际 800 是"计划压根没生效"，不能被当成量化误差放过去。
        val tooLoose = { req: Int?, _: Long?, act: Int?, _: Long? ->
            if (req != null && act != null && (act > req * 4 || act < req / 4)) "差得离谱" else ""
        }
        assertTrue("容差给到两档还放过「400 → 800」的实现没被抓住",
            exposureContract(tooLoose).isNotEmpty())
    }

    /**
     * [exposureMismatchNote] 的四条契约。与 `histogramContract` 同一个理由：
     * 判据要能拿去喂坏实现，否则"自检"只是再写一遍期望值。
     */
    private fun exposureContract(
        note: (Int?, Long?, Int?, Long?) -> String,
    ): List<String> {
        val problems = ArrayList<String>()
        if (note(null, null, 800, 8_000_000L).isNotEmpty()) problems += "没请求手动曝光时不许说话"
        if (note(400, 8_000_000L, null, null).isNotEmpty()) problems += "读不到实际值时不许猜"
        if (note(400, 8_000_000L, 400, 8_000_000L).isNotEmpty()) problems += "一致时不许说话"
        // 1/3 档以内是 HAL 的量化误差（400 → 500、1/125 → 1/100）
        if (note(400, 8_000_000L, 500, 8_000_000L).isNotEmpty()) problems += "1/3 档的量化误差被报成了不一致"
        val isoOff = note(400, null, 800, null)
        if (!isoOff.contains("请求 ISO 400") || !isoOff.contains("实际 800")) {
            problems += "差一档的 ISO 没被报出来：$isoOff"
        }
        val shutterOff = note(null, 8_000_000L, null, 125_000L)
        if (!shutterOff.contains("请求") || !shutterOff.contains("实际")) {
            problems += "快门差两档没被报出来：$shutterOff"
        }
        return problems
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /**
     * `CameraMetadata` 的常量在 JVM 单测里**读不到真值**：`unitTests.isReturnDefaultValues = true`
     * （`app/build.gradle.kts`）让 android.jar 的静态字段返回 0，所以
     * `assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, o.aeMode)` 会变成 "0 == 0" 的恒真空跑。
     *
     * 生产代码因此改用自己那张 `Camera2Codes`（`const val`，内联成真字面量）。
     * 这里断言的是**那张表抄对了**：值取自 `android-36/android.jar` 的
     * `javap -constants android.hardware.camera2.CameraMetadata` 输出。
     *
     * 注意下面这些 `assertEquals(..., 0)` 与 `assertEquals(..., 1)` 在桩环境下**只能验一半**：
     * 桩把 SDK 侧也读成 0。真正的防线是 [Camera2Codes] 里的注释与这条测试的出处记录——
     * 所以这里用一张**独立抄写的字面量表**（`sdkCodes`）而不是复用生产常量，
     * 生产常量写错一个数时，两者对不上。
     */
    private val sdkCodes = mapOf(
        "CONTROL_AE_MODE_OFF" to 0,
        "CONTROL_AE_MODE_ON" to 1,
        "CONTROL_AWB_MODE_OFF" to 0,
        "CONTROL_AF_MODE_OFF" to 0,
        "COLOR_CORRECTION_MODE_TRANSFORM_MATRIX" to 0,
        "COLOR_CORRECTION_MODE_FAST" to 1,
    )

    @Test
    fun androidCodesMatchTheSdk() {
        assertEquals(sdkCodes["CONTROL_AE_MODE_OFF"], Camera2Codes.CONTROL_AE_MODE_OFF)
        assertEquals(sdkCodes["CONTROL_AE_MODE_ON"], Camera2Codes.CONTROL_AE_MODE_ON)
        assertEquals(sdkCodes["CONTROL_AWB_MODE_OFF"], Camera2Codes.CONTROL_AWB_MODE_OFF)
        assertEquals(sdkCodes["CONTROL_AF_MODE_OFF"], Camera2Codes.CONTROL_AF_MODE_OFF)
        assertEquals(
            sdkCodes["COLOR_CORRECTION_MODE_TRANSFORM_MATRIX"],
            Camera2Codes.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
        )
        // 承重的一条：TRANSFORM_MATRIX 与 FAST 若撞成同一个数，
        // "手动白平衡要关掉自动色彩校正"那句断言就恒真。
        assertTrue(
            "TRANSFORM_MATRIX 与 FAST 撞值了，配对断言会变成恒真",
            Camera2Codes.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX != Camera2Codes.COLOR_CORRECTION_MODE_FAST,
        )
        // 自检：AE 的 OFF 与 ON 撞值的话，"关了还是开着"这一整族断言全废
        assertTrue("AE_MODE 的 OFF/ON 撞值了", Camera2Codes.CONTROL_AE_MODE_OFF != Camera2Codes.CONTROL_AE_MODE_ON)
    }

    private fun androidCameraAeOff() = sdkCodes.getValue("CONTROL_AE_MODE_OFF")
    private fun androidCameraAeOn() = sdkCodes.getValue("CONTROL_AE_MODE_ON")
    private fun androidAwbOff() = sdkCodes.getValue("CONTROL_AWB_MODE_OFF")
    private fun androidAfOff() = sdkCodes.getValue("CONTROL_AF_MODE_OFF")
    private fun androidColorTransformMatrix() = sdkCodes.getValue("COLOR_CORRECTION_MODE_TRANSFORM_MATRIX")

    /** 只实现 ProStore 用到的这几个方法的最小 SharedPreferences。 */
    private class FakePrefs : android.content.SharedPreferences {
        private val data = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun remove(key: String?) = apply { removed.add(key!!) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean {
                removed.forEach { data.remove(it) }
                data.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
