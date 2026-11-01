package com.suipai.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏三颗 tab 的名字，用**读源码**的方式钉住形状。
 *
 * 钉的是装机量到的那一件事（`C:\tmp\r5\land\dump_navtabs.txt`）：三颗 tab 在语义树里
 * 自己什么都不说 —— `nav_tab_0/1/2` 那三个节点 `text=''`、`desc=''`，「照片 / 相册 / 互通」
 * 只写在它们**子节点**的 TextView 上。读屏用户落到可点那一颗上时，听不到"这是什么"。
 *
 * 为什么不做成运行时断言：这台机器起不了 TalkBack / 无障碍服务，朗读结果量不到；
 * 语义树那一边只有持设备的 lane 能 dump。所以这一篇钉形状，运行时那一步交给 dump 复核 ——
 * 两件事分开说，不把"量不到"写成"已修"。
 *
 * 为什么形状只能是"可点那一颗自己带名字"（compose-bom 2024.12.01：ui 1.7.6 / material3 1.3.1）：
 * 1. `Modifier.clickable` 无条件带 mergeDescendants（Clickable.kt:1107
 *    `shouldMergeDescendantSemantics get() = true`），所以 `NavigationBarItem` 那一颗永远是合并节点；
 * 2. 无障碍桥只在「这颗不合并」或「合并了、但子树里没有可合并的节点」时，才把节点自己的
 *    contentDescription 写进 AccessibilityNodeInfo
 *    （AndroidComposeViewAccessibilityDelegateCompat:842-850）；
 * 3. 带可见 label 的 tab 两条都不满足 —— 那个名字就被改投到一颗**不可点的假子节点**上
 *    （SemanticsNode.kt:407-419「Fake node for contentDescription clobbering issue」），
 *    可点那一颗仍然 `desc=''`。本仓在 `AppBarPrimary` 上量到过同一个形状（AppBar.kt:94-97）；
 * 4. 反过来把 `clearAndSetSemantics` 挂到 `NavigationBarItem` 的 modifier 上也不行：
 *    它的 clickable 更靠内，`LayoutNode.collapseSemantics`（422-447）从里往外收，
 *    遇到清空就把已经收进来的整片重置掉 —— click 动作一起没了。
 * 所以只剩一种写法：这一格的可点节点自己拿（`Row` + `.clickable(role = Role.Tab)` 在前、
 * `.clearAndSetSemantics { contentDescription = …; selected = … }` 在后），里面的
 * `NavigationBarItem` 只负责画。清空子树顺带把 label 那颗 TextView 摘掉，名字只有一处出声；
 * 选中态走 `selected`（Role.Tab 下桥会写 `info.isSelected`），不并进文字，
 * 免得「已选中」念两遍。
 *
 * 判定做成纯函数，并带"故意喂坏例子"的自检：没见过自己变红的守卫不算守卫。
 * 证据区间只取 `NavigationBar(` 那一颗调用的范围（全是调用点，没有定义处），
 * 免得"提到了某个东西"因为签名行而永真。
 */
class NavTabSemanticsGuardTest {

    companion object {

        /**
         * 从 `from` 这一行起，按 `open`/`close` 配平取出那一段调用的参数区（含收尾那一行），拼成一句。
         *
         * 判定必须只在这一段里做完：固定行数的窗口会把隔壁那段的话当成它说的 ——
         * 变异实验里删掉 `selected = tabOn` 后判据仍然绿，就是因为窗口往后吃进了
         * `NavigationBarItem(selected = tabOn, …)` 那一个参数。
         */
        fun argsOf(code: List<Pair<Int, String>>, from: Int, open: Char, close: Char): String {
            var depth = 0
            var seen = false
            var i = from
            while (i < code.size) {
                val t = code[i].second
                depth += t.count { it == open } - t.count { it == close }
                if (depth > 0) seen = true
                i++
                if (seen && depth <= 0) break
            }
            return code.subList(from, minOf(code.size, i)).joinToString(" ") { it.second }
        }

        /**
         * 从 `from` 这一行起，按圆括号 + 花括号一起配平取出整段调用（含尾随 lambda 的收尾 `}`）。
         *
         * 只数圆括号会在这里当场失效：`NavigationBar(` 的参数在 `) {` 那一行刚好闭合，
         * 而三颗 tab 全写在尾随 lambda 里 —— 区间会缩成三行，判据变成空跑。
         */
        fun callBlock(code: List<Pair<Int, String>>, from: Int): List<Pair<Int, String>> {
            var paren = 0
            var brace = 0
            var seen = false
            var i = from
            while (i < code.size) {
                val t = code[i].second
                paren += t.count { it == '(' } - t.count { it == ')' }
                brace += t.count { it == '{' } - t.count { it == '}' }
                if (paren > 0 || brace > 0) seen = true
                i++
                if (seen && paren <= 0 && brace <= 0) break
            }
            return code.subList(from, minOf(code.size, i))
        }

        /** 一格里"报名字"的那一行：`contentDescription = <非 null 的值>`。返回 (行号, 值)。 */
        fun nameCarriers(block: List<Pair<Int, String>>): List<Pair<Int, String>> =
            block.mapNotNull { (n, t) ->
                val v = Regex("contentDescription\\s*=\\s*([^,)]+)").find(t)?.groupValues?.get(1)?.trim()
                if (v.isNullOrEmpty() || v == "null") null else n to v
            }

        /**
         * 底栏每一颗 tab：可点那一颗必须自己带名字与选中态，并且全格只许有一处报名字。
         *
         * 返回 `核对到的格数 to 违规列表`。三颗 tab 共用一个 `forEachIndexed`，源码里只有
         * 一处 `testTag("nav_tab_`，所以格数 1 是对的（运行时它长出三颗节点，那一步靠 dump 复核）；
         * 但 0 就是读法失效，必须报出来。
         */
        fun navTabsThatDoNotNameThemselves(lines: List<String>): Pair<Int, List<String>> {
            val code = PerfSourceGuardTest.codeLines(lines)
            val barAt = code.indexOfFirst { Regex("(^|[^\\w.])NavigationBar\\(").containsMatchIn(it.second) }
            if (barAt < 0) return 0 to listOf("<没找到 NavigationBar( 那一段，读法失效>")
            val barLine = code[barAt].first
            val block = callBlock(code, barAt)
            val sites = block.mapIndexedNotNull { n, pair ->
                if (pair.second.contains("testTag(\"nav_tab_")) n else null
            }
            if (sites.isEmpty()) return 0 to listOf("<$barLine: NavigationBar 里没有 nav_tab_ 的 testTag，读法失效>")
            val out = ArrayList<String>()
            for (s in sites) {
                val line = block[s].first
                val tail = block.subList(s, minOf(block.size, s + 14))
                val texts = tail.map { it.second }
                val clickAt = texts.indexOfFirst { it.contains(".clickable(") }
                val clearAt = texts.indexOfFirst { it.contains("clearAndSetSemantics") }
                if (clickAt < 0) {
                    out += "$line：可点那一颗不在带 nav_tab_ 标签的节点上（读屏只报「标签」，不报这是照片还是互通）"
                }
                if (clearAt < 0) {
                    out += "$line：没有 clearAndSetSemantics —— 名字落不到可点那颗，会另长出一颗不可点的假子节点"
                }
                if (clickAt >= 0 && clearAt in 0 until clickAt) {
                    out += "$line：clearAndSetSemantics 写在 clickable 之前（会把 click 动作一起清空，量过 click=false）"
                }
                if (clickAt >= 0 && !argsOf(tail, clickAt, '(', ')').contains("role = Role.Tab")) {
                    out += "$line：可点那颗没报 role=Tab（读屏不说这是一颗标签）"
                }
                if (clearAt >= 0) {
                    val body = argsOf(tail, clearAt, '{', '}')
                    if (!Regex("contentDescription\\s*=\\s*(?!null)").containsMatchIn(body)) {
                        out += "$line：clearAndSetSemantics 块里没写 contentDescription（名字被清掉了）"
                    }
                    if (!Regex("selected\\s*=").containsMatchIn(body)) {
                        out += "$line：clearAndSetSemantics 块里没写 selected（子树被清空后，选中态就没人写了）"
                    }
                }
            }
            val named = nameCarriers(block)
            if (named.size != sites.size) {
                out += "$barLine：底栏里报名字的 contentDescription 有 ${named.size} 处，应当每颗 tab 一处" +
                    "（图标那颗再带一份就是让读屏念两遍）"
            }
            for ((line, expr) in named) {
                if (block.count { it.second.contains(expr) } < 2) {
                    out += "$line：读屏念的「$expr」在可见文字里找不到第二处，标签会和屏幕上的字不一致"
                }
            }
            return sites.size to out
        }
    }

    private val mainActivity: File
        get() {
            // 迁移账（见 docs/arch/APP-ARCH.md §八）：底栏 + 印样页眉从 MainActivity.kt
            // 逐行搬到 nav/AppChrome.kt（P0-B 架构重构）。判据、检查项、自检坏样本一个字没改，
            // 只是把扫描目标指向新宿主 —— 这是「改路径不改意图」。
            val f = File("src/main/java/com/suipai/app/nav/AppChrome.kt")
            assertTrue("找不到底栏宿主 AppChrome.kt：${f.absolutePath}", f.isFile)
            return f
        }

    /** 一条判定 = 一个坏样本 + 一处"改对了不许误判"。 */
    private val goodShape = listOf(
        "                                NavigationBar(",
        "                                    containerColor = MaterialTheme.colorScheme.surface,",
        "                                    tonalElevation = 0.dp,",
        "                                ) {",
        "                                    tabNames.forEachIndexed { i, icon ->",
        "                                        val tabOn = tab == i",
        "                                        // 注释里写满 .clickable( 与 clearAndSetSemantics 也不算数",
        "                                        Box(",
        "                                            modifier = Modifier",
        "                                                .weight(1f)",
        "                                                .testTag(\"nav_tab_\$i\")",
        "                                                .clickable(",
        "                                                    interactionSource = null,",
        "                                                    indication = null,",
        "                                                    role = Role.Tab,",
        "                                                    onClick = { tab = i },",
        "                                                )",
        "                                                .clearAndSetSemantics {",
        "                                                    contentDescription = tabNames[i]",
        "                                                    selected = tabOn",
        "                                                },",
        "                                        ) {",
        "                                            Row(Modifier.fillMaxWidth()) {",
        "                                                NavigationBarItem(",
        "                                                    selected = tabOn,",
        "                                                    onClick = { tab = i },",
        "                                                    icon = { Icon(icon, contentDescription = null) },",
        "                                                    label = { Text(tabNames[i], style = SuiPaiSheet.type.frameNo) },",
        "                                                )",
        "                                            }",
        "                                        }",
        "                                    }",
        "                                }",
    )

    /** 真源码：底栏那三颗必须自己报名字、自己带选中态。 */
    @Test
    fun bottomNavTabsNameThemselvesOnTheClickableNode() {
        val (checked, bad) = navTabsThatDoNotNameThemselves(mainActivity.readLines())
        assertTrue("只核对到 $checked 处 nav_tab_ 标签，读法大概失效了（正则不匹配了也要红）", checked >= 1)
        assertTrue(
            "底栏的可点节点又不自己报「照片 / 相册 / 互通」了 —— 装机量到的正是这一条：" +
                "nav_tab_* 的 text 与 content-desc 全空，名字只在子 TextView 上：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )
    }

    @Test
    fun checkerCatchesEveryShapeWeMeasured() {
        assertEquals(
            "改对的形状不许误判（注释里提到的那些词也不许算）：" + navTabsThatDoNotNameThemselves(goodShape).second,
            listOf<String>(),
            navTabsThatDoNotNameThemselves(goodShape).second,
        )
        assertEquals("好样本应当核对到一处标签", 1, navTabsThatDoNotNameThemselves(goodShape).first)

        // 0.6.0 装机那一份（改前的 MainActivity.kt:878-918 原文形状）必须变红
        val shipped = listOf(
            "                                NavigationBar(",
            "                                    containerColor = MaterialTheme.colorScheme.surface,",
            "                                ) {",
            "                                    tabNames.forEachIndexed { i, icon ->",
            "                                        val tabOn = tab == i",
            "                                        NavigationBarItem(",
            "                                            modifier = Modifier.testTag(\"nav_tab_\$i\"),",
            "                                            selected = tabOn,",
            "                                            onClick = { tab = i },",
            "                                            icon = {",
            "                                                Icon(",
            "                                                    icon,",
            "                                                    contentDescription = tabNames[i],",
            "                                                    tint = if (tabOn) sheetColors.grease",
            "                                                    else MaterialTheme.colorScheme.onSurfaceVariant,",
            "                                                )",
            "                                            },",
            "                                            label = { Text(tabNames[i], style = SuiPaiSheet.type.frameNo) },",
            "                                        )",
            "                                    }",
            "                                }",
        )
        val (n, bad) = navTabsThatDoNotNameThemselves(shipped)
        assertEquals("改前那份也该核对到一处标签", 1, n)
        assertEquals(
            "改前的形状（名字挂在 Icon / label 的子节点上）必须报出这两条",
            listOf(
                "7：可点那一颗不在带 nav_tab_ 标签的节点上（读屏只报「标签」，不报这是照片还是互通）",
                "7：没有 clearAndSetSemantics —— 名字落不到可点那颗，会另长出一颗不可点的假子节点",
            ),
            bad,
        )

        // 顺序反了：清空写在可点之前 —— 会把 click 动作一起清掉（装机在 AppBarPrimary 上量过）
        val reversed = listOf(
            "NavigationBar(",
            "    tabNames.forEachIndexed { i, icon ->",
            "        Row(",
            "            modifier = Modifier",
            "                .testTag(\"nav_tab_\$i\")",
            "                .clearAndSetSemantics {",
            "                    contentDescription = tabNames[i]",
            "                    selected = tabOn",
            "                },",
            "                .clickable(role = Role.Tab, onClick = { tab = i }),",
            "        ) { NavigationBarItem(label = { Text(tabNames[i]) }) }",
            "    }",
            ")",
        )
        assertEquals(
            "clearAndSetSemantics 写在 clickable 之前必须判出来",
            listOf("5：clearAndSetSemantics 写在 clickable 之前（会把 click 动作一起清空，量过 click=false）"),
            navTabsThatDoNotNameThemselves(reversed).second,
        )

        assertTrue(
            "少了 role=Tab 必须判出来：" + navTabsThatDoNotNameThemselves(
                goodShape.filterNot { it.contains("role = Role.Tab,") },
            ).second,
            navTabsThatDoNotNameThemselves(goodShape.filterNot { it.contains("role = Role.Tab,") }).second
                .any { it.contains("没报 role=Tab") },
        )
        val noSelected = goodShape.filterNot { it.contains("selected = tabOn") }
        assertTrue(
            "少了选中态必须判出来：" + navTabsThatDoNotNameThemselves(noSelected).second,
            navTabsThatDoNotNameThemselves(noSelected).second.any { it.contains("没写 selected") },
        )
        val noName = goodShape.map {
            if (it.contains("contentDescription = tabNames[i]")) "                                                    // 忘了报名字" else it
        }
        assertTrue(
            "清空块里没写 contentDescription 必须判出来：" + navTabsThatDoNotNameThemselves(noName).second,
            navTabsThatDoNotNameThemselves(noName).second.any { it.contains("没写 contentDescription") },
        )
        val iconAlsoSpeaks = goodShape.map {
            it.replace(
                "icon = { Icon(icon, contentDescription = null) }",
                "icon = { Icon(icon, contentDescription = tabNames[i]) }",
            )
        }
        assertTrue(
            "图标再自带一份名字必须判出来（重复朗读的来源）：" + navTabsThatDoNotNameThemselves(iconAlsoSpeaks).second,
            navTabsThatDoNotNameThemselves(iconAlsoSpeaks).second.any { it.contains("念两遍") },
        )
        val spokenElsewhere = goodShape.map {
            it.replace("contentDescription = tabNames[i]", "contentDescription = \"首页\"")
        }
        assertTrue(
            "读屏念的和屏幕上写的不是一句时必须判出来：" + navTabsThatDoNotNameThemselves(spokenElsewhere).second,
            navTabsThatDoNotNameThemselves(spokenElsewhere).second.any { it.contains("和屏幕上的字不一致") },
        )
        assertEquals(
            "读不到 NavigationBar 时要报「读法失效」，不许悄悄算通过",
            listOf("<没找到 NavigationBar( 那一段，读法失效>"),
            navTabsThatDoNotNameThemselves(listOf("fun A() {", "    Column { }", "}")).second,
        )
        assertEquals(
            "有 NavigationBar 却没有 nav_tab_ 标签，同样要报读法失效",
            listOf("<1: NavigationBar 里没有 nav_tab_ 的 testTag，读法失效>"),
            navTabsThatDoNotNameThemselves(
                listOf("NavigationBar(", "    tonalElevation = 0.dp,", "    ) {", "        Spacer(Modifier.height(0.dp))", "    }"),
            ).second,
        )
    }
}
