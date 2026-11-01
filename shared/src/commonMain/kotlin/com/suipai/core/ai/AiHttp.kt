package com.suipai.core.ai

/**
 * 一次 HTTPS POST 的最小抽象。
 *
 * 为什么要把 HTTP 抽出去而不是直接在 shared 里引 OkHttp/Ktor：
 * 随拍对外的数据安全申报写的是「依赖只有 androidx / Kotlin / Coil」，多一个第三方网络栈
 * 就要多审一个供应链；而这里需要的能力只有一句「POST 一段 JSON，拿回状态码和正文」。
 * Android 侧用 HttpURLConnection（平台自带），iOS 侧将来用 URLSession，各自十几行。
 *
 * 实现必须在后台线程执行，允许阻塞；[post] 抛异常等同于网络失败，由调用方归一成
 * [AiReply.Failed]。
 */
fun interface AiHttp {
    suspend fun post(url: String, jsonBody: String, apiKey: String, timeoutMs: Int): AiHttpResponse
}

/** HTTP 状态码与响应体原文——解析和判错都交给 [OpenAiCompatible]，这里不做判断。 */
data class AiHttpResponse(val status: Int, val body: String)
