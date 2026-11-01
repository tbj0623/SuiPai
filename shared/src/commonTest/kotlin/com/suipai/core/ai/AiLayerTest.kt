package com.suipai.core.ai

import com.suipai.core.engine.LookLibrary
import com.suipai.core.engine.LookSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 智能推荐这一层的守卫：候选表、prompt、回复解析、同族检索。
 *
 * 这里的 fixture 全部是 2026-09-21 在 glm-4v-flash 上跑真照片**实际收到过的回复**，
 * 不是照着 schema 编的理想样本——因为翻车的正是模型自己发明的写法
 * （把整行 `基础|日光 daylight` 当成 lookId 抄回来）。
 *
 * 每条判定都配反例自检；没见过自己变红的守卫不算守卫。
 */
class AiLayerTest {

    private val all = LookLibrary.all

    /** 与 `AiRecipeTable.BASE_IDS` 同序；那边改名不改这里，`everyBaseIdSurvivesIntoTheTable` 会红。 */
    private val BASE22 = listOf(
        "daylight", "portrait", "indoor", "film", "celadon", "neon", "moon", "inkgold",
        "dawn", "night", "dusk", "wood", "blossom", "autumn", "snow", "mist", "bronze",
        "hazeblue", "cyan", "clear", "silver", "rain",
    )

    private fun baseOnly() = all.filter { it.id in BASE22.toSet() }

    private fun look(id: String, name: String, saturation: Float) =
        com.suipai.core.engine.Look(id = id, name = name, summary = name, saturation = saturation)

    // ── 候选表 ──────────────────────────────────────────────────────

    @Test
    fun candidateTableAlwaysCarriesTheHeuristicLooks() {
        val table = AiRecipeTable.build(all)
        assertTrue(table.size in 40..120, "候选表大小失控：${table.size}")
        assertEquals(table.size, table.map { it.id }.distinct().size, "同一 id 出现两次，模型会摇摆")
        // 本地启发式能给的 22 条必须在表里，否则 AI 上限低于它要替代的东西
        val ids = table.map { it.id }.toSet()
        for (base in listOf("daylight", "portrait", "indoor", "film", "celadon", "silver", "night", "dusk")) {
            assertTrue(base in ids, "候选表里少了 $base")
        }
        // 自检：故意查一条不在表里的，证明上面那条断言不是恒真
        assertTrue("不存在的配方" !in ids)
    }

    @Test
    fun everyTableLineResolvesBackToARealLook() {
        val table = AiRecipeTable.build(all)
        for (look in table) {
            val line = AiRecipeTable.line(look)
            val idOnLine = line.substringBefore(' ')
            assertEquals(look.id, idOnLine, "行首不是 id，模型就会把整行抄回来：$line")
            assertTrue(LookLibrary.byId(idOnLine) != null, "表里写了库中不存在的 id：$line")
            // 数值不能带 float 的尾巴：0.14000000001 这种会把 token 白烧一遍
            assertTrue("0.14000" !in line, line)
        }
    }

    @Test
    fun spreadTakesBothEndsOfTheSaturationRange() {
        // 老写法 `filterIndexed { i -> i % step == 0 }` 在 perBucket=2 时只取下标 0 和 n/2，
        // 也就是「最灰 + 中位数」，一族里最饱和的那条永远够不到。合成池子先把这件事钉死：
        val pool = (0 until 9).map { i ->
            look("sat$i", "红色$i", saturation = -0.5f + i * 0.13f)
        }
        val two = AiRecipeTable.build(baseOnly() + pool, perBucket = 2).map { it.id }
        assertTrue("sat0" in two, "最淡的一条没进表：$two")
        assertTrue("sat8" in two, "最浓的一条被漏掉（老写法就在这儿翻车）：$two")

        // 真库上只说得住「表顶 = 库顶」这一句。别写"候选表太灰"那种臆断：
        // 实测整库 5446 条的饱和上限就是 +0.14（p95 +0.08，sat>0.2 的有 0 条），
        // 这是一套胶片模拟库的本性，不是抽样抽歪了。
        val auto = AiRecipeTable.build(all).filter { it.id !in BASE22 }
        val libMax = all.filter { it.id !in BASE22.toSet() }.maxOf { it.saturation }
        val tableMax = auto.maxOf { it.saturation }
        assertTrue(
            kotlin.math.abs(tableMax - libMax) < 0.001f,
            "全库最饱和的非基础配方（$libMax）没能进候选表，表里最高只有 $tableMax",
        )
    }

    @Test
    fun everyBaseIdSurvivesIntoTheTable() {
        // build() 用 mapNotNull 找基础配方：改名的话它是**静默**少一条，
        // AI 的上限就会比本地智能适配还窄，而没人会注意到。
        val ids = AiRecipeTable.build(all).map { it.id }.toSet()
        val missing = BASE22.filter { it !in ids }
        assertTrue(missing.isEmpty(), "22 条基础配方里这些没进候选表（改过 id？）：$missing")
    }

    @Test
    fun endpointQualityClaimSaysWhatWasCounted() {
        // 界面上那一句话是对用户的承诺：数的是哪两类问题、量了几张、有没有量过，都要从这里出。
        val z = AiEndpoint.byId("zhipu-vision")
        val ag = AiEndpoint.byId("agnes-vision")
        val note = z.pickConflictsNote
        assertNotNull(note, "默认档位必须带实测数")
        assertTrue(AiEndpoint.byId("agnes-pro").pickConflictsNote == null, "没量过的档位不许编一个数出来")
        assertTrue("20 张" in note!!, "句子要带上分母")
        assertTrue("安全牌" in note, "句子要说出『安全牌』这一类，它不越任何阈值但用户看得到")
        assertTrue("7~8" in note, "同一配置重跑会差一张，所以对外只能给区间")
        assertTrue("3~4" in ag.pickConflictsNote!!)
        // 自检：数字换了，句子必须跟着换（证明上面两条不是抄来的死话）
        val moved = z.copy(pickConflictsOf20 = 12..13)
        assertTrue("12~13 张" in (moved.pickConflictsNote ?: ""))
        val single = z.copy(pickConflictsOf20 = 9..9)
        assertTrue("9 张" in (single.pickConflictsNote ?: "") && "9~9" !in single.pickConflictsNote!!)
    }

    @Test
    fun candidateIdsAreSafeForASmallModelToCopy() {
        // prompt 明确要求「只写 id 那一段英文小写」。id 里只要混进大写/中文/空格，
        // 小模型就会照抄错，解析端只认表里的 id —— 一次调用白费。
        for (look in AiRecipeTable.build(all)) {
            assertTrue(
                Regex("^[a-z][a-z0-9_]*$").matches(look.id),
                "id 不适合让模型抄：「${look.id}」（${look.name}）",
            )
        }
    }

    @Test
    fun promptCarriesEveryCandidateId() {
        val table = AiRecipeTable.build(all)
        val prompt = AiPrompt.system(table)
        for (look in table) {
            assertTrue(look.id in prompt, "prompt 里没有 ${look.id}")
        }
        for (key in listOf("lookId", "seen", "why", "query", "temp", "contrast", "sat", "shadow", "fade", "highlight", "soft")) {
            assertTrue(key in prompt, "prompt 漏了字段 $key")
        }
        // 自检：故意找一个不该出现的 id
        assertTrue("definitely_not_a_look" !in prompt)
    }

    // ── 回复解析 ────────────────────────────────────────────────────

    /** 2026-09-21 真机实测回复（夜色 / 舞台暗场）。 */
    private fun realReply(lookId: String) =
        """{"seen":"舞台灯光昏暗","lookId":"$lookId","why":"适合夜晚场景","query":"暖光 低饱和",""" +
            """"temp":0,"contrast":0,"sat":0,"shadow":0,"fade":0,"highlight":0,"soft":0}"""

    @Test
    fun parsesARealReplyIntoARecipeAndAlternates() {
        val table = AiRecipeTable.build(all)
        val parsed = AiSuggestionParser.parse(realReply("night"), table, all)
        val ok = (parsed as? AiParse.Ok)?.suggestion
        assertTrue(ok != null, "真机回复没解析出来：$parsed")
        assertEquals("night", ok!!.look.id)
        assertEquals("舞台灯光昏暗", ok.seen)
        assertTrue(ok.shifts.isNeutral, "全 0 的偏移不该被读成非 0")
        assertTrue(ok.alternates.isNotEmpty(), "备选为空，用户没有第二条可点")
        assertTrue(ok.alternates.none { it.id == "night" }, "备选里不能又是自己")
    }

    /** 围栏与前后废话都要能吃掉——模型很少老实回裸对象。 */
    @Test
    fun toleratesFencesAndProse() {
        val table = AiRecipeTable.build(all)
        val wrapped = "```json\n" + realReply("celadon") + "\n```"
        assertTrue(AiSuggestionParser.parse(wrapped, table, all) is AiParse.Ok)
        val prose = "好的，我看了照片。" + realReply("celadon") + " 希望有帮助"
        assertTrue(AiSuggestionParser.parse(prose, table, all) is AiParse.Ok)
    }

    /**
     * v4 实测翻车写法：模型把整行抄成 lookId（`基础|日光 daylight`）。
     * 这里的不变量不是「拒绝这个 id」，而是**永远不可能落成一个不存在的配方**——
     * 能按描述兜底就兜底并标出来，兜不住就整条失败，两种都不许把幽灵 id 交给界面。
     */
    @Test
    fun aCopiedWholeLineNeverBecomesAPhantomRecipe() {
        val table = AiRecipeTable.build(all)
        val parsed = AiSuggestionParser.parse(realReply("基础|日光 daylight"), table, all)
        val ids = all.map { it.id }.toSet()
        when (parsed) {
            is AiParse.Ok -> {
                assertTrue(parsed.suggestion.resolvedByFallback, "抄来的整行被当成了表内命中")
                assertTrue(parsed.suggestion.look.id in ids, "兜底落到了库外：${parsed.suggestion.look.id}")
            }

            is AiParse.UnknownLook -> assertEquals("基础|日光 daylight", parsed.lookId)
            is AiParse.NotJson -> error("这条回复是合法 JSON，不该判成 NotJson")
        }
        // 自检：同一个 fixture 换回真 id 就必须是表内命中且不带兜底标记
        val good = AiSuggestionParser.parse(realReply("daylight"), table, all) as AiParse.Ok
        assertEquals("daylight", good.suggestion.look.id)
        assertTrue(!good.suggestion.resolvedByFallback)
    }

    /**
     * 2026-09-21 实测：两家模型在**同一张**照片上各自编过一个 id——智谱给 `tanhong`、Agnes 给 `rustic`，
     * 都是「土红」的拼音/英译。它们同时给的 query 却是准的（「土红 低饱和」）。
     * 所以落不到候选表时先按它自己的描述去整库里找，比直接告诉用户「模型给的配方不在库里」有用。
     */
    @Test
    fun inventedIdFallsBackToTheModelsOwnDescription() {
        val table = AiRecipeTable.build(all)
        val reply = """{"lookId":"tanhong","query":"土红 低饱和","seen":"铁丝网"}"""
        val parsed = AiSuggestionParser.parse(reply, table, all)
        assertTrue(parsed is AiParse.Ok, "编造 id 本该走兜底，结果直接失败：$parsed")
        val s = (parsed as AiParse.Ok).suggestion
        assertTrue(s.resolvedByFallback, "走了兜底却没标记，界面无法告诉用户这是近似结果")
        assertEquals("土红", s.look.name)
        // 自检：query 也落不了地时仍然要失败，而不是从库里随便抓一条糊弄
        val junk = AiSuggestionParser.parse("""{"lookId":"zzz","query":"量子纠缠 超弦"}""", table, all)
        assertTrue(junk is AiParse.UnknownLook, "无对应词的编造 id 居然也落到了配方：$junk")
    }

    @Test
    fun notJsonIsItsOwnFailure() {
        val table = AiRecipeTable.build(all)
        assertTrue(AiSuggestionParser.parse("我觉得这张照片很适合暖色调", table, all) is AiParse.NotJson)
        // 半截 JSON（被 max_tokens 截断）也归到这里，而不是抛出去
        assertTrue(AiSuggestionParser.parse("""{"lookId":"night","why":""", table, all) is AiParse.NotJson)
    }

    /** 实测过 sat=-1：越界到那个程度说明模型在重画而不是微调，整组丢掉。 */
    @Test
    fun dropsWildOffsetsButKeepsTheLook() {
        val table = AiRecipeTable.build(all)
        val reply = """{"lookId":"silver","sat":-1,"contrast":0.28,"temp":0,"shadow":0,"fade":0,"highlight":0,"soft":0}"""
        val ok = AiSuggestionParser.parse(reply, table, all) as AiParse.Ok
        assertEquals("silver", ok.suggestion.look.id)
        assertTrue(ok.suggestion.shifts.isNeutral, "越界偏移被夹回后仍然应用了：${ok.suggestion.shifts}")
        assertTrue(ok.suggestion.shiftsDropped != null, "丢掉了偏移却没告诉用户")
    }

    /** 夹取本身：0.28 要落到可信区间的边界，而不是变成 0。 */
    @Test
    fun clampsOffsetsIntoTheSliderRange() {
        val table = AiRecipeTable.build(all)
        val reply = """{"lookId":"dusk","temp":0.21,"contrast":0.19,"sat":0.29,"shadow":0,"fade":0,"highlight":0,"soft":0}"""
        val ok = AiSuggestionParser.parse(reply, table, all) as AiParse.Ok
        val s = ok.suggestion.shifts
        assertEquals(0.21f, s.temp)
        assertEquals(0.19f, s.contrast)
        assertEquals(0.29f, s.sat)
        assertTrue(s.isNeutral.not(), "三项非 0 被整组清空了")
        assertTrue(ok.suggestion.shiftsDropped == null)
        assertTrue(s.temp in AiShiftLimits.TEMP && s.sat in AiShiftLimits.SAT)
        // 越界但不算乱来的：夹到边界，不丢
        val edge = AiSuggestionParser.parse(
            """{"lookId":"dusk","temp":0.24,"contrast":0.19,"sat":0.34,"shadow":0,"fade":0,"highlight":0,"soft":0}""",
            table, all,
        ) as AiParse.Ok
        assertEquals(0.24f, edge.suggestion.shifts.temp)
        assertEquals(0.34f, edge.suggestion.shifts.sat)
    }

    /** prompt 明确要求「最多改 3 个」；一次改 5 个是在重画，整组丢掉比留一半诚实。 */
    @Test
    fun tooManyOffsetsAtOnceAreDroppedWholesale() {
        val table = AiRecipeTable.build(all)
        val reply = """{"lookId":"dusk","temp":0.21,"contrast":0.19,"sat":0.29,"shadow":0.15,"fade":0,"highlight":0,"soft":0.11}"""
        val ok = AiSuggestionParser.parse(reply, table, all) as AiParse.Ok
        assertTrue(ok.suggestion.shifts.isNeutral, "改了 5 项还照单全收：${ok.suggestion.shifts}")
        assertTrue(ok.suggestion.shiftsDropped != null, "丢掉了偏移却没告诉用户")
        assertEquals("dusk", ok.suggestion.look.id, "丢偏移不该把配方也丢了")
    }

    /** 数字以字符串回来的写法（实测见过）。 */
    @Test
    fun readsNumbersSentAsStrings() {
        val table = AiRecipeTable.build(all)
        val reply = """{"lookId":"dusk","temp":"0.1","contrast":"0.02","sat":"0","shadow":0,"fade":0,"highlight":0,"soft":0}"""
        val ok = AiSuggestionParser.parse(reply, table, all) as AiParse.Ok
        assertEquals(0.1f, ok.suggestion.shifts.temp)
        assertEquals(0.02f, ok.suggestion.shifts.contrast)
    }

    // ── 本机色偏（喂给模型的那组数）─────────────────────────────────

    @Test
    fun warmBlueAndGreyReadAsTheyActuallyAre() {
        val red = AiPhotoFacts.from(IntArray(16) { 0xFFFF0000.toInt() })
        assertTrue(red.warmth > 0.9f, "纯红读出来 warmth=${red.warmth}")
        assertEquals("偏暖", red.line().let { if ("偏暖" in it) "偏暖" else "错" })

        val blue = AiPhotoFacts.from(IntArray(16) { 0xFF0000FF.toInt() })
        assertTrue(blue.warmth < -0.9f, "纯蓝读出来 warmth=${blue.warmth}")
        assertTrue("偏冷" in blue.line(), blue.line())

        val grey = AiPhotoFacts.from(IntArray(16) { 0xFF808080.toInt() })
        assertEquals(0f, grey.warmth)
        assertEquals(0f, grey.saturation)
        assertTrue("接近中性" in grey.line(), grey.line())
        assertTrue("清淡" in grey.line(), grey.line())

        // 自检：全 0 像素（黑）不能把饱和度算成 NaN 或者崩掉
        val black = AiPhotoFacts.from(IntArray(4) { 0xFF000000.toInt() })
        assertEquals(0f, black.saturation)
        assertTrue(AiPhotoFacts.from(IntArray(0)).line().isNotEmpty())
    }

    /**
     * 数字格式必须和宿主机 harness 完全同形：那批 A/B 结论（10→6、6→3）是拿这个字符串跑出来的，
     * 设备上换成「+0.1」或者「0.10000001」就等于换了 prompt，结论不再适用。
     */
    @Test
    fun factsLineIsFormattedExactlyLikeTheHarness() {
        val line = AiPhotoFacts(warmth = 0.14f, brightness = 0.44f, saturation = 0.34f).line()
        assertTrue(line.startsWith("本机已量过这张照片的像素：色偏 +0.14（正为暖、负为冷）、明度 0.44、平均饱和 0.34，"), line)
        assertTrue("即 偏暖、中间调、浓彩。" in line, line)
        assertTrue(line.endsWith("冷暖与明暗以这组数为准，不要自己估。"), line)
        // 负数与 0 的写法
        assertTrue("色偏 -0.05" in AiPhotoFacts(-0.05f, 0.7f, 0.2f).line())
        assertTrue("色偏 +0.00" in AiPhotoFacts(0f, 0.7f, 0.2f).line())
    }

    @Test
    fun promptUserTextCarriesTheFactsWhenGiven() {
        val facts = AiPhotoFacts(0.2f, 0.5f, 0.4f)
        assertTrue(AiPrompt.userText(facts).startsWith("本机已量过"))
        assertTrue(AiPrompt.userText(facts).endsWith(AiPrompt.USER_TEXT))
        assertEquals(AiPrompt.USER_TEXT, AiPrompt.userText(null))
    }

    // ── 同族检索 ────────────────────────────────────────────────────

    /** 锚定检索的意义：模型只会说「冷绿 低饱和」，把它落到整库里对的几条上。 */
    @Test
    fun alternatesStayInTheSameHueFamily() {
        val celadon = LookLibrary.byId("celadon")!!
        val hits = LookSearch.around(celadon, "冷绿 低饱和", all, 3)
        assertEquals(3, hits.size, "备选不足 3 条：${hits.map { it.name }}")
        assertTrue(hits.none { it.id == celadon.id })
        // 青瓷是灰绿低饱和，备选必须也在低饱和一侧，不能捞出个浓彩
        assertTrue(hits.all { it.saturation <= 0.12f }, hits.map { "${it.name}=${it.saturation}" }.toString())
        // 自检：给一个库里没有的乱词，也不该把 anchor 自己塞回来
        assertTrue(LookSearch.around(celadon, "", emptyList(), 3).isEmpty(), "空库还能出结果，说明过滤没生效")
    }

    @Test
    fun termsSplitEverySeparatorTheModelActuallyUses() {
        // 实测：query 里的分隔符见过 空格、、，, 三种
        val terms = LookSearch.terms("冷、青 低饱和,暖")
        assertEquals(listOf("冷", "青", "低饱和", "暖"), terms)
        assertTrue(LookSearch.terms("   ").isEmpty())
    }

    /**
     * 形状检查：只挡明显不可能是 Key 的输入，不猜各家格式。
     * 起因是设备上真发生过一次「自动化漏了字符 → 应用收下 → 之后一直 401」。
     */
    @Test
    fun keyShapeRejectsWhatCannotBeAKey() {
        val good = "0123456789abcdef0123456789abcdef.ABCDEF0123456789"
        assertEquals(49, good.length)
        assertNull(AiKeyShape.problem(good))
        // 粘贴时带的前后空白是常态，trim 之后要放行
        assertNull(AiKeyShape.problem("  $good\n"))
        assertNotNull(AiKeyShape.problem(""))
        assertNotNull(AiKeyShape.problem("     "))
        assertNotNull(AiKeyShape.problem("sk-abc"))
        assertNotNull(AiKeyShape.problem("0123456789abcdef 0123456789abcdef"))
        assertNotNull(AiKeyShape.problem("０１２３４５６７８９ａｂｃｄｅｆｇｈｉｊｋ"))
        // 自检：正常 Key 的每个字符都过得了逐字符判断，说明上面的拒绝不是误伤
        assertTrue(good.all { it.code in 0x21..0x7E && !it.isWhitespace() })
    }
}
