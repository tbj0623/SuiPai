package com.suipai.app.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「时段」那张表的不变量（`GalleryScreen.kt` 里的 `HourBuckets`）。
 *
 * 为什么要钉：这一格是**循环**的（点一下换一个），而分桶用的是 `first { hour in it.hours }`
 * —— 两件事都依赖表的**顺序与完整性**，写错不会崩、也不会报错，只会悄悄筛错：
 *  - 「正午」那行如果排到「白天」后面，11~13 点就永远归到白天，正午那一格变成空的；
 *  - 桶数与循环的 `% 7` 不匹配，就会有一档**永远点不到**（多加第七个桶而不改循环就是这一种）；
 *  - 标签与编号对不上，抽屉里写着「傍晚」而筛出夜晚的照片 —— 两处 `when` 分抄时最容易出。
 */
class HourBucketTest {

    internal companion object {
        /** 表 → 每个小时的归属。抽出来是为了能把"坏表"喂进来验证判据真的会红。 */
        internal fun mappingOf(buckets: List<HourBucket>): Map<Int, Int> =
            (0..23).associateWith { h -> buckets.first { h in it.hours }.id }

        internal val EXPECTED = mapOf(
            0 to 6, 1 to 6, 2 to 6, 3 to 6, 4 to 6,
            5 to 1, 6 to 1, 7 to 1, 8 to 1,
            9 to 2, 10 to 2,
            11 to 5, 12 to 5, 13 to 5,
            14 to 2, 15 to 2, 16 to 2,
            17 to 3, 18 to 3, 19 to 3,
            20 to 4, 21 to 4, 22 to 4, 23 to 4,
        )
    }

    @Test
    fun everyHourLandsWhereTheLabelsSay() {
        assertEquals(EXPECTED, mappingOf(HourBuckets))
    }

    @Test
    fun bucketsCoverTheDayExactlyOnce() {
        val hits = IntArray(24)
        HourBuckets.forEach { b ->
            b.hours.forEach { h -> hits[h] += 1 }
        }
        // 区间本身可以重叠（白天 9..16 与正午 11..13 就是重叠的），
        // 但**归属**必须唯一 —— 上面那条按 first{} 算的就是归属，这条查的是区间写歪（漏了某小时）。
        assertEquals("有哪个小时没人管：" + hits.mapIndexed { i, v -> if (v == 0) i else null }
                .filterNotNull(), 24, hits.count { it >= 1 })
        assertEquals("六个桶的编号必须是 1..6 各一次", listOf(1, 2, 3, 4, 5, 6), HourBuckets.map { it.id }.sorted())
        assertEquals("标签不许重名", HourBuckets.map { it.label }.distinct().size, HourBuckets.size)
        assertTrue("标签不许空白", HourBuckets.all { it.label.isNotBlank() })
    }

    @Test
    fun cycleLengthMatchesTheTable() {
        // 抽屉里那格是 `hourFilter = (hourFilter + 1) % 7`：0 = 不限 + 六个桶。
        // 加第七个桶却忘了改这个模数，那一档就永远点不到 —— 所以两边钉在一起。
        assertEquals(6, HourBuckets.size)
        assertEquals(HourBuckets.size + 1, 7)
        (1..HourBuckets.size).forEach { id ->
            assertEquals("第 $id 档的标签读不出来", HourBuckets.first { it.id == id }.label, hourBucketLabel(id))
        }
    }

    @Test
    fun guardActuallyCatchesAReorderedTable() {
        // 脏样本：把「正午」挪到「白天」后面 —— 归属必须变，否则上面那条断言等于没写。
        val dirty = listOf(
            HourBucket(6, "午夜", 0..4),
            HourBucket(1, "清晨", 5..8),
            HourBucket(2, "白天", 9..16),
            HourBucket(5, "正午", 11..13),
            HourBucket(3, "傍晚", 17..19),
            HourBucket(4, "夜晚", 20..23),
        )
        val m = mappingOf(dirty)
        assertEquals("换了顺序之后 12 点应当被「白天」吃掉（这一条不成立就说明判据是摆设）", 2, m.getValue(12))
        assertTrue(EXPECTED != m)
        assertEquals(EXPECTED, mappingOf(HourBuckets))
    }
}
