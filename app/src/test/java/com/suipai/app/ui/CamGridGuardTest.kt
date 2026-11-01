package com.suipai.app.ui

import com.suipai.app.ui.camera.CamGrid
import com.suipai.app.ui.camera.GridPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 辅助线这张表的数据不变量：**每一条预设必须画出一套别人没有的线**。
 *
 * 起因是读代码读出来的（这台机器量不了帧率，见 `ANDROID-VERIFY.md` §1b，但表能直接算）：
 * 商店文案写"24 种构图辅助线预设"，而表里只有 23 条，其中还有**四对画出来一模一样**——
 * `井字中点`/`井字十字`、`四角中点`/`十字四角`、`对角中点`/`对角十字`、`黄金对角`/`对角黄金`，
 * 反过来 `井字+三角`、`对角+三角` 两种组合根本没有。
 * 用户按"网格"格循环到那四条时，看到的是**点了没反应**：名字换了，画面一根线都没变。
 *
 * 另一条不变量是**序号**：ordinal 直接存进 SharedPreferences 的 `grid_mode`，
 * 所以这张表只许就地换内容或往后追加，插入/删除/重排都会把用户存过的选择换成另一条线。
 */
class CamGridGuardTest {

    companion object {
        /** 画出同一套线的标签（同一组基元的两种排列也算同一套）。 */
        fun sameDrawLabels(rows: List<Pair<String, Set<String>>>): List<String> =
            rows.groupBy { it.second }.filterValues { it.size > 1 }.values.flatten().map { it.first }

        /** 6 个基元里"两两组合"缺了哪几对。 */
        fun missingPairCombos(rows: List<Pair<String, Set<String>>>, primitives: List<String>): List<Set<String>> {
            val drawn = rows.map { it.second }.toSet()
            return listOfAllPairs(primitives).filter { it !in drawn }
        }

        fun listOfAllPairs(primitives: List<String>): List<Set<String>> =
            primitives.indices.flatMap { i -> (i + 1 until primitives.size).map { j -> setOf(primitives[i], primitives[j]) } }
    }

    private val rows: List<Pair<String, Set<String>>>
        get() = CamGrid.values().filter { it.parts.isNotEmpty() }
            .map { it.label to it.parts.map { p -> p.name }.toSet() }

    private val primitives = GridPart.values().map { it.name }

    @Test
    fun noTwoPresetsDrawTheSameLines() {
        val dupes = sameDrawLabels(rows)
        assertTrue("这些辅助线预设画出来的线一模一样：$dupes", dupes.isEmpty())
        assertEquals("标签本身也不许重名", rows.map { it.first }.distinct().size, rows.size)
    }

    @Test
    fun everyPrimitiveAndEveryPairIsOffered() {
        assertEquals("基元该有 6 种", 6, primitives.size)
        val drawn = rows.map { it.second }.toSet()
        primitives.forEach {
            assertTrue("单基元「$it」这一条没了", drawn.contains(setOf(it)))
        }
        val missing = missingPairCombos(rows, primitives)
        assertTrue("两两组合缺这几对（15 对应当齐全）：$missing", missing.isEmpty())
    }

    @Test
    fun presetCountMatchesWhatTheStoreAdvertises() {
        // store/listing-zh.md 里那句「24 种构图辅助线预设」数的就是它：
        // 6 个单基元 + 15 种两两组合 + 3 种三重组合 = 24，一条不多一条不少。
        assertEquals("预设条数与商店文案对不上，改任何一边都要同步另一边", 24, rows.size)
        assertEquals("含「网格 关」应当是 25 档", 25, CamGrid.values().size)
        val triples = rows.filter { it.second.size >= 3 }
        assertEquals("三重组合应当有 3 条", 3, triples.map { it.second }.distinct().size)
        assertTrue("没有哪条预设画超过 3 种基元", rows.all { it.second.size <= 3 })
    }

    @Test
    fun offStaysFirstBecauseShowGridIsOrdinalPositive() {
        // `show_grid = gridMode > 0` 与 `cycleGrid()` 的 `% size` 都靠 OFF 在 0 号位。
        assertEquals("网格 关", CamGrid.values().first().label)
        assertTrue(CamGrid.values().first().parts.isEmpty())
    }

    @Test
    fun declaredOrderIsPinnedBecauseOrdinalIsPersisted() {
        val expected = listOf(
            "网格 关", "九宫格", "黄金分割", "对角", "中心点", "三角", "井字对角", "四角",
            "井字中点", "井字黄金", "四角中点", "对角中点", "井字四角", "黄金四角", "对角四角",
            "三角四角", "黄金对角中点", "井字对角四角", "井字三角", "黄金十字", "对角三角",
            "三角十字", "黄金三角", "对角黄金", "井字黄金四角",
        )
        // 想动顺序：要么把用户存过的 grid_mode 做迁移，要么别动——这张表只许就地改或往后追加。
        assertEquals(expected, CamGrid.values().map { it.label })
    }

    @Test
    fun outOfRangeSavedIndexFallsBackInsteadOfCrashingThePreview() {
        // grid_mode 来自 SharedPreferences：从更新的版本降级回来时序号可能比这张表长，
        // 而读它的那一行在取景 composition 里（`CamGrid.at(...)` 就是为这一条存在的）。
        assertEquals(CamGrid.values().last(), CamGrid.at(999))
        assertEquals(CamGrid.OFF, CamGrid.at(-3))
        // 老版本 % 24 循环，存过的最大序号是 23：新表必须仍然接得住它。
        assertEquals(23, CamGrid.at(23).ordinal)
        assertTrue(CamGrid.at(23).parts.isNotEmpty())
    }

    @Test
    fun guardActuallyCatchesADuplicatedPreset() {
        // 脏样本自带：以前那张表里"井字中点/井字十字"就是同一套线，守卫必须抓得住它。
        val dirty = listOf(
            "九宫格" to setOf("THIRD"),
            "井字中点" to setOf("THIRD", "CROSS"),
            "井字十字" to setOf("CROSS", "THIRD"),
        )
        assertEquals(listOf("井字中点", "井字十字"), sameDrawLabels(dirty))
        assertTrue(missingPairCombos(listOf("九宫格" to setOf("THIRD")), primitives).isNotEmpty())
        assertTrue(sameDrawLabels(rows).isEmpty())
    }
}
