package com.suipai.app.ui

import com.suipai.app.ui.camera.CameraFlag
import com.suipai.app.ui.gallery.AlbumFlag
import com.suipai.app.ui.gallery.AlbumWindow
import com.suipai.app.ui.gallery.GalleryFlag
import com.suipai.app.ui.yishun.LookFilter
import com.suipai.app.ui.yishun.YishunFlag
import com.suipai.app.ui.yishun.YishunFlagGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表驱动开关的三条不变量，全部跑在 JVM 上（不需要设备）。
 *
 * 动机是实测过的两个 bug，都不是编译能发现的：
 * 1. 一瞬「更多」抽屉里「闪比」和「适应」各出现了两次——一次来自 YishunFlag 表，
 *    一次来自抽屉里手写的同名 AppBarCell。两个格子控制同一个状态，
 *    用户点哪一个都可能和另一个显示的不一致；自动化扫出来的现象是「点了没反应」。
 * 2. 所有开关共用一个 SharedPreferences 文件，键名撞车会让两个功能互相覆盖，
 *    而且只在用户第二次改动时才看得出来。
 *
 * 这两类在设备上都表现为「界面有个按钮是死的」，抓一次要起模拟器、装包、走三层导航；
 * 而它们其实纯粹是表里的数据问题，所以钉在这里。
 */
class FlagTableGuardTest {

    private fun clashMessage(pairs: List<Triple<String, String, String>>): String {
        val byLabel = mutableMapOf<String, MutableList<String>>()
        for ((owner, off, on) in pairs) {
            for (l in listOf(off, on)) byLabel.getOrPut(l) { mutableListOf() }.add(owner)
        }
        return byLabel.filter { it.value.size > 1 }
            .map { (l, o) -> "「$l」同时属于 $o" }.joinToString("; ")
    }

    /** 同一个抽屉 = 同一组：组内两个开关的四条文案不能有任何交集。 */
    private fun assertGroupLabelsDistinct(name: String, groups: Map<*, List<Triple<String, String, String>>>) {
        groups.forEach { (group, pairs) ->
            val msg = clashMessage(pairs)
            assertTrue("$name 的 $group 抽屉里有同名格：$msg", msg.isEmpty())
        }
    }

    @Test
    fun yishunFlagGroupsHaveNoDuplicateLabels() {
        val byGroup = YishunFlag.entries.groupBy { it.group }
            .mapValues { (_, flags) -> flags.map { Triple(it.name, it.labelOff, it.labelOn) } }
        assertGroupLabelsDistinct("YishunFlag", byGroup)
    }

    @Test
    fun galleryAlbumCameraFlagGroupsHaveNoDuplicateLabels() {
        assertGroupLabelsDistinct(
            "GalleryFlag",
            GalleryFlag.entries.groupBy { it.group }
                .mapValues { (_, v) -> v.map { Triple(it.name, it.labelOff, it.labelOn) } },
        )
        assertGroupLabelsDistinct(
            "AlbumFlag",
            AlbumFlag.entries.groupBy { it.group }
                .mapValues { (_, v) -> v.map { Triple(it.name, it.labelOff, it.labelOn) } },
        )
        assertGroupLabelsDistinct(
            "CameraFlag",
            CameraFlag.entries.groupBy { it.group }
                .mapValues { (_, v) -> v.map { Triple(it.name, it.labelOff, it.labelOn) } },
        )
    }

    /** 一瞬的筛选抽屉把 LookFilter 表和 YishunFlag 的 FILTER 组排在一起，两边也不能撞名。 */
    @Test
    fun lookFilterDoesNotClashWithFilterGroupFlags() {
        val msg = clashMessage(
            LookFilter.entries.map { Triple("LookFilter.${it.name}", it.labelOff, it.labelOn) } +
                YishunFlag.entries.filter { it.group == YishunFlagGroup.FILTER }
                    .map { Triple("YishunFlag.${it.name}", it.labelOff, it.labelOn) },
        )
        assertTrue("一瞬筛选抽屉里有同名格：$msg", msg.isEmpty())
    }

    /** 相册列表的日期窗全在同一个抽屉里，且「仅…」与「含…」两套文案都不能重复。 */
    @Test
    fun albumWindowLabelsAreDistinct() {
        val pairs = AlbumWindow.entries.map { Triple(it.name, it.labelOff, it.labelOn) }
        val msg = clashMessage(pairs)
        assertTrue("相册日期窗里有同名格：$msg", msg.isEmpty())
        val empties = AlbumWindow.entries.map { it.empty }
        assertTrue(
            "相册日期窗的空态文案重复：${empties.groupBy { it }.filter { it.value.size > 1 }.keys}",
            empties.size == empties.distinct().size,
        )
    }

    /** 所有表共用一个 SharedPreferences 文件，键名必须全局唯一。 */
    @Test
    fun prefKeysAreGloballyUnique() {
        val keys = LookFilter.entries.map { it.prefKey } +
            YishunFlag.entries.map { it.prefKey } +
            GalleryFlag.entries.map { it.prefKey } +
            AlbumFlag.entries.map { it.prefKey } +
            AlbumWindow.entries.map { it.prefKey } +
            CameraFlag.entries.map { it.key }
        val dup = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("落盘键名撞车：$dup", dup.isEmpty())
    }

    /**
     * 一瞬「更多」抽屉里手写的那几格（不属于任何表）不能和表里的文案撞名。
     * 这份清单是故意抄一遍 UI 的：撞名 bug 正是出在「表 + 手写格」的交界处，
     * 只测表内部是抓不到的。加格子时同步这里，等于强制看一眼它和表里的谁重了。
     */
    @Test
    fun yishunMoreSheetInlineCellsDoNotClashWithTables() {
        val inline = listOf(
            "看成片", "看原图", "分屏", "分屏开", "居中", "左三分", "右三分",
            "存对比", "配方卡", "轻", "中", "重", "上一张", "下一张", "上个配方", "分享", "撤销",
            "复制名", "复制编号", "复制摘要", "复制强度", "复制微调",
        )
        val tabled = (
            YishunFlag.entries.flatMap { listOf(it.labelOff, it.labelOn) } +
                LookFilter.entries.flatMap { listOf(it.labelOff, it.labelOn) }
            ).toSet()
        val both = inline.filter { it in tabled }
        assertTrue("这些文案在抽屉里既有表里的格子又有手写的格子：$both", both.isEmpty())
        assertTrue(
            "手写格之间撞名：${inline.groupBy { it }.filter { it.value.size > 1 }.keys}",
            inline.size == inline.distinct().size,
        )
    }

    /**
     * 上面那些只覆盖得到表驱动的部分。手写格「同一个状态挂两把钥匙」得直接读源码才看得见：
     * 查看器的顶栏和底栏各有一颗 `AppBarCell(label = "更多", on = vSheet == 1, …)`，
     * 两格同时亮琥珀框，用户判断不出哪颗是"当前"——这是 2026-09-21 横屏审计量出来的。
     *
     * 判据故意收得很窄：**标签字面量相同、且 `on =` 是同一个非常量表达式**才算。
     * - `on = false` 的格子（如相机页三处「关闭」）不承载状态，而且分属互斥分支，
     *   把它们算成冲突是误报——第一版就是这么红的。
     * - 动态标签（when/if）不参与，免得把"两个抽屉各自合理复用同一个开关"误判成 bug。
     */
    @Test
    fun noTwoHandWrittenCellsForTheSameState() {
        val root = java.io.File("src/main/java/com/suipai/app")
        assertTrue("找不到源码根，工作目录变了：${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> duplicateStateCellGroups(f.readLines()).map { "${f.name}$it" } }
            .toList()
        assertTrue("同一个状态挂了两把一模一样的钥匙：$hits", hits.isEmpty())
    }

    /** 自检：判定真的会因为"两格同标签同状态"而变红，也不会把注释/常量 on/动态标签算成违规。 */
    @Test
    fun duplicateToggleCellCheckerActuallyCatches() {
        val dirty = listOf(
            "fun A() {",
            "    AppBarCell(label = \"更多\", on = vSheet == 1, onClick = { vSheet = 1 })",
            "    Row {",
            "        AppBarCell(",
            "            label = \"更多\",",
            "            on = vSheet == 1,",
            "            onClick = { vSheet = 1 },",
            "        )",
            "    }",
            "}",
        )
        assertEquals(
            listOf("「更多」(on = vSheet == 1) 出现在行 [2, 4]"),
            duplicateStateCellGroups(dirty),
        )

        val clean = listOf(
            "fun A() {",
            "    // AppBarCell(label = \"更多\", on = vSheet == 1) 这是注释里的旧代码",
            "    AppBarCell(label = \"更多\", on = vSheet == 1, onClick = { vSheet = 1 })",
            "    AppBarCell(label = \"收藏\", on = vSheet == 1, onClick = { vSheet = 1 })",
            "    AppBarCell(label = if (x) \"甲\" else \"乙\", on = vSheet == 1, onClick = {})",
            "    AppBarCell(label = \"关闭\", on = false, onClick = onClose)",
            "    AppBarCell(label = \"关闭\", on = false, onClick = onClose)",
            "}",
        )
        assertTrue("注释/异标签/动态标签/常量 on 被误判：${duplicateStateCellGroups(clean)}",
            duplicateStateCellGroups(clean).isEmpty())
        // 抽取器只留下带状态的两格；注释里那格、动态标签那格和两格 on=false 的「关闭」都按定义丢掉
        assertEquals(
            listOf(
                Triple(3, "更多", "vSheet == 1"),
                Triple(4, "收藏", "vSheet == 1"),
            ),
            stateToggleCells(clean),
        )
    }

    /**
     * **有页面、无入口**：带参数的 `AppRoute` 子类必须在 `AppRoute.kt` 之外真的被构造过一次。
     *
     * 这一条来自本轮实修的三处：`RecipeCard` / `CollectionsScreen` 的「加入选中」/ `CastIntent`，
     * 三者都是「路由与页面全齐、就是没人 push」「函数写好、就是没人调」——
     * 编译绿、单测绿、装机点半天也点不出来，因为**入口压根不存在**。
     * 这类缺陷没有任何编译器或运行时信号，只能靠"扫一遍有没有人用"。
     *
     * 判据故意只认**带参数的 data class**：
     * - `data object`（`Settings` / `Trash` …）在 `Tabs` 那张表里被引用，恒有"用法"，判不出东西；
     * - 无参 data class 不存在，所以 `RecipeCard` 这种"必须带三个参数才画得对"的最容易被漏。
     *
     * 证据区间**不含 `AppRoute.kt` 自身**（`decode()` 里就在构造这些类），
     * 否则"文件里提到了这个名字"会让这条判据永真 —— `docs/handoff.md` §8.3 那条。
     */
    @Test
    fun everyParameterisedRouteHasAnEntry() {
        val routeFile = java.io.File("src/main/java/com/suipai/app/state/AppRoute.kt")
        assertTrue("找不到 AppRoute.kt：${routeFile.absolutePath}", routeFile.isFile)
        val sources = java.io.File("src/main/java/com/suipai/app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AppRoute.kt" }
            .map { it.name to it.readText() }
            .toList()
        assertTrue("扫到的源文件太少（${sources.size}），工作目录大概变了", sources.size >= 30)

        val declared = parameterisedRoutes(routeFile.readText())
        assertTrue("一个带参数的路由都没抽到，正则失效了：$declared", declared.size >= 8)
        val orphans = routesWithoutEntry(declared, sources)
        assertTrue(
            "这些路由定义了、也画得出来，但全仓没有一处构造它 —— " +
                "用户永远到不了那一屏（本轮修的是 `RecipeCard` 这一类）：$orphans",
            orphans.isEmpty(),
        )

        // 自检：同一份判据，把最后一个使用点拿掉必须立刻变红；
        // 只留在 AppRoute.kt 里的自引用（decode 会在那儿构造）不许算成入口。
        val fakeRoute = """
            data class Ghost(val a: String) : AppRoute
        """.trimIndent()
        val fakeDeclared = parameterisedRoutes(fakeRoute)
        assertEquals("自检前提：假路由没被抽出来", listOf("Ghost"), fakeDeclared)
        assertEquals(
            "没人构造的路由没被判出来",
            listOf("Ghost"),
            routesWithoutEntry(fakeDeclared, listOf("A.kt" to "fun f() { nav.push(other()) }")),
        )
        assertTrue(
            "有一处构造的路由被误判：" + routesWithoutEntry(fakeDeclared, listOf("A.kt" to "nav.push(AppRoute.Ghost(x))")),
            routesWithoutEntry(fakeDeclared, listOf("A.kt" to "nav.push(AppRoute.Ghost(x))")).isEmpty(),
        )
        assertTrue(
            "别名导入的构造点被误判：" +
                routesWithoutEntry(
                    fakeDeclared,
                    listOf(
                        "A.kt" to "import com.suipai.app.state.AppRoute.Ghost",
                        "B.kt" to "nav.push(Ghost(x))",
                    ),
                ),
            routesWithoutEntry(
                fakeDeclared,
                listOf(
                    "A.kt" to "import com.suipai.app.state.AppRoute.Ghost",
                    "B.kt" to "nav.push(Ghost(x))",
                ),
            ).isEmpty(),
        )
    }

    /**
     * 用**真源码**再喂一次坏例子：把 `AppNavHost` 里那一行 `nav.push(AppRoute.RecipeCard(...))`
     * 抹掉（也就是本轮动手之前的样子），判据必须立刻把 `RecipeCard` 报成孤儿。
     *
     * 只用假源码的自检有个盲区：它证明得了"函数会判"，证明不了"真源码读得到"——
     * 路径写错、扫到的文件太少、正则对不上真写法，假样本全绿而真守卫空转。
     */
    @Test
    fun removingTheEntryMakesTheRealRouteAnOrphan() {
        val routeFile = java.io.File("src/main/java/com/suipai/app/state/AppRoute.kt")
        val declared = parameterisedRoutes(routeFile.readText())
        assertTrue("带三个参数的 `RecipeCard` 没被抽出来，抽法失效：$declared", "RecipeCard" in declared)

        val navHost = java.io.File("src/main/java/com/suipai/app/nav/AppNavHost.kt")
        assertTrue("找不到 AppNavHost.kt：${navHost.absolutePath}", navHost.isFile)
        val real = navHost.readText()
        assertTrue("AppNavHost 里已经找不到 RecipeCard 的构造点了（本轮接的入口被人拆了）",
            real.contains("AppRoute.RecipeCard("))

        val beforeFix = listOf("AppNavHost.kt" to real.replace("AppRoute.RecipeCard(", "已拆掉（"))
        val orphans = routesWithoutEntry(declared, beforeFix)
        assertTrue("抹掉入口之后 `RecipeCard` 没被判成孤儿：$orphans", "RecipeCard" in orphans)
        // 反向：原样的源码必须干净，否则上面那条只是"永远报一个"。
        assertTrue(
            "原样源码里 `RecipeCard` 被误判成孤儿：" + routesWithoutEntry(declared, listOf("AppNavHost.kt" to real)),
            "RecipeCard" !in routesWithoutEntry(declared, listOf("AppNavHost.kt" to real)),
        )
    }

    companion object {
        /** `data class Foo(...) : AppRoute` 的类名。**只要带参数的那些**。 */
        fun parameterisedRoutes(routeSource: String): List<String> =
            Regex("""data\s+class\s+(\w+)\s*\([^)]*\)\s*:\s*AppRoute""")
                .findAll(routeSource)
                .map { it.groupValues[1] }
                .distinct()
                .sorted()
                .toList()

        /**
         * 在 `sources`（**(文件名, 全文)**，**不含 AppRoute.kt**）里找不到构造点的路由名。
         *
         * 两种"用到了"都算：`AppRoute.X(` 的直接构造，以及 `import …AppRoute.X` 之后的别名构造。
         * 前者是这一仓的标准写法，后者是为了不把"另一个文件里 `X(...)`"误报成没有入口。
         */
        fun routesWithoutEntry(declared: List<String>, sources: List<Pair<String, String>>): List<String> =
            declared.filter { name ->
                sources.none { (_, text) ->
                    text.contains("AppRoute.$name(") ||
                        text.contains("import com.suipai.app.state.AppRoute.$name")
                }
            }

        /**
         * 扫出带状态的 `AppBarCell(label = "字面量", on = 表达式)`，返回 (行号, 标签, on 表达式)。
         * 跨行调用按括号配平拼成一段再取，因为表驱动那几处的写法是参数各占一行。
         * `on = true/false` 的格子不承载状态，直接排除。
         */
        fun stateToggleCells(lines: List<String>): List<Triple<Int, String, String>> {
            val code = lines.map { it.substringBefore("//") }
            val out = mutableListOf<Triple<Int, String, String>>()
            var i = 0
            while (i < code.size) {
                val at = code[i].indexOf("AppBarCell(")
                if (at < 0) { i++; continue }
                val body = StringBuilder()
                var depth = 0
                var j = i
                while (j < code.size) {
                    val seg = if (j == i) code[j].substring(at) else code[j]
                    body.append(seg).append('\n')
                    for (c in seg) when (c) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    if (depth <= 0) break
                    j++
                }
                val text = body.toString()
                val label = Regex("""label\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
                val on = Regex("""[,(]\s*on\s*=\s*([^,\n]+)""").find(text)?.groupValues?.get(1)?.trim()
                if (label != null && on != null && on != "false" && on != "true") {
                    out += Triple(i + 1, label, on)
                }
                i = j + 1
            }
            return out
        }

        /** 同一个文件里，(标签, 状态表达式) 完全相同的格子 → 冲突描述清单。 */
        fun duplicateStateCellGroups(lines: List<String>): List<String> =
            stateToggleCells(lines)
                .groupBy { it.second to it.third }
                .filter { it.value.size > 1 }
                .map { (k, v) -> "「${k.first}」(on = ${k.second}) 出现在行 ${v.map { it.first }}" }
                .sorted()
    }
}
