package com.suipai.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只授权了部分照片」（Android 14+ 的 partial access）这一族的判据、文案与两条静默出口。
 *
 * 为什么要钉下来：这一状态特别在**它看起来完全正常** —— `hasMediaAccess` 返回 true、
 * 时间线有照片、不崩也不报错，只是少了一半。装机量到的那一次（`ANDROID-VERIFY.md`
 * 「注入的账」那一节）：`pm revoke READ_MEDIA_IMAGES` 之后顶栏从 85 FRAMES 变成 73，
 * 屏幕上一个字都不解释，用户手上的结论只有"相册自己缩了"。
 * 设备上复现它要改权限、重启、数顶栏，而四行真值表在 JVM 里几十毫秒就能穷举完。
 *
 * 三件事分别钉：
 * 1. **真值表**：哪一格算"部分"。含两格"猜错方向"的坏样本（两个都授予的那一格，
 *    以及压根没有这条权限的系统）。
 * 2. **那句人话**：不许引用它拿不到的数字，不许在格子灰着时教用户去点它，
 *    也不许把责任推给用户的搜索词或"没拍照"（`还没有照片` 那一族）。
 * 3. **两条出口不许静默**（守卫 #23、#24）：`sendOrTell` 那一族不许传空 lambda，
 *    「补授权」那颗不许在没有可达性判据时亮着。
 *
 * 每条判定都带"故意喂一个坏例子"的自检：没见过自己变红的守卫不算守卫。
 */
class PartialAccessTest {

    companion object {

        /** 声明行（`internal fun sendOrTell(`）不是调用点：证据区间不许含被检词的定义处。 */
        private fun isDeclaration(code: String, fn: String): Boolean =
            Regex("""\bfun\s+$fn\s*\(""").containsMatchIn(code)

        /**
         * 真的"打了这一句"：函数名后面必须跟着左括号。
         *
         * 光按子串匹配会把 `import com.suipai.app.ui.common.sendOrTell` 也算成一次调用点 ——
         * 第一版就是这么在四个文件上报出根本不存在的空 lambda 的。
         */
        private fun isCall(code: String, fn: String): Boolean =
            Regex("""(^|[^.\w])$fn\s*\(""").containsMatchIn(code) && !isDeclaration(code, fn)

        /**
         * 23. 「把 intent 发给别的 App 接手」这一族出口，每一处调用都必须真的把失败说一句。
         *
         * 这一族的静默形状与 #19 不同：它不 `return`，它就是**什么都不做** ——
         * `sendOrTell(ctx, i, "分享") {}` 传一个空 lambda，于是"没做成"又变回"像做成了"
         * （`Launch.kt` 顶部记的那笔账：八个出口曾经都是 `runCatching { startActivity }`，一个 catch 不写）。
         *
         * 判据（只看那一次调用的实参区 + 紧跟其后的尾随 lambda 的体）：
         *  · 合法：带一个**非空** lambda（`{ onNote(it) }`，分几行都算），
         *    或把上游的 `tell` 原样转下去（`, tell)` —— 转出去那一头由它自己的调用点保证说过）；
         *  · 违规：`{}`、`{ }`、只剩注释的 lambda（`codeLines` 会把注释剥成空行），
         *    以及压根没有 tell 的调用。
         * 括号按整行累加，所以实参区**开头那一行**的前半截得是配平的（真源码都满足：
         * 要么行首就是这个调用，要么 `if (x) ` 那种自带的括号成对）。
         */
        fun intentExitsThatSayNothing(lines: List<String>, fns: List<String>): Pair<Int, List<Int>> {
            val code = PerfSourceGuardTest.codeLines(lines)
            val bad = ArrayList<Int>()
            var seen = 0
            for ((pos, pair) in code.withIndex()) {
                val (n, t) = pair
                val fn = fns.firstOrNull { isCall(t, it) } ?: continue
                seen++
                var depth = 0
                var opened = false
                var end = pos
                while (end < code.size) {
                    for (ch in code[end].second) {
                        if (ch == '(') { depth++; opened = true } else if (ch == ')') depth--
                    }
                    if (opened && depth <= 0) break
                    end++
                }
                val args = code.subList(pos, minOf(code.size, end + 1)).joinToString(" ") { it.second }
                if (Regex("""[,(]\s*tell\s*[,)]""").containsMatchIn(args)) continue
                val tail = StringBuilder()
                if (args.substringAfterLast(')').contains('{')) {
                    var braces = 0
                    var openedBrace = false
                    var j = end
                    while (j < code.size && j <= end + 12) {
                        for (ch in code[j].second) {
                            if (ch == '{') { braces++; openedBrace = true } else if (ch == '}') braces--
                        }
                        tail.append(code[j].second).append(' ')
                        j++
                        if (openedBrace && braces <= 0) break
                    }
                }
                if (!Regex("""\{[^{}]*[^\s{}][^{}]*\}""").containsMatchIn("$args $tail")) bad += n
            }
            return seen to bad
        }

        /**
         * 24. 「补授权」那颗格子必须由可达性管着 —— 做不了的时候它自己就是灰的。
         *
         * #19 管的是 `?: return@AppBarCell`；这一族的静默形状是"intent 没人接，于是它什么都不做"，
         * 它不 return，所以 #19 看不见它。判据形状照抄 #19：每一处 `openPhotoAccessSettings(`
         * 调用所属的那一次 `AppBarCell(` 里必须出现 `enabled =`。
         */
        fun settingsCellsThatCannotGrey(lines: List<String>): Pair<Int, List<Int>> {
            val code = PerfSourceGuardTest.codeLines(lines)
            val bad = ArrayList<Int>()
            var seen = 0
            for ((pos, pair) in code.withIndex()) {
                if (!isCall(pair.second, "openPhotoAccessSettings")) continue
                seen++
                var start = -1
                var back = pos - 1
                while (back >= 0 && pos - back <= 20) {
                    if (code[back].second.contains("AppBarCell(")) {
                        start = back
                        break
                    }
                    back--
                }
                if (start < 0) {
                    bad += pair.first          // 读不到所属格子：也算违规，逼人来改这条判据
                    continue
                }
                if ((start..pos).none { code[it].second.contains("enabled =") }) bad += pair.first
            }
            return seen to bad
        }

        /**
         * 从源码里读出那颗格子的 `label`（从 `openPhotoAccessSettings(` 往回找）。
         * 用来跟那句人话里引号中的名字对账 —— 这两处一分家，用户就被指向一颗不存在的格子。
         */
        fun settingsCellLabel(lines: List<String>): String? {
            val code = PerfSourceGuardTest.codeLines(lines)
            val at = code.indexOfFirst { isCall(it.second, "openPhotoAccessSettings") }
            if (at < 0) return null
            var back = at
            while (back >= 0 && at - back <= 12) {
                Regex("""label\s*=\s*"([^"]+)"""").find(code[back].second)?.let { return it.groupValues[1] }
                back--
            }
            return null
        }
    }

    private fun kotlinFiles(): List<File> {
        val root = File("src/main/java/com/suipai/app")
        assertTrue("找不到源码根，工作目录变了：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun fileNamed(name: String): File {
        val f = kotlinFiles().firstOrNull { it.name == name }
        assertTrue("扫描集里没有 $name，这一条无从判断", f != null)
        return f!!
    }

    // ── 1. 真值表 ──────────────────────────────────────────────────────

    /**
     * 四行真值表逐格钉住。每一格对应哪种用户动作写在 `isPartialPhotoAccess` 的注释里，
     * 这里只钉"这一格判什么"，改判据的人必须同时改那份说明。
     */
    @Test
    fun truthTableOfPartialAccess() {
        // 允许全部：`READ_MEDIA_VISUAL_USER_SELECTED` **不**在授予列表里。
        // 这一格判错的代价是满屏的人被无缘无故提醒一次。
        assertFalse(
            "images 授予 + selected 不授予 = 全部授权",
            isPartialPhotoAccess(MediaAccess(34, imagesGranted = true, userSelectedGranted = false)),
        )
        // 只选一部分：装机量到的那一格（85 → 73 张）
        assertTrue(isPartialPhotoAccess(MediaAccess(34, imagesGranted = false, userSelectedGranted = true)))
        // 猜错方向的坏样本：文档只把 selected 单独授予那一行写成"部分"，
        // 于是"两个都在"（选完一部分又去设置里允许全部、或某些机型就这么留着）被当成全部，
        // 那一屏照样少了一半而一个字都不说。判"部分"：宁可多说一句。
        assertTrue(isPartialPhotoAccess(MediaAccess(34, imagesGranted = true, userSelectedGranted = true)))
        // 全拒绝：压根没有访问，那是授权门的事，不是"少看了一半"
        assertFalse(isPartialPhotoAccess(MediaAccess(34, imagesGranted = false, userSelectedGranted = false)))
        // 系统门限：Android 13 及以下没有这条权限，真机上这两格到不了；
        // 钉住的是"哪天 `readMediaAccess` 的 sdk 门限写漏，一个从别处喂进来的 true 不许在 13 上冒话"。
        assertFalse(isPartialPhotoAccess(MediaAccess(33, imagesGranted = true, userSelectedGranted = true)))
        assertFalse(isPartialPhotoAccess(MediaAccess(26, imagesGranted = false, userSelectedGranted = true)))
        assertTrue(isPartialPhotoAccess(MediaAccess(36, imagesGranted = false, userSelectedGranted = true)))
    }

    /**
     * 部分授权仍然算"有访问"（否则时间线根本不出现，那句没机会说）；
     * 而门限以下那条 true 不许换来访问 —— 这一条钉的是 `hasMediaAccess` 改写成
     * `readMediaAccess(...).allowed` 之后行为与原来逐格相同。
     */
    @Test
    fun partialStillCountsAsAllowed() {
        assertTrue(MediaAccess(34, imagesGranted = false, userSelectedGranted = true).allowed)
        assertTrue(MediaAccess(34, imagesGranted = true, userSelectedGranted = false).allowed)
        assertTrue(MediaAccess(33, imagesGranted = true, userSelectedGranted = true).allowed)
        assertTrue(MediaAccess(26, imagesGranted = true, userSelectedGranted = false).allowed)
        assertFalse(MediaAccess(34, imagesGranted = false, userSelectedGranted = false).allowed)
        assertFalse(MediaAccess(26, imagesGranted = false, userSelectedGranted = true).allowed)
    }

    // ── 2. 那句人话 ────────────────────────────────────────────────────

    private val partial = MediaAccess(34, imagesGranted = false, userSelectedGranted = true)
    private val full = MediaAccess(34, imagesGranted = true, userSelectedGranted = false)

    @Test
    fun noticeOnlyAppearsInPartialState() {
        assertNull(partialPhotoNotice(full, visibleCount = 73, settingsReachable = true))
        assertNull(partialPhotoNotice(MediaAccess(34, imagesGranted = false, userSelectedGranted = false)))
        assertNull(partialPhotoNotice(MediaAccess(33, imagesGranted = true, userSelectedGranted = true)))
        val said = partialPhotoNotice(partial).orEmpty()
        assertTrue("这句话必须自己说是授权的事：$said", said.contains("授权") && said.contains("部分"))
    }

    /**
     * 不许把责任推给用户：这一族的空屏原来会说「还没有照片。点快门拍一张。」，
     * 而真话是"你有几百张，系统只把选中的那些给了我们"。
     */
    @Test
    fun noticeNeverBlamesTheUsersLibrary() {
        for (count in listOf<Int?>(null, 0, 1, 73)) {
            val said = partialPhotoNotice(partial, visibleCount = count, settingsReachable = true).orEmpty()
            for (lie in listOf("还没有照片", "没有匹配", "点快门")) {
                assertFalse("这句把「少了照片」说成「用户没有照片」（count=$count → $said）", said.contains(lie))
            }
        }
    }

    /**
     * 数字的两条规矩：可见数不可信时一个数字都不报；全库数不比可见数大时也不许说"少了多少"
     * （后一格就是"猜错方向"的坏样本：两处不同源时报出来正好把假话说圆）。
     */
    @Test
    fun noticeNeverQuotesANumberItCannotTrust() {
        val paging = partialPhotoNotice(partial, visibleCount = null).orEmpty()
        assertFalse("分页没读到底时不许报任何张数：$paging", paging.any { it.isDigit() })

        val both = partialPhotoNotice(partial, visibleCount = 73, libraryCount = 85).orEmpty()
        assertTrue("两处都对得上时才允许说少了：$both", both.contains("85") && both.contains("73"))

        for (lib in listOf(0, 40, 73)) {
            val said = partialPhotoNotice(partial, visibleCount = 73, libraryCount = lib).orEmpty()
            assertFalse("全库 $lib 张时不许说「相册里有」：$said", said.contains("相册里有"))
            assertTrue("得退回只报可见数那一句：$said", said.contains("73"))
        }

        val zero = partialPhotoNotice(partial, visibleCount = 0, libraryCount = 85).orEmpty()
        assertFalse("一张都没放行时不许冒出 0 或 85：$zero", zero.any { it.isDigit() })
    }

    /** 格子灰着的时候，那句话不许再教用户去点它。 */
    @Test
    fun noticeStopsPointingAtTheCellWhenNothingOpens() {
        val reachable = partialPhotoNotice(partial, settingsReachable = true).orEmpty()
        assertTrue(reachable.contains("补授权"))
        val stuck = partialPhotoNotice(partial, settingsReachable = false).orEmpty()
        assertFalse("没有设置页可去时不许还说要点它：$stuck", stuck.contains("补授权"))
        assertTrue("要改口说清怎么办：$stuck", stuck.contains("系统设置"))
    }

    // ── 3. 守卫：两条出口不许静默 ─────────────────────────────────────

    @Test
    fun launchExitsAlwaysSpeakOnFailure() {
        val fns = listOf("sendOrTell", "openAppSettings", "openPhotoAccessSettings")
        var seen = 0
        val bad = ArrayList<String>()
        for (f in kotlinFiles()) {
            val (n, hits) = intentExitsThatSayNothing(f.readLines(), fns)
            seen += n
            bad += hits.map { "${f.name}:$it" }
        }
        // 非空判：读法失效（换行写法变了、函数改名了）会让这一条绿着空跑。
        assertTrue("只核对到 $seen 处出口调用，读法大概失效了（全仓应有 8 处以上）", seen >= 8)
        assertTrue(
            "这些出口把失败说成了空话（点下去什么都不发生，界面上也没有一句）：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )

        val dirty = listOf(
            "    sendOrTell(context, intent, \"分享\") {}",
            "    openAppSettings(context) { }",
            "    openPhotoAccessSettings(context) {",
            "        // 这里本来要写 onNote(it)，结果只剩一句注释",
            "    }",
        )
        assertEquals("三种「其实一个字都没说」的形状都要红", listOf(1, 2, 3), intentExitsThatSayNothing(dirty, fns).second)

        val clean = listOf(
            "    fun openAppSettings(context: Context, tell: (String) -> Unit) {",
            "        sendOrTell(context, intent, \"打开本应用的设置页\", tell)",
            "    }",
            "    sendOrTell(context, Intent.createChooser(send, \"分享\"), \"分享\") { onNote(it) }",
            "    openPhotoAccessSettings(context) {",
            "        note = it",
            "    }",
            "    // 上面那三处是全部合法形状：转 tell / 单行尾随 lambda / 分行尾随 lambda",
            "    import com.suipai.app.ui.common.sendOrTell",
        )
        val (cn, cbad) = intentExitsThatSayNothing(clean, fns)
        assertTrue(
            "合法写法（转 tell / 非空 lambda，分几行都算）不许误判：" + cbad.joinToString(" | "),
            cbad.isEmpty(),
        )
        assertEquals("定义行不许被算成调用点（那等于把证据区间盖在被检词的定义处）", 3, cn)
    }

    @Test
    fun settingsCellGreysOutWhenNoExitResolves() {
        val gallery = fileNamed("GalleryScreen.kt").readLines()
        val (seen, bad) = settingsCellsThatCannotGrey(gallery)
        assertTrue("GalleryScreen 里一处 openPhotoAccessSettings( 都没读到，这一条在空转", seen >= 1)
        assertTrue(
            "这些「打开设置」的格子没有 enabled 判据（做不了也亮着）：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )

        val dirty = listOf(
            "    AppBarCell(",
            "        label = \"补授权\",",
            "        on = false,",
            "        onClick = { openPhotoAccessSettings(context) { onNote(it) } },",
            "    )",
        )
        assertEquals("没有 enabled 的那一颗必须红", listOf(4), settingsCellsThatCannotGrey(dirty).second)
        val clean = dirty.toMutableList().also { it.add(3, "        enabled = reachable,") }
        assertTrue(
            "补上 enabled 就不许再报：" + settingsCellsThatCannotGrey(clean).second,
            settingsCellsThatCannotGrey(clean).second.isEmpty(),
        )
        val orphan = listOf(
            "    fun helper(context: Context) {",
            "        openPhotoAccessSettings(context) { onNote(it) }",
            "    }",
        )
        assertEquals(
            "读不到所属格子时报「读不到」（行号照旧给出，逼人来改这条判据）",
            listOf(2),
            settingsCellsThatCannotGrey(orphan).second,
        )
    }

    /**
     * 那句人话里引号中的名字，必须等于源码里那颗格子自己的 `label`。
     *
     * 这一条钉的是本项目反复栽过的「格子上写 A、话里说 B」：文案与控件各存一份，
     * 改一个忘一个，用户就照着提示去找一颗不存在的格子。
     */
    @Test
    fun theCellTheSentencePointsAtIsTheCellThatExists() {
        val label = settingsCellLabel(fileNamed("GalleryScreen.kt").readLines())
        assertTrue("读法失效：GalleryScreen 里找不到那颗格子的 label", label != null)
        val said = partialPhotoNotice(partial, settingsReachable = true).orEmpty()
        val named = Regex("""「(.+?)」""").find(said)?.groupValues?.get(1)
        assertEquals("提示语里点的名字与格子自己的标签分家了", label, named)

        // 自检：把源码里那颗格子改名，两处就必须对不上（改动传不进判据的自检是死的）
        val renamed = fileNamed("GalleryScreen.kt").readLines()
            .map { if (it.contains("label = \"补授权\"")) it.replace("补授权", "改授权") else it }
        val fromSource = settingsCellLabel(renamed)
        assertEquals("改名后从源码读到的应该是新名字", "改授权", fromSource)
        assertNotEquals("改名之后这条就该红", fromSource, named)
    }
}
