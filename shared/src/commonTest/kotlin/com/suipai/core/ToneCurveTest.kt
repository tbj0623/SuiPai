package com.suipai.core

import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 曲线算子的不变式。
 *
 * 每条断言都配了「喂坏例子必须变红」的自检（每个用例末尾那一段）：
 * 这个工程的历史教训是，判据本身写错时测试会安静地绿一整年。
 */
class ToneCurveTest {

    /** 一块不足 16×16 的小图够用：曲线是逐像素的点运算，与邻域无关。 */
    private val lutSize = ToneCurve.LUT_SIZE

    // ---- 单调性与值域 ---------------------------------------------------------

    @Test
    fun identityCurveProducesExactIdentityLut() {
        // 恒等曲线（只有两个对角线端点）必须产出 i/1023 的 LUT ——
        // 差一点点就会被 [ToneCurves.hasAny] 当成品味调整，让「没调曲线」的输出变样。
        val lut = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))).buildLut()
        assertEquals(lutSize, lut.size)
        for (i in 0 until lutSize) {
            assertTrue(abs(lut[i] - i / (lutSize - 1f)) <= 1e-6f, "i=$i 偏离恒等 ${lut[i]}")
        }
        // 自检：在 0.5 处把控制点抬高 0.005，同一条「恒等」判据必须变得不成立。
        // 注意不能靠把端点抬到 1.01 —— 归一化会把它夹回 1，那样自检是空跑。
        val nudged = ToneCurve(
            listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.505f), CurvePoint(1f, 1f)),
        ).buildLut()
        assertTrue(
            abs(nudged[lutSize / 2] - 0.5f) > 1e-3f,
            "自检失败：中点抬高 0.005 却没被测出来（LUT=${nudged[lutSize / 2]}）",
        )
    }

    @Test
    fun lutIsMonotonicForRandomControlPoints() {
        // 1000 组固定种子随机控制点：x 乱序、y 有升有降、含重复 x。
        // 判据是「LUT 单调不减」——Fritsch–Carlson 的充分条件一旦被改坏
        // （比如去掉切线符号钳制），这里立刻红。
        val rnd = Random(20241018)
        repeat(1000) { case ->
            val n = rnd.nextInt(2, 17)
            val pts = List(n) {
                CurvePoint(rnd.nextFloat(), rnd.nextFloat() * 1.4f - 0.2f) // y 故意越界
            }
            val lut = ToneCurve(pts).buildLut()
            var prev = Float.NEGATIVE_INFINITY
            for (i in 0 until lutSize) {
                assertTrue(lut[i] >= prev - 1e-6f, "case=$case i=$i 单调性被破坏：${lut[i]} < $prev")
                assertTrue(lut[i] in 0f..1f, "case=$case i=$i 越界：${lut[i]}")
                prev = lut[i]
            }
        }
        // 自检：故意造一条「先降后升」的曲线，并把 y 反向喂进去，
        // 归一化后必须是平的（钳平），而不是原样保留下降段。
        val descending = ToneCurve(listOf(CurvePoint(0f, 0.9f), CurvePoint(1f, 0.1f))).buildLut()
        assertTrue(descending.all { abs(it - 0.9f) <= 1e-6f }, "y 应被钳平到首点，实际 ${descending.last()}")
    }

    @Test
    fun endpointsAreAlwaysInsideUnitInterval() {
        val rnd = Random(7)
        repeat(200) {
            val pts = List(rnd.nextInt(2, 17)) { CurvePoint(rnd.nextFloat(), rnd.nextFloat()) }
            val lut = ToneCurve(pts).buildLut()
            assertTrue(lut.all { it in 0f..1f })
        }
        // 自检：直接构造一条 y 全为 2 的曲线，端点必须被夹到 1
        val wild = ToneCurve(listOf(CurvePoint(0f, 2f), CurvePoint(1f, 2f)))
        assertEquals(1f, wild.buildLut().last())
    }

    @Test
    fun outOfOrderXIsSortedAutomatically() {
        // 用户从 UI 拖点时顺序不保证；x 乱序必须内部排序，而不是画出锯齿。
        val a = ToneCurve(
            listOf(
                CurvePoint(1f, 1f),
                CurvePoint(0f, 0f),
                CurvePoint(0.5f, 0.8f),
            ),
        )
        assertEquals(listOf(0f, 0.5f, 1f), a.points.map { it.x })
        val lut = a.buildLut()
        var prev = -1f
        for (v in lut) {
            assertTrue(v >= prev - 1e-6f)
            prev = v
        }
        // 自检：x 不排序的话 points 不会是升序
        val unsorted = listOf(CurvePoint(1f, 1f), CurvePoint(0f, 0f)).map { it.x }
        assertFalse(unsorted == unsorted.sorted(), "自检失败：这个输入本来就是升序的")
    }

    @Test
    fun pointCountIsClampedToTwoThroughSixteen() {
        assertEquals(2, ToneCurve(emptyList()).points.size)
        assertEquals(2, ToneCurve(listOf(CurvePoint(0.4f, 0.5f))).points.size)
        val many = List(40) { CurvePoint(it / 40f, it / 40f) }
        assertTrue(ToneCurve(many).points.size <= ToneCurve.MAX_POINTS)
        // 自检：40 个点必须真的被抽稀（否则上一条是空跑）
        assertTrue(ToneCurve(many).points.size < 40)
    }

    @Test
    fun nonFiniteControlPointsAreDropped() {
        val bad = listOf(CurvePoint(Float.NaN, 0.5f), CurvePoint(0f, 0f), CurvePoint(1f, Float.POSITIVE_INFINITY))
        val c = ToneCurve(bad)
        assertTrue(c.points.all { it.x.isFinite() && it.y.isFinite() })
        // 自检：三个非法/合法混合输入，结果里不能还有 NaN
        assertFalse(c.points.any { it.x.isNaN() || it.y.isNaN() })
    }

    @Test
    fun applyMatchesLutWithinQuantization() {
        val curve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.4f), CurvePoint(1f, 1f)))
        val lut = curve.buildLut()
        for (i in 0 until lutSize) {
            val direct = curve.apply(i / (lutSize - 1f))
            assertTrue(abs(direct - lut[i]) <= 1e-6f, "i=$i apply=$direct lut=${lut[i]}")
        }
    }

    // ---- 恒等判据 -------------------------------------------------------------

    @Test
    fun emptyCurvesAreNeutralButRealOnesAreNot() {
        assertFalse(ToneCurves().hasAny)
        assertTrue(ToneCurves().isEmpty)
        assertFalse(ToneCurves(master = ToneCurve.IDENTITY).hasAny)
        // 自检：明显的 S 曲线必须被判成「有内容」
        val s = ToneCurves(master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.62f), CurvePoint(1f, 1f))))
        assertTrue(s.hasAny, "自检失败：S 曲线被判成了中性")
    }
}
