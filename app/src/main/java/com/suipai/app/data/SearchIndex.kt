package com.suipai.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 全库搜索索引：**不受 `MediaRepository.PAGE = 400` 限制**的那一份照片目录。
 *
 * 为什么要有这个文件：现在画廊的搜索（`GalleryScreen.matchesPhotoQuery`）只在
 * `readLibrary()` 读回来的前 400 张里翻字 —— 库里有 5000 张时，搜「上海」只覆盖前 8%。
 * 那个 400 是**首屏渲染**的预算（一页 400 张刚好够铺满一屏并留一屏预取），
 * 不是搜索的边界。所以搜索单开一本账：分页扫全库（自己用 `QUERY_ARG_LIMIT/OFFSET`
 * 翻页，每页 [PAGE]），把 MediaStore 的 9 列 + EXIF 的一段摘要落成 JSON。
 *
 * 三条设计约束（照 `MediaRepository` 的口径抄的）：
 *  1. **只开流不解码**：EXIF 只读文件头 64 KB 交给 `ExifInterface(InputStream)`。
 *     整图解码一次要 5~30 ms/张，5000 张就是半分钟到两分钟，而且会把内存顶穿；
 *     而 EXIF 全在 JPEG 的 APP1 / HEIC 的头部盒里，64 KB 足够。
 *  2. **读不到就跳过该字段，绝不抛**：一张坏图不该让整趟索索引崩掉。
 *  3. **哪一步没读成，就保留旧索引**：`rebuild()` 一张都没扫到时不清空已有数据
 *     （媒体提供方抖一下、撤权限、partial access 都会走到这里）——
 *     把"没读到"当成"库里没有"是本项目反复踩过的那一类谎话。
 *
 * 索引是**幂等的、可重建的缓存**：删掉 `filesDir/search_index.json` 只会让它下次重建，
 * 不会丢任何用户数据。所以版本号对不上时直接当空表重建，不做迁移。
 */
class SearchIndex internal constructor(
    /** `null` = 纯内存模式（JVM 单测）；生产路径恒为 `context.contentResolver`。 */
    private val resolver: ContentResolver?,
    private val file: File,
) {

    constructor(context: Context) : this(
        context.applicationContext.contentResolver,
        File(context.applicationContext.filesDir, FILE_NAME),
    )

    /** JVM 单测用的入口：不碰 ContentResolver，只测落盘 / 查询 / 建议。 */
    internal constructor(file: File) : this(null, file)

    private val lock = Any()
    private val items = ArrayList<IndexedPhoto>()
    private val byId = HashMap<Long, IndexedPhoto>()

    /** 全库扫完的时刻（毫秒）；0 = 还没全量扫过。 */
    @Volatile
    var builtAt: Long = 0L
        private set

    /** 落盘/载入时那份 JSON 的版本号，给探针用。 */
    @Volatile
    var version: Int = 0
        private set

    /** 索引里现在有多少张。界面用它说「已索引 N 张」。 */
    fun size(): Int = synchronized(lock) { items.size }

    fun snapshot(): List<IndexedPhoto> = synchronized(lock) { ArrayList(items) }

    fun photoById(id: Long): IndexedPhoto? = synchronized(lock) { byId[id] }

    /**
     * 把一张清单装进索引（合并、按 id 去重）。**给测试和"外部已有索引快照"用**：
     * 生产路径只有 `rebuild()` / `refreshIncremental()` 会写这本账。
     */
    internal fun seed(photos: List<IndexedPhoto>, builtAt: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            replaceAllLocked(items + photos)
            if (builtAt > 0) this.builtAt = builtAt
            version = SearchJson.VERSION
        }
    }

    // ── 落盘 ──────────────────────────────────────────────────────────

    /**
     * 从 `filesDir/search_index.json` 载入，返回**装进来多少张**。
     *
     * 0 有四种来源，界面不必区分（都是"这本账还没有内容"）：文件不存在、读不动、
     * JSON 坏了、版本号对不上。后两种会把文件删掉 —— 留着它只会让每次启动都白解析一遍
     * 一兆多的垃圾。
     */
    fun load(): Int {
        if (!file.isFile) return 0
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "索引文件读不动，按空索引处理", e)
            return 0
        }
        val parsed = SearchJson.decodeIndex(text)
        if (parsed == null) {
            Log.w(TAG, "索引文件坏了（${text.length} 字符），删掉重建")
            dropFile()
            return 0
        }
        if (parsed.version != SearchJson.VERSION) {
            Log.w(TAG, "索引版本 ${parsed.version} 与当前 ${SearchJson.VERSION} 不符，删掉重建")
            dropFile()
            return 0
        }
        synchronized(lock) {
            replaceAllLocked(parsed.photos)
            builtAt = parsed.builtAt
            version = parsed.version
        }
        return items.size
    }

    /** 落盘。写临时文件再改名：进程在写一半时被杀，旧文件仍然完整。 */
    suspend fun save() = withContext(Dispatchers.IO) { writeNow() }

    internal fun writeNow(): Boolean {
        val snapshot = synchronized(lock) {
            if (items.isEmpty() && builtAt == 0L) return false
            SearchJson.encodeIndex(items, builtAt)
        }
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(snapshot)
            if (!tmp.renameTo(file)) {
                // 某些实现上目标已存在时 renameTo 会失败：先删再试一次，仍失败就直接覆盖写。
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(snapshot)
                    tmp.delete()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "索引没写进磁盘（${snapshot.length} 字符）", e)
            false
        }
    }

    private fun dropFile() {
        try {
            if (!file.delete() && file.exists()) Log.w(TAG, "坏索引文件删不掉：${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "坏索引文件删不掉", e)
        }
    }

    private fun replaceAllLocked(photos: List<IndexedPhoto>) {
        val merged = mergeIndexed(emptyList(), photos)
        items.clear()
        items.addAll(merged)
        byId.clear()
        merged.forEach { byId[it.photoId] = it }
    }

    /**
     * 只补新照片：按"id 不在索引里"判定，读回来之后整表合并（按 id 去重）。
     *
     * **不处理删除**：照片被删掉时索引里那一行还在，查询会给出一条打不开的结果。
     * 这是有意的取舍 —— 判断"这条还在不在"要在增量时再扫一遍全库 id，
     * 而增量扫描挂在上传/回到前台的路径上，宁快勿全。真要清掉，跑一次 [rebuild]。
     *
     * @return 新补进来几张（0 = 没有新照片，或者这一趟没读成）。
     */
    suspend fun refreshIncremental(): Int = withContext(Dispatchers.IO) {
        val resolver = resolver ?: return@withContext 0
        val known = synchronized(lock) { HashSet(byId.keys) }
        val allIds = readAllIds(resolver) ?: return@withContext 0
        val missing = allIds.filter { it !in known }
        if (missing.isEmpty()) return@withContext 0
        val fresh = readByIds(resolver, missing) ?: return@withContext 0
        if (fresh.isEmpty()) return@withContext 0
        val enriched = fresh.map { withExif(resolver, it) }
        synchronized(lock) {
            replaceAllLocked(items + enriched)
        }
        writeNow()
        enriched.size
    }

    // ── 全量重建 ──────────────────────────────────────────────────────

    /**
     * 分页扫全库重建索引。
     *
     * @param onProgress (已索引, 总数)。总数数不出来时给 0 —— 界面据此显示"不定进度"，
     *   而不是一个假的 100%。协程被取消时会在页边界停下（`ensureActive()`），
     *   已扫到的部分**不**落盘（半本账比没有更糟：它会让人以为索引是全的）。
     */
    suspend fun rebuild(onProgress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val resolver = resolver ?: return@withContext
        val total = countRows(resolver)
        val scanned = ArrayList<IndexedPhoto>(if (total > 0) total else 1024)
        var failed = false

        if (Build.VERSION.SDK_INT >= 30) {
            var offset = 0
            while (true) {
                ensureActive()
                val page = readPage(resolver, offset, PAGE)
                if (page == null) {
                    failed = true
                    break
                }
                if (page.isEmpty()) break
                for (row in page) {
                    ensureActive()
                    scanned.add(withExif(resolver, row))
                    onProgress(scanned.size, total)
                }
                offset += page.size
                if (page.size < PAGE) break
            }
        } else {
            // API 26~29 没有 QUERY_ARG_LIMIT/OFFSET：一次读回来再按页报进度。
            // 内存代价是 5000 个引用 + 5000 个 IndexedPhoto，约 1.5 MB，可接受。
            val all = readAllLegacy(resolver)
            if (all == null) {
                failed = true
            } else {
                for (row in all) {
                    ensureActive()
                    scanned.add(withExif(resolver, row))
                    onProgress(scanned.size, if (total > 0) total else all.size)
                }
            }
        }

        if (failed && scanned.isEmpty()) {
            // 一张都没读到：留着旧索引，别把好数据擦成空的。
            Log.w(TAG, "这趟重建一张也没读到，保留旧索引（${size()} 张）")
            onProgress(size(), size())
            return@withContext
        }
        synchronized(lock) {
            replaceAllLocked(scanned)
            builtAt = System.currentTimeMillis()
            version = SearchJson.VERSION
        }
        writeNow()
        onProgress(size(), size())
    }

    private fun countRows(resolver: ContentResolver): Int = try {
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null,
        )?.use { it.count } ?: 0
    } catch (e: Exception) {
        Log.w(TAG, "数不出全库有多少张，进度按不定处理", e)
        0
    }

    /** API 30+：自己翻页。返回值 `null` = 这一页没读成（不是"没有下一页"）。 */
    private fun readPage(resolver: ContentResolver, offset: Int, limit: Int): List<IndexedPhoto>? {
        val extras = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (offset > 0) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, SORT_COLUMNS)
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val cursor = try {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PROJECTION, extras, null)
        } catch (e: Exception) {
            Log.w(TAG, "读索引第 $offset 页没读成", e)
            null
        } ?: return null
        return readRows(cursor)
    }

    private fun readAllLegacy(resolver: ContentResolver): List<IndexedPhoto>? {
        val cursor = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                null,
                null,
                SORT,
            )
        } catch (e: Exception) {
            Log.w(TAG, "老系统上读全库没读成", e)
            null
        } ?: return null
        return readRows(cursor)
    }

    private fun readAllIds(resolver: ContentResolver): List<Long>? {
        val cursor = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "增量扫描读 id 清单没读成", e)
            null
        } ?: return null
        return try {
            cursor.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val out = ArrayList<Long>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) out += c.getLong(col)
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "增量扫描读 id 清单读到一半断了", e)
            null
        }
    }

    private fun readByIds(resolver: ContentResolver, ids: List<Long>): List<IndexedPhoto>? {
        val found = LinkedHashMap<Long, IndexedPhoto>(ids.size)
        var failed = false
        ids.chunked(180).forEach { chunk ->
            val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"
            val cursor = try {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    selection,
                    null,
                    SORT,
                )
            } catch (e: Exception) {
                Log.w(TAG, "按 id 补 ${chunk.size} 张没读成", e)
                null
            }
            if (cursor == null) {
                failed = true
            } else {
                val rows = readRows(cursor)
                if (rows == null) failed = true else rows.forEach { found[it.photoId] = it }
            }
        }
        return if (failed && found.isEmpty()) null else found.values.toList()
    }

    private fun readRows(cursor: Cursor): List<IndexedPhoto>? = try {
        cursor.use { c ->
            val cols = MediaCols(c)
            val out = ArrayList<IndexedPhoto>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += c.photo(cols)
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "读照片行读到一半断了", e)
        null
    }

    /** MediaStore 的 9 列（与 `MediaRepository.PHOTO_PROJECTION` 同口径，只多了 EXIF 摘要）。 */
    private class MediaCols(private val cursor: Cursor) {
        private fun idx(name: String) = cursor.getColumnIndex(name)
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name = idx(MediaStore.Images.Media.DISPLAY_NAME)
        val taken = idx(MediaStore.Images.Media.DATE_TAKEN)
        val added = idx(MediaStore.Images.Media.DATE_ADDED)
        val bucket = idx(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val width = idx(MediaStore.Images.Media.WIDTH)
        val height = idx(MediaStore.Images.Media.HEIGHT)
        val size = idx(MediaStore.Images.Media.SIZE)
    }

    private fun Cursor.photo(c: MediaCols): IndexedPhoto {
        val id = getLong(c.id)
        val taken = longOr(c.taken).takeIf { it > 0 } ?: longOr(c.added) * 1000
        val name = strOr(c.name, "IMG_$id.jpg")
        val bucket = strOr(c.bucket, "相册")
        return IndexedPhoto(
            photoId = id,
            displayName = name,
            bucketName = bucket,
            dateTaken = taken,
            width = intOr(c.width),
            height = intOr(c.height),
            sizeBytes = longOr(c.size),
            make = "",
            model = "",
            lens = "",
            iso = 0,
            aperture = "",
            shutter = "",
            focal = 0,
            hasGps = false,
            lat = 0.0,
            lon = 0.0,
            kind = photoKindOf(name, bucket),
        )
    }

    // ── EXIF ─────────────────────────────────────────────────────────

    /**
     * 读一张的 EXIF 摘要。**只开流、不解码**：读前 [EXIF_HEAD_BYTES] 字节交给
     * `ExifInterface(InputStream)`。任何一步不顺利就返回空摘要（`ExifBits.EMPTY`）——
     * 一行 EXIF 读不到不该让这张照片从索引里消失。
     */
    private fun withExif(resolver: ContentResolver, photo: IndexedPhoto): IndexedPhoto {
        val bits = readExifSafe(resolver, photo)
        return photo.copy(
            make = bits.make,
            model = bits.model,
            lens = bits.lens,
            iso = bits.iso,
            aperture = bits.aperture,
            shutter = bits.shutter,
            focal = bits.focal,
            hasGps = bits.hasGps,
            lat = bits.lat,
            lon = bits.lon,
        )
    }

    /**
     * EXIF 摘要，**永不抛、永不返回 null**。
     *
     * 这一层不是多余的：`ExifInterface` 的构造在个别 ROM 上会对畸形文件抛
     * `RuntimeException`（不是 IOException），而 `readExif` 只接 `Exception` 不收 `Error`。
     * 索引器是**一条 5000 次的循环**：中间炸一次，用户看到的是"索引永远停在 37%"。
     * 所以每张各包一层，坏的那张就退化成没有 EXIF。
     */
    private fun readExifSafe(resolver: ContentResolver, photo: IndexedPhoto): ExifBits = try {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.photoId)
        readExif(resolver, uri) ?: ExifBits.EMPTY
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "这张的 EXIF 读崩了，跳过（id=${photo.photoId}）", e)
        ExifBits.EMPTY
    }

    private fun readExif(resolver: ContentResolver, uri: android.net.Uri): ExifBits? = try {
        val stream: InputStream? = resolver.openInputStream(uri)
        if (stream == null) {
            ExifBits.EMPTY
        } else {
            stream.use { raw ->
                val head = readHead(raw, EXIF_HEAD_BYTES)
                if (head.isEmpty()) {
                    ExifBits.EMPTY
                } else {
                    val exif = ExifInterface(ByteArrayInputStream(head))
                    val gps = exif.latLong
                    val apertureRaw = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ExifBits(
                        make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim(),
                        model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim(),
                        lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty().trim(),
                        iso = runCatching {
                            exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                        }.getOrDefault(0),
                        aperture = if (apertureRaw.isNullOrBlank()) "" else "f/$apertureRaw",
                        shutter = shutterOf(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
                        focal = runCatching {
                            exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                        }.getOrDefault(0),
                        hasGps = gps != null && gps.size >= 2,
                        lat = gps?.getOrNull(0) ?: 0.0,
                        lon = gps?.getOrNull(1) ?: 0.0,
                    )
                }
            }
        }
    } catch (e: Exception) {
        // 每张都打一行日志会把 logcat 刷满（一张坏图一条），所以只在 debug 之外降级成一行汇总不可能；
        // 这里保持一条 warn/张，实测 5000 张里通常 0~3 条。
        Log.w(TAG, "这张读不出 EXIF：$uri", e)
        ExifBits.EMPTY
    }

    private fun readHead(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = try {
                input.read(buf, off, max - off)
            } catch (e: Exception) {
                Log.w(TAG, "读文件头断了（已读 $off 字节）", e)
                -1
            }
            if (n <= 0) break
            off += n
        }
        return if (off == buf.size) buf else buf.copyOf(off)
    }

    // ── 查询 ─────────────────────────────────────────────────────────

    /** 结果只要 id 与原因（调用方拿去选中/打开）。 */
    fun query(q: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> =
        match(q, limit).map { SearchHit(it.photo.photoId, it.reason) }

    /** 带完整索引行的命中，给分组界面用（相册/相机型号/年份要从行里取字段）。 */
    fun match(q: String, limit: Int = DEFAULT_LIMIT): List<SearchMatch> {
        val photos = snapshot()
        return SearchQuery.rank(photos, q, limit, LocalDate.now(zone()), zone())
    }

    /** 输入建议：相机型号 / 年份 / 相册名，按"型号 → 年份 → 相册"的次序排。 */
    fun suggestions(q: String): List<String> = SearchQuery.suggestions(snapshot(), q, zone())

    private fun zone(): ZoneId = ZoneId.systemDefault()

    companion object {
        private const val TAG = "SearchIndex"

        /** 落盘文件名。放在 `filesDir` 下（不是缓存目录）：搜索索引在"没网/没内存"时也要能用。 */
        const val FILE_NAME = "search_index.json"

        /** 索引器自己的分页大小。与 `MediaRepository.PAGE` 无关 —— 那个 400 是首屏预算。 */
        const val PAGE = 500

        /** 单次查询默认上限。5000 张里命中 500 条已经远超一屏能看完的量。 */
        const val DEFAULT_LIMIT = 500

        /** EXIF 只读文件头这么多字节。JPEG 的 APP1 与 HEIC 的头部盒都在里面。 */
        const val EXIF_HEAD_BYTES = 64 * 1024

        private val SORT_COLUMNS = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )

        private val SORT =
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )

        /**
         * 按 id 合并两份清单：**后到的覆盖先到的**，顺序按"先出现的先留"。
         *
         * 增量那一趟给出的 `incoming` 是刚从 MediaStore 读回来的，EXIF 也是刚读的，
         * 所以同 id 时保留新的一份；而 id 集合取并集，于是"同一张被扫两遍"不会变成两条结果。
         */
        internal fun mergeIndexed(
            existing: List<IndexedPhoto>,
            incoming: List<IndexedPhoto>,
        ): List<IndexedPhoto> {
            val map = LinkedHashMap<Long, IndexedPhoto>(existing.size + incoming.size)
            existing.forEach { map[it.photoId] = it }
            incoming.forEach { map[it.photoId] = it }
            return ArrayList(map.values)
        }

        /** 曝光时间归一成"1/125"这种能读的形状；读不出来就原样留着。 */
        internal fun shutterOf(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val sec = raw.toDoubleOrNull() ?: return raw
            if (sec <= 0.0) return raw
            return if (sec < 1.0) {
                val denom = Math.round(1.0 / sec)
                if (denom <= 0L) raw else "1/$denom"
            } else {
                raw
            }
        }
    }
}

/** 一张照片在搜索索引里的一行。全是值类型 —— JVM 单测里造得出来（不像 `PhotoItem` 带着 `Uri`）。 */
data class IndexedPhoto(
    val photoId: Long,
    val displayName: String,
    val bucketName: String,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val make: String,
    val model: String,
    val lens: String,
    val iso: Int,
    val aperture: String,
    val shutter: String,
    val focal: Int,
    val hasGps: Boolean,
    val lat: Double,
    val lon: Double,
    val kind: PhotoKind,
)

/**
 * 索引里的"这是哪一类照片"。判据与 `GalleryScreen` 里那几处前缀判据同源，
 * 但**在数据层再写一份**：那边是 `private fun`，而且它按 `PhotoItem` 判（JVM 造不出来）；
 * 这里只吃两个字符串，于是每条分类规矩都能在单测里钉住。
 */
enum class PhotoKind(val label: String) {
    /** 相机自己存的：`SuiPai_<时间戳>.jpg`，只有一个下划线。 */
    ORIGINAL("原片"),

    /** 一瞬加工出来的成片：`SuiPai_一瞬_<配方>_…`。 */
    YISHUN("一瞬成片"),

    /** 副本 / 旋转 / 灰度 / 缩放这类加工件。 */
    COPY("副本"),

    SCREENSHOT("截图"),

    DOWNLOAD("下载"),

    OTHER("其他"),
}

/** 命中原因。顺序即"同分时的优先级"（文件名最硬，类型最软）。 */
enum class HitReason { FILE_NAME, ALBUM, DATE, CAMERA, LENS, TYPE }

/** @see SearchIndex.query */
data class SearchHit(val photoId: Long, val reason: HitReason)

/** @see SearchIndex.match */
data class SearchMatch(val photo: IndexedPhoto, val reason: HitReason)

/**
 * 两个字符串判"这是哪一类"。顺序即优先级：截图 → 随拍系列（原片/一瞬/副本）→ 下载 → 其他。
 *
 * 「随拍系列」的判据沿用 `GalleryScreen.isSuiPaiOriginalName` 那一条：**数下划线**。
 * 相机存的原片只有一个（`SuiPai_<时间戳>.jpg`），任何加工件都有两个以上。
 * 所以这里不是"手抄一串前缀排除表"，而是"只要是 SuiPai_ 且不止一个下划线，就是加工件"——
 * 以后新增任何加工动作（灰度/2K/裁切/圆角…）都自动落进副本，不会漏。
 */
internal fun photoKindOf(displayName: String, bucketName: String): PhotoKind {
    val name = displayName
    val bucket = bucketName
    val screenshot = name.startsWith("Screenshot", ignoreCase = true) ||
        name.contains("screenshot", ignoreCase = true) ||
        bucket.contains("Screenshot", ignoreCase = true) ||
        bucket.contains("截屏") ||
        bucket.contains("截图")
    if (screenshot) return PhotoKind.SCREENSHOT
    // 「随拍系列」判据照 `isSuiPaiOriginal`：数下划线。原片只有一个（`SuiPai_<时间戳>.jpg`），
    // 任何加工件（一瞬/副本/灰度/2K/裁切/圆角…）都有两个以上 —— 所以不抄排除表，
    // 以后新增任何加工动作都自动落进「副本」。
    if (name.startsWith("SuiPai_")) {
        if (isSuiPaiOriginal(name)) return PhotoKind.ORIGINAL
        if (name.startsWith("SuiPai_一瞬_")) return PhotoKind.YISHUN
        return PhotoKind.COPY
    }
    if (bucket.contains("download", ignoreCase = true) || bucket.contains("下载")) return PhotoKind.DOWNLOAD
    return PhotoKind.OTHER
}

/**
 * 「随拍原片」= 只有一个下划线的 `SuiPai_…`（与 `GalleryScreen.isSuiPaiOriginalName` 同一条判据，
 * 那边注释解释了为什么按"数下划线"而不是手抄排除表）。
 */
internal fun isSuiPaiOriginal(name: String): Boolean =
    name.startsWith("SuiPai_") && !name.substring("SuiPai_".length).contains("_")

// ── 查询引擎 ─────────────────────────────────────────────────────────

/**
 * 查询语义的唯一实现。**纯函数 + 不碰 Android**，所以 `SearchQueryTest` 能在 JVM 里把它钉死。
 *
 * 规则：
 *  1. **大小写不敏感的子串**（英文/数字按词）。
 *  2. **中文按字、但整段汉字是一个词**：一段连续汉字拆成单字逐个匹配，
 *     **字序必须一致**（子序列）。于是「上海」命中「上海外滩」，不命中「海上日出」。
 *     按字的代价是"整串不连续也会中"，用"整串连续命中"加权把最相关的顶到前面。
 *  3. **多词 AND**：空格/顿号/逗号分开的每一段都必须命中，缺一段就不算结果。
 *  4. **日期模式**（整串或单独一段）：`2024`、`2024-09`/`2024/9`、`9月22`/`9月22日`、`9月`，
 *     以及「今天」「昨天」「今年」。
 *  5. **每条结果带命中原因**：同一个词命中多个维度时，取权重最高的那个。
 *  6. **空查询不返回任何东西** —— 空串不是"全部"，界面上那是"没输入"这一态。
 */
internal object SearchQuery {

    // 正则提成常量：`String.matches(Regex("…"))` 每次调用都重新编译一个 Pattern，
    // 而查询路径上每张照片都要过一遍（GalleryScreen.kt:2075 记过同一件事）。
    private val YearOnly = Regex("\\d{4}")
    private val YearMonthPattern = Regex("(\\d{4})[-/](\\d{1,2})")
    private val MonthDayPattern = Regex("(\\d{1,2})月(\\d{1,2})日?")
    private val MonthOnlyPattern = Regex("(\\d{1,2})月")

    /** 建议条数上限。再多就变成第二屏列表了，而建议是"点一下就走"的东西。 */
    const val SUGGESTION_LIMIT = 12

    private const val W_NAME = 100
    private const val W_ALBUM = 60
    private const val W_CAMERA = 50
    private const val W_LENS = 40
    private const val W_TYPE = 30
    private const val W_DATE = 1000
    private const val BONUS_CONTIGUOUS = 120

    private val SEPARATORS = charArrayOf(' ', '\u3000', '\t', '\n', ',', '，', '、', ';', '；', '|')

    // ── 入参模型 ──

    /** 一段文本词。[hanzi] = 这段是汉字（按字序匹配），否则按整词子串匹配。 */
    internal class TextTerm(val text: String, val hanzi: Boolean) {
        fun hits(field: String): Boolean =
            if (hanzi) inOrder(text, field) else field.contains(text)

        override fun toString(): String = if (hanzi) "{字:$text}" else "{词:$text}"
    }

    /** 日期语义。 */
    internal sealed class DateSpec {
        object Today : DateSpec()
        object Yesterday : DateSpec()
        object ThisYear : DateSpec()
        data class Year(val year: Int) : DateSpec()
        data class YearMonth(val year: Int, val month: Int) : DateSpec()
        data class MonthDay(val month: Int, val day: Int) : DateSpec()
        data class Month(val month: Int) : DateSpec()

        fun matches(onDay: LocalDate, today: LocalDate): Boolean = when (this) {
            is Today -> onDay == today
            is Yesterday -> onDay == today.minusDays(1)
            is ThisYear -> onDay.year == today.year
            is Year -> onDay.year == year
            is YearMonth -> onDay.year == year && onDay.monthValue == month
            is MonthDay -> onDay.monthValue == month && onDay.dayOfMonth == day
            is Month -> onDay.monthValue == month
        }
    }

    internal class ParsedQuery(
        /** 原文（trim 过），给"没搜到「上海」"那句用。 */
        val raw: String,
        /** 去掉分隔符的整串（小写），用来给"连续命中"加权。 */
        val joined: String,
        val texts: List<TextTerm>,
        val dates: List<DateSpec>,
    ) {
        val isEmpty: Boolean get() = texts.isEmpty() && dates.isEmpty()
    }

    // ── 解析 ──

    fun parse(raw: String): ParsedQuery {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedQuery(trimmed, "", emptyList(), emptyList())
        // 整串就是一个日期：直接按日期查，不再当文件名搜（否则「今天」会命中「今天的云.jpg」）。
        val whole = dateSpecOf(trimmed)
        if (whole != null) return ParsedQuery(trimmed, "", emptyList(), listOf(whole))

        val texts = ArrayList<TextTerm>()
        val dates = ArrayList<DateSpec>()
        for (chunk in splitChunks(trimmed)) {
            val spec = dateSpecOf(chunk)
            if (spec != null) {
                dates += spec
            } else {
                texts += tokensOf(chunk)
            }
        }
        return ParsedQuery(trimmed, joinedOf(trimmed), texts, dates)
    }

    /** 英文词 + 单个汉字。汉字逐字成词（按字），所以「上海夜景」= 上/海/夜/景 四个字。 */
    fun tokenize(segment: String): List<String> = tokensOf(segment).map { it.text }

    /**
     * 一段文本切成"词"。
     *
     * **连续汉字合成一个词**（`hanzi = true`，按字序匹配），英文/数字各自成词。
     * 这个分法是被一条真缺陷逼出来的：把汉字**逐字**拆成多个词时，"上海"就变成
     * `上` AND `海` 两个独立条件 —— 而各自只要"出现过"就算命中，
     * 于是「海上日出.jpg」也会被搜出来。**次序约束只在同一段汉字内部成立**，
     * 所以它们必须留在同一个词里。
     *
     * 例：`上海2024` → `{字:上海}`、`{词:2024}`；`IMG_0042` → `{词:img}`、`{词:0042}`。
     */
    private fun tokensOf(segment: String): List<TextTerm> {
        val out = ArrayList<TextTerm>(segment.length)
        val hanzi = StringBuilder()
        val word = StringBuilder()
        fun flushHanzi() {
            if (hanzi.isNotEmpty()) {
                out += TextTerm(hanzi.toString(), hanzi = true)
                hanzi.setLength(0)
            }
        }
        fun flushWord() {
            if (word.isNotEmpty()) {
                out += TextTerm(word.toString(), hanzi = false)
                word.setLength(0)
            }
        }
        for (ch in segment.lowercase(Locale.ROOT)) {
            when {
                isHanzi(ch) -> {
                    flushWord()
                    hanzi.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushHanzi()
                    word.append(ch)
                }
                else -> {
                    flushHanzi()
                    flushWord()
                }
            }
        }
        flushHanzi()
        flushWord()
        return out
    }

    private fun splitChunks(raw: String): List<String> {
        val out = ArrayList<String>(4)
        val buf = StringBuilder()
        for (ch in raw) {
            if (ch in SEPARATORS) {
                if (buf.isNotEmpty()) {
                    out += buf.toString()
                    buf.setLength(0)
                }
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun joinedOf(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) if (ch !in SEPARATORS) sb.append(ch)
        return sb.toString()
    }

    private fun dateSpecOf(chunk: String): DateSpec? {
        when (chunk) {
            "今天" -> return DateSpec.Today
            "昨天" -> return DateSpec.Yesterday
            "今年" -> return DateSpec.ThisYear
        }
        if (YearOnly.matches(chunk)) {
            val y = chunk.toIntOrNull() ?: return null
            return if (y in 1900..2999) DateSpec.Year(y) else null
        }
        YearMonthPattern.matchEntire(chunk)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            return if (mo in 1..12) DateSpec.YearMonth(y, mo) else null
        }
        MonthDayPattern.matchEntire(chunk)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull() ?: return null
            val d = m.groupValues[2].toIntOrNull() ?: return null
            return if (mo in 1..12 && d in 1..31) DateSpec.MonthDay(mo, d) else null
        }
        MonthOnlyPattern.matchEntire(chunk)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull() ?: return null
            return if (mo in 1..12) DateSpec.Month(mo) else null
        }
        return null
    }

    // ── 打分 ──

    internal data class Scored(val score: Int, val reason: HitReason)

    /**
     * 一张照片对一条查询的得分；`null` = 不命中。
     *
     * 权重不是随手拍的：文件名（100）比相册名（60）硬，因为文件名是"这张照片是什么"，
     * 相册名是"它被放在哪"；相机/镜头（50/40）是弱线索；类型（30）最软。
     * 日期（1000）压过一切 —— 用户输入一个日期时，他要的是那一天，不是名字里带那个数字的。
     */
    fun scoreOne(
        photo: IndexedPhoto,
        parsed: ParsedQuery,
        today: LocalDate,
        zone: ZoneId,
    ): Scored? {
        if (parsed.isEmpty) return null
        val nameL = photo.displayName.lowercase(Locale.ROOT)
        val albumL = photo.bucketName.lowercase(Locale.ROOT)
        val makeL = photo.make.lowercase(Locale.ROOT)
        val modelL = photo.model.lowercase(Locale.ROOT)
        val lensL = photo.lens.lowercase(Locale.ROOT)
        val kindL = photo.kind.label.lowercase(Locale.ROOT)
        val day = dayOf(photo.dateTaken, zone)

        var score = 0
        var bestWeight = -1
        var bestReason = HitReason.TYPE

        for (spec in parsed.dates) {
            if (!spec.matches(day, today)) return null
            score += W_DATE
            if (bestWeight < W_DATE) {
                bestWeight = W_DATE
                bestReason = HitReason.DATE
            }
        }

        for (term in parsed.texts) {
            var hitAny = false
            if (term.hits(nameL)) {
                hitAny = true
                score += W_NAME
                if (bestWeight < W_NAME) {
                    bestWeight = W_NAME
                    bestReason = HitReason.FILE_NAME
                }
            }
            if (term.hits(albumL)) {
                hitAny = true
                score += W_ALBUM
                if (bestWeight < W_ALBUM) {
                    bestWeight = W_ALBUM
                    bestReason = HitReason.ALBUM
                }
            }
            if (makeL.isNotEmpty() && term.hits(makeL) || modelL.isNotEmpty() && term.hits(modelL)) {
                hitAny = true
                score += W_CAMERA
                if (bestWeight < W_CAMERA) {
                    bestWeight = W_CAMERA
                    bestReason = HitReason.CAMERA
                }
            }
            if (lensL.isNotEmpty() && term.hits(lensL)) {
                hitAny = true
                score += W_LENS
                if (bestWeight < W_LENS) {
                    bestWeight = W_LENS
                    bestReason = HitReason.LENS
                }
            }
            if (term.hits(kindL)) {
                hitAny = true
                score += W_TYPE
                if (bestWeight < W_TYPE) {
                    bestWeight = W_TYPE
                    bestReason = HitReason.TYPE
                }
            }
            if (!hitAny) return null
        }

        // 整串连续命中加权：按字切分让「上海」也能搜到「上…海」，但"整串连着出现"显然更相关。
        // 条件是 joined.length >= 2 而不是 texts.size > 1：**一段汉字也是一个词**
        // （见 `tokensOf` 的注释），所以「上海」这种最常见的中文查询落在 size == 1 上；
        // 只认 size > 1 会让最常见的那一路拿不到这条加权。
        if (parsed.joined.length >= 2) {
            if (nameL.contains(parsed.joined)) score += BONUS_CONTIGUOUS
            else if (albumL.contains(parsed.joined)) score += BONUS_CONTIGUOUS / 2
        }
        return Scored(score, bestReason)
    }

    private data class Ranked(val photo: IndexedPhoto, val reason: HitReason, val score: Int)

    /** 排序：得分 → 拍摄时间新 → id 大。同分时"新的先看到"，与时间线的口径一致。 */
    fun rank(
        photos: List<IndexedPhoto>,
        q: String,
        limit: Int,
        today: LocalDate,
        zone: ZoneId,
    ): List<SearchMatch> {
        if (limit <= 0) return emptyList()
        val parsed = parse(q)
        if (parsed.isEmpty) return emptyList()
        val hits = ArrayList<Ranked>()
        for (photo in photos) {
            val scored = scoreOne(photo, parsed, today, zone) ?: continue
            hits += Ranked(photo, scored.reason, scored.score)
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortWith(
            compareByDescending<Ranked> { it.score }
                .thenByDescending { it.photo.dateTaken }
                .thenByDescending { it.photo.photoId },
        )
        return hits.take(limit).map { SearchMatch(it.photo, it.reason) }
    }

    // ── 建议 ──

    /**
     * 输入建议：**相机型号 → 年份 → 相册名**，各自按"出现次数多的在前"，相册名同票按字典序。
     *
     * 顺序是有理由的：型号最具体（用户想"我那台相机拍的"），年份次之（"去年的"），
     * 相册名最泛（很多相册名就是"Camera"/"截图"，挑中的价值最低）。
     * 三类之间去重（同一个字符串只出现一次，留在靠前的那一类）。
     */
    fun suggestions(photos: List<IndexedPhoto>, q: String, zone: ZoneId): List<String> {
        val needle = q.trim().lowercase(Locale.ROOT)
        fun accepted(s: String): Boolean =
            needle.isEmpty() || s.lowercase(Locale.ROOT).contains(needle)

        val models = photos.mapNotNull { photo ->
            val m = photo.model.trim()
            if (m.isEmpty()) null else m
        }.filter(::accepted)
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        val years = photos.map { dayOf(it.dateTaken, zone).year }
            .filter { it > 1970 }
            .map { it.toString() }
            .filter(::accepted)
            .groupingBy { it }.eachCount()
            .entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .map { it.key }

        val albums = photos.mapNotNull { photo ->
            val b = photo.bucketName.trim()
            if (b.isEmpty()) null else b
        }.filter(::accepted)
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        val out = ArrayList<String>(SUGGESTION_LIMIT)
        for (bucket in listOf(models.take(4), years.take(3), albums.take(5))) {
            for (s in bucket) {
                if (out.size >= SUGGESTION_LIMIT) return out
                if (s !in out) out += s
            }
        }
        return out
    }

    // ── 小工具 ──

    private fun isHanzi(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'

    /** [chars] 的每一个字都在 [field] 里，且**先后次序一致**（子序列）。 */
    private fun inOrder(chars: String, field: String): Boolean {
        var from = 0
        for (ch in chars) {
            val at = field.indexOf(ch, from)
            if (at < 0) return false
            from = at + 1
        }
        return true
    }

    fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis.coerceAtLeast(0L)).atZone(zone).toLocalDate()
}

// ── 落盘格式 ─────────────────────────────────────────────────────────

/** 索引文件解出来的东西。`version` 由调用方比对，这里不做迁移。 */
internal data class IndexFile(
    val version: Int,
    val builtAt: Long,
    val photos: List<IndexedPhoto>,
)

/**
 * 手写的最小 JSON 读写。
 *
 * 为什么不用 `org.json`：那是 `android.jar` 里的桩，`unitTests.isReturnDefaultValues = true`
 * 之后每个方法都返回默认值（构造出来是个空壳），于是"JSON 往返"这条守卫会绿着空跑。
 * 为什么不用 kotlinx-serialization：这个工程没引它，而索引是**可重建的缓存**，
 * 为它加一个带 R8 keep 规则的序列化框架不划算。
 *
 * 只支持本项目需要的子集：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 解析失败一律抛 [JsonError]，由调用方收成"索引坏了"。
 */
internal object SearchJson {

    /** 版本号。字段增删改语义时 +1 —— 老文件会被当成空索引重建（`SearchIndex.load`）。 */
    const val VERSION = 1

    fun encodeIndex(photos: List<IndexedPhoto>, builtAt: Long): String = buildString(photos.size * 220 + 64) {
        append('{')
        append("\"version\":").append(VERSION)
        append(",\"builtAt\":").append(builtAt)
        append(",\"count\":").append(photos.size)
        append(",\"photos\":[")
        photos.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(p.photoId)
            append(",\"name\":\"").append(escape(p.displayName)).append('"')
            append(",\"album\":\"").append(escape(p.bucketName)).append('"')
            append(",\"taken\":").append(p.dateTaken)
            append(",\"w\":").append(p.width)
            append(",\"h\":").append(p.height)
            append(",\"size\":").append(p.sizeBytes)
            append(",\"make\":\"").append(escape(p.make)).append('"')
            append(",\"model\":\"").append(escape(p.model)).append('"')
            append(",\"lens\":\"").append(escape(p.lens)).append('"')
            append(",\"iso\":").append(p.iso)
            append(",\"aperture\":\"").append(escape(p.aperture)).append('"')
            append(",\"shutter\":\"").append(escape(p.shutter)).append('"')
            append(",\"focal\":").append(p.focal)
            append(",\"gps\":").append(if (p.hasGps) "true" else "false")
            append(",\"lat\":").append(p.lat)
            append(",\"lon\":").append(p.lon)
            append(",\"kind\":\"").append(p.kind.name).append('"')
            append('}')
        }
        append("]}")
    }

    fun decodeIndex(text: String): IndexFile? {
        val root = try {
            JsonParser(text).parseDocument() as? Map<*, *>
        } catch (e: Exception) {
            Log.w("SearchIndex", "索引 JSON 解析失败", e)
            null
        } ?: return null
        val version = (root["version"] as? Number)?.toInt() ?: return null
        val builtAt = (root["builtAt"] as? Number)?.toLong() ?: 0L
        val rawPhotos = root["photos"] as? List<*> ?: return null
        val photos = ArrayList<IndexedPhoto>(rawPhotos.size)
        for (raw in rawPhotos) {
            val obj = raw as? Map<*, *> ?: continue
            val photo = photoOf(obj) ?: continue
            photos += photo
        }
        return IndexFile(version, builtAt, photos)
    }

    private fun photoOf(obj: Map<*, *>): IndexedPhoto? {
        val id = (obj["id"] as? Number)?.toLong() ?: return null
        return IndexedPhoto(
            photoId = id,
            displayName = str(obj["name"]).ifEmpty { "IMG_$id.jpg" },
            bucketName = str(obj["album"]).ifEmpty { "相册" },
            dateTaken = (obj["taken"] as? Number)?.toLong() ?: 0L,
            width = (obj["w"] as? Number)?.toInt() ?: 0,
            height = (obj["h"] as? Number)?.toInt() ?: 0,
            sizeBytes = (obj["size"] as? Number)?.toLong() ?: 0L,
            make = str(obj["make"]),
            model = str(obj["model"]),
            lens = str(obj["lens"]),
            iso = (obj["iso"] as? Number)?.toInt() ?: 0,
            aperture = str(obj["aperture"]),
            shutter = str(obj["shutter"]),
            focal = (obj["focal"] as? Number)?.toInt() ?: 0,
            hasGps = obj["gps"] as? Boolean ?: false,
            lat = (obj["lat"] as? Number)?.toDouble() ?: 0.0,
            lon = (obj["lon"] as? Number)?.toDouble() ?: 0.0,
            kind = PhotoKind.entries.firstOrNull { it.name == str(obj["kind"]) } ?: PhotoKind.OTHER,
        )
    }

    fun encodeStrings(items: List<String>): String = buildString(items.size * 24 + 48) {
        append('{')
        append("\"version\":").append(VERSION)
        append(",\"items\":[")
        items.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append('"').append(escape(s)).append('"')
        }
        append("]}")
    }

    /** `null` = 不是一份能读的清单（文件坏了 / 不是我们写的格式）。 */
    fun decodeStrings(text: String): List<String>? {
        val root = try {
            JsonParser(text).parseDocument() as? Map<*, *>
        } catch (e: Exception) {
            Log.w("SearchIndex", "清单 JSON 解析失败", e)
            null
        } ?: return null
        val version = (root["version"] as? Number)?.toInt() ?: return null
        if (version != VERSION) return null
        val raw = root["items"] as? List<*> ?: return null
        return raw.mapNotNull { it as? String }
    }

    private fun str(v: Any?): String = v as? String ?: ""

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}

/** 解析失败。故意做成受检之外的一个类型，好让调用点只 catch 一次。 */
internal class JsonError(message: String) : Exception(message)

/** 递归下降解析器。只认 [`SearchJson`] 写得出来的那几种值。 */
private class JsonParser(private val src: String) {

    private var i = 0

    fun parseDocument(): Any? {
        val value = parseValue()
        skipWs()
        if (i != src.length) throw JsonError("末尾还有 ${src.length - i} 个字符没读")
        return value
    }

    private fun parseValue(): Any? {
        skipWs()
        if (i >= src.length) throw JsonError("JSON 提前结束")
        return when (val c = src[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else throw JsonError("意外的字符 '$c' @$i")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        skipWs()
        if (peek() == '}') {
            i++
            return out
        }
        while (true) {
            skipWs()
            if (peek() != '"') throw JsonError("对象的键必须是字符串 @$i")
            val key = parseString()
            skipWs()
            if (peek() != ':') throw JsonError("键后面缺冒号 @$i")
            i++
            out[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return out
                }
                else -> throw JsonError("对象里要用 , 或 } @$i")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        skipWs()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            out += parseValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return out
                }
                else -> throw JsonError("数组里要用 , 或 ] @$i")
            }
        }
    }

    private fun parseString(): String {
        if (peek() != '"') throw JsonError("这里该有一个字符串 @$i")
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw JsonError("字符串没有收尾")
            val ch = src[i++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= src.length) throw JsonError("转义符没有内容")
                    when (val esc = src[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > src.length) throw JsonError("\\u 后面不足 4 位")
                            val code = src.substring(i, i + 4).toIntOrNull(16)
                                ?: throw JsonError("\\u 后面不是十六进制")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw JsonError("不认识的转义 \\$esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = i
        if (peek() == '-') i++
        var integral = true
        while (i < src.length) {
            val c = src[i]
            if (c.isDigit()) {
                i++
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+') {
                integral = false
                i++
            } else if (c == '-' && i > start) {
                // 指数里的符号（`1e-7`）；开头的负号上面已经吃过
                i++
            } else {
                break
            }
        }
        val raw = src.substring(start, i)
        // 整数走 Long：照片 id 与毫秒时间戳都要**逐位精确**，走 Double 会在 2^53 以上丢位。
        if (integral) raw.toLongOrNull()?.let { return it }
        return raw.toDoubleOrNull() ?: throw JsonError("数字读不出来：'$raw'")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!src.startsWith(word, i)) throw JsonError("这里该是 $word @$i")
        i += word.length
        return value
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun peek(): Char = if (i < src.length) src[i] else '\u0000'
}

// 列不存在（`getColumnIndex` 给 -1）就退回默认值，而不是把整趟索引带崩。
// 与 `MediaRepository` 末尾那三个是同一套口径，但那是 file-private，不能跨文件用。
private fun Cursor.longOr(col: Int, def: Long = 0L): Long = if (col < 0) def else getLong(col)
private fun Cursor.intOr(col: Int, def: Int = 0): Int = if (col < 0) def else getInt(col)
private fun Cursor.strOr(col: Int, def: String): String = if (col < 0) def else (getString(col) ?: def)

/** EXIF 摘要。读不到任何一项就是 [EMPTY]，此时那张照片的 EXIF 字段全空。 */
private class ExifBits(
    val make: String = "",
    val model: String = "",
    val lens: String = "",
    val iso: Int = 0,
    val aperture: String = "",
    val shutter: String = "",
    val focal: Int = 0,
    val hasGps: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
) {
    companion object {
        val EMPTY = ExifBits()
    }
}
