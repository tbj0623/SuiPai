package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StorageInsight` 的纯逻辑判据。
 *
 * 这一屏只报告不执行，所以判据的重点不是"删得对不对"，而是**说得准不准**：
 * 分档边界、分桶不重不漏、TopN、可省估算、人话格式化。
 * 每类判据都配一条"喂坏例子必须变红"的自检——没有验过自己会红的守卫不算守卫。
 */
class StorageInsightTest {

    private fun item(
        id: Long,
        size: Long = 2_000_000L,
        w: Int = 4000,
        h: Int = 3000,
        name: String = "IMG_$id.jpg",
        path: String = "DCIM/Camera/",
    ) = StorageItem(id, name, size, w, h, path, takenAtMillis = 1_700_000_000_000L)

    // ── classify ────────────────────────────────────────────────────

    @Test
    fun classifyUsesPixelCountNotBytes() {
        // 一张 4000×3000（1200 万像素）但只有 200KB 的：是 LARGE，不是 SMALL。
        // 分档要反映"这张图本身多大"，不是"压得多狠"。
        assertEquals(SizeClass.LARGE, classify(200_000L, 4000, 3000))
    }

    @Test
    fun classifyBoundariesAreInclusiveLowerBounds() {
        // 每档下界正好命中该档（闭区间的下界），这样没有"空洞"
        assertEquals(SizeClass.TINY, classify(1000L, 100, 100))
        assertEquals(SizeClass.SMALL, classify(100_000L, 600, 500)) // 300000 px 正好下界
        assertEquals(SizeClass.NORMAL, classify(500_000L, 1200, 1000)) // 1200000
        assertEquals(SizeClass.MEDIUM, classify(800_000L, 2000, 1500)) // 3000000
        // 3264×2448 = 7,990,272 px < 8,000,000 → 是 MEDIUM，不是 LARGE。
        // （第一版这里写错了：手算 799 万当成了 800 万。）
        assertEquals(SizeClass.MEDIUM, classify(1_000_000L, 3264, 2448))
        // 真正的 LARGE 要用超过 800 万像素的尺寸：4000×3000 = 1200 万
        assertEquals(SizeClass.LARGE, classify(1_000_000L, 4000, 3000))
        // 5200×3800 = 19,760,000 < 20,000,000 → 仍是 LARGE。
        // 要进 HUGE 必须超过 2000 万：6000×4000 = 2400 万。
        assertEquals(SizeClass.LARGE, classify(5_000_000L, 5200, 3800))
        assertEquals(SizeClass.HUGE, classify(5_000_000L, 6000, 4000))
    }

    @Test
    fun classifyKnownValuesMatchTheirFloor() {
        // 逐个档位断言"它的下界确实把它自己分进去"。
        // 注意：sqrt 取整会落到下界下方（547×548=299756 < 300000），所以要向上取整，
        // 再加 1 保证严格 ≥ 下界 —— 第一版就是没处理这一步才红。
        for (c in SizeClass.entries) {
            if (c.minPixels == 0L) continue
            val side = kotlin.math.ceil(kotlin.math.sqrt(c.minPixels.toDouble())).toInt() + 1
            val px = side.toLong() * side.toLong()
            assertTrue("构图的 ${side}×$side=$px 必须 ≥ 下界 ${c.minPixels}", px >= c.minPixels)
            val got = classify(1_000_000L, side, side)
            assertTrue(
                "档 ${c.label} 的下界 ${c.minPixels} 用 ${side}×${side} 应当分进 $c 或更高，实得 $got",
                got.ordinal >= c.ordinal,
            )
        }
    }

    @Test
    fun classifyZeroDimensionsFallsBackToBytes() {
        // 尺寸未知时不能塞进 TINY 糊弄——按字节给个诚实的档
        assertEquals(SizeClass.HUGE, classify(20_000_000L, 0, 0))
        assertEquals(SizeClass.TINY, classify(10_000L, 0, 0))
    }

    @Test
    fun classifyNegativeDimensionsIsSafe() {
        // 脏数据（-1）不能算出负数像素再崩
        assertEquals(SizeClass.TINY, classify(10_000L, -1, -1))
    }

    @Test
    fun selfCheckClassifyWouldGoRedIfPixelUsedWrong() {
        // 自检：如果分档误用字节数，这条会红
        val byBytesWouldBeSmall = 200_000L
        assertEquals(
            "自检失败：4000×3000 的图被按字节分成了 SMALL，说明 classify 用了 sizeBytes 而不是像素",
            SizeClass.LARGE,
            classify(byBytesWouldBeSmall, 4000, 3000),
        )
    }

    // ── histogram ───────────────────────────────────────────────────

    @Test
    fun histogramHasSixBucketsAndCoversEverything() {
        val items = listOf(
            item(1, size = 100_000L),
            item(2, size = 1_000_000L),
            item(3, size = 3_000_000L),
            item(4, size = 7_000_000L),
            item(5, size = 15_000_000L),
            item(6, size = 30_000_000L),
        )
        val buckets = histogram(items)
        assertEquals(6, buckets.size)
        assertEquals("不重不漏：总数必须等于输入", items.size, buckets.sumOf { it.count })
        assertEquals(
            "不重不漏：总字节必须等于输入",
            items.sumOf { it.sizeBytes },
            buckets.sumOf { it.totalBytes },
        )
        assertEquals("最后一桶必须开口向上", Long.MAX_VALUE, buckets.last().maxBytesExclusive)
        assertEquals("第一桶从 0 开始", 0L, buckets.first().minBytes)
    }

    @Test
    fun histogramBoundariesAreHalfOpen() {
        // 512 KB 正好落在第二桶（下界含），不是第一桶
        val b = histogram(listOf(item(1, size = 512L * 1024)))
        assertEquals(0, b[0].count)
        assertEquals(1, b[1].count)
    }

    @Test
    fun histogramOfEmptyIsAllZero() {
        val buckets = histogram(emptyList())
        assertEquals(6, buckets.size)
        assertTrue(buckets.all { it.count == 0 && it.totalBytes == 0L })
    }

    @Test
    fun histogramLabelsAreHumanReadable() {
        val buckets = histogram(listOf(item(1)))
        // 每个桶的标签都必须带单位 —— 否则「0–0.5」看不出是 MB 还是 KB。
        // （第一版实现里 humanMb 把 <1MB 一律输出 "0"，标签成了「0–2」，这条就是那时候红的。）
        for (b in buckets.dropLast(1)) {
            assertTrue(
                "中间桶标签必须带单位：${b.label}",
                b.label.contains("MB") || b.label.contains("KB") || b.label.contains("B"),
            )
        }
        assertTrue("最后一桶要说「以上」：${buckets.last().label}", buckets.last().label.contains("以上"))
        // 具体抽查两档
        assertEquals("0–512 KB", buckets[0].label)
        assertEquals("512 KB–2 MB", buckets[1].label)
        assertEquals("20 MB 以上", buckets.last().label)
    }

    @Test
    fun selfCheckHistogramWouldGoRedIfLastBucketClosed() {
        val buckets = histogram(listOf(item(1, size = 999_999_999L)))
        assertEquals(
            "自检失败：超大文件没落进最后一桶，说明分桶上界写错了",
            1,
            buckets.last().count,
        )
    }

    // ── topN ────────────────────────────────────────────────────────

    @Test
    fun topNReturnsLargestFirst() {
        val items = listOf(
            item(1, size = 100L),
            item(2, size = 900L),
            item(3, size = 500L),
        )
        val top = topN(items, 2)
        assertEquals(2, top.size)
        assertEquals(2L, top[0].id)
        assertEquals(3L, top[1].id)
    }

    @Test
    fun topNZeroOrNegativeIsEmpty() {
        val items = listOf(item(1))
        assertTrue(topN(items, 0).isEmpty())
        assertTrue(topN(items, -5).isEmpty())
    }

    @Test
    fun topNMoreThanAvailableReturnsAll() {
        val items = listOf(item(1), item(2))
        assertEquals(2, topN(items, 100).size)
    }

    @Test
    fun selfCheckTopNWouldGoRedIfAscending() {
        val top = topN(listOf(item(1, size = 1L), item(2, size = 9L)), 1)
        assertEquals("自检失败：topN 返回了最小的那个", 2L, top[0].id)
    }

    // ── storageKindOf ───────────────────────────────────────────────

    @Test
    fun screenshotDetectedFromPath() {
        assertEquals(StorageKind.SCREENSHOT, storageKindOf("Screenshot_1.png", "Pictures/Screenshots/"))
    }

    @Test
    fun screenshotDetectedFromChinesePath() {
        assertEquals(StorageKind.SCREENSHOT, storageKindOf("截图_1.png", "Pictures/截图/"))
    }

    @Test
    fun suipaiOriginalIsSingleUnderscore() {
        // 原片：SuiPai_<时间戳>.jpg，只有 SuiPai_ 这一个下划线
        assertEquals(StorageKind.SUIPAI_ORIGINAL, storageKindOf("SuiPai_1790023855356.jpg", "Pictures/SuiPai/"))
    }

    @Test
    fun suipaiDerivativeIsMultipleUnderscores() {
        // 加工件：SuiPai_一瞬_暖调_70_<ts>.jpg
        assertEquals(
            StorageKind.SUIPAI_DERIVATIVE,
            storageKindOf("SuiPai_一瞬_暖调_70_1790023855356.jpg", "Pictures/SuiPai/"),
        )
        assertEquals(
            StorageKind.SUIPAI_DERIVATIVE,
            storageKindOf("SuiPai_副本_1790023855356.jpg", "Pictures/SuiPai/"),
        )
    }

    @Test
    fun downloadAndCameraDetected() {
        assertEquals(StorageKind.DOWNLOAD, storageKindOf("a.jpg", "Download/"))
        assertEquals(StorageKind.CAMERA, storageKindOf("IMG_1.jpg", "DCIM/Camera/"))
        assertEquals(StorageKind.OTHER, storageKindOf("a.jpg", "Documents/"))
    }

    @Test
    fun screenshotBeatsSuipaiPrefix() {
        // 顺序即优先级：截图判据在最前
        assertEquals(
            StorageKind.SCREENSHOT,
            storageKindOf("SuiPai_1.jpg", "Pictures/Screenshots/"),
        )
    }

    @Test
    fun selfCheckKindWouldGoRedIfUnderscoreCountIgnored() {
        // 自检：如果把所有 SuiPai_ 都当原片，这条会红
        assertEquals(
            "自检失败：加工件被当成了原片（数下划线的判据没生效）",
            StorageKind.SUIPAI_DERIVATIVE,
            storageKindOf("SuiPai_一瞬_x_1.jpg", "Pictures/SuiPai/"),
        )
    }

    // ── reclaimable ─────────────────────────────────────────────────

    @Test
    fun reclaimableGroupsByKindAndSums() {
        val items = listOf(
            item(1, size = 1000L, name = "SuiPai_一瞬_a_1.jpg"),
            item(2, size = 2000L, name = "SuiPai_副本_2.jpg"),
            item(3, size = 500L, name = "Screenshot_3.png", path = "Pictures/Screenshots/"),
        )
        val out = reclaimable(items)
        val derivative = out.first { it.kind == StorageKind.SUIPAI_DERIVATIVE }
        assertEquals(2, derivative.count)
        assertEquals(3000L, derivative.totalBytes)
        val shot = out.first { it.kind == StorageKind.SCREENSHOT }
        assertEquals(1, shot.count)
        assertEquals(500L, shot.totalBytes)
    }

    @Test
    fun reclaimableIsSortedByBytesDesc() {
        val items = listOf(
            item(1, size = 100L, name = "SuiPai_一瞬_a_1.jpg"),
            item(2, size = 9000L, name = "Screenshot_2.png", path = "Pictures/Screenshots/"),
        )
        val out = reclaimable(items)
        for (i in 0 until out.size - 1) {
            assertTrue(
                "可省估算必须按字节从大到小",
                out[i].totalBytes >= out[i + 1].totalBytes,
            )
        }
    }

    @Test
    fun reclaimableNoteIsHonestAboutDerivatives() {
        val out = reclaimable(listOf(item(1, name = "SuiPai_一瞬_a_1.jpg")))
        val note = out.first().note
        assertTrue("加工件的说明必须讲清「能重新生成」：$note", note.contains("重新生成"))
    }

    @Test
    fun reclaimableNoteDoesNotPromiseRecoveryForScreenshots() {
        val out = reclaimable(listOf(item(1, name = "Screenshot_1.png", path = "Pictures/Screenshots/")))
        val note = out.first().note
        assertTrue("截图不许暗示能恢复：$note", !note.contains("恢复"))
    }

    @Test
    fun selfCheckReclaimableWouldGoRedIfUnsorted() {
        val out = reclaimable(
            listOf(
                item(1, size = 10L, name = "Screenshot_1.png", path = "Pictures/Screenshots/"),
                item(2, size = 999L, name = "SuiPai_一瞬_a_1.jpg"),
            ),
        )
        assertEquals("自检失败：最大的一类没排第一", StorageKind.SUIPAI_DERIVATIVE, out.first().kind)
    }

    // ── humanBytes / formatCount ────────────────────────────────────

    @Test
    fun humanBytesFormatsUnits() {
        assertEquals("0 B", humanBytes(0))
        assertEquals("512 B", humanBytes(512))
        assertEquals("1 KB", humanBytes(1024))
        assertEquals("1.5 KB", humanBytes(1536))
        assertEquals("1 MB", humanBytes(1024L * 1024))
        assertEquals("38.2 GB", humanBytes((38.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun humanBytesNeverNegative() {
        assertEquals("0 B", humanBytes(-1))
        assertEquals("0 B", humanBytes(Long.MIN_VALUE))
    }

    // ── summarize ───────────────────────────────────────────────────

    @Test
    fun summarizeEmptyIsEmptySingleton() {
        val s = summarize(emptyList())
        assertEquals(StorageSummary.EMPTY, s)
    }

    @Test
    fun summarizeCountsAndTotalsMatchInput() {
        val items = listOf(item(1, size = 1000L), item(2, size = 2000L))
        val s = summarize(items)
        assertEquals(2, s.count)
        assertEquals(3000L, s.totalBytes)
        assertEquals("byClass 必须覆盖全部 6 档", SizeClass.entries.size, s.byClass.size)
        assertEquals("byClass 张数之和必须等于总数", 2, s.byClass.sumOf { it.second })
    }

    @Test
    fun summarizeTopCountIsRespected() {
        val items = (1..30).map { item(it.toLong(), size = it * 1000L) }
        val s = summarize(items, topCount = 5)
        assertEquals(5, s.largest.size)
        assertEquals("最大的一定是第 30 个", 30L, s.largest.first().id)
    }

    @Test
    fun summarizeBucketsCoverAll() {
        val items = (1..20).map { item(it.toLong(), size = it * 1_000_000L) }
        val s = summarize(items)
        assertEquals(20, s.buckets.sumOf { it.count })
    }

    // ── Bucket 标签 ─────────────────────────────────────────────────

    @Test
    fun bucketLabelForLastIsOpenEnded() {
        val b = Bucket(20L * 1024 * 1024, Long.MAX_VALUE, 3, 90L * 1024 * 1024)
        assertTrue("最后一桶标签必须说「以上」：${b.label}", b.label.endsWith("以上"))
    }

    @Test
    fun bucketLabelForMiddleIsRange() {
        val b = Bucket(2L * 1024 * 1024, 5L * 1024 * 1024, 1, 3L * 1024 * 1024)
        assertTrue("中间桶要说区间：${b.label}", b.label.contains("–"))
    }

    // ── byKind 与 byClass 不漂移（本轮新增）──────────────────────────

    /**
     * 防的 bug：`StorageSummary` 有两个并列的切法 —— [byClass]（尺寸档）与
     * [byKind]（来源档）。它们都是"枚举全量 + 张数"，所以两者之和必须都等于
     * [count]。哪一天加第三个维度、或者改判据时漏算一边，这条会立刻红。
     *
     * 真实事故背景：宿主曾拿 `byClass` 去对 `StorageKind`（类型不同、
     * 编译器报 Incompatible enums），界面表现是"点了分类没反应"。
     * 根因就是缺 `byKind` 这一格；补上之后必须有一条钉住它与 `count` 对得上。
     */
    @Test
    fun byKindAndByClassBothSumToCount() {
        val items = (1..30).map { i ->
            item(
                id = i.toLong(),
                size = i * 700_000L,
                w = 100 * i,
                h = 100 * i,
            )
        }
        val s = summarize(items)
        assertEquals("byClass 之和必须等于 count", s.count, s.byClass.sumOf { it.second })
        assertEquals("byKind 之和必须等于 count", s.count, s.byKind.sumOf { it.second })
        assertEquals(
            "两种切法的总数必须相同（否则界面两个数字会对不上）",
            s.byClass.sumOf { it.second },
            s.byKind.sumOf { it.second },
        )
        // 两个列表都必须是枚举全量（不是"只有非零项"）—— 否则界面上会缺一格，
        // 而缺的那一格正好是"0 张"，用户看不到"这一类你一张都没有"。
        assertEquals("byClass 要覆盖全部 SizeClass", SizeClass.entries.size, s.byClass.size)
        assertEquals("byKind 要覆盖全部 StorageKind", StorageKind.entries.size, s.byKind.size)
    }

    /** 防的 bug：空库时 `EMPTY` 的 `byKind` 是 `emptyList()`，不满足"枚举全量"，
     *  而 `summarize(emptyList())` 返回的正是它 —— 界面按 `byKind` 渲染会出现空列表。
     *  这一条钉住"空库这一格也要能安全渲染"。 */
    @Test
    fun emptySummaryHasNoKindRowsButIsSafe() {
        val s = summarize(emptyList())
        assertEquals(0, s.count)
        assertEquals(0, s.byKind.sumOf { it.second })
        assertEquals(StorageSummary.EMPTY, s)
    }
}
