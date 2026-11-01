package com.suipai.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 失败种类。分得细是为了让界面能说清「该做什么」，而不是统一一句「网络错误」。 */
enum class AiFailure {
    /** 总开关没开：这是设计上的默认状态，不是错误。 */
    DISABLED,

    /** 用户还没贴 key。 */
    NO_KEY,

    /** 抛异常/连不上：飞行模式、无网、DNS 失败都归这里。 */
    OFFLINE,

    /**
     * 端点收了请求但没在时间内回话。
     *
     * 它由 HTTP 408 表达：commonMain 里拿不到 `SocketTimeoutException`（那是 java.io 的类型），
     * 所以由各端的 [AiHttp] 实现把超时**翻译成 408 交回来**，而不是抛异常——
     * 抛出来只会被归成 OFFLINE，用户会看到「检查网络」，而真相是「网络是通的，这家太慢」。
     */
    TIMEOUT,

    AUTH,
    RATE_LIMIT,
    SERVER,

    /** 400：请求形态被拒（常见于选了纯文本档位却带图）。 */
    REJECTED,

    /** 200 但不是能认的形状。 */
    BAD_JSON,

    /** 200 且形状对，但正文为空。 */
    EMPTY,
}

sealed class AiReply {
    data class Ok(
        val text: String,
        // null = 这家用人没回 usage；0 是「回了 0」。两件事不许混成一个数：
        // 那一行是「这一趟花了多少」的凭据，编一个 0 比空着更容易骗人。
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    ) : AiReply()

    data class Failed(val kind: AiFailure, val detail: String = "") : AiReply()
}

/**
 * OpenAI 兼容 `/chat/completions` 的请求构造与响应解析。
 *
 * [chatBody] 与 [parse] 都是纯函数，于是整条链路里唯一需要网络的部分被关在 [chat] 里，
 * 请求体和各家不规范的返回形状都能在 JVM 上钉死。
 */
object OpenAiCompatible {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 组请求体。[imageDataUrl] 非空但 [endpoint] 不支持视觉时**返回 null 拒发**，
     * 而不是静默把图丢掉——否则用户以为模型看了照片，其实只看了文字。
     */
    fun chatBody(
        endpoint: AiEndpoint,
        system: String,
        userText: String,
        imageDataUrl: String? = null,
        maxTokens: Int = 400,
        temperature: Float = 0.2f,
    ): String? {
        if (imageDataUrl != null && !endpoint.vision) return null
        val content: JsonElement = if (imageDataUrl == null) {
            JsonPrimitive(userText)
        } else {
            buildJsonArray {
                addJsonObject {
                    put("type", "text")
                    put("text", userText)
                }
                addJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", imageDataUrl) })
                }
            }
        }
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * 状态码 + 正文 → [AiReply]。
     *
     * 三家实测形状不一致：智谱把错误写成 error.code/error.message；
     * 带思考的档位会把思维链单独放进 reasoning_content，而 glm-4.1v-thinking-flash
     * 直接把 think 标签混在 content 里。两种都要能读出正文，否则回复看着非空却解析成空。
     */
    fun parse(status: Int, body: String): AiReply {
        if (status == 401 || status == 403) return AiReply.Failed(AiFailure.AUTH, errText(body))
        if (status == 408) return AiReply.Failed(AiFailure.TIMEOUT, errText(body))
        if (status == 429) return AiReply.Failed(AiFailure.RATE_LIMIT, errText(body))
        if (status == 400) return AiReply.Failed(AiFailure.REJECTED, errText(body))
        if (status >= 500) return AiReply.Failed(AiFailure.SERVER, errText(body))
        if (status !in 200..299) {
            return AiReply.Failed(AiFailure.REJECTED, errText(body).ifBlank { "HTTP $status" })
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return AiReply.Failed(AiFailure.BAD_JSON, body.take(160))
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return AiReply.Failed(AiFailure.BAD_JSON, body.take(160))
        val msg = choice["message"]?.jsonObject
            ?: return AiReply.Failed(AiFailure.BAD_JSON, body.take(160))
        val content = msg["content"].text()
        val reasoning = msg["reasoning_content"].text()
        val text = stripThinking(content).ifBlank { stripThinking(reasoning) }
        if (text.isBlank()) return AiReply.Failed(AiFailure.EMPTY, content.take(160))
        val usage = root["usage"]?.jsonObject
        return AiReply.Ok(
            text = text.trim(),
            promptTokens = usage.intOfOrNull("prompt_tokens"),
            completionTokens = usage.intOfOrNull("completion_tokens"),
        )
    }

    /**
     * 去掉混在正文里的思考段。
     *
     * 标签用拼接写出来：这里若出现字面的结束标签会把本文件的注释吃掉。
     * 必须匹配完整的开标签而不是 "think" + ">"——少一个尖括号就只吃掉 "think>"，
     * 正文前面会留下一个孤零零的 "<"，多模态档位下这个残字符足以让后面的 JSON 解析失败。
     * 找不到闭合标签说明模型被 max_tokens 截断在半截思考里：此时**没有答案**，
     * 交出去的就是把一段没结论的碎碎念当成调色建议，所以直接截断在这里。
     */
    fun stripThinking(raw: String): String {
        val open = "<" + "think" + ">"
        val close = "<" + "/" + "think" + ">"
        var s = raw
        while (true) {
            val i = s.indexOf(open)
            if (i < 0) break
            val j = s.indexOf(close, i + open.length)
            if (j < 0) return s.substring(0, i).trim()
            s = s.substring(0, i) + s.substring(j + close.length)
        }
        return s.trim()
    }

    /**
     * 从可能裹着 ```json 围栏、前后带解释文字的回复里取出第一个花括号配平的 JSON 对象。
     * 模型很少老实回裸对象，这步不做后面全白做。字符串内的花括号与转义引号要跳过。
     */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 一次调用，带一次重试（只重试值得重试的那两类错）。 */
    suspend fun chat(
        http: AiHttp,
        endpoint: AiEndpoint,
        apiKey: String,
        system: String,
        userText: String,
        imageDataUrl: String? = null,
        maxTokens: Int = 400,
        timeoutMs: Int = 20_000,
        retries: Int = 1,
    ): AiReply {
        if (apiKey.isBlank()) return AiReply.Failed(AiFailure.NO_KEY)
        val body = chatBody(endpoint, system, userText, imageDataUrl, maxTokens)
            ?: return AiReply.Failed(AiFailure.REJECTED, "${endpoint.label} 不支持图片")
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        var last: AiReply = AiReply.Failed(AiFailure.OFFLINE)
        repeat(retries + 1) {
            val reply = runCatching { http.post(url, body, apiKey, timeoutMs) }.fold(
                onSuccess = { parse(it.status, it.body) },
                onFailure = { AiReply.Failed(AiFailure.OFFLINE, it.message.orEmpty()) },
            )
            last = reply
            // TIMEOUT 故意不在这里：端点已经慢过一次，再等一轮只是把用户的等待翻倍，
            // 界面上「再点一次」比自动重试更诚实。OFFLINE（连不上）与 5xx（服务端抖动）才值得马上再试一次。
            val retryable = reply is AiReply.Failed &&
                (reply.kind == AiFailure.OFFLINE || reply.kind == AiFailure.SERVER)
            if (reply is AiReply.Ok || !retryable) return reply
        }
        return last
    }

    private fun JsonElement?.text(): String =
        (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject?.intOfOrNull(key: String): Int? = runCatching {
        this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }.getOrNull()

    private fun errText(body: String): String = runCatching {
        val e = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        val code = e?.get("code").text()
        val msg = e?.get("message").text()
        listOf(code, msg).filter { it.isNotBlank() }.joinToString(" ")
    }.getOrDefault("").ifBlank { body.take(160) }
}
