package com.suipai.app.ui.yishun.pro

import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.LocalMaskField
import com.suipai.core.engine.MaskShape
import com.suipai.core.engine.RadialMask

/**
 * 蒙版编辑的**纯函数**：新建 / 改形状 / 改调整量 / 删除 / 钳制。
 *
 * 产品上限 4 个来自引擎（[LocalMaskField.MAX_MASKS]），超出部分引擎会静默截断 ——
 * 所以 UI 必须在**加第 5 个的时候**就说"满了"，而不是让用户拖完发现没生效。
 *
 * 所有形状参数都在归一化坐标 0..1（相对工作缓冲），见 `LocalMask` 的类注释：
 * 预览（≤1600）与成片（4096）用同一套归一化场，所以这里不做任何分辨率换算。
 * 唯一要注意的是径向半径：引擎里 `rx/ry` 会取绝对值再夹到 ≥1e-4，
 * 这里先夹到 [MIN_RADIUS]，避免用户把圈拖成一条线之后再也点不中它。
 */
object MaskModel {

    const val MAX_MASKS = LocalMaskField.MAX_MASKS

    /** 径向最小半径：再小就点不中、也看不出是圈。 */
    const val MIN_RADIUS = 0.04f

    /** 线性渐变的最小跨度：start/end 贴着时引擎按硬边处理，UI 上却是两个叠死的滑杆。 */
    const val MIN_SPAN = 0.02f

    enum class Kind(val label: String) {
        LINEAR("线性"),
        RADIAL("径向"),
    }

    fun kindOf(mask: LocalMask): Kind = when (mask.shape) {
        is LinearMask -> Kind.LINEAR
        is RadialMask -> Kind.RADIAL
    }

    /**
     * 新建一个蒙版。
     *
     * **必须带一个可见的默认调整量**，理由是一条实测会踩到的产品缺陷：
     * `LocalMaskField` 在构造时会把 `LocalAdjust.isNeutral`（四项全 0）的蒙版**直接丢掉**
     * （见 `LocalMask.kt` 的类注释：它不产生任何像素改动，却会白算一整张权重场）。
     * 所以如果这里给一个四项全 0 的蒙版，用户加完之后会看到：列表里 4 个蒙版、
     * 画面一点没变、拖任何滑杆才有反应 —— 他只会以为"加蒙版这个按钮是坏的"。
     *
     * 默认值挑的是两种最常用的意图：
     * - 线性 → **压暗一侧**（曝光 -0.30），就是「压暗天空 / 压暗背景」那一下；
     * - 径向 → **提亮主体**（曝光 +0.30），就是「把脸/主体拉出来」那一下。
     *
     * 形状本身：线性默认竖向（从上到下），径向默认居中、半径 0.35、羽化 0.5。
     */
    fun create(kind: Kind): LocalMask = when (kind) {
        Kind.LINEAR -> LocalMask(
            shape = LinearMask(angle = 90f, start = 0.25f, end = 0.75f),
            adjust = LocalAdjust(exposure = -0.30f),
        )

        Kind.RADIAL -> LocalMask(
            shape = RadialMask(cx = 0.5f, cy = 0.5f, rx = 0.35f, ry = 0.35f, feather = 0.5f),
            adjust = LocalAdjust(exposure = 0.30f),
        )
    }

    /** 只有形状、不带任何调整量的蒙版。给"我要一个真中性的壳"的调用方与测试用。 */
    fun shell(kind: Kind): LocalMask = LocalMask(shape = create(kind).shape, adjust = LocalAdjust())

    fun add(masks: List<LocalMask>, kind: Kind): List<LocalMask> =
        if (masks.size >= MAX_MASKS) masks else masks + create(kind)

    /** 删第 [index] 个；越界原样返回（配方/点击都可能给到已经变了的下标）。 */
    fun remove(masks: List<LocalMask>, index: Int): List<LocalMask> =
        if (index !in masks.indices) masks else masks.filterIndexed { i, _ -> i != index }

    fun replace(masks: List<LocalMask>, index: Int, mask: LocalMask): List<LocalMask> =
        if (index !in masks.indices) masks
        else masks.mapIndexed { i, old -> if (i == index) mask else old }

    // ---- 形状参数 ------------------------------------------------------------

    /** 线性：角度归一化到 [0,360)，start/end 夹进 [0,1] 并保持 end - start ≥ [MIN_SPAN]。 */
    fun linear(angle: Float, start: Float, end: Float): LinearMask {
        val a = ((if (angle.isNaN()) 0f else angle) % 360f + 360f) % 360f
        val s = clamp01(start)
        // end 至少比 start 大 MIN_SPAN；两者都顶到 1 时把 start 往回压，而不是让 end 溢出。
        val e = clamp01(end).coerceAtLeast((s + MIN_SPAN).coerceAtMost(1f))
        val ss = if (e - s < MIN_SPAN) (e - MIN_SPAN).coerceAtLeast(0f) else s
        return LinearMask(angle = a, start = ss, end = e)
    }

    /** 径向：中心夹进画面、半径夹进 [MIN_RADIUS, 1]、羽化夹进 [0,1]。 */
    fun radial(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        feather: Float,
        inverted: Boolean,
    ): RadialMask = RadialMask(
        cx = clamp01(cx),
        cy = clamp01(cy),
        rx = clampRadius(rx),
        ry = clampRadius(ry),
        feather = clamp01(feather),
        inverted = inverted,
    )

    /** 只改形状，保留调整量。 */
    fun withShape(mask: LocalMask, shape: MaskShape): LocalMask = mask.copy(shape = shape)

    /** 只改调整量，保留形状。 */
    fun withAdjust(mask: LocalMask, adjust: LocalAdjust): LocalMask = mask.copy(adjust = adjust)

    /** 调整量：四项各自夹进 [-1,1]（[LocalAdjust] 自己也会夹，这里是给滑杆用的同一份判据）。 */
    fun adjust(exposure: Float, contrast: Float, saturation: Float, temperature: Float): LocalAdjust =
        LocalAdjust(
            exposure = clampUnit(exposure),
            contrast = clampUnit(contrast),
            saturation = clampUnit(saturation),
            temperature = clampUnit(temperature),
        )

    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    private fun clampRadius(v: Float): Float =
        if (v.isNaN()) MIN_RADIUS else kotlin.math.abs(v).coerceIn(MIN_RADIUS, 1f)

    private fun clampUnit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)

    /** 顶栏/抽屉角标用：有几个"真的会改变画面"的蒙版（四项全 0 的引擎会丢掉）。 */
    fun activeCount(masks: List<LocalMask>): Int = masks.count { !it.adjust.isNeutral }

    /** 一行小字：`2 个蒙版（线性 1 / 径向 1）`。 */
    fun summary(masks: List<LocalMask>): String {
        if (masks.isEmpty()) return "还没有蒙版"
        val linear = masks.count { kindOf(it) == Kind.LINEAR }
        val radial = masks.count { kindOf(it) == Kind.RADIAL }
        val parts = buildList {
            if (linear > 0) add("线性 $linear")
            if (radial > 0) add("径向 $radial")
        }
        return "${masks.size}/$MAX_MASKS 个蒙版（${parts.joinToString(" / ")}）"
    }
}

/**
 * 单个蒙版调整量的滑杆表。
 *
 * 与 `Shift` / `HslChannel` 同构：值域、文案、字段读写只有一处定义，
 * 界面上那四根滑杆由它生成，读屏名也由它生成（否则两处各写一遍迟早分叉）。
 */
enum class MaskAdjustChannel(
    val label: String,
    val lowWord: String,
    val midWord: String,
    val highWord: String,
    val get: (LocalAdjust) -> Float,
    val with: (LocalAdjust, Float) -> LocalAdjust,
) {
    EXPOSURE("曝光", "压暗", "原曝光", "提亮", { it.exposureC }, { a, v -> a.copy(exposure = v) }),
    CONTRAST("对比", "更软", "原反差", "更硬", { it.contrastC }, { a, v -> a.copy(contrast = v) }),
    SATURATION("饱和", "更淡", "原饱和", "更浓", { it.saturationC }, { a, v -> a.copy(saturation = v) }),
    TEMPERATURE("色温", "偏冷", "原色", "偏暖", { it.temperatureC }, { a, v -> a.copy(temperature = v) }),
    ;

    /** 读屏与界面共用这一句：`蒙版 1 曝光 提亮`。 */
    fun caption(maskNumber: Int, adjust: LocalAdjust): String {
        val v = get(adjust)
        val word = when {
            v < -WORD_STEP -> lowWord
            v > WORD_STEP -> highWord
            else -> midWord
        }
        return "蒙版$maskNumber$label $word"
    }

    companion object {
        const val WORD_STEP = 0.04f

        /** 四项全 0 时的提示语。 */
        fun neutralHint(): String = "四项都是 0，这个蒙版不会改变画面"
    }
}
