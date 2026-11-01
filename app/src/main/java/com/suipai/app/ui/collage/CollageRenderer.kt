package com.suipai.app.ui.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.suipai.app.data.ImageLoader
import com.suipai.app.data.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 拼图参数。全部以 **dp 的数值**存放（不是 Dp），因为这个类要能在 JVM 单测里造出来；
 * 换算成像素发生在 [CollageRenderer.render] 里，用调用方给的 density。
 */
data class CollageStyle(
    /** 格与格之间的缝，0..24dp。0 时每格严丝合缝。 */
    val borderDp: Float = 6f,
    /** 画布外边距，0..48dp。 */
    val marginDp: Float = 12f,
    /** 每格圆角，0..24dp。 */
    val radiusDp: Float = 0f,
    /** 底色（ARGB）。缝隙、外边距、解码失败留下的洞都是这个色。 */
    val backgroundArgb: Int = 0xFF111111.toInt(),
    /** 画布宽高比 = width / height。1f 正方形，0.8f 竖幅，1.7778f 横幅。 */
    val aspect: Float = 1f,
    /**
     * 胶片装裱样式。**默认 [FrameStyle.NONE]**：加这个特性之前的所有调用点
     * 一个字都不用改，且出来的像素与之前逐字节相同（`CollagePixelsTest` 钉住这条）。
     */
    val frame: FrameStyle = FrameStyle.NONE,
    /** 装裱用的三支颜色。不跟主题走：片基与相纸是**材料**，不是界面 chrome。 */
    val frameInk: FrameInk = FrameInk(),
    /** 漏光强度 0f..1f。0 = 一个像素都不碰。 */
    val leak: Float = 0f,
    /** 漏光从哪个角漏：0 左上、1 右上、2 左下、3 右下。 */
    val leakCorner: Int = 0,
) {
    /** 越界的参数一律夹回合法区间，而不是让 Canvas 画出负宽高。 */
    fun coerced(): CollageStyle = copy(
        borderDp = borderDp.coerceIn(0f, CollageRenderer.MAX_BORDER_DP),
        marginDp = marginDp.coerceIn(0f, CollageRenderer.MAX_MARGIN_DP),
        radiusDp = radiusDp.coerceIn(0f, CollageRenderer.MAX_RADIUS_DP),
        aspect = aspect.coerceIn(0.5f, 2f),
        leak = leak.coerceIn(0f, 1f),
        leakCorner = leakCorner.mod(4),
    )

    /** 这一套样式到底要不要动像素。false 时渲染器**整条装裱路径都不进**。 */
    val hasAdornments: Boolean get() = frame != FrameStyle.NONE || leak > 0f

    /** 工具条/预览那一行读的那句话。 */
    val adornmentLabel: String
        get() = buildString {
            append(frame.cellLabel)
            if (leak > 0f) {
                append(" · 漏光")
                append((leak * 100).toInt())
            }
        }
}

/** 一格在**像素**坐标系里的位置（已含外边距与缝）。纯 Kotlin，可在 JVM 上断言。 */
data class CollageCellRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * 把 N 张图按 [CollageLayout] 画进一张 Bitmap。只用 `android.graphics`，没有第三方库。
 *
 * 三条落盘口径与 `MediaRepository.saveBitmap` 对齐（见那个函数）：
 * 输出 ARGB_8888、JPEG 质量由调用方给、长边默认 2048 —— 拼图是给看/分享的，
 * 不是再存一份原片。
 *
 * ### 内存
 * 绘制阶段只持有**输出画布 + 一张正在画的源图**：每张源图按目标格子的像素尺寸
 * 降采样解出来（[decodeEdgeFor]），画完立刻 `recycle()`。所以 9 张 4000px 的图
 * 不会同时展开成 9 × 64 MB —— 峰值是 2048² × 4 ≈ 16 MB 的输出，加一张格子大小的源图。
 */
object CollageRenderer {

    const val DEFAULT_LONG_EDGE: Int = 2048
    const val MAX_BORDER_DP: Float = 24f
    const val MAX_MARGIN_DP: Float = 48f
    const val MAX_RADIUS_DP: Float = 24f
    /** 源图最长边永远不超过这个数：再大对 2048 的输出也是浪费。 */
    const val MAX_SOURCE_EDGE: Int = 2400

    /** 输出尺寸：长边 = `longEdge`，短边按 `aspect` 缩，至少 64px。 */
    fun outputSize(style: CollageStyle, longEdge: Int = DEFAULT_LONG_EDGE): Pair<Int, Int> {
        val edge = longEdge.coerceIn(256, 8192)
        val a = style.coerced().aspect
        return if (a >= 1f) {
            edge to max(64, (edge / a).toInt())
        } else {
            max(64, (edge * a).toInt()) to edge
        }
    }

    /**
     * 每格的像素矩形。抽成纯函数是为了让"缝和边距别把格子挤成负数"这件事可测：
     * 外边距大到吃掉整张画布时，结果会是空矩形而不是一幅倒过来的图。
     */
    fun cellRects(
        layout: CollageLayout,
        widthPx: Int,
        heightPx: Int,
        marginPx: Float,
        borderPx: Float,
    ): List<CollageCellRect> {
        val innerW = widthPx - marginPx * 2f
        val innerH = heightPx - marginPx * 2f
        if (innerW <= 0f || innerH <= 0f) return layout.slots.map { CollageCellRect(0f, 0f, 0f, 0f) }
        val half = borderPx / 2f
        return layout.slots.map { slot ->
            val l = marginPx + slot.x * innerW
            val t = marginPx + slot.y * innerH
            val r = marginPx + slot.right * innerW
            val b = marginPx + slot.bottom * innerH
            // 缝的一半向格子里面收：相邻两格各收一半，缝就是 borderPx 宽，且不会画出画布外。
            CollageCellRect(l + half, t + half, r - half, b - half)
        }
    }

    /**
     * 一张源图该按多长的边去解码。
     *
     * 按目标格子的**较长边** × 1.2 取整（多给 20% 给 center-crop 的余量），
     * 上限 [MAX_SOURCE_EDGE]。不是按原图尺寸解 —— 那正是 9 张 4000px 一起进内存的来路。
     */
    fun decodeEdgeFor(cell: CollageCellRect): Int {
        if (cell.isEmpty) return 0
        val need = max(cell.width, cell.height) * 1.2f
        return need.toInt().coerceIn(64, MAX_SOURCE_EDGE)
    }

    /**
     * 同步绘制。调用方负责给已解码的位图（顺序 = 格子顺序，不足时按 `i % size` 循环补，
     * 多了则只用前 `slots.size` 张）。**不回收**传入的位图 —— 那是调用方的。
     */
    fun render(
        bitmaps: List<Bitmap>,
        layout: CollageLayout,
        style: CollageStyle,
        density: Float = 1f,
        longEdge: Int = DEFAULT_LONG_EDGE,
    ): Bitmap {
        val s = style.coerced()
        val (w, h) = outputSize(s, longEdge)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(s.backgroundArgb)
        val margin = (s.marginDp * density).coerceAtMost(min(w, h) / 4f)
        val border = s.borderDp * density
        val radius = min(s.radiusDp * density, min(w, h) / 8f)
        val rects = cellRects(layout, w, h, margin, border)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val clip = Path()
        val src = Rect()
        val dst = RectF()
        for ((index, cell) in rects.withIndex()) {
            if (cell.isEmpty || bitmaps.isEmpty()) continue
            val bitmap = bitmaps[index % bitmaps.size]
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) continue
            centerCrop(bitmap, cell, src)
            dst.set(cell.left, cell.top, cell.right, cell.bottom)
            if (radius > 0.5f) {
                clip.reset()
                clip.addRoundRect(dst, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, src, dst, paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
        }
        adorn(out, s)
        return out
    }

    /**
     * center-crop 的源矩形：取源图正中、与目标格子同比例的那一块。
     * 缩放交给 `drawBitmap(src, dst)`，这里只决定"裁哪一块"。
     */
    fun centerCrop(bitmap: Bitmap, cell: CollageCellRect, out: Rect) {
        val srcW = bitmap.width
        val srcH = bitmap.height
        // 空格子（边距大到把画布吃完）：整张图原样给出，让 drawBitmap 画一个退化矩形，
        // 而不是在这里除以 0 得到一个 Inf 宽高。
        if (cell.isEmpty) {
            out.set(0, 0, srcW, srcH)
            return
        }
        val dstAspect = cell.width / cell.height
        val srcAspect = srcW.toFloat() / srcH
        if (srcAspect > dstAspect) {
            // 源图更宽：左右各裁掉一条
            val cropW = (srcH * dstAspect).toInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            out.set(left, 0, left + cropW, srcH)
        } else {
            // 源图更高：上下各裁掉一条
            val cropH = (srcW / dstAspect).toInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            out.set(0, top, srcW, top + cropH)
        }
    }

    /**
     * 从 MediaStore 一路做到底：解码 → 画 → 回收 → 交回成品。
     *
     * 解码是串行的，这是有意的：并行解 9 张只在"导出一张拼图"这一步省几十毫秒，
     * 代价是同一时刻 9 份位图同时驻留（见类注释里的内存账）。
     *
     * @return 一张都没画上时返回 null（调用方据此说一句"这几张读不出来"，
     *         而不是导出一张纯底色的图）。
     */
    suspend fun renderPhotos(
        context: Context,
        photos: List<PhotoItem>,
        layout: CollageLayout,
        style: CollageStyle,
        density: Float = 1f,
        longEdge: Int = DEFAULT_LONG_EDGE,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (photos.isEmpty() || layout.slots.isEmpty()) return@withContext null
        val s = style.coerced()
        val (w, h) = outputSize(s, longEdge)
        val margin = (s.marginDp * density).coerceAtMost(min(w, h) / 4f)
        val rects = cellRects(layout, w, h, margin, s.borderDp * density)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(s.backgroundArgb)
        val radius = min(s.radiusDp * density, min(w, h) / 8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val clip = Path()
        val src = Rect()
        val dst = RectF()
        var drawn = 0
        rects.forEachIndexed { index, cell ->
            if (cell.isEmpty) return@forEachIndexed
            val photo = photos[index % photos.size]
            val edge = decodeEdgeFor(cell)
            if (edge <= 0) return@forEachIndexed
            val bitmap = runCatching { ImageLoader.decode(context, photo.uri, edge).first }.getOrNull()
                ?: return@forEachIndexed
            centerCrop(bitmap, cell, src)
            dst.set(cell.left, cell.top, cell.right, cell.bottom)
            if (radius > 0.5f) {
                clip.reset()
                clip.addRoundRect(dst, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, src, dst, paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
            drawn++
            // 画完就收：这是"峰值只有一张源图"的唯一来路。
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (drawn == 0) {
            out.recycle()
            null
        } else {
            adorn(out, s)
            out
        }
    }

    /**
     * 把装裱（边框 + 漏光）落到成品上。
     *
     * **按条带取像素**，不整幅取：2048² 的整幅 IntArray 是 16 MB，
     * 而条带只有 [CollagePixels.stripHeight] 行，约 2 MB。
     * 4K 输出时这个差别是"会不会 OOM"。
     *
     * [CollageStyle.hasAdornments] 为 false 时**直接返回**：连一次 `getPixels`
     * 都不发生，这是"强度 0 时逐像素不变"在实现层的保证（不只是数学上的巧合）。
     *
     * 装裱是**装裱**，不是修改：`Pictures/SuiPai` 里的源照片一个字节都不动，
     * 这里动的是刚从画布上画出来的那张新位图。
     */
    fun adorn(bitmap: Bitmap, style: CollageStyle) {
        val s = style.coerced()
        if (!s.hasAdornments) return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val marks = CollagePixels.frameMarks(w, h, s)
        val strip = CollagePixels.stripHeight
        // 条带数组按"最多 strip 行"一次分配好，循环里不再分配 —— 4K 图是 24 次 getPixels，
        // 每次都 new 一个 2 MB 的数组会让 GC 在最需要它的时候插一脚。
        val buf = IntArray(w * strip.coerceAtMost(h))
        var top = 0
        while (top < h) {
            val rows = strip.coerceAtMost(h - top)
            val n = w * rows
            if (n > buf.size) break
            val region = PixelRegion(left = 0, top = top, width = w, height = rows)
            bitmap.getPixels(buf, 0, w, 0, top, w, rows)
            val touched = CollagePixels.applyAdornments(buf, region, w, h, s, marks)
            if (touched) bitmap.setPixels(buf, 0, w, 0, top, w, rows)
            top += rows
        }
    }
}

/** 导出文件名。与 `SuiPai_<动作>_<时间戳>.<ext>` 那套加工件命名同族（两个下划线 ⇒ 不算原片）。 */
fun collageExportName(timestamp: Long): String = "SuiPai_拼图_$timestamp.jpg"
