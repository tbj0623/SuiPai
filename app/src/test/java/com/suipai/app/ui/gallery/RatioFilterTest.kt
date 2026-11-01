package com.suipai.app.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画幅」筛选那张表的不变量（`GalleryScreen.kt` 里的 `RatioFilters`）。
 *
 * 这张表以前抄了三遍（格子的标签、筛的 `when`、空态文案），三遍里任何一处漏改，
 * 用户看到的都是"格子上写着 4:3，筛出来的是 3:4"—— 不会崩、不会报错，只会筛错。
 * 最狠的一条是 `labelsAgreeWithTheAspectTheyFilter`：**把标签里的 "a:b" 自己解析成数**，
 * 与真正拿去 `nearRatio` 的那个数对一遍。抄写漏改、上下手滑颠倒，都会被这一条抓住。
 */
class RatioFilterTest {

    internal companion object {
        /** 标签里写的比例 vs 实际用来筛的那个数。返回不一致的条目。 */
        internal fun mismatches(rows: List<RatioFilter>): List<String> = rows.mapNotNull { f ->
            val parts = f.label.split(":")
            if (parts.size != 2) return@mapNotNull null
            val a = parts[0].toFloatOrNull() ?: return@mapNotNull f.label
            val b = parts[1].toFloatOrNull() ?: return@mapNotNull f.label
            val want = a / b
            val got = f.aspect ?: return@mapNotNull "${f.label}（标签是个比例，可它没有要接近的数）"
            if (kotlin.math.abs(want - got) > 0.001f) "${f.label}：标签=${want} 实筛=$got" else null
        }
    }

    @Test
    fun idsAndLabelsAreUnique() {
        assertEquals("编号必须是 1..21 各一次", (1..21).toList(), RatioFilters.map { it.id }.sorted())
        assertEquals("标签不许重名", RatioFilters.map { it.label }.distinct().size, RatioFilters.size)
        assertTrue("标签不许空白", RatioFilters.all { it.label.isNotBlank() })
        assertEquals("只有「超宽」一条走特殊判据", 1, RatioFilters.count { it.aspect == null })
        assertEquals("超宽那条的标签就是「超宽」", "超宽", RatioFilters.first { it.aspect == null }.label)
    }

    @Test
    fun labelsAgreeWithTheAspectTheyFilter() {
        val bad = mismatches(RatioFilters)
        assertTrue("这些画幅格子上写的比例与实际筛的不是一个数：$bad", bad.isEmpty())
    }

    @Test
    fun cycleModulusMatchesTheTable() {
        // 那一格是 `ratioFilter = (ratioFilter + 1) % 22`：0 = 不限 + 21 档。
        // 加一档忘了改模数 = 那一档永远点不到（`HourBucketTest` 里同一类钉子）。
        assertEquals(RatioFilters.size + 1, 22)
    }

    @Test
    fun everyIdHasItsOwnEmptyStateHint() {
        RatioFilters.forEach { f ->
            val hint = ratioFilterHint(f.id)
            assertTrue("第 ${f.id} 档（${f.label}）的空态文案没带上自己的名字：$hint", hint.contains(f.label))
            assertTrue("空态文案不该是兜底那句", hint != "没有匹配的照片")
        }
        assertEquals("0 是不限，不该有专属文案", "画幅", ratioFilterLabel(0))
        assertEquals("越界编号要退回兜底而不是抛", "没有匹配的照片", ratioFilterHint(99))
    }

    @Test
    fun guardActuallyCatchesACopyPasteSlip() {
        // 脏样本：把「4:3」的筛值写成 3/4 —— 上下颠倒正是"抄两遍最容易犯的那一种手滑"。
        val dirty = RatioFilters.map { f -> if (f.label == "4:3") f.copy(aspect = 3f / 4f) else f }
        val caught = mismatches(dirty)
        assertEquals("只该抓到被改的那一条：$caught", 1, caught.size)
        assertTrue("抓到的那条要指名是谁：$caught", caught[0].startsWith("4:3"))
        assertTrue("干净表必须不红，否则上面那条断言毫无意义", mismatches(RatioFilters).isEmpty())
    }
}
