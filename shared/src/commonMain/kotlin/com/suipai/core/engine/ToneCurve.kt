package com.suipai.core.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 曲线控制点，x/y 都在 [0,1]。
 *
 * 为什么要单独一个类型而不是 `Pair<Float, Float>`：配方、UI、iOS 三处都要按名字取值，
 * 用 Pair 之后 `first`/`second` 谁是谁只能靠记性——曲线是最容易被“x/y 写反”毁掉的算子。
 */
data class CurvePoint(val x: Float, val y: Float)

/**
 * 一条色调曲线（控制点 + 单调三次插值）。
 *
 * 插值用 **Fritsch–Carlson 单调三次 Hermite**，不用自然三次样条：
 * 自然样条在陡峭段落会过冲（两个控制点之间鼓出一个亮包），曲线是用户直接拖出来的，
 * 过冲会让“我没动那里，画面怎么亮了一块”变成 bug 报告；单调 Hermite 保证
 * **段内不越界、整条 LUT 单调不减**，这两条都有测试钉住。
 *
 * 输入是外部数据（配方 / 用户拖拽），所以构造**永不抛异常**：
 * 非有限值丢弃、越界夹回、x 乱序自动排序、同 x 去重、点数不足补成恒等、超过 16 个抽稀。
 *
 * 恒等判据见 [isIdentity]：恒等曲线在管线里直接跳过（见 [ToneCurves.hasAny]），
 * 这样“没调曲线”和“调了一条 y=x”的输出逐位相同。
 */
class ToneCurve(controlPoints: List<CurvePoint>) {

    /** 归一化后的控制点：x 升序、去重、y 单调不减、数量 2..16。 */
    val points: List<CurvePoint> = normalize(controlPoints)

    private val xs: FloatArray = FloatArray(this.points.size) { this.points[it].x }
    private val ys: FloatArray = FloatArray(this.points.size) { this.points[it].y }

    /** Fritsch–Carlson 切线，构造时算一次。 */
    private val ms: FloatArray = tangents(xs, ys)

    private val cachedLut = lazy { buildLut() }

    /** 缓存的 1024 点 LUT（内部用；[buildLut] 可以要别的尺寸）。 */
    private val lut: FloatArray get() = cachedLut.value

    /**
     * 恒等判据：LUT 每一点与 y=x 的偏差都 ≤ [IDENTITY_EPS]。
     *
     * 不做“控制点是否都在对角线上”的结构判断，因为那判不出「只有两个点在线上、
     * 其余段落被 Hermite 顶出去」的情况；直接量 LUT 才是用户真正会看到的差别。
     */
    val isIdentity: Boolean by lazy {
        var maxDev = 0f
        val n = LUT_SIZE - 1f
        for (i in 0 until LUT_SIZE) {
            val d = abs(lut[i] - i / n)
            if (d > maxDev) maxDev = d
            if (maxDev > IDENTITY_EPS) return@lazy false
        }
        true
    }

    /** [x] 处的曲线值（直接求值，不查 LUT；给测试与烘焙用）。 */
    fun apply(x: Float): Float {
        if (x.isNaN()) return 0f
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var seg = 0
        while (seg < n - 2 && x > xs[seg + 1]) seg++
        return hermite(seg, x)
    }

    /**
     * 1024 点 LUT（默认），保证单调不减且值域 [0,1]。
     *
     * 单调性的来源有两层：控制点 y 在 [normalize] 里已经被钳成不减；
     * Hermite 段内再夹到两端点之间，避免浮点尾差把两个相等值排出 -1e-7 的逆序。
     */
    fun buildLut(size: Int = LUT_SIZE): FloatArray {
        require(size >= 2) { "LUT 至少 2 点，收到 $size" }
        val out = FloatArray(size)
        val n = xs.size
        var seg = 0
        val last = size - 1f
        for (i in 0 until size) {
            val x = i / last
            if (x <= xs[0]) {
                out[i] = ys[0]
                continue
            }
            if (x >= xs[n - 1]) {
                out[i] = ys[n - 1]
                continue
            }
            while (seg < n - 2 && x > xs[seg + 1]) seg++
            out[i] = hermite(seg, x)
        }
        return out
    }

    private fun hermite(seg: Int, x: Float): Float {
        val x0 = xs[seg]
        val x1 = xs[seg + 1]
        val h = x1 - x0
        val t = if (h <= 0f) 1f else ((x - x0) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        val y = (2f * t3 - 3f * t2 + 1f) * ys[seg] +
            (t3 - 2f * t2 + t) * h * ms[seg] +
            (-2f * t3 + 3f * t2) * ys[seg + 1] +
            (t3 - t2) * h * ms[seg + 1]
        val lo = minOf(ys[seg], ys[seg + 1])
        val hi = maxOf(ys[seg], ys[seg + 1])
        return y.coerceIn(lo, hi).coerceIn(0f, 1f)
    }

    override fun equals(other: Any?): Boolean = other is ToneCurve && other.points == points

    override fun hashCode(): Int = points.hashCode()

    override fun toString(): String = "ToneCurve($points)"

    companion object {
        /** 引擎内部 LUT 长度，与 [ColorCore] 的 toneLut 同口径。 */
        const val LUT_SIZE = 1024

        /** 与 y=x 的最大偏差小于它就算恒等（量化到 8 位后完全看不出）。 */
        const val IDENTITY_EPS = 2e-4f

        /** 控制点数量上下限：1 个点定义不了曲线，超过 16 个在 UI 上也没法拖。 */
        const val MIN_POINTS = 2
        const val MAX_POINTS = 16

        /** 恒等曲线（两端点），给“显式给一条 y=x”的调用方用。 */
        val IDENTITY = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))

        /**
         * 恒等的两点控制点。
         *
         * 写成函数而不是 `val`：它在 [normalize] 里被引用，而 [IDENTITY] 的构造又会
         * 反过来调用 [normalize]。用 val 的话，静态初始化顺序一旦被重排，
         * 这里读到的就是 null（Kotlin/Native 上直接崩）。
         */
        private fun identityPoints(): List<CurvePoint> =
            listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

        private fun normalize(input: List<CurvePoint>): List<CurvePoint> {
            val clean = ArrayList<CurvePoint>(input.size)
            for (p in input) {
                if (!p.x.isFinite() || !p.y.isFinite()) continue
                clean += CurvePoint(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
            }
            if (clean.isEmpty()) return identityPoints()
            clean.sortWith(compareBy({ it.x }, { it.y }))
            // 同一个 x 上多个控制点 = 曲线不是函数：取平均，保持“一个 x 一个 y”。
            val merged = ArrayList<CurvePoint>(clean.size)
            var i = 0
            while (i < clean.size) {
                var j = i
                var sum = 0f
                while (j < clean.size && clean[j].x - clean[i].x <= X_EPS) {
                    sum += clean[j].y
                    j++
                }
                merged += CurvePoint(clean[i].x, sum / (j - i))
                i = j
            }
            // 点数：不足补端点，过多按等间隔抽稀（保留首尾）。
            while (merged.size < MIN_POINTS) {
                val hasLeft = merged.firstOrNull()?.x?.let { it <= X_EPS } == true
                val hasRight = merged.lastOrNull()?.x?.let { it >= 1f - X_EPS } == true
                when {
                    !hasLeft -> merged.add(0, CurvePoint(0f, 0f))
                    !hasRight -> merged.add(CurvePoint(1f, 1f))
                    else -> break
                }
            }
            val capped = if (merged.size <= MAX_POINTS) merged else thin(merged, MAX_POINTS)
            // y 单调不减：引擎承诺“LUT 单调不减”，非单调输入在入口被钳平，
            // 而不是让管线里出现一段会反滚的映射（那会让直方图出现空洞）。
            var prev = 0f
            val sorted = ArrayList<CurvePoint>(capped.size)
            for (p in capped) {
                val y = maxOf(prev, p.y)
                prev = y
                sorted += CurvePoint(p.x, y)
            }
            return sorted
        }

        private fun thin(points: List<CurvePoint>, max: Int): List<CurvePoint> {
            val out = ArrayList<CurvePoint>(max)
            val step = (points.size - 1).toFloat() / (max - 1)
            for (i in 0 until max) {
                out += points[(i * step).toInt().coerceIn(0, points.size - 1)]
            }
            out[0] = points.first()
            out[max - 1] = points.last()
            return out
        }

        private const val X_EPS = 1e-6f

        /** Fritsch–Carlson：先取中心差分，再按充分条件压回单调区间。 */
        private fun tangents(xs: FloatArray, ys: FloatArray): FloatArray {
            val n = xs.size
            if (n < 2) return FloatArray(n) { 0f }
            val d = FloatArray(n - 1) { i ->
                val h = xs[i + 1] - xs[i]
                if (h <= 0f) 0f else (ys[i + 1] - ys[i]) / h
            }
            val m = FloatArray(n)
            if (n == 2) {
                m[0] = d[0]
                m[1] = d[0]
                return m
            }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) * 0.5f
            for (i in 0 until n - 1) {
                val di = d[i]
                if (di == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                } else {
                    val a = m[i] / di
                    val b = m[i + 1] / di
                    val s = a * a + b * b
                    if (s > 9f) {
                        val tau = 3f / sqrt(s)
                        m[i] = tau * a * di
                        m[i + 1] = tau * b * di
                    }
                }
            }
            // 切线符号必须与割线一致，否则段内会先反向再回来（过冲/下凹）。
            for (i in 0 until n - 1) {
                val di = d[i]
                if (di > 0f) {
                    if (m[i] < 0f) m[i] = 0f
                    if (m[i + 1] < 0f) m[i + 1] = 0f
                } else if (di < 0f) {
                    if (m[i] > 0f) m[i] = 0f
                    if (m[i + 1] > 0f) m[i + 1] = 0f
                } else {
                    m[i] = 0f
                    m[i + 1] = 0f
                }
            }
            return m
        }
    }
}

/**
 * 一套曲线：主通道 + R/G/B 三条独立通道，全部可空（null = 不动）。
 *
 * 为什么主通道与通道曲线是**分开的两步**而不是合成一条：合成之后用户改主曲线会连带
 * 改掉通道曲线的斜率，两条滑杆互相影响；分两步各自可解释，也方便配方逐段记录。
 */
data class ToneCurves(
    val master: ToneCurve? = null,
    val r: ToneCurve? = null,
    val g: ToneCurve? = null,
    val b: ToneCurve? = null,
) {

    /**
     * 是否“真的会改变画面”。全 null 或全是恒等曲线都算中性 ——
     * 管线用它做守卫，中性时整段不执行，默认路径因此零开销、且逐位不变。
     */
    val hasAny: Boolean = (master?.isIdentity == false) ||
        (r?.isIdentity == false) ||
        (g?.isIdentity == false) ||
        (b?.isIdentity == false)

    val isNeutral: Boolean get() = !hasAny

    val isEmpty: Boolean get() = master == null && r == null && g == null && b == null
}

/** sRGB 传递函数（烘焙曲线用，只在构造时跑，不在热循环里）。 */
internal fun srgbToLinear(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

internal fun linearToSrgb(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}
