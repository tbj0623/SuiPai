package com.suipai.app.data

import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 存储分析：把「手机里到底什么在占地方」变成一句能读懂的话。
 *
 * ## 为什么单独一个工具，而不是塞进设置里的「清除缓存」
 * 相册类 App 的"清理"绝大多数时候和缓存无关——是**照片本身**占的。
 * 用户真正想知道的是三件事：总共有多少、大的都是什么、哪些是可以放心删的（截图/加工件/重复）。
 * 前两件这里答，第三件由 [Duplicates] 与调用方接。
 *
 * ## 为什么分类靠尺寸而不是格式
 * `mime` 在 MediaStore 里几乎永远是 `image/jpeg`，对"该不该删"没有信息量。
 * 真正有信息量的是**像素尺寸 + 字节数**：一张 4000×3000 却只有 200KB 的，是聊天软件压过的；
 * 一张 800×600 却 3MB 的，是截图被别的 App 二次保存过。所以 [SizeClass] 由两者共同决定。
 *
 * ## 纯逻辑 / Android 侧的分工
 * 与 [Duplicates] 同一套纪律：分档、分桶、TopN、可省空间全是纯函数（JVM 可测），
 * 只有扫 MediaStore 那一步碰 Context。
 */

// ─────────────────────────────────────────────────────────────────────
// 纯逻辑
// ─────────────────────────────────────────────────────────────────────

/** 尺寸档。阈值是**像素总数**，不是字节——分档要反映"这张图本身有多大"，不是"压缩得多狠"。 */
enum class SizeClass(val label: String, val minPixels: Long) {
    TINY("极小", 0L),
    SMALL("小", 300_000L), // ~640×480
    NORMAL("常规", 1_200_000L), // ~1280×960
    MEDIUM("中", 3_000_000L), // ~2000×1500
    LARGE("大", 8_000_000L), // ~3264×2448
    HUGE("超大", 20_000_000L), // ~5200×3800
}

/** 一张图的存储记录（扫 MediaStore 得到的，或单测直接构造）。 */
@Immutable
data class StorageItem(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** `MediaStore` 的 `RELATIVE_PATH`，可能为空（老设备/外部写入的）。 */
    val relativePath: String = "",
    val takenAtMillis: Long = 0L,
) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * 按像素总数分档。
 *
 * 边界用 `>=` 而不是 `>`：一张正好 300000 像素的图落进 SMALL，
 * 这样"每档的下界"就是它的 [SizeClass.minPixels]，单测可以逐档枚举断言，
 * 不会出现"某个值既不属于上一档也不属于下一档"的空洞。
 */
fun classify(
    sizeBytes: Long,
    width: Int,
    height: Int,
    mime: String? = null,
): SizeClass {
    // mime 目前不参与分档（见文件头），保留参数是为了将来"视频/RAW 单独计"时不必改签名。
    @Suppress("UNUSED_VARIABLE")
    val unused = mime
    val px = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    if (px <= 0L) {
        // 尺寸未知（外部写入、尚未解析）：退回按字节判断，别把它塞进"极小"里糊弄过去。
        return when {
            sizeBytes >= 12_000_000L -> SizeClass.HUGE
            sizeBytes >= 5_000_000L -> SizeClass.LARGE
            sizeBytes >= 2_000_000L -> SizeClass.MEDIUM
            sizeBytes >= 600_000L -> SizeClass.NORMAL
            sizeBytes >= 120_000L -> SizeClass.SMALL
            else -> SizeClass.TINY
        }
    }
    var result = SizeClass.TINY
    for (c in SizeClass.entries) {
        if (px >= c.minPixels) result = c
    }
    return result
}

/** 直方图的一个桶。 */
@Immutable
data class Bucket(
    /** 桶的下界（含），单位字节。 */
    val minBytes: Long,
    /** 桶的上界（不含）；最后一个桶是 [Long.MAX_VALUE]。 */
    val maxBytesExclusive: Long,
    val count: Int,
    val totalBytes: Long,
) {
    /** 「0–2 MB」这种给人看的标签。**必须带单位**，否则「0–0.5」看不出是 MB 还是 KB。 */
    val label: String
        get() {
            val lo = shortBytes(minBytes)
            return if (maxBytesExclusive == Long.MAX_VALUE) "$lo 以上" else "$lo–${shortBytes(maxBytesExclusive)}"
        }
}

/**
 * 桶边界用的短格式：`0` / `512 KB` / `2 MB` / `20 MB`。
 *
 * 与 [humanBytes] 的区别：这里**不保留小数**（桶边界都是整 MB），
 * 且 0 单独给 `"0"` 而不是 `"0 B"`（区间写「0–512 KB」比「0 B–512 KB」干净）。
 */
private fun shortBytes(bytes: Long): String {
    if (bytes <= 0L) return "0"
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> {
            val v = bytes / mb
            if (v % 1.0 == 0.0) "${v.toInt()} MB" else "${kotlin.math.round(v * 10.0) / 10.0} MB"
        }
        bytes >= kb -> {
            val v = bytes / kb
            if (v % 1.0 == 0.0) "${v.toInt()} KB" else "${kotlin.math.round(v * 10.0) / 10.0} KB"
        }
        else -> "$bytes B"
    }
}

/**
 * 按文件大小分桶（MB 为界）。
 *
 * 固定 6 档而不是"自适应等分"：用户看一眼就知道"2 MB 以下的那一堆是我的截图"，
 * 自适应分桶每次刷新边界都在变，反而没法形成直觉。
 */
fun histogram(items: List<StorageItem>): List<Bucket> {
    val edges = longArrayOf(
        0L,
        512L * 1024, // 512 KB
        2L * 1024 * 1024, // 2 MB
        5L * 1024 * 1024, // 5 MB
        10L * 1024 * 1024, // 10 MB
        20L * 1024 * 1024, // 20 MB
    )
    val counts = IntArray(edges.size)
    val totals = LongArray(edges.size)
    for (item in items) {
        var idx = 0
        for (i in edges.indices) {
            if (item.sizeBytes >= edges[i]) idx = i
        }
        counts[idx]++
        totals[idx] += item.sizeBytes.coerceAtLeast(0L)
    }
    return edges.indices.map { i ->
        Bucket(
            minBytes = edges[i],
            maxBytesExclusive = if (i + 1 < edges.size) edges[i + 1] else Long.MAX_VALUE,
            count = counts[i],
            totalBytes = totals[i],
        )
    }
}

/** 最大的 N 个文件（按字节降序）。`n <= 0` 返回空表。 */
fun topN(items: List<StorageItem>, n: Int): List<StorageItem> {
    if (n <= 0) return emptyList()
    return items.sortedByDescending { it.sizeBytes }.take(n)
}

/** 一张图属于哪一类。用于"按分类清理"的入口分组。 */
enum class StorageKind(val label: String) {
    SCREENSHOT("截图"),
    SUIPAI_DERIVATIVE("随拍加工件"),
    SUIPAI_ORIGINAL("随拍原片"),
    DOWNLOAD("下载"),
    CAMERA("相机"),
    OTHER("其他"),
}

/**
 * 分类判据。
 *
 * 「随拍系列」的口径与 `SearchIndex.photoKindOf` **一致但不 import 它**（避免数据层互相耦合）：
 * `SuiPai_` 前缀，且**数下划线**——原片只有一个（`SuiPai_<时间戳>.jpg`），
 * 任何加工件（一瞬/副本/灰度/2K/裁切/圆角…）都有两个以上。
 * 这条判据的好处是：以后新增任何加工动作都自动落进加工件，不用改这里。
 */
fun storageKindOf(displayName: String, relativePath: String): StorageKind {
    val name = displayName
    val path = relativePath

    val screenshot = path.contains("Screenshot", ignoreCase = true) ||
        path.contains("截屏") ||
        path.contains("截图") ||
        name.startsWith("Screenshot", ignoreCase = true) ||
        name.contains("screenshot", ignoreCase = true)
    if (screenshot) return StorageKind.SCREENSHOT

    if (name.startsWith(SUIPAI_PREFIX)) {
        val rest = name.substring(SUIPAI_PREFIX.length)
        return if (rest.contains("_")) StorageKind.SUIPAI_DERIVATIVE else StorageKind.SUIPAI_ORIGINAL
    }

    if (path.contains("download", ignoreCase = true) || path.contains("下载")) {
        return StorageKind.DOWNLOAD
    }
    if (path.contains("DCIM") || path.contains("Camera", ignoreCase = true)) {
        return StorageKind.CAMERA
    }
    return StorageKind.OTHER
}

internal const val SUIPAI_PREFIX = "SuiPai_"

/** 一类可省空间的估算结果。 */
@Immutable
data class ReclaimSuggestion(
    val kind: StorageKind,
    val count: Int,
    val totalBytes: Long,
    /** 一句话说明"删了会怎样"，必须诚实：截图删了就没了，加工件删了能从原片再出一遍。 */
    val note: String,
)

/**
 * 估算「按分类清理能省多少」。
 *
 * **只估算，不决定**：真正删不删由用户在界面上确认，且走系统的删除请求。
 * 这里唯一"敢建议"的是随拍加工件——它们能从原片重新生成，删掉不丢东西；
 * 截图和相机原片只报数字、不给"推荐清理"的语气。
 */
fun reclaimable(items: List<StorageItem>): List<ReclaimSuggestion> {
    val grouped = items.groupBy { storageKindOf(it.displayName, it.relativePath) }
    return grouped.map { (kind, list) ->
        ReclaimSuggestion(
            kind = kind,
            count = list.size,
            totalBytes = list.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            note = noteFor(kind),
        )
    }.sortedByDescending { it.totalBytes }
}

private fun noteFor(kind: StorageKind): String = when (kind) {
    StorageKind.SUIPAI_DERIVATIVE -> "这些是从原片加工出来的，删了可以用一瞬重新生成"
    StorageKind.SCREENSHOT -> "截图删了就没了，建议先看一眼"
    StorageKind.SUIPAI_ORIGINAL -> "随拍自己拍的原片，删了无法恢复"
    StorageKind.DOWNLOAD -> "下载目录里的图，删了要重新下载"
    StorageKind.CAMERA -> "相机拍的原图，删了无法恢复"
    StorageKind.OTHER -> "其他来源的照片"
}

/** 一次扫描的汇总。 */
@Immutable
data class StorageSummary(
    val count: Int,
    val totalBytes: Long,
    val byClass: List<Pair<SizeClass, Int>>,
    val buckets: List<Bucket>,
    val largest: List<StorageItem>,
    val suggestions: List<ReclaimSuggestion>,
    /**
     * 按 [StorageKind] 汇总的张数，与 [byClass]（按 [SizeClass]）**并列且互相独立**。
     *
     * 为什么必须有这一格：`StorageInsightScreen` 的 `onOpenCategory` 收的是
     * [StorageKind]（"点这一档看有哪些照片"），而 [byClass] 按尺寸档分
     * （[SizeClass]）。两者是不同的切法 —— 同一张 8MP 截图既落在某个 [SizeClass]，
     * 又落在 [StorageKind.SCREENSHOT]。缺这一格时宿主只能拿 [byClass] 去对
     * [StorageKind]，那个比较恒 false（编译器直接报 "Incompatible enums"），
     * 界面上表现为"点了没反应"。
     *
     * 两个列表都是枚举全量，所以
     * `byClass.sumOf { it.second } == byKind.sumOf { it.second } == count`
     * 恒成立。单测钉住这一条，防止以后加第三个维度时某一边漏算。
     */
    val byKind: List<Pair<StorageKind, Int>> = emptyList(),
) {
    companion object {
        val EMPTY = StorageSummary(
            count = 0,
            totalBytes = 0L,
            byClass = emptyList(),
            buckets = emptyList(),
            largest = emptyList(),
            suggestions = emptyList(),
            byKind = emptyList(),
        )
    }
}

/** 纯函数汇总（单测入口）。 */
fun summarize(items: List<StorageItem>, topCount: Int = 20): StorageSummary {
    if (items.isEmpty()) return StorageSummary.EMPTY
    return StorageSummary(
        count = items.size,
        totalBytes = items.sumOf { it.sizeBytes.coerceAtLeast(0L) },
        byClass = SizeClass.entries.map { c -> c to items.count { classify(it.sizeBytes, it.width, it.height) == c } },
        buckets = histogram(items),
        largest = topN(items, topCount),
        suggestions = reclaimable(items),
        // 与 `reclaimable()` 用的是**同一个判据函数** `storageKindOf`（不另写一份）——
        // 两处判据漂移是本仓库反复栽过的坑；这里直接复用，单测再钉住两边之和相等。
        byKind = StorageKind.entries.map { k ->
            k to items.count { storageKindOf(it.displayName, it.relativePath) == k }
        },
    )
}

/** 人话字节数：`38.2 GB` / `912 MB` / `12 KB`。 */
fun humanBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        b >= gb -> format1(b / gb) + " GB"
        b >= mb -> format1(b / mb) + " MB"
        b >= kb -> format1(b / kb) + " KB"
        else -> "$b B"
    }
}

private fun format1(v: Double): String {
    val r = kotlin.math.round(v * 10.0) / 10.0
    return if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
}

// ─────────────────────────────────────────────────────────────────────
// Android 侧
// ─────────────────────────────────────────────────────────────────────

/**
 * 扫 MediaStore 出一份存储报告。
 *
 * 只读游标、**不解码任何图**——所以 5000 张通常 100–300ms 就扫完（实测见 docs/product/TOOLS.md）。
 */
class StorageInsight(private val context: Context) {

    companion object {
        private const val PAGE = 1000
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
    }

    /** 扫描并汇总。游标读不到时返回 [StorageSummary.EMPTY]（界面应该说"没扫到"，不要假装是 0 张）。 */
    suspend fun scan(topCount: Int = 20): StorageSummary = withContext(Dispatchers.IO) {
        val items = readAll()
        if (items.isEmpty()) return@withContext StorageSummary.EMPTY
        summarize(items, topCount)
    }

    /** 只读全部行（供需要自己做的调用方复用）。 */
    suspend fun readAll(): List<StorageItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<StorageItem>(1024)
        var offset = 0
        while (true) {
            val page = runCatching { readPage(offset) }.getOrNull() ?: break
            if (page.isEmpty()) break
            out += page
            if (page.size < PAGE) break
            offset += PAGE
        }
        out
    }

    private fun readPage(offset: Int): List<StorageItem> {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            null,
            null,
            "${MediaStore.Images.Media._ID} ASC LIMIT $PAGE OFFSET $offset",
        ) ?: return emptyList()
        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val list = ArrayList<StorageItem>(PAGE)
            while (c.moveToNext()) {
                val taken = c.getLong(takenCol).takeIf { it > 0L } ?: (c.getLong(addedCol) * 1000L)
                list += StorageItem(
                    id = c.getLong(idCol),
                    displayName = c.getString(nameCol) ?: "",
                    sizeBytes = c.getLong(sizeCol).coerceAtLeast(0L),
                    width = c.getInt(wCol).coerceAtLeast(0),
                    height = c.getInt(hCol).coerceAtLeast(0),
                    relativePath = runCatching { c.getString(pathCol) }.getOrNull() ?: "",
                    takenAtMillis = taken,
                )
            }
            list
        }
    }
}
