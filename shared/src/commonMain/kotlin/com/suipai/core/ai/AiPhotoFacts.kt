package com.suipai.core.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 照片本身的客观色偏/明度/饱和，用来**喂给模型**而不是让它自己判断。
 *
 * 为什么要有这个类。2026-09-21 用 20 张有实测统计的真照片做过一次 A/B，
 * 「问题数」= 推荐与照片实测色偏互相矛盾的次数（色相打架、把清淡当黑白、编造表里没有的 id 等）：
 *
 * | 端点 | 不给这组数 | 给这组数 |
 * | --- | --- | --- |
 * | 智谱 glm-4v-flash | 10 / 20 | **6 / 20** |
 * | Agnes agnes-2.5-flash | 6 / 20 | **3 / 20** |
 *
 * 两个端点各自独立复现了同一个方向，所以这不是抽样噪声。
 * 具体修掉的是最难看的一类：黑白照片被配上「夜色」、高调街景被当成暗片处理。
 *
 * 反过来，**用文字规则去纠正模型是失败的**：加过「冷暖以画面色偏为准，不以光源为准」、
 * 「只有平均饱和低于 0.05 才算黑白」这类条款之后，问题数从 6 涨回 9，
 * 而且模型开始把 schema 里的占位文字（`主体+光线+色偏+饱和度`）原样抄进 `seen`，
 * 还会禁止住一条本来正确的推荐（真黑白片 sat=0.15 就不许选 银盐 了）。
 * 判断色偏这种活，本机 30 行代码比小模型准；模型负责它真正强的部分——
 * 看懂画面里是什么、该配哪种胶片气质。所以这里给数，不给道理。
 *
 * 算法与 `C:\tmp\ai\harness.py` 的 `facts()` 一致；设备上实际算出的三个数会打一行日志
 * （`UrlAiHttp.logFacts`），两边对不上就说明有一边改坏了。
 */
data class AiPhotoFacts(val warmth: Float, val brightness: Float, val saturation: Float) {

    companion object {
        /** 采样后的 ARGB 像素（调用方负责缩到小尺寸，这里只做统计）。 */
        fun from(pixels: IntArray): AiPhotoFacts {
            if (pixels.isEmpty()) return AiPhotoFacts(0f, 0f, 0f)
            var r = 0L
            var g = 0L
            var b = 0L
            var s = 0.0
            for (p in pixels) {
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF
                r += pr; g += pg; b += pb
                val mx = max(pr, max(pg, pb))
                val mn = min(pr, min(pg, pb))
                // colorsys.rgb_to_hsv 的 S：max 为 0 时是 0，否则 (max-min)/max
                s += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx.toDouble()
            }
            val n = pixels.size
            val rf = r / n / 255f
            val gf = g / n / 255f
            val bf = b / n / 255f
            return AiPhotoFacts(
                warmth = rf - bf,
                brightness = 0.299f * rf + 0.587f * gf + 0.114f * bf,
                saturation = (s / n).toFloat(),
            )
        }
    }

    private val cast: String
        get() = when {
            warmth > 0.05f -> "偏暖"
            warmth < -0.03f -> "偏冷"
            else -> "接近中性"
        }

    private val brightnessWord: String
        get() = when {
            brightness > 0.6f -> "明亮"
            brightness < 0.35f -> "昏暗"
            else -> "中间调"
        }

    private val saturationWord: String
        get() = when {
            saturation > 0.3f -> "浓彩"
            saturation < 0.12f -> "清淡"
            else -> "中等饱和"
        }

    /** 拼给模型的那句话。数字保留两位，与 python 侧的 `%+.2f` 完全同形。 */
    fun line(): String =
        "本机已量过这张照片的像素：色偏 " + signed(warmth) + "（正为暖、负为冷）、明度 " +
            fixed(brightness) + "、平均饱和 " + fixed(saturation) + "，即 " +
            cast + "、" + brightnessWord + "、" + saturationWord +
            "。冷暖与明暗以这组数为准，不要自己估。"
}

private fun fixed(v: Float): String {
    val scaled = abs((v * 100f).roundToInt())
    return (scaled / 100).toString() + "." + (scaled % 100).toString().padStart(2, '0')
}

private fun signed(v: Float): String = (if (v >= 0f) "+" else "-") + fixed(v)
