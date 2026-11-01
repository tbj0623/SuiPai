package com.suipai.app.ui.camera

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 相机专业模式（PRO）的**纯逻辑层**：档位表、参数钳制、降级判定、CaptureRequest 计划。
 *
 * 为什么单独一个文件、且这里一行 Android 调用都没有：
 *
 * 1. 专业模式的价值全在"设备支持什么"和"用户选的那个档在不在范围里"上。这两件事写成
 *    纯函数才能在 JVM 里逐条穷举（`CameraProStateTest` 钉边界），而**一旦混进 Camera2 的
 *    对象，就只能在真机上碰运气**——本项目已经栽过「守卫没跑与守卫通过长得一样」。
 * 2. 相机页崩掉的代价远大于少一个功能（见 ROADMAP 波段 2 的纪律）。这里所有判定的语义都是
 *    "拿不到 → null → 界面置灰"，不许抛、不许猜。
 *
 * 唯一的 Android 依赖是 [Camera2Codes] 的那几个常量（`const val`，编译期内联成真字面量，
 * JVM 单测里不会像 android.jar 的静态字段那样退化成 0），以及本文件末尾的
 * [CaptureOptionPlan]——它只是"要往 CaptureRequest 里放什么"的一张数据表，真正 set 的地方
 * 在 `CameraScreen.kt` 且每一处都 `runCatching`。
 */

/**
 * `CameraMetadata` 里我们要用的那几个码，**抄成本文件自己的常量**。
 *
 * 为什么不用 `CameraMetadata.CONTROL_AE_MODE_OFF`：
 * `app/build.gradle.kts` 开了 `unitTests.isReturnDefaultValues = true`，JVM 单测里
 * android.jar 全是桩，**读静态字段一律得到 0**。于是
 * `assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, plan.aeMode)` 这种断言会变成
 * "0 == 0" 的恒真空跑——而"AE 该开还是该关"正是这一个模块里最要命的一条。
 *
 * 写成 `const val` 之后（Kotlin 会内联成真字面量），
 * 1. 单测里比较的是真值，写错了会红；
 * 2. `CameraProStateTest.androidCodesMatchTheSdk` 反过来钉住这张表本身没抄错。
 *
 * 值来源：`android-36/android.jar` 上 `javap -constants android.hardware.camera2.CameraMetadata`。
 * **改这里之前先重跑那一条命令**，别凭记忆改。
 */
object Camera2Codes {
    const val CONTROL_AE_MODE_OFF = 0
    const val CONTROL_AE_MODE_ON = 1
    const val CONTROL_AWB_MODE_OFF = 0
    const val CONTROL_AF_MODE_OFF = 0
    /** `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`：手动增益必须配它，否则驱动忽略 GAINS。 */
    const val COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0
    /** 对照值：只在单测里用来证明上一条不是"随便哪个数都行"。 */
    const val COLOR_CORRECTION_MODE_FAST = 1
}

/**
 * 一档参数的可用范围。**null 一律表示"这台设备不支持这一档"**，不是"用默认值"。
 *
 * 为什么用三张独立的范围而不是一个 `CameraCharacteristics`：
 * 后者是 android.hardware.camera2 的对象，JVM 里造不出来；而这三张 Range 就是界面上
 * 唯一真正需要的东西，收窄到这一步之后整个降级矩阵都能单测。
 */
data class ProCaps(
    /** `SENSOR_INFO_SENSITIVITY_RANGE`（部分设备叫 SENSOR_INFO_SENSITIVITY_RANGE 的旧名）。 */
    val isoRange: IntRange?,
    /** `SENSOR_INFO_EXPOSURE_TIME_RANGE`：纳秒。 */
    val shutterNsRange: LongRange?,
    /** `CONTROL_AE_COMPENSATION_RANGE`：**补偿档位下标**（不是 ev 值），与现有 EV 格同一口径。 */
    val evRange: IntRange?,
    /** `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 之外的最近对焦距离：`LENS_INFO_MINIMUM_FOCUS_DISTANCE`（屈光度）。 */
    val maxFocusDiopters: Float,
    /** 设备允许的白平衡档（开尔文）。空 = 不支持手动白平衡，界面整档置灰。 */
    val wbKelvins: List<Int>,
    /** `CONTROL_AE_AVAILABLE_MODES` 里有 `AE_MODE_OFF` 才谈得上手动 ISO / 快门。 */
    val aeOffSupported: Boolean,
    /** `CONTROL_AWB_AVAILABLE_MODES` 里有 `AWB_MODE_OFF` 才谈得上手动白平衡。 */
    val awbOffSupported: Boolean,
    /** `CONTROL_AF_AVAILABLE_MODES` 里有 `AF_MODE_OFF` 才谈得上手动对焦。 */
    val afOffSupported: Boolean,
) {
    /** 整个 PRO 入口能不能用。缺任何一项都不算废——分档置灰，见 [ProAvailability]。 */
    val anyManual: Boolean
        get() = (aeOffSupported && isoRange != null && shutterNsRange != null) ||
            (awbOffSupported && wbKelvins.isNotEmpty()) ||
            afOffSupported

    companion object {
        /** 读不到任何能力时用它：全 null / 全 false，等于"这台设备什么都没有"。 */
        val None = ProCaps(
            isoRange = null,
            shutterNsRange = null,
            evRange = null,
            maxFocusDiopters = 0f,
            wbKelvins = emptyList(),
            aeOffSupported = false,
            awbOffSupported = false,
            afOffSupported = false,
        )
    }
}

/**
 * 用户当前的专业参数。**null 一律表示"这一项交给相机自动"**，
 * 全 null 就是普通模式——这一点让"关掉 PRO 必须复位"变成一次构造，而不是一段清理代码。
 */
data class ProMode(
    val enabled: Boolean = false,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    /** AE 补偿下标（1/3 档一格），与 `cameraControl.setExposureCompensationIndex` 同口径。 */
    val ev: Int = 0,
    val wbKelvin: Int? = null,
    /** 屈光度（1/米）。0 = 无穷远，越大越近。 */
    val focusDiopters: Float? = null,
) {
    /** 有没有任何一项真在手动。PRO 开着但五项全自动 = 与普通模式等价，状态行要说实话。 */
    val anyManual: Boolean
        get() = iso != null || shutterNs != null || wbKelvin != null || focusDiopters != null

    companion object {
        /** 关掉 PRO 之后的唯一形状。名字带 Auto 是为了让调用点读起来像一句人话。 */
        val Auto = ProMode(enabled = false)
    }
}

/** 某一档参数此刻能不能点。`why` 是给用户看的一句原因（置灰时也必须有话说）。 */
data class ProAvailability(
    val iso: Boolean = false,
    val shutter: Boolean = false,
    val ev: Boolean = false,
    val wb: Boolean = false,
    val focus: Boolean = false,
    /** 整个 PRO 都不能用时的原因；能用则为空串。 */
    val reason: String = "",
) {
    val any: Boolean get() = iso || shutter || ev || wb || focus
}

/**
 * 档位表。**全项目唯一的一份**：界面从它生成，单测对它做单调/唯一性断言，
 * `docs/product/CAMERA-PRO.md` 的档位表也从它抄。
 */
object ProTables {

    /** ISO：1/3 档一节的常用序列，50–6400。超出的设备范围由 [clampIso] 夹。 */
    val isos: List<Int> = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
    )

    /**
     * 快门：1/4000 – 2 秒。值用**纳秒**存（`SENSOR_EXPOSURE_TIME` 的单位），
     * 标签在 [shutterLabel] 里按人话说（短于 0.3 秒写成 1/125，长于它写成 0.5s / 2s）。
     */
    val shuttersNs: List<Long> = listOf(
        sec(1.0 / 4000), sec(1.0 / 3200), sec(1.0 / 2500), sec(1.0 / 2000),
        sec(1.0 / 1600), sec(1.0 / 1250), sec(1.0 / 1000), sec(1.0 / 800),
        sec(1.0 / 640), sec(1.0 / 500), sec(1.0 / 400), sec(1.0 / 320),
        sec(1.0 / 250), sec(1.0 / 200), sec(1.0 / 160), sec(1.0 / 125),
        sec(1.0 / 100), sec(1.0 / 80), sec(1.0 / 60), sec(1.0 / 50),
        sec(1.0 / 40), sec(1.0 / 30), sec(1.0 / 25), sec(1.0 / 20),
        sec(1.0 / 15), sec(1.0 / 13), sec(1.0 / 10), sec(1.0 / 8),
        sec(1.0 / 6), sec(1.0 / 5), sec(1.0 / 4), sec(0.3),
        sec(0.4), sec(0.5), sec(0.6), sec(0.8),
        sec(1.0), sec(1.3), sec(1.6), sec(2.0),
    )

    /** 白平衡：开尔文。数值是人话里的那个数，标签补一句典型光源。 */
    val wbKelvins: List<Int> = listOf(2800, 3200, 4000, 4500, 5000, 5500, 6500, 7500, 9000)

    /** AE 补偿：±3 EV，1/3 档一格 —— 与相机现成的 `evText` / `nudgeEv` 同一口径。 */
    const val EV_MIN_INDEX = -9
    const val EV_MAX_INDEX = 9

    /**
     * 手动对焦的档位：从无穷远到最近，按屈光度（1/米）。0 必须在第一个——无穷远是默认档，
     * 而"最近对焦"由设备的 `LENS_INFO_MINIMUM_FOCUS_DISTANCE` 决定，所以这条表是**按设备截断**的，
     * 不是固定长度（见 `docs/product/CAMERA-PRO.md` 的降级矩阵）。
     */
    fun focusStops(maxDiopters: Float): List<Float> {
        if (maxDiopters <= 0f) return emptyList()
        val base = listOf(0f, 0.5f, 1f, 2f, 3f, 5f, 10f)
        return base.filter { it <= maxDiopters + 1e-4f }
            .distinct()
            .sorted()
            .ifEmpty { listOf(0f) }
    }

    fun isoLabel(iso: Int): String = "ISO $iso"

    /**
     * `1/125s` / `0.5s` / `2s`。四舍五入到能读的精度，不做无意义的 0.333s。
     *
     * 表是**声明一次**的（[shutterLabels]）：`String.format` 每次调用都要现造一个
     * `java.util.Formatter`，而这一行在取景时每个拖动事件都会重拼一次——
     * 与 `PerfSourceGuardTest.formattersAreDeclaredOnce` 管的是同一件事。
     */
    fun shutterLabel(ns: Long): String = shutterLabels[ns] ?: run {
        val seconds = ns / 1_000_000_000.0
        if (seconds >= 0.4) {
            val tenths = (seconds * 10).roundToInt()
            if (tenths % 10 == 0) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
        } else {
            "1/${(1.0 / seconds).roundToInt()}s"
        }
    }

    private val shutterLabels: Map<Long, String> by lazy {
        shuttersNs.associateWith { ns ->
            val seconds = ns / 1_000_000_000.0
            if (seconds >= 0.4) {
                val tenths = (seconds * 10).roundToInt()
                if (tenths % 10 == 0) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
            } else {
                "1/${(1.0 / seconds).roundToInt()}s"
            }
        }
    }

    /**
     * `EV −1.0` / `EV 0` / `EV +0.7`。负号用 U+2212，与相机里既有的 `EV −` 格子同一写法。
     *
     * 不查表：表只覆盖 [EV_MIN_INDEX]..[EV_MAX_INDEX]，而 `ev` 是从 SharedPreferences
     * 读回来的外部数据（老版本/改过的文件都可能越界），查表会让越界值显示成「EV 0」——
     * 一句看起来没问题、实际在说谎的话。这里按值直接算，任何整数都有正确结果。
     */
    fun evLabel(index: Int): String {
        if (index == 0) return "EV 0"
        // 1/3 档一格 → 十分位。四舍五入而不是截断：EV +2/3 要写成 +0.7 而不是 +0.6。
        val tenths = (abs(index) * 10.0 / 3.0).roundToInt()
        val sign = if (index > 0) "+" else "−"
        return "EV $sign${tenths / 10}.${tenths % 10}"
    }

    fun wbLabel(kelvin: Int): String = when (kelvin) {
        in Int.MIN_VALUE..2999 -> "${kelvin}K 白炽"
        in 3000..3599 -> "${kelvin}K 钨丝"
        in 3600..4299 -> "${kelvin}K 荧光"
        in 4300..5199 -> "${kelvin}K 日光"
        in 5200..5899 -> "${kelvin}K 标准"
        in 5900..6899 -> "${kelvin}K 阴天"
        in 6900..7999 -> "${kelvin}K 阴影"
        else -> "${kelvin}K 深阴影"
    }

    /**
     * 屈光度 → 人话。0 = 无穷远；其余按"最近对焦在几米"说，这才是用户心里那个数。
     * 这里刻意不用 `String.format`：滑杆每拖动一次就调一遍（见 [shutterLabel] 的注释）。
     */
    fun focusLabel(diopters: Float): String {
        if (diopters <= 1e-4f) return "∞"
        val metersTenths = (10f / diopters).roundToInt()
        return if (metersTenths >= 10) {
            "${metersTenths / 10}m"
        } else {
            val whole = metersTenths / 10
            val frac = metersTenths % 10
            if (frac == 0) "${whole}m" else "$whole.${frac}m"
        }
    }

    private fun sec(seconds: Double): Long = Math.round(seconds * 1_000_000_000.0)
}

// ── 钳制 ────────────────────────────────────────────────────────────────

/**
 * 把用户选的值夹到**这台设备真有**的最近一档。
 *
 * 三条语义，缺一条降级矩阵就不成立：
 * 1. `caps` 里那一项是 null（设备不支持）→ 返回 null，界面置灰；
 * 2. 范围非空但表里没有任何一档落在范围内（例如 ISO 范围 60..75，表里最小 50 下一档 80）
 *    → 同样返回 null。**不许把 80 塞给一台只到 75 的设备**，那是一次 `IllegalArgumentException`；
 * 3. 范围内的档取**数值上最近**的那一档；比下限还小的选范围内最小档，比上限还大的选最大档。
 *
 * 空范围（`IntRange.EMPTY`，start > end）按"没有可用档"处理 —— 真实设备不会这么报，
 * 但它是从 SharedPreferences / 设备回调一路传进来的外部数据，这里不信任。
 */
fun clampIso(iso: Int?, caps: ProCaps): Int? {
    val range = caps.isoRange ?: return null
    if (!caps.aeOffSupported) return null
    val usable = ProTables.isos.filter { it in range }
    if (usable.isEmpty()) return null
    if (iso == null) return null
    return usable.minByOrNull { abs(it - iso) }
}

fun clampShutter(ns: Long?, caps: ProCaps): Long? {
    val range = caps.shutterNsRange ?: return null
    if (!caps.aeOffSupported) return null
    val usable = ProTables.shuttersNs.filter { it in range }
    if (usable.isEmpty()) return null
    if (ns == null) return null
    return usable.minByOrNull { abs(it - ns) }
}

/** EV 补偿：设备范围与表范围取交集，再夹到最近的下标。设备不支持就只能是 0（不置灰，那是"关着"）。 */
fun clampEv(index: Int, caps: ProCaps): Int {
    val range = caps.evRange ?: return 0
    val lo = max(range.first, ProTables.EV_MIN_INDEX)
    val hi = minOf(range.last, ProTables.EV_MAX_INDEX)
    if (lo > hi) return 0
    return index.coerceIn(lo, hi)
}

/** 白平衡取范围内最近的一档；设备没有可用档就 null。 */
fun clampWb(kelvin: Int?, caps: ProCaps): Int? {
    if (!caps.awbOffSupported) return null
    // 设备没给可用档表 = 不支持手动白平衡（不是"用我们内置的那张表试试"）。
    // 这里**不许回退到 ProTables.wbKelvins**：回退等于对一台没开放 AWB_MODE_OFF 的设备
    // 硬塞一个它不认的档，界面会亮着一颗点了没反应的格子。
    val usable = caps.wbKelvins
    if (usable.isEmpty()) return null
    if (kelvin == null) return null
    return usable.minByOrNull { abs(it - kelvin) }
}

/** 屈光度夹进 `0..maxFocusDiopters`；设备不支持手动对焦 / 没有最近对焦距离就 null。 */
fun clampFocus(diopters: Float?, caps: ProCaps): Float? {
    if (!caps.afOffSupported) return null
    if (caps.maxFocusDiopters <= 0f) return null
    val requested = diopters ?: return null
    return requested.coerceIn(0f, caps.maxFocusDiopters)
}

/**
 * 整套参数的钳制入口。**界面只许从这里出参数**，不许把自己手里的值直接递给 Camera2。
 *
 * 注意 `enabled` 原样带过去：钳制说的是"值合不合法"，开不开是另一件事。
 * PRO 关着时调用方应当直接用 [ProMode.Auto]，而不是靠这里清空
 * （见 [resetOnDisable] —— 那条是给"用户点关"这一个动作准备的）。
 */
fun clampToCapabilities(mode: ProMode, caps: ProCaps): ProMode = ProMode(
    enabled = mode.enabled,
    iso = clampIso(mode.iso, caps),
    shutterNs = clampShutter(mode.shutterNs, caps),
    ev = clampEv(mode.ev, caps),
    wbKelvin = clampWb(mode.wbKelvin, caps),
    focusDiopters = clampFocus(mode.focusDiopters, caps),
)

/**
 * 关闭 PRO 时的复位。**必须返回全 null**：残留的 `SENSOR_SENSITIVITY` 会让下一张照片
 * 在用户以为已经回到自动的时候仍然按 ISO 3200 曝光——那是"关掉了但没关掉"，
 * 比功能缺失更难查。`CameraProStateTest` 逐项钉这一条。
 */
fun resetOnDisable(): ProMode = ProMode.Auto

/** 设备此刻的置灰矩阵。每一项 `false` 都必须在面板上看得见（置灰），不许整格消失。 */
fun availability(caps: ProCaps): ProAvailability {
    val aeOk = caps.aeOffSupported && caps.isoRange != null &&
        ProTables.isos.any { it in caps.isoRange }
    val shutterOk = caps.aeOffSupported && caps.shutterNsRange != null &&
        ProTables.shuttersNs.any { it in caps.shutterNsRange }
    val wbOk = caps.awbOffSupported && caps.wbKelvins.isNotEmpty()
    val focusOk = caps.afOffSupported && caps.maxFocusDiopters > 0f
    val evOk = caps.evRange != null
    val anything = aeOk || wbOk || focusOk || evOk
    return ProAvailability(
        iso = aeOk,
        shutter = shutterOk,
        ev = evOk,
        wb = wbOk,
        focus = focusOk,
        // 一项都用不了时必须给一句人话：模拟器与部分平板就是这一支，空白面板会被当成坏了。
        reason = if (anything) "" else "这台设备的相机不提供手动参数",
    )
}

/**
 * 一行状态文字，给工具条与读屏共用（只有这一处拼这句话，`CameraProStateTest` 穷举）。
 *
 * 设计约束：PRO 关着时它必须**明确说关着**，而不是空串——空串在语义树里等于"这颗没说
 * 自己是什么"。开着但五项全自动时也要说实话（"全自动"），否则用户会以为设了 ISO。
 */
fun describe(mode: ProMode, caps: ProCaps): String {
    if (!mode.enabled) return "PRO 关"
    val parts = ArrayList<String>(6)
    mode.iso?.let { parts += ProTables.isoLabel(it) }
    mode.shutterNs?.let { parts += ProTables.shutterLabel(it) }
    if (mode.ev != 0) parts += ProTables.evLabel(mode.ev)
    mode.wbKelvin?.let { parts += ProTables.wbLabel(it) }
    mode.focusDiopters?.let { parts += "MF ${ProTables.focusLabel(it)}" }
    if (parts.isEmpty()) return "PRO 开 · 全自动"
    val avail = availability(caps)
    val tail = if (!avail.any) " · 本机不支持手动" else ""
    return "PRO · " + parts.joinToString(" · ") + tail
}

// ── 要往 CaptureRequest 里放什么 ────────────────────────────────────────

/**
 * 一次 CaptureRequest 的改动计划。**只是一张数据表**，不碰任何 Android 对象：
 * 真正 `setCaptureRequestOption` 的地方在 `CameraScreen.kt`，那里每一处都 `runCatching`。
 *
 * 为什么不让这个函数直接调 Camera2：
 * 1. 它要能被 JVM 单测逐条验（"ISO 400 + 1/125 时 AE 必须是 OFF，且两值都非空"）；
 * 2. 它要能回答"PRO 关着时计划是什么"——[Reset] 与 [None] 的区别就是"要不要清掉旧 options"。
 */
sealed class CaptureOptionPlan {
    /** 什么都不用改（PRO 关着且从来没设过）。 */
    object None : CaptureOptionPlan()

    /** 清掉所有由我们加过的 CaptureRequest options（用户点关 PRO，或相机重绑）。 */
    object Reset : CaptureOptionPlan()

    /**
     * 要写进 CaptureRequest 的一组值。null 表示"这一项不设"（保留 CameraX/自动）。
     *
     * [sensitivity] 与 [exposureNs] **要么都没有，要么都有**：Camera2 的
     * `CONTROL_AE_MODE_OFF` 一旦打开，两个都得给，只给一个会被驱动按 0 处理（黑图）。
     * 缺的那个由 [planCaptureOptions] 用设备范围的中间值补齐。
     */
    data class Options(
        val aeMode: Int?,
        val sensitivity: Int?,
        val exposureNs: Long?,
        val awbMode: Int?,
        /**
         * `COLOR_CORRECTION_MODE`：手动增益必须配 `TRANSFORM_MATRIX`，否则驱动会忽略
         * `COLOR_CORRECTION_GAINS`（自动 AWB 会盖回来，表现为"白平衡格子点了没反应"）。
         */
        val colorCorrectionMode: Int?,
        val colorGains: FloatArray?,
        val afMode: Int?,
        val focusDiopters: Float?,
        /** AE 补偿下标：只在 AE 自动时才有意义（手动曝光下它会被忽略，界面已置灰）。 */
        val evIndex: Int?,
    ) : CaptureOptionPlan() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Options) return false
            return aeMode == other.aeMode && sensitivity == other.sensitivity &&
                exposureNs == other.exposureNs && awbMode == other.awbMode &&
                colorCorrectionMode == other.colorCorrectionMode &&
                afMode == other.afMode && focusDiopters == other.focusDiopters &&
                evIndex == other.evIndex && gainsEqual(colorGains, other.colorGains)
        }

        override fun hashCode(): Int {
            var result = aeMode ?: 0
            result = 31 * result + (sensitivity ?: 0)
            result = 31 * result + (exposureNs ?: 0L).hashCode()
            result = 31 * result + (awbMode ?: 0)
            result = 31 * result + (colorCorrectionMode ?: 0)
            result = 31 * result + (afMode ?: 0)
            result = 31 * result + (focusDiopters ?: 0f).hashCode()
            result = 31 * result + (evIndex ?: 0)
            result = 31 * result + (colorGains?.contentHashCode() ?: 0)
            return result
        }

        private fun gainsEqual(a: FloatArray?, b: FloatArray?): Boolean =
            if (a == null || b == null) a == null && b == null
            else a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 1e-6f }
    }
}

/**
 * 由"用户参数 + 设备能力"算出这一帧要改什么。
 *
 * 语义全部写在这里，于是"关闭 PRO 后 CaptureRequest 里不许残留"这件事可以单测：
 * `enabled == false` 一律返回 [CaptureOptionPlan.Reset]（而不是 None）——
 * 因为 None 会让上一次 add 进去的 options 留在 session 上。
 */
fun planCaptureOptions(mode: ProMode, caps: ProCaps): CaptureOptionPlan {
    if (!mode.enabled) return CaptureOptionPlan.Reset
    val iso = clampIso(mode.iso, caps)
    val shutter = clampShutter(mode.shutterNs, caps)
    val wb = clampWb(mode.wbKelvin, caps)
    val focus = clampFocus(mode.focusDiopters, caps)
    val ev = clampEv(mode.ev, caps)

    // AE：只要用户动了 ISO 或快门，就必须整只转手动；缺的那一半用范围中间值补齐。
    val wantManualAe = iso != null || shutter != null
    val aeMode: Int?
    val sensitivity: Int?
    val exposureNs: Long?
    if (wantManualAe) {
        aeMode = Camera2Codes.CONTROL_AE_MODE_OFF
        sensitivity = iso ?: midIso(caps)
        exposureNs = shutter ?: midShutter(caps)
        // 补齐失败（设备范围里连一档都没有）就整组放弃，退回自动 —— 宁可自动，也不要黑图。
        if (sensitivity == null || exposureNs == null) {
            return CaptureOptionPlan.Options(
                aeMode = Camera2Codes.CONTROL_AE_MODE_ON,
                sensitivity = null,
                exposureNs = null,
                awbMode = if (wb != null) Camera2Codes.CONTROL_AWB_MODE_OFF else null,
                colorCorrectionMode = if (wb != null) {
                    Camera2Codes.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                } else {
                    null
                },
                colorGains = wb?.let { kelvinToGains(it) },
                afMode = if (focus != null) Camera2Codes.CONTROL_AF_MODE_OFF else null,
                focusDiopters = focus,
                evIndex = ev.takeIf { it != 0 },
            )
        }
    } else {
        aeMode = null
        sensitivity = null
        exposureNs = null
    }

    return CaptureOptionPlan.Options(
        aeMode = aeMode,
        sensitivity = sensitivity,
        exposureNs = exposureNs,
        awbMode = if (wb != null) Camera2Codes.CONTROL_AWB_MODE_OFF else null,
        colorCorrectionMode = if (wb != null) {
            Camera2Codes.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
        } else {
            null
        },
        colorGains = wb?.let { kelvinToGains(it) },
        afMode = if (focus != null) Camera2Codes.CONTROL_AF_MODE_OFF else null,
        focusDiopters = focus,
        // EV 只有在 AE 自动时才谈得上；手动曝光下它是一条会误导人的死格子。
        evIndex = if (aeMode == Camera2Codes.CONTROL_AE_MODE_OFF) null else ev.takeIf { it != 0 },
    )
}

/** 设备 ISO 范围的中间档（AE 转手动时补另一半用）。取表里落在范围内的中位那一档。 */
internal fun midIso(caps: ProCaps): Int? {
    val range = caps.isoRange ?: return null
    val usable = ProTables.isos.filter { it in range }
    return usable.getOrNull(usable.size / 2)
}

internal fun midShutter(caps: ProCaps): Long? {
    val range = caps.shutterNsRange ?: return null
    val usable = ProTables.shuttersNs.filter { it in range }
    // 中位偏"手稳"的那一侧：宁可慢一档，也不要因为太慢糊片——所以取后半段的前一个。
    return usable.getOrNull(usable.size / 2)
}

/**
 * Y 平面 → 64 桶亮度直方图。**纯算术**，JVM 里能用假字节数组测。
 *
 * 采样策略是性能预算的核心（见 `docs/product/CAMERA-PRO.md` §5）：
 * 每 [step] 个像素取一个，640×480 的 YUV 帧只读约 1/64 的点（≈4800 个），
 * 单核几十微秒；**不做任何缩放/滤波**，因为直方图只需要分布形状。
 *
 * @param rowStride Y 平面一行的字节跨度（可能大于 width，解引用时必须按它跳行）
 * @param pixelStride 同一行相邻像素的跨度（YUV_420_888 的 Y 平面几乎总是 1）
 */
fun lumaHistogram(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int = 1,
    bins: Int = 64,
    step: Int = 8,
): IntArray {
    val hist = IntArray(bins.coerceAtLeast(1))
    if (width <= 0 || height <= 0 || y.isEmpty()) return hist
    val safeBins = hist.size
    val safeRow = if (rowStride > 0) rowStride else width * pixelStride
    val safePixel = if (pixelStride > 0) pixelStride else 1
    val safeStep = if (step > 0) step else 1
    var py = 0
    while (py < height) {
        val rowBase = py * safeRow
        var px = 0
        while (px < width) {
            val idx = rowBase + px * safePixel
            if (idx < y.size) {
                val v = y[idx].toInt() and 0xFF
                // 255 * bins / 256：让第 255 档落在最后一桶，不会越界也不会丢最亮那一档。
                hist[(v * safeBins) shr 8]++
            }
            px += safeStep
        }
        py += safeStep
    }
    return hist
}

/** 直方图归一化成 0..1 的高度比例（界面只关心形状）。空直方图回空数组。 */
fun normalizeHistogram(hist: IntArray): FloatArray {
    val peak = hist.maxOrNull() ?: 0
    if (peak <= 0) return FloatArray(0)
    return FloatArray(hist.size) { hist[it].toFloat() / peak.toFloat() }
}

/**
 * 直方图跑不起来时的那句话：**一个失败点一句话**，四个输入组合互不相同。
 *
 * 为什么抽成纯函数而不是在 `bind()` 里写 `when`：这一句以前是"本地写一句、随后又被
 * 另一段重算覆盖"的。三路流绑失败时 `analysis` 对象仍然非空，所以按 `frameSource == null`
 * 重算会落到 `else -> ""`，把那句人话擦成空串 —— 界面于是亮着「直方图 开」却什么都不画，
 * 也没有一句解释（P0-2）。把"哪种失败"收成三个布尔交给这里判，调用点就只剩"传事实"。
 *
 * 优先级是刻意的：执行器已经停（相机页要退了）是最准确的状态，压过其它两条。
 *
 * @param executorShutdown 分析线程池已经 shutdown（相机页退出）
 * @param analysisBuilt `ImageAnalysis.Builder()…build()` 是否真的造出了对象
 * @param threeStreamBound Preview + ImageCapture + ImageAnalysis 三路是否一起绑成功
 */
fun histogramNoteFor(
    executorShutdown: Boolean,
    analysisBuilt: Boolean,
    threeStreamBound: Boolean,
): String = when {
    executorShutdown -> "直方图已停"
    // 造不出分析流与"造得出但排不下"是两件事：给用户的那句话必须指出是哪一步不行，
    // 否则换个设备/换个 CameraX 版本之后，日志里的 note 分不清该怪哪一层。
    !analysisBuilt -> "这台设备的相机建不出直方图要用的分析流"
    !threeStreamBound -> "这台设备的相机排不出直方图用的第三条流"
    else -> ""
}

// ── 拍完回读：请求值 vs 实际值 ──────────────────────────────────────────

/**
 * 把刚存那张 JPEG 回读到的 ISO / 快门与**我们请求的值**比对，不一致时说一句实话。
 *
 * 为什么需要它：P0-1 那个 bug（关了 PRO 却仍按 ISO 1600 曝光）在界面上完全看不出来——
 * 「PRO 关」四个字可以是真的，session 上的 `SENSOR_SENSITIVITY` 却是假的。
 * EXIF 里那张照片**实际**用的 ISO / 快门，是模拟器上少数几个能直接判定"计划有没有真下发"的证据来源。
 *
 * 三条"量不到就不说"的纪律：
 * 1. 没请求过手动曝光（PRO 关 / 只有 EV 或白平衡）→ 空串，别拿自动档的数字去和 null 比；
 * 2. 照片里两个标签都读不到（很多设备不写 ISO）→ 空串，**不许猜**；
 * 3. 只对**量得到**的那一项下结论；一项一致、另一项量不到时不说话。
 *
 * 容差是 1/3 档（≈1.26×，取 1.3 留点余量）：HAL 会把 ISO / 快门量化到最近的 1/3 档，
 * 逐位相等是过严的判据——请求 1/125 得到 1/100 是正常的，报成"不一致"比不报更糟。
 * 差 1 档（×2）就一定会被抓到，而那正是"计划根本没生效"的形状。
 *
 * 返回形如 `请求 ISO 400 · 实际 800`，由调用点拼在状态行后面。
 */
fun exposureMismatchNote(
    requestedIso: Int?,
    requestedShutterNs: Long?,
    actualIso: Int?,
    actualShutterNs: Long?,
): String {
    if (requestedIso == null && requestedShutterNs == null) return ""
    if (actualIso == null && actualShutterNs == null) return ""
    val isoOff = requestedIso != null && actualIso != null &&
        !withinSameThirdStop(requestedIso.toLong(), actualIso.toLong())
    val shutterOff = requestedShutterNs != null && actualShutterNs != null &&
        !withinSameThirdStop(requestedShutterNs, actualShutterNs)
    if (!isoOff && !shutterOff) return ""
    val req = ArrayList<String>(2)
    val act = ArrayList<String>(2)
    if (isoOff) {
        req += ProTables.isoLabel(requestedIso!!)
        // 只写数字：`请求 ISO 400 · 实际 800` 读起来才是一句话，不是两组标签。
        act += "$actualIso"
    }
    if (shutterOff) {
        req += ProTables.shutterLabel(requestedShutterNs!!)
        act += ProTables.shutterLabel(actualShutterNs!!)
    }
    return "请求 ${req.joinToString(" ")} · 实际 ${act.joinToString(" ")}"
}

/** 1/3 档以内的差异算"同一档"（HAL 的量化误差），见 [exposureMismatchNote]。 */
private fun withinSameThirdStop(a: Long, b: Long): Boolean {
    if (a == b) return true
    val lo = minOf(abs(a), abs(b)).toDouble()
    val hi = maxOf(abs(a), abs(b)).toDouble()
    if (lo <= 0.0) return false
    // 按比值而不是差值：曝光是一档一档乘 2 的，
    // 差 100 在 ISO 50 上是两档、在 ISO 6400 上什么都不是。
    return hi / lo <= 1.3
}

/**
 * PRO 参数的落盘。**只有这一个地方读写这些键**。
 *
 * 为什么参数也落盘：专业模式使用者最不能接受的是"每次重开都回到自动"——
 * 那等于每一轮都要重新设一遍 ISO 与快门。代价是"上次停在 ISO 6400"会带到下一次，
 * 所以（a）关 PRO 时立刻写回自动值，（b）工具条那一行永远把当前值念出来（[describe]）。
 *
 * 落盘格式用哨兵值而不是 `remove()`：`-1` / `-1L` / `-1f` 都不会与真实档位撞
 * （表里最小的 ISO 是 50、快门是正的纳秒、屈光度 ≥ 0），
 * 于是"自动"是一个可以被读回来的状态，不是"这个键不存在"这种隐式状态。
 */
object CameraProStore {
    private const val KEY_ON = "cam_pro_on"
    private const val KEY_ISO = "cam_pro_iso"
    private const val KEY_SHUTTER = "cam_pro_shutter"
    private const val KEY_EV = "cam_pro_ev"
    private const val KEY_WB = "cam_pro_wb"
    private const val KEY_FOCUS = "cam_pro_focus"

    fun load(prefs: SharedPreferences): ProMode = ProMode(
        enabled = prefs.getBoolean(KEY_ON, false),
        iso = prefs.getInt(KEY_ISO, -1).takeIf { it > 0 },
        shutterNs = prefs.getLong(KEY_SHUTTER, -1L).takeIf { it > 0L },
        ev = prefs.getInt(KEY_EV, 0),
        wbKelvin = prefs.getInt(KEY_WB, -1).takeIf { it > 0 },
        focusDiopters = prefs.getFloat(KEY_FOCUS, -1f).takeIf { it >= 0f },
    )

    fun save(prefs: SharedPreferences, mode: ProMode) {
        prefs.edit()
            .putBoolean(KEY_ON, mode.enabled)
            .putInt(KEY_ISO, mode.iso ?: -1)
            .putLong(KEY_SHUTTER, mode.shutterNs ?: -1L)
            .putInt(KEY_EV, mode.ev)
            .putInt(KEY_WB, mode.wbKelvin ?: -1)
            .putFloat(KEY_FOCUS, mode.focusDiopters ?: -1f)
            .apply()
    }
}

/**
 * 开尔文 → `RggbChannelVector` 用的三个增益。
 *
 * 用 Tanner Helland 的经典近似（黑体辐射 → sRGB 白点），再按 G 归一化：
 * 这是相机 App 里最常见的做法，误差在几百 K 量级——足够"把餐厅的橙光掰回中性"，
 * 而且它是**纯算术**，能单测（低色温 R>G>B，高色温 B>G>R，任何输入都有限且为正）。
 *
 * 不做色彩矩阵/双光源校正：那是 `COLOR_CORRECTION_TRANSFORM` 的活，需要 `CameraCharacteristics`
 * 里的 `SENSOR_COLOR_TRANSFORM_*` 矩阵，超出这一轮的范围（见 `docs/product/CAMERA-PRO.md` §4）。
 */
fun kelvinToGains(kelvin: Int): FloatArray {
    val t = kelvin.coerceIn(1000, 40000) / 100.0
    val r: Double
    val g: Double
    val b: Double
    if (t <= 66) {
        r = 255.0
        g = 99.4708025861 * Math.log(t) - 161.1195681661
        b = if (t <= 19) 0.0 else 138.5177312231 * Math.log(t - 10) - 305.0447927307
    } else {
        r = 329.698727446 * Math.pow(t - 60, -0.1332047592)
        g = 288.1221695283 * Math.pow(t - 60, -0.0755148492)
        b = 255.0
    }
    val rr = (r.coerceIn(1.0, 255.0) / 255.0).toFloat()
    val gg = (g.coerceIn(1.0, 255.0) / 255.0).toFloat()
    val bb = (b.coerceIn(1.0, 255.0) / 255.0).toFloat()
    // 按 G 归一化，再夹到 [0.25, 4] —— 有的驱动对极端增益直接报 ILLEGAL_ARGUMENT。
    val norm = if (gg > 1e-4f) gg else 1f
    return floatArrayOf(
        (rr / norm).coerceIn(0.25f, 4f),
        1f,
        (bb / norm).coerceIn(0.25f, 4f),
    )
}
