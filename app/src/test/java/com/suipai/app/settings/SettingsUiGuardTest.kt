package com.suipai.app.settings

import com.suipai.app.ui.onboarding.OnboardingPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置 / 引导 / 隐私这三屏的**结构**守卫。
 *
 * 为什么用读源码的方式：这一族错的共同点是"编译得过、跑得起来、看得见"——
 * 一个横向滚动的动作行、一颗没有读屏名字的开关、三屏引导少了一屏、
 * 权限表和 manifest 对不上。这台机器上起不了 TalkBack（见 `A11ySourceGuardTest` 的说明），
 * 而设备侧观测通道时好时坏，所以退回最便宜也最确定的证据：源码本身。
 *
 * 与其它守卫同一套纪律：**判定做成纯函数，并且每条都配一个"喂坏例子必须变红"的自检**。
 * 没见过自己变红的守卫不算守卫。
 */
class SettingsUiGuardTest {

    companion object {

        /** 本轮新增的六个页面文件（相对 `src/main/java/com/suipai/app/`）。 */
        val NEW_SCREENS = listOf(
            "ui/settings/SettingsScreen.kt",
            "ui/settings/AppearanceScreen.kt",
            "ui/settings/StorageScreen.kt",
            "ui/settings/AboutScreen.kt",
            "ui/onboarding/OnboardingScreen.kt",
            "ui/privacy/PrivacyScreen.kt",
        )

        fun codeLines(lines: List<String>): List<Pair<Int, String>> =
            lines.mapIndexedNotNull { i, raw ->
                val t = raw.substringBefore("//").trim()
                if (t.isEmpty() || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to t
            }

        /**
         * 1. 新页面里不许出现 `horizontalScroll(`。
         *
         * 理由与 `PerfSourceGuardTest.noHorizontalScrollOnActionRows` 同一条：滚出视野的
         * Compose 节点**根本不在语义树里**，读屏与自动化都够不着。设置页的选项是
         * `FlowRow`（`AppChipFlow`）排的，装不下就换行。
         */
        fun horizontalScrollUses(lines: List<String>): List<Int> =
            codeLines(lines).filter { (_, t) -> "horizontalScroll(" in t }.map { it.first }

        /**
         * 2. 每一处 `.clickable(` 后面（三行内）必须跟着 `clearAndSetSemantics` 或 `semantics`
         *    里带 `contentDescription`。
         *
         * 装机量到的三种错法见 `AppBar.kt` 的注释：话落在子节点、可点那颗没名字。
         * 判据故意只看"这一颗自己有没有写名字"，不管写了什么。
         */
        fun unnamedClickables(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            val bad = ArrayList<Int>()
            for ((pos, pair) in code.withIndex()) {
                if (".clickable(" !in pair.second) continue
                val near = code.subList(pos, minOf(code.size, pos + 4)).joinToString(" ") { it.second }
                if (!near.contains("contentDescription") && !near.contains("clearAndSetSemantics")) {
                    bad += pair.first
                }
            }
            return bad
        }

        /**
         * 3. 设置行必须报**状态**（开/关、当前档位），不能只报名字。
         *
         * 装机账：`AppBarCell` 那一族加了「已选中」之后才第一次有人能从读屏判断当前值。
         * 设置页的 Toggle / Choice / Number 全都带状态，所以判据检查
         * `stateDescription` 这个符号在文件里出现过（至少一处 = 至少有一条路径想到了状态）。
         */
        fun missingStateDescription(lines: List<String>): Boolean =
            codeLines(lines).none { it.second.contains("stateDescription") }

        /**
         * 4. 首启引导必须正好三屏，且顺序固定（相册 → 一瞬 → 照片去向）。
         *
         * 任务书里点名的三屏。少一屏 = 用户永远学不到那一件事；顺序变了 = 最后一屏
         * 不是"照片去向 + 授权"，而授权按钮就挂在最后一屏上。
         */
        fun onboardingPageProblems(pages: List<OnboardingPage>): List<String> {
            val out = ArrayList<String>()
            if (pages.size != 3) out += "引导应当是 3 屏，实际 ${pages.size} 屏"
            if (pages.size >= 1 && !pages[0].title.contains("相册")) out += "第一屏应当讲相册：${pages[0].title}"
            if (pages.size >= 2 && !pages[1].title.contains("一瞬")) out += "第二屏应当讲一瞬：${pages[1].title}"
            if (pages.size >= 3) {
                val third = pages[2].title + pages[2].bullets.joinToString()
                if (!third.contains("不出") && !third.contains("不上传") && !third.contains("端侧")) {
                    out += "第三屏应当讲照片去向（不上传/端侧）：${pages[2].title}"
                }
            }
            pages.forEachIndexed { i, p ->
                if (p.bullets.isEmpty()) out += "第 ${i + 1} 屏一条都没说"
                if (p.footNote.isBlank()) out += "第 ${i + 1} 屏没有底注"
                if (p.kicker.isBlank()) out += "第 ${i + 1} 屏没有编号"
            }
            // 编号必须递增且不重复（印样页的帧号，重复了看起来像排版错）
            val kickers = pages.map { it.kicker }
            if (kickers.size != kickers.distinct().size) out += "屏编号重复：$kickers"
            return out
        }

        /**
         * 5. 引导文案里不许出现"能力夸大"的字眼。
         *
         * 这是这个项目最硬的一条产品原则：**不改画面内容**、不是美颜、不是 P 图。
         * 引导页是新用户看到的第一段话，写错一句就是对外承诺了一句不存在的能力。
         * 词表是"营销语"那一类，不是"功能名"——「一瞬」「配方」「端侧」都合法。
         */
        fun capabilityOverreach(text: String): List<String> {
            val banned = listOf(
                "一键变大片", "智能美颜", "AI 修图", "自动修图", "秒变专业",
                "云端处理", "云端加速", "无限容量", "永久保存", "绝对安全", "端到端加密",
            )
            return banned.filter { it in text }
        }

        /** 6. 隐私页的权限行必须与 `AndroidManifest.xml` 里的 `<uses-permission>` 逐条对得上。 */
        fun permissionMismatches(
            rows: List<PrivacyRowView>,
            manifestPermissions: List<String>,
        ): List<String> {
            val out = ArrayList<String>()
            // 反向：manifest 里每一条都必须有一行解释
            val explained = rows.flatMap { row -> row.manifest.split('/', '·').map { it.trim() } }
                .flatMap { seg -> Regex("[A-Z_]{4,}").findAll(seg).map { it.value }.toList() }
                .toSet()
            val missing = manifestPermissions.filterNot { it in explained }
            // `READ_MEDIA_VISUAL_USER_SELECTED` 与 `READ_MEDIA_IMAGES` 合写在同一行，算解释过
            if (missing.isNotEmpty()) out += "manifest 里这些权限在隐私页没有解释：$missing"
            // 正向：页面上的权限名必须在 manifest 里存在（不许编一条不存在的权限）
            val ghosts = explained.filterNot { it in manifestPermissions }
            if (ghosts.isNotEmpty()) out += "隐私页解释了这个应用没申请的权限：$ghosts"
            return out
        }

        /** manifest 里全部的 `<uses-permission android:name="..." />`。 */
        fun manifestPermissions(xml: String): List<String> =
            Regex("""uses-permission[^>]*android:name="([^"]+)"""")
                .findAll(xml)
                .map { it.groupValues[1].substringAfterLast('.') }
                .toList()
    }

    /** `PrivacyScreen.kt` 里那一行权限的**视图**（避免测试依赖 compose 文件里的 data class 语义）。 */
    data class PrivacyRowView(val name: String, val manifest: String)

    private fun srcRoot(): File {
        // AGP 跑 JVM 单测时工作目录就是模块根（app/）
        val f = File("src/main/java/com/suipai/app")
        assertTrue("找不到源码根，工作目录变了：${f.absolutePath}", f.isDirectory)
        return f
    }

    private fun screen(name: String): File {
        val f = File(srcRoot(), name)
        assertTrue("本轮应当存在这个页面：${f.absolutePath}", f.isFile)
        return f
    }

    // ── 真源码 ────────────────────────────────────────────────────────

    @Test
    fun noNewScreenScrollsHorizontally() {
        val hits = NEW_SCREENS.flatMap { rel ->
            horizontalScrollUses(screen(rel).readLines()).map { "$rel:$it" }
        }
        assertTrue(
            "设置/引导/隐私这几屏又出现横向滚动了（滚出视野的节点不在语义树里，读屏与自动化都够不着）：$hits",
            hits.isEmpty(),
        )
        // 非空判：六个文件真的都在
        assertEquals("本轮新增页面数量不对", 6, NEW_SCREENS.count { screen(it).isFile })
    }

    @Test
    fun everyClickableInNewScreensCarriesItsOwnName() {
        val hits = NEW_SCREENS.flatMap { rel ->
            unnamedClickables(screen(rel).readLines()).map { "$rel:$it" }
        }
        assertTrue(
            "这些可点节点没在可点那颗上写名字（读屏只会念「按钮，双击激活」）：$hits",
            hits.isEmpty(),
        )

        val dirty = listOf(
            "Column(",
            "    Modifier.clickable(role = Role.Switch) { },",
            "    contentAlignment = Alignment.Center,",
            ") { }",
        )
        assertEquals("没名字的可点节点没被抓到", listOf(2), unnamedClickables(dirty))
        val clean = listOf(
            "Column(",
            "    Modifier",
            "        .clickable(role = Role.Switch) { }",
            "        .clearAndSetSemantics { contentDescription = \"减弱动效，开\" },",
            ") { }",
        )
        assertTrue("写了名字的被误判：${unnamedClickables(clean)}", unnamedClickables(clean).isEmpty())
    }

    @Test
    fun settingsRowsSpeakTheirState() {
        val main = screen("ui/settings/SettingsScreen.kt")
        assertFalse(
            "设置行没有 stateDescription —— 读屏只说名字，听的人判断不出现在是开还是关",
            missingStateDescription(main.readLines()),
        )
        // 自检：把那个符号拿掉必须报
        val without = main.readLines().map { it.replace("stateDescription", "别的东西") }
        assertTrue("状态那一行拿掉之后没被抓到", missingStateDescription(without))
    }

    @Test
    fun onboardingHasExactlyThreePagesInOrder() {
        val pages = com.suipai.app.ui.onboarding.onboardingPages()
        assertEquals("引导三屏的完整性不对：${onboardingPageProblems(pages)}",
            emptyList<String>(), onboardingPageProblems(pages))
        assertEquals("ONBOARDING_PAGE_COUNT 与正文不一致", pages.size,
            com.suipai.app.ui.onboarding.ONBOARDING_PAGE_COUNT)

        // 自检：删一屏、换顺序都必须红
        assertTrue("少一屏没被抓到", onboardingPageProblems(pages.take(2)).isNotEmpty())
        assertTrue("顺序反了没被抓到",
            onboardingPageProblems(listOf(pages[1], pages[0], pages[2])).isNotEmpty())
        assertTrue("空的正文没被抓到",
            onboardingPageProblems(pages.map { it.copy(bullets = emptyList()) }).isNotEmpty())
    }

    @Test
    fun onboardingTextDoesNotOverclaim() {
        val text = com.suipai.app.ui.onboarding.onboardingPages()
            .joinToString("\n") { it.title + "\n" + it.kicker + "\n" + it.bullets.joinToString("\n") + "\n" + it.footNote }
        assertEquals("引导文案里有不该出现的说法（这个产品不改画面内容、不做美颜、没有云端处理）：",
            emptyList<String>(), capabilityOverreach(text))
        // 自检：塞一句进去必须红
        assertEquals("夸大词没被抓到", listOf("一键变大片"),
            capabilityOverreach(text + "一键变大片"))
    }

    @Test
    fun privacyPermissionTableMatchesTheManifest() {
        val manifest = File("../app/src/main/AndroidManifest.xml").let {
            if (it.isFile) it else File("src/main/AndroidManifest.xml")
        }
        assertTrue("找不到 AndroidManifest：${manifest.absolutePath}", manifest.isFile)
        val fromManifest = manifestPermissions(manifest.readText())
        assertTrue(
            "manifest 里一条 uses-permission 都没抽到，抽取器失效了：$fromManifest",
            fromManifest.size >= 8,
        )
        // 从隐私页的源码里抽出那几张表（`manifest = "..."` 那一列）
        val privacySrc = screen("ui/privacy/PrivacyScreen.kt").readText()
        val rows = Regex("""manifest = "([^"]+)"""")
            .findAll(privacySrc)
            .map { PrivacyRowView(name = "", manifest = it.groupValues[1]) }
            .toList()
        assertTrue("隐私页没抽到权限行，抽取器失效了", rows.size >= 6)

        val problems = permissionMismatches(rows, fromManifest)
        assertTrue("隐私页的权限表与 AndroidManifest.xml 对不上：$problems", problems.isEmpty())
    }

    @Test
    fun privacyExportJsonIsWellFormedAndEscapes() {
        // 直接测生成器：它是一段会被用户复制出去、贴给别人的文本，坏一个引号就是一段废数据
        val snap = SettingsSnapshot(mapOf(SettingsKeys.General.confirmDelete.id to false))
        val json = com.suipai.app.ui.privacy.PrivacyExport.buildJson(
            snap,
            mapOf("version" to "0.6.0", "设备" to "Xiaomi \"14\"\n换行"),
        )
        assertTrue("JSON 没以 { 开头：$json", json.trimStart().startsWith("{"))
        assertTrue("JSON 没以 } 收尾", json.trimEnd().endsWith("}"))
        // 平衡的花括号（我们只生成一层嵌套 + settings 一层，所以正好 2/2）
        assertEquals("花括号不配平", json.count { it == '{' }, json.count { it == '}' })
        // 引号数量必须是偶数：奇数说明有未转义的引号
        assertEquals("引号没配平（有未转义的引号）", 0, json.count { it == '"' } % 2)
        // 换行必须被转义成字面量 \n，不许真的换行把 JSON 截断
        assertTrue("字段值里的换行没被转义", json.contains("\\n"))
        assertTrue("设置快照的键没进导出", json.contains(SettingsKeys.General.confirmDelete.id))
        assertTrue("固定摘要没进导出", json.contains("photosUploaded"))

        // 自检：转义函数本身
        assertEquals("引号没转义", "\\\"", com.suipai.app.ui.privacy.PrivacyExport.escape("\""))
        assertEquals("反斜杠没转义", "\\\\", com.suipai.app.ui.privacy.PrivacyExport.escape("\\"))
        assertEquals("换行没转义", "\\n", com.suipai.app.ui.privacy.PrivacyExport.escape("\n"))
        assertEquals("普通中文被改了", "随拍", com.suipai.app.ui.privacy.PrivacyExport.escape("随拍"))
    }

    @Test
    fun privacyTextsQuoteTheRealThumbnailConstants() {
        // 隐私页与关于页都必须**引用常量**而不是重抄数字：`AiGuardTest` 那边核对的是
        // 政策文件里的数字，这里核对 app 内两屏有没有引用常量（抄死数字就失去单一真值来源）。
        for (rel in listOf("ui/privacy/PrivacyScreen.kt", "ui/settings/AboutScreen.kt")) {
            val text = screen(rel).readText()
            assertTrue("$rel 没有引用 AiAdvisor.SEND_EDGE（重抄数字会在改常量后变成假话）",
                "AiAdvisor.SEND_EDGE" in text)
            assertTrue("$rel 没有引用 AiAdvisor.SEND_QUALITY", "AiAdvisor.SEND_QUALITY" in text)
            // 自检的反面：这两屏里不许出现裸的 256/1024 这类"看起来是缩略图长边"的字面量
            val bare = Regex("""长边\s*\d+""").findAll(text).map { it.value }.toList()
            assertTrue("$rel 里写死了缩略图长边：$bare", bare.isEmpty())
        }
    }

    @Test
    fun settingsScreensOnlyUseExistingComponentLanguage() {
        // 本波明确不许引用还不存在的设计系统符号（writer-design 的 ui/design/ 正在并行做）。
        // 判据：这六个文件里出现的 `com.suipai.app.ui.design.*` 一律算违规。
        val hits = NEW_SCREENS.filter { rel ->
            screen(rel).readText().contains("com.suipai.app.ui.design")
        }
        assertTrue(
            "这些页面引用了设计系统（等 writer-design 落地后统一换皮时才允许）：$hits",
            hits.isEmpty(),
        )
        // 自检：现在 design 包确实存在（所以不是"扫了个空目录"），只是本模块不引用它
        val designDir = File(srcRoot(), "ui/design")
        assertTrue("拿 design 包的存在性做自检失败（它应当已被 writer-design 建出来）", designDir.isDirectory)
    }
}
