package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 查询语义的唯一一堆断言：分词、大小写、多词 AND、四种日期模式 + 今天/昨天/今年、
 * 命中原因、排序，以及"空查询不是全部"。
 *
 * 这一篇存在的理由是这个项目的搜索**一直是 `private fun`**（`GalleryScreen.matchesPhotoQuery`），
 * 它吃 `PhotoItem`（带 `android.net.Uri`），JVM 里造不出来 —— 于是"搜不到"这类缺陷
 * 只能靠装机手点。`SearchQuery` 是纯函数、只吃 `IndexedPhoto`（全值类型），所以现在能钉。
 *
 * 每条断言都配一个**喂坏例子必须变红**的自检：没见过自己变红的守卫不算守卫。
 */
class SearchQueryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定的"今天"：2024-09-22。所有相对日期的用例都钉这一天，否则跨零点会飘。 */
    private val today: LocalDate = LocalDate.of(2024, 9, 22)

    private fun photo(
        id: Long,
        name: String = "IMG_$id.jpg",
        album: String = "Camera",
        taken: String = "2024-09-22",
        make: String = "",
        model: String = "",
        lens: String = "",
        iso: Int = 0,
        aperture: String = "",
        shutter: String = "",
        focal: Int = 0,
        hasGps: Boolean = false,
    ): IndexedPhoto {
        val day = LocalDate.parse(taken).atStartOfDay(zone).toInstant().toEpochMilli()
        return IndexedPhoto(
            photoId = id,
            displayName = name,
            bucketName = album,
            dateTaken = day,
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
            lat = if (hasGps) 31.23 else 0.0,
            lon = if (hasGps) 121.47 else 0.0,
            kind = photoKindOf(name, album),
        )
    }

    private fun query(q: String, photos: List<IndexedPhoto>, limit: Int = 500): List<SearchMatch> =
        SearchQuery.rank(photos, q, limit, today, zone)

    private fun ids(q: String, photos: List<IndexedPhoto>, limit: Int = 500): List<Long> =
        query(q, photos, limit).map { it.photo.photoId }

    // ── 分词 ────────────────────────────────────────────────────────

    @Test
    fun chineseIsSplitPerCharacterAndOrderIsKept() {
        val photos = listOf(
            photo(1, name = "上海外滩.jpg"),
            photo(2, name = "海上日出.jpg"),
        )
        // 「上海」是一段连续汉字 → **一个词**，按字序匹配（子序列）。
        // 外滩那张「上」在「海」前面 → 命中；日出那张「海」在「上」前面 → 不算。
        // 这一条钉的正是"按字但保序"：逐字拆成两个独立条件会让日出那张也中。
        assertEquals(listOf(1L), ids("上海", photos))
    }

    @Test
    fun aSingleCharacterMatchesAnywhere() {
        val photos = listOf(photo(1, name = "海边.jpg"), photo(2, name = "山.jpg"))
        assertEquals(listOf(1L), ids("海", photos))
    }

    @Test
    fun englishWordsMatchAsWholeWordsCaseInsensitively() {
        val photos = listOf(
            photo(1, name = "IMG_0042.JPG"),
            photo(2, name = "screenshot_2024.png"),
        )
        // 英文不按字母切：搜 "IMG" 不该靠 i/m/g 三个字母的次序命中别的词。
        assertEquals(listOf(1L), ids("img", photos))
        assertEquals("大小写不敏感", listOf(1L), ids("ImG_0042", photos))
        assertEquals(listOf(2L), ids("SCREENSHOT", photos))
    }

    @Test
    fun multiWordQueryRequiresEveryWord() {
        val photos = listOf(
            photo(1, name = "上海外滩.jpg"),
            photo(2, name = "上海夜景.jpg"),
            photo(3, name = "北京故宫.jpg"),
        )
        assertEquals("多词 AND：只留两段都中的", listOf(1L), ids("上海 外滩", photos))
        assertTrue("中途缺一段就整条不命中", ids("上海 故宫", photos).isEmpty())
    }

    @Test
    fun separatorsOfEveryKindSplitTerms() {
        val photos = listOf(photo(1, name = "上海外滩.jpg"))
        assertEquals(listOf(1L), ids("上海、外滩", photos))
        assertEquals(listOf(1L), ids("上海，外滩", photos))
        assertEquals(listOf(1L), ids("上海,外滩", photos))
        assertEquals(listOf(1L), ids("上海 外滩", photos))
    }

    // ── 日期模式 ────────────────────────────────────────────────────

    @Test
    fun fourDigitYearMatchesTheYearOnly() {
        val photos = listOf(
            photo(1, taken = "2024-03-01"),
            photo(2, taken = "2023-03-01"),
        )
        assertEquals(listOf(1L), ids("2024", photos))
    }

    @Test
    fun yearDashMonthMatchesThatMonth() {
        val photos = listOf(
            photo(1, taken = "2024-09-01"),
            photo(2, taken = "2024-10-01"),
            photo(3, taken = "2023-09-01"),
        )
        assertEquals(listOf(1L), ids("2024-09", photos))
        assertEquals("斜杠写法等价", listOf(1L), ids("2024/9", photos))
    }

    @Test
    fun monthDayMatchesEveryYear() {
        val photos = listOf(
            photo(1, taken = "2024-09-22"),
            photo(2, taken = "2020-09-22"),
            photo(3, taken = "2024-09-23"),
        )
        assertEquals("9月22日 不限定年份", listOf(1L, 2L), ids("9月22日", photos))
        assertEquals("省略「日」也认", listOf(1L, 2L), ids("9月22", photos))
    }

    @Test
    fun monthOnlyMatchesThatMonthInAnyYear() {
        val photos = listOf(
            photo(1, taken = "2024-09-01"),
            photo(2, taken = "2019-09-30"),
            photo(3, taken = "2024-08-01"),
        )
        assertEquals(listOf(1L, 2L), ids("9月", photos))
    }

    @Test
    fun relativeDaysAreRelativeToToday() {
        val photos = listOf(
            photo(1, taken = "2024-09-22"),
            photo(2, taken = "2024-09-21"),
            photo(3, taken = "2023-12-01"),
        )
        assertEquals("今天", listOf(1L), ids("今天", photos))
        assertEquals("昨天", listOf(2L), ids("昨天", photos))
        assertEquals("今年 = 2024", listOf(1L, 2L), ids("今年", photos))
    }

    @Test
    fun aWholeDateQueryDoesNotAlsoSearchFileNames() {
        // 整串是一个日期时，它**只**当日期查：「今天」不该再去命中名叫「今天的云.jpg」的那张。
        val photos = listOf(
            photo(1, name = "今天的云.jpg", taken = "2023-01-01"),
            photo(2, name = "IMG_2.jpg", taken = "2024-09-22"),
        )
        assertEquals(listOf(2L), ids("今天", photos))
    }

    @Test
    fun dateInsideAMultiWordQueryStillApplies() {
        val photos = listOf(
            photo(1, name = "上海外滩.jpg", taken = "2024-09-22"),
            photo(2, name = "上海外滩.jpg", taken = "2024-01-01"),
        )
        assertEquals("日期那一段要 AND 进整条查询", listOf(1L), ids("上海 2024-09", photos))
    }

    @Test
    fun impossibleDateValuesAreNotDates() {
        // 13 月 / 32 日不该被当成日期模式（否则它们会悄悄匹配到"零张"而看起来像搜索坏了）。
        val photos = listOf(photo(1, name = "13月.jpg", taken = "2024-09-22"))
        assertEquals("13 月不是日期，退回文件名子串", listOf(1L), ids("13月", photos))
        assertTrue("32 日不是日期，也没人叫这个名字", ids("9月32日", photos).isEmpty())
    }

    // ── 命中原因 ────────────────────────────────────────────────────

    @Test
    fun eachDimensionReportsItsOwnReason() {
        // 每一维都用一条"只命中这一维"的查询，否则测到的永远是优先级最高的那一维。
        val byName = photo(1, name = "上海外滩.jpg")
        assertEquals(HitReason.FILE_NAME, query("上海", listOf(byName)).single().reason)

        val byAlbum = photo(2, name = "IMG_2.jpg", album = "上海", )
        assertEquals(HitReason.ALBUM, query("上海", listOf(byAlbum)).single().reason)

        val byCamera = photo(3, name = "IMG_3.jpg", make = "Canon", model = "Canon EOS R5")
        assertEquals(HitReason.CAMERA, query("canon", listOf(byCamera)).single().reason)

        val byLens = photo(4, name = "IMG_4.jpg", lens = "RF24-70mm F2.8")
        assertEquals(HitReason.LENS, query("rf24", listOf(byLens)).single().reason)

        // 类型那一维只能靠"类型的名字"命中，而这个名字不该同时出现在文件名里 ——
        // 截图正好满足：文件名是 Screenshot_…，类型的名字是「截图」。
        val byType = photo(5, name = "Screenshot_2024.png")
        assertEquals("截图", byType.kind.label)
        assertEquals(HitReason.TYPE, query("截图", listOf(byType)).single().reason)
    }

    @Test
    fun theHardestReasonWinsWhenSeveralHit() {
        // 文件名与相册名同时命中：文件名更硬（100 > 60）。
        val p = photo(1, name = "上海外滩.jpg", album = "上海")
        assertEquals(HitReason.FILE_NAME, query("上海", listOf(p)).single().reason)
    }

    @Test
    fun dateBeatsEveryTextDimension() {
        val p = photo(1, name = "2024.jpg", taken = "2024-09-22")
        assertEquals("输入年份时用户要的是那一天，不是名字里带那个数字的", HitReason.DATE, query("2024", listOf(p)).single().reason)
    }

    // ── 排序与上限 ───────────────────────────────────────────────────

    @Test
    fun newerPhotosComeFirstAtTheSameScore() {
        val photos = listOf(
            photo(2, name = "上海A.jpg", taken = "2024-01-01"),
            photo(1, name = "上海B.jpg", taken = "2020-01-01"),
        )
        // 两张都是"文件名命中"、都只有一次命中（同分）→ 按拍摄时间新的在前。
        // 这是与时间线同一套口径：同等相关时，用户先想看到的是最近拍的。
        assertEquals(listOf(2L, 1L), ids("上海", photos))
    }

    @Test
    fun contiguousHitOutranksACharByCharHit() {
        val photos = listOf(
            photo(1, name = "上外海.jpg", taken = "2024-01-01"),   // 两个字都在，但不连续
            photo(2, name = "上海.jpg", taken = "2020-01-01"),     // 连续（而且更旧）
        )
        // 连续命中那一条多拿 BONUS_CONTIGUOUS，所以即使它更旧也排在前面 ——
        // 这一条同时证明了"按字但保序"不会把整串连续的那张埋掉。
        assertEquals("整串连着出现的那张排前面", listOf(2L, 1L), ids("上海", photos))
    }

    @Test
    fun limitCapsTheResultButDoesNotChangeTheOrder() {
        val photos = (1L..10L).map { photo(it, name = "上海$it.jpg", taken = "2024-01-01") }
        val top3 = ids("上海", photos, limit = 3)
        assertEquals(3, top3.size)
        assertEquals("截断只砍尾巴，不改次序", ids("上海", photos).take(3), top3)
    }

    @Test
    fun zeroLimitAsksForNothing() {
        val photos = listOf(photo(1, name = "上海.jpg"))
        assertTrue(query("上海", photos, limit = 0).isEmpty())
    }

    // ── 空查询与无结果 ───────────────────────────────────────────────

    @Test
    fun anEmptyQueryReturnsNothingRatherThanEverything() {
        val photos = listOf(photo(1), photo(2))
        assertTrue("空串不是「全部」——界面上那是「没输入」这一态", query("", photos).isEmpty())
        assertTrue(query("   ", photos).isEmpty())
        assertTrue("全角空格也一样", query("\u3000\u3000", photos).isEmpty())
        assertTrue(query("\t\n", photos).isEmpty())
    }

    @Test
    fun noResultIsAnEmptyListNotAnException() {
        val photos = listOf(photo(1, name = "北京.jpg"))
        assertTrue(query("上海", photos).isEmpty())
        assertTrue("空索引也不许抛", query("上海", emptyList()).isEmpty())
    }

    @Test
    fun parseKeepsTheTrimmedRawForTheEmptyStateSentence() {
        assertEquals("上海", SearchQuery.parse("  上海  ").raw)
        assertTrue(SearchQuery.parse("   ").isEmpty)
    }

    @Test
    fun datesAreParsedAsDatesOnlyWhenTheWholeChunkIsOne() {
        assertNotNull(SearchQuery.parse("2024-09").dates.firstOrNull())
        assertNotNull(SearchQuery.parse("9月").dates.firstOrNull())
        assertNotNull(SearchQuery.parse("今天").dates.firstOrNull())
        // 「上海2024」整体不是日期：它被拆成「一段汉字」+「一段数字」两个词，
        // 而年份那一段**不当日期**（它只是文件名里的四个字符）。
        val mixed = SearchQuery.parse("上海2024")
        assertTrue("整串不是日期", mixed.dates.isEmpty())
        assertEquals("拆成 上海 / 2024 两段", 2, mixed.texts.size)
        assertEquals(listOf("上海", "2024"), SearchQuery.tokenize("上海2024"))
        // 反过来：`2024-09` 是日期，不会被拆成文本词
        assertTrue(SearchQuery.parse("2024-09").texts.isEmpty())
    }

    // ── 自检：坏例子必须变红 ─────────────────────────────────────────

    /**
     * 把判据逐条退回"错的写法"，断言它们会红。
     *
     * 这三条都是这个项目真踩过的形状：
     *  - `contains(q)` 不做分词 →「上海 外滩」整串查不到任何东西（用户打了两个词，结果为零）；
     *  - 中文不保序 →「上海」会命中「海上日出」；
     *  - 日期不做整串判定 →「今天」会命中名叫「今天的云」的照片。
     */
    @Test
    fun checkersActuallyCatchBadQueries() {
        val photos = listOf(
            photo(1, name = "上海外滩.jpg", taken = "2023-05-01"),
            photo(2, name = "海上日出.jpg", taken = "2022-05-01"),
            photo(3, name = "今天的云.jpg", taken = "2020-01-01"),
        )

        // 1. 不分词（整串子串）：多词必然零结果
        val naiveMulti = photos.filter { it.displayName.contains("上海 外滩", ignoreCase = true) }
        assertTrue("不分词的写法应当一条都搜不到", naiveMulti.isEmpty())

        // 2. 不保序（只判每个字都在）：会把「海上日出」也带出来
        val naiveUnordered = photos.filter { p -> "上海".all { it in p.displayName } }
        assertEquals("不保序的写法会把「海上日出」也带出来", 2, naiveUnordered.size)

        // 3. 日期不整串判定：「今天」命中文件名
        val naiveDate = photos.filter { it.displayName.contains("今天") }
        assertEquals("不整串判定时「今天」会命中文件名", listOf(3L), naiveDate.map { it.photoId })

        // 4. 逐字拆成独立 AND 条件（"按字"的错法）：日出那张也会中
        val naivePerChar = photos.filter { p -> "上海".all { c -> c in p.displayName } }
        assertEquals("逐字拆词的写法同样会误伤", 2, naivePerChar.size)

        // 真判据在这四处都不许犯同样的错
        assertEquals(listOf(1L), ids("上海 外滩", photos))
        assertEquals(listOf(1L), ids("上海", photos))
        assertTrue(ids("今天", photos).isEmpty())
    }

    @Test
    fun suggestionOrderIsModelsThenYearsThenAlbums() {
        val photos = listOf(
            photo(1, model = "iPhone 15 Pro", album = "Camera", taken = "2024-01-01"),
            photo(2, model = "iPhone 15 Pro", album = "Screenshots", taken = "2024-02-01"),
            photo(3, model = "Pixel 8", album = "Camera", taken = "2023-01-01"),
        )
        val out = SearchQuery.suggestions(photos, "", zone)
        assertEquals("型号（按票数）在前", "iPhone 15 Pro", out[0])
        assertTrue("年份排在型号之后", out.indexOf("2024") > out.indexOf("Pixel 8"))
        assertTrue("相册名排在年份之后", out.indexOf("Camera") > out.indexOf("2024"))
        assertEquals("同一个字符串只出现一次", out.size, out.distinct().size)
    }

    @Test
    fun suggestionsAllMatchTheTypedPrefix() {
        val photos = listOf(
            photo(1, model = "iPhone 15 Pro", album = "上海"),
            photo(2, model = "Pixel 8", album = "北京"),
        )
        val out = SearchQuery.suggestions(photos, "上海", zone)
        assertTrue("只给和输入有关的建议：$out", out.all { "上海" in it })
        assertTrue("别把无关的型号塞进来", out.none { "iPhone" in it })
    }
}
