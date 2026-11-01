package com.suipai.app.data

import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IdLists` 的闸门、重试与跨键不回归 —— 收藏/隐藏这两份清单唯一的真值所在。
 *
 * 为什么值得为它写一份假 prefs：这个类是今天从两个类合并出来的，而它扛着三条只有"出事那天"
 * 才看得见的规矩，JVM 侧之前一条都没钉：
 *
 *  1. 一次 `commit()` 同时写两个键 —— 只改收藏时把隐藏那份写回旧值，就是跨对象丢更新；
 *  2. 版本闸门 —— 没改过就不许再写一遍文件（`pruneForDeleted` 会连着要两次落盘）；
 *  3. 写完回头看版本 —— 调用点的协程挂在组合作用域上，字级/分屏/深色都会重建 Activity 并取消
 *     未完成的工作；写者必须跑到"磁盘等于最后一次改动"为止，否则界面继续说「已藏」而磁盘是旧的。
 *
 * 每条都配了一个"改动没发生就看不见"的反例（先让它红，再让它绿）。
 */
class IdListsTest {

    /** 只实现用得上的三个方法；其余按 SharedPreferences 契约抛错，免得哪天悄悄依赖了没实现的行为。 */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Set<String>>()
        var commits = 0
        var failNext = false
        var onCommit: (() -> Unit)? = null

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key]?.firstOrNull() ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key]?.let { HashSet(it) } ?: defValues

        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Set<String>>()
            var dropped = false
            val droppedKeys = ArrayList<String>()

            override fun putString(key: String?, value: String?) = apply { pending[key!!] = setOf(value ?: "") }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { pending[key!!] = values?.toSet() ?: emptySet() }

            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { dropped = true; key?.let { droppedKeys.add(it) } }
            override fun clear() = apply { data.clear() }

            override fun commit(): Boolean {
                commits++
                onCommit?.invoke()
                if (failNext) {
                    failNext = false
                    return false
                }
                data.putAll(pending)
                // remove() 必须真的删：迁移那几条断言判的就是"老键还在不在"。
                // 原来这个桩只把键从 pending 里摘掉、data 原样留着，于是"清掉了"永远测不出来。
                droppedKeys.forEach { data.remove(it) }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    /**
     * 默认关掉自动写者：这些守卫要的是「我调 persistNow 才写」，
     * 而不是后台抢跑一次把 commits 计数和竞态断言都搅乱。
     *
     * 默认让两个文件是**同一个** fake —— 清单落哪个文件不是这些老守卫要判的事，
     * 而迁移只在"两个文件不是同一个、且老文件里有键"时才发生。迁移的用例各自传两个。
     */
    private fun store(
        prefs: FakePrefs = FakePrefs(),
        autoWriter: Boolean = false,
        privatePrefs: FakePrefs = prefs,
    ) = IdLists(prefs, privatePrefs, autoWriter)

    @Test
    fun oneCommitCarriesBothLists() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.addFavorites(listOf(11L, 12L))
        s.addHidden(listOf(21L))
        assertTrue(s.persistNow())
        val commitsWithBoth = prefs.data.keys
        assertEquals(
            "一次落盘要把两个键都写进去",
            setOf(IdLists.FAV_KEY, IdLists.HIDE_KEY),
            commitsWithBoth,
        )
        assertEquals(setOf("11", "12"), prefs.data[IdLists.FAV_KEY])
        assertEquals(setOf("21"), prefs.data[IdLists.HIDE_KEY])
    }

    @Test
    fun aFavouriteOnlyChangeNeverRewritesTheHiddenListBack() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.addHidden(listOf(21L, 22L))
        assertTrue(s.persistNow())
        s.addFavorites(listOf(9L))
        assertTrue(s.persistNow())
        assertEquals("改了收藏不许把隐藏写回旧值", setOf("21", "22"), prefs.data[IdLists.HIDE_KEY])
        assertEquals(setOf("9"), prefs.data[IdLists.FAV_KEY])
    }

    @Test
    fun persistWithoutAChangeDoesNotTouchTheDiskAgain() {
        val s = store()
        s.toggleFavorite(1L)
        assertTrue(s.persistNow())
        val after = (s.persistedVersionForTests() to s.versionForTests())
        assertTrue("没改过就直接回 true", s.persistNow())
        assertEquals("闸门之后版本不许动", after, s.persistedVersionForTests() to s.versionForTests())
    }

    @Test
    fun aChangeLandingMidCommitIsStillWrittenOut() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.addHidden(listOf(1L))
        assertTrue(s.persistNow())
        // 闸门已经关了：没有改动就不会再写。先做一次改动，让这一次 commit 正在写盘时
        // 用户又点了一张 —— 写者必须追上去，不能停在旧快照上。
        s.toggleHidden(5L)
        val before = prefs.commits
        prefs.onCommit = {
            if (prefs.commits == before + 1) {
                s.toggleHidden(77L)
                prefs.onCommit = null
            }
        }
        assertTrue(s.persistNow())
        assertEquals(
            "磁盘最终要等于最后一次改动（哪怕那次改动落在 commit 中间）",
            setOf("1", "5", "77"),
            prefs.data[IdLists.HIDE_KEY],
        )
        assertEquals("追的那一轮就是这次改动换来的", before + 2, prefs.commits)
    }

    @Test
    fun aFailedCommitDoesNotAdvanceTheGate() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.toggleFavorite(5L)
        prefs.failNext = true
        assertFalse("落盘失败必须说 false —— 界面靠这句说「没写进设置」", s.persistNow())
        assertTrue("失败之后闸门没开，下一次要重写", s.persistNow())
        assertEquals(setOf("5"), prefs.data[IdLists.FAV_KEY])
    }

    @Test
    fun idsReflectTheTapWithoutReadingPrefsAgain() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.toggleFavorite(3L)
        assertEquals(setOf(3L), s.favoriteIds())
        assertTrue(s.isFavorite(3L))
        assertFalse(s.isFavorite(4L))
        // 内存真值先于磁盘：prefs 里此刻还是空的，界面却已经要显示这一张。
        assertEquals(null, prefs.data[IdLists.FAV_KEY])
        val returned = s.removeFavorites(listOf(3L))
        assertTrue("mutator 要把改完的集合还给调用点", returned.isEmpty())
        assertTrue(s.favoriteIds().isEmpty())
    }

    /**
     * 今天这条链真正的病灶：调用点的协程挂在组合作用域上，改字级 / 进分屏 / 切深色都会重建
     * Activity 并把未完成的工作整批取消。所以标脏与排写者必须在**点击线程**上同步做完，
     * 写者挂在进程级作用域上 —— 取消"等结果"只会丢掉那句文案，不会丢掉这次写。
     */
    @Test
    fun aCancelledAwaiterStillGetsTheWriteDone() {
        val prefs = FakePrefs()
        val s = store(prefs, autoWriter = true)   // 生产形状：改动当场自己排写者
        val next = s.toggleHidden(8L)
        assertEquals("改动当场就要在内存里", setOf(8L), next)
        val caller = CoroutineScope(Dispatchers.Default)
        val job = caller.launch { s.awaitPersisted() }
        job.cancel()
        var waited = 0
        while (prefs.data[IdLists.HIDE_KEY] != setOf("8") && waited < 4_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertEquals("调用点被取消不能带走这一次写", setOf("8"), prefs.data[IdLists.HIDE_KEY])
    }

    /**
     * 装机探针读到"点完收藏页眉一句都没有"，而照片确实收藏上了、重启也在 ——
     * 说明写者跑成了，是**调用点那句 await 没有回来**（结论与后续刷新都在 await 之后）。
     * 这条测试就是钉它：改动之后 awaitPersisted() 必须在有限时间内返回。
     */
    @Test
    fun awaitPersistedReturnsAfterAChange() {
        val prefs = FakePrefs()
        val s = store(prefs, autoWriter = true)
        s.toggleFavorite(4242L)
        val got = runBlocking { withTimeout(3_000) { s.awaitPersisted() } }
        assertTrue("awaitPersisted() 没在 3 秒内返回：结论那句永远不会有", got)
        assertEquals(setOf("4242"), prefs.data[IdLists.FAV_KEY])
    }

    // ── 清单搬家：收藏/隐藏不再随云备份出设备 ────────────────────────────

    /**
     * 旧版本把两份清单写在**参与备份**的 `suipai.xml` 里。备份规则是整文件粒度，
     * 没法把两个键单独摘出去 —— 所以清单搬进 `suipai.private.xml`，并把老键清掉。
     * 这条钉的是"搬完且老键真的没了"：老键还在，就等于搬家白搬。
     */
    @Test
    fun legacyListsMoveToThePrivateFileAndTheOldOneIsCleaned() {
        val legacy = FakePrefs().apply {
            data[IdLists.FAV_KEY] = setOf("11", "12")
            data[IdLists.HIDE_KEY] = setOf("13")
        }
        val priv = FakePrefs()
        val s = store(legacy, privatePrefs = priv)

        assertEquals(setOf(11L, 12L), s.favoriteIds())
        assertEquals(setOf(13L), s.hiddenIds())
        assertTrue("一次落盘都没发生就没搬完家", s.persistNow())

        assertEquals(setOf("11", "12"), priv.data[IdLists.FAV_KEY])
        assertEquals(setOf("13"), priv.data[IdLists.HIDE_KEY])
        assertTrue("老文件里还留着 fav_ids —— 它还在备份范围内", !legacy.data.containsKey(IdLists.FAV_KEY))
        assertTrue("老文件里还留着 hide_ids", !legacy.data.containsKey(IdLists.HIDE_KEY))
    }

    /** 搬完之后重启：以新文件为准，且不许再去写老文件（否则每次开机都全文件写一遍）。 */
    @Test
    fun migrationHappensOnceAndDoesNotResurrectOldValues() {
        val legacy = FakePrefs().apply { data[IdLists.FAV_KEY] = setOf("11") }
        val priv = FakePrefs()
        store(legacy, privatePrefs = priv).also { it.persistNow() }

        val legacyCommits = legacy.commits
        val second = store(legacy, privatePrefs = priv)
        assertEquals(setOf(11L), second.favoriteIds())
        second.persistNow()
        assertEquals("搬完家之后还写老文件：${legacy.commits} 次", legacyCommits, legacy.commits)

        // 新文件说"没有收藏"，老文件里那条 11 不许把它捞回来
        priv.data[IdLists.FAV_KEY] = emptySet()
        assertEquals(emptySet<Long>(), store(legacy, privatePrefs = priv).favoriteIds())
    }

    /** 清老键那一次写失败：不能把"没清掉"当成搬完了，下一次落盘要再试。 */
    @Test
    fun aFailedLegacyCleanupIsRetried() {
        val legacy = FakePrefs().apply {
            data[IdLists.HIDE_KEY] = setOf("7")
            failNext = true                     // 第一次 commit（清老键）失败
        }
        val priv = FakePrefs()
        val s = store(legacy, privatePrefs = priv)
        // 写新文件成功、清老键失败 → 整体回 false，且老键还在
        assertFalse("清老键失败却被判成成功", s.persistNow())
        assertTrue(legacy.data.containsKey(IdLists.HIDE_KEY))
        assertTrue("下一次落盘没把老键清掉", s.persistNow())
        assertTrue(!legacy.data.containsKey(IdLists.HIDE_KEY))
    }

    /**
     * 两个备份规则文件都必须**显式排除**私有文件，且谁都没 include 它。
     *
     * 判据做成纯函数：`full-backup-content`（Android 6+）与 `data-extraction-rules`
     * （Android 12+，云备份与换机直传两段）语法不同，但"私有文件不许出现在 include 里"这一条一样。
     */
    @Test
    fun bothBackupRuleFilesExcludeThePrivateFile() {
        fun check(name: String) {
            val f = File("src/main/res/xml/$name")
            assertTrue("找不到 $name（路径变了？）", f.isFile)
            val (included, excluded) = privateFileBackupStatus(f.readText(), "suipai.private.xml")
            assertFalse("$name 把私有文件 include 进了备份", included)
            assertTrue("$name 没有显式 exclude 私有文件（默认排除不算承诺）", excluded)
        }
        check("backup_rules.xml")
        check("data_extraction_rules.xml")

        // 自检：三种形状都要能判出来，否则上面两条是空跑
        val noExclude = """<full-backup-content><include domain="sharedpref" path="suipai.xml"/></full-backup-content>"""
        assertEquals(false to false, privateFileBackupStatus(noExclude, "suipai.private.xml"))
        val included = """<r><include domain="sharedpref" path="suipai.private.xml"/></r>"""
        assertEquals(true to false, privateFileBackupStatus(included, "suipai.private.xml"))
        // 注释里提一句文件名，不许被当成 include 了它
        val onlyComment = """<r><!-- suipai.private.xml 不参与备份 --></r>"""
        assertEquals(false to false, privateFileBackupStatus(onlyComment, "suipai.private.xml"))
        val good = """<r><exclude domain="sharedpref"%s path="suipai.private.xml" /></r>"""
            .format("\n        ")
        assertEquals(false to true, privateFileBackupStatus(good, "suipai.private.xml"))
    }

    /** 读 `<include>` / `<exclude>` 的 path，注释整段剥掉再判。 */
    private fun privateFileBackupStatus(xml: String, privateFile: String): Pair<Boolean, Boolean> {
        val body = Regex("(?s)<!--.*?-->").replace(xml, " ")
        val included = Regex("""<include\b[^>]*path="$privateFile"""").containsMatchIn(body)
        val excluded = Regex("""<exclude\b[^>]*path="$privateFile"""").containsMatchIn(body)
        return included to excluded
    }
}
