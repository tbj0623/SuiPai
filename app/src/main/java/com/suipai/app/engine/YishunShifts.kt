package com.suipai.app.engine

import android.content.SharedPreferences
import com.suipai.core.ai.AiShifts
import com.suipai.core.engine.YishunRecipe

/**
 * 一瞬的 7 个微调偏移，收成一张表（做法照抄 [com.suipai.app.ui.yishun.LookFilter]）。
 *
 * 两个动机都不是「好看」：
 * 1. `YishunScreen()` 里 7 个 `mutableFloatStateOf` 合成 1 个——那个 composable 长到 1674 行时
 *    被 ART 校验器整类拒过（编译期完全看不出来，见交接文档 §3.8），少一个委托就离悬崖远一点；
 * 2. 7 段一模一样的 Text+Slider 合成一个 `forEach`，滑杆范围/落盘键/文案只有一处定义，
 *    不会再出现「滑杆上 0.3、可信区间 0.35」「滑杆写淡化、摘要写褪色」这类两两对不上的情况。
 */
data class Shifts(
    val temp: Float = 0f,
    val contrast: Float = 0f,
    val sat: Float = 0f,
    val shadow: Float = 0f,
    val fade: Float = 0f,
    val highlight: Float = 0f,
    val soft: Float = 0f,
) {
    val isNeutral: Boolean get() = this == None

    companion object {
        val None = Shifts()

        fun load(prefs: SharedPreferences): Shifts =
            Shift.entries.fold(None) { acc, s -> s.with(acc, s.read(prefs)) }
    }
}

/**
 * @param clamp 落盘值可信区间。比 [range] 宽是故意的：老版本存过界值时要夹得回来，
 *              但滑杆本身不该让人拖到那里。
 */
enum class Shift(
    val prefKey: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val clamp: ClosedFloatingPointRange<Float>,
    val lowWord: String,
    val midWord: String,
    val highWord: String,
    val wordStep: Float,
    val get: (Shifts) -> Float,
    val with: (Shifts, Float) -> Shifts,
    val fromRecipe: (YishunRecipe) -> Float,
) {
    Temp(
        "yishun_temp", "色温", -0.22f..0.22f, -0.25f..0.25f,
        "偏冷", "原色", "偏暖", 0.04f,
        { it.temp }, { s, v -> s.copy(temp = v) }, { it.tempShift },
    ),
    Contrast(
        "yishun_contrast", "对比", -0.2f..0.2f, -0.25f..0.25f,
        "更软", "原反差", "更硬", 0.03f,
        { it.contrast }, { s, v -> s.copy(contrast = v) }, { it.contrastShift },
    ),
    Sat(
        "yishun_sat", "饱和", -0.3f..0.3f, -0.35f..0.35f,
        "更淡", "原饱和", "更浓", 0.04f,
        { it.sat }, { s, v -> s.copy(sat = v) }, { it.satShift },
    ),
    Shadow(
        "yishun_shadow", "阴影", -0.16f..0.2f, -0.2f..0.25f,
        "更深", "原阴影", "更亮", 0.03f,
        { it.shadow }, { s, v -> s.copy(shadow = v) }, { it.shadowShift },
    ),
    Fade(
        "yishun_fade", "褪色", -0.12f..0.22f, -0.2f..0.3f,
        "更实", "不褪", "更淡", 0.03f,
        { it.fade }, { s, v -> s.copy(fade = v) }, { it.fadeShift },
    ),
    Highlight(
        "yishun_hi", "高光", -0.16f..0.24f, -0.2f..0.3f,
        "更冲", "原高光", "更收", 0.03f,
        { it.highlight }, { s, v -> s.copy(highlight = v) }, { it.highlightShift },
    ),
    Soft(
        "yishun_soft", "柔光", -0.12f..0.22f, -0.15f..0.25f,
        "更锐", "原柔光", "更柔", 0.03f,
        { it.soft }, { s, v -> s.copy(soft = v) }, { it.softShift },
    ),
    ;

    fun read(prefs: SharedPreferences): Float = prefs.getFloat(prefKey, 0f).coerceIn(clamp)

    /** 「色温 偏暖」这种一行说明；阈值两侧各 [wordStep]。 */
    fun caption(value: Float): String = label + " " + when {
        value < -wordStep -> lowWord
        value > wordStep -> highWord
        else -> midWord
    }
}

/** 落盘全部 7 项（一次 edit/apply，别在循环里逐条 apply）。 */
fun Shifts.saveTo(prefs: SharedPreferences) {
    val editor = prefs.edit()
    Shift.entries.forEach { editor.putFloat(it.prefKey, it.get(this)) }
    editor.apply()
}

/**
 * 智能推荐给的偏移 → 本机的 7 个滑杆。
 *
 * shared 侧已经按可信区间夹过一次，这里再夹一次不是重复劳动：`when` 覆盖 [Shift] 全部枚举，
 * 以后加第 8 个滑杆时这里会**编译不过**，逼着改的人决定 AI 要不要动它——
 * 而按名字取值的写法只会安静地漏掉。
 */
fun AiShifts.asShifts(): Shifts = Shift.entries.fold(Shifts.None) { acc, s ->
    acc.let { s.with(it, valueOf(s).coerceIn(s.clamp)) }
}

private fun AiShifts.valueOf(s: Shift): Float = when (s) {
    Shift.Temp -> temp
    Shift.Contrast -> contrast
    Shift.Sat -> sat
    Shift.Shadow -> shadow
    Shift.Fade -> fade
    Shift.Highlight -> highlight
    Shift.Soft -> soft
}

/** 配方来自 EXIF，属于外部输入：逐条夹回可信区间，别让别的 App 写进一个拖不动的滑杆。 */
fun YishunRecipe.asShifts(): Shifts =
    Shift.entries.fold(Shifts.None) { acc, s -> s.with(acc, s.fromRecipe(this).coerceIn(s.clamp)) }

fun recipeWithShifts(
    lookId: String,
    intensity: Float,
    grainMul: Float,
    vignetteMul: Float,
    exportEdge: Int,
    shifts: Shifts,
): YishunRecipe = YishunRecipe(
    lookId = lookId,
    intensity = intensity,
    tempShift = shifts.temp,
    contrastShift = shifts.contrast,
    satShift = shifts.sat,
    shadowShift = shifts.shadow,
    fadeShift = shifts.fade,
    highlightShift = shifts.highlight,
    softShift = shifts.soft,
    grainMul = grainMul,
    vignetteMul = vignetteMul,
    exportEdge = exportEdge,
)
