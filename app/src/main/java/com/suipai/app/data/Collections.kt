package com.suipai.app.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 一个收藏夹（用户自己攒的一组照片）。
 *
 * 只有 id，没有 `Uri`：封面由调用方按第一张去 [MediaRepository] 取
 * （`CollectionsScreen` 收的是 `covers: Map<String, String?>`）。
 * 数据层持有 Android 类型会让整层没法在 JVM 上测。
 */
@Immutable
data class PhotoCollection(
    val id: String,
    val name: String,
    /** 保序。列表顺序就是用户在收藏夹里看到的顺序。 */
    val photoIds: List<Long>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 收藏夹。落 `filesDir/collections.json`，全进程一份（[of]）。
 *
 * ## 为什么不是 `Set<Long>`
 * 收藏夹的顺序是用户排过的（`replaceOrder` 就是"拖完之后存一下"），
 * 用 Set 会把这份顺序丢掉。去重用 [orderedAdd] 做，它保留第一次出现的位置。
 *
 * ## 为什么 `now` 是参数
 * `createdAt` / `updatedAt` 要出现在界面上（"3 天前整理"）也要参与排序。
 * 时钟写死在函数体里，测试就只能 `Thread.sleep`；注入之后边界（同一毫秒建两个、
 * 改名不改 `updatedAt`）都能钉。
 *
 * ## 上限
 * 200 个集合 / 每个 5000 张。不是为了省磁盘，是为了让 `list()` 这条
 * "每次进收藏页都要跑"的路径有一个可算的上界：5000 条 Long 的浅拷贝
 * 大约 40 KB，200 个就是 8 MB —— 再多就该分页了。
 */
class Collections(context: Context) {

    /**
     * 只留 `filesDir`，**不留 `Context` 本身**。
     *
     * 这个类被 [Companion.instance] 持有在全进程的静态字段里，字段里放 Context 就是
     * 一条真实的泄漏链（lint 的 `StaticFieldLeak` 报的就是它）。现在传进来的
     * 是 `applicationContext`，所以今天不泄漏；但那条规则挡的是"以后有人改成
     * Activity context"的那一天 —— 而这里压根用不到 Context，只用到它下面的一个目录。
     * 收窄成 File 之后，误用不可能发生，也不是靠"记得传 applicationContext"来保平安。
     */
    private val dir: File = context.filesDir

    private val file: File get() = File(dir, FILE_NAME)
    private val lock = Any()

    // ── 读 ────────────────────────────────────────────────────────────

    /** 按 `updatedAt` 从新到旧。坏文件 / 不存在都返回空表。 */
    fun list(): List<PhotoCollection> = readAll().sortedByDescending { it.updatedAt }

    fun byId(id: String): PhotoCollection? = readAll().firstOrNull { it.id == id }

    // ── 写 ────────────────────────────────────────────────────────────

    /**
     * 新建一个收藏夹。名称空白 → "未命名收藏"；id 由 [newId] 保证不撞。
     * 已到 [MAX_COLLECTIONS] 时**不新建**，返回 **null** —— 调用方要能说一句
     * 「收藏夹满了」，而不是看到一个静默的 `create` 返回了个假对象。
     */
    fun create(name: String, ids: List<Long>, now: Long = System.currentTimeMillis()): PhotoCollection? {
        val all = readAll()
        if (all.size >= MAX_COLLECTIONS) return null
        val created = PhotoCollection(
            id = newId(now, all.map { it.id }.toSet()),
            name = normalizeName(name),
            photoIds = orderedAdd(emptyList(), ids),
            createdAt = now,
            updatedAt = now,
        )
        return if (writeAll(all + created)) created else null
    }

    fun rename(id: String, name: String, now: Long = System.currentTimeMillis()): Boolean =
        update(id) { it.copy(name = normalizeName(name), updatedAt = now) }

    fun delete(id: String): Boolean {
        val all = readAll()
        val next = all.filterNot { it.id == id }
        if (next.size == all.size) return false
        return writeAll(next)
    }

    /** 追加照片。已在里面的跳过，且**保留原位置**（不把老照片挪到末尾）。 */
    fun addPhotos(id: String, ids: List<Long>, now: Long = System.currentTimeMillis()): Boolean =
        update(id) { it.copy(photoIds = orderedAdd(it.photoIds, ids).take(MAX_PHOTOS), updatedAt = now) }

    /** 移除照片，其余顺序不变。一个都没匹配上也回 `true`（"现在是这个样子"）。 */
    fun removePhotos(id: String, ids: List<Long>, now: Long = System.currentTimeMillis()): Boolean {
        val drop = ids.toHashSet()
        return update(id) { it.copy(photoIds = it.photoIds.filterNot(drop::contains), updatedAt = now) }
    }

    /** 整份替换顺序（拖拽之后）。去重后按传入顺序；不认识的 id 不会自己长回来。 */
    fun replaceOrder(id: String, ids: List<Long>, now: Long = System.currentTimeMillis()): Boolean =
        update(id) { it.copy(photoIds = orderedAdd(emptyList(), ids).take(MAX_PHOTOS), updatedAt = now) }

    private inline fun update(id: String, change: (PhotoCollection) -> PhotoCollection): Boolean {
        val all = readAll()
        val at = all.indexOfFirst { it.id == id }
        if (at < 0) return false
        val next = all.toMutableList().also { it[at] = change(it[at]) }
        return writeAll(next)
    }

    // ── 落盘 ──────────────────────────────────────────────────────────

    private fun readAll(): List<PhotoCollection> {
        val text = try {
            if (!file.isFile) return emptyList()
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "读 $FILE_NAME 失败，当成没有收藏夹", e)
            return emptyList()
        }
        return decode(text)
    }

    private fun writeAll(items: List<PhotoCollection>): Boolean = synchronized(lock) {
        try {
            file.writeText(encode(items))
            true
        } catch (e: Exception) {
            Log.w(TAG, "写 $FILE_NAME 失败", e)
            false
        }
    }

    companion object {
        const val FILE_NAME = "collections.json"

        /** 收藏夹个数上限。 */
        const val MAX_COLLECTIONS = 200

        /** 单个收藏夹里的照片上限。 */
        const val MAX_PHOTOS = 5000

        /** 名称空白时的回退值。 */
        const val FALLBACK_NAME = "未命名收藏"

        private const val TAG = "Collections"
        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_PHOTO_IDS = "photoIds"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_UPDATED_AT = "updatedAt"

        @Volatile
        private var instance: Collections? = null

        /**
         * 全进程一份。两个实例会各自持有"整份 json 的旧快照"，
         * 后写的那个把先写的那份覆盖掉 —— 与 `IdLists` 的病灶完全一样。
         */
        fun of(context: Context): Collections = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: Collections(app).also { instance = it }
        }

        /**
         * 名称规整：去首尾空白、压掉换行（名称是单行输入框来的，
         * 但 json 是手也能改的）、空白回退 [FALLBACK_NAME]。
         */
        internal fun normalizeName(name: String): String {
            val flat = name.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            return flat.ifEmpty { FALLBACK_NAME }
        }

        /**
         * 保序去重追加：`base` 原样在前，`extra` 里没出现过的按传入顺序接上。
         *
         * 这个函数是"去重且保序"这一条规则的**唯一**实现 —— `create` / `addPhotos` /
         * `replaceOrder` 三处都从它过来，所以三处的行为不可能分叉。
         */
        internal fun orderedAdd(base: List<Long>, extra: List<Long>): List<Long> {
            if (extra.isEmpty()) return base
            val seen = LinkedHashSet<Long>(base.size + extra.size)
            seen.addAll(base)
            seen.addAll(extra)
            return seen.toList()
        }

        /**
         * 新 id。时间戳的 36 进制 + 冲突序号：同一毫秒里连点两次「新建」
         * 不会撞（撞了的话第二次 `byId` 会拿到第一个）。
         */
        internal fun newId(now: Long, taken: Set<String>): String {
            val base = now.coerceAtLeast(0L).toString(36)
            if (base !in taken) return base
            var n = 2
            while ("$base-$n" in taken) n++
            return "$base-$n"
        }

        /**
         * 解 json。与 `Trash.decode` 同一套容错：整份不是数组、条目不是对象、
         * 缺 id / 缺名称都跳过那一条，不抛。`photoIds` 里的非数字项也跳过。
         *
         * 用 [TinyJson] 而不是 `org.json`：后者在 JVM 单测里是返回默认值的桩，
         * 会把"往返"与"坏样本"两条断言变成空跑（详见 `Trash.kt` 末尾那段说明）。
         */
        internal fun decode(json: String): List<PhotoCollection> {
            if (json.isBlank()) return emptyList()
            val root = try {
                TinyJson.parse(json)
            } catch (e: Exception) {
                return emptyList()
            }
            val array = TinyJson.arrayOf(root)
                ?: TinyJson.objectOf(root)?.let { TinyJson.arrayOf(it[KEY_ITEMS]) }
                ?: return emptyList()
            val out = ArrayList<PhotoCollection>(array.size)
            val usedIds = HashSet<String>(array.size)
            for (entry in array) {
                val obj = TinyJson.objectOf(entry) ?: continue
                val id = TinyJson.stringOf(obj[KEY_ID])?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                // 同一个 id 出现两次：留第一条。否则删除永远只删掉一条。
                if (!usedIds.add(id)) continue
                val created = TinyJson.longOf(obj[KEY_CREATED_AT]) ?: 0L
                out += PhotoCollection(
                    id = id,
                    name = normalizeName(TinyJson.stringOf(obj[KEY_NAME]).orEmpty()),
                    photoIds = decodeIds(obj[KEY_PHOTO_IDS]).take(MAX_PHOTOS),
                    createdAt = created,
                    updatedAt = TinyJson.longOf(obj[KEY_UPDATED_AT]) ?: created,
                )
            }
            return out
        }

        private fun decodeIds(value: Any?): List<Long> {
            val array = TinyJson.arrayOf(value) ?: return emptyList()
            val out = ArrayList<Long>(array.size)
            for (raw in array) {
                val id = TinyJson.longOf(raw) ?: continue
                if (id > 0L) out += id
            }
            // 文件里出现重复 id 时按"第一次出现"收，且顺序不变。
            return orderedAdd(emptyList(), out)
        }

        internal fun encode(items: List<PhotoCollection>): String = buildString {
            append("{\"").append(KEY_ITEMS).append("\":[")
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append('{')
                append("\"").append(KEY_ID).append("\":").append(TinyJson.quote(item.id))
                append(",\"").append(KEY_NAME).append("\":").append(TinyJson.quote(item.name))
                append(",\"").append(KEY_PHOTO_IDS).append("\":[")
                item.photoIds.forEachIndexed { i, id ->
                    if (i > 0) append(',')
                    append(id)
                }
                append("],\"").append(KEY_CREATED_AT).append("\":").append(item.createdAt)
                append(",\"").append(KEY_UPDATED_AT).append("\":").append(item.updatedAt)
                append('}')
            }
            append("]}")
        }
    }
}
