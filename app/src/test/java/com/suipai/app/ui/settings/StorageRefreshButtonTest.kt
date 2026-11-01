package com.suipai.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「长得像"只看一眼"的那颗按钮，不许顺手改东西」。
 *
 * ## 这条测试挡的是什么
 *
 * `StorageScreen` 缓存那一行并排放着两颗：
 *  - 「清除缓存」（`storage_clear_cache`，主按钮，真的删文件）；
 *  - 「刷新占用」（`storage_refresh`，副按钮，只该重新量一次数字）。
 *
 * 改前第二颗写的是 `onClick = { onAction(SettingAction.CLEAR_CACHE) }` ——
 * **和第一颗一模一样**。于是用户点一个只该读一眼的动作，可再生的临时文件被真删掉了；
 * 而界面给出的那句结论（"清了 X"）看起来还像是"刷新"成功了。
 * 这比"点了没反应"更糟：它做了一件用户没要求的事。
 *
 * ## 判据
 *
 * 读源码，抓 `testTag("storage_refresh")` 那一颗 `AppBarCell` 的 `onClick`，
 * 它**不许**引用 `SettingAction.CLEAR_CACHE`（任何"改"的动词都不许）。
 * 反过来，`storage_clear_cache` 那一颗必须仍然引用它 —— 否则这条判据可能只是
 * "两颗都坏了"恰好也通过。
 *
 * 自检：把坏形状喂进同一个抽取函数，必须变红。
 */
class StorageRefreshButtonTest {

    companion object {
        private const val SCREEN = "src/main/java/com/suipai/app/ui/settings/StorageScreen.kt"

        /**
         * 去掉注释后的源码。
         *
         * **必须先去注释再判**：这一颗格子的注释里正写着
         * "这一颗原来写的是 `onAction(SettingAction.CLEAR_CACHE)`" —— 也就是被检词
         * 出现在了它的**说明处**。守卫的证据区间不许含这个形状，否则"解释了 bug"本身就
         * 会让守卫变红（本仓库 §8 第 3 条：证据区间不含被检词的定义处）。
         *
         * 只处理单行 `//` 与块注释两种；这一屏没有把 `//` 写进字符串字面量的地方。
         */
        fun withoutComments(text: String): String = text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { line ->
                val at = line.indexOf("//")
                if (at < 0) line else line.substring(0, at)
            }

        /**
         * 抓某一颗格子（按它的 `testTag`）的**实参段**。
         *
         * 这一屏两颗按钮用的不是同一个组件：主按钮是 `AppBarPrimary`（没有 `on` 参数），
         * 副按钮是 `AppBarCell`。只认其中一个会让另一半"抽不到"——
         * 而"抽不到"在 `cellInvokes` 里会读成 false，于是反向的那条断言（
         * "清除缓存那颗必须还连着 CLEAR_CACHE"）会因为抽不到而红，
         * 正向那条（"刷新那颗不许连着它"）会因为抽不到而恒绿。两种都是错的判据。
         *
         * 从 `testTag("<tag>")` 往前找到最近的组件调用起点，再往后按括号配对找它的右括号。
         * 用配对而不是"到下一个 `),`"：这一屏的格子实参里带着嵌套调用
         * （`onAction(SettingAction.X)`、`Modifier.testTag(...)`），
         * 第一个 `),` 会落在 `testTag("…"),` 上，把整段 onClick 切掉 —— 那样
         * "刷新那颗调了什么"就永远读不到（判据恒绿）。
         */
        fun cellBlock(text: String, tag: String): String? {
            val clean = withoutComments(text)
            val tagAt = clean.indexOf("testTag(\"$tag\")")
            if (tagAt < 0) return null
            val start = COMPONENTS
                .map { clean.lastIndexOf("$it(", tagAt) }
                .maxOrNull() ?: return null
            if (start < 0) return null
            var depth = 0
            var i = start + COMPONENTS.first { clean.startsWith("$it(", start) }.length
            while (i < clean.length) {
                when (clean[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return clean.substring(start, i + 1)
                    }
                }
                i++
            }
            return null
        }

        /** 这一屏按钮用到的组件名。 */
        private val COMPONENTS = listOf("AppBarCell", "AppBarPrimary")

        /** 这颗格子的 onClick 有没有引用某个动作。 */
        fun cellInvokes(text: String, tag: String, action: String): Boolean {
            val block = cellBlock(text, tag) ?: return false
            return "SettingAction.$action" in block
        }
    }

    private fun screenText(): String {
        val f = File(SCREEN)
        assertTrue("找不到 StorageScreen.kt：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun theRefreshButtonDoesNotDeleteAnything() {
        val text = screenText()
        assertFalse(
            "「刷新占用」按下去会执行 CLEAR_CACHE（也就是删缓存）—— 用户点一个只该读一眼的动作，" +
                "结果文件被真删掉了。它应该只重量一次数字。",
            cellInvokes(text, "storage_refresh", "CLEAR_CACHE"),
        )
        // 反向非空判：真正该删的那颗必须还连着它，否则上面那条可能是"两个都废了"。
        assertTrue(
            "「清除缓存」那颗不执行 CLEAR_CACHE 了 —— 那它点了也不会清任何东西",
            cellInvokes(text, "storage_clear_cache", "CLEAR_CACHE"),
        )
    }

    @Test
    fun theRefreshButtonActuallyHasAReader() {
        val text = screenText()
        val block = cellBlock(text, "storage_refresh")
        assertTrue("抽不到 storage_refresh 那颗格子，判据失效", block != null)
        assertTrue(
            "「刷新占用」的 onClick 是一个空回调或只调 onAction —— 它需要一条自己的出路" +
                "（改前它复用了 CLEAR_CACHE，正是这个 bug）。实际：$block",
            "onRefreshNumbers" in block.orEmpty(),
        )
    }

    @Test
    fun theGuardCatchesTheBugShape() {
        // 改前那一版（真源码里出现过的那四行）
        val bad = """
            AppBarCell(
                label = "刷新占用",
                on = false,
                onClick = { onAction(SettingAction.CLEAR_CACHE) },
                modifier = Modifier.testTag("storage_refresh"),
            )
            AppBarCell(
                label = "清除缓存",
                onClick = { onAction(SettingAction.CLEAR_CACHE) },
                modifier = Modifier.testTag("storage_clear_cache"),
            )
        """.trimIndent()
        assertTrue(
            "自检失败：坏形状没被判定成「刷新会删缓存」",
            cellInvokes(bad, "storage_refresh", "CLEAR_CACHE"),
        )
        assertFalse(
            "自检失败：清洗之后仍被判成会删缓存",
            cellInvokes(
                bad.replace("onClick = { onAction(SettingAction.CLEAR_CACHE) }", "onClick = onRefreshNumbers"),
                "storage_refresh",
                "CLEAR_CACHE",
            ),
        )
        // 自检：抽不到格子时必须返回 null（不然上面那条断言可能因为"抽不到"而恒绿）
        assertEquals(null, cellBlock(bad, "storage_not_here"))

        // 自检：注释里的被检词不许被当成调用点（本文件自己的注释就写着它）
        val commented = """
            AppBarCell(
                label = "刷新占用",
                // 改前这里是 onClick = { onAction(SettingAction.CLEAR_CACHE) }
                onClick = onRefreshNumbers,
                modifier = Modifier.testTag("storage_refresh"),
            )
        """.trimIndent()
        assertFalse(
            "自检失败：注释里的 CLEAR_CACHE 被当成了真调用",
            cellInvokes(commented, "storage_refresh", "CLEAR_CACHE"),
        )
        // 反向：同一段去掉注释那行之后，真的写了调用必须被抓到
        assertTrue(
            "自检失败：把注释那行变成真代码之后反而抓不到",
            cellInvokes(
                commented.replace("onClick = onRefreshNumbers", "onClick = { onAction(SettingAction.CLEAR_CACHE) }"),
                "storage_refresh",
                "CLEAR_CACHE",
            ),
        )
    }
}
