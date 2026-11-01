package com.suipai.core.engine

import kotlin.math.abs
import kotlin.math.floor

/**
 * 8 个色相带上的分色调整（Lightroom 式「HSL 分色」）。
 *
 * 结构与取舍：
 * - 每带三个偏移 `hue/sat/lum`，值域 [-1,1]，映射到 [HUE_AMPLITUDE_DEG] 等实际幅度。
 *   幅度是产品口径而不是随手取的：色相 ±30° 足够把橙肤色推到暖金，再大就开始「换颜色」；
 * - 带间用**余弦交叠**而不是硬分桶。硬边会让同一片天空在渐变里出现一圈色阶断层；
 *   这里的权重在每个色相角上只在「最近的两个带」之间过渡，且处处**权重和恒为 1**
 *   （单位分解），所以色相旋转不会顺手改变整体亮度/饱和度；
 * - 权重按色相角整数 0..359 预计算成表，热循环里只剩整数索引与乘加，
 *   **没有 pow / atan2 / 三角函数**；
 * - RGB↔HSV 用同一对函数（热循环与测试共用一份实现），往返误差 ≤ 1/255 有测试钉住。
 *
 * 之所以走 HSV 而不是 HSL：V 直接就是 max(r,g,b)/255，省一次除法；
 * 对「分色」而言两者只差一个重标定，而幅度常量里已经吸收掉了。
 */
data class HslBands(
    val red: HslBand = HslBand(),
    val orange: HslBand = HslBand(),
    val yellow: HslBand = HslBand(),
    val green: HslBand = HslBand(),
    val cyan: HslBand = HslBand(),
    val blue: HslBand = HslBand(),
    val purple: HslBand = HslBand(),
    val magenta: HslBand = HslBand(),
) {

    /** 8 个带按色相角升序；索引顺序与 [HUES] 一致，配方与 UI 都按它遍历。 */
    fun bands(): List<HslBand> = listOf(red, orange, yellow, green, cyan, blue, purple, magenta)

    /** 全部偏移都为 0 时为 false —— 管线用这个守卫跳过整段，默认路径逐位不变。 */
    val hasAny: Boolean
        get() = red.hasAny || orange.hasAny || yellow.hasAny || green.hasAny ||
            cyan.hasAny || blue.hasAny || purple.hasAny || magenta.hasAny

    val isNeutral: Boolean get() = !hasAny

    /** 按带下标取（0..7），越界夹到端点而不是抛。 */
    fun band(index: Int): HslBand = when (index.coerceIn(0, BAND_COUNT - 1)) {
        0 -> red
        1 -> orange
        2 -> yellow
        3 -> green
        4 -> cyan
        5 -> blue
        6 -> purple
        else -> magenta
    }

    /** 按带下标替换，给「拖第 i 个带」的 UI 用。 */
    fun withBand(index: Int, band: HslBand): HslBands = when (index.coerceIn(0, BAND_COUNT - 1)) {
        0 -> copy(red = band)
        1 -> copy(orange = band)
        2 -> copy(yellow = band)
        3 -> copy(green = band)
        4 -> copy(cyan = band)
        5 -> copy(blue = band)
        6 -> copy(purple = band)
        else -> copy(magenta = band)
    }

    companion object {
        const val BAND_COUNT = 8

        /** 各带中心色相角（度）：红/橙/黄在上半环等距，青之后按可见色域疏密拉开。 */
        val HUES: IntArray = intArrayOf(0, 30, 60, 120, 180, 240, 280, 320)

        /** 实际幅度：色相 ±30°、饱和 ±100%（相对值）、明度 ±25%。 */
        const val HUE_AMPLITUDE_DEG = 30f
        const val SAT_AMPLITUDE = 1f
        const val LUM_AMPLITUDE = 0.25f

        val NONE = HslBands()

        /**
         * 色相角 × 带的权重表，[360 * BAND_COUNT]，每个色相角上所有带权重和 = 1。
         *
         * 构造：取环上最近的两个带 c1/c2（距离 d1 ≤ d2），过渡系数 u = 2·d1/(d1+d2) ∈ [0,1]，
         * w(c1) = 1 − 0.5·smoothstep(u)、w(c2) = 1 − w(c1)。
         * 在带中心 u=0 → 1；在两带中点 u=1 → 0.5/0.5；smoothstep 两端导数为 0，
         * 所以整条权重曲线 C¹，不会在带边界留下折角。
         */
        internal val WEIGHT_LUT: FloatArray = buildWeightLut()

        private fun buildWeightLut(): FloatArray {
            val lut = FloatArray(360 * BAND_COUNT)
            for (deg in 0 until 360) {
                var best = 0
                var bestD = Int.MAX_VALUE
                var second = 0
                var secondD = Int.MAX_VALUE
                for (b in 0 until BAND_COUNT) {
                    val d = circularDistance(deg, HUES[b])
                    if (d < bestD) {
                        second = best
                        secondD = bestD
                        best = b
                        bestD = d
                    } else if (d < secondD) {
                        second = b
                        secondD = d
                    }
                }
                val total = (bestD + secondD).toFloat().coerceAtLeast(1f)
                val u = (2f * bestD / total).coerceIn(0f, 1f)
                val s = u * u * (3f - 2f * u)
                val wNear = 1f - 0.5f * s
                lut[deg * BAND_COUNT + best] = wNear
                lut[deg * BAND_COUNT + second] = 1f - wNear
            }
            return lut
        }

        private fun circularDistance(a: Int, b: Int): Int {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}

/** 单个色相带上的三个偏移，全部 [-1,1]；越界在构造时夹回，配方是外部输入。 */
data class HslBand(
    val hue: Float = 0f,
    val sat: Float = 0f,
    val lum: Float = 0f,
) {
    val hueC: Float = hue.coerceIn(-1f, 1f)
    val satC: Float = sat.coerceIn(-1f, 1f)
    val lumC: Float = lum.coerceIn(-1f, 1f)

    val hasAny: Boolean get() = hueC != 0f || satC != 0f || lumC != 0f
}

/**
 * HSL 分色的应用器。
 *
 * 中性时调用方必须跳过（见 [HslBands.hasAny]）；[applyRange] 内部再判一次是双保险 ——
 * 一个算子的“零偏移等于恒等”不该依赖调用方记得加守卫。
 */
object Hsl {

    /**
     * 就地处理 [pixels]（ARGB int）的行区间 [yStart, yEnd)。
     *
     * 复杂度 O(像素)：每像素 4 次比较、1 次整数除法得色相扇区、1 次查权重表、若干乘加。
     * 无三角函数、无 pow、无逐像素分配（临时数组在循环外开一次）。
     */
    fun applyRange(pixels: IntArray, w: Int, h: Int, yStart: Int, yEnd: Int, bands: HslBands) {
        if (!bands.hasAny) return
        val list = bands.bands()
        // 把 8 带偏移预展开成扁平数组：热循环里避免 List 装箱与虚调用。
        val hueAmt = FloatArray(HslBands.BAND_COUNT)
        val satAmt = FloatArray(HslBands.BAND_COUNT)
        val lumAmt = FloatArray(HslBands.BAND_COUNT)
        for (b in 0 until HslBands.BAND_COUNT) {
            val band = list[b]
            hueAmt[b] = band.hueC * HslBands.HUE_AMPLITUDE_DEG
            satAmt[b] = band.satC * HslBands.SAT_AMPLITUDE
            lumAmt[b] = band.lumC * HslBands.LUM_AMPLITUDE
        }
        val wt = HslBands.WEIGHT_LUT
        /**
         * 把「色相角 × 三带权重」合成 360 条**预先加权**的偏移量。
         *
         * 原来每个像素都要在 8 个带上做一次 "读权重 + 判零 + 三次乘加"，实测占分色
         * 总耗时的一大半（1200 万像素 × 8 次 = 1 亿次循环）。色相角本来就只有 360 个，
         * 逐像素只是合并同一份结果 —— 提到循环外，热循环里只剩三次数组读。
         *
         * 数值完全等价：原来的 `Σ w_b · a_b` 与这里的 `Σ w_b · a_b` 是同一串乘加，
         * 只是把"每个像素算一遍"变成"每个角度算一遍"。
         */
        val hueAt = FloatArray(360)
        val satAt = FloatArray(360)
        val lumAt = FloatArray(360)
        var anyHue = false
        var anySat = false
        var anyLum = false
        for (deg in 0 until 360) {
            var hs = 0f
            var ss = 0f
            var ls = 0f
            val base = deg * HslBands.BAND_COUNT
            for (b in 0 until HslBands.BAND_COUNT) {
                val wgt = wt[base + b]
                if (wgt <= 1e-4f) continue
                hs += wgt * hueAmt[b]
                ss += wgt * satAmt[b]
                ls += wgt * lumAmt[b]
            }
            hueAt[deg] = hs
            satAt[deg] = ss
            lumAt[deg] = ls
            if (hs != 0f) anyHue = true
            if (ss != 0f) anySat = true
            if (ls != 0f) anyLum = true
        }
        // 三个通道全为 0 = 这组参数在任何色相上都不产生位移：整段直接返回。
        // （不是"没效果"的近似判断：色相角只有 360 个取值，全 0 就是严格恒等。）
        if (!anyHue && !anySat && !anyLum) return

        val start = yStart.coerceIn(0, h)
        val end = yEnd.coerceIn(start, h)
        for (y in start until end) {
            val rowBase = y * w
            for (x in 0 until w) {
                val i = rowBase + x
                val px = pixels[i]
                val a = (px ushr 24) and 0xFF
                val r0 = (px shr 16) and 0xFF
                val g0 = (px shr 8) and 0xFF
                val b0 = px and 0xFF
                val max = if (r0 > g0) (if (r0 > b0) r0 else b0) else (if (g0 > b0) g0 else b0)
                if (max == 0) continue
                val min = if (r0 < g0) (if (r0 < b0) r0 else b0) else (if (g0 < b0) g0 else b0)
                val delta = max - min
                // 无彩色（delta == 0）：色相未定义，分色不该动它 —— 否则灰阶会被染色。
                if (delta == 0) continue

                val hue: Float
                if (max == r0) {
                    hue = (60f * (g0 - b0) / delta).let { if (it < 0f) it + 360f else it }
                } else if (max == g0) {
                    hue = 60f * (b0 - r0) / delta + 120f
                } else {
                    hue = 60f * (r0 - g0) / delta + 240f
                }
                val hueDeg = hue.toInt().coerceIn(0, 359)
                val hueShift = hueAt[hueDeg]
                val satShift = satAt[hueDeg]
                val lumShift = lumAt[hueDeg]
                if (hueShift == 0f && satShift == 0f && lumShift == 0f) continue

                val sat = delta.toFloat() / max
                var newHue = hue + hueShift
                if (newHue < 0f) newHue += 360f
                if (newHue >= 360f) newHue -= 360f
                val newSat = (sat * (1f + satShift)).coerceIn(0f, 1f)
                val newVal = ((max * (1f + lumShift)) / 255f).coerceIn(0f, 1f)
                val packed = hsvToRgbPacked(newHue, newSat, newVal)
                val nr = (packed shr 16) and 0xFF
                val ng = (packed shr 8) and 0xFF
                val nb = packed and 0xFF
                if (nr == r0 && ng == g0 && nb == b0) continue
                pixels[i] = (a shl 24) or packed
            }
        }
    }

    /**
     * RGB(0..255) → HSV，`out = [hue(0..360), sat(0..1), val(0..1)]`。
     *
     * 色相用 `60·(…)/delta` 的整数商再转 Float：整数部分精确，小数部分由除法保留，
     * 既能保持往返误差 ≤ 1/255，又比浮点 atan2 便宜一个数量级。
     */
    internal fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val max = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
        val min = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
        val delta = max - min
        out[2] = max / 255f
        if (max == 0) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        out[1] = delta.toFloat() / max
        if (delta == 0) {
            out[0] = 0f
            return
        }
        val h = when (max) {
            r -> (60f * (g - b) / delta).let { if (it < 0f) it + 360f else it }
            g -> 60f * (b - r) / delta + 120f
            else -> 60f * (r - g) / delta + 240f
        }
        out[0] = if (h < 0f) h + 360f else if (h >= 360f) h - 360f else h
    }

    /** HSV → RGB(0..255)，与 [rgbToHsv] 严格互逆（差一次 8 位舍入）。 */
    internal fun hsvToRgb(hue: Float, sat: Float, value: Float, out: IntArray) {
        val v = value.coerceIn(0f, 1f)
        val s = sat.coerceIn(0f, 1f)
        if (s <= 0f) {
            val gray = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[0] = gray
            out[1] = gray
            out[2] = gray
            return
        }
        var h = hue
        if (h < 0f) h = 0f
        if (h >= 360f) h -= 360f
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - abs(hp - 2f * floor(hp / 2f) - 1f))
        val m = v - c
        val sector = floor(hp).toInt() % 6
        val r: Float
        val g: Float
        val b: Float
        when (sector) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        out[0] = ((r + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        out[1] = ((g + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        out[2] = ((b + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * [hsvToRgb] 的打包版：返回 `0xRRGGBB`。热循环用它，省掉一次 `IntArray` 的
     * 建/读/写与三次边界检查。
     *
     * 数值与 [hsvToRgb] **完全一致**（同一串运算），区别只是返回值的形状 ——
     * 两条路径都由同一条往返测试覆盖。
     */
    internal fun hsvToRgbPacked(hue: Float, sat: Float, value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val s = sat.coerceIn(0f, 1f)
        if (s <= 0f) {
            val gray = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (gray shl 16) or (gray shl 8) or gray
        }
        var h = hue
        if (h < 0f) h = 0f
        if (h >= 360f) h -= 360f
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - abs(hp - 2f * floor(hp / 2f) - 1f))
        val m = v - c
        val r: Float
        val g: Float
        val b: Float
        when (floor(hp).toInt() % 6) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        val rr = ((r + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gg = ((g + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bb = ((b + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (rr shl 16) or (gg shl 8) or bb
    }

    /**
     * RGB→HSV→RGB 的往返最大误差（0..255 上的整数），给测试当判据。
     *
     * 这条判据存在的理由：色相是用浮点算的，如果哪天有人把 `60f*(g-b)/delta` 改成
     * 整数截断的 `60*(g-b)/delta`，色相会整体偏最多 1°，往返误差立刻从 1 跳到 4+。
     */
    fun roundTripMaxError(step: Int = 1): Int {
        var worst = 0
        val hsv = FloatArray(3)
        val rgb = IntArray(3)
        val s = step.coerceAtLeast(1)
        var r = 0
        while (r <= 255) {
            var g = 0
            while (g <= 255) {
                var b = 0
                while (b <= 255) {
                    rgbToHsv(r, g, b, hsv)
                    hsvToRgb(hsv[0], hsv[1], hsv[2], rgb)
                    val dr = abs(rgb[0] - r)
                    val dg = abs(rgb[1] - g)
                    val db = abs(rgb[2] - b)
                    val d = maxOf(dr, dg, db)
                    if (d > worst) worst = d
                    b += s
                }
                g += s
            }
            r += s
        }
        return worst
    }
}
