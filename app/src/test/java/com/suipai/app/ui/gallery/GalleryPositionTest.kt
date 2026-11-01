package com.suipai.app.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画廊滚动位置浮标的三张表：分组 → 行号 → 第几组 → 浮标该说什么。
 *
 * 为什么单独钉这一支：网格里的行是「1 行标题 + N 张照片」交替排出来的，
 * 而浮标读的是 `visibleItemsInfo.firstOrNull()?.index`。这两套编号只要有一处算歪，
 * 表现不是崩溃，是**指错日子**——浮标说「9月21日」，屏幕上那屏其实是 23 号的。
 * 这种错在真机上要靠人工逐屏核对才看得见，所以这里用一张独立摊平的表逐行对账。
 *
 * 关键设计：期望表用 `repeat` 直接摊平（不累加行号），被测的 `sectionStarts` 用的是
 * 累加。两条算法任何一条漂了都会对不上，而不是"一起错得很自洽"。
 * 见 `ANDROID-VERIFY.md` §41。
 */
class GalleryPositionTest {

    companion object {
        /** 把「每组 1 行标题 + N 张照片」独立摊平成 行号 → 第几组。 */
        fun ownerTable(sizes: List<Int>): List<Int> {
            val rows = ArrayList<Int>()
            sizes.forEachIndexed { i, n -> repeat(n + 1) { rows.add(i) } }
            return rows
        }

        /** 逐行核对 + 两端越界。返回所有对不上的地方。 */
        fun mismatches(sizes: List<Int>): List<String> {
            val starts = sectionStarts(sizes)
            val owners = ownerTable(sizes)
            val bad = ArrayList<String>()
            if (starts.size != sizes.size) bad.add("有几组没有起始行号：${starts.size}/${sizes.size}")
            owners.forEachIndexed { index, want ->
                val got = sectionAt(starts, index)
                if (got != want) bad.add("行 $index 属于第 $want 组，sectionAt 说第 $got 组（$sizes）")
            }
            if (starts.isEmpty()) {
                if (sectionAt(starts, 0) != -1) bad.add("空分组表要回 -1：一组都没有，没有什么「第 0 组」")
            } else {
                if (sectionAt(starts, -7) != 0) bad.add("负行号要夹到第 0 组")
                if (sectionAt(starts, owners.size + 9) != sizes.lastIndex) bad.add("越过总行数的行号要夹到最后一组")
            }
            return bad
        }
    }

    @Test
    fun startsFollowTheHeaderPlusPhotosStride() {
        assertEquals("空库", listOf<Int>(), sectionStarts(emptyList()).toList())
        assertEquals("一组", listOf(0), sectionStarts(listOf(5)).toList())
        assertEquals("空组也占一行标题", listOf(0, 1, 2), sectionStarts(listOf(0, 0, 5)).toList())
        assertEquals("三组", listOf(0, 4, 6), sectionStarts(listOf(3, 1, 4)).toList())
        assertEquals("单组内的跨度", 4, sectionStarts(listOf(3, 1, 4))[1] - sectionStarts(listOf(3, 1, 4))[0])
    }

    @Test
    fun everyRowMapsToItsOwnSection() {
        val cases = listOf(
            listOf(3, 1, 4),
            listOf(1),
            listOf(0, 0, 5),
            listOf(1, 0, 1, 0, 1),
            List(200) { 7 },
            List(60) { (it * 13) % 9 },
            listOf(5000),
        )
        val problems = cases.flatMap { mismatches(it) }
        assertTrue("逐行对账失败：\n" + problems.take(12).joinToString("\n"), problems.isEmpty())
        val rowsChecked = cases.sumOf { ownerTable(it).size }
        assertTrue("这几例一共才核对 $rowsChecked 行，地板是 3000 行", rowsChecked >= 3_000)
    }

    /**
     * 浮标的出现规则：本组标题还在屏幕上时不露（那是重复），滚进本组中段才露，
     * 下一组标题一到就收起 —— 而收起那一帧留的还是**上一组**的名字，
     * 否则淡出的 120 ms 里闪出来的是下一天。
     */
    @Test
    fun chipShowsTheSectionWhoseHeaderScrolledOff() {
        val starts = sectionStarts(listOf(3, 1, 4))
        // starts = [0, 4, 6]
        val expected = mapOf(
            -1 to GalleryPosition(0, false),   // 还没有第一行（空态、首帧）
            0 to GalleryPosition(0, false),   // 第 0 组标题在顶上
            1 to GalleryPosition(0, true),
            3 to GalleryPosition(0, true),
            4 to GalleryPosition(0, false),   // 下一组标题到了：收起，留住 0
            5 to GalleryPosition(1, true),
            6 to GalleryPosition(1, false),   // 再下一组：收起，留住 1
            99 to GalleryPosition(2, true),   // 越界（末尾「加载更多」那行）夹到最后一组
        )
        expected.forEach { (row, want) ->
            assertEquals("第一行是行 $row 时", want, galleryPosition(starts, row))
        }
    }

    @Test
    fun emptyLibraryHasNoChip() {
        assertEquals(GalleryPosition(0, false), galleryPosition(sectionStarts(emptyList()), 0))
        assertEquals(GalleryPosition(0, false), galleryPosition(sectionStarts(listOf(4)), 0))
    }
}
