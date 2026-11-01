package com.suipai.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 矮屏（手机横屏）省纵向的三条不变量，同样用**读源码**的方式钉住。
 *
 * 这三条都是"设备上量得出来、编译器不报错、单测跑不出来"的那类：横屏原本被
 * 页眉 + 工具条 + 整行搜索框 + 日期行 + 底部导航条吃掉 591 px = 55 % 屏高，
 * 印样视口只剩 323 px（0.96 行）。改动之后是 381 px / 699 px（2.04 行）。
 * 钉住的是"别再退回去"，不是"当时改对了"。
 *
 * 每条判定都是纯函数，并且各自配一条故意喂坏例子的自检 —— 没见过自己变红的守卫不算守卫。
 */
class CompactChromeGuardTest {

    companion object {

        private fun codeLines(lines: List<String>): List<Pair<Int, String>> =
            lines.mapIndexedNotNull { i, raw ->
                val t = raw.substringBefore("//").trim()
                if (t.isEmpty() || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to t
            }

        /**
         * 绑到 `query` 的整行搜索框（`OutlinedTextField` + `fillMaxWidth`）没有对应的内嵌版。
         *
         * 内嵌版只在矮屏出现、整行版只在竖屏出现，所以两者必须同时存在于同一个文件里；
         * 只留整行版就是"横屏又白吃一行"。
         *
         * 故意只认 `value = query` 这一个命名约定：抽屉里的输入框（一瞬的配方搜索、
         * API Key、设备名）整行本来就是对的，它们绑的是别的状态名。
         */
        fun orphanFullWidthSearch(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            if (code.any { "AppInlineSearch(" in it.second }) return emptyList()
            return code.mapIndexedNotNull { i, (n, t) ->
                if (t == "OutlinedTextField(" &&
                    code.drop(i + 1).take(12).any { "value = query" in it.second }
                ) n else null
            }
        }

        /** 底部 `NavigationBar(` 上面没有 `if (!shortChrome)` 挡一道 → 矮屏会同时有底栏和页眉 tab。 */
        fun ungatedBottomBar(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            return code.mapIndexedNotNull { i, (n, t) ->
                if (t.startsWith("NavigationBar(") &&
                    code.take(i).none { "if (!shortChrome)" in it.second }
                ) n else null
            }
        }

        /**
         * 矮屏阈值的取值范围。手机横屏 411 dp、竖屏 915 dp、平板横屏 800 dp 都是实测值，
         * 阈值必须严格落在 411 与 800 之间，且内嵌搜索还得另有一道宽度闸门（横屏分屏窄到放不下）。
         */
        fun badCompactThresholds(src: String): List<String> {
            val out = mutableListOf<String>()
            val h = Regex("""screenHeightDp\s*<\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
            if (h == null) out += "找不到 screenHeightDp 阈值"
            else if (h !in 450..700) out += "高度阈值 $h 不在 450..700（横屏 411 / 平板横屏 800 / 竖屏 915）"
            val w = Regex("""screenWidthDp\s*>=\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
            if (w == null) out += "内嵌搜索没有宽度闸门（横屏分屏会被挤得出屏）"
            else if (w !in 480..700) out += "宽度阈值 $w 不在 480..700"
            return out
        }
    }

    @Test
    fun everySearchFieldHasAnInlineTwin() {
        val ui = File("src/main/java/com/suipai/app/ui")
        assertTrue("找不到 ui 源码根：${ui.absolutePath}", ui.isDirectory)
        val hits = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> orphanFullWidthSearch(f.readLines()).map { "${f.name}:$it" } }
            .toList()
        assertTrue("整行搜索框没有矮屏内嵌版：$hits", hits.isEmpty())
    }

    @Test
    fun bottomBarIsGoneOnShortViewports() {
        // 迁移账（见 docs/arch/APP-ARCH.md §八）：底栏与 `if (!shortChrome)` 那道闸门
        // 一起从 MainActivity.kt 搬到 nav/AppChrome.kt（P0-B）。判据与自检原样。
        val main = File("src/main/java/com/suipai/app/nav/AppChrome.kt")
        assertTrue("找不到底栏宿主 AppChrome.kt：${main.absolutePath}", main.isFile)
        val hits = ungatedBottomBar(main.readLines())
        assertTrue("底栏没有按矮屏收掉（横屏会白吃 272 px）：$hits", hits.isEmpty())
    }

    @Test
    fun compactThresholdSitsBetweenPortraitAndLandscape() {
        val f = File("src/main/java/com/suipai/app/ui/common/CompactChrome.kt")
        assertTrue("找不到矮屏判定文件：${f.absolutePath}", f.isFile)
        assertEquals("矮屏阈值不合理：${badCompactThresholds(f.readText())}", emptyList<String>(), badCompactThresholds(f.readText()))
    }

    @Test
    fun guardsActuallyCatch() {
        val dirtySearch = listOf(
            "fun A() {",
            "    OutlinedTextField(",
            "        value = query,",
            "        modifier = Modifier.fillMaxWidth(),",
            "    )",
            "}",
        )
        assertEquals(listOf(2), orphanFullWidthSearch(dirtySearch))
        val twinSearch = dirtySearch + "    AppInlineSearch(query, { query = it })"
        assertEquals(emptyList<Int>(), orphanFullWidthSearch(twinSearch))
        // 非搜索用的整行输入框（Key、设备名）不该被牵连
        val keyField = listOf("    OutlinedTextField(", "        value = keyDraft,")
        assertEquals(emptyList<Int>(), orphanFullWidthSearch(keyField))

        val dirtyBar = listOf("fun A() {", "    Scaffold(", "        bottomBar = {", "            NavigationBar(", "        }")
        assertEquals(listOf(4), ungatedBottomBar(dirtyBar))
        val gatedBar = listOf("fun A() {", "    Scaffold(", "        bottomBar = {", "            if (!shortChrome) {", "            NavigationBar(", "        }")
        assertEquals(emptyList<Int>(), ungatedBottomBar(gatedBar))

        assertTrue(badCompactThresholds("val a = cfg.screenHeightDp < 600 && cfg.screenWidthDp >= 560").isEmpty())
        assertEquals(
            "高度 1000 会把竖屏也算成矮屏：${badCompactThresholds("screenHeightDp < 1000 && screenWidthDp >= 560")}",
            listOf("高度阈值 1000 不在 450..700（横屏 411 / 平板横屏 800 / 竖屏 915）"),
            badCompactThresholds("screenHeightDp < 1000 && screenWidthDp >= 560"),
        )
        assertTrue(badCompactThresholds("screenHeightDp < 600").contains("内嵌搜索没有宽度闸门（横屏分屏会被挤得出屏）"))
    }
}
