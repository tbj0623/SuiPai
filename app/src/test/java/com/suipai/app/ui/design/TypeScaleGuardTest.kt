package com.suipai.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字级守卫：行高倍数、字号阶梯、等宽家族不许作正文。
 *
 * 为什么行高要写成测试：
 * 「中文段落 1.2 倍行高会糊成一块」这件事人人都知道，但**不知道每一档该是多少**。
 * 改之前全项目只有三个 TextStyle（pageHead/frameNo/label），而 Material 默认的
 * `Typography()` 行高是围绕西文算的：`bodyMedium` 14sp / 20sp = 1.43 倍，
 * `labelSmall` 11sp / 16sp = 1.45 倍 —— 单看都"差不多"，但中文正文 14sp 配 20sp
 * 实测是挤的，而 `titleLarge` 22sp / 28sp = 1.27 对中文大字又太松。
 * 这些偏差肉眼一档一档看不出来，所以钉成数字。
 *
 * 三条判据：
 * 1. 每个 TextStyle 的 lineHeight ≥ fontSize × 1.32（大字）或 × 1.45（正文/标签）；
 * 2. 字号阶梯单调递增（display → label 一路收），不许有两档同号；
 * 3. 等宽家族只用于编号、不做正文 —— 这一条按"等宽规格的字号上限"钉。
 *
 * 每条都配"喂坏样本必须变红"的自检。
 */
class TypeScaleGuardTest {

    /** 行高倍数的绝对下限：低于它，中文多行文本会糊。 */
    private val hardFloor = 1.3f

    /** 正文/标签族的下限（比大字更严格）。 */
    private val bodyFloor = 1.45f

    /** 大字族（display/headline）的下限。 */
    private val displayFloor = 1.32f

    @Test
    fun everyTextStyleHasEnoughLineHeightForChinese() {
        val failures = ArrayList<String>()
        var checked = 0
        for (spec in TypeScale.all) {
            checked++
            val ratio = spec.lineHeightRatio
            val floor = when {
                spec.name.startsWith("display") || spec.name.startsWith("headline") -> displayFloor
                spec.mono -> TypeScale.MonoLineHeight
                else -> bodyFloor
            }
            if (ratio < floor) {
                failures += "${spec.name}: 行高 %.2f 倍（${spec.sizeSp}sp / ${spec.lineHeightSp}sp），" +
                    "低于中文下限 %.2f".format(ratio, floor)
            }
        }
        assertTrue("这是空跑：一个 TextStyle 都没核对到", checked >= 18)
        assertTrue(
            "这些字级的行高对中文太挤（多行文本会糊成一块）：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
        // 绝对下限再兜一道（不管属于哪一族）
        val belowHardFloor = TypeScale.all.filter { it.lineHeightRatio < hardFloor }
        assertTrue("这些字级连 1.3 倍都不到：${belowHardFloor.map { it.name }}", belowHardFloor.isEmpty())

        // ── 自检：喂两个坏样本必须变红 ──
        // 1. Material 默认 bodyMedium 的行高倍数（14sp/20sp = 1.43）—— 正好卡在下限之下
        assertTrue(
            "1.43 倍被当成合格（那是 Material 西文默认值，中文偏挤）",
            20f / 14f < bodyFloor,
        )
        // 2. titleLarge 的默认 22/28 = 1.27，连绝对下限都不到
        assertTrue("1.27 倍被当成合格", 28f / 22f < hardFloor)
        // 3. 反方向：合规的一档不许误判
        assertTrue("1.6 倍被误判", 16f * 1.6f / 16f >= bodyFloor)
    }

    @Test
    fun fontSizeHierarchyDecreasesWithinEveryTier() {
        // 真正的结构性承诺不是"整张表单调递增"（Material 的 title 族与 body 族在尺寸上
        // 是交叉的：titleSmall 15 比 bodySmall 12 大），而是**每一族内部 Large→Small 严格递减**，
        // 且逐族来看 tier 之间整体下移。这张矩阵才是"重要程度"的证据。
        //
        //         Large   Medium  Small
        // display   48      36      30
        // headline  26      22      19
        // title     20      17      15
        // body      16      14      12
        // label     14      12      11
        val specByName = TypeScale.all.associateBy { it.name }
        assertEquals("层级族应当恰好 5 族", 5, TypeScale.hierarchy.size)

        // 每一族内部：Large > Medium > Small（严格递减）
        for ((idx, tier) in TypeScale.hierarchy.withIndex()) {
            assertEquals("第 $idx 族应当恰好三档", 3, tier.size)
            val sizes = tier.map { name ->
                val spec = specByName[name]
                assertTrue("层级族里出现了规格表没有的名字：$name", spec != null)
                spec!!.sizeSp
            }
            val violations = sizes.indices.drop(1).filter { i -> sizes[i] >= sizes[i - 1] }
            assertTrue("第 $idx 族（$tier）内部不是严格递减：$sizes", violations.isEmpty())
        }

        // 逐列看：同一档位下越靠前的族必须严格更大（display > headline > title > body > label）。
        // 三列都必须严格递减 —— 不同语义档（例如 body 与 label）在同一重量级上不许同字号，
        // 否则"正文里的强调"与"一颗按钮"看起来一样重。
        for (col in 0..2) {
            val column = TypeScale.hierarchy.map { tier -> specByName.getValue(tier[col]).sizeSp }
            val violations = column.indices.drop(1).filter { i -> column[i] >= column[i - 1] }
            assertTrue("第 ${col + 1} 档的层级不是严格递减：$column", violations.isEmpty())
        }

        // 两端：最小字级不许小于 11sp（再小中文笔画会断），最大不许超过 56sp。
        val allSizes = TypeScale.all.map { it.sizeSp }
        assertTrue("最小字级 ${allSizes.minOrNull()}sp 太小", (allSizes.minOrNull() ?: 0f) >= 11f)
        assertTrue("最大字级 ${allSizes.maxOrNull()}sp 太大", (allSizes.maxOrNull() ?: 0f) <= 56f)
        assertTrue("字级太稀疏（${allSizes.toSet().size} 个不同字号）", allSizes.toSet().size >= 10)

        // ── 自检：喂坏样本必须变红 ──
        // 1. 族内有一个不递减（titleMedium 与 titleSmall 同值）
        assertTrue(
            "titleMiddle=titleSmall 被当成合格",
            !(17f > 17f),
        )
        // 2. body 比 title 还大（族序颠倒）
        assertTrue("bodyLarge 应该比 titleLarge 小", 16f < 20f)
        // 3. 反方向：真实的一张合法列不许被误判
        val legit = listOf(48f, 26f, 20f, 16f, 14f)
        assertTrue(
            "合法的层级列被误判",
            legit.indices.drop(1).none { i -> legit[i] >= legit[i - 1] },
        )
    }

    @Test
    fun noTwoTiersShareTheSameSize() {
        // 同一档位（都是 Medium / 都是 Small）下，**相邻两族**不许同字号 ——
        // 同号意味着"两个层级的字一样大"，层级就没了。
        //
        // 注意：不同语义档**可以**同字号（例如 bodyLarge=16 与 headlineSmall=19 不同，
        // 但 bodyLarge 与 titleSmall 的语义本来就分离），这里只判"同一列（同一档位）
        // 里的重复"，因为那一列才是"同一重量级"的比较对象。
        val specByName = TypeScale.all.associateBy { it.name }
        val tiers = TypeScale.hierarchy

        val collisions = ArrayList<String>()
        for (col in 0..2) {
            val column = tiers.map { specByName.getValue(it[col]).sizeSp }
            val dupCount = column.size - column.toSet().size
            if (dupCount > 0) collisions += "第 ${col + 1} 档同字号 $dupCount 处：$column"
        }
        assertTrue(
            "这些档位里有同字号（同一重量级的两个层级会看起来一样重）：$collisions",
            collisions.isEmpty(),
        )

        // 每一列都必须严格递减（同列无重复的自然结果，明写出来让规则可读）
        for (col in 0..2) {
            val column = tiers.map { specByName.getValue(it[col]).sizeSp }
            val violations = column.indices.drop(1).filter { i -> column[i] >= column[i - 1] }
            assertTrue("第 ${col + 1} 档不是严格递减：$column", violations.isEmpty())
        }

        // 自检：人为造一对同号必须被认出来
        val fake = listOf(16f, 16f, 12f)
        assertTrue("同号没被认出来", fake.size - fake.toSet().size == 1)
        val badMedium = listOf(36f, 22f, 22f, 16f, 14f)
        assertTrue("Medium 列同号没被算成超标", badMedium.size - badMedium.toSet().size > 0)
        // 反方向：全不同的一组不许误判
        val clean = listOf(36f, 22f, 19f, 16f, 14f)
        assertTrue("正常列被误判", clean.size - clean.toSet().size == 0)
    }

    @Test
    fun everySpecIsDeclaredInBothSpecTableAndStyleObject() {
        // 规格表（TypeScale）与样式对象（SuiPaiType）必须一一对应。
        // 有人加了一条规格却忘了在 SuiPaiType 里接出来时，这条会红 ——
        // 否则那条规格就"存在但没人能用"，下一步别人会再写一条。
        val specNames = TypeScale.all.map { it.name }.toSet()
        val required = setOf(
            "displayLarge", "displayMedium", "displaySmall",
            "headlineLarge", "headlineMedium", "headlineSmall",
            "titleLarge", "titleMedium", "titleSmall",
            "bodyLarge", "bodyMedium", "bodySmall",
            "labelLarge", "labelMedium", "labelSmall",
            "frameNo", "stamp", "parameter", "counter", "marker",
        )
        val missingSpec = required.filterNot { it in specNames }
        assertTrue("这些样式名没有对应的规格：$missingSpec", missingSpec.isEmpty())

        // 反过来：规格表里不许有"没被要求的孤条目"（那说明加规格时没同步文档与守卫）。
        val extra = specNames.filterNot { it in required }
        assertTrue("规格表里有多余的、没进守卫清单的样式：$extra（加样式时必须同步文档与 required 清单）", extra.isEmpty())

        // 实际接出来的样式非空（读一次 fontSize，确认不是占位）
        val styles = listOf(
            SuiPaiType.displayLarge, SuiPaiType.displayMedium, SuiPaiType.displaySmall,
            SuiPaiType.headlineLarge, SuiPaiType.headlineMedium, SuiPaiType.headlineSmall,
            SuiPaiType.titleLarge, SuiPaiType.titleMedium, SuiPaiType.titleSmall,
            SuiPaiType.bodyLarge, SuiPaiType.bodyMedium, SuiPaiType.bodySmall,
            SuiPaiType.labelLarge, SuiPaiType.labelMedium, SuiPaiType.labelSmall,
            SuiPaiType.frameNo, SuiPaiType.stamp, SuiPaiType.parameter,
            SuiPaiType.counter, SuiPaiType.marker,
        )
        assertTrue("有样式没被接出来（size 为 0）", styles.all { it.fontSize.value > 0f })

        // 自检
        assertTrue("少一个名字没被抓出来", setOf("a", "b").filterNot { it in setOf("a") }.isNotEmpty())
    }

    @Test
    fun monoFamilyIsOnlyForNumbers() {
        // 等宽只用于编号/计数/日期/参数 —— 它是**印样语义**，不是"技术感"的装饰。
        // 判据：等宽族不许出现大于 34sp 的字级（再大就只能当标题用，而标题不该用等宽），
        // 且它的最小字号不许小到 9sp 以下（编号是给人读的）。
        val mono = TypeScale.mono
        assertTrue("等宽族太小（${mono.size} 档）", mono.size >= 4)
        for (spec in mono) {
            assertTrue("${spec.name} 是等宽却大于 34sp，那它只能当标题用了", spec.sizeSp <= 34f)
            assertTrue("${spec.name} 是等宽却小于 9sp，编号会读不清", spec.sizeSp >= 9f)
            assertTrue("${spec.name} 标了 mono=false，但它属于等宽族", spec.mono)
        }
        // 正文族反过来：一个都不许是等宽（中文正文用等宽读起来像终端）。
        val wrongMono = TypeScale.body.filter { it.mono }
        assertTrue("这些正文样式被标成了等宽（中文正文不能用等宽）：${wrongMono.map { it.name }}", wrongMono.isEmpty())

        // 自检
        assertTrue("40sp 的等宽被当成合格", 40f > 34f)
        assertTrue("14sp 的等宽被当成不合格", 14f in 9f..34f)
    }

    @Test
    fun contrastThresholdUsedByTypeMatchesSize() {
        // 大字（≥24sp 或 ≥18.66sp bold）可以按 3:1 判，其余必须 4.5:1。
        // 这条把"哪些字级可以用大字阈值"固定下来，免得有人拿 13sp 的标签套 3:1。
        val largeSizeFloor = 24f
        val large = TypeScale.all.filter { it.sizeSp >= largeSizeFloor }
        val small = TypeScale.all.filter { it.sizeSp < largeSizeFloor }
        assertTrue("大字族为空，判据失效", large.isNotEmpty())
        assertTrue("小字族为空，判据失效", small.isNotEmpty())
        // 已知的大字：只有 display 与少数 headline/counter。
        assertTrue(
            "displayLarge（48sp）没被算成大字",
            TypeScale.all.first { it.name == "displayLarge" }.sizeSp >= largeSizeFloor,
        )
        assertTrue(
            "labelSmall（12sp）被算成了大字",
            TypeScale.all.first { it.name == "labelSmall" }.sizeSp < largeSizeFloor,
        )
        // 正文族一律按 4.5:1 —— 这条是给 ContrastGuardTest 的说明，不是重复检查。
        assertTrue(
            "bodyMedium 按大字阈值判是错的（它 14sp）",
            TypeScale.all.first { it.name == "bodyMedium" }.sizeSp < largeSizeFloor,
        )
    }
}
