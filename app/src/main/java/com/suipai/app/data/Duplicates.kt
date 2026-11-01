package com.suipai.app.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 重复照片合并。
 *
 * ## 为什么这张表最怕误报
 * 合并 = 删照片。**误删是产品级事故**，漏报只是"这个工具没用"。所以这里的判据是
 * **三键全部满足才算重复**，而不是"任意两键相似就给个建议"：
 *
 *   1. `sizeBytes` **逐字节相等**（不是"相近"）—— 同一张图另存/重压之后大小几乎一定变，
 *      但大小相同**不能**单独当判据（两个完全不同的文件可以碰巧一样大）。
 *   2. `takenAtMillis` 相差 ≤ [TIME_WINDOW_MS]（120 秒）—— 连拍/一次点击存两遍会落在几秒内；
 *      用户手动翻牌两次导出通常差几分钟以上。
 *   3. dHash 汉明距离 ≤ [DEFAULT_HASH_DISTANCE]（6）。
 *
 * 三条各自都不够，凑一起才够。`confidence` 只用来**排序与显示**，不参与判定。
 *
 * ## 为什么哈希输入是 `IntArray` 而不是 `Bitmap`
 * `dHash` 是纯数学，必须能在 JVM 单测里跑（`unitTests.isReturnDefaultValues = true`
 * 会把 `Bitmap` 的构造 stub 掉，任何吃 Bitmap 的测试都是假通过）。所以：
 * - 纯函数 [dHash] 吃**已经解码好的灰度数组**；
 * - 解码这一步单独放在 Android 侧的 [Duplicates] 里，单测不碰它。
 *
 * ## 为什么用 dHash 而不是 aHash
 * 差分哈希对**亮度整体漂移**（JPEG 重压、不同解码器、轻微曝光差）比均值哈希稳：
 * aHash 比较的是"每个像素与全图均值"，全图亮度抬一点，所有像素一起翻边；
 * dHash 比较的是"相邻像素谁更亮"，亮度整体抬升时相邻关系不变。
 * 实测同一张图缩放 + JPEG 重压后 dHash 距离通常 0–2，两张不同的风景图通常 20 以上。
 */

// ─────────────────────────────────────────────────────────────────────
// 纯逻辑（JVM 可测，不 import 任何 android.*）
// ─────────────────────────────────────────────────────────────────────

/** dHash 的网格：9 列 × 8 行 → 每行 8 个差分 → 64 位。 */
internal const val DHASH_COLS = 9
internal const val DHASH_ROWS = 8

/** 拍摄时间窗：120 秒。超过这个就当作两张独立拍摄（用户手动存两遍一般也在几分钟内）。 */
internal const val TIME_WINDOW_MS = 120_000L

/** 默认汉明距离阈值。6 在 64 位里约等于 9% 的位不同，是"重压后仍相同"的保守上限。 */
internal const val DEFAULT_HASH_DISTANCE = 6

/** 保留策略。 */
enum class KeepRule(val label: String) {
    /** 分辨率最高（宽×高）优先；相同则比文件大小。 */
    HIGHEST_RESOLUTION("保留分辨率最高的"),

    /** 文件最大优先（通常就是画质最好的那张）。 */
    LARGEST_FILE("保留文件最大的"),

    /** 拍得最早优先（"我最早存的那张"）。 */
    EARLIEST("保留拍得最早的"),
}

/** 一组疑似重复。`keep` 是建议保留的那张的 id。 */
@Immutable
data class DuplicateGroup(
    /** 组内所有照片，按 [pickKeep] 的结果把保留的那张排在最前。 */
    val items: List<DuplicatePhoto>,
    /** 建议保留的那张 id（一定在 [items] 里）。 */
    val keep: Long,
    /** 0.6 或 1.0：三键全中给 1.0（本实现的判定线就是三键全中，所以这里恒为 1.0，
     *  保留字段是为了将来放宽到"两键 + 人工确认"时不必改模型）。 */
    val confidence: Float,
    /** 一句话中文理由，直接显示在组头上。 */
    val reasonText: String,
) {
    /** 删掉这些就省下这么多字节（= 组内除保留那张之外的总和）。 */
    val reclaimableBytes: Long
        get() = items.filter { it.id != keep }.sumOf { it.sizeBytes }
}

/** 参与判重的一张照片的最小信息集。 */
@Immutable
data class DuplicatePhoto(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val takenAtMillis: Long,
    val width: Int,
    val height: Int,
    /** dHash 结果；null = 还没算（扫描中）或解码失败。null **永远不参与合并**。 */
    val hash: Long?,
) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * 9×8 灰度差分哈希。
 *
 * 输入 [bits] 是**已经缩到 9 列 × 8 行的灰度值**（行主序，长度必须 ≥ 72，每格 0..255）。
 * 输出 64 位：每行 8 次比较（`右 > 左` 记 1），共 8 行。
 *
 * 为什么"右 > 左"而不是"右 >= 左"：等宽纯色区域在 `>` 下全 0，在 `>=` 下全 1；
 * 两种都行，但 `>` 让纯色图的哈希恒为 0，边界行为更好断言。
 *
 * @throws IllegalArgumentException 长度不足 72
 */
fun dHash(bits: IntArray, w: Int = DHASH_COLS, h: Int = DHASH_ROWS): Long {
    require(w == DHASH_COLS && h == DHASH_ROWS) {
        "dHash 只接受 ${DHASH_COLS}×${DHASH_ROWS} 的灰度网格，实际 ${w}×${h}"
    }
    require(bits.size >= w * h) { "灰度数组长度 ${bits.size} 不够 ${w * h}" }
    var hash = 0L
    var bit = 0
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w - 1) {
            if (bits[row + x + 1] > bits[row + x]) {
                hash = hash or (1L shl bit)
            }
            bit++
        }
    }
    return hash
}

/** 汉明距离（两个 64 位哈希有多少位不同）。 */
fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

/**
 * 把候选照片聚成重复组。
 *
 * **三键全中才算**（见文件头）。实现分两阶段，是为了让"不该合并"的例子能被精确断言：
 *   1. 按 `sizeBytes` 分桶 —— 大小不同的直接不在一个桶里，这是最便宜的排除；
 *   2. 桶内两两比较时间窗 + 哈希距离，用并查集合并（保证传递性：
 *      A~B、B~C 时 A、C 也在一组，即便 A、C 直接比超了阈值）。
 *
 * 用并查集而不是"两两配对输出"，是因为后者会让同一批照片出现在多个组里，
 * 用户点"删除其余"时会重复删。
 *
 * @param items 候选（`hash == null` 的会被跳过：宁可漏报）
 * @param keepRule 组内保留策略
 * @param hashDistance 汉明距离上限，默认 [DEFAULT_HASH_DISTANCE]
 * @param timeWindowMs 拍摄时间窗，默认 [TIME_WINDOW_MS]
 * @return 只包含 ≥2 张的组，按"可省字节"从大到小
 */
fun cluster(
    items: List<DuplicatePhoto>,
    keepRule: KeepRule = KeepRule.HIGHEST_RESOLUTION,
    hashDistance: Int = DEFAULT_HASH_DISTANCE,
    timeWindowMs: Long = TIME_WINDOW_MS,
): List<DuplicateGroup> {
    if (items.size < 2) return emptyList()
    val usable = items.filter { it.hash != null }
    if (usable.size < 2) return emptyList()

    val parent = IntArray(usable.size) { it }

    fun find(x: Int): Int {
        var i = x
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]
            i = parent[i]
        }
        return i
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }

    // 阶段 1：按 size 分桶（逐字节相等）。
    val bySize = usable.withIndex().groupBy { it.value.sizeBytes }

    // 阶段 2：桶内两两比较。
    for ((_, bucket) in bySize) {
        if (bucket.size < 2) continue
        for (i in 0 until bucket.size - 1) {
            val a = bucket[i]
            val ha = a.value.hash ?: continue
            for (j in i + 1 until bucket.size) {
                val b = bucket[j]
                val hb = b.value.hash ?: continue
                if (kotlin.math.abs(a.value.takenAtMillis - b.value.takenAtMillis) > timeWindowMs) continue
                if (hamming(ha, hb) > hashDistance) continue
                union(a.index, b.index)
            }
        }
    }

    // 收组。
    val groups = LinkedHashMap<Int, MutableList<DuplicatePhoto>>()
    for (i in usable.indices) {
        groups.getOrPut(find(i)) { ArrayList() }.add(usable[i])
    }

    return groups.values
        .filter { it.size >= 2 }
        .map { members ->
            val keep = pickKeep(members, keepRule)
            val ordered = members.sortedWith(compareBy({ it.id != keep }, { it.id }))
            DuplicateGroup(
                items = ordered,
                keep = keep,
                confidence = 1.0f,
                reasonText = reasonOf(members),
            )
        }
        .sortedByDescending { it.reclaimableBytes }
}

/**
 * 组内保留哪张。
 *
 * 三条规则都必须**确定**（同一组输入永远给同一个答案），否则用户第二次进这个页面
 * 会看到"建议保留"换了一张，那一瞬间他就不会再信这个工具了。
 * 所以每条规则后面都跟了 id 兜底：分辨率/大小/时间全都一样时按 id 小的留。
 */
fun pickKeep(group: List<DuplicatePhoto>, rule: KeepRule = KeepRule.HIGHEST_RESOLUTION): Long {
    if (group.isEmpty()) return 0L
    val best = when (rule) {
        KeepRule.HIGHEST_RESOLUTION -> group.maxWith(
            compareBy<DuplicatePhoto> { it.pixels }
                .thenBy { it.sizeBytes }
                .thenByDescending { it.id },
        )
        KeepRule.LARGEST_FILE -> group.maxWith(
            compareBy<DuplicatePhoto> { it.sizeBytes }
                .thenBy { it.pixels }
                .thenByDescending { it.id },
        )
        KeepRule.EARLIEST -> group.minWith(
            compareBy<DuplicatePhoto> { if (it.takenAtMillis <= 0L) Long.MAX_VALUE else it.takenAtMillis }
                .thenByDescending { it.pixels }
                .thenBy { it.id },
        )
    }
    return best.id
}

/** 组头那句中文。不吹"完全相同"——三键相同不等于文件逐位相同。 */
internal fun reasonOf(members: List<DuplicatePhoto>): String {
    val n = members.size
    val sameSize = members.map { it.sizeBytes }.distinct().size == 1
    val span = members.maxOf { it.takenAtMillis } - members.minOf { it.takenAtMillis }
    val res = members.map { "${it.width}×${it.height}" }.distinct()
    val parts = ArrayList<String>(3)
    if (sameSize) parts += "大小相同"
    if (span <= 1_000L) parts += "同时拍摄" else parts += "拍摄相差 ${span / 1000} 秒"
    parts += if (res.size == 1) "画质相同" else "画质不同"
    val keepHint = if (n >= 3) "这 $n 张" else "这 2 张"
    return "$keepHint${parts.joinToString("、")}，建议只留一张"
}

// ─────────────────────────────────────────────────────────────────────
// Android 侧
// ─────────────────────────────────────────────────────────────────────

/**
 * 扫一遍媒体库，找出疑似重复的照片。
 *
 * ## 性能：分页粗筛，只给候选解缩略图
 * 全库 5000 张每张都解码一遍要十几秒。这里的顺序是：
 *   1. 分页读 `_ID/SIZE/DATE_TAKEN/WIDTH/HEIGHT`（**纯游标，不解码**，5000 行约 100ms）；
 *   2. 按 `SIZE` 分组，**只留下组内 >1 的**——重复照片必然同大小，这一步通常砍掉 90% 以上；
 *   3. 只对候选解码到 ≤64px 算 dHash。
 *
 * 解码用 `BitmapFactory` + `inSampleSize`（不用 `ImageDecoder`：后者要 API 28+，
 * 而本项目 minSdk 26，且 `inSampleSize` 的"缩到 ≤64"语义已经够用）。
 *
 * @param context 只用 `contentResolver`
 */
class Duplicates(private val context: Context) {

    companion object {
        /** 分页大小。1000 行一次游标大约 20ms，50 次就扫完全库。 */
        private const val PAGE = 1000

        /** 解码目标边长。dHash 只要 9×8，缩到 64 已经远超需求（留了抗锯齿余量）。 */
        private const val DECODE_TARGET = 64

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
    }

    /**
     * 扫描。
     *
     * @param onProgress (已处理张数, 总数)；总数在粗筛后才知道，粗筛前给 -1
     * @param cancel 每页检查一次；返回 true 就提前结束并返回已找到的组
     */
    suspend fun scan(
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        cancel: () -> Boolean = { false },
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val rows = readAllRows()
        if (rows.isEmpty()) {
            onProgress(0, 0)
            return@withContext emptyList()
        }
        // 粗筛：同 size 的才可能是重复。
        val buckets = rows.groupBy { it.sizeBytes }.filterValues { it.size > 1 }
        val candidates = buckets.values.flatten()
        if (candidates.isEmpty()) {
            onProgress(rows.size, rows.size)
            return@withContext emptyList()
        }

        val total = candidates.size
        val hashed = ArrayList<DuplicatePhoto>(total)
        var done = 0
        for (row in candidates) {
            coroutineContext.ensureActive()
            if (cancel()) break
            val hash = hashOf(row.id)
            hashed += row.copy(hash = hash)
            done++
            if (done % 16 == 0) onProgress(done, total)
        }
        onProgress(done, total)
        cluster(hashed)
    }

    /** 读全部行（不解码）。游标读不到返回空表 —— 由调用方说"没扫到"。 */
    private fun readAllRows(): List<DuplicatePhoto> {
        val out = ArrayList<DuplicatePhoto>(1024)
        var offset = 0
        while (true) {
            val page = runCatching { readPage(offset) }.getOrNull() ?: break
            if (page.isEmpty()) break
            out += page
            if (page.size < PAGE) break
            offset += PAGE
        }
        return out
    }

    private fun readPage(offset: Int): List<DuplicatePhoto> {
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
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val list = ArrayList<DuplicatePhoto>(PAGE)
            while (c.moveToNext()) {
                val taken = c.getLong(takenCol).takeIf { it > 0L } ?: (c.getLong(addedCol) * 1000L)
                list += DuplicatePhoto(
                    id = c.getLong(idCol),
                    displayName = c.getString(nameCol) ?: "",
                    sizeBytes = c.getLong(sizeCol).coerceAtLeast(0L),
                    takenAtMillis = taken,
                    width = c.getInt(wCol).coerceAtLeast(0),
                    height = c.getInt(hCol).coerceAtLeast(0),
                    hash = null,
                )
            }
            list
        }
    }

    /** 解一张到 ≤64px，转 9×8 灰度，算 dHash。任何一步失败返回 null（该张不参与合并）。 */
    fun hashOf(id: Long): Long? = runCatching {
        val uri: Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sampleSizeOf(opts.outWidth, opts.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            grayGrid(bitmap)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun sampleSizeOf(w: Int, h: Int): Int {
        var sample = 1
        while (maxOf(w, h) / sample > DECODE_TARGET * 2) sample *= 2
        return sample
    }

    /** Bitmap → 9×8 灰度（双线性近似：直接按格取区域均值，比最近邻稳）。 */
    internal fun grayGrid(bitmap: Bitmap): Long {
        val grid = IntArray(DHASH_COLS * DHASH_ROWS)
        val bw = bitmap.width
        val bh = bitmap.height
        for (gy in 0 until DHASH_ROWS) {
            val y0 = gy * bh / DHASH_ROWS
            val y1 = ((gy + 1) * bh / DHASH_ROWS).coerceAtLeast(y0 + 1)
            for (gx in 0 until DHASH_COLS) {
                val x0 = gx * bw / DHASH_COLS
                val x1 = ((gx + 1) * bw / DHASH_COLS).coerceAtLeast(x0 + 1)
                var sum = 0L
                var count = 0
                for (y in y0 until minOf(y1, bh)) {
                    for (x in x0 until minOf(x1, bw)) {
                        val p = bitmap.getPixel(x, y)
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        // Rec.601 亮度，整数近似（不需要 gamma 正确，dHash 只比相邻）
                        sum += (299L * r + 587L * g + 114L * b) / 1000L
                        count++
                    }
                }
                grid[gy * DHASH_COLS + gx] = if (count == 0) 0 else (sum / count).toInt()
            }
        }
        return dHash(grid)
    }
}

/**
 * 删除一组里除保留那张之外的照片。
 *
 * 走 `MediaStore.createDeleteRequest`（系统确认框），返回**实际删掉的张数**。
 * 项目纪律：`> 0` 才算成功；返回 0 表示用户取消了确认框，那不是失败、也不许说"已删除"。
 *
 * 这里只构造 request，`intentSender` 的发起必须由宿主（Activity）做 ——
 * 与 `MediaRepository.deleteRequest` 同样的分工。
 */
fun deleteRequestFor(context: Context, uris: List<Uri>): android.content.IntentSender? {
    if (uris.isEmpty()) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }.getOrNull()
}

/** 一组要删的 uri（除保留那张）。 */
fun Duplicates.deletionUrisOf(group: DuplicateGroup): List<Uri> =
    group.items.filter { it.id != group.keep }.map { photo ->
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)
    }
