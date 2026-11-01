package com.suipai.core

import com.suipai.core.engine.Hsl
import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HSL 分色算子。
 *
 * 重点不是「有没有效果」，而是三件会让照片悄悄坏掉的事：
 * 1. 零偏移必须逐位恒等（默认路径的承诺）；
 * 2. 灰阶不许被染色（无彩色像素的色相是未定义量，动它就是错的）；
 * 3. 带间权重是单位分解（权重和恒为 1），否则「只把蓝色调亮」会顺手整体提亮。
 */
class HslBandsTest {

    private val w = 16
    private val h = 16

    private fun pixels(): IntArray {
        // 覆盖红/橙/黄/绿/青/蓝/紫/品红 + 灰阶的确定性图
        val list = ArrayList<Int>(w * h)
        val hues = intArrayOf(0, 30, 60, 120, 180, 240, 280, 320)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y < 2) {
                    val v = (x * 255) / (w - 1)
                    list += (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                } else {
                    val band = hues[y % hues.size]
                    val sat = 0.35f + 0.05f * (y / 8)
                    val value = 0.25f + 0.7f * (x / (w - 1f))
                    val hsv = FloatArray(3)
                    val out = IntArray(3)
                    hsv[0] = band.toFloat()
                    hsv[1] = sat
                    hsv[2] = value
                    Hsl.hsvToRgb(hsv[0], hsv[1], hsv[2], out)
                    list += (0xFF shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
                }
            }
        }
        return list.toIntArray()
    }

    // ---- 恒等 ---------------------------------------------------------------

    @Test
    fun zeroOffsetsAreBitExact() {
        // 全 0 的 HslBands：hasAny 必须是 false，applyRange 必须一个像素都不改。
        val bands = HslBands()
        assertFalse(bands.hasAny)
        val input = pixels()
        val work = input.copyOf()
        Hsl.applyRange(work, w, h, 0, h, bands)
        assertTrue(input.contentEquals(work), "零偏移改动了像素")

        // 自检：给红带 +0.5 色相，同一张图必须真的变了，否则上面的断言是空跑
        val redShift = HslBands(red = HslBand(hue = 0.5f))
        assertTrue(redShift.hasAny, "自检失败：非零偏移没被识别成有内容")
        val shifted = input.copyOf()
        Hsl.applyRange(shifted, w, h, 0, h, redShift)
        assertFalse(input.contentEquals(shifted), "自检失败：给了偏移却没改动")
    }

    @Test
    fun bandClampsOffsetsToUnitRange() {
        val wild = HslBand(hue = 9f, sat = -9f, lum = 0.5f)
        assertEquals(1f, wild.hueC)
        assertEquals(-1f, wild.satC)
        assertEquals(0.5f, wild.lumC)
    }

    // ---- 灰阶保护 -----------------------------------------------------------

    @Test
    fun grayscaleIsNeverTinted() {
        // 无彩色像素的色相未定义。老实现里 `if (delta == 0) continue` 漏在
        // 权重计算之后，于是纯灰被「最近的那个色相带」染了色。
        val bands = HslBands(
            red = HslBand(sat = 1f, hue = 1f),
            blue = HslBand(sat = -1f, lum = 1f),
        )
        val gray = IntArray(w * h) { i ->
            val v = (i * 255) / (w * h - 1)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val work = gray.copyOf()
        Hsl.applyRange(work, w, h, 0, h, bands)
        assertTrue(gray.contentEquals(work), "灰阶被染色了")

        // 自检：同一组参数在彩色图上必须生效
        val color = pixels()
        val colorWork = color.copyOf()
        Hsl.applyRange(colorWork, w, h, 0, h, bands)
        assertFalse(color.contentEquals(colorWork), "自检失败：参数对彩色图也没效果")
    }

    // ---- 往返精度 -----------------------------------------------------------

    @Test
    fun rgbHsvRoundTripErrorIsWithinOneCode() {
        // 判据 ≤ 1/255 ≈ 0.0039，即 8 位上的 1 个码。
        // 曾经把色相写成整数截断的 60*(g-b)/delta，误差会跳到 4 个码。
        val worst = Hsl.roundTripMaxError(step = 3)
        assertTrue(worst <= 1, "往返最大误差 $worst 超过 1/255")

        // 自检：故意把色相偏 2° 再转回来，误差必须超过 1 —— 证明这个判据真的在量东西
        val hsv = FloatArray(3)
        val rgb = IntArray(3)
        Hsl.rgbToHsv(220, 40, 40, hsv)
        Hsl.hsvToRgb(hsv[0] + 2f, hsv[1], hsv[2], rgb)
        val drift = abs(rgb[0] - 220) + abs(rgb[1] - 40) + abs(rgb[2] - 40)
        assertTrue(drift > 1, "自检失败：色相偏 2° 却没测出漂移")
    }

    // ---- 权重 -----------------------------------------------------------------

    @Test
    fun bandWeightsSumToOneEverywhere() {
        // 单位分解：任何色相角上的 8 个权重加起来都是 1（±1e-5）。
        // 破坏它的典型改法是把 smoothstep 换成线性并忘了归一化。
        for (deg in 0 until 360) {
            var sum = 0f
            for (b in 0 until HslBands.BAND_COUNT) sum += HslBands.WEIGHT_LUT[deg * HslBands.BAND_COUNT + b]
            assertTrue(abs(sum - 1f) <= 1e-5f, "deg=$deg 权重和=$sum")
        }
        // 自检：在**两带中点**（30° 与 60° 之间的 45°）必须两带同时非零 ——
        // 带中心天生只有一个带（那是设计），拿中心点验交叠等于没验。
        val mid = 45
        val at45 = (0 until HslBands.BAND_COUNT).count { HslBands.WEIGHT_LUT[mid * 8 + it] > 1e-4f }
        assertTrue(at45 >= 2, "自检失败：45° 只命中 $at45 个带，交叠没生效")
        // 交叉验证：中点两带的权重应该接近各半
        val orange = HslBands.WEIGHT_LUT[mid * 8 + 1]
        val yellow = HslBands.WEIGHT_LUT[mid * 8 + 2]
        assertTrue(abs(orange - 0.5f) < 1e-4f && abs(yellow - 0.5f) < 1e-4f, "中点权重 $orange/$yellow 不是各半")
    }

    @Test
    fun bandCentreHasFullWeightOnItsOwnBand() {
        // 带中心必须是纯的（权重 1），否则「只调橙色」会把邻居也带跑。
        for (b in 0 until HslBands.BAND_COUNT) {
            val hue = HslBands.HUES[b]
            val wgt = HslBands.WEIGHT_LUT[hue * 8 + b]
            assertTrue(wgt >= 0.999f, "带 $b 在中心 $hue° 的权重只有 $wgt")
        }
    }

    @Test
    fun hueShiftMovesOnlyTargetBand() {
        // 只把蓝(+0.5) 转 15°，蓝像素必须变、绿像素必须原样。
        val bands = HslBands(blue = HslBand(hue = 0.5f))
        val green = intArrayOf(0xFF000000.toInt() or (40 shl 16) or (200 shl 8) or 70)
        val blue = intArrayOf(0xFF000000.toInt() or (40 shl 16) or (80 shl 8) or 210)
        val g2 = green.copyOf()
        val b2 = blue.copyOf()
        Hsl.applyRange(g2, 1, 1, 0, 1, bands)
        Hsl.applyRange(b2, 1, 1, 0, 1, bands)
        assertTrue(green.contentEquals(g2), "绿被蓝带的调整影响了：${g2[0]}")
        assertTrue(!blue.contentEquals(b2), "蓝没变：${b2[0]}")
    }
}
