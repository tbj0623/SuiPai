package com.suipai.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 删一张的结果。`NEEDS_CONSENT` 不是失败，是"还差你点一下允许"。 */
enum class DeleteOutcome { DELETED, NEEDS_CONSENT, FAILED }

class MediaRepository(private val context: Context) {
    companion object {
        const val PAGE = 400

        /**
         * [lastByIds] 最多留几份。收藏 / 隐藏两份来回换，正常只会用到 2~3 份；
         * 给到 8 是留给"批量操作中途 id 集合一直在变"的那几趟，再多就是无界泄漏。
         */
        private const val MAX_BY_IDS_CACHE = 8

        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )
    }

    /**
     * 一次读回来的两份清单。
     *
     * `null` = **没读到**（游标是 null，或者查询抛了）；空表 = 读到了，库里确实没有。
     * 这两件事原来在界面上长得一样：媒体提供方抖一下（撤权限、partial access、进程被杀），
     * 画廊就说"这就是你的全部照片"，顺手还把 `hasMore` 关掉 —— 用户看到的是"照片没了"。
     */
    data class LibraryRead(val photos: List<PhotoItem>?, val albums: List<AlbumItem>?)

    /**
     * 上一次 `loadByIds` 的结果，按"id 集合"分开缓存。
     *
     * 与 [lastLibrary] 同一条理由（见那边注释）：`AppState.refresh()` 每次 `ON_RESUME`
     * 都会重读收藏与隐藏两份，给回同一个 `List` 实例至少免掉一次 5000 条的重建与比较，
     * 也让"两份清单里实际被稳住的那两份"变成四份 —— 改前只有 `readLibrary` 那两份是稳的。
     *
     * key 是 id 集合本身：同一批 id 读出来的内容只可能因为"行被删了"而变，
     * 那种情况 generation 会变、`AppState` 会走全量重读，走到这里时 id 集合通常也变了。
     */
    private val lastByIds = LinkedHashMap<Set<Long>, List<PhotoItem>>()

    /**
     * 媒体库的变更代数。
     *
     * `AppState.refresh()` 靠它决定"这一趟还要不要全量重读"（见 `RefreshGate`）。
     * 分档按 API：
     *  - **≥ 30**：`getGeneration(context, VOLUME_EXTERNAL)` —— 专门为这个用途加的，
     *    任何增删改都会让它变，是最准的信号；
     *  - **29**：`getVersion(context, VOLUME_EXTERNAL)` —— 同一件事的字符串版，
     *    Android 10 上就有；
     *  - **< 29**：`null`。`VOLUME_EXTERNAL` 这个常量本身要 29，26~28 上只能拿
     *    `MediaStore.VOLUME_EXTERNAL` 之外的写法或者干脆不判断 —— 返回 null 让调用方
     *    退回"每次都读"（改前行为），不猜。
     *
     * 整段 `runCatching`：某些提供方（或系统「部分照片」模式）在没权限时会抛
     * `SecurityException`，而"拿不到代数"**不等于**"库没变" —— 返回值是 `String?`，
     * null 会被 `RefreshGate` 读成"必读"，这正是我们要的。
     */
    suspend fun generation(): String? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL).toString()
                Build.VERSION.SDK_INT >= 29 ->
                    MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
                else -> null
            }
        }.onFailure {
            android.util.Log.w("MediaRepository", "查媒体库 generation 没查成，这一趟按必读处理", it)
        }.getOrNull()
    }

    /**
     * 上一次的结果实例。内容没变就把**同一对列表还回去**——
     * `MainActivity.refresh()` 每次 `ON_RESUME` 都跑一趟，换成新 List 会让画廊那条
     * 带 40 个键的 `remember` 整链重算（5000 张量到 18~25 ms），而那正是"回到 app 的第一帧"。
     * 比较放在 IO 线程做，主线程一次都不做。没读到的那一次不写缓存。
     */
    private var lastLibrary: PhotoLibrary? = null

    suspend fun readLibrary(): LibraryRead = withContext(Dispatchers.IO) {
        val photos = query(bucketId = null, limit = PAGE, offset = 0)
        val albums = queryAlbums()
        if (photos == null || albums == null) return@withContext LibraryRead(photos, albums)
        val prev = lastLibrary
        val next = if (prev != null && prev.photos == photos && prev.albums == albums) prev
        else PhotoLibrary(photos, albums)
        lastLibrary = next
        LibraryRead(next.photos, next.albums)
    }

    suspend fun loadPhotosPage(offset: Int): List<PhotoItem>? = withContext(Dispatchers.IO) {
        query(bucketId = null, limit = PAGE, offset = offset)
    }

    suspend fun loadAlbum(bucketId: String): List<PhotoItem>? = withContext(Dispatchers.IO) {
        if (bucketId == Favorites.ALBUM_ID) loadByIds(Favorites(context).ids())
        else query(bucketId)
    }

    /**  ids 为空是真的空（返回空表）；`null` 才是"没读到"。 */
    suspend fun loadByIds(ids: Set<Long>): List<PhotoItem>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val found = LinkedHashMap<Long, PhotoItem>(ids.size)
        var failed = false
        ids.chunked(180).forEach { chunk ->
            val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"
            val cursor = runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PHOTO_PROJECTION,
                    selection,
                    null,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
                )
            }.onFailure { android.util.Log.w("MediaRepository", "按 id 读这一段没读成：${chunk.size}", it) }.getOrNull()
            if (cursor == null) failed = true else cursor.use {
                val cols = PhotoColumns(it)
                while (it.moveToNext()) {
                    val photo = readPhoto(it, cols)
                    found[photo.id] = photo
                }
            }
        }
        if (failed) null else reuseIfSame(ids, found.values.sortedByDescending { it.dateTaken })
    }

    /**
     * 内容与上一次一样就把同一个实例还回去，否则记下新的（理由见 [lastByIds]）。
     *
     * 上锁是必要的：`refresh()` 可能被 `ON_RESUME` 与用户动作同时触发，
     * 两条协程都在 IO 线程上，一个 `clear()` 与一个 `put()` 撞在一起就是
     * `ConcurrentModificationException` —— 那会从 `loadByIds` 抛出去，
     * 调用点没接住，整趟刷新就烂在半路。
     */
    private fun reuseIfSame(ids: Set<Long>, next: List<PhotoItem>): List<PhotoItem> =
        synchronized(lastByIds) {
            val prev = lastByIds[ids]
            if (prev != null && prev == next) {
                prev
            } else {
                // 只留最近几份：收藏 / 隐藏两份来回换，缓存无界增长就是泄漏。
                if (lastByIds.size >= MAX_BY_IDS_CACHE) lastByIds.clear()
                lastByIds[ids] = next
                next
            }
        }

    /**
     * 存一张 JPEG。
     *
     * [dateTaken] 是**这张照片什么时候拍的**，不是"什么时候写进库"。
     * 它必须能由调用方给：互通收到对面一张三年前的照片时，写"现在"会让时间线按
     * 「收到的先后」排，收得越多越乱（相册的主排序就是 `DATE_TAKEN DESC`）。
     * 默认值留给"刚拍/刚生成"的那一类调用方，它们本来就没有另一个时刻。
     *
     * `<= 0` 视为"不知道"并换成当前时刻 —— 见 [dateTakenToWrite]。
     */
    suspend fun saveJpeg(
        bytes: ByteArray,
        displayName: String,
        dateTaken: Long = System.currentTimeMillis(),
    ): Uri? = saveBytes(bytes, displayName, "image/jpeg", dateTaken)

    suspend fun saveBytes(
        bytes: ByteArray,
        displayName: String,
        mime: String,
        dateTaken: Long = System.currentTimeMillis(),
    ): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenForSave(dateTaken))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SuiPai")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        // insert 之后就已经有 MediaStore 行了。空流、写盘异常或发布失败时
        // 不可把半截照片留在媒体库（尤其是 IS_PENDING=1 的永久幽灵行）。
        insertWritePublishOrRollback(
            insert = { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) },
            write = { uri ->
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(bytes)
                    true
                } ?: false
            },
            publish = { uri ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true
                else {
                    val update = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, update, null, null) > 0
                }
            },
            rollback = { uri -> resolver.delete(uri, null, null) },
            onFailure = { e -> android.util.Log.w("MediaRepository", "保存照片失败，已尝试清理半成品", e) },
        )
    }

    suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        quality: Int = 95,
        sourceUri: Uri? = null,
        png: Boolean = false,
    ): Uri? = withContext(Dispatchers.IO) {
        // 压缩本身是 CPU 密集：调用方多在 Main 的 rememberCoroutineScope 里，
        // 不把整段挪到 IO 上就会卡住主线程（2560–4096px、quality 95–100）。
        val stream = ByteArrayOutputStream()
        if (png) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        val mime = if (png) "image/png" else "image/jpeg"
        val uri = saveBytes(stream.toByteArray(), displayName, mime) ?: return@withContext null
        if (sourceUri != null) copyExif(sourceUri, uri)
        uri
    }

    /**
     * 复制进 Pictures/SuiPai。按源文件的真实 MIME 落盘：
     * 以前一律写成 .jpg，PNG/WebP 的副本就是「扩展名和内容不符」的文件，
     * 别的 App 按名字选解码器会打不开。系统分享的图也走这里。
     */
    suspend fun copyToSuiPai(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        // 别人分享进来的 uri 完全可能已经读不到（对方清了临时文件、或者就是个坏行）。
        // `openInputStream` 对这种情况是**抛 FileNotFoundException**，不是回 null ——
        // 装机实测：从外面分享一张读不到的图进来，`LaunchedEffect` 里没人接，
        // 主线程直接 FATAL EXCEPTION（logcat: No item at content://…/99999999）。
        // 这里收成 null，调用点本来就按"没存上几张"说话。
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            android.util.Log.w("MediaRepository", "这张读不出来：$uri", e)
            null
        } ?: return@withContext null
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            .ifBlank { "image/jpeg" }
        val dest = saveBytes(
            bytes,
            "SuiPai_副本_${System.currentTimeMillis()}.${extensionOf(mime)}",
            mime,
            dateTaken = dateTakenOf(uri),
        ) ?: return@withContext null
        copyExif(uri, dest)
        dest
    }

    /**
     * 源文件在媒体库里的拍摄时间；查不到（不是媒体库行 / 提供方没答话 / 值不合法）返回 0。
     *
     * 副本必须沿用原片的拍摄时间：把三年前的一张图分享进随拍、它却排在最前面，
     * 时间线从此就按"什么时候复制过"排序 —— 与 `saveIncoming` 那条是同一个病。
     * 返回 0 交给 [dateTakenForSave] 换成"现在"（那时确实只有这一个时刻可写）。
     *
     * 整段 `runCatching`：分享进来的 uri 完全可能来自别的 provider，
     * 它既可以抛 `SecurityException`，也可以没有 `DATE_TAKEN` 这一列。
     */
    private fun dateTakenOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            null,
            null,
            null,
        )?.use { c ->
            val col = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            if (col >= 0 && c.moveToFirst()) c.getLong(col) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun extensionOf(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "jpg"
    }

    /**
     * 删一张。返回**为什么没删掉**，而不是一个 false。
     *
     * Android 11 起，删不是本应用存的东西会抛 `RecoverableSecurityException` ——
     * 名字里的 recoverable 就是"还能救：去问用户要授权"（`createDeleteRequest` 那个系统确认框）。
     * 原来这里是 `runCatching { … }.getOrDefault(false)`，把外来照片（adb push 进来的
     * owner=com.android.shell、以及 owner 为 NULL 的那些）全吞成 false：
     * 界面上进度条照常 1/N 走到头，照片一张不少，什么也不说。
     */
    suspend fun deleteOne(uri: Uri): DeleteOutcome = withContext(Dispatchers.IO) {
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) DeleteOutcome.DELETED
            else DeleteOutcome.FAILED
        } catch (e: SecurityException) {
            // RecoverableSecurityException 是 API 29 才有的类（SecurityException 的子类）；
            // 26~28 抛 SecurityException 就是真删不了，没有可挽回的路。
            if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) {
                DeleteOutcome.NEEDS_CONSENT
            } else {
                DeleteOutcome.FAILED
            }
        } catch (e: Exception) {
            DeleteOutcome.FAILED
        }
    }

    /**
     * 把"要你点允许"的一批 uri 打成一个系统确认框（一次问完，不是一张弹一次）。
     * null = 这台机器上没有这条路（API < 30），或者打包失败。
     *
     * 门槛是 **30 不是 29**：`RecoverableSecurityException` 从 29 就在，
     * `createDeleteRequest` 却要 30 —— Android 10 上删别人的照片会抛一个"可挽回"的异常，
     * 系统却不给挽回的入口。原来我按 29 挡，`:app:lintRelease` 的 NewApi 当场抓到
     * （按 29 走会在 Android 10 上 NoSuchMethodError 直接崩）。
     */
    fun deleteRequestFor(uris: List<Uri>): androidx.activity.result.IntentSenderRequest? {
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return null
        return try {
            androidx.activity.result.IntentSenderRequest.Builder(
                MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            ).build()
        } catch (e: Exception) {
            // 不静默：这条一旦在某台机器上不可用，用户会看到"要授权但没入口"，
            // 而 logcat 里得留着原因（否则这条和 `runCatching{}.getOrDefault(false)` 没区别）。
            android.util.Log.w("MediaRepository", "createDeleteRequest 失败，uris=" + uris.size, e)
            null
        }
    }

    /**
     * 这些 id 里哪些**还在**媒体库里。
     * 系统确认框回来之后用它判断用户到底允许了哪几张（也可能一张都没允许）。
     *
     * 返回 null 表示**没探到**（查询本身炸了），不等于"都不在了"。
     * 原来这里 `runCatching { … }` 之后直接返回已经填好的 `found`，
     * 于是查询一失败就交回空集，而调用方把空集读成"asked 那些全没了"，
     * 对用户报一句"删了 N 张" —— 这是一句没量过的成功。
     */
    suspend fun stillPresent(ids: List<Long>): Set<Long>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptySet()
        val found = HashSet<Long>()
        val succeeded = runCatching {
            val sel = ids.joinToString(",")     // id 是我们自己从 MediaStore 取的 Long，不是用户输入
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media._ID + " IN (" + sel + ")",
                null,
                null,
            ) ?: return@runCatching false
            cursor.use { c ->
                val i = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) found += c.getLong(i)
            }
            true
        }.getOrDefault(false)
        // query 返回 null 也是"没有读到"，绝不能当成空表、
        // 更不能由上游误报"用户批准删除，照片已经没了"。
        if (!succeeded) {
            android.util.Log.w("MediaRepository", "stillPresent 查询失败或游标为空，ids=" + ids.size)
            null
        } else {
            found
        }
    }

    private fun copyExif(source: Uri, dest: Uri) {
        runCatching {
            val src = context.contentResolver.openInputStream(source)?.use { ExifInterface(it) }
                ?: return
            val pfd = context.contentResolver.openFileDescriptor(dest, "rw") ?: return
            pfd.use { fd ->
                val dst = ExifInterface(fd.fileDescriptor)
                val tags = listOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_APERTURE_VALUE,
                    ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                )
                for (tag in tags) {
                    src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                }
                dst.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "SuiPai")
                dst.saveAttributes()
            }
        }
    }

    private fun queryAlbums(): List<AlbumItem>? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val buckets = linkedMapOf<String, AlbumItem>()
        // 与 `query` 同一条理由：游标拿不到 / 半路抛了 = "没读到"，不是"一个相册都没有"。
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sort,
            )
        }.onFailure { android.util.Log.w("MediaRepository", "读相册清单没读成", it) }.getOrNull()
            ?: return null
        val read = runCatching {
            cursor.use {
            // 与 `PhotoColumns` 同一条理由：`_ID` 之外一律容错，
            // 少一列不该让「相册」那一页整个空掉。（`getColumnIndex` 缺失给 -1，`strOr/intOr/longOr` 兜住。）
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val bucketIdCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.strOr(bucketIdCol, "")
                if (bucketId.isEmpty()) continue
                val existing = buckets[bucketId]
                if (existing == null) {
                    val id = cursor.getLong(idCol)
                    val taken = cursor.longOr(takenCol).takeIf { v -> v > 0 } ?: cursor.longOr(addedCol) * 1000
                    buckets[bucketId] = AlbumItem(
                        id = bucketId,
                        name = cursor.strOr(bucketNameCol, "").ifBlank { "未命名" },
                        cover = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        count = 1,
                        lastTaken = taken,
                    )
                } else {
                    buckets[bucketId] = existing.copy(count = existing.count + 1)
                }
            }
            }
            true
        }.onFailure {
            android.util.Log.w("MediaRepository", "读相册清单读到一半断了", it)
        }.getOrDefault(false)
        if (!read) return null
        return buckets.values.sortedByDescending { it.count }
    }

    private fun query(
        bucketId: String?,
        limit: Int? = null,
        offset: Int = 0,
    ): List<PhotoItem>? {
        val photos = mutableListOf<PhotoItem>()
        val selection = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId) else null
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        // 整趟读下来任何一步不顺利（提供方没答话、抛异常、缺 `_ID` 列）都返回 null = "没读到"，
        // 让调用点留着上一次的结果说一句"没读到"，而不是把空表当成"库里就这些"。
        val cursor = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && limit != null) {
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
                    if (selection != null) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    }
                }
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PHOTO_PROJECTION,
                    extras,
                    null,
                )
            } else {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PHOTO_PROJECTION,
                    selection,
                    args,
                    sort,
                )
            }
        }.onFailure {
            android.util.Log.w("MediaRepository", "读照片没读成 bucket=$bucketId limit=$limit", it)
        }.getOrNull() ?: return null
        val read = runCatching {
            cursor.use {
                var skipped = 0
                val skip = if (Build.VERSION.SDK_INT >= 30 && limit != null) 0 else offset
                val cols = PhotoColumns(it)
                while (it.moveToNext()) {
                    if (skipped < skip) {
                        skipped++
                        continue
                    }
                    photos += readPhoto(it, cols)
                    if (limit != null && photos.size >= limit) break
                }
            }
            true
        }.onFailure {
            android.util.Log.w("MediaRepository", "读照片读到一半断了 bucket=$bucketId", it)
        }.getOrDefault(false)
        if (!read) return null
        return photos
    }

    /**
     * 列号**一次**算好。原来每行要做 9 次 `getColumnIndexOrThrow`（5000 行 = 45 万次查列名），
     * 更要紧的是它不给退路：某些提供方（或系统「部分照片」模式）没有 `BUCKET_DISPLAY_NAME`
     * 这一类列时，`getColumnIndexOrThrow` 直接抛 `IllegalStateException`，
     * 用户看到的症状是"相册一张都没有"，而不是"少了个相册名"。
     */
    private class PhotoColumns(private val cursor: Cursor) {
        private fun idx(name: String) = cursor.getColumnIndex(name)
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name = idx(MediaStore.Images.Media.DISPLAY_NAME)
        val taken = idx(MediaStore.Images.Media.DATE_TAKEN)
        val added = idx(MediaStore.Images.Media.DATE_ADDED)
        val bucketId = idx(MediaStore.Images.Media.BUCKET_ID)
        val bucketName = idx(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val width = idx(MediaStore.Images.Media.WIDTH)
        val height = idx(MediaStore.Images.Media.HEIGHT)
        val size = idx(MediaStore.Images.Media.SIZE)
    }

    private fun readPhoto(cursor: Cursor, c: PhotoColumns): PhotoItem {
        val id = cursor.getLong(c.id)
        val taken = cursor.longOr(c.taken).takeIf { v -> v > 0 } ?: cursor.longOr(c.added) * 1000
        return PhotoItem(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            displayName = cursor.strOr(c.name, "IMG_$id.jpg"),
            dateTaken = taken,
            bucketId = cursor.strOr(c.bucketId, ""),
            bucketName = cursor.strOr(c.bucketName, "相册"),
            width = cursor.intOr(c.width),
            height = cursor.intOr(c.height),
            sizeBytes = cursor.longOr(c.size),
        )
    }
}

/**
 * 落 `DATE_TAKEN` 之前最后一道夹：`<= 0` 一律换成"现在"。
 *
 * 为什么不是"不写"（像 `Trash.dateTakenToWrite` 那样返回 0）：那个函数服务于**恢复**——
 * 原行没记下拍摄时间时，"不写"会让媒体库留空（时间线退回 `DATE_ADDED`，行为可接受）；
 * 而这里是**新建**一行，写 0 会让它落在 1970 年附近、被时间线排到最后，
 * 比"按收到时刻排"更糟。所以两处的口径必须不同，不能共用同一个函数。
 *
 * 抽成纯函数（而不是就地 `if`）是为了能在 JVM 上钉住：`saveBytes` 里那一行要
 * `ContentResolver`，单测里是返回默认值的桩，测不到真行为。
 */
internal fun dateTakenForSave(raw: Long, now: Long = System.currentTimeMillis()): Long =
    if (raw > 0L) raw else now

/**
 * 列不存在（`getColumnIndex` 给 -1）就退回默认值，而不是把整趟加载带崩。
 */
private fun Cursor.longOr(col: Int, def: Long = 0L): Long = if (col < 0) def else getLong(col)
private fun Cursor.intOr(col: Int, def: Int = 0): Int = if (col < 0) def else getInt(col)
private fun Cursor.strOr(col: Int, def: String): String = if (col < 0) def else (getString(col) ?: def)
