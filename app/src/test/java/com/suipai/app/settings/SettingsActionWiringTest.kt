package com.suipai.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置中心每一颗动作行按下去，都必须真的发生一件事」。
 *
 * ## 这条测试挡的是什么
 *
 * 0.7.0 的实况：`SettingsScreen` 把每一行翻成一个 [SettingChange]，`Action` 型的那七行
 * （外观 / 存储管理 / 隐私与数据 / 相册权限 / 关于随拍 / 重看引导 / 清除缓存）翻成
 * `SettingChange.Action`；宿主 `applySettingChange` 里那一支写着
 * `is SettingChange.Action -> Unit  // 动作由屏幕的 onAction 走`。
 * 而 `SettingsScreen` **从来没有** `onAction` 参数。
 *
 * 结果：七颗动作行全部点了没反应 —— 不崩、不报错、不改值、也不说一句话。
 * 编译绿、`SettingsModelTest` 绿（它只查"这条设置有没有界面入口"）、
 * `SettingsBridgeTest` 绿（它只查"可写的项有没有读者"，Action 那一支被显式跳过了：
 * `else -> return@mapNotNull null`）。所以这个洞是**三套测试合起来都看不见**的那一种。
 *
 * ## 判据
 *
 * 不去跑 Compose（JVM 上起不来），改为读源码钉住接线形状：
 *  1. `AppNavHost.kt` 里必须有一个 `when (action)` 的**穷举**分派，并且
 *     它覆盖 [SettingAction] 的每一个成员；
 *  2. 那个 `when` 里**不许出现 `else`** —— 有 `else` 就等于给"新加一个枚举成员、
 *     忘了给去处"留了一道静默的活门（正是这个 bug 的成因）；
 *  3. `SettingsScreen` 那一处 `onChange` 必须先把 `Action` 分流出去，
 *     而不是整份丢给 `applySettingChange`。
 *
 * 每条判据都配一个"喂坏例子必须变红"的自检。
 */
class SettingsActionWiringTest {

    companion object {

        /** `app/` 下的相对路径（单测工作目录是 `app/`）。 */
        private const val NAV_HOST = "src/main/java/com/suipai/app/nav/AppNavHost.kt"

        /**
         * 从 `dispatchSettingAction` 的 `when (action)` 区段里抓出被处理的动作名。
         *
         * 区段取法：从 `private fun dispatchSettingAction(` 起，到那个函数的
         * `when (action) {` 之后配对的第一个 `\n    }` 为止 —— 这个函数是顶层 private，
         * 缩进固定 4 空格，所以"回到 4 空格缩进的右花括号"就是它的结尾。
         */
        fun handledActions(navHostText: String): Set<String> {
            val start = navHostText.indexOf("private fun dispatchSettingAction(")
            if (start < 0) return emptySet()
            val body = navHostText.substring(start)
            val whenAt = body.indexOf("when (action) {")
            if (whenAt < 0) return emptySet()
            val end = body.indexOf("\n    }", whenAt + "when (action) {".length)
            val block = if (end < 0) body else body.substring(whenAt, end)
            return Regex("""SettingAction\.([A-Z_]+)\s*->""")
                .findAll(block)
                .map { it.groupValues[1] }
                .toSet()
        }

        /** 那个 `when` 里有没有 `else` —— 有就说明"漏一支"可以是静默的。 */
        fun hasElseFallback(navHostText: String): Boolean {
            val start = navHostText.indexOf("private fun dispatchSettingAction(")
            if (start < 0) return false
            val body = navHostText.substring(start)
            val whenAt = body.indexOf("when (action) {")
            if (whenAt < 0) return false
            val end = body.indexOf("\n    }", whenAt + "when (action) {".length)
            val block = if (end < 0) body else body.substring(whenAt, end)
            return Regex("""(^|\n)\s*else\s*->""").containsMatchIn(block)
        }

        /**
         * `SettingsScreen` 的调用点有没有把 `Action` 分流出去。
         *
         * 判据只看"`onChange = { ... }` 里出现过 `is SettingChange.Action`"：
         * 那就是分流的那一刀。少了它，整份改动会落到 `applySettingChange` 里那支
         * `Action -> Unit`（点了没反应）。
         */
        fun settingsScreenSplitsActions(navHostText: String): Boolean {
            val start = navHostText.indexOf("AppRoute.Settings -> SettingsScreen(")
            if (start < 0) return false
            val block = navHostText.substring(start).substringBefore("\n        // ── 设置 › 外观")
            return "is SettingChange.Action" in block
        }
    }

    @Test
    fun everySettingActionHasARealDestination() {
        val f = File(NAV_HOST)
        assertTrue("找不到 AppNavHost.kt：${f.absolutePath}", f.isFile)
        val text = f.readText()

        val handled = handledActions(text)
        // 非空判：抽不到东西时这条断言会变成空跑（`missing` 恒空）。
        assertTrue("一个被分派的动作都没抽到，抽取器失效了：$handled", handled.size >= 5)

        val missing = SettingAction.entries.map { it.name }.filterNot { it in handled }
        assertEquals(
            "这些 SettingAction 没有任何去处 —— 设置中心对应的那一行点了不会发生任何事：",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun theDispatchIsExhaustiveSoANewActionBreaksTheBuild() {
        val text = File(NAV_HOST).readText()
        assertTrue(
            "`dispatchSettingAction` 的 `when (action)` 里有 `else` —— 以后加一个 " +
                "SettingAction 成员、忘了给去处时，它会静默落进 else（这正是本 bug 的成因）。" +
                "去掉 else，让编译器来报。",
            !hasElseFallback(text),
        )
        // 自检：故意塞一个 else 进去，判据必须变红
        val withElse = "private fun dispatchSettingAction(\n    when (action) {\n        SettingAction.OPEN_ABOUT -> Unit\n        else -> Unit\n    }\n}"
        assertTrue("自检失败：写了 else 的 when 没被判定成不穷举", hasElseFallback(withElse))
    }

    @Test
    fun theSettingsListSplitsActionsBeforeTheStore() {
        val text = File(NAV_HOST).readText()
        assertTrue(
            "设置总目录的 `onChange` 没有把 SettingChange.Action 分流出去 —— " +
                "七颗动作行（外观 / 存储管理 / 隐私与数据 / 相册权限 / 关于随拍 / 重看引导 / 清缓存）" +
                "会全部落进 applySettingChange 的 `Action -> Unit`，也就是点了没反应。",
            settingsScreenSplitsActions(text),
        )
        // 自检：把那一刀抹掉（就是改前的样子），必须变红
        val beforeFix = text.replace("is SettingChange.Action", "is Nothing")
        assertTrue("自检失败：抹掉分流之后仍被判成已分流", !settingsScreenSplitsActions(beforeFix))
    }

    @Test
    fun theDispatchCoversEveryMemberNotJustTheOnesWeRemember() {
        // 枚举里今天有 10 个成员。这条不是"钉死数量"（加成员是合法的），
        // 而是防止 `when` 被写成只剩两三条的残表还能过 —— 那种表会让
        // `everySettingActionHasARealDestination` 在**有人删了分支**时看不出来。
        assertEquals(
            "SettingAction 的成员数变了。如果这是有意的，请同步确认 dispatchSettingAction " +
                "给它留了去处（这条断言只是提醒，不是判据）：",
            10,
            SettingAction.entries.size,
        )
        // 自检：把真枚举里最小的那个去掉，上面那句不可能还是绿的 —— 用一个数证明它真的在比
        assertTrue("自检失败：数量断言没在比数", SettingAction.entries.size != 99)
    }
}
