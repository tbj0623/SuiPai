package com.suipai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打上这个 tag 的请求表示「这是缩略图，可以走系统缓存」。
 *
 * 为什么不靠 `Options.size` 判断：Coil 2.7 的 `Size` 是 `Dimension` 抽象类，
 * 请求侧写 `.size(420)` 之后能不能拿回像素值并不稳定；而这里要区分的恰恰是
 * 「网格里的 420px 缩略图」和「长按预览那张 1600px 的图」——后者必须解原图，
 * 用系统小缩略图放大就糊了。显式打 tag 比猜尺寸可靠。
 */
data class FastThumb(val edgePx: Int)

/**
 * 相册缩略图优先走**系统已经缓存好的 MediaStore 缩略图**，而不是把原图整张解一遍。
 *
 * 这条是被实测逼出来的：原来 `AsyncImage` 直接 `data(photo.uri).size(420)`，
 * Coil 的 `ContentUriFetcher` 会 `openInputStream` 把**整张原图**读出来再降采样。
 * 签名 release 包冷启动（Coil 内存缓存是空的）实测：时间线先呈现约 10 秒空深色格子才出图。
 * `ContentResolver.loadThumbnail` 用的是 MediaStore 自己那份已落盘的缩略图，单张通常是毫秒级。
 *
 * 拿不到（API < 29、非媒体库 uri、系统还没生成缩略图、抛异常）就返回 null，
 * 交回 Coil 的默认链路——行为退化成原来的样子，但不会变坏。
 */
class ThumbnailFetcher private constructor(
    private val context: Context,
    private val uri: Uri,
    private val edgePx: Int,
) : Fetcher {

    /** 不靠 BuildConfig（AGP 8 默认不生成），直接看应用是不是 debuggable。 */
    private val logging =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override suspend fun fetch(): FetchResult? {
        // 工厂里已经拦过一次，这里再拦一次：lint 的 NewApi 只认「同一个方法内的 SDK_INT 判断」，
        // 不然它把 loadThumbnail 报成 API 29 的错，check 任务直接红。
        if (Build.VERSION.SDK_INT < 29) return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(edgePx, edgePx), CancellationSignal())
            }.getOrNull()
        } ?: return null
        if (bmp.width <= 0 || bmp.height <= 0) return null
        if (logging) {
            android.util.Log.d(TAG, "系统缩略图命中 edge=$edgePx ${uri.lastPathSegment} ${bmp.width}x${bmp.height}")
        }
        // isSampled = true：告诉 Coil 这张已经是降过采样的小图，别再按原图尺寸算一遍采样率
        return DrawableResult(BitmapDrawable(context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT < 29) return null
            if (data.authority != MEDIA_AUTHORITY) return null
            val tag = options.tags.tag<FastThumb>() ?: return null
            return ThumbnailFetcher(options.context, data, tag.edgePx.coerceIn(64, MAX_THUMB_EDGE))
        }
    }

    private companion object {
        const val TAG = "CoilThumb"
        const val MEDIA_AUTHORITY = "media"

        /** 超过这个边长就不走系统缩略图，交回原图解码。 */
        const val MAX_THUMB_EDGE = 1024
    }
}
