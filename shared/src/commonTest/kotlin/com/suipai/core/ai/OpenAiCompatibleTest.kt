package com.suipai.core.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协议层的不变量，全部在 JVM 上跑，不需要设备也不需要真 key。
 *
 * 请求体的形状和各家返回的怪形状一旦钉住，接真机时就只剩「网络通不通」一个问题。
 * 每条判定都带反例自检——没见过自己变红的守卫不算守卫。
 *
 * 测试里不写真正的思考闭合标签：那个字面串会吃掉本文件的块注释，
 * 所以用 THINK_OPEN / THINK_CLOSE 拼出来。
 */
class OpenAiCompatibleTest {

    private val vision = AiEndpoint.byId("zhipu-vision")
    private val textOnly = AiEndpoint.byId("zhipu-text")

    // ── 请求体 ──────────────────────────────────────────────────────

    @Test
    fun textOnlyBodyUsesPlainStringContent() {
        val body = OpenAiCompatible.chatBody(textOnly, "系统", "问题")!!
        assertTrue("\"content\":\"系统\"" in body, body)
        assertTrue("\"content\":\"问题\"" in body, body)
        assertTrue("image_url" !in body, body)
        assertTrue("\"model\":\"glm-4-flash\"" in body, body)
    }

    @Test
    fun visionBodySendsPartsArrayWithTheDataUrl() {
        val body = OpenAiCompatible.chatBody(vision, "系统", "问题", "data:image/jpeg;base64,AAA")!!
        assertTrue("\"type\":\"text\"" in body, body)
        assertTrue("\"type\":\"image_url\"" in body, body)
        assertTrue("\"url\":\"data:image/jpeg;base64,AAA\"" in body, body)
        assertTrue("\"model\":\"glm-4v-flash\"" in body, body)
    }

    /** 关键不变量：往不支持视觉的档位塞图必须整条拒发，而不是静默把图丢掉。 */
    @Test
    fun refusesToSilentlyDropTheImageOnATextEndpoint() {
        assertNull(OpenAiCompatible.chatBody(textOnly, "s", "u", "data:image/jpeg;base64,AAA"))
        // 自检：反过来，视觉档位确实带上了图，否则上面那条断言可能只是两边都空
        assertTrue("image_url" in OpenAiCompatible.chatBody(vision, "s", "u", "data:image/jpeg;base64,AAA")!!)
    }

    // ── 状态码归一 ──────────────────────────────────────────────────

    @Test
    fun mapsStatusCodesToActionableFailures() {
        fun kind(status: Int, body: String = "{}") =
            (OpenAiCompatible.parse(status, body) as AiReply.Failed).kind

        assertEquals(AiFailure.AUTH, kind(401))
        assertEquals(AiFailure.AUTH, kind(403))
        assertEquals(AiFailure.RATE_LIMIT, kind(429))
        assertEquals(AiFailure.REJECTED, kind(400))
        assertEquals(AiFailure.SERVER, kind(503))
        assertEquals(AiFailure.BAD_JSON, kind(200, "not json at all"))
        // 自检：200 且形状对时不能被误判成失败
        assertTrue(OpenAiCompatible.parse(200, good("""{"id":"x"}""")) is AiReply.Ok)
    }

    /** 智谱把 400 的原因写在 error.code / error.message 里，界面要能原样显示。 */
    @Test
    fun surfacesProviderErrorText() {
        val body = """{"error":{"code":"1210","message":"messages.content.type 参数非法"}}"""
        val f = OpenAiCompatible.parse(400, body) as AiReply.Failed
        assertEquals(AiFailure.REJECTED, f.kind)
        assertTrue("1210" in f.detail, f.detail)
    }

    // ── 回复解析 ────────────────────────────────────────────────────

    @Test
    fun readsContentAndUsage() {
        val ok = OpenAiCompatible.parse(200, good("""{"id":"x"}""")) as AiReply.Ok
        assertEquals("""{"id":"x"}""", ok.text)
        assertEquals(64, ok.promptTokens)
        assertEquals(19, ok.completionTokens)
    }

    /** 思考档位会把思考混在正文里，不剥掉后面根本取不到 JSON。 */
    @Test
    fun stripsThinkingBlocksFromContent() {
        val raw = THINK_OPEN + "用户想要颜色" + THINK_CLOSE + "红"
        val ok = OpenAiCompatible.parse(200, good(raw)) as AiReply.Ok
        assertEquals("红", ok.text)
    }

    /**
     * 被 max_tokens 截断、只剩半截思考时，宁可判 EMPTY 也不能把思考当答案交出去——
     * 那等于把一段没结论的碎碎念当成用户照片的调色建议。
     */
    @Test
    fun unclosedThinkingIsNotPassedOffAsAnAnswer() {
        val f = OpenAiCompatible.parse(200, good(THINK_OPEN + "想到一半就断了")) as AiReply.Failed
        assertEquals(AiFailure.EMPTY, f.kind)
        // 自检：同一个函数在闭合标签存在时必须能读出正文，否则上面那条只是恒真
        val ok = OpenAiCompatible.parse(200, good(THINK_OPEN + "想" + THINK_CLOSE + "答案")) as AiReply.Ok
        assertEquals("答案", ok.text)
    }

    /** 有的档位正文放在 reasoning_content，content 是空串。 */
    @Test
    fun fallsBackToReasoningContent() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"",""" +
            """"reasoning_content":"因为太暗所以提亮"}}],"usage":{"prompt_tokens":5}}"""
        val ok = OpenAiCompatible.parse(200, body) as AiReply.Ok
        assertEquals("因为太暗所以提亮", ok.text)
        assertEquals(5, ok.promptTokens)
        // 只回了 prompt_tokens：另一项必须是 null，不能是编出来的 0
        assertNull(ok.completionTokens)
    }

    @Test
    fun emptyContentAndMissingChoicesAreDistinctFailures() {
        assertEquals(AiFailure.EMPTY, (OpenAiCompatible.parse(200, good("   ")) as AiReply.Failed).kind)
        assertEquals(
            AiFailure.BAD_JSON,
            (OpenAiCompatible.parse(200, """{"choices":[]}""") as AiReply.Failed).kind,
        )
    }

    // ── 从散文里抠 JSON ─────────────────────────────────────────────

    @Test
    fun extractsJsonObjectFromNoisyReplies() {
        assertEquals("""{"a":1}""", OpenAiCompatible.extractJsonObject("```json{\"a\":1}```"))
        assertEquals("""{"a":1}""", OpenAiCompatible.extractJsonObject("""好的：{"a":1} 以上"""))
        assertEquals("""{"a":{"b":2}}""", OpenAiCompatible.extractJsonObject("""前缀 {"a":{"b":2}} 后缀"""))
        // 字符串里的花括号不能把配平带跑
        assertEquals(
            """{"why":"像 } 这样 { 也不破","n":1}""",
            OpenAiCompatible.extractJsonObject("""{"why":"像 } 这样 { 也不破","n":1} 后面还有话"""),
        )
        // 转义引号里的内容也不能提前结束字符串
        assertEquals(
            """{"q":"他说\"好\" }","ok":1}""",
            OpenAiCompatible.extractJsonObject("""{"q":"他说\"好\" }","ok":1}"""),
        )
        assertNull(OpenAiCompatible.extractJsonObject("没有对象"))
        assertNull(OpenAiCompatible.extractJsonObject("""{"a":1"""), "不配平时不该把尾巴全吞掉")
    }

    // ── chat 的重试策略 ─────────────────────────────────────────────

    @Test
    fun chatRetriesTransientFailuresOnlyOnce() = runTest {
        var calls = 0
        val flaky = AiHttp { _, _, _, _ ->
            calls++
            if (calls < 2) AiHttpResponse(503, "busy") else AiHttpResponse(200, good("成"))
        }
        val ok = OpenAiCompatible.chat(flaky, textOnly, "k", "s", "u")
        assertTrue(ok is AiReply.Ok, "$ok")
        assertEquals(2, calls)

        calls = 0
        val auth = AiHttp { _, _, _, _ ->
            calls++
            AiHttpResponse(401, """{"error":{"message":"bad key"}}""")
        }
        val f = OpenAiCompatible.chat(auth, textOnly, "k", "s", "u")
        assertEquals(AiFailure.AUTH, (f as AiReply.Failed).kind)
        assertEquals(1, calls, "鉴权失败不该重试，白烧一次配额")
    }

    @Test
    fun chatNeverTouchesTheNetworkWithoutAKey() = runTest {
        var touched = false
        val http = AiHttp { _, _, _, _ -> touched = true; AiHttpResponse(200, good("x")) }
        val f = OpenAiCompatible.chat(http, textOnly, "", "s", "u")
        assertEquals(AiFailure.NO_KEY, (f as AiReply.Failed).kind)
        assertTrue(!touched, "没有 key 也照样发了请求")
    }

    /** 抛异常（无网/飞行模式）要归一成 OFFLINE，而不是把异常抛穿到界面。 */
    @Test
    fun chatTurnsTransportExceptionsIntoOffline() = runTest {
        val boom = AiHttp { _, _, _, _ -> throw RuntimeException("no route to host") }
        val f = OpenAiCompatible.chat(boom, textOnly, "k", "s", "u", retries = 0)
        assertEquals(AiFailure.OFFLINE, (f as AiReply.Failed).kind)
    }

    /** 重试次数上限：坏网下不能无限打转。 */
    @Test
    fun chatStopsAfterTheRetryBudget() = runTest {
        var calls = 0
        val dead = AiHttp { _, _, _, _ -> calls++; throw RuntimeException("down") }
        OpenAiCompatible.chat(dead, textOnly, "k", "s", "u", retries = 1)
        assertEquals(2, calls)
    }

    // ── 档位表 ──────────────────────────────────────────────────────

    @Test
    fun endpointTableIsConsistent() {
        assertTrue(AiEndpoint.ALL.isNotEmpty())
        assertEquals(AiEndpoint.ALL.size, AiEndpoint.ALL.map { it.id }.distinct().size)
        assertTrue(AiEndpoint.ALL.any { it.vision }, "一个能看图的都没有，智能P图无从谈起")
        assertTrue(AiEndpoint.ALL.all { it.baseUrl.startsWith("https://") }, "明文发 key 是不行的")
        assertEquals(AiEndpoint.ALL.first(), AiEndpoint.byId("不存在的档位"))
        // 自检：未知 id 兜底到的那一档必须能看图，否则兜底等于把功能降级没了
        assertTrue(AiEndpoint.byId("不存在的档位").vision)
    }

    private fun good(content: String): String {
        val esc = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"role":"assistant","content":"$esc"}}],""" +
            """"usage":{"prompt_tokens":64,"completion_tokens":19}}"""
    }

    companion object {
        private const val THINK_OPEN = "<" + "think" + ">"
        private const val THINK_CLOSE = "<" + "/" + "think" + ">"
    }
}
