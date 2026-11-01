package com.suipai.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 最近搜索词：**最多 10 条、去重、按时间倒序**，落 `filesDir/search_history.json`。
 *
 * 为什么不用 `SharedPreferences`：三张老的偏好表（`CameraFlag` / `YishunFlag` / `GalleryFlag`）
 * 加上设置模块的 `cfg_*` 全住同一个 `suipai.xml`，而 `FlagTableGuardTest.prefKeysAreGloballyUnique`
 * 钉着"落盘键名全局唯一"。搜索历史是个**纯本地、纯丢弃也无所谓**的缓存，
 * 为它在那个命名空间里抢一个键名，收益为零、撞名风险不为零 —— 所以走独立文件。
 *
 * 为什么落 JSON 而不是 prefs 的一个 `StringSet`：`StringSet` **不保序**，
 * 而"最近 10 条、按时间倒序"这个需求里顺序就是全部内容。
 *
 * 这个类不持有 Android Context 以外的任何状态，也没有后台写者：
 * 搜索历史丢一次最多让用户多点一次建议，不值得为它引入 `IdLists` 那种进程级写者。
 * 写盘走 [save]（IO 线程），调用点用 `rememberCoroutineScope()` 即可。
 */
class SearchHistory internal constructor(
    /** `null` = 纯内存模式（JVM 单测）。 */
    private val file: File?,
) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    // JVM 单测的入口就是上面那一个（`internal constructor(file: File?)`）：给一个临时文件，
    // 或者 `null` 只要内存行为。不另开 `constructor(file: File)` —— 它与 `File?` 的 JVM 签名相同，
    // 两个一起写会 platform declaration clash。

    private val lock = Any()
    private val items = ArrayList<String>()

    /** 现在有几条。 */
    fun size(): Int = synchronized(lock) { items.size }

    /** 当前清单的**快照**（调用点拿它当 Compose 的键，所以必须是新表）。 */
    fun recent(): List<String> = synchronized(lock) { ArrayList(items) }

    /**
     * 从磁盘读回来。返回装进来几条。
     *
     * 文件坏了就当空的（并且删掉它）：搜索历史是**可重建的**，为一份坏 JSON
     * 在每次冷启动时反复解析没有意义。
     */
    fun load(): Int {
        val f = file ?: return 0
        if (!f.isFile) return 0
        val text = try {
            f.readText()
        } catch (e: Exception) {
            Log.w(TAG, "搜索历史读不动，按空的处理", e)
            return 0
        }
        val parsed = SearchJson.decodeStrings(text)
        if (parsed == null) {
            Log.w(TAG, "搜索历史文件坏了（${text.length} 字符），删掉重来")
            try {
                f.delete()
            } catch (e: Exception) {
                Log.w(TAG, "坏的历史文件删不掉", e)
            }
            return 0
        }
        val cleaned = normalize(parsed)
        synchronized(lock) {
            items.clear()
            items.addAll(cleaned)
        }
        return items.size
    }

    /** 落盘。没有历史时把文件删掉（不留一个 `{"items":[]}` 的壳）。 */
    suspend fun save(): Boolean = withContext(Dispatchers.IO) { writeNow() }

    internal fun writeNow(): Boolean {
        val f = file ?: return false
        val snapshot = synchronized(lock) { ArrayList(items) }
        if (snapshot.isEmpty()) {
            return try {
                if (f.exists() && !f.delete()) {
                    Log.w(TAG, "空历史文件删不掉：${f.name}")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "空历史文件删不掉", e)
                false
            }
        }
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SearchJson.encodeStrings(snapshot))
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(SearchJson.encodeStrings(snapshot))
                    tmp.delete()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "搜索历史没写进磁盘", e)
            false
        }
    }

    /**
     * 记一条。返回记完之后的整份清单（调用点直接往下传，不要回磁盘再取一次）。
     *
     * 语义：
     *  - 首尾空白去掉、全是空白的不记；
     *  - **去重不区分大小写**（搜「Shanghai」与「shanghai」是同一次搜索），
     *    但保留**最新一次**的写法（用户刚敲的那个大小写才是他想看到的）；
     *  - 已经在表里的话**移到最前**，不是原地不动 —— 否则重搜同一个词看不出任何反馈；
     *  - 超过 [MAX] 条挤掉最旧的那条。
     */
    fun record(raw: String): List<String> = synchronized(lock) {
        val term = raw.trim()
        if (term.isNotEmpty()) {
            val key = term.lowercase(Locale.ROOT)
            val existing = items.indexOfFirst { it.lowercase(Locale.ROOT) == key }
            // 先摘掉旧的那一条（不论大小写），再把**这一次的写法**插到最前。
            if (existing >= 0) items.removeAt(existing)
            items.add(0, term)
            while (items.size > MAX) items.removeAt(items.size - 1)
        }
        ArrayList(items)
    }

    /** 清空。列表**就地**清（不换实例），调用点拿到的快照仍然安全。 */
    fun clear(): List<String> = synchronized(lock) {
        items.clear()
        ArrayList(items)
    }

    /** 删一条（下拉历史里划掉某一个词）。 */
    fun remove(raw: String): List<String> = synchronized(lock) {
        val key = raw.trim().lowercase(Locale.ROOT)
        items.removeAll { it.lowercase(Locale.ROOT) == key }
        ArrayList(items)
    }

    /**
     * 一条都不记的**判据**（不是 `record` 的内部实现）：给测试与调用点共用。
     *
     * 单独抽出来是因为"什么叫空"在这块地方有三种写法都说得通
     * （`isEmpty` / `isBlank` / 只认 ASCII 空格），而它们的行为不一样 ——
     * 搜「　」（全角空格）不该变成一条历史，它只是手滑。
     */
    fun isRecordable(raw: String): Boolean = raw.trim().isNotEmpty()

    companion object {
        private const val TAG = "SearchHistory"

        /** 落盘文件名。 */
        const val FILE_NAME = "search_history.json"

        /** 上限：10 条。再多就把"最近"变成"翻旧账"，而这块地方在首屏只有一行。 */
        const val MAX = 10

        /**
         * 载入时的清洗：trim → 去空 → 去重（不区分大小写，保留先出现的）→ 截到上限。
         *
         * 为什么要清洗而不是直接信文件：这个文件在开发期被手改过、也被旧版本以别的顺序写过。
         * 一个 200 条的清单会让历史区把整屏吃光，而"最多 10 条"是这一块唯一的硬约束。
         */
        internal fun normalize(raw: List<String>): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>(MAX)
            for (s in raw) {
                val t = s.trim()
                if (t.isEmpty()) continue
                val key = t.lowercase(Locale.ROOT)
                if (!seen.add(key)) continue
                out += t
                if (out.size >= MAX) break
            }
            return out
        }
    }
}
