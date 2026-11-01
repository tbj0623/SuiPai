package com.suipai.app.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 索引的落盘与合并：JSON 往返、版本不匹配、坏文件、增量去重、`suggestions` 的排序与去重。
 *
 * 为什么这些必须在 JVM 里钉：索引是**一条 5000 行的账**，它坏掉时的表现不是崩溃，
 * 而是"搜索突然只搜到一部分"或"每次冷启动都白解析一兆多的垃圾"—— 两种都要装机才能看见。
 *
 * 这里用 `SearchIndex(file)`（不传 ContentResolver）走纯内存模式：
 * 生产路径的 MediaStore 那一半在 JVM 里没有实现（`android.jar` 全是桩），
 * 但**落盘格式、版本闸门、去重、排序**全在这半边，可以完整测。
 */
class SearchIndexTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("suipai-search-index").toFile()
        file = File(dir, SearchIndex.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun photo(
        id: Long,
        name: String = "IMG_$id.jpg",
        album: String = "Camera",
        taken: Long = 1_700_000_000_000L,
        make: String = "",
        model: String = "",
        lens: String = "",
        iso: Int = 0,
        aperture: String = "",
        shutter: String = "",
        focal: Int = 0,
        hasGps: Boolean = false,
        lat: Double = 0.0,
        lon: Double = 0.0,
    ) = IndexedPhoto(
        photoId = id,
        displayName = name,
        bucketName = album,
        dateTaken = taken,
        width = 4000,
        height = 3000,
        sizeBytes = 4_000_000L,
        make = make,
        model = model,
        lens = lens,
        iso = iso,
        aperture = aperture,
        shutter = shutter,
        focal = focal,
        hasGps = hasGps,
        lat = lat,
        lon = lon,
        kind = photoKindOf(name, album),
    )

    private fun index() = SearchIndex(file)

    // ── JSON 往返 ───────────────────────────────────────────────────

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val original = photo(
            id = 42,
            name = "上海外滩.jpg",
            album = "旅行",
            taken = 1_700_000_123_456L,
            make = "SONY",
            model = "ILCE-7M4",
            lens = "FE 24-70mm F2.8 GM II",
            iso = 400,
            aperture = "f/2.8",
            shutter = "1/125",
            focal = 35,
            hasGps = true,
            lat = 31.2304,
            lon = 121.4737,
        )
        val index = index()
        index.seed(listOf(original))
        assertTrue("落盘失败", index.writeNow())

        val reopened = index()
        assertEquals("载入回来几张", 1, reopened.load())
        assertEquals(original, reopened.photoById(42))
    }

    @Test
    fun stringsWithQuotesBackslashesAndNewlinesSurvive() {
        // 文件名里真会出现引号与反斜杠（别人分享进来的、相机导出的）。
        // 不做转义的话，第一个 `"` 就把整份 JSON 切断 —— 表现是"索引莫名其妙全空"。
        val nasty = photo(id = 7, name = "a\"b\\c\nd\te.jpg", album = "x\"y")
        val index = index()
        index.seed(listOf(nasty))
        assertTrue(index.writeNow())

        val reopened = index()
        assertEquals(1, reopened.load())
        assertEquals(nasty, reopened.photoById(7))
    }

    @Test
    fun aMissingFileIsZeroRatherThanAnError() {
        assertFalse(file.exists())
        assertEquals(0, index().load())
    }

    @Test
    fun emptyIndexNeverWritesAFile() {
        // 什么都没扫到就不要留一个空壳：那个空壳会让 `builtAt` 看起来像"扫过了"。
        val index = index()
        assertFalse("一张都没有时不该写盘", index.writeNow())
        assertFalse(file.exists())
    }

    // ── 版本与损坏兜底 ───────────────────────────────────────────────

    @Test
    fun aVersionMismatchFallsBackToAnEmptyIndexAndDeletesTheFile() {
        val index = index()
        index.seed(listOf(photo(1)))
        assertTrue(index.writeNow())
        assertTrue("前置条件：文件写出来了", file.isFile)

        // 把版本号改成"未来版本"：字段语义可能已经变了，不做迁移，直接重建。
        val bumped = file.readText().replace("\"version\":${SearchJson.VERSION}", "\"version\":999")
        file.writeText(bumped)

        val reopened = index()
        assertEquals("版本不符时按空索引处理", 0, reopened.load())
        assertEquals(0, reopened.size())
        assertFalse("坏/过期的索引文件要删掉，否则每次冷启动都白解析一遍", file.exists())
    }

    @Test
    fun aCorruptFileFallsBackToAnEmptyIndexAndDeletesTheFile() {
        val index = index()
        index.seed(listOf(photo(1), photo(2)))
        assertTrue(index.writeNow())
        // 进程写到一半被杀的形状：截断成前一半。
        val text = file.readText()
        file.writeText(text.substring(0, text.length / 2))

        val reopened = index()
        assertEquals(0, reopened.load())
        assertEquals(0, reopened.size())
        assertFalse("截断的索引文件要删掉", file.exists())
    }

    @Test
    fun garbageThatIsNotEvenJsonIsHandled() {
        file.writeText("这不是 JSON")
        val index = index()
        assertEquals(0, index.load())
        assertFalse(file.exists())
    }

    @Test
    fun aMissingVersionIsTreatedAsCorrupt() {
        file.writeText("""{"photos":[{"id":1,"name":"a.jpg"}]}""")
        assertEquals(0, index().load())
    }

    @Test
    fun oneBadRowDoesNotKillTheWholeIndex() {
        // 单行坏掉（缺 id）：跳过那一行，别的照样进来。
        file.writeText(
            """{"version":${SearchJson.VERSION},"builtAt":5,"count":3,"photos":["""
                + """{"id":1,"name":"a.jpg","album":"A","taken":1},"""
                + """{"name":"没有 id 的一行"},"""
                + """{"id":3,"name":"c.jpg","album":"C","taken":3}"""
                + """]}""",
        )
        val index = index()
        assertEquals("坏行被跳过、好行照进", 2, index.load())
        assertNotNull(index.photoById(1))
        assertNotNull(index.photoById(3))
        assertNull(index.photoById(2))
    }

    @Test
    fun builtAtSurvivesARoundTrip() {
        val index = index()
        index.seed(listOf(photo(1)), builtAt = 1_234_567_890L)
        assertTrue(index.writeNow())
        assertEquals(1_234_567_890L, index().also { it.load() }.builtAt)
    }

    // ── 增量去重 ────────────────────────────────────────────────────

    @Test
    fun mergeKeepsOneRowPerIdAndLetsTheNewOneWin() {
        val before = photo(1, name = "old.jpg")
        val after = photo(1, name = "new.jpg")
        val merged = SearchIndex.mergeIndexed(listOf(before), listOf(after))
        assertEquals("同一个 id 只留一行", 1, merged.size)
        assertEquals("后来的那份才是刚读出来的，它赢", "new.jpg", merged.single().displayName)
    }

    @Test
    fun mergePreservesOrderAndAppendsNewIds() {
        val merged = SearchIndex.mergeIndexed(
            listOf(photo(1), photo(2)),
            listOf(photo(3)),
        )
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.photoId })
    }

    @Test
    fun seedingTheSamePhotosTwiceDoesNotDuplicateThem() {
        val index = index()
        index.seed(listOf(photo(1), photo(2)))
        index.seed(listOf(photo(1), photo(2), photo(3)))
        assertEquals("同一张扫两遍不许变成两条结果", 3, index.size())
        assertEquals(setOf(1L, 2L, 3L), index.snapshot().map { it.photoId }.toSet())
    }

    @Test
    fun aReseededRowIsUpdatedInPlaceNotEmptyExtraRow() {
        val index = index()
        index.seed(listOf(photo(1, name = "old.jpg")))
        index.seed(listOf(photo(1, name = "new.jpg")))
        assertEquals(1, index.size())
        assertEquals("new.jpg", index.photoById(1)?.displayName)
    }

    @Test
    fun mergeIsIdempotent() {
        val once = SearchIndex.mergeIndexed(emptyList(), listOf(photo(1), photo(2)))
        val twice = SearchIndex.mergeIndexed(once, listOf(photo(1), photo(2)))
        assertEquals(once, twice)
    }

    // ── 查询 / 建议接在索引上 ────────────────────────────────────────

    @Test
    fun queryOnTheIndexReturnsHitsWithReasons() {
        val index = index()
        index.seed(
            listOf(
                photo(1, name = "上海外滩.jpg"),
                photo(2, name = "IMG_2.jpg", album = "上海"),
                photo(3, name = "IMG_3.jpg", album = "北京"),
            ),
        )
        val hits = index.query("上海")
        assertEquals(setOf(1L, 2L), hits.map { it.photoId }.toSet())
        assertEquals(HitReason.FILE_NAME, hits.first { it.photoId == 1L }.reason)
        assertEquals(HitReason.ALBUM, hits.first { it.photoId == 2L }.reason)
        assertTrue(index.query("上海", limit = 1).size == 1)
    }

    @Test
    fun anEmptyQueryAgainstAnEmptyIndexIsEmpty() {
        assertTrue(index().query("").isEmpty())
        assertTrue(index().query("上海").isEmpty())
    }

    @Test
    fun suggestionsAreModelsThenYearsThenAlbumsAndDeduplicated() {
        val index = index()
        index.seed(
            listOf(
                photo(1, model = "iPhone 15 Pro", album = "Camera", taken = ms("2024-01-01")),
                photo(2, model = "iPhone 15 Pro", album = "Camera", taken = ms("2024-02-01")),
                photo(3, model = "Pixel 8", album = "Screenshots", taken = ms("2023-01-01")),
            ),
        )
        val out = index.suggestions("")
        assertEquals("型号在前（按票数）", "iPhone 15 Pro", out.first())
        assertTrue("年份在型号之后", out.indexOf("2024") > out.indexOf("Pixel 8"))
        assertTrue("相册名在年份之后", out.indexOf("Camera") > out.indexOf("2024"))
        assertEquals("同一串只出现一次", out.size, out.distinct().size)
        assertTrue("建议不许超过上限", out.size <= SearchQuery.SUGGESTION_LIMIT)
    }

    @Test
    fun suggestionsAreFilteredByWhatIsTyped() {
        val index = index()
        index.seed(
            listOf(
                photo(1, model = "iPhone 15 Pro", album = "上海"),
                photo(2, model = "Pixel 8", album = "北京"),
            ),
        )
        val out = index.suggestions("上海")
        assertTrue("只给和输入有关的：$out", out.all { "上海" in it })
    }

    @Test
    fun suggestionsDoNotExplodeOnAnEmptyIndex() {
        assertTrue(index().suggestions("").isEmpty())
    }

    // ── 快门格式化 ──────────────────────────────────────────────────

    @Test
    fun shutterIsNormalisedToAFraction() {
        assertEquals("1/125", SearchIndex.shutterOf("0.008"))
        assertEquals("1/4", SearchIndex.shutterOf("0.25"))
        assertEquals("2", SearchIndex.shutterOf("2"))
        assertEquals("", SearchIndex.shutterOf(null))
        assertEquals("", SearchIndex.shutterOf(""))
        assertEquals("读不出来就原样留着", "1/abc", SearchIndex.shutterOf("1/abc"))
        assertEquals("零不许除", "0", SearchIndex.shutterOf("0"))
    }

    // ── 类型判据 ────────────────────────────────────────────────────

    @Test
    fun photoKindsFollowTheNameAndAlbum() {
        assertEquals(PhotoKind.ORIGINAL, photoKindOf("SuiPai_1790023855356.jpg", "Camera"))
        assertEquals(PhotoKind.YISHUN, photoKindOf("SuiPai_一瞬_暖_80_1.jpg", "Camera"))
        assertEquals(PhotoKind.COPY, photoKindOf("SuiPai_副本_1790.jpg", "Camera"))
        assertEquals(PhotoKind.SCREENSHOT, photoKindOf("Screenshot_20240101.png", "Pictures"))
        assertEquals(PhotoKind.SCREENSHOT, photoKindOf("IMG_1.jpg", "Screenshots"))
        assertEquals(PhotoKind.SCREENSHOT, photoKindOf("IMG_1.jpg", "截屏"))
        assertEquals(PhotoKind.DOWNLOAD, photoKindOf("IMG_1.jpg", "Download"))
        assertEquals(PhotoKind.DOWNLOAD, photoKindOf("IMG_1.jpg", "下载"))
        assertEquals(PhotoKind.OTHER, photoKindOf("IMG_1.jpg", "Camera"))
        // 加工件不许被算成原片（判据是"只有一个下划线"）
        assertEquals(PhotoKind.COPY, photoKindOf("SuiPai_2K_1790.jpg", "Camera"))
        // 空前缀（token 漏值）：`SuiPai__1790.jpg` 去掉前缀之后是 `_1790.jpg`，
        // 里面还有一个下划线 → 不是原片。这条与 `GalleryScreen.isSuiPaiOriginalName` 同解。
        assertEquals(PhotoKind.COPY, photoKindOf("SuiPai__1790.jpg", "Camera"))
        // 截图优先级高于一切："Screenshot_" 开头的绝不进随拍系列
        assertEquals(PhotoKind.SCREENSHOT, photoKindOf("SuiPai_1790.jpg", "Screenshots"))
    }

    @Test
    fun kindLabelsAreDistinct() {
        val labels = PhotoKind.entries.map { it.label }
        assertEquals("类型标签不能重复（下拉里会撞名）", labels.size, labels.distinct().size)
    }

    // ── 自检：坏例子必须变红 ─────────────────────────────────────────

    /**
     * 三条判据各自喂一个坏样本，证明它们真的会红。
     *
     * 踩过的形状：
     *  - 版本号不比对 → 老版本用新格式读出一堆空字段，搜索静默失效；
     *  - 增量不做 id 去重 → 同一张照片搜出两条结果（而且删掉一条还在）；
     *  - 损坏不兜底直接抛 → 冷启动崩在 `filesDir` 那一行。
     */
    @Test
    fun checkersActuallyCatchBadIndexes() {
        // 1. 版本不比对：把校验换成"有 version 就算过"，999 也会被接受。
        val seeded = index().also { it.seed(listOf(photo(1))) }
        assertTrue(seeded.writeNow())
        val bumped = file.readText().replace("\"version\":${SearchJson.VERSION}", "\"version\":999")
        file.writeText(bumped)
        val naiveAccepts = bumped.contains("\"version\":999")
        assertTrue("前提：文件里真的写着 999", naiveAccepts)
        assertEquals("真判据必须拒绝 999", 0, index().load())

        // 2. 不去重：直接拼两份清单，同一张会出现两次。
        val naive = listOf(photo(1), photo(1))
        assertEquals("不合并的写法会重复", 2, naive.size)
        assertEquals("合并之后是一行", 1, SearchIndex.mergeIndexed(listOf(photo(1)), listOf(photo(1))).size)

        // 3. 不兜底：把一半的 JSON 直接丢给解析器，它只能抛。
        val half = """{"version":1,"photos":[{"id":1,"na"""
        assertNull("坏 JSON 必须是 null，不是异常", SearchJson.decodeIndex(half))
        assertEquals("清单那边同理", null, SearchJson.decodeStrings("<html>"))
    }

    @Test
    fun versionMismatchOnTheHistoryFileIsAlsoRejected() {
        // 两份文件共用同一个版本号常量 —— 一边改了另一边没跟，就是"老历史读出新格式"。
        val encoded = SearchJson.encodeStrings(listOf("上海"))
        val bumped = encoded.replace("\"version\":${SearchJson.VERSION}", "\"version\":0")
        assertNull("版本不符的清单当没读到", SearchJson.decodeStrings(bumped))
        assertEquals(listOf("上海"), SearchJson.decodeStrings(encoded))
    }

    private fun ms(day: String): Long =
        java.time.LocalDate.parse(day)
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()
}
