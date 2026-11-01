package com.suipai.app.ui.yishun

import android.content.SharedPreferences
import com.suipai.core.engine.Look
import kotlin.math.abs

/**
 * 一瞬的预设筛选开关。
 *
 * 原本每个开关是一个 `var xxxOnly by remember { mutableStateOf(prefs.getBoolean(...)) }`，
 * 23 个开关 = 23 个委托属性 + 23 段 TextButton + 两遍 23 条 && 判定，
 * 全塞进同一个 @Composable。YishunScreen 因此膨胀到 ART 校验器直接拒绝该类
 * （java.lang.VerifyError: Verifier rejected class ...YishunScreenKt）。
 * 这里把开关收成一张表，界面和判定都从表里生成。
 */
enum class LookFilter(
    val prefKey: String,
    val labelOn: String,
    val labelOff: String,
    val test: Look.() -> Boolean,
) {
    SOFT("yishun_soft_only", "只看柔光", "含硬调", { softness > 0.05f }),
    GRAY("yishun_gray_only", "只看灰调", "含彩色", { monochrome || saturation <= -0.18f }),
    HIGH_CONTRAST("yishun_hi_con", "只看高反差", "含平调", { contrast > 0.14f }),
    GRAIN("yishun_grain_only", "只看颗粒", "含细腻", { grain > 0.09f }),
    VIGNETTE("yishun_vig_only", "只看暗角", "含无暗角", { vignette > 0.12f }),
    SKIN("yishun_skin_only", "只看护肤", "含无护肤", { skinProtect > 0.10f }),
    FADE("yishun_fade_only", "只看褪色", "含未褪", { fade > 0.07f }),
    SATURATION("yishun_sat_only", "只看高饱和", "含淡色", { saturation > 0.06f }),
    LOW_CONTRAST("yishun_low_con", "只看低反差", "含硬反差", { contrast < 0.09f }),
    SHADOW_LIFT("yishun_lift_only", "只看提阴影", "含压阴影", { shadowLift > 0.10f }),
    MUTE("yishun_mute_only", "只看降饱和", "含未降", { saturation < -0.04f && !monochrome }),
    HIGHLIGHT_ROLL("yishun_roll_only", "只看压高光", "含未压", { highlightRoll > 0.20f }),
    COOL_SHADOW("yishun_split_only", "只看冷阴影", "含无冷影", { splitShadowBlue > 0.06f }),
    WARM_HIGHLIGHT("yishun_warm_split", "只看暖高光", "含无暖高光", { splitHighlightWarm > 0.06f }),
    LOCK_WB("yishun_lock_wb", "只看锁白", "含未锁", { lockDaylightWb }),
    HEAVY("yishun_heavy", "只看重调", "含轻调", { defaultIntensity > 0.52f }),
    DUAL_SPLIT("yishun_dual_split", "只看分色", "含未分", { splitHighlightWarm > 0.04f && splitShadowBlue > 0.04f }),
    NEUTRAL("yishun_neutral", "只看中性", "含偏色", { abs(temperature) < 0.03f }),
    LIGHT("yishun_light", "只看浅调", "含深调", { defaultIntensity < 0.48f }),
    MID_CONTRAST("yishun_mid_con", "只看中反差", "含极端", { contrast in 0.09f..0.14f }),
    MID_GRAIN("yishun_mid_grain", "只看中颗粒", "含极端粒", { grain in 0.05f..0.09f }),
    MID_VIGNETTE("yishun_mid_vig", "只看中暗角", "含极端角", { vignette in 0.06f..0.12f }),
    MID_SOFT("yishun_mid_soft", "只看中柔", "含极端柔", { softness in 0.04f..0.08f }),
    ;

    companion object {
        fun enabledIn(prefs: SharedPreferences): Set<LookFilter> =
            entries.filter { prefs.getBoolean(it.prefKey, false) }.toSet()
    }
}

/** 一个预设是否同时满足所有已开启的筛选。 */
fun Look.matchesFilters(active: Set<LookFilter>): Boolean = active.all { it.test(this) }
