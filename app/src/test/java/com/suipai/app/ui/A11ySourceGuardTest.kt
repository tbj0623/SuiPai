package com.suipai.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏那一层的四条不变量，同样用**读源码**的方式钉住。
 *
 * 为什么不做成"跑一次 TalkBack"的测试：这台机器上无障碍服务的观测通道不可靠（`uiautomator`
 * 的 dump 里没有 TalkBack 的朗读内容，焦点节点也拿不稳），而这四类错的共同点是
 * **编译得过、跑得起、看得见** —— 只有把屏幕阅读器打开才露馅。上一轮装机量读屏一次抓出六处，
 * 这一篇把它们变成会自己变红的断言。
 *
 * 1. 滑杆必须自己报名字。material3 的 `Slider` 只有写在**调用方 modifier** 上的语义才会落到
 *    与 `sliderSemantics` 同一个节点（Slider.kt:686-695）。不写，TalkBack 到那里只报「滑动条」，
 *    看得见的那行「强度 62%」不在焦点路径上。一瞬那十根滑杆原来全部无名。
 * 2. 会自己变的字必须标活区。Compose 1.7.6 对普通 `Text` 原地改内容只发 `WINDOW_CONTENT_CHANGED`，
 *    不标 liveRegion 就等于"没人碰也不播"。倒计时的每一秒、连拍那一行、取景状态行都是这一族。
 * 3. 状态标签的每个布尔都得有一句话说它。格子上的角标越加越多，而**新加一个忘了写进读屏那句**
 *    在 diff 里长得完全正常，`CellLabelTest` 也只穷举它写下时的那三个词。
 * 4. 名字由调用方给的控件，不许把话说死。快门那颗原来写死 `contentDescription = "拍照"`，
 *    于是画廊主屏那只在读屏里报的是它**不会做**的那件事。
 *
 * 判定逻辑全部做成纯函数，每条都带"故意喂一个坏例子"的自检：没见过自己变红的守卫不算守卫。
 */
class A11ySourceGuardTest {

    companion object {

        /**
         * 1. 返回「这一句 `Slider(` 里没有 contentDescription」的起始行号。
         *
         * 只看那一次调用本身（括号配平到收尾），不拿后面十行凑窗口 —— 用窗口的第一版把下一根
         * 滑杆的名字算给了上一根，于是两根都「有名字」。
         */
        fun unnamedSliders(lines: List<String>): Pair<Int, List<Int>> {
            val bad = ArrayList<Int>()
            var seen = 0
            var i = 0
            while (i < lines.size) {
                val cut = lines[i].indexOf("//")
                val head = if (cut >= 0) lines[i].substring(0, cut) else lines[i]
                val at = head.indexOf("Slider(")
                if (at < 0) {
                    i++
                    continue
                }
                seen++
                if (!statementFrom(lines, i, at + "Slider".length).contains("contentDescription")) bad += i + 1
                i = maxOf(i + 1, stmtEndLine(lines, i, at + "Slider".length))
            }
            return seen to bad
        }

        /**
         * 2. 每一句「会自己变的字」都要落在标了 liveRegion 的节点上。
         *
         * `wanted` 用各句里一个独一的片段而不是整行；读法失效时照样报，别让人以为这条过了。
         */
        fun unannouncedLiveTexts(lines: List<String>, wanted: List<String>): List<String> {
            val out = ArrayList<String>()
            for (token in wanted) {
                val at = lines.indexOfFirst { it.substringBefore("//").contains(token) }
                if (at < 0) {
                    out += "$token(读法失效：这一句在源码里找不到了)"
                    continue
                }
                var start = at
                var back = at
                while (back >= 0 && at - back <= 6) {
                    if (lines[back].substringBefore("//").contains("Text(")) {
                        start = back
                        break
                    }
                    back--
                }
                val stmt = statementFrom(lines, start, lines[start].indexOf("Text(") + "Text".length)
                if (!stmt.contains("liveRegion")) out += "$token(第 ${at + 1} 行，没标活区)"
            }
            return out
        }

        /**
         * 3. 状态标签构造函数里，每一个 Boolean 参数都必须真的被说出来。
         *
         * 抓的不是「写错了」，是「格子上多加一格角标、忘了读屏那句」。
         */
        fun unsaidBooleanStates(lines: List<String>, fn: String): Pair<Int, List<String>> {
            val start = lines.indexOfFirst { Regex("fun\\s+$fn\\s*\\(").containsMatchIn(it) }
            if (start < 0) return 0 to listOf("$fn(读法失效：函数没找到)")
            val params = ArrayList<String>()
            var i = start
            var depth = 0
            while (i < lines.size) {
                val code = lines[i].substringBefore("//")
                depth += code.count { it == '(' } - code.count { it == ')' }
                Regex("""(^\s*)(\w+)\s*:\s*Boolean\s*[,=)]""").find(code)?.let { params += it.groupValues[2] }
                i++
                if (depth <= 0 && i > start + 1) break
            }
            if (params.isEmpty()) return 0 to listOf("$fn(读法失效：签名里一个 Boolean 都没有)")
            val body = PerfSourceGuardTest.functionBody(lines, fn)
                ?: return params.size to listOf("$fn(读法失效：函数体取不到)")
            // 签名那一行本身写着 `pinned: Boolean,` —— 不排除参数声明，"提到了这个名字"
            // 就永远成立，这条会绿着空跑（第一版就是这么漏掉 dirty 样本的）。
            val paramDecl = Regex("""^\s*\w+\s*:\s*\w+\s*,?\s*$""")
            val said = body.map { it.substringBefore("//") }.filterNot { paramDecl.matches(it) }
            return params.size to params.filter { p -> said.none { it.contains(p) } }
        }

        /**
         * 4. 收 `onClickLabel` 参数的控件：说出去的名字必须跟着参数走。
         *
         * 两种死法都判：写死一个字面量（主屏快门那种），或者压根不写 contentDescription
         * （那颗圆钮就只剩图标，读屏只说「按钮」）。
         */
        fun mislabelledLabelledControls(lines: List<String>): Pair<Int, List<String>> {
            val out = ArrayList<String>()
            var seen = 0
            val declared = lines.mapIndexedNotNull { n, l ->
                Regex("^fun\\s+(\\w+)").find(l.substringBefore("//"))?.groupValues?.get(1)?.let { n to it }
            }
            for ((_, name) in declared) {
                val body = PerfSourceGuardTest.functionBody(lines, name) ?: continue
                // 参数是逐行写的（真源码那样），所以签名要看前 16 行而不是第一行。
                val sig = body.take(16).joinToString("\n") { it.substringBefore("//") }
                if (!Regex("""onClickLabel\s*:\s*String""").containsMatchIn(sig)) continue
                seen++
                // `contentDescription = null` 是"这颗图标不报名字"的声明，不是说了什么。
                val said = body.map { it.substringBefore("//") }
                    .filter { it.contains("contentDescription") && !it.contains("contentDescription = null") }
                if (said.isEmpty()) {
                    out += "$name：收了 onClickLabel 却一个字都没说（读屏只报「按钮」）"
                    continue
                }
                if (said.any { Regex("""contentDescription\s*=\s*\"""").containsMatchIn(it) }) {
                    out += "$name：contentDescription 写死了，调用方给的名字被盖掉"
                }
                if (said.none { it.contains("onClickLabel") }) {
                    out += "$name：contentDescription 没跟着 onClickLabel 走"
                }
            }
            return seen to out
        }

        /** 从 (line, col) 那个开括号起，取到配平为止的整段源码。 */
        private fun statementFrom(lines: List<String>, line: Int, col: Int): String {
            val sb = StringBuilder()
            var depth = 0
            var i = line
            var c = col
            while (i < lines.size) {
                var l = lines[i].substring(maxOf(0, if (i == line) c else 0))
                val cut = l.indexOf("//")
                if (cut >= 0 && l.substring(0, cut).count { it == '"' } % 2 == 0) l = l.substring(0, cut)
                for (ch in l) {
                    if (ch == '(') depth++
                    else if (ch == ')') depth--
                }
                sb.append(l).append('\n')
                i++
                if (depth <= 0) break
                c = 0
            }
            return sb.toString()
        }

        private fun stmtEndLine(lines: List<String>, line: Int, col: Int): Int {
            var depth = 0
            var i = line
            var c = col
            while (i < lines.size) {
                val l = lines[i].substring(maxOf(0, if (i == line) c else 0))
                for (ch in l) {
                    if (ch == '(') depth++
                    else if (ch == ')') depth--
                }
                i++
                if (depth <= 0) return i
                c = 0
            }
            return i
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

    @Test
    fun everySliderHasItsOwnName() {
        var seen = 0
        val bad = ArrayList<String>()
        for (f in kotlinFiles()) {
            val (n, hits) = unnamedSliders(f.readLines())
            seen += n
            bad += hits.map { "${f.name}:$it" }
        }
        // 非空判：读法失效（`Slider(` 的换行写法变了）会让这一条绿着空跑。
        assertTrue("只核对到 $seen 句 Slider(，读法大概失效了", seen >= 4)
        assertTrue(
            "这些滑杆在 TalkBack 里是无名的（看得见的那行标题不在焦点路径上）：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )

        val dirty = listOf(
            "        Slider(",
            "            value = intensity,",
            "            modifier = Modifier.padding(horizontal = 12.dp),",
            "        )",
            "        Slider(",
            "            value = x,",
            "            modifier = Modifier.semantics { contentDescription = \"颗粒 40%\" },",
            "        )",
        )
        assertEquals("无名的要报，有名字的不许误判", 2 to listOf(1), unnamedSliders(dirty))
    }

    @Test
    fun selfChangingCameraTextsAreLiveRegions() {
        val camera = fileNamed("CameraScreen.kt").readLines()
        val bad = unannouncedLiveTexts(camera, listOf("countdown.toString()", "间隔拍摄中", "meta,"))
        assertTrue(
            "相机这几句自己会变的字没标活区，读屏不会主动播（倒计时、连拍、取景状态）：" +
                bad.joinToString(" | "),
            bad.isEmpty(),
        )

        val dirty = listOf(
            "            Text(",
            "                countdown.toString(),",
            "                fontSize = 72.sp,",
            "                modifier = Modifier.align(Alignment.Center),",
            "            )",
        )
        assertEquals(
            listOf("countdown.toString()(第 2 行，没标活区)"),
            unannouncedLiveTexts(dirty, listOf("countdown.toString()")),
        )
        val clean = listOf(
            "            Text(",
            "                countdown.toString(),",
            "                fontSize = 72.sp,",
            "                modifier = Modifier",
            "                    .align(Alignment.Center)",
            "                    .semantics { liveRegion = LiveRegionMode.Assertive },",
            "            )",
        )
        assertTrue(
            "标了活区的写法不许误判（真源码就是分这么多行写的）",
            unannouncedLiveTexts(clean, listOf("countdown.toString()")).isEmpty(),
        )
        assertEquals(
            "读法失效必须报出来，不能悄悄算通过",
            listOf("<换掉了>(读法失效：这一句在源码里找不到了)"),
            unannouncedLiveTexts(camera, listOf("<换掉了>")),
        )
    }

    @Test
    fun everyBooleanStateGetsSpoken() {
        val gallery = fileNamed("GalleryScreen.kt").readLines()
        val (n, silent) = unsaidBooleanStates(gallery, "photoCellLabel")
        assertTrue("只核对到 $n 个 Boolean 参数，读法大概失效了（应有 3 个以上）", n >= 3)
        assertTrue("这些状态在格子上看得见、在读屏里没人说：" + silent.joinToString(" | "), silent.isEmpty())

        val dirty = listOf(
            "internal fun label(",
            "    name: String,",
            "    favorite: Boolean,",
            "    pinned: Boolean,",
            "): String = buildString {",
            "    append(name)",
            "    if (favorite) append(\"，已收藏\")",
            "}",
        )
        assertEquals(
            "新加一格角标忘了写那句，必须报出来（pinned 就是那种在 diff 里完全正常的提交）",
            2 to listOf("pinned"),
            unsaidBooleanStates(dirty, "label"),
        )
        val clean = listOf(
            "internal fun label(",
            "    name: String,",
            "    favorite: Boolean,",
            "    pinned: Boolean,",
            "): String = buildString {",
            "    append(name)",
            "    if (favorite) append(\"，已收藏\")",
            "    if (pinned) append(\"，已置顶\")",
            "}",
        )
        assertTrue(
            "补上了那句就不许再报",
            unsaidBooleanStates(clean, "label").second.isEmpty(),
        )
        assertEquals(
            "函数名读不到时必须报「读法失效」",
            listOf("nope(读法失效：函数没找到)"),
            unsaidBooleanStates(clean, "nope").second,
        )
    }

    @Test
    fun shutterFabSpeaksTheCallersLabel() {
        val shutter = fileNamed("Shutter.kt").readLines()
        val (seen, bad) = mislabelledLabelledControls(shutter)
        assertTrue("Shutter.kt 里一个收 onClickLabel 的控件都没找到，读法大概失效了", seen >= 1)
        assertTrue("名字由调用方给的控件把话说死了：" + bad.joinToString(" | "), bad.isEmpty())

        val hardcoded = listOf(
            "fun ShutterFab(",
            "    onClickLabel: String,",
            "    onClick: () -> Unit,",
            ") {",
            "    Box(modifier = Modifier.combinedClickable(onClick = onClick)) {",
            "        Icon(Icons.Outlined.PhotoCamera, contentDescription = \"拍照\")",
            "    }",
            "}",
        )
        assertEquals(
            "写死「拍照」那种（装机量到的原状）两条都要报",
            listOf(
                "ShutterFab：contentDescription 写死了，调用方给的名字被盖掉",
                "ShutterFab：contentDescription 没跟着 onClickLabel 走",
            ),
            mislabelledLabelledControls(hardcoded).second,
        )
        val silent = hardcoded.toMutableList().also {
            it[5] = "        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)"
        }
        assertEquals(
            "图标自己说 null、外层一个字都没说，也要报",
            listOf("ShutterFab：收了 onClickLabel 却一个字都没说（读屏只报「按钮」）"),
            mislabelledLabelledControls(
                silent.take(4) +
                    listOf(
                        "    Box(",
                        "        modifier = Modifier",
                        "            .combinedClickable(onClick = onClick),",
                    ) +
                    silent.subList(4, silent.size),
            ).second,
        )
        val good = listOf(
            "fun ShutterFab(",
            "    onClickLabel: String,",
            "    onClick: () -> Unit,",
            ") {",
            "    Box(",
            "        modifier = Modifier",
            "            .size(60.dp)",
            "            .combinedClickable(onClick = onClick)",
            "            // 名字跟着调用方走",
            "            .semantics { contentDescription = onClickLabel },",
            "    ) {",
            "        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)",
            "    }",
            "}",
        )
        assertEquals(
            "真源码那个形状（语义挂在可点那颗的 modifier 上，图标自己闭嘴）不许误判，也不许空跑",
            1 to emptyList<String>(),
            mislabelledLabelledControls(good.map { if (it.contains("// 名字")) it.substringBefore("//") else it }),
        )
    }
}
