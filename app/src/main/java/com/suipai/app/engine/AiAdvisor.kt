package com.suipai.app.engine

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.suipai.core.ai.AiEndpoint
import com.suipai.core.ai.AiFailure
import com.suipai.core.ai.AiHttp
import com.suipai.core.ai.AiParse
import com.suipai.core.ai.AiPhotoFacts
import com.suipai.core.ai.AiPrompt
import com.suipai.core.ai.AiRecipeTable
import com.suipai.core.ai.AiReply
import com.suipai.core.ai.AiSuggestion
import com.suipai.core.ai.AiSuggestionParser
import com.suipai.core.ai.OpenAiCompatible
import com.suipai.core.engine.Look
import com.suipai.core.engine.LookLibrary
import java.io.ByteArrayOutputStream

/** 一次「看这张图」的结果。失败要带着**种类**回来，界面才能说清下一步做什么。 */
sealed class AiOutcome {
    data class Suggested(
        val suggestion: AiSuggestion,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val millis: Long,
    ) : AiOutcome()

    data class Failed(val kind: AiFailure, val detail: String = "") : AiOutcome()
}

/**
 * 把「一张本机的照片」变成「一条可应用的配方建议」。
 *
 * 出网内容在这里被钉死成两样东西：**长边 [SEND_EDGE] 的缩略 JPEG** 和 prompt 文本。
 * 原图、EXIF、拍摄位置、相册里的其它照片都不参与——这条边界是隐私申报的实体，
 * 改 [SEND_EDGE] 或加字段之前必须同步改 `AboutSheet` 与 `store/privacy-policy.html`。
 */
object AiAdvisor {

    /**
     * 出网缩略图的长边与 JPEG 质量。
     *
     * 256 是量出来的，不是猜的：同一套 prompt、12 张真照片，256 与 512 都拿到 12/12 合法 JSON、
     * 12/12 命中候选表里的真实 id，7~8 条不同配方（512 是 8 条），延迟 2.4s 对 2.7s；
     * 差别在上传字节：平均 11 KB 对 38 KB。判色相靠的是低频信息，缩到 128 也一样能用——
     * 选 256 是让模型看得见构图与色族，同时把「出网的这张图」明确留在缩略图的量级。
     */
    const val SEND_EDGE = 256
    const val SEND_QUALITY = 85
    private const val MAX_TOKENS = 400

    /**
     * 单次超时。15 秒而不是 20：实测中位 1.1~2.9 秒、最坏 5 秒，15 秒已经留了 3 倍余量，
     * 而超时不自动重试（见 [com.suipai.core.ai.OpenAiCompatible.chat]），所以最坏等待就是这一个数。
     */
    /** 单次读超时（毫秒）。界面上的失败文案要引用同一个数，所以它是公开的。 */
    const val TIMEOUT_MS = 15_000

    /** 采样边长：与宿主机 harness 的 `facts()` 一致，两边算出的数要能互相对上。 */
    private const val FACTS_SAMPLE_EDGE = 64

    /** 一次请求要发出去的两样东西：缩略图 data url，以及本机量到的色偏。 */
    data class Prepared(val dataUrl: String, val facts: AiPhotoFacts)

    /** 候选表只算一次：5446 条配方过一遍色族分桶是毫秒级，但没必要每次点按钮都来一遍。 */
    val candidates: List<Look> by lazy { AiRecipeTable.build(LookLibrary.all) }

    suspend fun suggest(
        bitmap: Bitmap,
        endpoint: AiEndpoint,
        apiKey: String,
        http: AiHttp,
    ): AiOutcome {
        if (apiKey.isBlank()) return AiOutcome.Failed(AiFailure.NO_KEY)
        if (!endpoint.vision) return AiOutcome.Failed(AiFailure.REJECTED, "${endpoint.label} 不支持图片")
        val prepared = prepare(bitmap) ?: return AiOutcome.Failed(AiFailure.EMPTY, "无法压缩这张照片")
        // 与 UrlAiHttp 共用一个 tag：`logcat -s SuiPaiAi` 一次就能看到「发了什么数 + 有没有真发」。
        Log.i("SuiPaiAi", "ai facts -> ${prepared.facts.line()}")
        val started = System.currentTimeMillis()
        val reply = OpenAiCompatible.chat(
            http = http,
            endpoint = endpoint,
            apiKey = apiKey,
            system = AiPrompt.system(candidates),
            userText = AiPrompt.userText(prepared.facts),
            imageDataUrl = prepared.dataUrl,
            maxTokens = MAX_TOKENS,
            timeoutMs = TIMEOUT_MS,
        )
        val millis = System.currentTimeMillis() - started
        return when (reply) {
            is AiReply.Failed -> AiOutcome.Failed(reply.kind, reply.detail)
            is AiReply.Ok -> when (val parsed = AiSuggestionParser.parse(reply.text, candidates, LookLibrary.all)) {
                is AiParse.Ok -> AiOutcome.Suggested(
                    suggestion = parsed.suggestion,
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens,
                    millis = millis,
                )

                is AiParse.NotJson -> AiOutcome.Failed(AiFailure.BAD_JSON, parsed.detail)
                is AiParse.UnknownLook -> AiOutcome.Failed(AiFailure.BAD_JSON, "模型给的配方不在库里：${parsed.lookId}")
            }
        }
    }

    /**
     * 缩略 + JPEG + base64 data url，并顺手算出色偏。
     *
     * 缩放副本用完即回收，一瞬页本来就吃内存；传进来的 [bitmap] 是调用方的原图，绝不回收。
     */
    fun prepare(bitmap: Bitmap): Prepared? = runCatching {
        val src = scale(bitmap, SEND_EDGE) ?: return@runCatching null
        try {
            val out = ByteArrayOutputStream(64 * 1024)
            if (!src.compress(Bitmap.CompressFormat.JPEG, SEND_QUALITY, out)) return@runCatching null
            val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            Prepared(url, AiPhotoFacts.from(samplePixels(src)))
        } finally {
            if (src !== bitmap && !src.isRecycled) src.recycle()
        }
    }.getOrNull()

    /** 等比缩到长边 [edge]；已经在范围内就原样返回（调用方按 `!==` 判断要不要回收）。 */
    private fun scale(bitmap: Bitmap, edge: Int): Bitmap? {
        if (bitmap.isRecycled) return null
        val s = edge / maxOf(bitmap.width, bitmap.height).toFloat()
        if (s >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * s).toInt().coerceAtLeast(1),
            (bitmap.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun samplePixels(src: Bitmap): IntArray {
        val small = scale(src, FACTS_SAMPLE_EDGE) ?: return IntArray(0)
        try {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            return px
        } finally {
            if (small !== src && !small.isRecycled) small.recycle()
        }
    }
}
