package com.suipai.app.ui.collage

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 胶片边框样式。
 *
 * 为什么要有这一层：拼图渲染原来只会"裁格子 + 画圆角"，一张拼图看起来是
 * 九宫格而不是一页印样。边框是**装裱**，不是修改画面内容 —— 它压在成片边缘，
 * 源照片一个字节都不动（见 `docs/product/PLAY.md` 的「装裱口径」）。
 *
 * 四种样式各自的世界语义：
 * - [NONE]：什么都不加（默认，行为与加这个特性之前逐像素一致）；
 * - [FILM_35MM]：上下齿孔条 —— 等宽小黑方块沿边缘等距排开，像一条 35mm 片基；
 * - [FILM_120]：120 卷片的宽边 —— 四边深色宽框 + 框内一道亮黄转记线 + 四角刻线；
 * - [POLAROID]：拍立得白卡 —— 三边窄、**下边宽**，下方预留落款位（空着，不印字）。
 */
enum class FrameStyle(val label: String, val cellLabel: String) {
    NONE("无边框", "无框"),
    FILM_35MM("35mm 齿孔", "35mm"),
    FILM_120("120 宽边", "120"),
    POLAROID("拍立得", "拍立得"),
    ;

    /** 循环格的下一个（界面那一格点一下就是它）。 */
    fun next(): FrameStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 序号 → 样式。**越界一律回 [NONE]**（不是按模取）。
         *
         * 偏好里存的是序号；表要是变短了（删掉一档），旧值必须落回"什么都不加"，
         * 而不是按模绕到另一个样式上 —— 用户会发现自己没动过的拼图突然多了个拍立得白边。
         * 第一版写的是 `entries[i.mod(size)]`，`99` 于是落在 POLAROID 上，
         * 被 `CollagePixelsTest.frameStyleCycleCoversEveryEntryExactlyOnce` 抓住。
         */
        fun byOrdinal(i: Int): FrameStyle =
            if (i in entries.indices) entries[i] else NONE
    }
}

/**
 * 边框用到的三支颜色（ARGB）。
 *
 * 固定值而不是跟主题走：胶片片基、相纸卡、转记线是**物理材料**的颜色，
 * 不该因为界面切成日光主题就变成另一种塑料。这一点和 UI chrome 的 token 不同。
 */
data class FrameInk(
    /** 片基 / 120 宽边：接近黑，压在暗房底上不刺眼，压在亮照片上有明确的边。 */
    val baseArgb: Int = 0xFF0A0D0C.toInt(),
    /** 齿孔 / 拍立得卡面：相纸乳白。 */
    val paperArgb: Int = 0xFFF5F3EE.toInt(),
    /** 转记线 / 刻线 / 卡内框线：中间调，在纸上和片基上都读得出。 */
    val markArgb: Int = 0xFF8A857A.toInt(),
)

/** 一块像素在整幅图里的位置。数组按行优先，每行 [width] 个，行距 [width]。 */
data class PixelRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** 一个边框记号是什么：测试按种类取样，界面按种类决定要不要模糊/半透明。 */
enum class FrameMarkKind {
    /** 片基 / 宽边 / 卡面：不透明填充。 */
    BASE,

    /** 齿孔：亮色小方块。 */
    HOLE,

    /** 转记线 / 卡内框线。 */
    LINE,

    /** 四角刻线（120 的机械感）。 */
    TICK,
}

/**
 * 一个矩形记号。**绘制顺序 = 列表顺序，后面的盖前面的**，
 * 所以"齿孔压在片基上"这件事是靠列表顺序表达的，不需要额外的层级字段。
 */
data class FrameMark(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val argb: Int,
    val kind: FrameMarkKind,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 拼图成片的**装裱像素层**：边框与漏光。
 *
 * ## 为什么是"纯 Kotlin 像素数组"而不是 Canvas 的 RadialGradient / drawRect
 *
 * 因为这两件事是**会改像素**的，而"改了什么"必须能在 JVM 单测里逐像素断言 ——
 * 装机才能看见的东西等于没看见（本项目的判据纪律）。
 * `android.graphics.Bitmap` 在 JVM 单测里是桩（`unitTests.isReturnDefaultValues = true`
 * 让所有方法返回默认值），`Bitmap.createBitmap` 直接回 null，像素根本造不出来。
 * 所以这一层只认 `IntArray`：生产路径用 `Bitmap.getPixels/setPixels` 把像素取出来交给它，
 * 测试路径直接喂自己造的数组。**两条路径调的是同一批函数**，不存在"测的和跑的不是一份逻辑"。
 *
 * 漏光在数学上就是一道径向渐变（`leakAlphaAt` 是半径的二次衰减），
 * 只是这里显式算出每个像素的 alpha 再合成，而不是交给 Canvas —— 这样该渐变可测。
 *
 * ## 内存
 *
 * 不整幅取像素：生产路径按 [stripHeight] 行一条地调 [applyAdornments]，
 * 每条约 4 MB（2048 宽 × 256 行 × 4 字节），4K 输出也不会出现
 * "位图一份 + 整幅数组一份"的双份峰值。
 */
object CollagePixels {

    /** 生产路径一次处理的条带行数。256 行 × 2048 宽 ≈ 2 MB 的 IntArray。 */
    const val stripHeight: Int = 256

    /** 漏光从角上铺开的半径（相对短边的倍数）。0.62 刚好让对角中点还有一点点余光。 */
    const val leakRadiusFactor: Float = 0.62f

    /** 漏光最大不透明度：拉满也不能把照片糊掉，它是"漏"不是"滤镜"。 */
    const val leakMaxAlpha: Float = 0.62f

    // ────────────────────────────────────────────────────────────────
    // 几何：边框记号
    // ────────────────────────────────────────────────────────────────

    /**
     * 算出这一幅尺寸上该有哪些边框记号。**纯函数，只依赖画布尺寸与样式。**
     *
     * 所有厚度都按 `min(width, height)` 的比例算，不按 density：同一份 `CollageStyle`
     * 在 2048 导出图和 360dp 预览上得到的是**等比**的边框，预览与成片因此长得一样
     * （预览那边把记号按 `previewW / outputW` 缩小再画，见 `CollageScreen`）。
     */
    fun frameMarks(width: Int, height: Int, style: CollageStyle): List<FrameMark> {
        if (width <= 1 || height <= 1) return emptyList()
        val ink = style.frameInk
        val raw = when (style.frame) {
            FrameStyle.NONE -> emptyList()
            FrameStyle.FILM_35MM -> film35Marks(width, height, ink)
            FrameStyle.FILM_120 -> film120Marks(width, height, ink)
            FrameStyle.POLAROID -> polaroidMarks(width, height, ink)
        }
        // 夹到画布内：厚度是按短边比例算的，2×2 这种退化尺寸上会算出比画布还宽的边。
        // 越界的记号在 Canvas 上只是被裁掉，但在像素层越界就是写错行 —— 所以在这里夹死。
        return raw.mapNotNull { it.trimTo(width, height) }
    }

    /** 与画布求交，空了就丢掉。宽高为 0 的记号没有意义。 */
    private fun FrameMark.trimTo(w: Int, h: Int): FrameMark? {
        val l = left.coerceIn(0, w)
        val t = top.coerceIn(0, h)
        val r = right.coerceIn(0, w)
        val b = bottom.coerceIn(0, h)
        if (r <= l || b <= t) return null
        return copy(left = l, top = t, right = r, bottom = b)
    }

    /**
     * 35mm：上下两条片基 + 等距齿孔，左右各一道窄片基。
     *
     * 齿孔尺寸按画布短边定，**孔数由"孔宽 + 孔距"推出**而不是写死 16 个 ——
     * 写死个数会在横幅上把孔拉成邮票、在竖幅上挤成一排黑点。
     */
    private fun film35Marks(w: Int, h: Int, ink: FrameInk): List<FrameMark> {
        val short = min(w, h).toFloat()
        val band = max(3, (short * 0.055f).roundToInt())
        val side = max(2, (short * 0.012f).roundToInt())
        val marks = ArrayList<FrameMark>(24)
        // 1. 片基：上、下、左、右（后画的不遮前面：四条互不重叠）
        marks += FrameMark(0, 0, w, band, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, h - band, w, h, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, band, side, h - band, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(w - side, band, w, h - band, ink.baseArgb, FrameMarkKind.BASE)
        // 2. 齿孔：上下各一排，孔宽按宽度 2%，孔距 = 孔宽 × 0.95
        val holeW = max(2, (w * 0.020f).roundToInt())
        val holeH = max(2, (band * 0.42f).roundToInt())
        val gap = max(2, (holeW * 0.95f).roundToInt())
        val period = holeW + gap
        val count = max(1, (w + gap) / period)
        val total = count * period - gap
        val start = (w - total) / 2
        val topY = (band - holeH) / 2
        val bottomY = h - band + (band - holeH) / 2
        for (i in 0 until count) {
            val x = start + i * period
            marks += FrameMark(x, topY, x + holeW, topY + holeH, ink.paperArgb, FrameMarkKind.HOLE)
            marks += FrameMark(x, bottomY, x + holeW, bottomY + holeH, ink.paperArgb, FrameMarkKind.HOLE)
        }
        return marks
    }

    /**
     * 120：四边深色宽边 + 框内一道转记线 + 四角刻线。
     *
     * 120 没有齿孔（这正是它与 35mm 在视觉上最容易分开的地方），
     * 所以辨识度靠"边更宽 + 那道亮线 + 角上的机械刻线"。
     */
    private fun film120Marks(w: Int, h: Int, ink: FrameInk): List<FrameMark> {
        val short = min(w, h).toFloat()
        val edge = max(4, (short * 0.072f).roundToInt())
        val line = max(1, (short * 0.004f).roundToInt())
        val tickLen = max(6, (short * 0.055f).roundToInt())
        val tick = max(2, (short * 0.008f).roundToInt())
        val marks = ArrayList<FrameMark>(12)
        marks += FrameMark(0, 0, w, edge, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, h - edge, w, h, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, edge, edge, h - edge, ink.baseArgb, FrameMarkKind.BASE)
        marks += FrameMark(w - edge, edge, w, h - edge, ink.baseArgb, FrameMarkKind.BASE)
        // 转记线：框内一道 1..3px 的亮线，把"画幅"从"边"里划出来
        marks += FrameMark(edge + line, edge + line, w - edge - line, edge + 2 * line, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(edge + line, h - edge - 2 * line, w - edge - line, h - edge - line, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(edge + line, edge + line, edge + 2 * line, h - edge - line, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(w - edge - 2 * line, edge + line, w - edge - line, h - edge - line, ink.markArgb, FrameMarkKind.LINE)
        // 四角刻线：压在宽边上，像 120 背纸的定位标记
        val inset = edge / 3
        marks += FrameMark(inset, inset, inset + tickLen, inset + tick, ink.markArgb, FrameMarkKind.TICK)
        marks += FrameMark(w - inset - tickLen, inset, w - inset, inset + tick, ink.markArgb, FrameMarkKind.TICK)
        marks += FrameMark(inset, h - inset - tick, inset + tickLen, h - inset, ink.markArgb, FrameMarkKind.TICK)
        marks += FrameMark(w - inset - tickLen, h - inset - tick, w - inset, h - inset, ink.markArgb, FrameMarkKind.TICK)
        return marks
    }

    /**
     * 拍立得：三边窄白卡 + **下边宽白卡**，下边就是落款位。
     *
     * 卡面不透明地压住画面边缘（拍立得的相纸本来就压住画幅），
     * 所以这不违反"不改画面内容"：原图不动，成片是一张装在卡里的相纸。
     */
    private fun polaroidMarks(w: Int, h: Int, ink: FrameInk): List<FrameMark> {
        val short = min(w, h).toFloat()
        val narrow = max(4, (short * 0.055f).roundToInt())
        // 下边 = 窄边的 2.9 倍：这是拍立得最好认的那个比例（4:5 卡面里约 1:2.9）
        val wide = max(narrow + 4, (short * 0.16f).roundToInt())
        val line = max(1, (short * 0.0035f).roundToInt())
        val marks = ArrayList<FrameMark>(8)
        marks += FrameMark(0, 0, w, narrow, ink.paperArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, h - wide, w, h, ink.paperArgb, FrameMarkKind.BASE)
        marks += FrameMark(0, narrow, narrow, h - wide, ink.paperArgb, FrameMarkKind.BASE)
        marks += FrameMark(w - narrow, narrow, w, h - wide, ink.paperArgb, FrameMarkKind.BASE)
        // 卡内框线：贴着画幅的四周一圈，让"卡"与"照片"不是一块糊的
        marks += FrameMark(narrow, narrow, w - narrow, narrow + line, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(narrow, h - wide - line, w - narrow, h - wide, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(narrow, narrow, narrow + line, h - wide, ink.markArgb, FrameMarkKind.LINE)
        marks += FrameMark(w - narrow - line, narrow, w - narrow, h - wide, ink.markArgb, FrameMarkKind.LINE)
        return marks
    }

    // ────────────────────────────────────────────────────────────────
    // 几何：漏光
    // ────────────────────────────────────────────────────────────────

    /**
     * 漏光在 (x, y) 处的不透明度（0..1）。**纯函数**，测试直接对着它断言。
     *
     * 衰减是 `(1 - d/r)^2`：一次方太均匀（像蒙了一层橙纱），三次方太快收
     * （半径内一半的地方就看不见了）。平方落在中间，像光线从缝里漏进来。
     *
     * @param corner 0 = 左上、1 = 右上、2 = 左下、3 = 右下（越界按 4 取模）。
     */
    fun leakAlphaAt(x: Int, y: Int, width: Int, height: Int, strength: Float, corner: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return 0f
        val c = corner.mod(4)
        val cx = if (c == 1 || c == 3) width.toFloat() else 0f
        val cy = if (c == 2 || c == 3) height.toFloat() else 0f
        val radius = max(1f, min(width, height) * leakRadiusFactor)
        val d = hypot(x - cx, y - cy)
        if (d >= radius) return 0f
        val falloff = 1f - d / radius
        return (s * falloff * falloff * leakMaxAlpha).coerceIn(0f, 1f)
    }

    // ────────────────────────────────────────────────────────────────
    // 应用：把记号与漏光落到一块像素上
    // ────────────────────────────────────────────────────────────────

    /**
     * 把装裱应用到 [region] 这一块像素上（`px` 是这块区域的像素，行优先）。
     *
     * **顺序是承重的**：先漏光、后边框 —— 片基与卡面是不透明的实物，
     * 漏光只会漏在画面上，不会把白卡染成橙的。
     *
     * @return 是否改动过任何像素（`false` = 一个字节都没碰，调用方可以跳过回写）。
     */
    fun applyAdornments(
        px: IntArray,
        region: PixelRegion,
        imageWidth: Int,
        imageHeight: Int,
        style: CollageStyle,
        marks: List<FrameMark> = frameMarks(imageWidth, imageHeight, style),
    ): Boolean {
        if (region.isEmpty || px.isEmpty()) return false
        if (px.size < region.width * region.height) return false
        var touched = false
        val leak = style.leak.coerceIn(0f, 1f)
        if (leak > 0f) {
            touched = applyLeak(px, region, imageWidth, imageHeight, leak, style.leakCorner) || touched
        }
        if (marks.isNotEmpty()) {
            touched = fillMarks(px, region, marks) || touched
        }
        return touched
    }

    /** 漏光：对区域里每个像素算 alpha 再 src-over 合成。 */
    fun applyLeak(
        px: IntArray,
        region: PixelRegion,
        imageWidth: Int,
        imageHeight: Int,
        strength: Float,
        corner: Int,
    ): Boolean {
        if (strength <= 0f || region.isEmpty) return false
        val rgb = LEAK_RGB
        var touched = false
        for (row in 0 until region.height) {
            val gy = region.top + row
            val base = row * region.width
            for (col in 0 until region.width) {
                val a = leakAlphaAt(region.left + col, gy, imageWidth, imageHeight, strength, corner)
                if (a <= 0f) continue
                val i = base + col
                px[i] = blendOver(px[i], rgb, a)
                touched = true
            }
        }
        return touched
    }

    /** 把记号按列表顺序刷进区域（只刷与区域相交的部分）。 */
    fun fillMarks(px: IntArray, region: PixelRegion, marks: List<FrameMark>): Boolean {
        if (region.isEmpty) return false
        var touched = false
        for (mark in marks) {
            if (mark.isEmpty || (mark.argb ushr 24) == 0) continue
            val l = max(mark.left, region.left)
            val t = max(mark.top, region.top)
            val r = min(mark.right, region.left + region.width)
            val b = min(mark.bottom, region.top + region.height)
            if (l >= r || t >= b) continue
            val opaque = (mark.argb ushr 24) == 0xFF
            for (gy in t until b) {
                val row = gy - region.top
                val base = row * region.width
                for (gx in l until r) {
                    val i = base + (gx - region.left)
                    px[i] = if (opaque) mark.argb else blendOver(px[i], mark.argb, ((mark.argb ushr 24) and 0xFF) / 255f)
                }
                touched = true
            }
        }
        return touched
    }

    /**
     * 漏光的 RGB。
     *
     * `internal` 而不是 `private`：预览层（`CollageScreen.AdornmentOverlay`）要画同一种橙，
     * 两处各写一个 0xFFE8A33D 迟早分叉。**只此一处定义**，预览与像素层都读它，
     * `CollagePixelsTest.previewTintComesFromTheSameConstant` 钉住。
     *
     * 为什么不用主题 token（`SuiPaiColor.current.leakWarm`）：漏光是**胶片化学色**，
     * 不跟暗房/日光切换；而且像素层不依赖 Compose，取不到 CompositionLocal。
     * 这一支与 `SuiPaiTokens` 的 `leakWarm` 基色同值，是同一个世界的琥珀。
     */
    const val LEAK_RGB: Int = 0xFFE8A33D.toInt()

    /**
     * 一次 src-over 合成（不透明底）。alpha 只取自 [srcArgb] 的 alpha 与 [alpha] 的乘积。
     *
     * 输出一律不透明：拼图落盘是 JPEG，alpha 通道本来就会被丢掉，
     * 留着半透明只会让 `getPixels` 回来的值和写回去的不一样。
     */
    fun blendOver(dstArgb: Int, srcArgb: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return dstArgb
        if (a >= 1f) return srcArgb or 0xFF000000.toInt()
        val inv = 1f - a
        val r = (((srcArgb ushr 16) and 0xFF) * a + ((dstArgb ushr 16) and 0xFF) * inv).toInt().coerceIn(0, 255)
        val g = (((srcArgb ushr 8) and 0xFF) * a + ((dstArgb ushr 8) and 0xFF) * inv).toInt().coerceIn(0, 255)
        val b = ((srcArgb and 0xFF) * a + (dstArgb and 0xFF) * inv).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
