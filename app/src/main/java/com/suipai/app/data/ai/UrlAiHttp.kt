package com.suipai.app.data.ai

import android.util.Log
import com.suipai.core.ai.AiHttp
import com.suipai.core.ai.AiHttpResponse
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唯一的一条出网路径：POST 一段 JSON，拿回状态码与正文。
 *
 * 为什么不引 OkHttp/Ktor：随拍的数据安全申报写的是「依赖只有 androidx / Kotlin / Coil」，
 * 多一个网络栈就要多审一条供应链，而这里需要的能力只有这一句。
 * 平台自带的 [HttpURLConnection] 够用，且它是**唯一**被允许发请求的地方——
 * 「关掉总开关后一个请求都不发」这条要能在设备上证明（见 AiSheet 的开关与 ANDROID-VERIFY.md）。
 */
object UrlAiHttp : AiHttp {

    /** 只在 HTTPS 上发：Bearer 头走明文等于把 key 送出去。 */
    private const val SCHEME = "https://"

    override suspend fun post(
        url: String,
        jsonBody: String,
        apiKey: String,
        timeoutMs: Int,
    ): AiHttpResponse = withContext(Dispatchers.IO) {
        if (!url.startsWith(SCHEME)) throw IOException("只允许 https")
        logAttempt(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            // 非 2xx 时正文在 errorStream；读错流会拿到空串，界面就只剩「HTTP 401」没有原因。
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            AiHttpResponse(status, body.take(MAX_BODY_CHARS))
        } catch (e: InterruptedIOException) {
            // 408 是这条链路上「超时」的唯一表示：commonMain 拿不到 java 的超时类型，
            // 所以在这里把它翻成 HTTP 语义交回给 OpenAiCompatible.parse 归一成 AiFailure.TIMEOUT。
            //
            // 这里**不**区分「连不上」与「连上了但回得慢」：试过显式 `conn.connect()` 把两阶段拆开，
            // 但那会在写请求体之前就把请求头提交出去，等于在唯一的认证路径上动手术，
            // 而两种情况给用户的建议其实一样（等一会儿再点一次 / 检查网络），不值得冒这个风险。
            AiHttpResponse(408, "")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 错误正文只用来给人看原因，截断防止某个端点回一整页 HTML 把日志和界面一起撑爆。 */
    private const val MAX_BODY_CHARS = 4000

    /**
     * 只打「有没有出网 + 打给谁」，不打 key、不打请求体、不打照片。
     *
     * 这行日志是**隐私声明的测量仪器**：这台 AVD 上 `dumpsys netstats` 没有 per-UID 字节行、
     * `ss` 也看不到应用套接字，要证明「关掉总开关后一个请求都不发」，只能数这一行
     * （见 ANDROID-VERIFY.md §8.2 的三条断言）。
     */
    fun logAttempt(url: String) {
        Log.i(TAG, "ai request -> ${URL(url).host}")
    }

    private const val TAG = "SuiPaiAi"
}
