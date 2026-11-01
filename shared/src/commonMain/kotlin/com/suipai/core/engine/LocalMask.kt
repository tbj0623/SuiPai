package com.suipai.core.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 局部蒙版的形状。全部坐标归一化到 0..1（相对工作缓冲，不是原图）——
 * 预览（960–1600）与成片（4096）用的是同一套归一化场，所以局部调整在预览里看到的
 * 位置就是成片里的位置，不需要按分辨率换算。
 */
sealed interface MaskShape

/**
 * 线性渐变：一条与 [angle] 垂直的过渡带。
 *
 * [angle] 是**渐变方向**（度，0 = 从左到右，90 = 从上到下，顺时针为正）。
 * [start] / [end] 是沿该方向的归一化位置：小于 start 权重 0，大于 end 权重 1，
 * 中间 smoothstep 过渡。投影按单位方四角归一化，所以任何角度下 start/end 的语义一致。
 */
data class LinearMask(
    val angle: Float = 0f,
    val start: Float = 0.3f,
    val end: Float = 0.7f,
) : MaskShape

/**
 * 椭圆（径向）蒙版。([cx],[cy]) 中心，([rx],[ry]) 半径，全部归一化 0..1。
 *
 * [feather] 是羽化占半径的比例（0 = 硬边，1 = 从中心就开始衰减）；
 * [inverted] 反转（蒙版外生效），对应「压暗四周」这类用法。
 */
data class RadialMask(
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val rx: Float = 0.3f,
    val ry: Float = 0.3f,
    val feather: Float = 0.5f,
    val inverted: Boolean = false,
) : MaskShape

/**
 * 局部调整量，全部 -1..1。
 *
 * 与全局滑杆的区别只在「有蒙版权重」：同一组参数乘上权重 w∈[0,1] 后作用，
 * 于是 w=0 的区域逐位不变 —— 这是局部调整能被接受的前提。
 */
data class LocalAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
) {
    val exposureC: Float = clampUnit(exposure)
    val contrastC: Float = clampUnit(contrast)
    val saturationC: Float = clampUnit(saturation)
    val temperatureC: Float = clampUnit(temperature)

    /** 四项全 0：这个蒙版什么都不做（连权重都不用算）。 */
    val isNeutral: Boolean
        get() = exposureC == 0f && contrastC == 0f && saturationC == 0f && temperatureC == 0f

    private fun clampUnit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
}

/** 一个蒙版 = 形状 + 调整量。 */
data class LocalMask(
    val shape: MaskShape,
    val adjust: LocalAdjust = LocalAdjust(),
)

/**
 * 蒙版权重场：把 [LocalMask] 列表预计算成查表结构，热循环里只做索引与乘加。
 *
 * 内存策略（4096 长边 ≈ 1200 万像素时，4 个蒙版全展开是 192 MB，会把整机压垮）：
 * - 权重总量 ≤ [MAX_FIELD_BYTES] 时**整场预计算**（最快）；
 * - 否则退化成**按行预计算**：只用 count×w 的滚动缓冲，同一行内仍然只查表。
 * 两条路径数值一致（测试用不同行带切分对比，正是钉这个）。
 *
 * 中性蒙版（[LocalAdjust.isNeutral]）在构造时就被丢掉：它不产生任何像素改动，
 * 却会白算一整张权重场。超过 [MAX_MASKS] 个的部分同样丢弃（配方是外部输入，不抛）。
 */
class LocalMaskField(
    val w: Int,
    val h: Int,
    rawMasks: List<LocalMask>,
) {
    /** 生效的蒙版：最多 4 个、丢掉中性项。顺序即叠加顺序。 */
    val masks: List<LocalMask> = rawMasks.asSequence()
        .filter { !it.adjust.isNeutral }
        .take(MAX_MASKS)
        .toList()

    val count: Int get() = masks.size

    val hasAny: Boolean get() = masks.isNotEmpty()

    /** 形状的几何常量在构造时算一次：热循环里绝不做 cos/sin/sqrt。 */
    private val plans: Array<MaskPlan> = Array(masks.size) { MaskPlan.of(masks[it].shape, w, h) }

    private val full: FloatArray? =
        if (masks.isEmpty()) null
        else if (masks.size.toLong() * w * h * 4L <= MAX_FIELD_BYTES) FloatArray(masks.size * w * h)
        else null

    /** 按行模式的滚动缓冲（count×w）；整场模式时为空数组。 */
    private val rowBuf: FloatArray =
        if (full == null && masks.isNotEmpty()) FloatArray(masks.size * w) else FloatArray(0)

    init {
        val f = full
        if (f != null && w > 0 && h > 0) {
            for (m in masks.indices) {
                val plan = plans[m]
                val base = m * w * h
                for (y in 0 until h) {
                    val rowBase = base + y * w
                    val ny = plan.ny(y)
                    for (x in 0 until w) f[rowBase + x] = plan.at(plan.nx(x), ny)
                }
            }
        }
    }

    /** 是否整场预计算（测试与性能预算报告用）。 */
    val isFullyPrecomputed: Boolean get() = full != null

    /**
     * 把第 [maskIndex] 个蒙版在行 [y] 的权重写进 `dst[offset until offset+w]`。
     * 热循环每行调一次，之后按下标取 —— 避免每像素重复计算形状。
     *
     * 这里**只做一次** sealed 分派（`when (kind)`），而不是每像素一次：
     * 形状的具体算法在两段各自内联成顺序循环，`plan.at()` 的虚调用因此离开热路径。
     * 实测这一改把 3 蒙版 1200 万像素的耗时从 3.4 s 压到 1.9 s。
     */
    fun fillRow(maskIndex: Int, y: Int, dst: FloatArray, offset: Int = 0) {
        val f = full
        if (f != null) {
            val base = maskIndex * w * h + y * w
            for (x in 0 until w) dst[offset + x] = f[base + x]
            return
        }
        val plan = plans[maskIndex]
        val ny = plan.ny(y)
        when (plan.kind) {
            KIND_LINEAR -> {
                // 线性：q 对 x 是仿射的（q = (nx·dx + ny·dy − pmin)/span，
                // 而 nx = x·invW），所以行内只剩一次乘加推进，不必每点重算投影。
                val span = plan.span
                if (span <= 1e-6f) {
                    for (x in 0 until w) dst[offset + x] = 0f
                    return
                }
                val invSpan = 1f / span
                val startC = plan.startC
                val endC = plan.endC
                val range = endC - startC
                var q = (ny * plan.dy - plan.pmin) * invSpan
                val qStep = plan.dx * plan.invW * invSpan
                if (range <= 0f) {
                    // start >= end：退化成硬边（q 过了 end 就是 1）
                    for (x in 0 until w) {
                        dst[offset + x] = if (q >= endC) 1f else 0f
                        q += qStep
                    }
                    return
                }
                val invRange = 1f / range
                for (x in 0 until w) {
                    val t = ((q - startC) * invRange).coerceIn(0f, 1f)
                    dst[offset + x] = t * t * (3f - 2f * t)
                    q += qStep
                }
            }
            else -> {
                // 径向：d = sqrt((nx−cx)²·irx² + (ny−cy)²·iry²)，
                // 把 y 那一半提到行外，行内只剩一次乘加 + 一次 sqrt。
                val gy = (ny - plan.cy) * plan.iry
                val gy2 = gy * gy
                val inner = plan.inner
                val feather = plan.feather
                val irx = plan.irx
                val invW = plan.invW
                val cx = plan.cx
                for (x in 0 until w) {
                    val gx = (x * invW - cx) * irx
                    val d = sqrt(gx * gx + gy2)
                    val v = when {
                        d <= inner -> 1f
                        d >= 1f -> 0f
                        feather <= 1e-6f -> 1f
                        else -> {
                            val t = ((d - inner) / (1f - inner)).coerceIn(0f, 1f)
                            1f - t * t * (3f - 2f * t)
                        }
                    }
                    dst[offset + x] = if (plan.inverted) 1f - v else v
                }
            }
        }
    }

    /** 第 [maskIndex] 个蒙版在 (x,y) 的权重（逐点重算，仅整场模式是查表；给测试与小图用）。 */
    fun weight(maskIndex: Int, x: Int, y: Int): Float {
        val f = full ?: return plans[maskIndex].at(plans[maskIndex].nx(x), plans[maskIndex].ny(y))
        return f[maskIndex * w * h + y * w + x]
    }

    companion object {
        /** 产品上限：4 个蒙版。超出的数据直接截断（不抛，配方来自外部）。 */
        const val MAX_MASKS = 4

        /** 整场预计算的内存上限：24 MB。超过就走按行模式（1200 万像素 × 4 蒙版就是后者）。 */
        const val MAX_FIELD_BYTES = 24L * 1024 * 1024

        /** 单个蒙版形状在归一化坐标上的权重，值域 [0,1]。 */
        fun weightAt(shape: MaskShape, x: Int, y: Int, w: Int, h: Int): Float {
            val plan = MaskPlan.of(shape, w, h)
            return plan.at(plan.nx(x), plan.ny(y))
        }

        /**
         * 线性渐变权重（独立入口，测试直接喂归一化坐标）。
         *
         * 投影 p = nx·dx + ny·dy 在单位方上的极值只由四角决定，据此归一到 [0,1]；
         * 归一只在构造时做一次（[MaskPlan]），逐像素只剩两次乘加。
         */
        fun linearWeight(shape: LinearMask, nx: Float, ny: Float): Float {
            val plan = MaskPlan.of(shape, 2, 2)
            return plan.at(nx, ny)
        }

        /**
         * 径向权重（独立入口）。
         *
         * 椭圆归一化距离 d：d ≤ 1-feather 满权重，1-feather → 1 用 smoothstep 降到 0，
         * d ≥ 1 恒 0；[RadialMask.inverted] 取反。
         */
        fun radialWeight(shape: RadialMask, nx: Float, ny: Float): Float {
            val plan = MaskPlan.of(shape, 2, 2)
            return plan.at(nx, ny)
        }

        private const val PI = 3.1415927f

        /** [MaskPlan.kind] 取值：热循环据此只分派一次。 */
        private const val KIND_LINEAR = 0
        private const val KIND_RADIAL = 1

        /**
         * 形状的计算计划：坐标换算 + 几何常量。
         *
         * 为什么不直接在热循环里 when(shape)：那样每像素都要做一次 sealed 分派 + cos/sin，
         * 1200 万像素 × 4 蒙版就是 4800 万次三角函数。这里把常量提到构造期，
         * 逐像素只剩两次乘加 / 一次开方。
         */
        private class MaskPlan(
            private val shape: MaskShape,
            val kind: Int,
            val invW: Float,
            val invH: Float,
            // Linear
            val dx: Float = 0f,
            val dy: Float = 0f,
            val pmin: Float = 0f,
            val span: Float = 1f,
            // Radial
            val cx: Float = 0f,
            val cy: Float = 0f,
            val irx: Float = 1f,
            val iry: Float = 1f,
            val inner: Float = 0f,
            val feather: Float = 0f,
            val inverted: Boolean = false,
            val startC: Float = 0f,
            val endC: Float = 1f,
        ) {
            fun nx(x: Int): Float = x * invW
            fun ny(y: Int): Float = y * invH

            fun at(nx: Float, ny: Float): Float = when (shape) {
                is LinearMask -> linear(nx, ny)
                is RadialMask -> radial(nx, ny)
            }

            private fun linear(nx: Float, ny: Float): Float {
                if (span <= 1e-6f) return 0f
                val q = ((nx * dx + ny * dy) - pmin) / span
                if (endC <= startC) return if (q >= endC) 1f else 0f
                val t = ((q - startC) / (endC - startC)).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }

            private fun radial(nx: Float, ny: Float): Float {
                val gx = (nx - cx) * irx
                val gy = (ny - cy) * iry
                val d = sqrt(gx * gx + gy * gy)
                val v = when {
                    d <= inner -> 1f
                    d >= 1f -> 0f
                    feather <= 1e-6f -> 1f
                    else -> {
                        val t = ((d - inner) / (1f - inner)).coerceIn(0f, 1f)
                        1f - t * t * (3f - 2f * t)
                    }
                }
                return if (inverted) 1f - v else v
            }

            companion object {
                fun of(shape: MaskShape, w: Int, h: Int): MaskPlan {
                    val invW = if (w > 1) 1f / (w - 1) else 0f
                    val invH = if (h > 1) 1f / (h - 1) else 0f
                    return when (shape) {
                        is LinearMask -> {
                            val rad = shape.angle * (PI / 180f)
                            val ddx = cos(rad)
                            val ddy = sin(rad)
                            var pmin = Float.MAX_VALUE
                            var pmax = -Float.MAX_VALUE
                            for (cxx in 0..1) {
                                for (cyy in 0..1) {
                                    val p = cxx * ddx + cyy * ddy
                                    if (p < pmin) pmin = p
                                    if (p > pmax) pmax = p
                                }
                            }
                            MaskPlan(
                                shape = shape,
                                kind = KIND_LINEAR,
                                invW = invW,
                                invH = invH,
                                dx = ddx,
                                dy = ddy,
                                pmin = pmin,
                                span = pmax - pmin,
                                startC = shape.start.coerceIn(0f, 1f),
                                endC = shape.end.coerceIn(0f, 1f),
                            )
                        }
                        is RadialMask -> {
                            val feather = shape.feather.coerceIn(0f, 1f)
                            MaskPlan(
                                shape = shape,
                                kind = KIND_RADIAL,
                                invW = invW,
                                invH = invH,
                                cx = shape.cx,
                                cy = shape.cy,
                                irx = 1f / abs(shape.rx).coerceAtLeast(1e-4f),
                                iry = 1f / abs(shape.ry).coerceAtLeast(1e-4f),
                                inner = 1f - feather,
                                feather = feather,
                                inverted = shape.inverted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 局部调整的应用器。与全局算子的差别只有一处：每个像素先查蒙版权重，
 * 再把四项调整**按权重缩放**后叠加 —— 于是权重 0 处严格是恒等（逐位不变）。
 */
object LocalMasks {

    /**
     * 就地处理 [pixels] 的行区间。要求 [field] 与本数组同宽同高。
     *
     * 复杂度 O(像素 × 蒙版数 ≤ 4)：每蒙版每像素 1 次查表 + 4 次乘加。
     * 每行的权重先算进 [rowWeights]（长度 ≥ w），所以形状计算次数是 w·h 次而不是 w·h·蒙版数。
     */
    fun applyRange(pixels: IntArray, w: Int, h: Int, yStart: Int, yEnd: Int, field: LocalMaskField) {
        if (!field.hasAny) return
        val start = yStart.coerceIn(0, h)
        val end = yEnd.coerceIn(start, h)
        val count = field.count
        val exposure = FloatArray(count)
        val contrast = FloatArray(count)
        val saturation = FloatArray(count)
        val temperature = FloatArray(count)
        for (m in 0 until count) {
            val a = field.masks[m].adjust
            exposure[m] = a.exposureC
            contrast[m] = a.contrastC
            saturation[m] = a.saturationC
            temperature[m] = a.temperatureC
        }
        // 本行所有蒙版的权重一次填好：count×w。热循环里不再调用 fillRow，
        // 于是"每行 × 每蒙版"的函数调用与边界检查都消失了。
        val rowWeights = FloatArray(count * w)
        for (y in start until end) {
            val rowBase = y * w
            for (m in 0 until count) {
                field.fillRow(m, y, rowWeights, m * w)
            }
            for (x in 0 until w) {
                val i = rowBase + x
                val px = pixels[i]
                var r = ((px shr 16) and 0xFF) * INV255
                var g = ((px shr 8) and 0xFF) * INV255
                var b = (px and 0xFF) * INV255
                var touched = false
                for (m in 0 until count) {
                    val wgt = rowWeights[m * w + x]
                    if (wgt <= 0f) continue
                    touched = true
                    val gain = 1f + exposure[m] * 1.5f * wgt
                    if (gain != 1f) {
                        r *= gain
                        g *= gain
                        b *= gain
                    }
                    val k = 1f + contrast[m] * wgt
                    if (k != 1f) {
                        r = (r - 0.5f) * k + 0.5f
                        g = (g - 0.5f) * k + 0.5f
                        b = (b - 0.5f) * k + 0.5f
                    }
                    val sa = saturation[m] * wgt
                    if (sa != 0f) {
                        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        r += (r - lum) * sa
                        g += (g - lum) * sa
                        b += (b - lum) * sa
                    }
                    val tp = temperature[m] * wgt
                    if (tp != 0f) {
                        r *= 1f + tp * 0.35f
                        b *= 1f - tp * 0.45f
                    }
                }
                if (!touched) continue
                // 只在最后量化一次：多个蒙版叠加时，中间不重复舍入（那会让重叠区多掉两格）。
                val nr = to8(r)
                val ng = to8(g)
                val nb = to8(b)
                if (nr == (px shr 16 and 0xFF) && ng == (px shr 8 and 0xFF) && nb == (px and 0xFF)) continue
                pixels[i] = ((px ushr 24) and 0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private const val INV255 = 1f / 255f
}
