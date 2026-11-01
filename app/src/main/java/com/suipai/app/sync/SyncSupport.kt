package com.suipai.app.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import com.suipai.app.data.MediaRepository
import com.suipai.core.sync.SyncHub
import com.suipai.core.sync.SyncPhotoMeta
import com.suipai.core.sync.SyncPhotoSink
import com.suipai.core.sync.SyncPhotoSource
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Android 侧互通单例持有者。
 *
 * deviceId 持久化在 SharedPreferences("suipai")；deviceName 可改名，改名后重建 [SyncHub]
 * （SyncHub 的 deviceName 是构造期冻结的，没有 setter，重建是最简单可靠的方式）。
 */
object SyncManager {
    private const val PREFS = "suipai"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    @Volatile
    private var hub: SyncHub? = null

    /** 首次调用创建并 start()，之后直接返回既有实例。 */
    @Synchronized
    fun ensure(context: Context): SyncHub {
        hub?.let { return it }
        val app = context.applicationContext
        val created = build(app, deviceName(app))
        created.start()
        hub = created
        return created
    }

    /**
     * 写入新名字并重建 hub：停旧、起新，返回新实例供 UI 重新订阅。
     *
     * **浏览授权必须跨过这一次重建**：`browseGrants` 只活在内存里，而改名是用户主动做的一件
     * 与隐私无关的事。改前 `stop()` 之后再 `build()`，之前逐台点过的「允许浏览」全部丢失 ——
     * 对方还在同一个 IP 上，却突然读不了，界面上还多出一条待确认的请求。
     * 那不是把权限收得更严（收回是 `revokeBrowse` 的活），只是一个没人预期的副作用。
     */
    @Synchronized
    fun rename(context: Context, name: String): SyncHub {
        val app = context.applicationContext
        val clean = name.trim().ifBlank { defaultDeviceName(app) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICE_NAME, clean)
            .apply()
        val old = hub
        // 先拍快照再 stop：`stop()` 之后 `state` 还在，但拍在更早的一刻能保证
        // "停止过程中没有新的握手挤进来"这件事不影响要带过去的那份清单。
        val carriedGrants = old?.state?.value?.browseGrants.orEmpty()
        old?.stop()
        val created = build(app, clean)
        created.restoreBrowseGrants(carriedGrants)
        created.start()
        hub = created
        return created
    }

    /** 当前实例（未初始化则为 null）。 */
    fun current(): SyncHub? = hub

    /** 展示用本机名（已保存则用保存值，否则设备默认名）。 */
    fun deviceName(context: Context): String {
        val app = context.applicationContext
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_NAME, null)
        return saved?.takeIf { it.isNotBlank() } ?: defaultDeviceName(app)
    }

    private fun build(context: Context, name: String): SyncHub = SyncHub(
        deviceId = deviceId(context),
        deviceName = name,
        platform = "android",
        source = AndroidSyncSource(context),
        sink = AndroidSyncSink(context),
    )

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun defaultDeviceName(context: Context): String {
        val system = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return system?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
    }
}

/**
 * 本机 MediaStore 照片来源。id 约定为字符串化的 MediaStore _id。
 * 方法可能被 SyncHub 在后台线程调用，故自身不做线程切换，由调用方（SyncHub 的 IO 协程）保证。
 */
class AndroidSyncSource(context: Context) : SyncPhotoSource {
    private val appContext = context.applicationContext

    override fun listPhotos(offset: Int, limit: Int): List<SyncPhotoMeta> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<SyncPhotoMeta>(limit)
        val resolver = appContext.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val extras = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                if (offset > 0) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
            }
            resolver.query(uri, PROJECTION, extras, null)
        } else {
            resolver.query(uri, PROJECTION, null, null, sort)
        }
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val skip = if (Build.VERSION.SDK_INT >= 30) 0 else offset
            var skipped = 0
            while (it.moveToNext()) {
                if (skipped < skip) {
                    skipped++
                    continue
                }
                val id = it.getLong(idCol)
                val taken = it.getLong(takenCol).takeIf { v -> v > 0 } ?: it.getLong(addedCol) * 1000
                out += SyncPhotoMeta(
                    id = id.toString(),
                    name = it.getString(nameCol) ?: "IMG_$id.jpg",
                    dateTaken = taken,
                    width = it.getInt(widthCol),
                    height = it.getInt(heightCol),
                    sizeBytes = it.getLong(sizeCol),
                )
                if (out.size >= limit) break
            }
        }
        return out
    }

    override fun readPhotoBytes(id: String): ByteArray? {
        val photoId = id.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        return runCatching {
            // 先查 SIZE，超限直接拒，避免把超大图整读进内存
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst() && c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE) >= 0) {
                    if (c.getLong(0) > MAX_READ_BYTES) return null
                }
            }
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.size > MAX_READ_BYTES) null else bytes
        }.getOrNull()
    }

    override fun readThumbBytes(id: String, maxEdge: Int): ByteArray? {
        val photoId = id.toLongOrNull() ?: return null
        val edge = if (maxEdge > 0) maxEdge else 256
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        return runCatching {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    appContext.contentResolver,
                    photoId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                ) ?: decodeSampled(uri, edge)
            }
            bitmap?.let { compressJpeg(it) }
        }.getOrNull()
    }

    private fun decodeSampled(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024 * 1024
        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )
    }
}

/**
 * 收到照片的落地实现：复用 MediaRepository.saveJpeg（落到 Pictures/SuiPai）。
 *
 * SyncPhotoSink 接口是同步签名，而 saveJpeg 是 suspend。SyncHub 在后台 Dispatchers.Default
 * 线程里调用本方法，没有现成挂起点可续，故用 runBlocking 桥接；saveJpeg 内部会切到
 * Dispatchers.IO，runBlocking 只阻塞当前后台线程，不会卡主线程。
 */
class AndroidSyncSink(context: Context) : SyncPhotoSink {
    private val repo = MediaRepository(context.applicationContext)

    /**
     * 收一张照片：**用对端给的拍摄时间落盘**。
     *
     * 这条曾经是一个已知缺口（注释里记着「`dateTaken` 被丢掉了」）：`saveJpeg` 只收
     * `(bytes, displayName)`，于是每一张收到的照片都写着"收到的那一刻"。一次收十张，
     * 十张落在同一个毫秒上 —— 而相册的主排序正是 `DATE_TAKEN DESC`，收得越多时间线越乱，
     * 且乱法取决于网络快慢而不是照片本身。
     *
     * 正确的位置就在这里：`SyncPhotoSink` 的签名已经带 `dateTaken`（服务端从对端的
     * `listPhotos` 元信息里拿到的原始拍摄时间），只是在桥上被丢掉了。
     * `dateTaken <= 0`（老对端没填 / 元信息损坏）交给 `saveJpeg` 的兜底换成"现在"——
     * 那种情况下"现在"确实是唯一知道的时刻。
     */
    override fun saveIncoming(bytes: ByteArray, suggestedName: String, dateTaken: Long): Boolean =
        runBlocking {
            repo.saveJpeg(bytes, suggestedName, dateTaken) != null
        }
}