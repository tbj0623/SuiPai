package com.suipai.app.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装裱（胶片边框 + 漏光）的判据。**全部在 JVM 上跑，不碰 `android.graphics.Bitmap`。**
 *
 * 为什么必须是纯像素数组：本工程 `unitTests.isReturnDefaultValues = true`，
 * `Bitmap.createBitmap` 在单测里直接返回 null、`getPixels` 什么都不做 ——
 * 任何"在 Bitmap 上断言像素"的测试都会**绿着空跑**。所以像素层的实现刻意不依赖 Bitmap，
 * 生产路径把像素取出来喂进来，测试路径自己造数组喂同一批函数。
 *
 * 这一篇钉住的四件事：
 * 1. **强度 0 时逐像素不变**（漏光 0 且边框 NONE）—— 这是"新增特性不改变老行为"的唯一证据；
 * 2. 四种边框各自画出预期像素（35mm 的齿孔区必须是亮的、片基必须暗、120 的宽边必须更宽、
 *    拍立得的下边必须明显宽于上边）；
 * 3. 漏光只落在选定的那个角，且对角必须是 0；
 * 4. 每条判据都配一个**喂坏例子必须变红**的自检（没见过自己变红的守卫不算守卫）。
 */
class CollagePixelsTest {

    companion object {
        /** 造一张"有花纹"的底图：纯色会让"没变"与"变了但看不出"分不开。 */
        fun pattern(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // 一段斜条纹 + 每格不同的亮度：任何一处被改写都能被逐字节比对抓到
            val v = ((x * 7 + y * 13) and 0xFF)
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((x xor y) and 0xFF)
        }

        fun region(w: Int, h: Int, top: Int = 0, left: Int = 0) =
            PixelRegion(left = left, top = top, width = w, height = h)

        /** 一整幅一次性应用（测试图小，不需要条带）。 */
        fun applyAll(px: IntArray, w: Int, h: Int, style: CollageStyle): IntArray {
            val out = px.copyOf()
            CollagePixels.applyAdornments(out, region(w, h), w, h, style.coerced())
            return out
        }

        /** 一块区域里有多少像素被改过。 */
        fun changedCount(a: IntArray, b: IntArray): Int {
            var n = 0
            for (i in a.indices) if (a[i] != b[i]) n++
            return n
        }

        /** 从 ARGB 取亮度（0..255，整数近似，足够判"暗/亮"）。 */
        fun luma(argb: Int): Int {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 2126 + g * 7152 + b * 722) / 10000
        }

        /** 一个矩形区域的平均亮度。 */
        fun avgLuma(px: IntArray, w: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
            var sum = 0L
            var n = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    sum += luma(px[y * w + x])
                    n++
                }
            }
            return if (n == 0) -1 else (sum / n).toInt()
        }

        /** 记号里"指定种类"的总面积（像素）。 */
        fun areaOf(marks: List<FrameMark>, kind: FrameMarkKind): Long =
            marks.filter { it.kind == kind }.sumOf { it.width.toLong() * it.height.toLong() }
    }

    // ────────────────────────────────────────────────────────────────
    // 1. 强度 0 时逐像素不变
    // ────────────────────────────────────────────────────────────────

    @Test
    fun zeroStrengthLeavesEveryPixelUntouched() {
        val w = 64
        val h = 48
        val src = pattern(w, h)

        // 情形 A：默认样式（NONE + leak 0）—— 加这个特性之前的调用点就是这个值
        val plain = CollageStyle()
        val outA = applyAll(src, w, h, plain)
        assertEquals("默认样式改动了像素", 0, changedCount(src, outA))

        // 情形 B：显式 NONE + 0 漏光
        val outB = applyAll(src, w, h, CollageStyle(frame = FrameStyle.NONE, leak = 0f))
        assertEquals("NONE + 0 漏光改动了像素", 0, changedCount(src, outB))

        // 情形 C：负强度 / 超界的角 —— 夹取之后仍是"什么都不做"
        val outC = applyAll(src, w, h, CollageStyle(leak = -1f, leakCorner = 9))
        assertEquals("负强度改动了像素", 0, changedCount(src, outC))

        assertFalse("hasAdornments 在默认值上是 true —— 生产路径会白取一遍像素", plain.hasAdornments)
        assertEquals("默认样式的工具条文案变了", "无框", plain.adornmentLabel)
    }

    @Test
    fun zeroLeakIsExactlyTheIdentityEvenWithNegativeInput() {
        // applyAdornments 必须在 leak<=0 且 NONE 时**一次 blend 都不做**
        val src = pattern(16, 16)
        val touched = CollagePixels.applyAdornments(
            src, region(16, 16), 16, 16,
            CollageStyle(frame = FrameStyle.NONE, leak = 0f),
        )
        assertFalse("返回 true 说明进过装裱路径", touched)

        // 自检：同一条判据在 leak=0.01 时必须变 true（否则"永远返回 false"也会绿）
        val src2 = pattern(16, 16)
        val touched2 = CollagePixels.applyAdornments(
            src2, region(16, 16), 16, 16,
            CollageStyle(frame = FrameStyle.NONE, leak = 0.01f),
        )
        assertTrue("leak=0.01 居然一个像素都没碰 —— 判据失效", touched2)
    }

    // ────────────────────────────────────────────────────────────────
    // 2. 四种边框各自画出预期像素
    // ────────────────────────────────────────────────────────────────

    @Test
    fun noneDrawsNothing() {
        assertEquals(
            "NONE 居然产出了记号",
            emptyList<FrameMark>(),
            CollagePixels.frameMarks(100, 100, CollageStyle(frame = FrameStyle.NONE)),
        )
        // 自检：换一种样式必须有记号，否则"永远返回空表"也会绿
        assertTrue(
            "FILM_35MM 一个记号都没有",
            CollagePixels.frameMarks(100, 100, CollageStyle(frame = FrameStyle.FILM_35MM)).isNotEmpty(),
        )
    }

    @Test
    fun film35PutsAPerforatedDarkBandOnBothEdges() {
        val w = 400
        val h = 300
        val style = CollageStyle(frame = FrameStyle.FILM_35MM).coerced()
        val marks = CollagePixels.frameMarks(w, h, style)
        val ink = style.frameInk

        // 片基是 BASE 记号的四块，色值必须是 ink.baseArgb
        val bases = marks.filter { it.kind == FrameMarkKind.BASE }
        assertEquals("35mm 的片基不是四块", 4, bases.size)
        assertTrue("片基颜色不对", bases.all { it.argb == ink.baseArgb })

        // 上边必须有片基、下边也必须有；左右两块的宽度才是"窄片基"
        assertTrue("上边缘没有片基", bases.any { it.top == 0 && it.left == 0 && it.right == w })
        assertTrue("下边缘没有片基", bases.any { it.bottom == h })

        // 齿孔：HOLE、四角亮色、上下各一排、数量相等
        val holes = marks.filter { it.kind == FrameMarkKind.HOLE }
        assertTrue("齿孔少于 8 个（%d）".format(holes.size), holes.size >= 8)
        assertEquals("上下两排齿孔数量不等", 0, holes.size % 2)
        assertTrue("齿孔不是亮色", holes.all { it.argb == ink.paperArgb })
        assertTrue("齿孔越界", holes.all { it.left >= 0 && it.right <= w && it.top >= 0 && it.bottom <= h })
        // 齿孔必须落在上下条带里，不能漏到画面中间
        val band = bases.first { it.top == 0 }.bottom
        val topHoles = holes.filter { it.bottom <= band }
        val bottomHoles = holes.filter { it.top >= h - band }
        assertEquals("上排齿孔数不等于下排", topHoles.size, bottomHoles.size)
        assertTrue("上排齿孔为 0", topHoles.isNotEmpty())

        // 逐像素：上条带里必须同时有暗（片基）和亮（齿孔）两类像素
        val px = applyAll(pattern(w, h), w, h, style)
        val bandAvg = avgLuma(px, w, 0, 0, w, band)
        assertTrue("上片基不够暗（avg=$bandAvg）", bandAvg < 120)
        val firstHole = topHoles.first()
        val holeLuma = luma(px[firstHole.centerY * w + firstHole.centerX])
        assertTrue("齿孔区不够亮（luma=$holeLuma）", holeLuma > 200)
    }

    @Test
    fun film120HasWiderEdgesThan35AndNoPerforation() {
        val w = 400
        val h = 300
        val s35 = CollageStyle(frame = FrameStyle.FILM_35MM).coerced()
        val s120 = CollageStyle(frame = FrameStyle.FILM_120).coerced()

        val marks35 = CollagePixels.frameMarks(w, h, s35)
        val marks120 = CollagePixels.frameMarks(w, h, s120)

        // 120 没有齿孔 —— 这是它与 35mm 最容易分开的视觉事实
        assertEquals(
            "120 不该有齿孔",
            0,
            marks120.count { it.kind == FrameMarkKind.HOLE },
        )
        // 120 的边更宽
        val edge35 = marks35.first { it.kind == FrameMarkKind.BASE && it.top == 0 }.bottom
        val edge120 = marks120.first { it.kind == FrameMarkKind.BASE && it.top == 0 }.bottom
        assertTrue("120 的边（$edge120）不比 35mm（$edge35）宽", edge120 > edge35)
        // 120 有转记线与四角刻线
        assertEquals("120 的角刻线不是 4 条", 4, marks120.count { it.kind == FrameMarkKind.TICK })
        assertTrue("120 没有转记线", marks120.any { it.kind == FrameMarkKind.LINE })

        // 逐像素：四角中心（刻线所在）与画面正中心必须不同
        val px = applyAll(pattern(w, h), w, h, s120)
        val corner = marks120.first { it.kind == FrameMarkKind.TICK }
        val cornerPx = px[corner.centerY * w + corner.centerX]
        assertEquals("刻线颜色不对", s120.frameInk.markArgb, cornerPx)
    }

    @Test
    fun polaroidHasAWideBottomCardWithASignatureArea() {
        val w = 360
        val h = 420
        val style = CollageStyle(frame = FrameStyle.POLAROID).coerced()
        val marks = CollagePixels.frameMarks(w, h, style)

        val top = marks.first { it.kind == FrameMarkKind.BASE && it.top == 0 }
        val bottom = marks.first { it.kind == FrameMarkKind.BASE && it.bottom == h }
        assertTrue(
            "拍立得的下边（${bottom.height}）没有明显宽于上边（${top.height}）",
            bottom.height >= top.height * 2,
        )
        // 下边必须够放落款：至少占高度的 12%
        assertTrue(
            "下边只占 ${"%.1f".format(bottom.height * 100f / h)}%，放不下落款",
            bottom.height >= h * 0.12f,
        )
        // 卡面是纸白
        assertTrue("拍立得卡面不是纸白", marks.filter { it.kind == FrameMarkKind.BASE }
            .all { it.argb == style.frameInk.paperArgb })

        // 逐像素：卡面区必须明显亮于画面中心
        val px = applyAll(pattern(w, h), w, h, style)
        val cardLuma = avgLuma(px, w, 0, h - bottom.height + 2, w, h - 2)
        val centerLuma = avgLuma(px, w, w / 2 - 5, h / 2 - 5, w / 2 + 5, h / 2 + 5)
        assertTrue("卡面（$cardLuma）不够亮", cardLuma > 200)
        assertTrue("画面中心（$centerLuma）被卡面盖了", centerLuma != cardLuma)
    }

    @Test
    fun everyFrameKeepsItsMarksInsideTheCanvas() {
        // 极端尺寸：细长条、极小的图 —— 记号不许越界（越界在 Canvas 上会被裁掉，
        // 但在像素层越界就是下标异常或画到别的行上）
        val sizes = listOf(2 to 2, 3 to 400, 400 to 3, 64 to 64, 2048 to 1365)
        for ((w, h) in sizes) {
            for (frame in FrameStyle.entries) {
                val marks = CollagePixels.frameMarks(w, h, CollageStyle(frame = frame))
                for (m in marks) {
                    assertTrue(
                        "${frame.name} 在 ${w}x$h 上越界：$m",
                        m.left >= 0 && m.top >= 0 && m.right <= w && m.bottom <= h,
                    )
                    assertTrue("${frame.name} 在 ${w}x$h 上有负宽高：$m", m.width >= 0 && m.height >= 0)
                }
            }
        }
        // 自检：0 尺寸不崩、且返回空表
        assertEquals(emptyList<FrameMark>(), CollagePixels.frameMarks(0, 100, CollageStyle(frame = FrameStyle.FILM_35MM)))
    }

    // ────────────────────────────────────────────────────────────────
    // 3. 漏光只落在选定的角
    // ────────────────────────────────────────────────────────────────

    @Test
    fun leakOnlyTouchesTheChosenCorner() {
        val w = 200
        val h = 200
        val src = pattern(w, h)

        for (corner in 0..3) {
            val out = applyAll(src, w, h, CollageStyle(leak = 1f, leakCorner = corner))
            val (cx, cy) = when (corner) {
                0 -> 4 to 4
                1 -> w - 5 to 4
                2 -> 4 to h - 5
                else -> w - 5 to h - 5
            }
            val near = out[cy * w + cx]
            assertTrue("漏光角 $corner 的角上像素没变", near != src[cy * w + cx])
            // 对角必须完全没变
            val (ox, oy) = when (corner) {
                0 -> w - 5 to h - 5
                1 -> 4 to h - 5
                2 -> w - 5 to 4
                else -> 4 to 4
            }
            assertEquals(
                "漏光角 $corner 把对角也染了",
                src[oy * w + ox],
                out[oy * w + ox],
            )
        }
    }

    @Test
    fun leakAlphaDecaysWithDistanceAndRespectsStrength() {
        val w = 300
        val h = 300
        val corner = 0
        val atOrigin = CollagePixels.leakAlphaAt(0, 0, w, h, 1f, corner)
        val mid = CollagePixels.leakAlphaAt(80, 80, w, h, 1f, corner)
        val far = CollagePixels.leakAlphaAt(290, 290, w, h, 1f, corner)

        assertTrue("原点 alpha 太小：$atOrigin", atOrigin > 0.3f)
        assertTrue("衰减不是单调的：原点 $atOrigin / 中段 $mid", atOrigin > mid && mid > far)
        assertEquals("对角不该有漏光（$far）", 0f, far)
        // 强度线性缩放
        val half = CollagePixels.leakAlphaAt(0, 0, w, h, 0.5f, corner)
        assertEquals("强度没有线性缩放", atOrigin / 2f, half, 1e-4f)
        assertEquals("强度 0 仍有 alpha", 0f, CollagePixels.leakAlphaAt(0, 0, w, h, 0f, corner))
        // 自检：0 尺寸回 0 而不是崩
        assertEquals(0f, CollagePixels.leakAlphaAt(0, 0, 0, 0, 1f, 0))
    }

    @Test
    fun leakIsWarmNotGray() {
        // 漏光是暖橙：叠加之后 R 必须比 B 涨得多
        val src = intArrayOf((0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80)
        val out = src.copyOf()
        CollagePixels.applyAdornments(out, region(1, 1), 1, 1, CollageStyle(leak = 1f, leakCorner = 0))
        val r = (out[0] ushr 16) and 0xFF
        val b = out[0] and 0xFF
        assertTrue("叠加后 B（$b）不该涨过 R（$r）", r > b)
        // 自检：如果实现退回"什么都不做"，这一条会红（out == src 时 r == b）
    }

    // ────────────────────────────────────────────────────────────────
    // 4. 条带与合成
    // ────────────────────────────────────────────────────────────────

    @Test
    fun stripProcessingMatchesWholeImageProcessing() {
        // 生产路径按条带取像素。条带处理必须与整幅一次处理**逐字节相同** ——
        // 否则接缝处会出现一条 1px 的横线（正是"导出看起来像坏了"的那种）。
        val w = 96
        val h = 200
        val style = CollageStyle(frame = FrameStyle.FILM_35MM, leak = 0.8f, leakCorner = 3).coerced()
        val src = pattern(w, h)

        val whole = applyAll(src, w, h, style)

        val strip = IntArray(w * 32)
        val striped = IntArray(w * h)
        var top = 0
        while (top < h) {
            val rows = minOf(32, h - top)
            System.arraycopy(src, top * w, strip, 0, w * rows)
            CollagePixels.applyAdornments(strip, region(w, rows, top = top), w, h, style)
            System.arraycopy(strip, 0, striped, top * w, w * rows)
            top += rows
        }
        assertEquals("条带处理与整幅处理结果不同", 0, changedCount(whole, striped))
    }

    @Test
    fun blendOverIsCorrectAtTheExtremes() {
        val dst = 0xFF204060.toInt()
        assertEquals("alpha=0 应完全保留底", dst, CollagePixels.blendOver(dst, 0xFFFFFFFF.toInt(), 0f))
        assertEquals(
            "alpha=1 应完全取源（且不透明）",
            0xFFFFFFFF.toInt(),
            CollagePixels.blendOver(dst, 0xFFFFFFFF.toInt(), 1f),
        )
        // 半透明：结果必须落在两者之间，且不透明
        val half = CollagePixels.blendOver(dst, 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals("合成结果必须不透明", 0xFF, (half ushr 24) and 0xFF)
        val r = (half ushr 16) and 0xFF
        assertTrue("半透明合成越界：r=$r", r in 0x20..0xFF)
        // 自检：喂一个"越界 alpha"也要夹住
        assertEquals(dst, CollagePixels.blendOver(dst, 0xFFFFFFFF.toInt(), -3f))
    }

    @Test
    fun frameStyleCycleCoversEveryEntryExactlyOnce() {
        // 界面那一格是"点一下下一个"。循环必须真的走遍四种、且回到起点。
        var f = FrameStyle.NONE
        val seen = mutableListOf(f)
        repeat(FrameStyle.entries.size - 1) {
            f = f.next()
            seen += f
        }
        assertEquals("循环少走了", FrameStyle.entries.size, seen.size)
        assertEquals("循环没有覆盖全部", FrameStyle.entries.toSet(), seen.toSet())
        assertEquals("循环没有回到起点", FrameStyle.NONE, f.next())

        // 越界序号归零（偏好里存了旧序号时不许崩）
        assertEquals(FrameStyle.NONE, FrameStyle.byOrdinal(0))
        assertEquals(FrameStyle.values()[1], FrameStyle.byOrdinal(1))
        assertEquals(FrameStyle.NONE, FrameStyle.byOrdinal(99))
        assertEquals(FrameStyle.NONE, FrameStyle.byOrdinal(-5))
    }

    @Test
    fun coercedStyleClampsTheNewFields() {
        val wild = CollageStyle(leak = 5f, leakCorner = 17, frame = FrameStyle.POLAROID).coerced()
        assertEquals("leak 没夹到 1", 1f, wild.leak, 1e-6f)
        assertEquals("leakCorner 没取模", 1, wild.leakCorner)
        assertEquals("frame 不该被改", FrameStyle.POLAROID, wild.frame)
        assertTrue(wild.hasAdornments)

        val negative = CollageStyle(leak = -2f, leakCorner = -1).coerced()
        assertEquals(0f, negative.leak, 1e-6f)
        assertEquals(3, negative.leakCorner)
        assertFalse("全关着却 hasAdornments=true", negative.hasAdornments)
    }

    @Test
    fun previewTintComesFromTheSameConstant() {
        // 预览与像素层各要画同一支橙。字面量抄两份迟早分叉，而"预览的橙和成片的橙
        // 不一样"是装机才看得出来、diff 里完全正常的那种缺陷。
        // 这里钉住：预览的 LeakTint 就是 CollagePixels.LEAK_RGB 的 Color 化，不是第二个字面量。
        val expected = CollagePixels.LEAK_RGB
        assertEquals("LEAK_RGB 被改名/改值了，预览那一支会跟着漂", 0xFFE8A33D.toInt(), expected)

        val preview = LeakTint
        val asArgb = (0xFF shl 24) or
            ((preview.red * 255f).toInt() shl 16) or
            ((preview.green * 255f).toInt() shl 8) or
            (preview.blue * 255f).toInt()
        // 允许 ±1 的往返误差（Float 颜色通道是 0..1）
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (asArgb ushr shift) and 0xFF
            val b = (expected ushr shift) and 0xFF
            assertTrue("通道 $shift 对不上：预览 $a / 像素层 $b", kotlin.math.abs(a - b) <= 1)
        }
        assertEquals("两个 alpha 不一致", 0xFF, (asArgb ushr 24) and 0xFF)
    }

    @Test
    fun emptyRegionIsANoOpInsteadOfACrash() {
        val src = pattern(8, 8)
        assertFalse(
            "空区域居然动了像素",
            CollagePixels.applyAdornments(
                src, PixelRegion(0, 0, 0, 0), 8, 8,
                CollageStyle(frame = FrameStyle.FILM_120, leak = 1f),
            ),
        )
        // 自检：非空区域必须 true（否则"永远 false"也会绿）
        assertTrue(
            CollagePixels.applyAdornments(
                src.copyOf(), region(8, 8), 8, 8,
                CollageStyle(frame = FrameStyle.FILM_120, leak = 1f),
            ),
        )
        // 数组比区域小：不许越界，直接拒绝
        assertFalse(
            CollagePixels.applyAdornments(
                IntArray(4), region(8, 8), 8, 8,
                CollageStyle(frame = FrameStyle.FILM_35MM),
            ),
        )
    }

    @Test
    fun guardHelpersActuallyCatchBadCode() {
        // 这一篇的判据函数自己也要证明"喂坏例子会红"。
        // 1) changedCount：同一份数组必须 0；改一个像素必须 1
        val a = pattern(4, 4)
        assertEquals(0, changedCount(a, a.copyOf()))
        val b = a.copyOf().also { it[5] = it[5] xor 0xFF }
        assertEquals(1, changedCount(a, b))

        // 2) luma：黑 0、白 255
        assertEquals(0, luma(0xFF000000.toInt()))
        assertEquals(255, luma(0xFFFFFFFF.toInt()))

        // 3) areaOf：只数指定种类
        val marks = listOf(
            FrameMark(0, 0, 10, 10, 0xFF000000.toInt(), FrameMarkKind.BASE),
            FrameMark(0, 0, 5, 5, 0xFFFFFFFF.toInt(), FrameMarkKind.HOLE),
        )
        assertEquals(100L, areaOf(marks, FrameMarkKind.BASE))
        assertEquals(25L, areaOf(marks, FrameMarkKind.HOLE))
        assertEquals(0L, areaOf(marks, FrameMarkKind.TICK))

        // 4) avgLuma：全黑区域必须 0（读数不对时这一条会红）
        val black = IntArray(100) { 0xFF000000.toInt() }
        assertEquals(0, avgLuma(black, 10, 0, 0, 10, 10))
    }
}
