package com.suipai.app.ui.yishun.pro

import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves

/**
 * 曲线编辑的**纯函数**：加 / 删 / 拖 / 排序 / 钳制。
 *
 * 为什么单独抽出来而不是写在 Canvas 的手势回调里：
 * 1. 这些规则（端点锁 x、中间点不许互相穿过、至少 2 点、最多 16 点）在设备上要
 *    "一格一格拖断"才看得见，而这台机器上设备侧观测通道时好时坏；
 * 2. 它们是用户手指直接打进去的**外部输入**，和配方来自 EXIF 一个性质——
 *    引擎侧 `ToneCurve.normalize` 已经做过一次兜底，但 UI 不能靠它兜：
 *    兜住的后果是"点下去控制点跳走了"，用户只会觉得编辑器坏了。
 *
 * 与引擎的分工：本文件只保证"交出去的列表是合法的、可继续编辑的"；
 * 数学（Fritsch–Carlson 插值、单调性）全在 `shared` 的 [ToneCurve] 里，
 * 权威定义见 `docs/engine/COLOR-PRO.md` §1。
 */
object CurvePoints {

    /** 与 `ToneCurve.MIN_POINTS` / `MAX_POINTS` 同源，测试钉住这两个数不许各改各的。 */
    const val MIN_POINTS = ToneCurve.MIN_POINTS
    const val MAX_POINTS = ToneCurve.MAX_POINTS

    /** 相邻控制点的最小 x 间距。小于它两点会重叠成一个，拖动时表现为"点粘住了"。 */
    const val MIN_GAP = 0.02f

    /** 恒等曲线（两端点）。新建一条通道时的起点，也是"删到只剩两端"的结果。 */
    val IDENTITY: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    /** x 升序稳定排序；调用方拿到的顺序就是画图与命中判定的顺序。 */
    fun sorted(points: List<CurvePoint>): List<CurvePoint> =
        points.filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(clamp01(it.x), clamp01(it.y)) }
            .sortedWith(compareBy({ it.x }, { it.y }))

    /**
     * 加入一个控制点，返回**新列表与新点下标**。
     *
     * 三条硬规则：
     * - 超过 [MAX_POINTS] 时**不加**（返回原列表与 -1）——不能悄悄丢掉用户已有的点；
     * - 与既有 x 差小于 [MIN_GAP] 时不加（同一个 x 上两个点会让曲线不再是函数）；
     * - **靠近端点时并入端点**（只改端点的 y）：手感上"点角落"是在调那一段的黑场/白场，
     *   而不是新增一个贴着端点的点 —— 后者会让曲线左端多一个永远拖不动的台阶。
     *   判据用 [MIN_GAP] 而不是严格的 0/1：手指按在角落上永远落不到精确的 0.000。
     */
    fun add(points: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int> {
        val base = sorted(points)
        if (base.size >= MAX_POINTS) return base to -1
        val cx = clamp01(x)
        val cy = clamp01(y)
        val atLeft = cx <= MIN_GAP
        val atRight = cx >= 1f - MIN_GAP
        if (atLeft || atRight) {
            // 落到端点上：只改端点的 y，不新增点（否则第一个点永远是 (0,0) 之外的东西）
            val out = base.toMutableList()
            val idx = if (atRight) out.lastIndex else 0
            out[idx] = CurvePoint(out[idx].x, cy)
            return out to idx
        }
        if (base.any { kotlin.math.abs(it.x - cx) < MIN_GAP }) return base to -1
        val out = (base + CurvePoint(cx, cy)).sortedWith(compareBy({ it.x }, { it.y }))
        return out to out.indexOfFirst { it.x == cx && it.y == cy }
    }

    /**
     * 删除第 [index] 个控制点。
     *
     * 端点不可删（删了曲线就没有定义域了）；总数已经到 [MIN_POINTS] 时也不删。
     * 返回原列表表示"这一次什么都没变"，调用方据此不必重算预览。
     */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> {
        val base = sorted(points)
        if (base.size <= MIN_POINTS) return base
        if (index !in base.indices) return base
        if (index == 0 || index == base.lastIndex) return base
        return base.filterIndexed { i, _ -> i != index }
    }

    /**
     * 拖动第 [index] 个控制点到 (x,y)。
     *
     * - **端点锁 x**（只改 y）：端点挪离 0/1 会让曲线在中暗部与高光各留一段平线，
     *   用户拖完看到的是"两头有台阶"，而那是最常见的误操作；
     * - 中间点 x 被夹在左右邻点之间，各留 [MIN_GAP]：不许穿过邻居，
     *   否则顺序会变、命中下标失效，下一次拖动就跳；
     * - y 一律夹回 [0,1]。
     */
    fun move(points: List<CurvePoint>, index: Int, x: Float, y: Float): List<CurvePoint> {
        val base = sorted(points)
        if (index !in base.indices) return base
        val cy = clamp01(y)
        if (index == 0) {
            return base.toMutableList().also { it[0] = CurvePoint(it[0].x, cy) }
        }
        if (index == base.lastIndex) {
            return base.toMutableList().also { it[index] = CurvePoint(it[index].x, cy) }
        }
        val lo = base[index - 1].x + MIN_GAP
        val hi = base[index + 1].x - MIN_GAP
        // 左右邻点本身贴得比 2*MIN_GAP 还近时（例如刚删过点），区间会反转；
        // 这时把 x 钉在中点，而不是产生一个 lo > hi 的 coerceIn 异常值。
        val nx = if (hi <= lo) (base[index - 1].x + base[index + 1].x) * 0.5f else clamp01(x).coerceIn(lo, hi)
        return base.toMutableList().also { it[index] = CurvePoint(nx, cy) }
    }

    /**
     * 命中判定：返回离 ([x],[y]) 最近且在 [radiusGap] 之内的控制点下标，没有则 -1。
     *
     * 距离按**归一化坐标的欧氏距离**算（x 与 y 同权）：Canvas 是正方形绘制，
     * 所以归一化距离与屏幕距离只差一个常数倍，不需要在这里知道像素尺寸。
     */
    fun hitTest(points: List<CurvePoint>, x: Float, y: Float, radiusGap: Float = 0.06f): Int {
        val base = sorted(points)
        var best = -1
        var bestD = radiusGap * radiusGap
        for (i in base.indices) {
            val dx = base[i].x - x
            val dy = base[i].y - y
            val d = dx * dx + dy * dy
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 控制点列表 → 引擎对象；与 [IDENTITY] 逐点相等时返回 null（中性，管线整段跳过）。 */
    fun toCurve(points: List<CurvePoint>): ToneCurve? {
        val base = sorted(points)
        if (base == IDENTITY) return null
        return ToneCurve(base)
    }

    /** 某条通道当前的控制点；空（null）通道按恒等显示，编辑器不必区分"没调"和"调成了直线"。 */
    fun of(curve: ToneCurve?): List<CurvePoint> = curve?.points ?: IDENTITY
}

/**
 * 四条通道（主 / R / G / B）的读写。
 *
 * 主通道与通道曲线在引擎里是**两步分开的**（见 `ToneCurves` 的注释），
 * 所以 UI 也必须分开编辑：把四条合成一条会让"改主曲线时通道曲线的斜率也跟着变"。
 */
object CurveChannels {
    const val MASTER = 0
    const val R = 1
    const val G = 2
    const val B = 3

    val count: Int = 4

    fun label(channel: Int): String = when (channel) {
        MASTER -> "主"
        R -> "R"
        G -> "G"
        else -> "B"
    }

    fun colorOf(channel: Int): Long = when (channel) {
        R -> 0xFFE8705F
        G -> 0xFF6FCF7F
        B -> 0xFF6FA8E8
        else -> 0xFFFFFFFF
    }

    fun curve(curves: ToneCurves, channel: Int): ToneCurve? = when (channel) {
        MASTER -> curves.master
        R -> curves.r
        G -> curves.g
        else -> curves.b
    }

    fun points(curves: ToneCurves, channel: Int): List<CurvePoint> = CurvePoints.of(curve(curves, channel))

    /** 用新的控制点替换某条通道；结果与恒等相等时写 null，保持"没动过"的语义。 */
    fun withPoints(curves: ToneCurves, channel: Int, points: List<CurvePoint>): ToneCurves {
        val next = CurvePoints.toCurve(points)
        return when (channel) {
            MASTER -> curves.copy(master = next)
            R -> curves.copy(r = next)
            G -> curves.copy(g = next)
            else -> curves.copy(b = next)
        }
    }

    /** 只清空这一条通道（其余三条保留）。 */
    fun clearChannel(curves: ToneCurves, channel: Int): ToneCurves =
        withPoints(curves, channel, CurvePoints.IDENTITY)

    /** 四条全清。 */
    fun clearAll(): ToneCurves = ToneCurves()
}
