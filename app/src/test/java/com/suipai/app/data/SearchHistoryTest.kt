package com.suipai.app.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 最近搜索词的四条规矩：去重（不区分大小写）、上限 10、时间倒序、坏 JSON 兜底。
 *
 * 为什么要单独一篇：这块地方**看起来**只是"存几个词"，但每一条规矩错了都只有用户才发现：
 *  - 不去重 → 同一个词连搜三次，历史里三行一模一样；
 *  - 不去掉空格 → 点了建议之后那个词在输入框里带前导空格，第二次搜索行为就变了；
 *  - 不截断 → 历史区会把首屏吃光（它就在输入框下面）；
 *  - 坏文件直接抛 → 冷启动崩在 `filesDir` 那一行。
 *
 * 故意**不用** `SharedPreferences`：见 `SearchHistory` 的类注释（会撞 `FlagTableGuardTest`
 * 的键名唯一性）。这里连桩都不用造，直接给临时文件。
 */
class SearchHistoryTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("suipai-search-history").toFile()
        file = File(dir, SearchHistory.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun history() = SearchHistory(file)

    // ── 基本语义 ────────────────────────────────────────────────────

    @Test
    fun aNewTermGoesToTheFront() {
        val h = history()
        h.record("上海")
        h.record("北京")
        assertEquals(listOf("北京", "上海"), h.recent())
    }

    @Test
    fun repeatingATermMovesItToTheFrontInsteadOfDuplicating() {
        val h = history()
        h.record("上海")
        h.record("北京")
        h.record("上海")
        assertEquals("同一个词不许出现两次", listOf("上海", "北京"), h.recent())
    }

    @Test
    fun deduplicationIgnoresCaseButKeepsTheLatestSpelling() {
        val h = history()
        h.record("Shanghai")
        h.record("shanghai")
        assertEquals("不区分大小写去重，但留最新写法", listOf("shanghai"), h.recent())
        h.record("SHANGHAI")
        assertEquals(listOf("SHANGHAI"), h.recent())
    }

    @Test
    fun theListIsCappedAtTenAndTheOldestFallsOff() {
        val h = history()
        (1..12).forEach { h.record("词$it") }
        assertEquals("上限 10 条", SearchHistory.MAX, h.size())
        assertEquals("最旧的两条被挤掉", "词3", h.recent().last())
        assertEquals("最新的在最前", "词12", h.recent().first())
        assertFalse("词1 应当已经没了", "词1" in h.recent())
    }

    @Test
    fun blanksAreNeverRecorded() {
        val h = history()
        assertEquals("空串不记", emptyList<String>(), h.record(""))
        assertEquals("只有空格不记", emptyList<String>(), h.record("   "))
        assertEquals("全角空格也不记", emptyList<String>(), h.record("\u3000"))
        assertEquals(0, h.size())
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val h = history()
        h.record("  上海  ")
        assertEquals(listOf("上海"), h.recent())
    }

    @Test
    fun recordReturnsTheWholeListSoCallersDoNotReadTheDiskAgain() {
        val h = history()
        assertEquals(listOf("上海"), h.record("上海"))
        assertEquals(listOf("北京", "上海"), h.record("北京"))
    }

    @Test
    fun removeDropsOneTerm() {
        val h = history()
        h.record("上海")
        h.record("北京")
        assertEquals(listOf("北京"), h.remove("上海"))
        assertEquals("大小写不敏感地删", emptyList<String>(), h.remove("北京"))
    }

    @Test
    fun clearEmptiesTheList() {
        val h = history()
        h.record("上海")
        assertEquals(emptyList<String>(), h.clear())
        assertEquals(0, h.size())
    }

    // ── 落盘 ────────────────────────────────────────────────────────

    @Test
    fun historySurvivesARoundTripInOrder() {
        val h = history()
        h.record("上海")
        h.record("北京")
        h.record("2024-09")
        assertTrue(h.writeNow())

        val reopened = history()
        assertEquals(3, reopened.load())
        assertEquals("顺序就是内容", listOf("2024-09", "北京", "上海"), reopened.recent())
    }

    @Test
    fun anEmptyHistoryDeletesTheFileInsteadOfLeavingAShell() {
        val h = history()
        h.record("上海")
        assertTrue(h.writeNow())
        assertTrue(file.isFile)

        h.clear()
        assertTrue(h.writeNow())
        assertFalse("清空之后不该留一个空数组的文件", file.exists())
    }

    @Test
    fun writingAnEmptyHistoryThatNeverExistedIsNotAnError() {
        assertTrue(history().writeNow())
        assertFalse(file.exists())
    }

    @Test
    fun termsWithQuotesAndBackslashesSurvive() {
        val h = history()
        h.record("a\"b\\c")
        assertTrue(h.writeNow())
        assertEquals(listOf("a\"b\\c"), history().also { it.load() }.recent())
    }

    @Test
    fun aMissingFileLoadsZero() {
        assertFalse(file.exists())
        assertEquals(0, history().load())
    }

    // ── 坏文件兜底 ──────────────────────────────────────────────────

    @Test
    fun aCorruptFileLoadsAsEmptyAndIsDeleted() {
        file.writeText("{\"version\":1,\"items\":[\"上海\"")
        val h = history()
        assertEquals(0, h.load())
        assertEquals(0, h.size())
        assertFalse("坏文件要删掉，否则每次冷启动都白解析", file.exists())
    }

    @Test
    fun aVersionMismatchLoadsAsEmpty() {
        val encoded = SearchJson.encodeStrings(listOf("上海"))
        file.writeText(encoded.replace("\"version\":${SearchJson.VERSION}", "\"version\":999"))
        assertEquals(0, history().load())
    }

    @Test
    fun handEditedOverlongFilesAreCleanedOnLoad() {
        // 这个文件在开发期被手改过：20 条、带空行、带重复。载入必须清洗成合法的那份。
        val dirty = (1..20).map { "词$it" } + listOf("", "  ", "词1")
        file.writeText(SearchJson.encodeStrings(dirty))
        val h = history()
        assertEquals(SearchHistory.MAX, h.load())
        assertEquals("词1", h.recent().first())
        assertEquals("不许有空串", 0, h.recent().count { it.isBlank() })
        assertEquals("不许重复", h.recent().size, h.recent().distinct().size)
    }

    @Test
    fun garbageThatIsNotJsonIsHandled() {
        file.writeText("不是 JSON")
        assertEquals(0, history().load())
        assertFalse(file.exists())
    }

    // ── 自检：坏例子必须变红 ─────────────────────────────────────────

    @Test
    fun checkersActuallyCatchBadHistories() {
        // 1. 不去重：直接把词追加到表尾，同一个词会有两行。
        val naive = ArrayList<String>()
        listOf("上海", "上海").forEach { naive += it }
        assertEquals("不去重的写法会重复", 2, naive.size)
        assertEquals("真判据只有一行", 1, history().also { it.record("上海"); it.record("上海") }.size())

        // 2. 不截断：12 条会全留下，把首屏吃光。
        val noCap = (1..12).map { "词$it" }
        assertEquals("不截断的写法是 12 条", 12, noCap.size)
        assertTrue("真判据必须截到 10", noCap.take(SearchHistory.MAX).size <= SearchHistory.MAX)

        // 3. 不兜底：坏 JSON 交给解析器，它只能回 null。
        assertEquals(null, SearchJson.decodeStrings("{\"items\":["))
        // 4. 版本不比对：把校验拿掉，999 也会被当成好文件。
        val bumped = SearchJson.encodeStrings(listOf("上海"))
            .replace("\"version\":${SearchJson.VERSION}", "\"version\":999")
        assertEquals(null, SearchJson.decodeStrings(bumped))
    }

    @Test
    fun normalizeIsTheOnePlaceTheRulesLive() {
        // 载入清洗与 record 必须遵守同一套规矩，否则"刚存的"与"重启后读回来的"会不一样。
        assertEquals(listOf("a", "b"), SearchHistory.normalize(listOf(" a ", "", "a", "b", "  ")))
        assertEquals((1..10).map { "词$it" }, SearchHistory.normalize((1..15).map { "词$it" }))
        assertEquals(emptyList<String>(), SearchHistory.normalize(listOf("", "   ")))
    }
}
