package com.suipai.core

import com.suipai.core.engine.ColorCore
import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.EngineParams
import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.Look
import com.suipai.core.engine.RadialMask
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 专业算子接进管线之后的**逐位不变**承诺。
 *
 * 这是整个升级里最容易砸掉的一条：新增三段处理，只要守卫写漏一个，
 * 所有老配方、所有老成片、所有没有曲线/分色/蒙版的导出的像素都会变一格。
 * 所以这里用一张 64×64 的确定性测试图，把「中性算子接进来 == 没接」钉成字节级断言。
 */
class ProPipelineIdentityTest {

    private val w = 64
    private val h = 64

    private fun image(): IntArray {
        // 64×64 确定性测试图：渐变 + 灰阶 + 饱和色块，覆盖三个算子的作用域。
        val rnd = Random(2026)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                out[i] = when {
                    y < 16 -> {
                        val v = (x * 255) / (w - 1)
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    y < 32 -> (0xFF shl 24) or ((x * 4) shl 16) or (255 - x * 4 shl 8) or (y * 4)
                    else -> (0xFF shl 24) or (rnd.nextInt(0x1000000))
                }
            }
        }
        return out
    }

    private fun params(
        curves: ToneCurves = ToneCurves(),
        hsl: HslBands = HslBands(),
        masks: List<LocalMask> = emptyList(),
    ) = EngineParams(
        look = Look(
            id = "probe",
            name = "探针",
            summary = "探针",
            contrast = 0.14f,
            saturation = 0.08f,
            temperature = 0.05f,
            grain = 0.09f,
            vignette = 0.11f,
            fade = 0.04f,
        ),
        intensity = 0.6f,
        w = w,
        h = h,
        grainEdge = 4096,
        seed = ColorCore.grainSeedFor("probe"),
        curves = curves,
        hsl = hsl,
        masks = masks,
    )

    private fun run(p: EngineParams, input: IntArray): IntArray =
        input.copyOf().also { ColorCore.process(it, w, h, p) }

    @Test
    fun neutralProOperatorsLeavePixelsBitIdentical() {
        val input = image()
        val baseline = run(params(), input)

        // 1) 显式传一条恒等曲线（两点对角线）：必须与不传完全一样
        val identityCurve = ToneCurves(master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))))
        assertContentEquals(baseline, run(params(curves = identityCurve), input), "恒等曲线改动了像素")

        // 2) 零偏移 HSL：8 带全 0
        assertContentEquals(baseline, run(params(hsl = HslBands()), input), "零 HSL 改动了像素")

        // 3) 零调整蒙版
        val zeroMask = listOf(LocalMask(RadialMask(feather = 0.8f), LocalAdjust()))
        assertContentEquals(baseline, run(params(masks = zeroMask), input), "零蒙版改动了像素")

        // 4) 三者一起中性
        assertContentEquals(
            baseline,
            run(params(identityCurve, HslBands(), zeroMask), input),
            "三者同时中性却改变了输出",
        )
    }

    @Test
    fun neutralProOperatorsDoNotAllocateWorkerState() {
        // 守卫的另一半：中性时不该建出 LUT / 权重场。
        // 建了也不影响像素，但 4096 长边上那是几十 MB 的无谓分配。
        val p = params()
        assertTrue(p.curveByteR == null && p.curveByteG == null && p.curveByteB == null)
        assertTrue(p.maskField == null)
        assertFalse(p.hasHsl)
        assertEquals(0, p.curveMaster?.size ?: 0)
    }

    @Test
    fun nonNeutralOperatorsActuallyChangePixels() {
        // 自检：上面那四条"不变"必须是因为真的中性，而不是因为整段处理根本没接上。
        val input = image()
        val baseline = run(params(), input)

        val curve = ToneCurves(
            master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.62f), CurvePoint(1f, 1f))),
        )
        assertFalse(baseline.contentEquals(run(params(curves = curve), input)), "曲线没生效")

        val hsl = HslBands(blue = HslBand(sat = 0.7f))
        assertFalse(baseline.contentEquals(run(params(hsl = hsl), input)), "HSL 没生效")

        val mask = listOf(
            LocalMask(
                RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.4f, ry = 0.4f, feather = 0.5f),
                LocalAdjust(exposure = 0.8f),
            ),
        )
        assertFalse(baseline.contentEquals(run(params(masks = mask), input)), "蒙版没生效")
    }

    @Test
    fun proOperatorsKeepChunkedProcessingDeterministic() {
        // 三段新处理都是逐像素的，跨行带不许有状态：乱序行带必须给出同一张图。
        val p = params(
            curves = ToneCurves(master = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 0.9f)))),
            hsl = HslBands(orange = HslBand(hue = 0.3f, lum = 0.4f)),
            masks = listOf(
                LocalMask(LinearMask(angle = 20f, start = 0.2f, end = 0.6f), LocalAdjust(contrast = 0.5f)),
            ),
        )
        val input = image()
        val reference = run(p, input)

        val chunked = input.copyOf()
        var y = 0
        while (y < h) {
            ColorCore.processRange(chunked, w, h, y, minOf(h, y + 7), p)
            y += 7
        }
        assertContentEquals(reference, chunked, "行带切分改变了结果")

        val reversed = input.copyOf()
        for (row in h - 1 downTo 0) ColorCore.processRange(reversed, w, h, row, row + 1, p)
        assertContentEquals(reference, reversed, "倒序行带改变了结果")
    }

    @Test
    fun proOperatorsPreserveAlphaAndRange() {
        val p = params(
            curves = ToneCurves(master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 1f)))),
            hsl = HslBands(green = HslBand(hue = -1f, sat = 1f, lum = -1f)),
            masks = listOf(
                LocalMask(RadialMask(feather = 1f), LocalAdjust(exposure = 1f, contrast = 1f, saturation = 1f, temperature = 1f)),
            ),
        )
        val input = image()
        val out = run(p, input)
        for (i in out.indices) {
            assertEquals((input[i] ushr 24) and 0xFF, (out[i] ushr 24) and 0xFF, "alpha 变了 @$i")
            val r = (out[i] shr 16) and 0xFF
            val g = (out[i] shr 8) and 0xFF
            val b = out[i] and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255, "通道越界 @$i")
        }
    }
}
