package com.suipai.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 收藏与隐藏两份 id 清单的唯一持有者。`Favorites` / `Hidden` 是它的两个门面。
 *
 * 为什么必须合成一个：两份清单存在**同一个** prefs 文件（`suipai.private.xml`）里，而 `commit()`
 * 写的是整个文件。两个类各自 commit 时，一次删除要连写两遍全文件，而且第二遍会按自己那份
 * 旧快照把第一遍刚写的内容覆盖回去 —— 跨对象的丢更新。现在一把锁管两份，落盘一次写两个键。
 *
 * 为什么这个文件不参与系统备份：`backup_rules.xml` 是**整文件**粒度，没法把两个键从
 * `suipai.xml` 里单独摘出去。而"收藏了哪些 / 藏起了哪些照片"本身就是隐私，
 * 于是清单单独住进 `suipai.private.xml`，两个 xml 都显式 exclude 它（见下面 `PRIVATE_FILE`）。
 *
 * 为什么内存里 String 与 Long 两份都留、且**就地增删**：`ids()` 原来每次点击都要把整表
 * String→Long 倒一遍（5000 条量级下一次点击要倒三四回：`remember(favTick)` 一回、`batch*`
 * 判断"是不是全都在里面"一回、`loadByIds` 的参数又一回）。改动其实只碰一个 id，
 * 所以两份集合都原地改，整表装箱那一类开销从点击路径上彻底消失；
 * 交给调用点的是一份浅拷贝快照 —— 调用点拿它当 Compose 的键，身份必须跟着改动换。
 *
 * 为什么不再有 `apply()`：内存真值在这里，prefs 只是持久化。主线程一次点击既不碰磁盘，
 * 也不会把一次全文件写挂到 `QueuedWork` 上等 Activity 暂停时去收。
 */
internal class IdLists internal constructor(
    /** 参与系统备份的那份设置（`suipai.xml`）。这里**只读不写**，除了清掉历史遗留的两个键。 */
    private val prefs: SharedPreferences,
    /**
     * 不参与备份的那份（`suipai.private.xml`）。两份清单只落在这里。
     *
     * 为什么单独一个文件：`backup_rules.xml` / `data_extraction_rules.xml` 是**整文件**粒度，
     * 没法把 `suipai.xml` 里的两个键单独摘出去。而"我收藏了哪些、我藏起了哪些"是要随云备份
     * 出设备的隐私信息（照片本身不跟着走，清单会）。所以清单搬家，参数留在原处继续备份。
     */
    private val privatePrefs: SharedPreferences,
    /** 改动是否当场自己排写者。生产路径恒为 true；JVM 守卫要确定性，关掉它自己调 persistNow()。 */
    private val autoWriter: Boolean = true,
) {

    private val lock = Any()
    private val writeLock = Any()

    /** 进程级写者：不属于任何一次组合，所以组合销毁取消不掉它。 */
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: Deferred<Boolean>? = null

    private val fav = HashSet<String>()
    private val hide = HashSet<String>()
    private val favIds = HashSet<Long>()
    private val hideIds = HashSet<Long>()
    private var version = 0L
    private var persistedVersion = -1L
    /**
     * 老文件（参与备份的那份）里还留着清单键 = 还没搬完家。
     * 只在 `writeLock` 下改，读它也只可能在落盘路径上。
     */
    private var legacyPending = false

    init {
        // 新文件里**有这两个键**就以它为准（哪怕值是空集 —— 空集是"用户把它们都取消了"，
        // 不是"还没搬过家"）。只有键压根不存在时，才回读老文件里的那一份。
        readPair(FAV_KEY, fav, favIds)
        readPair(HIDE_KEY, hide, hideIds)
        legacyPending = prefs.contains(FAV_KEY) || prefs.contains(HIDE_KEY)
    }

    private fun readPair(key: String, strings: HashSet<String>, ids: HashSet<Long>) {
        val fromPrivate = privatePrefs.contains(key)
        val raw = privatePrefs.getStringSet(key, emptySet()).orEmpty()
        if (!fromPrivate) {
            prefs.getStringSet(key, emptySet()).orEmpty().forEach { seed(it, strings, ids) }
        } else {
            raw.forEach { seed(it, strings, ids) }
        }
    }

    private fun seed(raw: String, strings: HashSet<String>, ids: HashSet<Long>) {
        strings += raw
        raw.toLongOrNull()?.let { ids += it }
    }

    fun favoriteIds(): Set<Long> = synchronized(lock) { favIds.toSet() }

    fun hiddenIds(): Set<Long> = synchronized(lock) { hideIds.toSet() }

    fun isFavorite(id: Long): Boolean = synchronized(lock) { id in favIds }

    fun isHidden(id: Long): Boolean = synchronized(lock) { id in hideIds }

    /** @return 改完之后的整份清单；调用点直接把它往下传，不要再回 prefs 取一次旧的。 */
    fun toggleFavorite(id: Long): Set<Long> = synchronized(lock) {
        if (!fav.remove(id.toString())) fav += id.toString()
        if (!favIds.remove(id)) favIds += id
        touchedFav()
    }

    fun addFavorites(ids: Collection<Long>): Set<Long> = synchronized(lock) {
        if (ids.isNotEmpty()) {
            ids.forEach { fav += it.toString(); favIds += it }
            touchedFav()
        } else {
            favIds.toSet()
        }
    }

    fun removeFavorites(ids: Collection<Long>): Set<Long> = synchronized(lock) {
        if (ids.isNotEmpty()) {
            ids.forEach { fav -= it.toString(); favIds -= it }
            touchedFav()
        } else {
            favIds.toSet()
        }
    }

    fun toggleHidden(id: Long): Set<Long> = synchronized(lock) {
        if (!hide.remove(id.toString())) hide += id.toString()
        if (!hideIds.remove(id)) hideIds += id
        touchedHide()
    }

    fun addHidden(ids: Collection<Long>): Set<Long> = synchronized(lock) {
        if (ids.isNotEmpty()) {
            ids.forEach { hide += it.toString(); hideIds += it }
            touchedHide()
        } else {
            hideIds.toSet()
        }
    }

    fun removeHidden(ids: Collection<Long>): Set<Long> = synchronized(lock) {
        if (ids.isNotEmpty()) {
            ids.forEach { hide -= it.toString(); hideIds -= it }
            touchedHide()
        } else {
            hideIds.toSet()
        }
    }

    private fun touchedFav(): Set<Long> {
        version++
        if (autoWriter) ensureWriter()
        return favIds.toSet()
    }

    private fun touchedHide(): Set<Long> {
        version++
        if (autoWriter) ensureWriter()
        return hideIds.toSet()
    }

    /** 调用线程上同步排一次：写者不属于组合作用域，取消调用点也带不走它。 */
    private fun ensureWriter(): Deferred<Boolean> {
        writer?.takeIf { it.isActive }?.let { return it }
        if (!autoWriter) return writerScope.async { true }
        return writerScope.async { persistNow() }.also { writer = it }
    }

    /** 落盘过的是哪一版；给测试和"要不要再写一遍"的判据用。 */
    fun persistedVersionForTests(): Long = synchronized(lock) { persistedVersion }

    fun versionForTests(): Long = synchronized(lock) { version }

    /**
     * 标脏之后要落盘：等一个**进程级**写者把磁盘追到当前版本。
     *
     * 为什么不直接 `withContext(IO) { persistNow() }`：调用点的协程挂在
     * `rememberCoroutineScope()`（组合作用域）上，而 Manifest 只声明了
     * `orientation|screenSize|keyboardHidden` —— 改系统字级、进分屏、切深色都会重建
     * MainActivity 并取消整批未完成的工作；`PermissionGate` 那里权限一掉，整棵内容子树
     * 也会离开组合。被取消的是"等结果"，不能是"这次写"：否则界面继续说「已藏」，
     * 磁盘却是旧的 —— 下次打开照片全回到时间线，屏幕一句解释都没有。
     */
    suspend fun awaitPersisted(): Boolean = synchronized(lock) { ensureWriter() }.await()

    /**
     * 把此刻两份清单落盘，返回写进设置了没有。同步版，给测试和进程级写者自己用。
     *
     * 已经是最新一份就直接回 true：`pruneForDeleted` 连着两个门面各要一次落盘，也只写一遍文件。
     *
     * **写完还要回头看版本，变了就再写一轮** —— 这条不是保险，是唯一的收口：
     * 两次点击可以共用一个写者（`awaitPersisted` 会 join 那个还在跑的 job），
     * 第二次的改动必须被追上去，否则磁盘停在第一次的快照上。
     */
    fun persistNow(): Boolean = synchronized(writeLock) { persistUnlocked() }

    /**
     * 这里**必须**用 `commit()` 而不是 lint 建议的 `apply()`：
     * 本函数的返回值就是"到底写进磁盘了没有"（[awaitPersisted] 拿它决定要不要
     * 让界面说「已藏」）。`apply()` 是异步的、不返回结果，换掉之后这个判据就变成了
     * 一句空话 —— 写失败也报成功，正是这条链路当初要解决的问题。
     * 所以就地静音，理由记在这里而不是留一条"以后再说"。
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    private fun persistUnlocked(): Boolean {
        var ok = true
        while (true) {
            val v: Long
            val snapshotFav: Set<String>
            val snapshotHide: Set<String>
            synchronized(lock) {
                if (persistedVersion == version && !legacyPending) return ok
                v = version
                snapshotFav = HashSet(fav)
                snapshotHide = HashSet(hide)
            }
            ok = try {
                privatePrefs.edit()
                    .putStringSet(FAV_KEY, snapshotFav)
                    .putStringSet(HIDE_KEY, snapshotHide)
                    .commit()
            } catch (e: Exception) {
                Log.w("IdLists", "写收藏/隐藏清单抛了", e)
                false
            }
            if (ok && legacyPending) ok = clearLegacy()
            if (!ok) {
                Log.w(
                    "IdLists",
                    "清单没写进设置（收藏 " + snapshotFav.size + " 条 / 隐藏 " + snapshotHide.size + " 条）",
                )
            }
            val stillCurrent = synchronized(lock) {
                if (ok) persistedVersion = v
                version == v
            }
            if (stillCurrent) return ok
        }
    }

    /**
     * 把老文件里遗留的两个键清掉 —— 不清的话它们会一直躺在参与备份的 `suipai.xml` 里，
     * 搬家就白搬了。同进程里 SharedPreferences 每个文件只有一份内存表，所以这里 remove 之后，
     * 别人对同一文件的全表写不会把旧值倒回来。
     */
    private fun clearLegacy(): Boolean = try {
        val wrote = prefs.edit().remove(FAV_KEY).remove(HIDE_KEY).commit()
        if (wrote) legacyPending = false
        else Log.w("IdLists", "老设置里的清单键没清掉，下次落盘再试")
        wrote
    } catch (e: Exception) {
        Log.w("IdLists", "清老设置里的清单键抛了", e)
        false
    }

    /**
     * 升级后第一次拿到实例就把家搬完，不等到用户点第一颗星星。
     *
     * 为什么不放在 `init` 里同步写：那等于把一次全文件写放回主线程（§42 量到的 69 ms / 132 ms
     * 就是这么来的）。这里只是把进程级写者踢起来，写盘仍在 IO 上。
     */
    fun migrateFromBackupFile() {
        synchronized(lock) { if (legacyPending) ensureWriter() }
    }

    companion object {
        const val FAV_KEY = "fav_ids"
        const val HIDE_KEY = "hide_ids"

        /** 参与备份的设置文件名。清单**不**存在这里（见 `PRIVATE_FILE`）。 */
        const val BACKED_UP_FILE = "suipai"

        /** 不参与备份的私有设置文件；两份 id 清单只落在这一个文件里。 */
        const val PRIVATE_FILE = "suipai.private"

        @Volatile
        private var instance: IdLists? = null

        /** 全进程一份：两份清单同属一个文件，两份实例就一定会互相覆盖。 */
        fun of(context: Context): IdLists = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: IdLists(
                prefs = app.getSharedPreferences(BACKED_UP_FILE, Context.MODE_PRIVATE),
                privatePrefs = app.getSharedPreferences(PRIVATE_FILE, Context.MODE_PRIVATE),
            ).also { it.migrateFromBackupFile(); instance = it }
        }
    }
}
