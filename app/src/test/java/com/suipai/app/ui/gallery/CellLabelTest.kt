package com.suipai.app.ui.gallery

import com.suipai.app.data.AlbumItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏那两句的判据：`photoCellLabel`（一格照片）与 `albumCellSub`/`albumCellLabel`（一册相册）。
 *
 * 三个都是纯函数，钉的是同一件事：
 * 1. 界面上看得见什么就说什么，顺序固定；
 * 2. 缺的信息不许编（没解出尺寸 / 没日期 → 那一段不出现，也不留悬空分隔号）；
 * 3. 每个状态词只在它自己那一支出现 —— 这一条同时是其余断言的变异证明。
 */
class CellLabelTest {

    private fun label(
        name: String = "SuiPai_1000.jpg",
        w: Int = 1920,
        h: Int = 1440,
        size: String = "2.4MB",
        day: String = "9月21日",
        fav: Boolean = false,
        hidden: Boolean = false,
        sel: Boolean = false,
    ) = photoCellLabel(name, w, h, size, day, fav, hidden, sel)

    @Test
    fun fullCellSaysEverythingInTheFixedOrder() {
        assertEquals(
            "SuiPai_1000.jpg，1920×1440，2.4MB，9月21日，已收藏，已隐藏，已选中",
            label(fav = true, hidden = true, sel = true),
        )
    }

    @Test
    fun missingFactsAreNotInvented() {
        // 尺寸没解出来 / 角标那句是空的 / 日期不可用：那几段不许出现
        assertEquals("SuiPai_1000.jpg", label(w = 0, h = 0, size = "", day = ""))
        val l = label(w = 0, h = 0, size = "", day = "")
        assertFalse("缺信息时不该有悬空的分隔号：" + l, l.contains("，，") || l.endsWith("，"))
    }

    @Test
    fun smallFilesStillGetTheirSize() {
        // 装机探针抓到的分叉：只报 MB 时，那批 72KB 的测试图在标签里等于没有大小。
        // 现在标签收的就是角标那句原文，角标念得出 KB，标签就必须也念得出。
        assertTrue("KB 级的文件也要说大小：" + label(size = "72KB"), label(size = "72KB").contains("72KB"))
    }

    @Test
    fun eachStateWordBelongsToItsOwnBranch() {
        val cases = listOf(
            Triple("已收藏", listOf("已隐藏", "已选中"), label(fav = true)),
            Triple("已隐藏", listOf("已收藏", "已选中"), label(hidden = true)),
            Triple("已选中", listOf("已收藏", "已隐藏"), label(sel = true)),
        )
        for ((must, mustNot, l) in cases) {
            assertTrue("应当说出 $must，实际=$l", l.contains(must))
            for (b in mustNot) assertFalse("不该说出 $b，实际=$l", l.contains(b))
        }
    }

    @Test
    fun derivedPhotoSaysItWasProcessed() {
        // 「瞬」角标读的就是这个前缀，两句不能各说各话
        val l = label(name = "SuiPai_一瞬_暖阳_44_1700.jpg")
        assertTrue("加工件该说一句，实际=$l", l.contains("一瞬加工过"))
        assertFalse("原片不该被说成加工件", label(name = "SuiPai_1700.jpg").contains("加工"))
    }

    // ── 相册卡片 ─────────────────────────────────────────────────────

    private fun album(
        name: String = "旅行",
        count: Int = 47,
        last: Long = 1_758_000_000_000L,
        pinned: Boolean = false,
        hidden: Boolean = false,
        recent: Boolean = false,
        special: Boolean = false,
        hideMode: Boolean = false,
    ): Pair<String, String> {
        val item = AlbumItem(id = "a-$name", name = name, cover = null, count = count, lastTaken = last)
        val sub = albumCellSub(item, pinned, hidden, recent, special, "  ·  ")
        return sub to albumCellLabel(item, pinned, hidden, recent, special, hideMode)
    }

    @Test
    fun albumSubtitleMatchesWhatTheCardShows() {
        val (sub, full) = album(pinned = true, recent = true)
        assertTrue("卡面小字该有张数与钉住：" + sub, sub.startsWith("47 张") && sub.contains("钉住"))
        assertTrue("读屏那句要带册名：" + full, full.startsWith("旅行，47 张"))
        assertFalse("界面用「 · 」，读屏用「，」，不要互相串：" + full, full.contains("  ·  "))
    }

    @Test
    fun albumLabelExplainsTheCurrentMode() {
        val (_, plain) = album()
        assertFalse("默认模式不该提藏册：" + plain, plain.contains("整理藏册"))
        val (_, inHide) = album(hideMode = true)
        assertTrue("整理模式下要说清点它是收还是放：" + inHide, inHide.contains("点它是把它藏起来"))
        val (_, out) = album(hideMode = true, hidden = true)
        assertTrue("已经藏起来的要说放出来：" + out, out.contains("把它放出来"))
        val (_, sys) = album(hideMode = true, special = true)
        assertTrue("系统册在藏册模式下不动，得说明白：" + sys, sys.contains("系统册"))
        assertFalse("系统册不该被说成可藏：" + sys, sys.contains("点它是"))
    }

    @Test
    fun albumLabelSaysWhatLongPressDoes() {
        val (_, l) = album()
        assertTrue("长按=钉住 要说出来：" + l, l.contains("长按是钉到最前面"))
        val (_, p) = album(pinned = true)
        assertTrue("已钉住的该说取消钉住：" + p, p.contains("长按是取消钉住"))
    }

    @Test
    fun albumWithoutDateOmitsThatPart() {
        val (sub, full) = album(last = 0L)
        assertFalse("没有拍摄时间就不该编一个日期：" + sub, sub.endsWith("·"))
        assertTrue(full.contains("47 张"))
    }

    @Test
    fun emptyScreenBlamesTheMissedReadFirst() {
        // 「没读到」压过「搜不到」：数据压根没拿回来时说"没有匹配的照片"是在误导
        val problem = "没读到照片库（系统那边没答话）"
        assertEquals(problem, topEmptyHint(problem, "abc", 0, 0))
        assertEquals(problem, topEmptyHint(problem, "", 0, 0))
        assertEquals(null, topEmptyHint(null, "", 0, 0))
        val hint = topEmptyHint(null, "abc", 0, 1)
        assertTrue("没读到时应退回搜索那一句并点名隐藏类：" + hint, hint != null && hint.contains("还藏着 1 类"))
    }

    @Test
    fun noMatchSaysWhoHidThePhotos() {
        // 装机复测：搜索词命中的照片正被「藏副本」藏着，屏幕却只说"没有匹配的照片"
        assertEquals("没有匹配的照片", noMatchHint(0, 0))
        val onlyHide = noMatchHint(0, 1)
        assertTrue("要说出还藏着几类：" + onlyHide, onlyHide.contains("还藏着 1 类"))
        assertFalse("没开筛选就别提筛选：" + onlyHide, onlyHide.contains("筛选"))
        val onlyFilter = noMatchHint(2, 0)
        assertTrue("要说出几处筛选开着：" + onlyFilter, onlyFilter.contains("2 处筛选"))
        assertFalse("没藏东西就别提藏：" + onlyFilter, onlyFilter.contains("藏着"))
        val both = noMatchHint(2, 3)
        assertTrue(both.contains("2 处筛选") && both.contains("藏着 3 类"))
        // 三支话各不相同，且都不是空话（0/0 之外必须带数字）
        val said = listOf(noMatchHint(0, 1), noMatchHint(1, 0), noMatchHint(1, 1))
        assertEquals("三句得是三句（撞了就说明分支写歪）：" + said, 3, said.distinct().size)
        assertTrue(said.all { t -> t.any { it.isDigit() } })
    }
}
