package com.suipai.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 视频的**只读**仓库：一条分页查询 + 一次取帧 + 一张封面缓存。
 *
 * ── 为什么这一版不写 MediaStore ──
 * 产品口径是「只播不编」（`docs/product/VIDEO.md` §1）：不删、不裁剪、不转码、不旋转。
 * 唯一的写操作是「提取一帧为照片」，而那一次写走的是 `MediaRepository.saveBitmap`
 * —— 与相机、一瞬、副本**同一条落盘口径**（`Pictures/SuiPai`、`IS_PENDING` 两段式）。
 * 多一条自己写的 MediaStore 通道，就多一处"扩展名和内容不符""半截文件留在库里"的机会。
 *
 * ── 为什么不用 Media3 / ExoPlayer ──
 * 见 `docs/product/VIDEO.md` §2：本轮的三个动作（播放 / 分享 / 提取一帧）全是系统能力，
 * `VideoView` + `MediaMetadataRetriever` 就够；引 Media3 等于凭空给包体加 1.5–3 MB、
 * 给 Data safety 申报加一条依赖，换来的是"能播 HLS"这种相册根本不需要的能力。
 */
class VideoRepository(private val context: Context) {

    companion object {
        /**
         * 一页多少条。与 `MediaRepository.PAGE` 同一个数：视频比照片少得多，
         * 但"一页 400"是这套分页的口径（首帧一次读满一屏十几格绰绰有余）。
         */
        const val PAGE = 400

        /**
         * 投影只要**用得上的**列。`DURATION` / `WIDTH` / `HEIGHT` 在
         * `MediaStore.Video.VideoColumns` 里，二者都在 API 16 就有，minSdk 26 吃得下。
         */
        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
    }

    /**
     * 现在能不能读视频。
     *
     * ── 这一格为什么必须单独探 ──
     * `MediaStore.Video` 在**没有** `READ_MEDIA_VIDEO` 时**不抛异常**：它照样回一个游标，
     * 只是里面只剩本应用自己写进去的行（通常是 0 行）。于是"没权限"和"真的没有视频"
     * 在数据层长得一模一样，界面上写一句"你没有视频"就是一句假话。
     * 所以这里直接问系统要那一条授权，把"系统没放行"单独说出去。
     *
     * Android 13（33）起视频与照片单独授权；视频页提供按需授权入口。
     * Android 14（34）起还可能只有用户选中的部分视频可见，不能把它说成整库。
     */
    suspend fun readAccess(): VideoAccess = withContext(Dispatchers.IO) {
        val sdk = Build.VERSION.SDK_INT
        val permission = videoReadPermission(sdk)
        runCatching {
            val full = ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val partial = sdk >= 34 &&
                ContextCompat.checkSelfPermission(
                    context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            videoAccessFor(sdk, full, partial)
        }.onFailure {
            // 探不到不等于拒绝：未知状态不能让界面说「库里没有视频」。
            android.util.Log.w("VideoRepository", "探视频授权没探成：$permission", it)
        }.getOrDefault(VideoAccess.UNKNOWN)
    }

    /** 一页视频。`null` = **没读到**（游标是 null / 查询或读行抛了），空表 = 读到了、库里确实没有。 */
    suspend fun loadPage(offset: Int): List<VideoItem>? = withContext(Dispatchers.IO) {
        query(limit = PAGE, offset = offset)
    }

    /**
     * 取某一时刻的一帧。失败回 null —— 调用点负责说一句话（这条链上不许静默，
     * 见 `docs/product/VIDEO.md` §3.3：取不到帧的原因很多，而用户只关心"存没存上"）。
     *
     * `maxEdge` 是长边上限：从 4K 视频里取一张原尺寸帧是 3840×2160×4 B ≈ 33 MB，
     * 连点两下就能让一台中端机开始甩页。落盘的是照片，不需要超过 2560。
     */
    suspend fun extractFrame(uri: Uri, atMs: Long, maxEdge: Int = 2560): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val us = atMs.coerceAtLeast(0L) * 1000L
                scaledFrame(retriever, us, maxEdge)
            } catch (e: Exception) {
                android.util.Log.w("VideoRepository", "取帧没取成：$uri @ $atMs ms", e)
                null
            } finally {
                // release() 本身也可能抛（解码器已经没了），而它抛出来会把上面真正的错盖掉。
                runCatching { retriever.release() }
            }
        }

    /** 给上层兜底用的时长（MediaStore 那一行 `DURATION` 为 0 时）。取不到回 -1。 */
    suspend fun readDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            android.util.Log.w("VideoRepository", "读时长没读成：$uri", e)
            -1L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 把一帧存成照片，**走 `MediaRepository.saveBitmap`**（与相机/一瞬/副本同一条落盘口径）。
     *
     * 传 `sourceUri = null`：`saveBitmap` 会顺手把 EXIF 从源复制过去，而源是一段视频，
     * `ExifInterface` 读视频只会抛（那边已经 `runCatching` 兜住，但没必要让它跑一趟）。
     * 帧本身没有方向标签：`getFrameAtTime` 给的是**已经摆正**的画面（旋转写在容器元数据里，
     * 解码器已经应用过），所以这里不做额外旋转。
     */
    suspend fun saveFrame(bitmap: Bitmap, stampMs: Long): Uri? =
        MediaRepository(context).saveBitmap(bitmap, videoFrameFileName(stampMs))

    // ── 查询 ────────────────────────────────────────────────────────────

    /**
     * 分页口径**逐条照抄** `MediaRepository.query`：
     * 30 及以上走 `QUERY_ARG_LIMIT/OFFSET`（由提供方分页，是真的只读一页）；
     * 26–29 没有这两个参数，只能自己 `moveToNext` 数着跳过 offset 行。
     * 两条路的**结果必须一致**，否则同一个库里翻到第二页会出现重复或空洞。
     */
    private fun query(limit: Int?, offset: Int = 0): List<VideoItem>? {
        val videos = mutableListOf<VideoItem>()
        val sort = "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
        // 与照片那条同一条理由：任何一步不顺利都返回 null（"没读到"），
        // 而不是把空表当"库里就这些"。
        val cursor = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && limit != null) {
                val extras = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    if (offset > 0) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED),
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                    )
                }
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    VIDEO_PROJECTION,
                    extras,
                    null,
                )
            } else {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    VIDEO_PROJECTION,
                    null,
                    null,
                    sort,
                )
            }
        }.onFailure {
            android.util.Log.w("VideoRepository", "读视频没读成 offset=$offset limit=$limit", it)
        }.getOrNull() ?: return null

        val read = runCatching {
            cursor.use { c ->
                var skipped = 0
                val skip = if (Build.VERSION.SDK_INT >= 30 && limit != null) 0 else offset
                val cols = VideoColumns(c)
                while (c.moveToNext()) {
                    if (skipped < skip) {
                        skipped++
                        continue
                    }
                    videos += readVideo(c, cols)
                    if (limit != null && videos.size >= limit) break
                }
            }
            true
        }.onFailure {
            android.util.Log.w("VideoRepository", "读视频读到一半断了 offset=$offset", it)
        }.getOrDefault(false)
        return if (read) videos else null
    }

    /**
     * 列号一次算好，且**只有 `_ID` 是硬要求**。
     * 某些提供方（或系统"只选部分照片"模式）的游标里没有 `BUCKET_DISPLAY_NAME` 这类列，
     * `getColumnIndexOrThrow` 会把"少一个相册名"变成"整个视频页是空的"。
     */
    private class VideoColumns(private val cursor: Cursor) {
        private fun idx(name: String) = cursor.getColumnIndex(name)
        val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = idx(MediaStore.Video.Media.DISPLAY_NAME)
        val duration = idx(MediaStore.Video.Media.DURATION)
        val size = idx(MediaStore.Video.Media.SIZE)
        val width = idx(MediaStore.Video.Media.WIDTH)
        val height = idx(MediaStore.Video.Media.HEIGHT)
        val taken = idx(MediaStore.Video.Media.DATE_TAKEN)
        val added = idx(MediaStore.Video.Media.DATE_ADDED)
        val bucketId = idx(MediaStore.Video.Media.BUCKET_ID)
        val bucketName = idx(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    }

    private fun readVideo(cursor: Cursor, c: VideoColumns): VideoItem {
        val id = cursor.getLong(c.id)
        // `DATE_TAKEN` 对视频经常是 0（录像不一定写这个字段），退回 DATE_ADDED（秒）×1000。
        // 与照片那一行同一条退化路径：宁可日期粗一档，也不要把 1970 写进网格。
        val taken = cursor.longOr(c.taken).takeIf { v -> v > 0 } ?: cursor.longOr(c.added) * 1000
        return VideoItem(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
            displayName = cursor.strOr(c.name, "VID_$id.mp4"),
            durationMs = cursor.longOr(c.duration),
            sizeBytes = cursor.longOr(c.size),
            width = cursor.intOr(c.width),
            height = cursor.intOr(c.height),
            dateTaken = taken,
            bucketId = cursor.strOr(c.bucketId, ""),
            bucketName = cursor.strOr(c.bucketName, "相册"),
        )
    }

    /**
     * 取一帧，能缩就顺手缩。
     *
     * `getScaledFrameAtTime` 是 **27** 才有的入口（`VideoThumbs` 也要用它省下 4K 那一趟
     * 33 MB 的整帧解码），而 lint 的 NewApi 只认"同一个方法内的 SDK_INT 判断"，
     * 所以版本闸门和调用必须写在同一个方法体里 —— 拆成两个方法会被 lint 判成
     * 在 26 上直接调 27 的 API（`MediaRepository.deleteRequestFor` 那段注释记的是同一件事）。
     *
     * 26 上退回整帧取：`VideoThumbs.scaleTo` 一样能把它缩到 320，只是峰值内存高一档。
     */
    private fun scaledFrame(retriever: MediaMetadataRetriever, us: Long, maxEdge: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val long = maxOf(width, height)
            if (width <= 0 || height <= 0 || long <= maxEdge) {
                // 尺寸读不出来 / 本来就不大：不缩放（给 0×0 进 getScaledFrameAtTime 会抛）。
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                val ratio = maxEdge.toFloat() / long
                retriever.getScaledFrameAtTime(
                    us,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * ratio).toInt().coerceAtLeast(1),
                    (height * ratio).toInt().coerceAtLeast(1),
                )
            }
        } else {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
}

/** 列不存在（`getColumnIndex` 给 -1）就退回默认值。与 `MediaRepository` 里那三个是同一套写法。 */
private fun Cursor.longOr(col: Int, def: Long = 0L): Long = if (col < 0) def else getLong(col)
private fun Cursor.intOr(col: Int, def: Int = 0): Int = if (col < 0) def else getInt(col)
private fun Cursor.strOr(col: Int, def: String): String = if (col < 0) def else (getString(col) ?: def)

/**
 * 视频封面缓存：**磁盘 + 一小块内存 LRU**，两层都在组合之外。
 *
 * ── 为什么必须落盘 ──
 * `MediaMetadataRetriever.getFrameAtTime` 是这一屏最贵的一步（一段 4K 视频取一帧
 * 量级在几十到几百毫秒，还带一次解码器初始化）。而 `LazyVerticalGrid` 在滚动时
 * **会反复销毁/重建**同一格：不缓存的话，往回滚一屏就是几十次取帧，
 * 每次都往主线程之外再压一份负载。存在 `filesDir/video_thumbs/` 之后，
 * 第二次以后是一次 20–40 KB 的 JPEG 解码（与普通缩略图同一量级）。
 *
 * ── 为什么不进内存缓存就完事 ──
 * 进程被杀、或者用户切出去再回来，内存缓存一并清空，而用户看到的应该是"立刻就有封面"。
 * 磁盘才是那份跨进程的缓存。内存 LRU 只是"这一屏不用重复解 JPEG"的短路。
 *
 * ── 为什么不把 Bitmap recycle 掉 ──
 * `LruCache` 淘汰时**不回收**：一张正在被 `Image` 画的位图被 recycle 会直接 FATAL
 * （`Canvas: trying to use a recycled bitmap`，一瞬那个位图袋 2026-09-21 实测 12 次翻片全崩）。
 * 淘汰只是"不再持有"，交给 GC —— 这就是多花的那点内存买来的安全性。
 */
object VideoThumbs {

    /** 封面边长。120 dp 的格子 × 3 倍屏 ≈ 360 px，取 320 已经够，还能压住内存。 */
    const val EDGE = 320

    /** 磁盘上限。超过就按"最久没动过"淘汰（见 [trim]）。 */
    const val DISK_BUDGET_BYTES = 48L * 1024 * 1024

    /** 内存 LRU 上限（字节）。约 20 张 320×320 的 ARGB_8888。 */
    private const val MEMORY_BUDGET_BYTES = 8 * 1024 * 1024

    private val memory = object : android.util.LruCache<String, Bitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 写盘与淘汰同一把锁：两个格子同时取同一段视频的帧时，只让一个真的动磁盘。 */
    private val diskLock = Mutex()

    fun dir(context: Context): File = File(context.filesDir, "video_thumbs")

    /**
     * 拿这格的封面。命中就回，没命中就取帧 → 落盘 → 回。
     * 失败回 null，调用点画"没有封面"的占位（**不回退成灰色空洞**，见 `VideoGrid`）。
     */
    suspend fun load(context: Context, item: VideoItem): Bitmap? {
        val name = coverCacheName(item.id, EDGE, item.sizeBytes)
        memory.get(name)?.let { return it }
        val file = File(dir(context), name)
        val fromDisk = withContext(Dispatchers.IO) {
            if (file.isFile) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            } else {
                null
            }
        }
        if (fromDisk != null) {
            memory.put(name, fromDisk)
            return fromDisk
        }
        // 首帧取 1 秒：0 秒位置在不少编码上是黑场（录屏、渐入的开头），
        // 而封面要的是"这段视频长什么样"。
        val frame = VideoRepository(context).extractFrame(item.uri, 1000L, EDGE) ?: return null
        val scaled = scaleTo(frame)
        if (scaled !== frame) frame.recycle()
        diskLock.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(file, scaled)
                trimLocked(context)
            }
        }
        memory.put(name, scaled)
        return scaled
    }

    /**
     * 缩到 [EDGE] 的长边。已经够小就原样返回（调用方据此决定要不要回收原图）。
     *
     * 用 `createScaledBitmap` 而不是 `Bitmap.createScaledBitmap` 的过滤参数：
     * 3840 → 320 这样的降采样不用 bilinear，`filter = true` 在这一档已经够看，
     * 而 bilinear 在 12 倍降采样下反而糊。
     */
    private fun scaleTo(frame: Bitmap): Bitmap {
        val long = maxOf(frame.width, frame.height)
        if (long <= EDGE) return frame
        val ratio = EDGE.toFloat() / long
        val w = (frame.width * ratio).toInt().coerceAtLeast(1)
        val h = (frame.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, w, h, true)
    }

    /**
     * 先写临时文件再改名：半截 JPEG 落在缓存里，下次会被当成"已缓存"解出一张花屏。
     * 这与 `MediaRepository` 落盘时用 `IS_PENDING` 是同一个道理，只是 filesDir 里没有系统帮忙。
     */
    private fun writeAtomically(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (!tmp.renameTo(file)) {
                // 有些文件系统上 rename 到已存在的目标会失败：先删再改名。
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure {
            android.util.Log.w("VideoThumbs", "封面没写下去：${file.name}", it)
            tmp.delete()
        }
    }

    /**
     * 超出预算就按"最久没被打开过"淘汰到 80%。
     *
     * 按 `lastModified` 而不是文件名：文件名里的 id 是自增的，按 id 淘汰会把
     * 最新录的那几段先删掉 —— 而它们恰好是用户最常回看的。
     */
    private fun trimLocked(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
        val total = files.sumOf { it.length() }
        if (!shouldTrimThumbCache(total, DISK_BUDGET_BYTES)) return
        var left = total
        val target = (DISK_BUDGET_BYTES * 0.8).toLong()
        for (f in files.sortedBy { it.lastModified() }) {
            if (left <= target) break
            val len = f.length()
            if (f.delete()) left -= len
        }
    }

    /** 清空（设置页"清缓存"将来接线时用；现在没有调用点，所以只做它说的事，不假装统计）。 */
    suspend fun clear(context: Context) {
        memory.evictAll()
        withContext(Dispatchers.IO) {
            dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}
