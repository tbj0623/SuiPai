package com.suipai.app.ui

import com.suipai.app.ui.camera.FocusResult
import com.suipai.app.ui.camera.focusRegion
import com.suipai.app.ui.camera.focusWords
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2：相机「这一次对焦到底发生了什么」，收起抽屉之后还要读得到。
 *
 * 装机量到的形状（ANDROID-VERIFY §32 附带观察）：抽屉里那颗「对焦中央」的动作**是有效的**
 * （点下去预览正中央挂上对焦框，那条边 0px→189px），但结果说不出来 ——
 * 非锁定的那一次只在状态行挂 2.8 秒，而抽屉是一颗独立弹窗，它开着的时候底下那层
 * **整个不在无障碍树里**（`uiautomator dump` 只给当前获焦窗口）。所以读屏用户的真实顺序是
 * 开抽屉 → 激活格子 → 收抽屉 → 摸状态行，走到那一步 2.8 秒早过了。
 *
 * 治法是「话写在动作那一刻，并且不随时间擦掉」：`focusAe` 当场把结果写进 `status`，
 * 收框那条 `LaunchedEffect` 从此一个字都不碰状态行。这句话是**实况**的过去式
 * （"已对焦 中央"），不像旧文案「对焦锁定」—— 那一支走 `setAutoCancelDuration(3, SECONDS)`，
 * 三秒后自己就取消了，话在挂出来的当下就开始假。
 *
 * 为什么不把答案写在抽屉里（那听起来更顺手）：抽屉一收，那颗格子和它说的话一起离开树，
 * 比 2.8 秒更读不到 —— 所以第 5 条守卫专门把它禁掉。
 * 为什么不用 `clearAndSetSemantics` / 给对焦框加 `contentDescription`：对焦框是个
 * 2.8 秒后就从树里消失的装饰 Box，话挂在它身上等于又变成一次性的；而 `clearAndSetSemantics`
 * 只重置它**内侧**的属性，帮不上"活着的时候要说"这件事。要"没人碰也会播"靠的是活区，
 * 状态行那颗 `Text(meta)` 早就标了（`A11ySourceGuardTest.selfChangingCameraTextsAreLiveRegions`
 * 钉着），于是这里只要保证**话一直在那颗节点上**就够了。
 *
 * 五条判据：
 * 1. `focusRegion` 的九宫格 —— 位置是唯一有信息量的部分，看得见的人看框，听的人只有这句话。
 * 2. `focusWords` 的真假 —— 没锁不许说"锁定"，锁了必须说；旧文案是这一条的坏样本。
 * 3. 收框的那一段不许写 `status` —— 就是"只活 2.8 秒"那个洞；顺带钉住这一段只有一个 `delay`（不新增定时器）。
 * 4. 对焦这句文案只许出自 `focusWords` —— 否则真值表管不到它。
 * 5. 抽屉那一段不许写 `status` —— 结果不许只说在收掉就没了的地方。
 * 6. `status` 必须是 `meta` 那串 `remember` 的键之一 —— 少一个键就等于这句话根本不会变。
 *
 * 每条都带"故意喂一个坏例子"的自检：没见过自己变红的守卫不算守卫。
 */
class CameraFocusFeedbackTest {

    companion object {

        /**
         * 从"含 marker 的那一行"起按花括号配平取到结尾，返回 (绝对行号, 去掉注释的代码)。
         *
         * 为什么不复用 `PerfSourceGuardTest.functionBody`：它找的是 `fun 名字(` 的**声明**，
         * 而这两段都是**调用点**的 lambda（`LaunchedEffect(focusAt, aeLocked) { … }` 与
         * `AppSheet(…) { … }`），没有函数名可寻。取不到返回 null，让调用方报"读法失效"。
         */
        fun blockFrom(lines: List<String>, marker: String): List<Pair<Int, String>>? {
            val start = lines.indexOfFirst { it.substringBefore("//").contains(marker) }
            if (start < 0) return null
            val out = ArrayList<Pair<Int, String>>()
            var depth = 0
            var seen = false
            var i = start
            while (i < lines.size) {
                val code = lines[i].substringBefore("//").trim()
                depth += code.count { it == '{' } - code.count { it == '}' }
                if (depth > 0) seen = true
                if (code.isNotEmpty()) out += (i + 1) to code
                i++
                if (seen && depth <= 0) break
            }
            return if (seen) out else null
        }

        /**
         * 3. 收对焦框那一段只许收框。
         *
         * 段内一个 `status =` 都不许有：写在那儿的话要么只活到 `delay` 之后（2.8 秒那个洞），
         * 要么被下一句盖掉。两种都读不到，所以整段禁写，而不是只禁"擦掉"那一支。
         */
        fun statusWrittenByTheBoxTimer(region: List<Pair<Int, String>>): List<Int> =
            region.filter { Regex("\\bstatus\\s*=[^=]").containsMatchIn(it.second) }.map { it.first }

        /**
         * 4. 对焦这句文案只许出自 `focusWords`（唯一一处能穷举测真假的表）。
         *
         * 两种绕法都判：那三个对焦函数的体内 `status =` 没走它（自己编了一句）；
         * 或者全文件任何地方 `status = "…对焦…"`（把话写在表外）。
         * 前半的证据区间是**函数体**而不是全文 —— `focusWords` 自己的 `return` 里就写着
         * "已对焦…"，把被检词的定义处算进证据会自己判红（本项目栽过两次）。
         * 后半扫全文只抓 `status = "…对焦…"` 这一种形状：表本身是 `return when -> "已对焦…"`，
         * 既没有 `status =` 也没有赋值的形状，所以不会被自己判红（改坏一处就会）。
         * 函数体内的行号从签名那一行起数。返回（核对到的 `status =` 句数, 违规）。
         */
        fun focusWordsBypassed(lines: List<String>, fns: List<String>): Pair<Int, List<String>> {
            val out = ArrayList<String>()
            var checked = 0
            for (fn in fns) {
                val raw = PerfSourceGuardTest.functionBody(lines, fn)
                if (raw == null) {
                    out += "$fn(读法失效：函数没找到)"
                    continue
                }
                for ((n, code) in PerfSourceGuardTest.codeLines(raw)) {
                    if (!Regex("\\bstatus\\s*=[^=]").containsMatchIn(code)) continue
                    checked++
                    if (!code.contains("focusWords(")) out += "$fn:$n 自己写了一句文案"
                }
            }
            for ((n, code) in PerfSourceGuardTest.codeLines(lines)) {
                if (Regex("""status\s*=[^=]*"[^"]*对焦""").containsMatchIn(code)) {
                    out += "第 $n 行把对焦的话写在了表外"
                }
            }
            return checked to out
        }

        /**
         * 5. 抽屉那一段不许写 `status`：抽屉收掉之后它整个不在树里，
         * 说在那里的结果等于没说。格子只许去调那个共享动作。
         */
        fun statusWrittenInsideTheSheet(sheet: List<Pair<Int, String>>): List<Int> =
            sheet.filter { Regex("\\bstatus\\s*=[^=]").containsMatchIn(it.second) }.map { it.first }

        /**
         * 6. `status` 必须是拼 `meta` 那串字的 `remember` 的键之一。
         * 少一个键，Compose 认为输入没变，屏幕上和语义里都还是原来那串 —— 与
         * `unkeyedFilterFields` 同一类错（编译过、跑得起、看得见，只有话不换）。
         */
        fun statusNotAKeyOfTheSpokenLine(lines: List<String>): List<String> {
            val at = lines.indexOfFirst { it.substringBefore("//").contains("val meta = remember(") }
            if (at < 0) return listOf("读法失效：找不到 `val meta = remember(` 那一行")
            val code = lines[at].substringBefore("//")
            return if ("status" in code) emptyList() else listOf("第 ${at + 1} 行 remember(...) 的键里没有 status")
        }

        /** 这句话是不是在声称"此刻锁着"。`已解除对焦锁定` 是否定式，不算声称。 */
        fun claimsLock(text: String): Boolean =
            text.contains("锁定") && !text.contains("解除")

        /**
         * 2 的判据：文案与实况对不对得上，返回 null = 说得对。
         * 旧文案「对焦锁定」就是不锁的时候说锁定 —— 它就是这一条的坏样本。
         */
        fun untruthfulFocusWords(text: String, locked: Boolean): String? = when {
            locked && !text.contains("锁定") -> "锁着却没说锁定：$text"
            !locked && claimsLock(text) -> "没锁却声称锁定：$text"
            else -> null
        }
    }

    private val cameraLines: List<String>
        get() {
            // AGP 跑 JVM 单测时工作目录就是模块根（app/）。路径不对直接红，不要跳过。
            val f = File("src/main/java/com/suipai/app/ui/camera/CameraScreen.kt")
            assertTrue("找不到 CameraScreen：${f.absolutePath}", f.isFile)
            return f.readLines()
        }

    // ── 1. 位置 ────────────────────────────────────────────────────────

    @Test
    fun focusRegionNamesTheNineCells() {
        // (w, h) = 900×600，每格 300×200
        assertEquals("左上", focusRegion(10f, 10f, 900, 600))
        assertEquals("上中", focusRegion(450f, 10f, 900, 600))
        assertEquals("右上", focusRegion(890f, 10f, 900, 600))
        assertEquals("左中", focusRegion(10f, 300f, 900, 600))
        assertEquals("中央", focusRegion(450f, 300f, 900, 600))
        assertEquals("右中", focusRegion(890f, 300f, 900, 600))
        assertEquals("左下", focusRegion(10f, 590f, 900, 600))
        assertEquals("下中", focusRegion(450f, 590f, 900, 600))
        assertEquals("右下", focusRegion(890f, 590f, 900, 600))
        // 抽屉里那颗「对焦中央」给的就是这条边界的正中央
        assertEquals("中央", focusRegion(450f, 300f, 900, 600))
        // 三分之一那一行本身算**下一**格：同一个点不许两格都能读到
        assertEquals("上中", focusRegion(450f, 199f, 900, 600))
        assertEquals("中央", focusRegion(450f, 200f, 900, 600))
        assertEquals("中央", focusRegion(599f, 399f, 900, 600))
        assertEquals("右中", focusRegion(601f, 399f, 900, 600))
        assertEquals("左下", focusRegion(0f, 600f, 900, 600))
        // 预览还没量出来：返回空串，由 focusWords 缩成不带位置的那句
        assertEquals("", focusRegion(1f, 1f, 0, 600))
        assertEquals("", focusRegion(1f, 1f, 900, 0))
        assertEquals("", focusRegion(1f, 1f, 900, -1))

        // 自检：九宫格读法一坏（比如把 row/col 写反），这三点立刻不一致
        val swapped = listOf(focusRegion(890f, 10f, 900, 600), focusRegion(10f, 590f, 900, 600))
        assertEquals("右上与左下必须是两个不同的格子", listOf("右上", "左下"), swapped)
    }

    // ── 2. 文案对不对得起实况 ──────────────────────────────────────────

    @Test
    fun focusWordsMatchWhatActuallyHappened() {
        assertEquals("已对焦 中央", focusWords(FocusResult.FOCUSED, "中央"))
        assertEquals("已对焦", focusWords(FocusResult.FOCUSED, ""))
        assertEquals("已锁定对焦 右下", focusWords(FocusResult.LOCKED, "右下"))
        assertTrue(focusWords(FocusResult.RELEASED, "").contains("解除"))
        assertTrue(focusWords(FocusResult.NOT_READY, "").contains("预览"))

        val cells = listOf("", "左上", "上中", "右上", "左中", "中央", "右中", "左下", "下中", "右下")
        for (result in FocusResult.values()) {
            for (region in cells) {
                val text = focusWords(result, region)
                assertTrue("($result,$region) 说的是空话", text.isNotBlank())
                assertNull("($result,$region) 不合实况：" + untruthfulFocusWords(text, result == FocusResult.LOCKED),
                    untruthfulFocusWords(text, result == FocusResult.LOCKED))
                // 注意 `text.contains("")` 恒为真，所以"带没带位置"要先排除空串
                val saysWhere = region.isNotEmpty() && text.contains(region)
                if (result == FocusResult.FOCUSED || result == FocusResult.LOCKED) {
                    // 位置只有"对上了 / 锁住了"这两种结局才谈得上，调用点给的正是那一点
                    assertTrue("丢了位置就是丢了这一次的信息：$text", saysWhere || region.isEmpty())
                } else {
                    // `解除` 是对整幅松手、`没做成` 压根没有那一点：位置塞进去就是假话
                    assertFalse("这两种结局不许带位置：" + text + " / " + region, saysWhere)
                }
            }
        }

        // 自检：坏样本必须让这条判据变红 —— 旧文案「对焦锁定」是不锁的时候说锁定
        assertEquals(
            "没锁却声称锁定：对焦锁定",
            untruthfulFocusWords("对焦锁定", locked = false),
        )
        // 反方向：真锁着、却只说"已对焦"，等于把人往"再点一下就松了"的误解里带
        assertEquals(
            "锁着却没说锁定：已对焦 中央",
            untruthfulFocusWords("已对焦 中央", locked = true),
        )
        // 否定式不许误判成声称（"已解除对焦锁定"说的是松开了）
        assertNull(untruthfulFocusWords("已解除对焦锁定", locked = false))
        assertNull(untruthfulFocusWords("已对焦 中央", locked = false))
    }

    // ── 3. 定时器不许擦话 ──────────────────────────────────────────────

    @Test
    fun theBoxTimerNeverTouchesTheSpokenLine() {
        val region = blockFrom(cameraLines, "LaunchedEffect(focusAt, aeLocked)")
        assertNotNull("读法失效：找不到 `LaunchedEffect(focusAt, aeLocked) { … }` 那一段", region)
        val code = region!!
        // 非空判：这一段确实还是"闪一下就收框"，读法哪天不匹配了这条不许绿着空跑
        assertTrue("读法失效：这一段里没有 delay( —— 收框的定时器不在了，判据要重想",
            code.any { it.second.contains("delay(") })
        assertTrue("读法失效：这一段里没有 focusAt = null（它剩下的正事就是收框）",
            code.any { it.second.contains("focusAt = null") })
        assertEquals(
            "这一段只许收对焦框，一个字都不许写状态行 —— 写在这里的话要么只活到 delay 之后" +
                "（读屏用户收完抽屉再摸过来就没了），要么被下一句盖掉：" + statusWrittenByTheBoxTimer(code),
            emptyList<Int>(),
            statusWrittenByTheBoxTimer(code),
        )
        // 不新增定时器：全段就原来那一个 delay
        assertEquals(1, code.count { it.second.contains("delay(") })

        // 自检：装机量到的那个旧写法必须红（三处写 status：锁定支、非锁定支、delay 之后擦回去）
        val oldShape = listOf(
            "        LaunchedEffect(focusAt, aeLocked) {",
            "            if (focusAt == null) return@LaunchedEffect",
            "            if (aeLocked) {",
            "                status = \"曝光锁定\"",
            "                return@LaunchedEffect",
            "            }",
            "            status = \"对焦锁定\"",
            "            delay(2800)",
            "            if (!aeLocked) {",
            "                focusAt = null",
            "                if (status == \"对焦锁定\") status = modeLabel",
            "            }",
            "        }",
        )
        val old = blockFrom(oldShape, "LaunchedEffect(focusAt, aeLocked)")
        assertNotNull("旧样本自己都没读出来，这一条判据无从判断", old)
        assertEquals("旧写法（挂一句、2.8 秒后擦回 modeLabel）必须红", listOf(4, 7, 11), statusWrittenByTheBoxTimer(old!!))
        val clean = listOf(
            "        LaunchedEffect(focusAt, aeLocked) {",
            "            if (focusAt == null) return@LaunchedEffect",
            "            if (aeLocked) return@LaunchedEffect",
            "            delay(2800)",
            "            if (!aeLocked) focusAt = null",
            "        }",
        )
        assertTrue(
            "只收框的写法不许误判：" + statusWrittenByTheBoxTimer(blockFrom(clean, "LaunchedEffect(focusAt, aeLocked)")!!),
            statusWrittenByTheBoxTimer(blockFrom(clean, "LaunchedEffect(focusAt, aeLocked)")!!).isEmpty(),
        )
    }

    // ── 4. 这句文案只许出自那张表 ──────────────────────────────────────

    @Test
    fun theFocusSentenceComesFromTheOneTruthTable() {
        val fns = listOf("focusAe", "releaseFocusAe", "centerOfPreview")
        val (checked, bypassed) = focusWordsBypassed(cameraLines, fns)
        assertTrue(
            "对焦这句文案只许出自 focusWords —— 写在别处的那一句没进上面的真值表，" +
                "「没锁不许说锁定」这类对错就没人管了：" + bypassed.joinToString(" | "),
            bypassed.isEmpty(),
        )
        // 非空判：那三个函数真的各写了一句 status（读法不匹配时这条会绿着空跑）
        assertTrue("只核对到 $checked 句对焦写的 status（应有 3 句以上），读法大概失效了", checked >= 3)

        val dirty = listOf(
            "fun focusAe(x: Float, y: Float, lock: Boolean) {",
            "    aeLocked = lock",
            "    status = \"对焦锁定\"",
            "}",
        )
        assertEquals(
            "自己编一句文案（旧形状）必须两条都报",
            1 to listOf("focusAe:3 自己写了一句文案", "第 3 行把对焦的话写在了表外"),
            focusWordsBypassed(dirty, listOf("focusAe")),
        )
        val viaTable = dirty.toMutableList().also {
            it[2] = "    status = focusWords(FocusResult.FOCUSED, \"中央\")"
        }
        assertEquals(
            "走了那张表就不许再报",
            1 to emptyList<String>(),
            focusWordsBypassed(viaTable, listOf("focusAe")),
        )
        assertEquals(
            "函数名读不到时必须报「读法失效」",
            0 to listOf("nope(读法失效：函数没找到)"),
            focusWordsBypassed(viaTable, listOf("nope")),
        )
    }

    // ── 5. 结果不许只说在收掉就没了的地方 ──────────────────────────────

    @Test
    fun theSheetNeverHoldsTheOnlyCopyOfTheResult() {
        val sheet = blockFrom(cameraLines, "AppSheet(title = \"拍摄设置\"")
        assertNotNull("读法失效：找不到拍摄设置抽屉那一段", sheet)
        assertEquals(
            "抽屉是独立弹窗：它开着的时候底下那层不在无障碍树里，收掉之后它自己也不在树里 —— " +
                "对焦的结果不许只写在这里说：" + statusWrittenInsideTheSheet(sheet!!),
            emptyList<Int>(),
            statusWrittenInsideTheSheet(sheet),
        )
        // 非空判：那两颗格子确实还在这段里（读法不匹配时这一段会是空的）
        assertTrue(
            "读法失效：抽屉那一段里找不到对焦的格子",
            sheet.any { it.second.contains("\"对焦中央\"") } && sheet.any { it.second.contains("focusAe(") },
        )

        val wrongFix = listOf(
            "            AppSheet(title = \"拍摄设置\", tag = \"cam_sheet\") {",
            "                AppBarCell(",
            "                    label = \"对焦中央\",",
            "                    onClick = { focusAe(1f, 1f, false); status = \"已对焦 中央\" },",
            "                )",
            "            }",
        )
        assertEquals(
            "「把答案写在抽屉里」这一听就顺的错误必须先红",
            listOf(4),
            statusWrittenInsideTheSheet(blockFrom(wrongFix, "AppSheet(title = \"拍摄设置\"")!!),
        )
        val rightShape = wrongFix.toMutableList().also {
            it[3] = "                    onClick = { centerOfPreview()?.let { p -> focusAe(p.x, p.y, false) } },"
        }
        assertTrue(
            "格子只调共享动作的写法不许误判：" +
                statusWrittenInsideTheSheet(blockFrom(rightShape, "AppSheet(title = \"拍摄设置\"")!!),
            statusWrittenInsideTheSheet(blockFrom(rightShape, "AppSheet(title = \"拍摄设置\"")!!).isEmpty(),
        )
    }

    // ── 6. 那句话真的挂在会变的字上 ────────────────────────────────────

    @Test
    fun theSpokenLineStillFollowsStatus() {
        val bad = statusNotAKeyOfTheSpokenLine(cameraLines)
        assertTrue(
            "`status` 不在拼这行字的 remember 键里 —— 于是对焦完之后屏幕和语义树都还是旧句子：" + bad,
            bad.isEmpty(),
        )
        // 标活区那一件由 A11ySourceGuardTest.selfChangingCameraTextsAreLiveRegions 钉，这里不复述。
        // 自检：把键拿掉就必须红
        val dirty = cameraLines.map { it.replace("val meta = remember(status,", "val meta = remember(") }
        assertEquals(
            "少一个键的写法必须报出来",
            1,
            statusNotAKeyOfTheSpokenLine(dirty).size,
        )
    }
}
