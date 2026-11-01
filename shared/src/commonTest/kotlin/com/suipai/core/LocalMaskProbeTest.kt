package com.suipai.core

import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.LocalMaskField
import com.suipai.core.engine.LocalMasks
import com.suipai.core.engine.RadialMask
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 局部蒙版。
 *
 * 三条判据对应三种会真出事的坏法：
 * - 权重场形状错了 → 局部调整落在半个画幅上（肉眼一看就知道，但测试能先说）；
 * - 权重 0 处不算恒等 → 「我只想提亮人脸」顺手把整张图加了一格对比；
 * - 整场预计算与按行预计算不一致 → 小图（预览）与大图（成片）所见非所得。
 */
class LocalMaskProbeTest {

    private val w = 33
    private val h = 21

    private fun pixels(w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 255) / (w - 1)
                val g = (y * 255) / (h - 1)
                val b = ((x + y) * 255) / (w + h - 2)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    // ---- 形状 -----------------------------------------------------------------

    @Test
    fun radialCentreIsOneEdgeIsZero() {
        val m = RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.4f, ry = 0.4f, feather = 0.5f)
        val field = LocalMaskField(w, h, listOf(LocalMask(m, LocalAdjust(exposure = 0.5f))))
        val cx = w / 2
        val cy = h / 2
        assertEquals(1f, field.weight(0, cx, cy), 1e-6f, "中心不是满权重")
        assertTrue(field.weight(0, 0, 0) <= 1e-6f, "角落不是 0 权重：${field.weight(0, 0, 0)}")

        // 自检：把中心挪到角落，中心点就不再是 1
        val shifted = LocalMaskField(
            w, h,
            listOf(LocalMask(m.copy(cx = 0f, cy = 0f), LocalAdjust(exposure = 0.5f))),
        )
        assertTrue(shifted.weight(0, cx, cy) < 0.99f, "自检失败：中心挪走后仍判满权重")
    }

    @Test
    fun radialFeatherIsMonotonicDecreasing() {
        // 从中心往外走，权重必须单调不增（羽化段出现鼓包 = 蒙版边缘有一圈亮边）。
        val m = RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.45f, ry = 0.45f, feather = 0.8f)
        var prev = 1.1f
        var samples = 0
        for (i in 0..40) {
            val t = i / 40f
            val wt = LocalMaskField.radialWeight(m, 0.5f + t * 0.5f, 0.5f)
            assertTrue(wt <= prev + 1e-6f, "t=$t 羽化不单调：$wt > $prev")
            prev = wt
            samples++
        }
        assertEquals(41, samples)
        // 自检：feather=0（硬边）时权重在边缘外侧必须直接掉到 0
        val hard = LocalMaskField.radialWeight(m.copy(feather = 0f), 0.5f, 0.99f)
        assertEquals(0f, hard, 1e-6f, "自检失败：硬边蒙版在半径外仍有权重")
    }

    @Test
    fun radialInvertedFlipsWeight() {
        val m = RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.3f, ry = 0.3f, feather = 0f)
        val inner = LocalMaskField.radialWeight(m, 0.5f, 0.5f)
        val innerInv = LocalMaskField.radialWeight(m.copy(inverted = true), 0.5f, 0.5f)
        assertEquals(1f, inner, 1e-6f)
        assertEquals(0f, innerInv, 1e-6f)
        // 自检：没反转时内外权重不该相同
        assertFalse(inner == innerInv)
    }

    @Test
    fun linearRampGoesFromZeroToOneAlongAngle() {
        val m = LinearMask(angle = 0f, start = 0.25f, end = 0.75f)
        assertEquals(0f, LocalMaskField.linearWeight(m, 0f, 0.5f), 1e-6f)
        assertEquals(1f, LocalMaskField.linearWeight(m, 1f, 0.5f), 1e-6f)
        val mid = LocalMaskField.linearWeight(m, 0.5f, 0.5f)
        assertEquals(0.5f, mid, 1e-5f)
        // 自检：角度转 180°，两端权重互换
        val flipped = LocalMaskField.linearWeight(m.copy(angle = 180f), 0f, 0.5f)
        assertEquals(1f, flipped, 1e-5f, "自检失败：翻转 180° 后左端不是 1")
    }

    @Test
    fun linearAngleIsRotationInvariantInSemantics() {
        // 竖直方向（90°）时，权重只随 y 变，不随 x 变。
        val m = LinearMask(angle = 90f, start = 0.3f, end = 0.7f)
        for (i in 0..10) {
            val x = i / 10f
            assertEquals(
                LocalMaskField.linearWeight(m, 0f, 0.5f),
                LocalMaskField.linearWeight(m, x, 0.5f),
                1e-5f,
                "x=$x 影响了竖直渐变",
            )
        }
    }

    // ---- 数量与中性 -----------------------------------------------------------

    @Test
    fun moreThanFourMasksAreTruncated() {
        val m = RadialMask()
        val many = List(9) { LocalMask(m, LocalAdjust(exposure = 0.1f * (it + 1))) }
        val field = LocalMaskField(w, h, many)
        assertEquals(LocalMaskField.MAX_MASKS, field.count)
        // 自检：给 3 个就必须是 3，别把「截断」写成「固定 4 个空位」
        assertEquals(3, LocalMaskField(w, h, many.take(3)).count)
    }

    @Test
    fun neutralMasksAreDroppedEntirely() {
        // 四项全 0 的蒙版不产生任何改动，却会白算一整张权重场（1200 万像素 × 4）。
        val neutral = LocalMask(RadialMask(feather = 0.9f), LocalAdjust())
        assertFalse(LocalMaskField(w, h, listOf(neutral)).hasAny)
        // 自检：同样的形状，只要有一项非 0 就必须保留
        assertTrue(LocalMaskField(w, h, listOf(neutral.copy(adjust = LocalAdjust(saturation = 0.01f)))).hasAny)
    }

    @Test
    fun zeroAdjustIsBitExact() {
        // 权重再怎么变，只要调整量是 0，输出就必须逐位不变。
        val input = pixels(w, h)
        val work = input.copyOf()
        val field = LocalMaskField(
            w, h,
            listOf(
                LocalMask(LinearMask(0f, 0.2f, 0.8f), LocalAdjust()),
                LocalMask(RadialMask(feather = 1f), LocalAdjust()),
            ),
        )
        LocalMasks.applyRange(work, w, h, 0, h, field)
        assertTrue(input.contentEquals(work), "零调整改动了像素")

        // 自检：给同一个形状一点饱和度，必须真的变
        val live = LocalMaskField(
            w, h,
            listOf(LocalMask(LinearMask(0f, 0.2f, 0.8f), LocalAdjust(saturation = 0.6f))),
        )
        val changed = input.copyOf()
        LocalMasks.applyRange(changed, w, h, 0, h, live)
        assertFalse(input.contentEquals(changed), "自检失败：非零调整没改动像素")
    }

    // ---- 行带一致性 -----------------------------------------------------------

    @Test
    fun rowModeMatchesFullPrecompute() {
        // 整场预计算与按行预计算必须逐位一致：前者走的是小图（预览），
        // 后者走的是大图（成片），不一致就是「预览与成片不一样」。
        val input = pixels(w, h)
        val masks = listOf(
            LocalMask(RadialMask(cx = 0.4f, cy = 0.6f, rx = 0.3f, ry = 0.2f, feather = 0.7f), LocalAdjust(exposure = 0.4f)),
            LocalMask(LinearMask(angle = 35f, start = 0.1f, end = 0.9f), LocalAdjust(temperature = -0.5f)),
        )
        val full = LocalMaskField(w, h, masks)
        assertTrue(full.isFullyPrecomputed, "小图应该走整场模式")

        val reference = input.copyOf()
        LocalMasks.applyRange(reference, w, h, 0, h, full)

        // 按行处理（每次一行）
        val rowed = input.copyOf()
        for (y in 0 until h) LocalMasks.applyRange(rowed, w, h, y, y + 1, full)
        assertTrue(reference.contentEquals(rowed), "按行处理与整图处理不一致")

        // 自检：乱序行带（倒序）也要一致
        val reversed = input.copyOf()
        for (y in h - 1 downTo 0) LocalMasks.applyRange(reversed, w, h, y, y + 1, full)
        assertTrue(reference.contentEquals(reversed), "自检失败：倒序行带的结果不同")
    }

    @Test
    fun maskCoordinatesAreResolutionIndependent() {
        // 同一个归一化蒙版在 16×16 与 64×64 上，中心都必须是满权重（±1 像素采样误差）。
        val m = RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.5f, ry = 0.5f, feather = 0f)
        for (size in intArrayOf(16, 64, 129)) {
            val wt = LocalMaskField.weightAt(m, size / 2, size / 2, size, size)
            assertTrue(wt > 0.98f, "size=$size 的中心权重只有 $wt")
        }
        // 自检：角落在任何尺寸下都必须是 0
        for (size in intArrayOf(16, 64, 129)) {
            assertEquals(0f, LocalMaskField.weightAt(m, 0, 0, size, size), 1e-6f)
        }
    }

    @Test
    fun exposureIsMonotonicInWeight() {
        // 同一个像素、同一组参数，权重越大改动越大 —— 否则羽化段会出现明暗反转。
        val base = 0xFF808080.toInt()
        var prevDelta = -1
        for (i in 0..10) {
            val alpha = i / 10f
            val input = intArrayOf(base)
            val out = input.copyOf()
            // 用一个「常数权重」的线性蒙版构造：start=end 让整片区域权重为 1，
            // 再靠 exposure 缩放近似出不同的有效权重
            val mask = LocalMask(LinearMask(0f, 0f, 0f), LocalAdjust(exposure = alpha))
            LocalMasks.applyRange(out, 1, 1, 0, 1, LocalMaskField(1, 1, listOf(mask)))
            val delta = ((out[0] shr 16) and 0xFF) - ((base shr 16) and 0xFF)
            assertTrue(delta >= prevDelta, "alpha=$alpha 曝光不单调：$delta < $prevDelta")
            prevDelta = delta
        }
        assertTrue(prevDelta > 0, "自检失败：满曝光的亮度没有增加")
    }

    @Test
    fun outOfRangeAdjustIsClampedNotPlanted() {
        // 配方是外部输入：exposure=99 必须被夹到 1，而不是让像素炸成白。
        val a = LocalAdjust(exposure = 99f, contrast = -99f, saturation = 5f, temperature = -5f)
        assertEquals(1f, a.exposureC)
        assertEquals(-1f, a.contrastC)
        assertEquals(1f, a.saturationC)
        assertEquals(-1f, a.temperatureC)
        // 自检：NaN 不许原样流进渲染
        assertEquals(0f, LocalAdjust(exposure = Float.NaN).exposureC)
        assertTrue(abs(LocalAdjust(exposure = Float.NaN).exposureC) < 1e-9f)
    }
}
