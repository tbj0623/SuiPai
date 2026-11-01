package com.suipai.core.ai

import com.suipai.core.engine.Look
import com.suipai.core.engine.LookSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/**
 * 模型交回来的偏移量的可信区间。
 *
 * 数值与 app 侧 `Shift.clamp` 必须一致，这条由 `app` 的 `AiRangeGuardTest` 钉住：
 * 模型经常越界（实测见过 sat=-1、contrast=0.28），越界不是报错而是要夹回滑杆拖得到的位置，
 * 夹错区间就会出现「AI 给的值在界面上显示为 0，但预览已经变了」这种查不出来的怪事。
 */
object AiShiftLimits {
    val TEMP = -0.25f..0.25f
    val CONTRAST = -0.25f..0.25f
    val SAT = -0.35f..0.35f
    val SHADOW = -0.2f..0.25f
    val FADE = -0.2f..0.3f
    val HIGHLIGHT = -0.2f..0.3f
    val SOFT = -0.15f..0.25f

    /** 七个里最多允许几个非 0：超了说明模型在乱加，整组丢掉比留一半诚实。 */
    const val MAX_NONZERO = 4
}

/** 七个微调偏移，全部已经夹进 [AiShiftLimits]。 */
data class AiShifts(
    val temp: Float = 0f,
    val contrast: Float = 0f,
    val sat: Float = 0f,
    val shadow: Float = 0f,
    val fade: Float = 0f,
    val highlight: Float = 0f,
    val soft: Float = 0f,
) {
    val isNeutral: Boolean
        get() = temp == 0f && contrast == 0f && sat == 0f && shadow == 0f &&
            fade == 0f && highlight == 0f && soft == 0f

    val nonZeroCount: Int
        get() = listOf(temp, contrast, sat, shadow, fade, highlight, soft).count { it != 0f }
}

/** 一次成功的推荐。 */
data class AiSuggestion(
    val look: Look,
    /** 同族备选：点了主推荐不满意时给的两三条，来自整库检索而不是候选表。 */
    val alternates: List<Look>,
    val seen: String,
    val why: String,
    val query: String,
    val shifts: AiShifts,
    /** 模型给的偏移被丢掉时说明原因（越界太多 / 非 0 项超限）。 */
    val shiftsDropped: String? = null,
    /** true = 模型给的编号不在候选表里，这条是按它的描述从整库里落出来的。 */
    val resolvedByFallback: Boolean = false,
)

sealed class AiParse {
    data class Ok(val suggestion: AiSuggestion) : AiParse()

    /** 回复里根本没有配平的 JSON：多半是模型开始讲道理了。 */
    data class NotJson(val detail: String) : AiParse()

    /** lookId 不在候选表里——不能拿它当配方，但 seen/why 仍然可以显示。 */
    data class UnknownLook(val lookId: String, val detail: String) : AiParse()
}

/**
 * 把模型的回复落成一条**可执行的**配方。
 *
 * 唯一入口是 [parse]，它只接受候选表里的 id：这样「AI 推荐」永远不可能把用户带到一个
 * 不存在的配方上（那会表现为点了没反应）。备选走整库检索，所以覆盖面又不受候选表限制。
 */
object AiSuggestionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        replyText: String,
        candidates: List<Look>,
        all: List<Look>,
        alternateLimit: Int = 2,
    ): AiParse {
        val obj = OpenAiCompatible.extractJsonObject(replyText)
            ?: return AiParse.NotJson(replyText.take(160))
        val root = runCatching { json.parseToJsonElement(obj).jsonObject }.getOrNull()
            ?: return AiParse.NotJson(obj.take(160))

        val seen = root.str("seen")
        val why = root.str("why")
        val query = root.str("query")
        val lookId = root.str("lookId").trim()

        // 首选：模型交回的 id 必须逐字在候选表里。
        // 兜底：实测两家模型都会在个别照片上编 id（智谱给 tanhong、Agnes 给 rustic，都是「土红」的拼音/英译），
        // 这时它同时给的 query 往往是准的（「土红 低饱和」），拿 query 去整库里落一条比直接报错好。
        // 落不到（below MIN_FALLBACK）仍然算失败——宁可说「没看懂」也不要给一条不相干的配方。
        var byFallback = false
        val look = candidates.firstOrNull { it.id == lookId }
            ?: LookSearch.byWords("$query $lookId", all, 1).firstOrNull()?.also { byFallback = true }
            ?: return AiParse.UnknownLook(lookId, replyText.take(160))

        val raw = AiShifts(
            temp = root.num("temp"),
            contrast = root.num("contrast"),
            sat = root.num("sat"),
            shadow = root.num("shadow"),
            fade = root.num("fade"),
            highlight = root.num("highlight"),
            soft = root.num("soft"),
        )
        val (shifts, dropped0) = clamp(raw)
        val dropped = when {
            byFallback && dropped0 != null -> dropped0
            byFallback -> "模型给的配方编号不在库里，按它的描述就近找了一条"
            else -> dropped0
        }
        return AiParse.Ok(
            AiSuggestion(
                look = look,
                alternates = LookSearch.around(look, query, all, alternateLimit),
                seen = seen,
                why = why,
                query = query,
                shifts = shifts,
                shiftsDropped = dropped,
                resolvedByFallback = byFallback,
            ),
        )
    }

    /**
     * 夹取 + 丢弃。
     *
     * 越界本身按区间夹回；但一旦有任意一项越界超过区间宽度的两倍（例如 sat=-1），
     * 说明模型不是在微调而是在重画，整组偏移丢掉、只保留配方——配方已经带了正确的色相。
     */
    private fun clamp(raw: AiShifts): Pair<AiShifts, String?> {
        val wild = abs(raw.temp) > 0.5f || abs(raw.contrast) > 0.5f || abs(raw.sat) > 0.7f ||
            abs(raw.shadow) > 0.5f || abs(raw.fade) > 0.6f || abs(raw.highlight) > 0.6f ||
            abs(raw.soft) > 0.5f
        if (wild) return AiShifts() to "模型给的偏移超出可信范围，已按配方原样应用"
        val clamped = AiShifts(
            temp = raw.temp.coerceIn(AiShiftLimits.TEMP),
            contrast = raw.contrast.coerceIn(AiShiftLimits.CONTRAST),
            sat = raw.sat.coerceIn(AiShiftLimits.SAT),
            shadow = raw.shadow.coerceIn(AiShiftLimits.SHADOW),
            fade = raw.fade.coerceIn(AiShiftLimits.FADE),
            highlight = raw.highlight.coerceIn(AiShiftLimits.HIGHLIGHT),
            soft = raw.soft.coerceIn(AiShiftLimits.SOFT),
        )
        if (clamped.nonZeroCount > AiShiftLimits.MAX_NONZERO) {
            return AiShifts() to "模型一次改了 ${clamped.nonZeroCount} 项，太多，已按配方原样应用"
        }
        return clamped to null
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    /** 数字可能以 "0.1" 字符串、0.1、或 0 的形式回来，实测三种都见过。 */
    private fun JsonObject.num(key: String): Float =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toFloatOrNull() ?: 0f
}
