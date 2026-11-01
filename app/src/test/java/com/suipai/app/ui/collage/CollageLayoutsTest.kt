package com.suipai.app.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼图模板表的几何不变量。
 *
 * 这些错法**编译得过、界面上也不一定看得出来**：
 * - 两格重叠：导出时后一格盖住前一格的一条边，用户以为照片被裁了；
 * - 有缝隙：底色从缝里露出来，9 格模板上会变成一张黑色网格；
 * - 面积不足 1：右下角空一块，而那正是"导出坏了"最像的样子。
 *
 * 所以这里逐个模板、逐对格子算，并且每条判据都配一条"喂坏例子必须变红"的自检 ——
 * 没见过自己变红的守卫不算守卫。
 */
class CollageLayoutsTest {

    companion object {
        const val EPS = 1e-4
        /** 重叠判定的容差比面积容差更紧：浮点误差在 1e-7 量级，1e-6 足够区分。 */
        const val OVERLAP_EPS = 1e-6

        /** 两格的交叠面积（归一化）。 */
        fun overlapArea(a: CollageSlot, b: CollageSlot): Float {
            val w = minOf(a.right, b.right) - maxOf(a.x, b.x)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.y, b.y)
            return if (w <= 0f || h <= 0f) 0f else w * h
        }

        /**
         * 返回这张模板的**违规清单**（空 = 合规）。
         *
         * 判据按需求逐条写死：
         * 1. `slots.size in 2..9`；
         * 2. 每个格子的 x/y/w/h 都在 [0,1] 且 w>0、h>0；
         * 3. 任意两格交叠面积 ≤ OVERLAP_EPS；
         * 4. 面积和 = 1.0（容差 EPS）。
         *
         * 2 + 3 + 4 三条合起来才是"无重叠、无缝隙、铺满"：少一条都可能放过一张
         * "格子超界"或"少一块"的模板。
         */
        fun violations(layout: CollageLayout): List<String> {
            val out = ArrayList<String>()
            if (layout.slots.size !in 2..9) out += "${layout.id}：格子数 ${layout.slots.size} 不在 2..9"
            for ((i, s) in layout.slots.withIndex()) {
                if (s.x < -EPS || s.y < -EPS || s.right > 1f + EPS || s.bottom > 1f + EPS) {
                    out += "${layout.id}[$i] 越界：x=${s.x} y=${s.y} right=${s.right} bottom=${s.bottom}"
                }
                if (s.w <= 0f || s.h <= 0f) out += "${layout.id}[$i] 宽高非正：w=${s.w} h=${s.h}"
            }
            for (i in layout.slots.indices) {
                for (j in i + 1 until layout.slots.size) {
                    val area = overlapArea(layout.slots[i], layout.slots[j])
                    if (area > OVERLAP_EPS) {
                        out += "${layout.id}[$i]×[$j] 重叠 $area"
                    }
                }
            }
            val sum = layout.slots.map { it.area }.sum()
            if (kotlin.math.abs(sum - 1f) > EPS) out += "${layout.id} 面积和 $sum ≠ 1.0"
            return out
        }

        /**
         * 返回"宣称的格数与实际格数对不上"的模板。
         *
         * 格数从 id 的**结尾数字**读：`grid3x3` 是两维相乘（9），`v2` / `col3` / `row4`
         * 是格数本身。`leftbar4` 这种带单词前缀的 id 也认（只看结尾）。
         */
        fun countMismatches(layouts: List<CollageLayout>): List<String> {
            val out = ArrayList<String>()
            for (layout in layouts) {
                val id = layout.id.lowercase()
                val claimed = Regex("(\\d+)x(\\d+)$").find(id)?.let {
                    it.groupValues[1].toInt() * it.groupValues[2].toInt()
                } ?: Regex("(\\d+)$").find(id)?.let { it.groupValues[1].toInt() }
                    ?: continue
                if (claimed != layout.slots.size) {
                    out += "${layout.id}：id 宣称 $claimed 格，实际 ${layout.slots.size} 格"
                }
            }
            return out
        }
    }

    @Test
    fun everyLayoutPartitionsTheCanvasExactly() {
        assertTrue("模板表是空的，这一篇在空跑", CollageLayouts.all.isNotEmpty())
        val bad = CollageLayouts.all.flatMap { violations(it) }
        assertEquals("这些模板违反「无重叠、无缝隙、面积=1」：${bad.joinToString(" | ")}", emptyList<String>(), bad)
    }

    @Test
    fun thereAreAtLeastTwelveLayoutsWithThePromisedMix() {
        val all = CollageLayouts.all
        assertTrue("模板只有 ${all.size} 张，需求是至少 12 张", all.size >= 12)
        assertEquals("模板 id 撞车了", all.size, all.map { it.id }.toSet().size)
        // 每种格数至少要有几张：2 格 2、3 格 3、4 格 3、6 格 2、9 格 1。
        val byCount = all.groupBy { it.count }
        assertTrue("2 格模板不足 2 张", (byCount[2]?.size ?: 0) >= 2)
        assertTrue("3 格模板不足 3 张", (byCount[3]?.size ?: 0) >= 3)
        assertTrue("4 格模板不足 3 张", (byCount[4]?.size ?: 0) >= 3)
        assertTrue("6 格模板不足 2 张", (byCount[6]?.size ?: 0) >= 2)
        assertTrue("9 格模板不足 1 张", (byCount[9]?.size ?: 0) >= 1)
        // 横竖各一套：2 格必须有"左右"和"上下"两种切法。
        assertTrue(
            "2 格模板里没有横切",
            byCount[2].orEmpty().any { it.id == "h2" },
        )
        assertTrue(
            "2 格模板里没有竖切",
            byCount[2].orEmpty().any { it.id == "v2" },
        )
    }

    @Test
    fun slotCountsMatchTheirNames() {
        val bad = countMismatches(CollageLayouts.all)
        assertEquals("这些模板的格数与名字对不上：${bad.joinToString(" | ")}", emptyList<String>(), bad)
        // 自检：判据不是死的 —— 每一张模板的 id 都必须能读出格数，读不出的等于没人核对。
        val readable = CollageLayouts.all.count { it.id.any { c -> c.isDigit() } }
        assertEquals("这些模板的 id 读不出格数，等于没人核对", CollageLayouts.all.size, readable)
    }

    @Test
    fun coordinatesStayInsideTheUnitSquare() {
        for (layout in CollageLayouts.all) {
            for ((i, s) in layout.slots.withIndex()) {
                assertTrue("${layout.id}[$i] x=${s.x} 不在 [0,1]", s.x in 0f..1f)
                assertTrue("${layout.id}[$i] y=${s.y} 不在 [0,1]", s.y in 0f..1f)
                assertTrue("${layout.id}[$i] right=${s.right} 超过 1", s.right <= 1f + EPS)
                assertTrue("${layout.id}[$i] bottom=${s.bottom} 超过 1", s.bottom <= 1f + EPS)
            }
        }
    }

    /**
     * 命中测试：每一格的中心点都必须命中它自己。
     *
     * 这条是拖拽换位的地基 —— 中心点都命不中，用户在格子正中间长按会"抓不到东西"。
     * 同时钉住"没命中返回 -1"：返回 0 会让画布外的误拖变成"换到第一格"。
     */
    @Test
    fun hitTestFindsTheSlotUnderTheFinger() {
        for (layout in CollageLayouts.all) {
            for ((i, s) in layout.slots.withIndex()) {
                assertEquals("${layout.id} 中心点没命中原格", i, layout.hit(s.centerX, s.centerY))
            }
            assertEquals("${layout.id} 命中测试把画布外报成了某一格", -1, layout.hit(1.5f, 0.5f))
            assertEquals("${layout.id} 负坐标没被拒绝", -1, layout.hit(-0.01f, 0.5f))
        }
    }

    @Test
    fun defaultLayoutForCountFitsAndIsStable() {
        for (n in 2..9) {
            val layout = CollageLayouts.defaultFor(n)
            assertTrue("$n 张照片选到了只有 ${layout.slots.size} 格的模板", layout.slots.size >= n)
            assertEquals("defaultFor($n) 不稳定", layout.id, CollageLayouts.defaultFor(n).id)
            assertTrue("${layout.id} 放不下 $n 张", layout.holds(n))
        }
        assertEquals("2 张应选到 2 格模板", 2, CollageLayouts.defaultFor(2).count)
        assertEquals("9 张应选到 9 格模板", 9, CollageLayouts.defaultFor(9).count)
        // 越界的入参不许抛：0 张、100 张都要给出一个能画的模板
        assertTrue(CollageLayouts.defaultFor(0).slots.isNotEmpty())
        assertTrue(CollageLayouts.defaultFor(100).slots.isNotEmpty())
        assertEquals("byId 找不到时要退回默认模板，不能抛", "v2", CollageLayouts.byId("不存在").id)
    }

    /**
     * 自检：判据真的会因为那三种坏模板变红。
     *
     * 三张坏例子分别是三种真实错法：**重叠**（后一格压前一格的边）、
     * **有缝**（左右各 0.5 但少算一条中线）、**少一块**（右下角空着）。
     */
    @Test
    fun checkerActuallyCatchesBadLayouts() {
        val overlap = CollageLayout(
            id = "bad_overlap",
            name = "重叠",
            slots = listOf(
                CollageSlot(0f, 0f, 0.6f, 1f),
                CollageSlot(0.5f, 0f, 0.5f, 1f),
            ),
        )
        assertTrue("重叠的模板没被抓到：${violations(overlap)}", violations(overlap).any { "重叠" in it })

        val gap = CollageLayout(
            id = "bad_gap",
            name = "有缝",
            slots = listOf(
                CollageSlot(0f, 0f, 0.5f, 0.99f),
                CollageSlot(0.5f, 0f, 0.5f, 0.99f),
            ),
        )
        assertTrue("少一条边的模板没被抓到：${violations(gap)}", violations(gap).any { "面积和" in it })

        val outOfBounds = CollageLayout(
            id = "bad_oob",
            name = "越界",
            slots = listOf(
                CollageSlot(0f, 0f, 0.5f, 1f),
                CollageSlot(0.5f, 0f, 0.7f, 1f),
            ),
        )
        assertTrue("越界的模板没被抓到：${violations(outOfBounds)}", violations(outOfBounds).any { "越界" in it })

        val tooFew = CollageLayout(id = "bad_one", name = "一格", slots = listOf(CollageSlot(0f, 0f, 1f, 1f)))
        assertTrue("1 格的模板没被抓到", violations(tooFew).any { "格子数" in it })

        // 反向：真模板里挑一张，判据必须认它是干净的（防止判据恒真）
        assertTrue("干净模板被误判了：${violations(CollageLayouts.grid3x3)}", violations(CollageLayouts.grid3x3).isEmpty())
        assertEquals(
            "格数与名字对不上时要报出来",
            listOf("grid2x3：id 宣称 6 格，实际 5 格"),
            countMismatches(
                listOf(
                    CollageLayout(
                        "grid2x3",
                        "坏例子",
                        listOf(
                            CollageSlot(0f, 0f, 0.5f, 0.2f),
                            CollageSlot(0.5f, 0f, 0.5f, 0.2f),
                            CollageSlot(0f, 0.2f, 0.5f, 0.2f),
                            CollageSlot(0.5f, 0.2f, 0.5f, 0.2f),
                            CollageSlot(0f, 0.4f, 1f, 0.6f),
                        ),
                    ),
                ),
            ),
        )
        // 带单词前缀的 id（leftbar4 / top4）也要能读出来 —— 否则 12 张里有几张永远没人核对
        assertEquals(
            "单词前缀的 id 读不出格数，这几张等于没人核对",
            emptyList<String>(),
            countMismatches(listOf(CollageLayouts.leftBar4, CollageLayouts.top4, CollageLayouts.row4)),
        )
    }

    /**
     * 渲染器的像素几何：外边距 + 缝不许把格子挤成负数或让相邻格子互相盖住。
     *
     * 这一条不跑 Android，只算 `cellRects` 的算术 —— 而它正是"缝宽了会吃掉整格"的唯一来源。
     */
    @Test
    fun cellRectsRespectMarginAndGutter() {
        val layout = CollageLayouts.grid2x2
        val rects = CollageRenderer.cellRects(layout, 1000, 1000, marginPx = 24f, borderPx = 12f)
        assertEquals("格子数与模板不一致", 4, rects.size)
        for (r in rects) {
            assertTrue("格子被挤成非正数：$r", r.width > 0f && r.height > 0f)
            assertTrue("格子画到画布外了：$r", r.left >= 24f - EPS && r.top >= 24f - EPS)
            assertTrue("格子画到画布外了：$r", r.right <= 1000f - 24f + EPS && r.bottom <= 1000f - 24f + EPS)
        }
        // 相邻两格的缝 = borderPx（各收一半）
        val left = rects[0]
        val right = rects[1]
        assertEquals("中缝不是 borderPx", 12.0, (right.left - left.right).toDouble(), EPS.toDouble())
        // 外边距是 marginPx 再各收半条缝
        assertEquals("左边距不对", 30.0, rects[0].left.toDouble(), EPS.toDouble())

        // 自检：边距大到吃光画布时不许出现负宽高（那是 Canvas 直接抛异常的形状）
        val crushed = CollageRenderer.cellRects(layout, 100, 100, marginPx = 80f, borderPx = 4f)
        assertTrue("边距吃光画布时给出了正矩形：$crushed", crushed.all { it.isEmpty })

        // 缝为 0 时四格必须严丝合缝（同一份归一化模板下）
        val tight = CollageRenderer.cellRects(layout, 999, 999, marginPx = 0f, borderPx = 0f)
        assertEquals("缝为 0 时左右两格之间有空隙", 0.0, (tight[1].left - tight[0].right).toDouble(), EPS.toDouble())
        assertEquals("缝为 0 时上下两格之间有空隙", 0.0, (tight[2].top - tight[0].bottom).toDouble(), EPS.toDouble())
    }

    @Test
    fun outputSizeFollowsAspectAndLongEdge() {
        val square = CollageRenderer.outputSize(CollageStyle(aspect = 1f), longEdge = 2048)
        assertEquals(2048 to 2048, square)
        val portrait = CollageRenderer.outputSize(CollageStyle(aspect = 0.5f), longEdge = 2048)
        assertEquals("竖幅的长边应是高度", 2048, portrait.second)
        assertEquals("竖幅的宽应为高的一半", 1024, portrait.first)
        val wide = CollageRenderer.outputSize(CollageStyle(aspect = 2f), longEdge = 2048)
        assertEquals("横幅的长边应是宽度", 2048, wide.first)
        assertEquals(1024, wide.second)
        // 越界的参数被夹回，而不是画出一张 0 宽的图
        val clamped = CollageRenderer.outputSize(CollageStyle(aspect = 100f), longEdge = 8)
        assertTrue("长边越界没被夹住：$clamped", clamped.first >= 256)
        // 自检：默认长边就是需求里的 2048
        assertEquals(2048, CollageRenderer.DEFAULT_LONG_EDGE)
    }

    /**
     * 导出文件名必须落进加工件那一族：`SuiPai_<动作>_<时间戳>.jpg`。
     *
     * 这条与 `SuiPaiNameTest.isSuiPaiOriginalName` 是同一个判据的两半：
     * 那边说"两个下划线 ⇒ 不是原片"，这边说"拼图导出的名字确实有两个下划线"。
     * 两边都对，才保证拼图不会被算进「仅随拍原片」。
     */
    @Test
    fun exportNameLooksLikeADerivative() {
        val name = collageExportName(1_790_023_855_356L)
        assertEquals("SuiPai_拼图_1790023855356.jpg", name)
        assertTrue("拼图导出名必须以 SuiPai_ 开头", name.startsWith("SuiPai_"))
        assertTrue("拼图导出名必须以 .jpg 结尾（落盘是 JPEG）", name.endsWith(".jpg"))
        assertEquals(
            "动作段必须是「拼图」—— SuiPaiNameTest 的加工件名单按这个前缀认",
            "拼图",
            name.removePrefix("SuiPai_").substringBefore('_'),
        )
        assertTrue(
            "两个下划线才算加工件；这一个名字只有一个就是被判成原片了",
            name.removePrefix("SuiPai_").contains('_'),
        )
    }

    /** 源图解码边长：按目标格子的像素数取，不许按原图尺寸取（那是 9 张 4000px 同时进内存的来路）。 */
    @Test
    fun decodeEdgeScalesWithTheCellNotTheSource() {
        val small = CollageCellRect(0f, 0f, 200f, 150f)
        val big = CollageCellRect(0f, 0f, 900f, 1200f)
        assertTrue("小格子解得太大了", CollageRenderer.decodeEdgeFor(small) in 200..400)
        assertTrue("大格子解得太小（会糊）", CollageRenderer.decodeEdgeFor(big) >= 1200)
        assertTrue(
            "解码边长没有被上限压住：${CollageRenderer.decodeEdgeFor(CollageCellRect(0f, 0f, 9000f, 9000f))}",
            CollageRenderer.decodeEdgeFor(CollageCellRect(0f, 0f, 9000f, 9000f)) <= CollageRenderer.MAX_SOURCE_EDGE,
        )
        assertEquals("空格子不该解图", 0, CollageRenderer.decodeEdgeFor(CollageCellRect(0f, 0f, 0f, 0f)))
    }
}
