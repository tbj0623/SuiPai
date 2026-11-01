package com.suipai.app.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 「日期」那一格 28 档的窗口边界。
 *
 * 期望值是在这里**用手写日期表复述一遍**（`今天 = 2026-03-05` 那一列），
 * 不是把 `RecencyRules` 的算法再跑一次：
 * 原来 27 个臂各写 `minusMonths(k)` / `minusMonths(k-1)`，抄反一位就是
 * 「格子上写三月前、筛的是四月前」，而界面上两种都会显示出一批照片，谁也看不出来。
 */
class RecencyRuleTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 5)   // 星期四
    private val zone: ZoneOffset = ZoneOffset.UTC

    /** 手写：每档的起点日与终点日（含）。终点 null = 开放到将来。 */
    private val expected: Map<Int, Pair<String, String?>> = linkedMapOf(
        0 to ("2026-03-05" to null),      // 不筛：窗口应为 null
        1 to ("2026-02-27" to null),      // 近7天 = 含今天往前 7 天
        2 to ("2026-02-04" to null),      // 近30天
        3 to ("2025-12-06" to null),      // 近90天
        4 to ("2026-01-01" to null),      // 今年
        5 to ("2026-03-05" to null),      // 今天
        6 to ("2026-03-03" to null),      // 近3天
        7 to ("2025-01-01" to "2025-12-31"),   // 去年：整个自然年
        8 to ("2026-03-02" to null),      // 本周：本周一起（ISO，往回取）
        9 to ("2026-03-01" to null),      // 本月
        10 to ("2024-01-01" to "2024-12-31"),  // 前年
        11 to ("2025-09-04" to null),     // 近半年：现状 183 天，见 RecencyRules 的注释
        12 to ("2025-03-06" to null),    // 近一年
        13 to ("2026-03-04" to "2026-03-04"),  // 昨天
        14 to ("2026-03-03" to "2026-03-03"),  // 前天
        15 to ("2026-03-02" to "2026-03-02"),  // 大前天
        16 to ("2026-02-01" to "2026-02-28"),  // 上个月
        17 to ("2026-01-01" to "2026-01-31"),  // 上上个月
        18 to ("2025-12-01" to "2025-12-31"),  // 三月前
        19 to ("2025-11-01" to "2025-11-30"),
        20 to ("2025-10-01" to "2025-10-31"),
        21 to ("2025-09-01" to "2025-09-30"),
        22 to ("2025-08-01" to "2025-08-31"),
        23 to ("2025-07-01" to "2025-07-31"),
        24 to ("2025-06-01" to "2025-06-30"),
        25 to ("2025-05-01" to "2025-05-31"),
        26 to ("2025-04-01" to "2025-04-30"),
        27 to ("2025-03-01" to "2025-03-31"),
    )

    private fun dayMs(iso: String): Long = LocalDate.parse(iso).toEpochDay() * 86_400_000L
    private fun lastMs(iso: String): Long = dayMs(iso) + 86_400_000L - 1

    /** 纯检查器：把任意一份表对着手写日期表跑一遍，报出没对上的编号。 */
    private fun mismatched(rules: List<RecencyRule>): List<Int> {
        val out = ArrayList<Int>()
        for ((id, exp) in expected) {
            val rule = rules.firstOrNull { it.id == id }
            if (rule == null) {
                out += id
                continue
            }
            val got = if (id == 0) null else recencyWindowOf(rule, today, zone)
            if (id == 0) {
                if (got != null) out += id
                continue
            }
            if (got == null) {
                out += id
                continue
            }
            val wantStart = dayMs(exp.first)
            val wantEnd = exp.second?.let { lastMs(it) } ?: Long.MAX_VALUE
            if (got.first != wantStart || got.last != wantEnd) out += id
        }
        return out
    }

    @Test
    fun everyWindowMatchesTheHandWrittenDateTable() {
        assertEquals("这些档的窗口与手写日期表不一致", emptyList<Int>(), mismatched(RecencyRules))
    }

    @Test
    fun theCycleModulusIsTheTableSize() {
        // 抽屉里是 `recency = (recency + 1) % 28`：表多一档/少一档，就会有点不到的档或走不到的标签。
        assertEquals(28, RecencyRules.size)
        assertEquals((0..27).toSet(), RecencyRules.map { it.id }.toSet())
    }

    @Test
    fun labelsAndHintsAreUniqueAndTheHintQuotesItsOwnLabel() {
        val labels = RecencyRules.map { it.label }
        val hints = RecencyRules.map { it.hint }
        assertEquals("标签有重复", labels.size, labels.toSet().size)
        assertEquals("空状态文案有重复", hints.size, hints.toSet().size)
        for (r in RecencyRules.filter { it.id != 0 }) {
            assertTrue("「${r.label}」的空状态没有提到自己：${r.hint}", r.hint.startsWith(r.label))
        }
    }

    @Test
    fun monthWindowsTileBackwardsWithoutGaps() {
        // 16..27 是一个月一格的连续区间：上个月的起点必须正好是本月窗口终点+1ms。
        for (id in 17..27) {
            val prev = recencyWindow(id - 1, today, zone)!!
            val cur = recencyWindow(id, today, zone)!!
            assertEquals("编号 " + id + " 的窗口与前一档之间断开或重叠", prev.first - 1, cur.last)
        }
    }

    @Test
    fun zeroMeansNoFilteringAndAnUnknownIdFallsBackToIt() {
        assertNull(recencyWindow(0, today, zone))
        assertNull("编号不认识时宁可不筛，也别把整册清空", recencyWindow(999, today, zone))
        assertEquals("全部日期", recencyLabel(999))
        assertEquals("没有照片", recencyHint(999))
    }

    /** 自检：把一档的起止抄反，检查器必须立刻变红——否则上面那条绿是死的。 */
    @Test
    fun checkerActuallyCatchesAnInvertedWindow() {
        val broken = RecencyRules.map {
            // 「三月前」的两个日期互换：窗口从 12 月 1 日到 11 月 1 日 → 空。
            if (it.id == 18) RecencyRule(18, it.label, it.hint, it.untilNext, it.from) else it
        }
        assertTrue("把一档的起止互换之后检查器仍然全绿，那它是死的", mismatched(broken).contains(18))
        assertTrue("检查器对好的表也报警了", mismatched(RecencyRules).isEmpty())
    }
}
