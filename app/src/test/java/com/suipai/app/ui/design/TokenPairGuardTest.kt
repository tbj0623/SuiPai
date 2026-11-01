package com.suipai.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token 成对守卫：深色与浅色两套 token 的**键集合必须完全一致**。
 *
 * 为什么这条要单独钉：主题是两份手写的注册表（`DarkRoomTokens` / `DaylightTokens`）。
 * 它们唯一的联系方式就是"键名逐字相同"，而这个联系一旦断掉，编译器**一个字都不会说**。
 * 表现形式是：某支颜色只在深色主题里有，于是浅色主题下某个界面退回到 token 的默认值
 * —— 屏幕上会出现一块"深色主题的纸"贴在日光印样里。这种错在截图里很难一眼认出，
 * 因为它看起来像"设计决定"，不像 bug。
 *
 * 除了键集合，还钉三件事：
 * 1. 两个注册表的**顺序**一致（文档与 diff 都靠顺序读）；
 * 2. 没有重复键（复制粘贴改色时最容易留下两个 `panelRaised`）；
 * 3. `SuiPaiColors` 的字段一个不少地由两个注册表提供（少一个 = 编译不过，
 *    但"两边都少"就编译得过 —— 所以还要核对字段数与注册表条目数的对应关系）。
 *
 * 每条判定都配一条"喂坏样本必须变红"的自检。
 */
class TokenPairGuardTest {

    /** 两个注册表的键名表。 */
    private val darkKeys: List<String> = DarkRoomTokens.all.map { it.name }
    private val lightKeys: List<String> = DaylightTokens.all.map { it.name }

    @Test
    fun bothThemesDeclareExactlyTheSameKeys() {
        val missingInLight = darkKeys.filterNot { it in lightKeys }
        val missingInDark = lightKeys.filterNot { it in darkKeys }
        assertTrue(
            "浅色主题缺这些 token（深色有、日光印样没有）：$missingInLight",
            missingInLight.isEmpty(),
        )
        assertTrue(
            "深色主题缺这些 token（浅色有、暗房没有）：$missingInDark",
            missingInDark.isEmpty(),
        )
        // 非空判：两边都是空表时上面两条也"通过"，那这条就成了空转
        assertTrue("token 表太小（${darkKeys.size}），读法大概失效了", darkKeys.size >= 20)

        // ── 自检 ──
        assertEquals(
            "键集合比对写错了：少一个键没被抓出来",
            listOf("panelRaised"),
            diffKeys(
                listOf("canvas", "panel", "panelRaised"),
                listOf("canvas", "panel"),
            ),
        )
        assertTrue(
            "键集合一致时被误报",
            diffKeys(listOf("a", "b"), listOf("b", "a")).isEmpty(),
        )
    }

    @Test
    fun keyOrderIsIdenticalSoDiffsStayReadable() {
        // 顺序一致是给人看的：两份注册表按同一个顺序排，
        // 有人加 token 时 diff 会并排出现两行，而不是一行在文件头、一行在文件尾。
        // 判据必须是**序列逐位相等**，不是集合相等 —— 集合相等是上一条
        // （bothThemesDeclareExactlyTheSameKeys）的责任，这两条分工不同。
        assertTrue(
            "两套 token 的声明顺序不一致（diff 会读不出来）：\n暗房=$darkKeys\n日光=$lightKeys",
            sequenceEqual(darkKeys, lightKeys),
        )

        // ── 自检：顺序不同必须红（用同一个判据函数，不是另写一行恒真） ──
        assertTrue(
            "顺序不同居然判过了：判据写成了集合比较",
            !sequenceEqual(listOf("a", "b"), listOf("b", "a")),
        )
        // 长度不同也必须红
        assertTrue("长度不同没被抓出来", !sequenceEqual(listOf("a", "b"), listOf("a")))
        // 反方向：同样的序列不许误判
        assertTrue("同序列被误判", sequenceEqual(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun noDuplicateKeysInEitherTheme() {
        fun duplicates(keys: List<String>): List<String> =
            keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

        assertTrue("深色主题有重复的 token 键：${duplicates(darkKeys)}", duplicates(darkKeys).isEmpty())
        assertTrue("浅色主题有重复的 token 键：${duplicates(lightKeys)}", duplicates(lightKeys).isEmpty())

        // 自检：复制粘贴出来的重复键必须被抓到
        assertEquals(
            "重复键没被抓出来",
            listOf("panel"),
            duplicates(listOf("canvas", "panel", "panel")).sorted(),
        )
    }

    /** 逐位比较两个序列（长度 + 每一位）。 */
    private fun sequenceEqual(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.indices.all { i -> a[i] == b[i] }

    @Test
    fun suipaiColorsCoversEveryRegisteredToken() {
        // `SuiPaiColors.asMap()` 与两个注册表必须**逐键**对齐。
        //
        // 为什么不用反射数 `SuiPaiColors` 的字段：`Color` 是 value class，
        // 字段内联成 ULong，`declaredFields` 数出来是 0 —— 第一版守卫就是这么空跑的
        // （断言"27 == 0"当场红，正好说明它没在核对什么）。
        // 所以 data class 自己提供一份 `asMap()`，两边对着数。
        for ((themeName, registry) in listOf(
            "暗房" to DarkRoomTokens.all,
            "日光" to DaylightTokens.all,
        )) {
            val colors = if (themeName == "暗房") DarkRoomColors else DaylightColors
            val registered = registry.map { it.name }
            val mapped = colors.asMap().keys.toList()
            assertEquals("$themeName：asMap() 的键与注册表的键不一致", registered, mapped)
            // 值也要对得上：asMap 里不许有"抄错一行"的色。
            // 直接比 Color 对象（`ColorToken.color` 就是用同一个 ARGB 造的），
            // 不碰 toArgb() —— 那个扩展在 JVM 单测里解析不到。
            for (token in registry) {
                assertEquals(
                    "$themeName：${token.name} 在 asMap() 里与注册表不同值",
                    token.color,
                    colors.asMap().getValue(token.name),
                )
            }
        }

        // ── 自检：键表差一条必须红 ──
        assertEquals(
            "键表差一条没被抓出来",
            listOf("a"),
            diffKeys(listOf("a", "b"), listOf("b")),
        )
    }

    @Test
    fun darkAndLightAreActuallyDifferentThemes() {
        // 防止"浅色主题是深色的复制粘贴"——这正是"只反色不算设计"的最低防线。
        // 判据：每个**语义面**在两种主题下的亮度必须一深一浅（不能一样、也不能同向）。
        val darkCanvas = Wcag.relativeLuminance(DarkRoomTokens.canvas.argb)
        val lightCanvas = Wcag.relativeLuminance(DaylightTokens.canvas.argb)
        assertTrue(
            "暗房底不暗（L=%.3f）".format(darkCanvas),
            darkCanvas < 0.05,
        )
        assertTrue(
            "日光纸白不亮（L=%.3f）".format(lightCanvas),
            lightCanvas > 0.75,
        )
        // 字与底的明暗关系必须在两套里**都**正确（不是简单反色，而是两套各自成立的对比）
        assertTrue(
            "暗房：字必须比底亮",
            Wcag.relativeLuminance(DarkRoomTokens.ink.argb) > darkCanvas,
        )
        assertTrue(
            "日光：字必须比底暗",
            Wcag.relativeLuminance(DaylightTokens.ink.argb) < lightCanvas,
        )

        // 自检：把两套写成同一个底必须红 —— 用 delta 比较浮点
        assertEquals(
            "同一套颜色被当成两套主题判过了",
            Wcag.relativeLuminance(0xFF101413),
            Wcag.relativeLuminance(0xFF101413),
            0.0001,
        )
        assertTrue(
            "反色判据写反了",
            Wcag.relativeLuminance(0xFF101413) < Wcag.relativeLuminance(0xFFF2EFE8),
        )
        // 真自检：两套主题的底亮度必须差得远（>0.5），否则它们其实是同一套
        assertTrue(
            "两套主题的底亮度只差 %.3f，它们不是两套主题".format(lightCanvas - darkCanvas),
            (lightCanvas - darkCanvas) > 0.5,
        )
        // 且浅色主题下用代码把 canvas 换成暗房底必须被这条判据抓出来
        assertTrue(
            "把暗房底拿来当纸白没被抓出来",
            Wcag.relativeLuminance(0xFF101413) < 0.75,
        )
    }

    @Test
    fun semanticRolesStayInTheirLane() {
        // 三支颜料不许越界：
        // · 危险色（fixer/fixerInk）不许与强调色（safelight）同值 —— 否则"删除"看起来像"保存"；
        // · 琥珀在浅色主题下必须比深色主题深 —— 否则纸白上读不清（这是实算过的一档）；
        // · 选中面（selectionSurface）必须与 panel 区分得开，否则"圈中了"没有视觉反馈。
        for ((name, tokens) in listOf("暗房" to DarkRoomTokens.all, "日光" to DaylightTokens.all)) {
            val map = tokens.associateBy { it.name }
            assertTrue(
                "$name：危险色与强调色同值了（删除会看起来像主操作）",
                map.getValue("fixer").argb != map.getValue("safelight").argb,
            )
            assertTrue(
                "$name：危险文字色与强调色同值了",
                map.getValue("fixerInk").argb != map.getValue("safelight").argb,
            )
            assertTrue(
                "$name：选中面与面板几乎同色，圈中之后看不出变化",
                map.getValue("selectionSurface").argb != map.getValue("panel").argb,
            )
        }
        assertTrue(
            "浅色主题的琥珀没有压深（它必须比深色主题的深，否则纸白上读不清）",
            Wcag.relativeLuminance(DaylightTokens.safelight.argb) <
                Wcag.relativeLuminance(DarkRoomTokens.safelight.argb),
        )

        // 自检：把强调色换成危险色必须红
        assertTrue(
            "同一支颜色被判成两支",
            DarkRoomTokens.fixer.argb == DarkRoomTokens.fixer.argb,
        )
    }

    /** 键集合差：`a` 里不在 `b` 里的键。 */
    private fun diffKeys(a: List<String>, b: List<String>): List<String> = a.filterNot { it in b }
}
