package com.suipai.app.ui

import com.suipai.app.ui.gallery.HourBuckets
import com.suipai.app.ui.gallery.OnlyKinds
import com.suipai.app.ui.gallery.RatioFilters
import com.suipai.app.ui.gallery.RecencyRules

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面层的几条不变量，用**读源码**的方式钉住。
 *
 * 为什么不写成普通断言测试：这三条都是"编译器不报错、单测跑不出来、设备上量到要起模拟器"
 * 的那类问题，而这台机器上设备侧的观测通道时好时坏（logcat 被 goldfish-logcat 抽干、
 * 软件渲染下 gfxinfo 一律贴着 100% jank）。所以退回到最便宜也最确定的证据：源码本身。
 *
 * 每条都对应一个实测过的缺陷：
 * 1. `horizontalScroll` 装动作按钮 —— 滚出视野的格子既点不到也不在语义树里
 *    （相册/查看器/一瞬三个界面都犯过，见 AppBar.kt 顶部的记录）。
 * 2. 进 Lazy 网格的条目模型没标 @Immutable —— Compose 认不出 `Uri` 这类平台类型，
 *    会把整个 data class 判成 unstable，列表一重建，屏幕上每一格缩略图全部重组。
 * 3. `ImageRequest.Builder(...)` 直接写在 composable 里 —— Coil 的 ImageRequest 没有 equals，
 *    每次重组都是一个"新"请求，边滚边重新派发、crossfade 从头再来。
 *
 * 判定逻辑做成纯函数，并且各自带一条"故意喂一个坏例子"的自检：
 * 没见过自己变红的守卫不算守卫。
 */
class PerfSourceGuardTest {

    companion object {

        /** 去掉整行/行内注释，避免把"解释旧 bug 的注释"当成违规。返回 (1 起行号, 代码)。 */
        /**
         * 相机后处理不许静默放弃：`?: return uri` / `out?.use {}` 这两种写法在量到的
         * 那一刻（MediaStore 还不许重开刚存的行）会把"用户要 1:1、结果存了相机原帧"
         * 变成一件没有任何提示的事。要嘛重试，要嘛说句话，不许静默。
         */
        fun silentPostProcessEscapes(lines: List<String>): List<Int> =
            codeLines(lines).mapNotNull { (n, t) ->
                val bad = t.contains("?: return uri") || t.contains("out?.use") ||
                    t.contains("?.use { working.compress") ||
                    // 结果被丢掉的 compress：`openOutputStream(uri, "wt")` 一开就把文件截空，
                    // 压不回 JPEG 时那一行就是张坏图，而界面还说「已保存」。
                    (t.contains(".compress(") && !t.contains("if (!") && !t.contains("="))
                if (bad) n else null
            }

        /**
         * 15. 不许有"吞掉 startActivity 失败"的写法。
         *
         * 八个出口原来都是 `runCatching { context.startActivity(chooser) }`，一个 catch 都不写：
         * 没有可接手的应用时，用户点了「分享 / 打开 / 地图」就是什么都不发生。
         * 判据不禁止 startActivity 本身（PermissionGate 那两处是直接调的，失败会响），
         * 禁的是**把它包在 runCatching 里而不说**。都走 `ui/common/Launch.kt` 的 `sendOrTell`。
         */
        fun swallowedLaunches(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            val hits = ArrayList<Int>()
            for ((pos, pair) in code.withIndex()) {
                if (!pair.second.contains(".startActivity(")) continue
                val back = code.subList(maxOf(0, pos - 8), pos).map { it.second }
                if (back.any { it.contains("runCatching") } &&
                    !code.subList(pos, minOf(code.size, pos + 6)).any { "tell(" in it.second.lowercase() }
                ) {
                    hits += pair.first
                }
            }
            return hits
        }

        fun codeLines(lines: List<String>): List<Pair<Int, String>> =
            lines.mapIndexedNotNull { i, raw ->
                val t = raw.substringBefore("//").trim()
                if (t.isEmpty() || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to t
            }

        /**
         * 6. `GalleryFilters` 的每一个字段，都必须是 `val visible = remember(...)` 的键之一。
         *
         * 这条的坏例子长得最像"什么都没坏"：给筛选对象加一个字段、忘了补进那串键，
         * 于是 `remember` 认为输入没变，**改完开关时间线根本不重算**——
         * 格子标签会变、徽标数字会变，只有结果不动。编译器不报，单测跑不到（`visiblePhotos`
         * 是纯函数，单测里根本没经过 `remember`），设备上要点两次才勉强看得出来。
         */
        fun unkeyedFilterFields(lines: List<String>): List<String> {
            val code = codeLines(lines).map { it.second }
            val fields = ArrayList<String>()
            var inside = false
            for (t in code) {
                if (t.startsWith("internal class GalleryFilters(")) { inside = true; continue }
                if (!inside) continue
                Regex("""^val (\w+):""").find(t)?.let { fields += it.groupValues[1] }
                if (t == ")") inside = false
            }
            if (fields.isEmpty()) return listOf("<没找到 GalleryFilters 的字段，读法失效>")
            val keyLine = code.firstOrNull { it.startsWith("val visible = remember(") }
                ?: return listOf("<没找到 val visible = remember(...) 那一行>")
            return fields.filterNot { k ->
                keyLine.contains("$k,") || keyLine.contains("$k)") ||
                    keyLine.contains("gflags.$k,") || keyLine.contains("gflags.$k)")
            }
        }

        /**
         * 7. 偏好键不许"只写不读"。
         *
         * 这一条抓的就是 §19 那类洞在偏好层的模样：设置存下来了，却没有任何人读它。
         * 量到的实例是 `yishun_int`：±、轻/中/重 六个写入点全打在同一个裸键上，
         * 而读取清一色用带配方号的 `yishun_int_<号>` —— 于是"调完强度切回来就没了"。
         *
         * 两种形状分别判：
         *  - 普通键：该字面量出现的**每一次**都在 `putX("k", …)` 里 => 死键；
         *  - 带插值的键（`"yishun_int_$selectedId"`）：读写两侧的变量名不同，字面量对不上，
         *    所以按 `$` 之前的前缀归并，只要没有任何 `getX` 提到同一前缀就算死族。
         */
        fun deadPreferenceKeys(sources: List<Pair<String, String>>): List<String> {
            val put = Regex("""\.put(?:Int|Long|Boolean|String|Float)\(\s*"([^"]+)""")
            val get = Regex("""\.get(?:Int|Long|Boolean|String|Float|OrNull|Value)\(\s*"([^"$]*)""")
            val puts = HashMap<String, Int>()
            val texts = ArrayList<String>()
            for ((_, text) in sources) {
                texts += text
                put.findAll(text).forEach { puts[it.groupValues[1]] = (puts[it.groupValues[1]] ?: 0) + 1 }
            }
            // 按"整个带引号的键"数出现次数。用配对引号的正则来数会踩坑：
            // 一行里有个 `getString("k", "")` 时，那对空串会把引号配对整体挪一位，
            // 于是同一个真实存在的读取被数丢了（`shot_n` 就是这么被误判成死键的）。
            fun countToken(quoted: String): Int {
                var n = 0
                for (t in texts) {
                    var i = t.indexOf(quoted)
                    while (i >= 0) {
                        n++
                        i = t.indexOf(quoted, i + quoted.length)
                    }
                }
                return n
            }
            val readPrefixes = sources.flatMap { (_, text) -> get.findAll(text).map { it.groupValues[1] } }.toSet()
            val out = ArrayList<String>()
            for (k in puts.keys) {
                if (k.contains('$')) {
                    val prefix = k.substringBefore('$')
                    if (prefix.length >= 3 && readPrefixes.none { it.startsWith(prefix) }) out += "$k（前缀 $prefix 没人读）"
                } else if (k.contains('_') && countToken("\"" + k + "\"") == (puts[k] ?: 0)) {
                    out += k
                }
            }
            return out.sorted()
        }

        /**
         * 8. 每一格"点一下换一档"的循环控件：模数必须与"有几个可显示的档"配得上。
         *
         * 少一档 = 那一档永远点不到（点到底跳回默认，用户以为控件坏了）；多一档 = 有一档只能落到
         * `else` 的默认标签上（写着「按数量」其实是别的）。这类错编译器不报、lint 不报，
         * 设备上要一格一格点满一圈才看得见 —— §18/§19 我就是这么手点出来的，这一篇把它变成断言。
         *
         * 一个格子的"应有档数"有两个来源，按能查到的那个判：
         *  - 同文件里有 `when (X) { 1 -> "…" }` 这样的标签块（`label = when (X)` 或
         *    `val sortLabel = when (X)` 都算）：臂数（+1 若有 `else` 兜默认档）；
         *  - 标签从表里读（§19c/§21 之后越来越多）：由调用方把表长传进来（`expected`）。
         * 两处都查不到的格子不判（相机里那些标签是枚举自己带的），
         * 但**核对到的格子数**由调用方钉一个下限，防止这条哪天悄悄变成空跑。
         */
        fun cycleCells(lines: List<String>, expected: Map<String, Int>): Pair<Int, List<String>> {
            val text = lines.joinToString("\n")
            val mods = LinkedHashMap<String, LinkedHashSet<Int>>()
            Regex("""(\w+) = \(\1 \+ 1\) % (\d+)""").findAll(text).forEach {
                mods.getOrPut(it.groupValues[1]) { LinkedHashSet() } += it.groupValues[2].toInt()
            }
            val blockCount = HashMap<String, Int>()
            Regex("""when \((\w+)\) \{([\s\S]*?)\n\s*\}""").findAll(text).forEach { m ->
                val body = m.groupValues[2]
                val arms = Regex("""^\s*(\d+)\s*->\s*\"""", RegexOption.MULTILINE).findAll(body).toList().size
                if (arms >= 3) {
                    val hasElse = Regex("""^\s*else\s*->""", RegexOption.MULTILINE).containsMatchIn(body)
                    blockCount[m.groupValues[1]] = arms + if (hasElse) 1 else 0
                }
            }
            val out = ArrayList<String>()
            var checked = 0
            for ((varName, ns) in mods) {
                if (ns.size > 1) {
                    out += "$varName：同一个变量有两处不同的模数 $ns（转一圈会跳档）"
                    checked++
                    continue
                }
                val mod = ns.first()
                val want = blockCount[varName] ?: expected[varName] ?: continue
                checked++
                if (mod != want) out += "$varName：模 $mod，应有 $want 档"
            }
            return checked to out
        }

        /**
         * 9. 批量动作顶栏那句进度，不许由"试到第几张"推导。
         *
         * 量过的两个实例（`MainActivity.batchDelete` / `batchCopy`，同一个形状）：
         * 循环里写 `batchHint = "${i + 1}/${picked.size}"`，`i` 是**尝试**的下标；
         * 而那两个被调的仓库函数失败时都不抛异常 —— 删外来照片抛
         * `RecoverableSecurityException` 被 `runCatching` 吞成 false，
         * 存副本打不开源文件返回 null。于是 23 张一张没动，进度条也能走到 23/23，
         * 走完之后什么也不说。编译器不报，设备上"看起来一切正常"。
         *
         * 形状判据：`forEachIndexed { i, … }` 的体内，给 `batchHint`/`batchNote`
         * 赋值的那一行提到了本层的下标变量。计数变量（`deleted.size`、`done`）不算。
         * 和 8 一样带核对数下限，防止正则哪天不匹配了这条变成空跑。
         */
        fun attemptCountProgress(lines: List<String>): Pair<Int, List<Int>> {
            val code = codeLines(lines)
            val bad = ArrayList<Int>()
            var checked = 0
            for ((pos, pair) in code.withIndex()) {
                val m = Regex("""forEachIndexed\s*\{\s*(\w+)\s*,""").find(pair.second) ?: continue
                val idx = Regex("""\b${m.groupValues[1]}\b""")
                checked++
                for ((n, t) in code.subList(pos + 1, minOf(code.size, pos + 16))) {
                    // 别用三引号写这个：内容以一个引号结尾时，它会和收尾的 `"""` 粘在一起，原始串就地截断（而且报错位置在文件另一头，找起来像别人的问题）。
                    if (!Regex("batch(?:Hint|Note)\\s*=").containsMatchIn(t)) continue
                    if (idx.containsMatchIn(t)) bad += n
                }
            }
            return checked to bad
        }

        /**
         * 从 `fun <fn>(` 那一行起，按花括号配平取出整个函数体（含签名行）。找不到返回 null。
         *
         * `seen` 不是多余的：多行签名的前几行还没有 `{`，那时 depth 仍是 0，
         * 一收工就把"函数体"读成空表 —— 17 号守卫的第一版正是这样把干净样本误判成违规的。
         * 三个判据共用这一份走查：复制三遍，就会有三份各自跑偏的机会。
         */
        fun functionBody(lines: List<String>, fn: String): List<String>? {
            val start = lines.indexOfFirst { Regex("fun\\s+$fn\\s*\\(").containsMatchIn(it) }
            if (start < 0) return null
            val body = ArrayList<String>()
            var depth = 0
            var seen = false
            var i = start
            while (i < lines.size) {
                val code = lines[i].substringBefore("//")
                depth += code.count { it == '{' } - code.count { it == '}' }
                if (depth > 0) seen = true
                body += lines[i]
                i++
                if (seen && depth <= 0) break
            }
            return body
        }

        /**
         * 17. shared 里那几个对端网络函数不许把"取消"当成"网络失败"。
         *
         * 取消是用户关掉页面 / `stop()` 发出来的；被 `catch (e: Throwable)` 吞掉之后，
         * 这一趟会装作正常返回一个空结果，界面上的「正在读取…」和上层的循环都不肯停。
         * 判据：每个 suspend 网络函数的函数体内必须有一个 `catch (e: CancellationException)`。
         */
        fun missingCancellationRethrow(lines: List<String>, wanted: List<String>): List<String> {
            val missing = ArrayList<String>()
            for (fn in wanted) {
                val body = functionBody(lines, fn)
                if (body == null) {
                    missing += "$fn(读法失效：suspend 函数没找到)"
                    continue
                }
                if (body.none { it.contains("catch (e: CancellationException)") }) missing += fn
            }
            return missing
        }

        /**
         * 18. 工具条那两颗自己画的格子（`AppBarCell` / `AppBarPrimary`）必须把话说在**可点那一颗**节点上。
         *
         * 装机量到的三种错法，读屏用户都拿不到"这颗是什么"：
         *  · 用 Material `Button`：可见文字留在子节点，可点的父节点 `content-desc` 是空的，
         *    TalkBack 只说「按钮，双击激活」（而且 `cls` 是 `android.view.View`，连"按钮"都不报）；
         *  · 在 `Button` 外面套 `clearAndSetSemantics`：`Button` 的 `clickable` 接在我们传入的
         *    modifier **之后**（更靠内），清空语义把 click 动作一起清了 —— `click=false`，比原来更糟；
         *  · 只在外层 `semantics { contentDescription = … }`：话落不到可点那颗上，量到 `desc=''`。
         * 判据要的形状：这两个函数体内
         *  · 不许出现 Material `Button(`；
         *  · 每一处 `.clickable(` 自己带上 `role = Role.Button`；
         *  · 紧跟其后（两行内）有一个 `clearAndSetSemantics { … contentDescription … }`。
         */
        fun toolbarCellsThatDoNotSpeak(lines: List<String>, wanted: List<String>): List<String> {
            val bad = ArrayList<String>()
            for (fn in wanted) {
                val raw = functionBody(lines, fn)
                if (raw == null) {
                    bad += "$fn(读法失效：函数没找到)"
                    continue
                }
                val body = raw.map { it.substringBefore("//") }
                if (body.any { Regex("(^|[^\\w.])Button\\(").containsMatchIn(it) }) {
                    bad += "$fn：又用回 Material Button 了（可点那颗没有名字）"
                    continue
                }
                val clicks = body.mapIndexedNotNull { n, code ->
                    if (Regex("\\.clickable\\(").containsMatchIn(code)) n else null
                }
                if (clicks.isEmpty()) {
                    bad += "$fn：读法失效（函数体里没有 .clickable(）"
                    continue
                }
                for (n in clicks) {
                    val near = body.subList(n, minOf(body.size, n + 3)).joinToString(" ")
                    if (!near.contains("role = Role.Button")) {
                        bad += "$fn：可点那颗没报 role=Button（读屏不说「按钮」）"
                    }
                    if (!Regex("clearAndSetSemantics\\s*\\{[^}]*contentDescription")
                            .containsMatchIn(near)
                    ) {
                        bad += "$fn：可点那颗没带 contentDescription（读屏只说「按钮」）"
                    }
                }
            }
            return bad
        }

        /**
         * 19. 抽屉 / 工具条里点得动的格子，不许在"现在无事可做"时一声不响地 return。
         *
         * 装机账：一瞬的「更多」抽屉里「分享 / 撤销」永远亮着，而它们第一行就是
         * `val target = lastSaved ?: return@AppBarCell` —— 没存过照片时点下去什么都不发生；
         * 页眉那句 `message` 又正好被抽屉盖住，等于连"事后解释"都没有。
         * 判据要的形状：每一处 `?: return@AppBarCell` 所属的那一次 `AppBarCell(` 调用里，
         * 必须出现 `enabled =` —— 也就是"做不了的时候它自己就是灰的"，用户看得见。
         * （要么就压根别 return：写一句会显示出来的话。这一条管的是"悄悄 return"。）
         */
        fun silentCellReturns(lines: List<String>): List<Int> {
            val bad = ArrayList<Int>()
            for (i in lines.indices) {
                val code = lines[i].substringBefore("//")
                if (!code.contains("?: return@AppBarCell")) continue
                var start = -1
                var back = i - 1
                while (back >= 0 && i - back <= 20) {
                    if (lines[back].substringBefore("//").contains("AppBarCell(")) {
                        start = back
                        break
                    }
                    back--
                }
                if (start < 0) {
                    bad += i + 1          // 读不到所属格子：也算违规，逼人来改这条判据
                    continue
                }
                val gated = (start..i).any { lines[it].substringBefore("//").contains("enabled =") }
                if (!gated) bad += i + 1
            }
            return bad
        }

        /**
         * 20. `AppBarRow` 必须"装不下就换行"，不许回到单行 `Row` 或固定高度。
         *
         * 装机量到（系统字级 2.0）：画廊那一排里页名 + 帧数先吃掉 547 px，
         * 四颗动作格子从 178/178/178/185 被挤成 178 / 139 / **15** / 95 ——
         * 「筛选」只剩 15 px 宽，看不见也点不着。`Row` 没有"这颗不许缩"的选项，
         * 横向滚动又是 1 号守卫明令禁止的（滚出视野的节点根本不在语义树里），
         * 所以唯一的出路是 `FlowRow` + `heightIn(min = …)`。
         */
        fun toolbarRowThatStarvesCells(lines: List<String>): List<String> {
            val body = functionBody(lines, "AppBarRow") ?: return listOf("AppBarRow(读法失效：函数没找到)")
            val code = body.map { it.substringBefore("//") }
            val bad = ArrayList<String>()
            if (code.any { it.contains("horizontalScroll") }) bad += "AppBarRow：又回到横向滚动（滚出去就不在语义树里）"
            if (!code.any { Regex("(^|[^\\w.])FlowRow\\(").containsMatchIn(it) }) {
                bad += "AppBarRow：不是 FlowRow，字级一大就会把格子挤成缝"
            }
            if (code.any { it.contains(".height(AppBarHeight)") }) {
                bad += "AppBarRow：高度钉死在 AppBarHeight，换行会被裁掉"
            }
            if (!code.any { it.contains("heightIn(min") }) bad += "AppBarRow：没有 heightIn(min = …)"
            return bad
        }

        /**
         * 21. `AppBarRow` 里不许再出现裸 `Text(` —— 工具条的文字一律走 `AppBarText`。
         *
         * `AppBarRow` 是 `FlowRow`，而这一版 Compose（1.7.6）没有 `itemVerticalAlignment`：
         * 一行里最高的那颗（格子 48 dp）定了行高，裸 `Text` 会贴到行的上沿，
         * 装机量到页名比同排格子高 22 px。`AppBarText` 与格子同高、内容垂直居中，
         * 并且把 `testTag` / `clickable` 留在文字那颗节点上。
         */
        fun rawTextsInToolbars(lines: List<String>): List<Int> {
            val bad = ArrayList<Int>()
            var i = 0
            while (i < lines.size) {
                if (!lines[i].substringBefore("//").contains("AppBarRow(")) {
                    i++
                    continue
                }
                var depth = 0
                var seen = false
                var j = i
                while (j < lines.size) {
                    val code = lines[j].substringBefore("//")
                    depth += code.count { it == '{' } - code.count { it == '}' }
                    if (depth > 0) seen = true
                    j++
                    if (seen && depth <= 0) break
                }
                for (k in i until minOf(lines.size, j)) {
                    val code = lines[k].substringBefore("//")
                    if (Regex("(^|[^A-Za-z])Text\\(").containsMatchIn(code)) bad += k + 1
                }
                i = if (j > i) j else i + 1
            }
            return bad
        }

        /**
         * 22. `singleLine` 输入框的 placeholder 必须自己也是单行。
         *
         * `singleLine = true` 只管用户输入，**不管提示语**：提示语是一颗普通的 `Text`，
         * 装不下就自己折行，把输入框撑高。装机量到（720×1600 + 字级 2.0）：
         * 画廊搜索框被「文件名、相册、2024-09、今天」撑到 **528 px 高**，
         * 时间线一颗格子都不剩 —— 用户看到的是"相册空了"。
         * 判据：`placeholder = {` 那段里出现 `Text(` 就必须出现 `maxLines`，
         * 且只在它上下 12 行内确有 `singleLine = true` 时才算（别管多行输入框的提示语）。
         */
        fun wrappingPlaceholders(lines: List<String>): List<Int> {
            val code = lines.map { it.substringBefore("//") }
            val bad = ArrayList<Int>()
            for (i in code.indices) {
                if (!code[i].contains("placeholder = {")) continue
                val near = code.subList(maxOf(0, i - 12), minOf(code.size, i + 13))
                if (near.none { it.contains("singleLine = true") }) continue
                var depth = 0
                var seen = false
                var j = i
                var end = code.size
                while (j < code.size) {
                    depth += code[j].count { it == '{' } - code[j].count { it == '}' }
                    if (depth > 0) seen = true
                    j++
                    if (seen && depth <= 0) {
                        end = j
                        break
                    }
                }
                val body = code.subList(i, minOf(code.size, end))
                if (body.any { it.contains("Text(") } && body.none { it.contains("maxLines") }) {
                    bad += i + 1
                }
            }
            return bad
        }

        /**
         * 14. 每一路 `batch*` 都得有一个"无条件写结论"的出口。
         *
         * 装机复测点「副本」：库里确实多了一行，界面上一个字都没有 ——
         * `batchCopy` 只在 `failed > 0` 时才写 batchNote；`batchHide`/`batchFavorite`
         * 一句都不写；`batchYishun` 只写失败那一句，而且写在进度位 `batchHint` 上（没人清它）。
         * 判据要的形状：函数体内出现一行 `batchNote = 某个 *Note(` ——
         * 结论出自 `ui/common/BatchNote.kt` 那几个纯函数（BatchNoteTest 穷举过：
         * 任何输入都有一句非空、带张数的话），而不是埋在某个 `if` 里面。
         */
        fun batchActionsWithoutNote(lines: List<String>, wanted: List<String>): List<String> {
            val missing = ArrayList<String>()
            for (fn in wanted) {
                val body = functionBody(lines, fn)
                if (body == null) {
                    missing += "$fn(读法失效：函数没找到)"
                    continue
                }
                if (body.none { Regex("batchNote\\s*=\\s*\\w+Note\\(").containsMatchIn(it) }) {
                    missing += fn
                }
            }
            return missing
        }

        /**
         * 10. 不允有"被同一行注释吞掉的 `@Test`"。
         *
         * 今天真踩的：一次删空行的编辑把 `// ── 自检…` 与下一行的 `@Test` 粘在了一起，
         * 于是 `checkersActuallyCatchBadCode` 丢了注解 —— 套件照旧"全绿"，
         * 而**变异样本一条都没跑**。守卫不跑了与守卫通过了，在测试报告里长得一模一样。
         *
         * 只认这个形状（不是"注释里提到 @Test"，那种合法）：
         * 本行去掉注释之后没了代码，注释里有 `@Test`/`@Ignore`，下一行紧跟着一个 `fun `。
         */
        fun swallowedTestAnnotations(lines: List<String>): List<Int> =
            lines.mapIndexedNotNull { i, raw ->
                val cut = raw.indexOf("//")
                if (cut < 0) return@mapIndexedNotNull null
                if (raw.substring(0, cut).trim().isNotEmpty()) return@mapIndexedNotNull null
                val tail = raw.substring(cut)
                if (!("@Test" in tail || "@Ignore" in tail)) return@mapIndexedNotNull null
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.startsWith("fun ")) i + 1 else null
            }

        /**
         * 11. 剪贴板只许有一个写入口。
         *
         * 之前全项目 20 处写入，每一处都是
         * `getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)` 然后无条件亮"已复制"——
         * 那个 `?.` 让"服务没拿到"变成一次成功的空操作，其中一格连 `onFailure` 都没有。
         * 现在判据收进 `ui/common/Clip.kt` 的 `clipWrite`，这条守卫钉住两件事：
         * 别人不许绕开它自己写，而且它自己必须真的写一次（只剩一处，别把它删空了还全绿）。
         */
        fun clipboardWriters(sources: List<Pair<String, String>>): Pair<List<String>, Int> {
            val stray = ArrayList<String>()
            var sanctioned = 0
            for ((name, text) in sources) {
                text.lineSequence().forEachIndexed { i, l ->
                    val t = l.trim()
                    // Clip.kt 自己的文档里就抄了一遍旧形状，注释不算写入
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                    if (!t.contains("setPrimaryClip")) return@forEachIndexed
                    if (name == "Clip.kt") sanctioned++ else stray += "$name:${i + 1}"
                }
            }
            return stray.sorted() to sanctioned
        }

        /**
         * 12. 一个格子两个动作的控件，两个动作都得有名字。
         *
         * `combinedClickable` 是本项目里"同一格既能点又能长按"的唯一形状：
         * 照片格子（点开 / 加多选）、相册封面（打开 / 钉住，整理藏册时点击还改成收放）、
         * 一瞬配方芯片（用这个配方 / 钉住）、快门（拍照 / 长按连拍）、查看器信息条（无 / 复制全部信息）。
         * 不写 `onClickLabel`/`onLongClickLabel`，读屏的"操作"列表里就是两个无名动作 ——
         * 而这些规则对看得见角标的人显然，对听的人一点都不显然（"长按=多选"尤其）。
         *
         * 只判这一种形状，不判 `Modifier.clickable`：带可见文字的按钮，TalkBack 念的就是那行字，
         * 动作没有歧义。判据：参数区里必须出现 `onClickLabel`（显式写 `null` 也算 —— 那是
         * "这层确实没有点击动作"的声明，比留空好），出现 `onLongClick =` 就必须有 `onLongClickLabel`。
         */
        fun unlabeledPressActions(lines: List<String>): Pair<Int, List<String>> {
            val out = ArrayList<String>()
            var seen = 0
            var i = 0
            while (i < lines.size) {
                val raw0 = lines[i]
                val cut = raw0.indexOf("//")
                val head = if (cut >= 0) raw0.substring(0, cut) else raw0
                val at = head.indexOf("combinedClickable(")
                if (at < 0) {
                    i++
                    continue
                }
                seen++
                val body = StringBuilder(head.substring(at + "combinedClickable(".length))
                var depth = 1
                var j = i
                while (depth > 0 && j + 1 < lines.size) {
                    j++
                    var l = lines[j]
                    val c2 = l.indexOf("//")
                    if (c2 >= 0) l = l.substring(0, c2)
                    for (ch in l) {
                        if (ch == '(') depth++
                        else if (ch == ')') depth--
                    }
                    body.append(l).append("\n")
                }
                val text = body.toString()
                val line = i + 1
                if (!text.contains("onClickLabel")) out += "$line:\u6ca1\u6709 onClickLabel"
                if (Regex("onLongClick\\s*=").containsMatchIn(text) && !text.contains("onLongClickLabel")) {
                    out += "$line:\u6709\u957f\u6309\uff0c\u4f46\u6ca1\u7ed9 onLongClickLabel"
                }
                i = maxOf(i + 1, j)
            }
            return seen to out
        }

        /**
         * 13. `DateTimeFormatter.ofPattern` 只许出现在**声明行**上。
         *
         * `ofPattern` 每次调用都要解析一遍模式串、建一个带 region 数据的对象。
         * 放在数据函数里看着无害，但它跟着格子跑：相册卡每重组一次就造两个
         * （卡面小字一份、读屏那一句一份），而多选、进度写入、选中态变化都会让整屏卡片重组。
         * 这一条就是这么抓出来的：给相册卡加读屏标签时，同一段格式化被复制了一份，
         * 顺手才发现原来那个 `ofPattern` 就写在函数体里。
         */
        fun inlineFormatters(lines: List<String>): List<Int> =
            codeLines(lines).filter { (_, t) -> "ofPattern(" in t && !t.contains("val ") }.map { it.first }

        fun horizontalScrollUses(lines: List<String>): List<Int> =
            codeLines(lines).filter { (_, t) -> "horizontalScroll(" in t }.map { it.first }

        /** 返回"该标却没标 @Immutable"的名字。 */
        fun missingImmutable(lines: List<String>, wanted: Set<String>): List<String> {
            val found = mutableSetOf<String>()
            for (i in lines.indices) {
                val t = lines[i].trim()
                for (name in wanted) {
                    if (t.startsWith("data class $name(") || t.startsWith("private data class $name(")) {
                        var j = i - 1
                        while (j >= 0 && lines[j].trim().startsWith("@")) {
                            if (lines[j].trim().startsWith("@Immutable")) found += name
                            j--
                        }
                    }
                }
            }
            return (wanted - found).sorted()
        }

        /** 返回"没被 remember 包住"的 ImageRequest.Builder 行号。 */
        fun unrememberedRequests(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            val bad = mutableListOf<Int>()
            for ((pos, pair) in code.withIndex()) {
                val (n, t) = pair
                if (!t.contains("ImageRequest.Builder(")) continue
                val window = code.subList(maxOf(0, pos - 4), pos + 1).joinToString(" ") { it.second }
                if (!window.contains("remember(")) bad += n
            }
            return bad
        }

        /**
         * 返回"没被 remember 包住"的**全库遍历**行号。
         *
         * 一瞬那一列配方芯片以前是 `val chips = buildList { addAll(LookLibrary.all.filter{...}) }`
         * 直接写在 composition 里 —— 5446 条的一次全表过滤 + 一次搜索过滤 + 一次排序。
         * 它所在的那一层在**拖强度滑杆时每个指针事件都重组**（`intensity` 就在同一层读），
         * 桌面 JVM warm 量到最坏 ~12 ms 一次，ART 上按 2~5 倍算就是每帧预算的一半到全部。
         * 这类"每帧重跑一遍大表"编译器不报、这台 AVD 又没有硬件 GPU 量不了帧率（见 §1b），
         * 所以钉在源码形状上。
         */
        fun unrememberedLibraryPass(lines: List<String>): List<Int> {
            val code = codeLines(lines)
            val bad = mutableListOf<Int>()
            for ((pos, pair) in code.withIndex()) {
                val (n, t) = pair
                if (!Regex("""LookLibrary\.all\.(filter|map|sorted|groupBy)""").containsMatchIn(t)) continue
                // `remember(a, b) { buildList { ... } }` 这种写法里，遍历可能在 remember 那行下面十行，
                // 窗口给窄了会把已经修好的形状误判成违规（真踩过）。
                val window = code.subList(maxOf(0, pos - 20), pos + 1).joinToString(" ") { it.second }
                if (!window.contains("remember(")) bad += n
            }
            return bad
        }

        /**
         * 位图袋按 uri 建 / 收的形状。
         *
         * 一瞬换照片那一帧，`original`/`preview` 还指着**上一张**（新的要几百毫秒才解出来），
         * 而 `DisposableEffect(uri)` 的 onDispose 就在那一帧之前跑 → 正在被 draw 的位图被回收，
         * 直接 FATAL：`Canvas: trying to use a recycled bitmap`。
         * 2026-09-21 在 release 包上实测 12 次翻片全崩（`C:\tmp\r5\land\flip_stress.py`），
         * 改成"新图全挂上状态之后才收旧袋"之后同样 12 次全过。
         */
        fun uriScopedBitmapBag(lines: List<String>): List<Int> =
            codeLines(lines).filter { (_, t) ->
                ("remember(uri)" in t && "BitmapBag" in t) || t == "DisposableEffect(uri) {"
            }.map { it.first }

        /**
         * 22. 每一处 `pro.toggle()` 后面都必须真的把计划下发出去（P0-1）。
         *
         * 这是"纯函数全绿、界面却在说谎"的样板：`planCaptureOptions(ProMode.Auto, …)`
         * 返回 `Reset` 有测试钉着（`disabledModeAlwaysPlansAReset_notANoOp`），
         * 但**没有任何东西保证有人去调 `apply`**。实际代码里两个 PRO 开关入口
         * （顶栏那颗 `CamProToggleCell`、抽屉里的 `cam_pro_sheet`）都只调了 `pro.toggle()`，
         * 于是关了 PRO 之后 session 上的 `SENSOR_SENSITIVITY` 还在，下一张仍按上次的 ISO 曝光。
         *
         * 判据要的形状：`pro.toggle()` 那一行起算，**向下 8 行内**必须出现
         * `applyProNow(` 或 `CameraProBindings.apply`。
         * 为什么是"向下"而不是"向上"：这个动作必须发生在状态翻转**之后**
         * （先 toggle 再 apply，否则下发的是旧计划）；写在上面一样是错的。
         */
        fun proTogglesWithoutApply(lines: List<String>, window: Int = 8): List<Int> {
            val code = codeLines(lines)
            val bad = ArrayList<Int>()
            code.forEachIndexed { pos, (n, t) ->
                if (!t.contains("pro.toggle()")) return@forEachIndexed
                val after = code.subList(pos, minOf(code.size, pos + window + 1)).joinToString(" ") { it.second }
                val applied = after.contains("applyProNow(") || after.contains("CameraProBindings.apply")
                if (!applied) bad += n
            }
            return bad
        }
    }

    private val srcRoot: File
        get() {
            // AGP 跑 JVM 单测时工作目录就是模块根（app/）。路径不对就直接红，
            // 不要"找不到就跳过"——那种守卫等于没有。
            val f = File("src/main/java/com/suipai/app")
            assertTrue("找不到源码根，工作目录变了：${f.absolutePath}", f.isDirectory)
            return f
        }

    private fun kotlinFiles(): List<File> =
        srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ── 真源码 ────────────────────────────────────────────────────────

    @Test
    fun noHorizontalScrollOnActionRows() {
        val hits = kotlinFiles().flatMap { f -> horizontalScrollUses(f.readLines()).map { "${f.name}:$it" } }
        assertTrue("动作按钮又回到横向滚动条里了（滚出去就点不到）：$hits", hits.isEmpty())
    }

    @Test
    fun lazyGridModelsAreImmutable() {
        val all = kotlinFiles().flatMap { it.readLines() }
        val missing = missingImmutable(
            all,
            setOf("PhotoItem", "AlbumItem", "DaySection", "GalleryFlags", "AlbumFlags"),
        )
        assertTrue("这些会进 Lazy 网格/列表的模型没标 @Immutable：$missing", missing.isEmpty())
    }

    @Test
    fun imageRequestsAreRemembered() {
        val hits = kotlinFiles().flatMap { f -> unrememberedRequests(f.readLines()).map { "${f.name}:$it" } }
        assertTrue("这些 ImageRequest 每重组一次就新建一个：$hits", hits.isEmpty())
    }

    @Test
    fun libraryScansAreRemembered() {
        val hits = kotlinFiles().flatMap { f -> unrememberedLibraryPass(f.readLines()).map { "${f.name}:$it" } }
        assertTrue("这些全库遍历写在 composition 里（每重组一次就跑一遍 5446 条）：$hits", hits.isEmpty())
    }

    @Test
    fun yishunBitmapBagIsNotUriScoped() {
        val f = File("src/main/java/com/suipai/app/ui/yishun/YishunScreen.kt")
        assertTrue("找不到 YishunScreen：${f.absolutePath}", f.isFile)
        val hits = uriScopedBitmapBag(f.readLines())
        assertTrue(
            "位图袋又按 uri 建/收了：换照片那一帧画面还在画上一张，回收它就是 " +
                "FATAL Canvas: trying to use a recycled bitmap（实测 12 次翻片全崩）：$hits",
            hits.isEmpty(),
        )

        // 自检：判定真的会因为那两种形状变红，也不会把注释和正确的写法算进去
        val dirty = listOf(
            "    val bag = remember(uri) { BitmapBag() }",
            "    DisposableEffect(uri) {",
            "        onDispose { bag.recycle() }",
            "    }",
        )
        assertEquals(listOf(1, 2), uriScopedBitmapBag(dirty))
        val clean = listOf(
            "    // 以前这里是 remember(uri)，换照片会崩，见上",
            "    var bag by remember { mutableStateOf(BitmapBag()) }",
            "    DisposableEffect(Unit) {",
            "        onDispose { bag.recycle() }",
            "    }",
        )
        assertEquals(emptyList<Int>(), uriScopedBitmapBag(clean))
    }

    @Test
    fun cameraPostProcessNeverFailsQuietly() {        val f = File("src/main/java/com/suipai/app/ui/camera/CameraScreen.kt")
        assertTrue("找不到 CameraScreen：${f.absolutePath}", f.isFile)
        val hits = silentPostProcessEscapes(f.readLines())
        assertTrue(
            "相机后处理里又有静默放弃的形状：实测十次里约两次 MediaStore 还不许重开刚存的那一行，" +
                "老写法 `?: return uri` 就把没裁过的原帧默默交给了用户（格子写着 1:1、照片是 4:3）：$hits",
            hits.isEmpty(),
        )

        val dirty = listOf(
            "    val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return uri",
            "    out?.use { working.compress(Bitmap.CompressFormat.JPEG, 95, it) }",
            // 结果丢掉的那一种：文件已经被 "wt" 截空，压坏了就是一张坏图
            "    out.use { working.compress(JPEG, 95, it) }",
        )
        assertEquals(listOf(1, 2, 3), silentPostProcessEscapes(dirty))
        // 正确的写法（重试 + 说句话 + 接住 compress 的结果）不许被误伤；
        // 读流时那个 `?.use {}` 是必须的，也不算违规
        val clean = listOf(
            "    val src = retryOpenStream { cr.openInputStream(uri)?.use { decode(it) } } ?: error(\"读不到刚存的那张\")",
            "        if (!working.compress(JPEG, 95, buf)) { error(\"这张压不回 JPEG\") }",
        )
        assertEquals(emptyList<Int>(), silentPostProcessEscapes(clean))
    }

    /**
     * 22 的真源码那一边：`CameraScreen.kt` 里每一处 `pro.toggle()` 都必须紧跟下发。
     *
     * 这一条正是 P0-1 那个 bug 能在 31 条纯函数测试全绿的情况下活下来的原因：
     * `planCaptureOptions` 返回 `Reset` 有测试钉着，**调用点没被任何东西钉着**。
     * 覆盖下限按实际站点数（现在 2 处：顶栏 `CamProToggleCell` 与抽屉 `cam_pro_sheet`）钉，
     * 读法失效（正则不匹配了）会先红在"只找到 N 处"——那样至少有人在看这一条。
     */
    @Test
    fun everyProToggleAppliesItsPlan() {
        val f = File("src/main/java/com/suipai/app/ui/camera/CameraScreen.kt")
        assertTrue("找不到 CameraScreen：${f.absolutePath}", f.isFile)
        val lines = f.readLines()
        val hits = proTogglesWithoutApply(lines)
        assertTrue(
            "这些 pro.toggle() 后面没有下发计划：关了 PRO 但 session 上还留着 SENSOR_SENSITIVITY，" +
                "下一张仍按上次的 ISO 曝光（P0-1）：" + hits.joinToString(" | "),
            hits.isEmpty(),
        )
        // 读法失效防线：真源码里确实有 2 处 pro.toggle()，且它们都被判成"已下发"。
        // 数站点必须走 codeLines()——注释里也写着 `pro.toggle()`（解释这个 bug 的那段），
        // 用裸 `contains` 会把它数成第三个站点，然后这条守卫会红在一个假原因上。
        val sites = codeLines(lines).count { (_, t) -> t.contains("pro.toggle()") }
        assertEquals("真源码里的 pro.toggle() 站点数变了（读法大概失效，或有人加了新入口）：$sites", 2, sites)

        // 自检：只翻状态、不下发的那两种形状都必须红
        val dirtyTopBar = listOf(
            "                if (!simpleUi) CamProToggleCell(",
            "                    pro = pro.mode,",
            "                    availability = pro.avail,",
            "                    onToggle = { pro.toggle() },",
            "                )",
        )
        assertEquals(
            "顶栏那颗「只翻状态」的形状没被抓到 —— 这正是 P0-1 的第一处",
            listOf(4),
            proTogglesWithoutApply(dirtyTopBar),
        )
        val dirtySheet = listOf(
            "                    AppBarCell(",
            "                        label = if (pro.mode.enabled) \"PRO 开\" else \"PRO 关\",",
            "                        enabled = pro.avail.any,",
            "                        onClick = { pro.toggle() },",
            "                        modifier = Modifier.testTag(\"cam_pro_sheet\"),",
            "                    )",
        )
        assertEquals("抽屉那颗「只翻状态」的形状没被抓到", listOf(4), proTogglesWithoutApply(dirtySheet))

        // 正确的写法不许被误伤（共用 applyProNow 与直接调 CameraProBindings.apply 两种都算）
        val cleanHelper = listOf(
            "                    onToggle = {",
            "                        pro.toggle()",
            "                        applyProNow(\"专业参数没能下发，已回到自动\")",
            "                    },",
        )
        assertTrue("走 applyProNow 的写法被误判", proTogglesWithoutApply(cleanHelper).isEmpty())
        val cleanDirect = listOf(
            "                        onClick = { pro.toggle() },",
            "                        // 这里也可以直接下发",
            "                        val ok = CameraProBindings.apply(camera?.cameraControl, plan)",
        )
        assertTrue("直接调 CameraProBindings.apply 的写法被误判", proTogglesWithoutApply(cleanDirect).isEmpty())
        // 向下 8 行之外的下发不算（顺序错了：那会先下发旧计划再翻状态）
        val tooFar = listOf(
            "                        onClick = { pro.toggle() },",
            "                        a1()",
            "                        a2()",
            "                        a3()",
            "                        a4()",
            "                        a5()",
            "                        a6()",
            "                        a7()",
            "                        a8()",
            "                        a9()",
            "                        applyProNow()",
        )
        assertEquals("窗口给宽了：隔了 10 行的下发不该算数", listOf(1), proTogglesWithoutApply(tooFar))
        // 注释里的 pro.toggle() 不算站点（codeLines 会去掉整行注释）
        assertTrue(
            "注释里的 pro.toggle() 被误判成调用点",
            proTogglesWithoutApply(listOf("    // 以前这里是 pro.toggle()，没下发")).isEmpty(),
        )
    }

    @Test
    fun everyFilterFieldIsARecompositionKey() {
        val f = File("src/main/java/com/suipai/app/ui/gallery/GalleryScreen.kt")
        assertEquals(emptyList<String>(), unkeyedFilterFields(f.readLines()))
    }

    @Test
    fun noWriteOnlyPreferenceKeys() {
        val roots = listOf(
            File("src/main/java/com/suipai/app"),
            File("../shared/src"),
        )
        val sources = roots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.name to it.readText() } }
            .toList()
        // 路径写错会让这一条变成空跑：先确认确实扫到了东西。
        assertTrue("扫到的源文件太少（$sources），这一条无从判断", sources.size > 40)
        assertEquals("这些偏好键只写不读：", emptyList<String>(), deadPreferenceKeys(sources))
    }

    /**
     * 表驱动的循环格：档数由表长说了算（这些格子没有 `when (X) { 1 -> "…" }` 可以数）。
     * 与 `RecencyRuleTest` / `RatioFilterTest` / `HourBucketTest` / `OnlyKindTableTest` 里
     * 各自钉过的那几条是同一件事，这里的价值在于**新加的循环格一旦落到表外，就会没人管** ——
     * 传进来的映射让这一篇替全项目兜底。
     */
    private val tableCycles: Map<String, Int> = mapOf(
        // 「日期」的表**含** 0 档（"全部日期"），所以模数就是表长；
        // 另外三张表只列 1..N（0 档走格子里那句 `if (x == 0) "默认词"`），所以要 +1。
        "recency" to RecencyRules.size,
        "hourFilter" to HourBuckets.size + 1,
        "ratioFilter" to RatioFilters.size + 1,
        "filter" to OnlyKinds.size + 1,
    )

    @Test
    fun everyCycleCellHasAWellMatchedModulus() {
        var checked = 0
        val bad = ArrayList<String>()
        for (f in kotlinFiles()) {
            val (n, hits) = cycleCells(f.readLines(), tableCycles)
            checked += n
            bad += hits.map { "${f.name}: $it" }
        }
        assertTrue("这些循环格子的档数与模数对不上：" + bad.joinToString(" | "), bad.isEmpty())
        // 读法失效（正则不匹配了）会让这一条变绿但什么都没核对 —— 钉一个下限。
        assertTrue("只核对到 $checked 格循环控件，读法大概失效了（应有 10 格以上）", checked >= 10)
    }

    /**
     * 12 的真源码那一边：全项目每个 `combinedClickable` 都要点名它的动作。
     * 覆盖下限按实际站点数钉（现在 6 处），读法哪天不匹配了就先红在"只核对到 N 处"。
     */
    @Test
    fun formattersAreDeclaredOnce() {
        var declared = 0
        val bad = ArrayList<String>()
        for (root in listOf(File("src/main/java/com/suipai/app"), File("../shared/src"))) {
            assertTrue("\u627e\u4e0d\u5230\u6e90\u7801\u6839\uff1a$root", root.isDirectory)
            for (f in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                val hits = inlineFormatters(f.readLines())
                bad += hits.map { "${f.name}:$it" }
                declared += f.readLines().count { it.contains("ofPattern(") && it.contains("val ") }
            }
        }
        assertTrue("\u8fd9\u4e9b\u5730\u65b9\u6bcf\u6b21\u8c03\u7528\u90fd\u5728\u73b0\u573a\u9020\u683c\u5f0f\u5316\u5668\uff1a" + bad.joinToString(" | "), bad.isEmpty())
        // \u53cd\u65b9\u5411\uff1a\u628a\u58f0\u660e\u5168\u90e8\u5220\u6389\u4e5f\u4f1a\u201c\u6ca1\u6709\u8fdd\u89c4\u201d\uff0c\u6240\u4ee5\u8981\u6c42\u5171\u4eab\u58f0\u660e\u7684\u6570\u91cf\u4e5f\u5728\u76d1\u89c6\u8303\u56f4\u5185
        assertTrue("\u5168\u9879\u76ee\u53ea\u627e\u5230 $declared \u4e2a\u5171\u4eab formatter\uff0c\u8bfb\u6cd5\u5927\u6982\u5931\u6548", declared >= 5)
    }

    @Test
    fun everyPressActionIsNamed() {
        var seen = 0
        val bad = ArrayList<String>()
        for (f in kotlinFiles()) {
            val (n, hits) = unlabeledPressActions(f.readLines())
            seen += n
            bad += hits.map { "${f.name}:$it" }
        }
        assertTrue(
            "\u8fd9\u4e9b\"\u70b9\u51fb + \u957f\u6309\"\u7684\u683c\u5b50\u6ca1\u7ed9\u52a8\u4f5c\u8d77\u540d\u5b57\uff1a" + bad.joinToString(" | "),
            bad.isEmpty(),
        )
        assertTrue("\u53ea\u6838\u5bf9\u5230 $seen \u5904 combinedClickable\uff0c\u8bfb\u6cd5\u5927\u6982\u5931\u6548\u4e86", seen >= 6)
    }

    @Test
    fun clipboardHasExactlyOneWriter() {
        val sources = File("src/main/java/com/suipai/app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }.toList()
        // \u5148\u786e\u8ba4\u626b\u63cf\u771f\u7684\u8986\u76d6\u5230\u4e86\u6709\u526a\u8d34\u677f\u7684\u90a3\u51e0\u4e2a\u6587\u4ef6\u2014\u2014\u8def\u5f84\u5199\u9519\u65f6\u8fd9\u4e00\u6761\u7b49\u4e8e\u7a7a\u8dd1\u3002
        // \uff08\u8e29\u8fc7\u7684\uff1afloor \u5199\u6210 >40 \u800c app \u4e3b\u6e90\u7801\u53ea\u6709 39 \u4e2a\u6587\u4ef6\uff0c\u7ea2\u7684\u662f\u5224\u636e\u81ea\u5df1\u3002\uff09
        val names = sources.map { it.first }.toSet()
        assertTrue(
            "\u626b\u63cf\u96c6\u5408\u91cc\u7f3a\u4e86\u8fd9\u4e9b\u6587\u4ef6\uff0c\u8fd9\u4e00\u6761\u65e0\u4ece\u5224\u65ad\uff1a$names",
            names.containsAll(setOf("PhotoViewerScreen.kt", "YishunScreen.kt", "Clip.kt")),
        )
        assertTrue("\u626b\u5230\u7684\u6e90\u6587\u4ef6\u592a\u5c11\uff08${sources.size}\uff09", sources.size >= 30)
        val (stray, inside) = clipboardWriters(sources)
        assertTrue("\u8fd9\u4e9b\u526a\u8d34\u677f\u5199\u5165\u7ed5\u5f00\u4e86 clipWrite\uff1a" + stray.joinToString(" | "), stray.isEmpty())
        assertEquals("Clip.kt \u91cc\u5e94\u8be5\u6709\u4e14\u53ea\u6709\u4e00\u5904 setPrimaryClip", 1, inside)

        val dirty = listOf(
            "A.kt" to "    cr.getSystemService(ClipboardManager::class.java)\n        ?.setPrimaryClip(clip)",
            "Clip.kt" to "    cm.setPrimaryClip(data)",
        )
        val (d, n) = clipboardWriters(dirty)
        assertEquals("\u7ed5\u5f00\u51fa\u53e3\u7684\u5199\u5165\u6ca1\u88ab\u6293\u5230", listOf("A.kt:2"), d)
        assertEquals(1, n)
        val clean = listOf(
            "A.kt" to "    val wrote = clipWrite(context, \"\u968f\u62cd\", clip)",
            "Clip.kt" to "    cm.setPrimaryClip(data)",
        )
        assertTrue(
            "\u8d70\u51fa\u53e3\u7684\u5199\u6cd5\u88ab\u8bef\u5224\uff1a" + clipboardWriters(clean).first,
            clipboardWriters(clean).first.isEmpty(),
        )
    }

    @Test
    fun noTestAnnotationHiddenByAComment() {
        val bad = ArrayList<String>()
        var scanned = 0
        for (root in listOf(File("src/test/java"), File("src/androidTest/java"))) {
            assertTrue("\u627e\u4e0d\u5230\u6d4b\u8bd5\u6839\u76ee\u5f55\uff1a$root", root.isDirectory)
            for (f in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                scanned++
                bad += swallowedTestAnnotations(f.readLines()).map { "${f.name}:$it" }
            }
        }
        assertTrue(
            "\u8fd9\u4e9b\u6ce8\u89e3\u88ab\u540c\u4e00\u884c\u7684\u6587\u5b57\u541e\u6389\u4e86\uff08\u6d4b\u8bd5\u6839\u672c\u4e0d\u4f1a\u8dd1\uff09\uff1a" + bad.joinToString(" | "),
            bad.isEmpty(),
        )
        assertTrue("\u53ea\u626b\u4e86 $scanned \u4e2a\u6d4b\u8bd5\u6587\u4ef6\uff0c\u8bfb\u6cd5\u5927\u6982\u5931\u6548\u4e86", scanned >= 10)

        val dirty = listOf(
            "    // \u2500\u2500 \u81ea\u68c0 \u2500\u2500    @Test",
            "    fun checkersActuallyCatchBadCode() {",
        )
        assertEquals(listOf(1), swallowedTestAnnotations(dirty))
        // \u6ce8\u91ca\u91cc\u5f00\u53e3\u63d0\u5230 @Test \u662f\u5408\u6cd5\u7684\uff1a\u5b83\u4e0b\u9762\u8fd8\u6709\u4e00\u884c\u771f\u6ce8\u89e3
        val clean = listOf(
            "    // \u8fd9\u91cc\u8bf4\u4e00\u4e0b @Test \u7684\u6765\u5386",
            "    @Test",
            "    fun something() { }",
        )
        assertTrue(
            "\u6ce8\u91ca\u91cc\u63d0\u5230\u6ce8\u89e3\u7684\u6b63\u5e38\u5199\u6cd5\u88ab\u8bef\u5224\uff1a" + swallowedTestAnnotations(clean),
            swallowedTestAnnotations(clean).isEmpty(),
        )
    }

    @Test
    fun batchProgressCountsSuccessesNotAttempts() {
        var checked = 0
        val bad = ArrayList<String>()
        for (f in kotlinFiles()) {
            val (n, hits) = attemptCountProgress(f.readLines())
            checked += n
            bad += hits.map { "${f.name}:$it" }
        }
        assertTrue(
            "这几处批量进度是「试到第几张」而不是「成了第几张」：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )
        assertTrue("只核对到 $checked 个 forEachIndexed 批量循环，读法大概失效了", checked >= 3)
    }

    @Test
    fun everyBatchActionEndsWithSpokenConclusion() {
        // 迁移账（见 docs/arch/APP-ARCH.md §八）：五个 batch* 函数从 MainActivity.kt 搬到
        // state/BatchActions.kt（P0-B）。函数名、`batchNote = xxxNote(` 判据、
        // `consentNote(` 那一条全部原样；只是宿主换了文件。
        val main = File("src/main/java/com/suipai/app/state/BatchActions.kt")
        assertTrue("找不到批量动作宿主 BatchActions.kt：${main.absolutePath}", main.isFile)
        val fns = listOf("batchDelete", "batchCopy", "batchHide", "batchFavorite", "batchYishun")
        val missing = batchActionsWithoutNote(main.readLines(), fns)
        assertTrue(
            "这几路批量动作没有无条件写结论（全成功时屏幕上一个字都不出）：" + missing.joinToString(" | "),
            missing.isEmpty(),
        )
        // 授权框回来那句必须走 consentNote —— 它现在在 DeleteFlow.kt 里（同一个迁移）。
        val deleteFlow = File("src/main/java/com/suipai/app/state/DeleteFlow.kt")
        assertTrue("找不到删除授权链宿主 DeleteFlow.kt：${deleteFlow.absolutePath}", deleteFlow.isFile)
        assertTrue(
            "授权框回来的那句没走 consentNote（那句要同时报「删了几张 / 几张没允许 / 之前直删几张」）",
            Regex("batchNote\\s*=\\s*consentNote\\(").containsMatchIn(deleteFlow.readText()),
        )

        val dirty = listOf(
            "fun batchCopy(x: Int) {",
            "    if (failed > 0) {",
            "        batchNote = \"存了 1 张\"",
            "    }",
            "}",
        )
        assertEquals(
            "「只在出错时说话」这一形状没被判出来",
            listOf("batchCopy"),
            batchActionsWithoutNote(dirty, listOf("batchCopy")),
        )
        val clean = listOf(
            "fun batchCopy(x: Int) {",
            "    batchNote = copyNote(x, 0, x)",
            "}",
        )
        assertTrue(
            "无条件写结论的写法被误判：" + batchActionsWithoutNote(clean, listOf("batchCopy")),
            batchActionsWithoutNote(clean, listOf("batchCopy")).isEmpty(),
        )
        assertEquals(
            "函数名读不到时必须报告警，不能悄悄算通过",
            listOf("batchNope(读法失效：函数没找到)"),
            batchActionsWithoutNote(clean, listOf("batchNope")),
        )
    }

    @Test
    fun syncNetworkRethrowsCancellation() {
        val f = File("../shared/src/commonMain/kotlin/com/suipai/core/sync/SyncHub.kt")
        assertTrue("找不到 SyncHub（跨模块路径变了）：${f.absolutePath}", f.isFile)
        val fns = listOf(
            "fetchPeerPhotos", "fetchPeerThumb", "fetchPeerThumbs", "downloadPhoto", "sendPhoto",
        )
        val missing = missingCancellationRethrow(f.readLines(), fns)
        assertTrue(
            "这几个对端网络把取消当网络失败吞了：用户关了页面它还在跑，" +
                "而界面上的「正在读取…」不会停：" + missing.joinToString(" | "),
            missing.isEmpty(),
        )
        val dirty = listOf(
            "suspend fun fetchPeerPhotos() {",
            "    try { boom() } catch (e: Throwable) { setError(e); null }",
            "}",
        )
        assertEquals(
            "少了取消分支的形状必须被判出来",
            listOf("fetchPeerPhotos"),
            missingCancellationRethrow(dirty, listOf("fetchPeerPhotos")),
        )
        // 干净样本故意用**多行签名**：范围算错的话会在 `{` 之前就收工，
        // 于是"已经重抛取消"的函数被误判成缺 —— 这一条自己就踩过一次。
        val clean = listOf(
            "suspend fun fetchPeerPhotos(",
            "    peer: PeerInfo,",
            "    offset: Int = 0,",
            "): List<SyncPhotoMeta>? = withContext(Dispatchers.Default) {",
            "    try { boom() } catch (e: CancellationException) { throw e }",
            "    catch (e: Throwable) { setError(e); null }",
            "}",
        )
        assertTrue("已经重抛取消的被误判", missingCancellationRethrow(clean, listOf("fetchPeerPhotos")).isEmpty())
        assertEquals(
            "函数名读不到时必须报「读法失效」",
            listOf("nope(读法失效：suspend 函数没找到)"),
            missingCancellationRethrow(clean, listOf("nope")),
        )
    }

    @Test
    fun toolbarCellsSpeakOnTheClickableNode() {
        val file = kotlinFiles().firstOrNull { it.name == "AppBar.kt" }
        assertTrue("扫描集里没有 AppBar.kt，这一条无从判断", file != null)
        val fns = listOf("AppBarCell", "AppBarPrimary")
        val bad = toolbarCellsThatDoNotSpeak(file!!.readLines(), fns)
        assertTrue(
            "工具条的格子又回到「可点那颗没名字」的形状了 —— " +
                "读屏到那里只会念「按钮，双击激活」：" + bad.joinToString(" | "),
            bad.isEmpty(),
        )

        // 自检：三种装机量到的错法都必须变红，正确的形状不许误判。
        val materialButton = listOf(
            "fun AppBarPrimary(label: String, onClick: () -> Unit) {",
            "    Button(onClick = onClick) { Text(label) }",
            "}",
        )
        assertEquals(
            "用回 Material Button 必须判出来",
            listOf("AppBarPrimary：又用回 Material Button 了（可点那颗没有名字）"),
            toolbarCellsThatDoNotSpeak(materialButton, listOf("AppBarPrimary")),
        )
        val clearedAway = listOf(
            "fun AppBarPrimary(label: String, onClick: () -> Unit) {",
            "    Button(",
            "        modifier = Modifier.clearAndSetSemantics { contentDescription = label },",
            "    ) { Text(label) }",
            "}",
        )
        assertEquals(
            "Button + clearAndSetSemantics（会把动作清掉）也要判出来",
            listOf("AppBarPrimary：又用回 Material Button 了（可点那颗没有名字）"),
            toolbarCellsThatDoNotSpeak(clearedAway, listOf("AppBarPrimary")),
        )
        val silentClick = listOf(
            "fun AppBarCell(label: String, onClick: () -> Unit) {",
            "    Box(modifier = Modifier.clickable(onClick = onClick)) { Text(label) }",
            "}",
        )
        assertEquals(
            "自己画的格子但两颗属性都没带：role 与 contentDescription 各报一条",
            listOf(
                "AppBarCell：可点那颗没报 role=Button（读屏不说「按钮」）",
                "AppBarCell：可点那颗没带 contentDescription（读屏只说「按钮」）",
            ),
            toolbarCellsThatDoNotSpeak(silentClick, listOf("AppBarCell")),
        )
        val multiLineSplit = listOf(
            "fun AppBarCell(label: String, onClick: () -> Unit) {",
            "    Box(",
            "        modifier = Modifier",
            "            .clickable(role = Role.Button, onClick = onClick)",
            "            .clearAndSetSemantics {",
            "                contentDescription = label",
            "            },",
            "    ) { Text(label) }",
            "}",
        )
        assertTrue(
            "语义分三行写（真源码就是这样）不许被误判：" +
                toolbarCellsThatDoNotSpeak(multiLineSplit, listOf("AppBarCell")),
            toolbarCellsThatDoNotSpeak(multiLineSplit, listOf("AppBarCell")).isEmpty(),
        )
        assertEquals(
            "函数名读不到时必须报「读法失效」",
            listOf("nope(读法失效：函数没找到)"),
            toolbarCellsThatDoNotSpeak(multiLineSplit, listOf("nope")),
        )
    }

    @Test
    fun toolbarTextsMustUseAppBarText() {
        val files = kotlinFiles()
        val hits = files.flatMap { f -> rawTextsInToolbars(f.readLines()).map { "${f.name}:${it}" } }
        assertTrue(
            "工具条里又有裸 Text 了 —— FlowRow 会把它们顶到行的上沿（量到比同排格子高 22px）：" +
                hits.joinToString(" | "),
            hits.isEmpty(),
        )
        val dirty = listOf(
            "fun A() {",
            "    AppBarRow(Modifier.fillMaxWidth()) {",
            "        AppBarCell(label = \"按日\", on = false, onClick = {})",
            "        Text(\"照片\", style = t.pageHead, maxLines = 1)",
            "    }",
            "}",
        )
        assertEquals("裸 Text 必须报出行号", listOf(4), rawTextsInToolbars(dirty))
        val clean = dirty.map { it.replace("Text(\"照片\"", "AppBarText(\"照片\"") }
        assertTrue(
            "AppBarText 里含 \"Text(\" 子串，不许被误判：" + rawTextsInToolbars(clean),
            rawTextsInToolbars(clean).isEmpty(),
        )
        val outside = listOf(
            "fun B() {",
            "    Column {",
            "        Text(\"正文里的 Text 与工具条无关\")",
            "    }",
            "}",
        )
        assertTrue("不在 AppBarRow 里的 Text 不许管", rawTextsInToolbars(outside).isEmpty())
    }

    @Test
    fun toolbarRowMustWrapNotStarve() {
        val file = kotlinFiles().firstOrNull { it.name == "AppBar.kt" }
        assertTrue("扫描集里没有 AppBar.kt，这一条无从判断", file != null)
        val bad = toolbarRowThatStarvesCells(file!!.readLines())
        assertTrue(
            "工具条又回到「一行装不下就挤格子」的形状了（字级 2.0 量到「筛选」只剩 15px）：" +
                bad.joinToString(" | "),
            bad.isEmpty(),
        )
        val oldRow = listOf(
            "fun AppBarRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {",
            "    Row(",
            "        modifier = modifier.fillMaxWidth().height(AppBarHeight).padding(horizontal = 4.dp),",
            "        verticalAlignment = Alignment.CenterVertically,",
            "        content = content,",
            "    )",
            "}",
        )
        assertEquals(
            "改回单行固定高度的 Row：三条都要报",
            listOf(
                "AppBarRow：不是 FlowRow，字级一大就会把格子挤成缝",
                "AppBarRow：高度钉死在 AppBarHeight，换行会被裁掉",
                "AppBarRow：没有 heightIn(min = …)",
            ),
            toolbarRowThatStarvesCells(oldRow),
        )
        val scrolled = listOf(
            "fun AppBarRow(modifier: Modifier = Modifier) {",
            "    FlowRow(",
            "        modifier = modifier.heightIn(min = AppBarHeight).horizontalScroll(rememberScrollState()),",
            "    )",
            "    // 注释里的 horizontalScroll 不许被算进去",
            "}",
        )
        assertEquals(
            "FlowRow 但偷偷加横向滚动：只报滚动那一条",
            listOf("AppBarRow：又回到横向滚动（滚出去就不在语义树里）"),
            toolbarRowThatStarvesCells(scrolled),
        )
        assertEquals(
            "函数名读不到时必须报「读法失效」",
            listOf("AppBarRow(读法失效：函数没找到)"),
            toolbarRowThatStarvesCells(listOf("fun Other() {", "    Row {}", "}")),
        )
    }

    @Test
    fun singleLineFieldsMustNotWrapTheirPlaceholder() {
        val files = kotlinFiles()
        val names = files.map { it.name }.toSet()
        assertTrue("扫描集里缺了带搜索框的屏，这一条无从判断：" +
            listOf("GalleryScreen.kt", "AlbumListScreen.kt", "YishunScreen.kt").filter { it !in names },
            names.containsAll(setOf("GalleryScreen.kt", "AlbumListScreen.kt", "YishunScreen.kt")))
        val hits = files.flatMap { f -> wrappingPlaceholders(f.readLines()).map { "${f.name}:${it}" } }
        // 非空判：这一族确实有三处输入框，判据必须真的看得见它们（看不见 = 空转）。
        val seen = files.sumOf { f -> f.readLines().count { it.contains("placeholder = {") } }
        assertTrue("全仓一处 placeholder 都没有 —— 这一条在空转，判据要重想", seen >= 3)
        assertTrue(
            "这些单行输入框的提示语会自己折行、把输入框撑高（装机量到搜索框 528 px，网格被顶光）：" +
                hits.joinToString(" | "),
            hits.isEmpty(),
        )
        val dirty = listOf(
            "            OutlinedTextField(",
            "                value = q,",
            "                singleLine = true,",
            "                placeholder = { Text(\"文件名、相册、2024-09、今天\") },",
            "            )",
        )
        assertEquals("裸 placeholder 必须报出行号", listOf(4), wrappingPlaceholders(dirty))
        assertTrue(
            "加了 maxLines 就不许再报",
            wrappingPlaceholders(
                dirty.map { it.replace("Text(\"文件名、相册、2024-09、今天\")", "Text(\"…\", maxLines = 1)") },
            ).isEmpty(),
        )
        val multiLineField = listOf(
            "            OutlinedTextField(",
            "                value = q,",
            "                placeholder = { Text(\"多行输入框的提示语，可以折行\") },",
            "            )",
            "            // 附近没有 singleLine = true",
        )
        assertTrue(
            "多行输入框（附近没有 singleLine）不许管：" + wrappingPlaceholders(multiLineField),
            wrappingPlaceholders(multiLineField).isEmpty(),
        )
    }

    @Test
    fun silentCellReturnsMustBeGreyedOut() {
        val files = kotlinFiles()
        val names = files.map { it.name }.toSet()
        assertTrue("扫描集里没有 YishunScreen.kt，这一条无从判断", "YishunScreen.kt" in names)
        val hits = files.flatMap { f -> silentCellReturns(f.readLines()).map { "${f.name}:${it + 1}" } }
        // 非空判：真源码里确实有这种写法（都被 enabled 管住了）。找不到就说明判据空转。
        val sites = files.sumOf { f -> f.readLines().count { it.contains("?: return@AppBarCell") } }
        assertTrue("全仓一处 `?: return@AppBarCell` 都没有 —— 这一条在空转，判据要重想", sites >= 2)
        assertTrue(
            "这些格子点下去会一声不响（做不了的时候它自己就是灰的才对）：" + hits.joinToString(" | "),
            hits.isEmpty(),
        )

        val dirty = listOf(
            "                    AppBarCell(",
            "                        label = \"分享\",",
            "                        on = false,",
            "                        onClick = {",
            "                            val target = lastSaved ?: return@AppBarCell",
            "                            send(target)",
            "                        },",
            "                    )",
        )
        assertEquals("没有 enabled 的哑格子必须红", listOf(5), silentCellReturns(dirty))
        val clean = dirty.toMutableList().also { it.add(3, "                        enabled = lastSaved != null,") }
        assertTrue("按灰了就不许再报：" + silentCellReturns(clean), silentCellReturns(clean).isEmpty())
        val orphan = listOf(
            "    fun helper() {",
            "        val x = y ?: return@AppBarCell",
            "    }",
        )
        assertEquals("找不到所属格子时报「读不到」（行号照旧给出，逼人来改这条判据）",
            listOf(2), silentCellReturns(orphan))
    }

    @Test
    fun noStartActivitySwallowedQuietly() {
        val files = kotlinFiles()
        val names = files.map { it.name }.toSet()
        assertTrue(
            "扫描集里缺了这些有出口的屏幕，这一条无从判断：" +
                listOf("YishunScreen.kt", "PhotoViewerScreen.kt", "GalleryScreen.kt", "CameraScreen.kt")
                    .filter { it !in names },
            names.containsAll(
                setOf(
                    "YishunScreen.kt", "PhotoViewerScreen.kt",
                    "GalleryScreen.kt", "CameraScreen.kt", "Launch.kt",
                ),
            ),
        )
        val hits = files.flatMap { f -> swallowedLaunches(f.readLines()).map { "${f.name}:$it" } }
        assertTrue(
            "这些 startActivity 被 runCatching 吞了：没有可接手的应用时，" +
                "用户点了「分享 / 打开 / 地图」什么都不发生，界面也不说。走 Launch.kt 的 sendOrTell：" +
                hits.joinToString(" | "),
            hits.isEmpty(),
        )
        // 自检：脏形状必须红，两种"允许"的写法都不许误判
        val dirty = listOf(
            "    onClick = {",
            "        runCatching {",
            "            val send = Intent(Intent.ACTION_SEND)",
            "            context.startActivity(Intent.createChooser(send, \"分享\"))",
            "        }",
            "    },",
        )
        assertEquals("吞掉 startActivity 的写法没被判出来", listOf(4), swallowedLaunches(dirty))
        val viaHelper = listOf(
            "        val send = Intent(Intent.ACTION_SEND)",
            "        sendOrTell(context, Intent.createChooser(send, \"分享\"), \"分享\") { message = it }",
        )
        assertTrue("走出口助手的写法被误判", swallowedLaunches(viaHelper).isEmpty())
        val loud = listOf(
            "        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)",
            "        context.startActivity(intent)",
        )
        assertTrue("没被 runCatching 包住的（失败会响）不该被误判", swallowedLaunches(loud).isEmpty())
    }

    // ── 自检：每条判定都必须真的能变红 ──────────────────────────────────
    @Test
    fun checkersActuallyCatchBadCode() {
        val dirty = listOf(
            "fun A() {",
            "    Row(Modifier.horizontalScroll(rememberScrollState())) { }",
            "    val req = ImageRequest.Builder(context).data(u).build()",
            "    AsyncImage(model = req)",
            "}",
            "fun B() {",
            "    val chips = buildList { addAll(LookLibrary.all.filter { it.saturation > 0f }) }",
            "}",
            "data class PhotoItem(val id: Long)",
        )
        val dirtyKeys = listOf(
            "internal class GalleryFilters(",
            "    val recency: Int = 0,",
            "    val hourFilter: Int = 0,",
            ")",
            "    val visible = remember(photos, recency) {",
        )
        assertEquals(listOf("hourFilter"), unkeyedFilterFields(dirtyKeys))
        val cleanKeys = listOf(
            "internal class GalleryFilters(",
            "    val recency: Int = 0,",
            "    val hourFilter: Int = 0,",
            ")",
            "    val visible = remember(photos, recency, gflags.hourFilter) {",
        )
        assertTrue("补进键了的字段被误报", unkeyedFilterFields(cleanKeys).isEmpty())

        val prefsSources = listOf(
            "A.kt" to """
                prefs.edit().putFloat("yishun_int", intensity).apply()
                prefs.edit().putFloat("dead_key_${'$'}id", 1f).apply()
                val v = prefs.getFloat("alive_int_${'$'}other", 0f)
            """.trimIndent(),
        )
        assertEquals(
            "只写不读的键没被抓全",
            listOf("dead_key_${'$'}id（前缀 dead_key_ 没人读）", "yishun_int"),
            deadPreferenceKeys(prefsSources),
        )
        val prefsOk = listOf(
            "A.kt" to """
                prefs.edit().putFloat("yishun_int_${'$'}id", intensity).apply()
                val v = prefs.getFloat("yishun_int_${'$'}other", 0f)
            """.trimIndent(),
        )
        assertTrue("读写两侧变量名不同的键被误判了", deadPreferenceKeys(prefsOk).isEmpty())

        val cycleCell = listOf(
            "    AppBarCell(",
            "        label = when (gridMode) {",
            "            1 -> \"A\"",
            "            2 -> \"B\"",
            "            3 -> \"C\"",
            "            else -> \"关\"",
            "        },",
            "        onClick = { gridMode = (gridMode + 1) % 5 }",
            "    )",
        )
        assertEquals("模 5 对 3 档 + else 居然判过了",
            listOf("gridMode：模 5，应有 4 档"), cycleCells(cycleCell, emptyMap()).second)
        assertTrue(
            "配得上的循环格被误判：" + cycleCells(cycleCell.map { it.replace("% 5", "% 4") }, emptyMap()).second,
            cycleCells(cycleCell.map { it.replace("% 5", "% 4") }, emptyMap()).second.isEmpty(),
        )

        // 9: the progress line derived from the loop index = violation; from a success counter = legal.
        // Both sides must be exercised. The dollar sign is spliced in from `d` on purpose:
        // writing it as a Kotlin escaped template still opens a template in some compiler versions.
        val d = "$"
        val attemptProgress = listOf(
            "                    picked.forEachIndexed { i, photo ->",
            "                        when (repo.deleteOne(photo.uri)) {",
            "                            DeleteOutcome.DELETED -> deleted += photo.id",
            "                            else -> Unit",
            "                        }",
            "                        batchHint = \"已存 " + d + "{i + 1}/" + d + "{picked.size}\"",
            "                    }",
        )
        assertEquals("按尝试下标报进度的形状没被抓到", listOf(6), attemptCountProgress(attemptProgress).second)
        assertEquals(1, attemptCountProgress(attemptProgress).first)
        val honestProgress = attemptProgress.map {
            it.replace(d + "{i + 1}", d + "{deleted.size}")
        } + listOf(
            "                    picked.forEach { photo ->",
            "                        if (repo.copyToSuiPai(photo.uri) != null) done++ else failed++",
            "                        batchHint = null",
            "                        batchNote = \"存了 " + d + "{done} 张\"",
            "                    }",
        )
        assertTrue(
            "已经改成按成功计数报的形状被误判：" + attemptCountProgress(honestProgress).second,
            attemptCountProgress(honestProgress).second.isEmpty(),
        )

        assertEquals(listOf(2), horizontalScrollUses(dirty))
        assertEquals(listOf(3), unrememberedRequests(dirty))
        assertEquals(listOf("PhotoItem"), missingImmutable(dirty, setOf("PhotoItem")))
        assertEquals("composition 里裸跑一遍全库，必须被抓到", listOf(7), unrememberedLibraryPass(dirty))

        val clean = listOf(
            "fun A() {",
            "    // 这里原来是 horizontalScroll，已改掉（注释不该被算成违规）",
            "    val req = remember(u) { ImageRequest.Builder(context).data(u).build() }",
            "    AsyncImage(model = req)",
            "}",
            "fun B() {",
            "    val chips = remember(filters, query) {",
            "        buildList { addAll(LookLibrary.all.filter { it.saturation > 0f }) }",
            "    }",
            "}",
            "@Immutable",
            "data class PhotoItem(val id: Long)",
        )
        assertTrue("注释里的 horizontalScroll 被误判", horizontalScrollUses(clean).isEmpty())
        assertTrue("remember 包住的请求被误判", unrememberedRequests(clean).isEmpty())
        assertTrue("@Immutable 被漏看", missingImmutable(clean, setOf("PhotoItem")).isEmpty())
        assertTrue("remember 包住的全表遍历被误判", unrememberedLibraryPass(clean).isEmpty())
    }
}
