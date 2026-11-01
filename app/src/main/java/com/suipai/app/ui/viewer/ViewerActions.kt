package com.suipai.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.suipai.app.data.ImageLoader
import com.suipai.app.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 查看器「另存为…」类动作的表 + 唯一实现。
 *
 * 这里存在的理由很具体：这些动作原来是一个按钮一段代码，
 * 15 个导出尺寸 × 32 行 + 16 个裁切 × 31 行 ≈ 980 行**只差一个数字**的复制粘贴，
 * 而且全部塞在同一条 `horizontalScroll` 行里——于是「一瞬」这个主功能入口
 * 被顶到第 40 个按钮，实测要横滚 8 次才点得到。
 *
 * 收成表之后：加一个尺寸/比例只改这里一行；界面从表生成；文案/文件名/失败提示只有一处定义。
 */
enum class ExportSize(val token: String, val edge: Int) {
    S2K("2K", 2048),
    S1080("1080", 1080),
    S720("720", 720),
    S480("480", 480),
    S320("320", 320),
    S240("240", 240),
    S160("160", 160),
    S120("120", 120),
    S96("96", 96),
    S64("64", 64),
    S48("48", 48),
    S32("32", 32),
    S24("24", 24),
    S16("16", 16),
    S12("12", 12),
    ;

    val label: String get() = "存$token"
    val doneHint: String get() = "已存$token"
    val failHint: String get() = "${token}失败"
}

/**
 * @param filePart 落盘文件名里 `SuiPai_` 后面那一段。**保持每个比例原有的文件名**，
 *                 不按 label 自动生成：1:1 历史上就叫 `SuiPai_裁切_…`（没有比例后缀），
 *                 改它会让用户已有的按文件名筛选/搜索失效。
 */
enum class CropRatio(val token: String, val target: Float, val filePart: String) {
    R1x1("1:1", 1f, "裁切"),
    R16x9("16:9", 16f / 9f, "裁切169"),
    R4x3("4:3", 4f / 3f, "裁切43"),
    R5x4("5:4", 5f / 4f, "裁切54"),
    GOLDEN("黄金", 1.618f, "裁切黄金"),
    C239("2.39", 2.39f, "裁切239"),
    C185("1.85", 1.85f, "裁切185"),
    R5x3("5:3", 5f / 3f, "裁切53"),
    R8x5("8:5", 8f / 5f, "裁切85"),
    R5x7("5:7", 5f / 7f, "裁切57"),
    R7x5("7:5", 7f / 5f, "裁切75"),
    R21x9("21:9", 21f / 9f, "裁切219"),
    R4x5("4:5", 4f / 5f, "裁切45"),
    R2x3("2:3", 2f / 3f, "裁切23"),
    R3x2("3:2", 3f / 2f, "裁切32"),
    R9x16("9:16", 9f / 16f, "裁切916"),
    ;

    val label: String get() = "裁$token"
    val doneHint: String get() = "已裁$token"
}

/** 几何/色彩变换后另存一份；原图永远不动（产品原则）。 */
enum class SaveTransform(val token: String, val label: String, val doneHint: String, val failHint: String) {
    MIRROR("镜像", "存镜像", "已存镜像", "镜像失败"),
    GRAY("灰度", "存灰度", "已存灰度", "灰度失败"),
    FLIP_V("上下翻", "存上下翻", "已存上下翻", "翻转失败"),
    ROUNDED("圆角", "存圆角", "已存圆角", "圆角失败"),
}

private const val DECODE_EDGE = 4096

private fun scaledTo(bmp: Bitmap, edge: Int): Bitmap {
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= edge) return bmp
    val s = edge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bmp,
        (bmp.width * s).toInt().coerceAtLeast(1),
        (bmp.height * s).toInt().coerceAtLeast(1),
        true,
    )
}

/** 居中裁到目标长宽比：宽的超出部分切两头，高的同理。 */
private fun croppedTo(bmp: Bitmap, target: Float): Bitmap {
    val src = bmp.width.toFloat() / bmp.height
    return if (src > target) {
        val w = (bmp.height * target).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(bmp, (bmp.width - w) / 2, 0, w, bmp.height)
    } else {
        val h = (bmp.width / target).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(bmp, 0, (bmp.height - h) / 2, bmp.width, h)
    }
}

private fun transformed(bmp: Bitmap, kind: SaveTransform): Bitmap = when (kind) {
    SaveTransform.MIRROR -> Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preScale(-1f, 1f) }, true)
    SaveTransform.FLIP_V -> Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preScale(1f, -1f) }, true)
    SaveTransform.GRAY -> Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { out ->
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
    }
    SaveTransform.ROUNDED -> Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { out ->
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.TRANSPARENT)
        val r = minOf(bmp.width, bmp.height) * 0.08f
        val path = Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
                r, r, Path.Direction.CW,
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }
}

/**
 * 三个入口都返回**要显示给用户的提示串**（成功/失败都编码在返回值里），
 * 因为这条链的失败原因对用户只有一句话的价值，不值得为它开一套结果类型。
 */
suspend fun exportScaled(context: Context, uri: Uri, size: ExportSize): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageLoader.decode(context, uri, maxEdge = DECODE_EDGE).first
            if (bmp.width <= 0 || bmp.height <= 0) return@runCatching size.failHint
            val out = scaledTo(bmp, size.edge)
            val saved = MediaRepository(context).saveBitmap(
                out,
                "SuiPai_${size.token}_${System.currentTimeMillis()}.jpg",
                sourceUri = uri,
            )
            if (out !== bmp && !out.isRecycled) out.recycle()
            if (!bmp.isRecycled) bmp.recycle()
            if (saved != null) size.doneHint else size.failHint
        }.getOrElse { size.failHint }
    }

suspend fun exportCropped(context: Context, uri: Uri, ratio: CropRatio): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageLoader.decode(context, uri, maxEdge = DECODE_EDGE).first
            if (bmp.width <= 0 || bmp.height <= 0) return@runCatching "裁切失败"
            val out = croppedTo(bmp, ratio.target)
            val saved = MediaRepository(context).saveBitmap(
                out,
                "SuiPai_${ratio.filePart}_${System.currentTimeMillis()}.jpg",
                sourceUri = uri,
            )
            if (out !== bmp && !out.isRecycled) out.recycle()
            if (!bmp.isRecycled) bmp.recycle()
            if (saved != null) ratio.doneHint else "裁切失败"
        }.getOrElse { "裁切失败" }
    }

suspend fun exportTransformed(context: Context, uri: Uri, kind: SaveTransform): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageLoader.decode(context, uri, maxEdge = DECODE_EDGE).first
            if (bmp.width <= 0 || bmp.height <= 0) return@runCatching kind.failHint
            val out = transformed(bmp, kind)
            val saved = MediaRepository(context).saveBitmap(
                out,
                "SuiPai_${kind.token}_${System.currentTimeMillis()}.${if (kind == SaveTransform.ROUNDED) "png" else "jpg"}",
                sourceUri = uri,
                png = kind == SaveTransform.ROUNDED,
            )
            if (out !== bmp && !out.isRecycled) out.recycle()
            if (!bmp.isRecycled) bmp.recycle()
            if (saved != null) kind.doneHint else kind.failHint
        }.getOrElse { kind.failHint }
    }
