package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Duplicates` 的纯逻辑判据。
 *
 * 这个文件存在的理由只有一个：**合并 = 删照片，误报是产品级事故**。
 * 所以每一条"该合并"的用例都配一条"差一点点就不该合并"的反例，
 * 而且反例是**逐个键**喂坏的（大小差 1 字节 / 时间差 121 秒 / 距离 7），
 * 不是笼统地"换个哈希"——只有逐键才能证明三键判据真的都在起作用。
 */
class DuplicatesTest {

    // ── 工具 ────────────────────────────────────────────────────────

    /** 一张合成的灰度图：按 [pattern] 填 9×8 网格。 */
    private fun grid(pattern: (x: Int, y: Int) -> Int): IntArray {
        val out = IntArray(DHASH_COLS * DHASH_ROWS)
        for (y in 0 until DHASH_ROWS) {
            for (x in 0 until DHASH_COLS) {
                out[y * DHASH_COLS + x] = pattern(x, y)
            }
        }
        return out
    }

    /** 横向从左到右变亮：dHash 应全 1。 */
    private fun risingGrid(): IntArray = grid { x, _ -> x * 20 }

    /** 横向从右到左变亮：dHash 应全 0。 */
    private fun fallingGrid(): IntArray = grid { x, _ -> (DHASH_COLS - 1 - x) * 20 }

    private fun photo(
        id: Long,
        size: Long = 1_000_000L,
        taken: Long = 1_700_000_000_000L,
        w: Int = 4000,
        h: Int = 3000,
        hash: Long? = 0L,
    ) = DuplicatePhoto(id, "IMG_$id.jpg", size, taken, w, h, hash)

    // ── dHash 本身 ──────────────────────────────────────────────────

    @Test
    fun dHashOfRisingGradientIsAllOnes() {
        val h = dHash(risingGrid())
        // 每行 8 次比较全部"右 > 左"→ 64 位全 1
        assertEquals(-1L, h)
    }

    @Test
    fun dHashOfFallingGradientIsAllZeros() {
        assertEquals(0L, dHash(fallingGrid()))
    }

    @Test
    fun dHashOfFlatImageIsAllZeros() {
        // 纯色：每行相邻相等，'>' 恒 false
        assertEquals(0L, dHash(grid { _, _ -> 128 }))
    }

    @Test
    fun dHashOfMirroredImageIsBitwiseComplement() {
        // 把横向梯度左右镜像 → 每次比较的方向全部反过来
        val rising = dHash(risingGrid())
        val falling = dHash(fallingGrid())
        assertEquals(rising.inv(), falling)
    }

    @Test
    fun dHashRejectsWrongGridSize() {
        var threw = false
        try {
            dHash(IntArray(10), w = 5, h = 2)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("尺寸不对必须抛，不能静默算出一个假哈希", threw)
    }

    @Test
    fun dHashRejectsShortInput() {
        var threw = false
        try {
            dHash(IntArray(71))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("灰度数组不够 72 必须抛", threw)
    }

    // ── 缩放 / 重压的鲁棒性（用合成扰动模拟）──────────────────────

    @Test
    fun dHashIsStableUnderSmallBrightnessShift() {
        // 整体亮度 +6：相邻关系不变 → 哈希应完全相同（距离 0）
        val base = dHash(grid { x, y -> x * 20 + y * 3 })
        val shifted = dHash(grid { x, y -> (x * 20 + y * 3 + 6).coerceAtMost(255) })
        assertEquals("亮度整体抬升不该改变差分哈希", 0, hamming(base, shifted))
    }

    @Test
    fun dHashIsStableUnderSmallNoise() {
        // 每格抖动 ±2（模拟 JPEG 重压的块效应）：距离必须 ≤ 3
        val base = dHash(grid { x, y -> x * 20 + y * 5 })
        val noisy = dHash(
            grid { x, y ->
                val v = x * 20 + y * 5
                // 确定性抖动（不用 Random，测试必须可复现）
                val jitter = if ((x + y) % 3 == 0) 2 else -2
                (v + jitter).coerceIn(0, 255)
            },
        )
        val d = hamming(base, noisy)
        assertTrue("轻微噪声后的距离应 ≤3，实际 $d", d <= 3)
    }

    @Test
    fun differentImagesAreFarApart() {
        // 一张横向渐变、一张纵向渐变：结构完全不同，距离应 > 10
        val horizontal = dHash(grid { x, _ -> x * 28 })
        val vertical = dHash(grid { _, y -> y * 30 })
        val d = hamming(horizontal, vertical)
        assertTrue("两张结构不同的图距离应 >10，实际 $d", d > 10)
    }

    // ── cluster：三键判据 ───────────────────────────────────────────

    @Test
    fun sameThreeKeysIsMerged() {
        val a = photo(1, hash = 0L)
        val b = photo(2, hash = 0L)
        val groups = cluster(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
        assertEquals(1.0f, groups[0].confidence, 0.001f)
    }

    @Test
    fun sizeDifferByOneByteIsNotMerged() {
        // 喂坏第 1 键：大小差 1 字节
        val a = photo(1, size = 1_000_000L, hash = 0L)
        val b = photo(2, size = 1_000_001L, hash = 0L)
        val groups = cluster(listOf(a, b))
        assertTrue("大小差 1 字节就不该合并（宁可漏报）", groups.isEmpty())
    }

    @Test
    fun timeDiffer121SecondsIsNotMerged() {
        // 喂坏第 2 键：时间差 121 秒
        val t = 1_700_000_000_000L
        val a = photo(1, taken = t, hash = 0L)
        val b = photo(2, taken = t + 121_000L, hash = 0L)
        assertTrue("时间差 121 秒不该合并", cluster(listOf(a, b)).isEmpty())
    }

    @Test
    fun timeDiffer120SecondsIsMerged() {
        // 边界内侧：正好 120 秒应当合并
        val t = 1_700_000_000_000L
        val a = photo(1, taken = t, hash = 0L)
        val b = photo(2, taken = t + 120_000L, hash = 0L)
        assertEquals("时间差正好 120 秒应当合并（窗口是闭区间）", 1, cluster(listOf(a, b)).size)
    }

    @Test
    fun hashDistance7IsNotMerged() {
        // 喂坏第 3 键：距离 7（阈值 6）
        val a = photo(1, hash = 0L)
        // 低 7 位置 1 → 距离正好 7
        val b = photo(2, hash = 0b111_1111L)
        assertEquals(7, hamming(0L, 0b111_1111L))
        assertTrue("距离 7 超过阈值 6，不该合并", cluster(listOf(a, b)).isEmpty())
    }

    @Test
    fun hashDistance6IsMerged() {
        // 边界内侧：距离正好 6 应当合并
        val a = photo(1, hash = 0L)
        val b = photo(2, hash = 0b11_1111L)
        assertEquals(6, hamming(0L, 0b11_1111L))
        assertEquals("距离正好 6 应当合并（阈值闭区间）", 1, cluster(listOf(a, b)).size)
    }

    @Test
    fun nullHashNeverMerges() {
        // 解码失败的图（hash=null）绝不能因为大小/时间相同就合并
        val a = photo(1, hash = null)
        val b = photo(2, hash = null)
        assertTrue("hash 为 null 时不许合并", cluster(listOf(a, b)).isEmpty())
    }

    @Test
    fun singleItemNeverFormsGroup() {
        assertTrue(cluster(listOf(photo(1))).isEmpty())
        assertTrue(cluster(emptyList()).isEmpty())
    }

    @Test
    fun unionIsTransitive() {
        // A~B 距离 3、B~C 距离 3、A~C 距离 6（都在阈值内）→ 必须并成一组，不能拆两对
        val a = photo(1, hash = 0L)
        val b = photo(2, hash = 0b111L) // 距 A = 3
        val c = photo(3, hash = 0b111_111L) // 距 B = 3，距 A = 6
        assertEquals(3, hamming(0L, 0b111L))
        assertEquals(3, hamming(0b111L, 0b111_111L))
        assertEquals(6, hamming(0L, 0b111_111L))
        val groups = cluster(listOf(a, b, c))
        assertEquals("传递性：三张必须在一组", 1, groups.size)
        assertEquals(3, groups[0].items.size)
    }

    @Test
    fun separateSizeBucketsStaySeparate() {
        // 两组各自成立，但两组之间大小不同 → 必须是 2 个组，不是 1 个
        val g1a = photo(1, size = 1000, hash = 0L)
        val g1b = photo(2, size = 1000, hash = 0L)
        val g2a = photo(3, size = 2000, hash = 0L)
        val g2b = photo(4, size = 2000, hash = 0L)
        val groups = cluster(listOf(g1a, g1b, g2a, g2b))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.items.size == 2 })
    }

    // ── pickKeep ────────────────────────────────────────────────────

    @Test
    fun highestResolutionWins() {
        val small = photo(1, w = 1000, h = 1000, size = 900_000)
        val large = photo(2, w = 4000, h = 3000, size = 800_000)
        assertEquals(2L, pickKeep(listOf(small, large), KeepRule.HIGHEST_RESOLUTION))
    }

    @Test
    fun largestFileWins() {
        val a = photo(1, size = 900_000)
        val b = photo(2, size = 2_000_000)
        assertEquals(2L, pickKeep(listOf(a, b), KeepRule.LARGEST_FILE))
    }

    @Test
    fun earliestWins() {
        val t = 1_700_000_000_000L
        val later = photo(1, taken = t + 60_000)
        val earlier = photo(2, taken = t)
        assertEquals(2L, pickKeep(listOf(later, earlier), KeepRule.EARLIEST))
    }

    @Test
    fun pickKeepIsDeterministicWhenAllTied() {
        // 分辨率/大小/时间全一样 → 必须稳定给同一个答案（按 id 小的），
        // 否则用户第二次进来看到"建议保留"换了一张，就再也不信这个工具了
        val a = photo(7, taken = 1_700_000_000_000L)
        val b = photo(3, taken = 1_700_000_000_000L)
        val first = pickKeep(listOf(a, b), KeepRule.HIGHEST_RESOLUTION)
        val second = pickKeep(listOf(b, a), KeepRule.HIGHEST_RESOLUTION)
        assertEquals("同一批输入的保留建议必须与顺序无关", first, second)
        assertEquals(3L, first)
    }

    @Test
    fun pickKeepOfEmptyGroupIsZero() {
        assertEquals(0L, pickKeep(emptyList()))
    }

    @Test
    fun earliestRulePutsUnknownDateLast() {
        // takenAtMillis <= 0 表示日期不可知，不该被当成"1970 年最早"而选为保留
        val unknown = photo(1, taken = 0L)
        val known = photo(2, taken = 1_700_000_000_000L)
        assertEquals(2L, pickKeep(listOf(unknown, known), KeepRule.EARLIEST))
    }

    // ── 组的产物 ────────────────────────────────────────────────────

    @Test
    fun keepIsAlwaysInsideItems() {
        val groups = cluster(listOf(photo(1, hash = 0L), photo(2, hash = 0L)))
        for (g in groups) {
            assertTrue("keep=${g.keep} 不在 items 里", g.items.any { it.id == g.keep })
        }
    }

    @Test
    fun keepIsFirstInItems() {
        val groups = cluster(listOf(photo(9, w = 800, h = 600), photo(1, w = 4000, h = 3000)))
        assertEquals(1, groups.size)
        assertEquals("保留的那张要排在最前（界面直接拿 items.first() 显示）", groups[0].keep, groups[0].items.first().id)
    }

    @Test
    fun reclaimableBytesExcludeKept() {
        val a = photo(1, size = 1000, hash = 0L)
        val b = photo(2, size = 1000, hash = 0L)
        val groups = cluster(listOf(a, b))
        assertEquals("可省 = 总数 - 保留那 1 张", 1000L, groups[0].reclaimableBytes)
    }

    @Test
    fun groupsSortedByReclaimableDesc() {
        // 3 张的一组能省 2MB；2 张的一组能省 1MB → 大的那组排前面
        val big = listOf(
            photo(1, size = 1_000_000, hash = 0L),
            photo(2, size = 1_000_000, hash = 0L),
            photo(3, size = 1_000_000, hash = 0L),
        )
        val small = listOf(
            photo(4, size = 1_000_000, hash = 0L),
            photo(5, size = 1_000_000, hash = 0L),
        )
        // 两组同 size 会并成一组……所以给第二组换 size
        val groups = cluster(
            big + small.map { it.copy(sizeBytes = 500_000) }.map { it.copy(sizeBytes = 1_000_000) }
                .mapIndexed { i, p -> p.copy(sizeBytes = 2_000_000L) }
                .map { it.copy(id = it.id + 100) },
        )
        // 断言：排序键单调不增
        for (i in 0 until groups.size - 1) {
            assertTrue(
                "组必须按可省字节从大到小",
                groups[i].reclaimableBytes >= groups[i + 1].reclaimableBytes,
            )
        }
    }

    @Test
    fun reasonTextIsChineseAndMentionsCount() {
        val groups = cluster(listOf(photo(1, hash = 0L), photo(2, hash = 0L)))
        val text = groups[0].reasonText
        assertTrue("理由必须是中文：$text", text.any { it.code in 0x4E00..0x9FFF })
        assertTrue("理由要说几张：$text", text.contains("2"))
    }

    // ── 自检：喂坏例子必须变红 ──────────────────────────────────────

    @Test
    fun selfCheckTightThresholdBreaksMerging() {
        // 把阈值收到 -1（等价于"永不合并"）→ 上一条会红的输入必须变成空
        val a = photo(1, hash = 0L)
        val b = photo(2, hash = 0L)
        assertEquals("对照组：默认阈值下应合并", 1, cluster(listOf(a, b)).size)
        assertEquals(
            "自检失败：阈值收到 -1 竟然还合并了，说明 hashDistance 没被用上",
            0,
            cluster(listOf(a, b), hashDistance = -1).size,
        )
    }

    @Test
    fun selfCheckZeroTimeWindowBreaksMerging() {
        val t = 1_700_000_000_000L
        val a = photo(1, taken = t, hash = 0L)
        val b = photo(2, taken = t + 1_000L, hash = 0L)
        assertEquals("对照组：120 秒窗口下应合并", 1, cluster(listOf(a, b)).size)
        assertEquals(
            "自检失败：窗口收到 0 竟然还合并了，说明 timeWindowMs 没被用上",
            0,
            cluster(listOf(a, b), timeWindowMs = 0L).size,
        )
    }
}
