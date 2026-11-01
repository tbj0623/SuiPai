package com.suipai.app.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/*
 * 全应用共用的一套动作刻度（设计系统 v1）。
 *
 * 规格来源：`.qoder/handoffs/motion-spec.md` §1，加上本轮"层级"改造后
 * 新增的两档（圈选、漏光）。旧文件 `ui/common/Motion.kt` 的常量已迁到这里，
 * 并以 @Deprecated 别名保留，**行为逐毫秒不变**——只是换了个家。
 *
 * 这几档不是审美选择，是从装机实测推出来的：
 * 90 锚在 Coil 实测的 100 ms（按压必须比"图换好了"更快，且要在手指**还按着**时出现，
 * 一次轻点按住 70-120 ms；50 看不出来，120 会在松手之后还在动）；
 * 160/120 是画廊位置横带今天用的那一对；每档约是上一档 ×1.7，
 * 与 Material 的短表（50/100/150/200）故意错开，免得两档听起来一样。
 *
 * 这一轮新增的两档遵守同一条规则：
 * - [Circle] 220 ms：油笔圈选落下 —— 比"进场"慢一档，因为它是一个**有份量**的动作
 *   （印样上圈一张照片，笔要落稳）；但它必须在用户下一次点击前完成，
 *   220 是 160×1.37，仍在"一个手势之内"。
 * - [Leak] 420 ms：漏光/空态渐变的一次性呼吸，只用于**没有照片的空地方**。
 *   这一档不许挂在可交互控件上 —— 它比一次点击慢太多。
 */
object MotionScale {

    /** 手指按住的这一下：可见尺寸 ≤60 dp、命中区 48 dp 的那几颗（快门、主按钮、格子）。 */
    const val Press = 90

    /** 一件东西进场：横带、查看器页眉、peek 遮罩、选中框、结论句。 */
    const val Enter = 160

    /** 同一件东西退场：比进场快，退出方要尽早让位。 */
    const val Exit = 120

    /** 只换颜色不换形状的状态（`on` 的琥珀框、DARKROOM 格子、tab 图标色）。 */
    const val State = 120

    /** 只用于**图**的替换（一瞬成片预览、缩略图）。文字走这条会出鬼影。 */
    const val Replace = 160

    /**
     * 整屏替换。今天**没有调用点**：规格 §4b 列了四条阻断
     * （两块全屏网格同时活着、`YishunScreen` 的 bitmap 被 `Canvas` 用到已回收的那张、
     * 音量键监听挂在同一个 Android View 上、`targetState` 必须是分支枚举而不是 `yishunUri`），
     * 落地形态是 `fadeIn(Enter) togetherWith fadeOut(0)` —— 即退出方同帧卸载。
     * 数值上限来自"退出方必须同帧卸载"这条约束，不是审美。
     */
    const val Screen = 260

    /** 油笔圈选落下：选中框从"描边"变成"圈住"的那一下。 */
    const val Circle = 220

    /** 漏光/空态渐变的呼吸。**不许**挂在可交互控件上。 */
    const val Leak = 420

    /** 进场/状态用。正好等于 Compose `tween` 的默认曲线，所以现有调用点无需改缓动。 */
    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 退场用：让出去的时候比进场的时候快。 */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * 按压用的曲线：先快后慢地"压下去"。
     * 与 [Standard] 的区别是起手更软 —— 压在手指下面的东西不该有冲刺感。
     */
    val PressEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 圈选用的曲线：落笔时略微**过冲**再回稳（0.34, 1.4, 0.64, 1）。
     * 高于 1 的控制点会让值越过终点再回来 —— 这正是油笔圈上去的那一下。
     */
    val CircleEasing: Easing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

    /**
     * 全部**不同的**时长值，严格递增。
     *
     * 注意这里列的是"刻度"，不是"常量"：`Exit` 与 `State` 都是 120、
     * `Enter` 与 `Replace` 都是 160 —— 那是**同一个刻度被两个语义共用**，
     * 不是两档。守卫按这张表核对"没有乱编的数"与"没有重复刻度"，
     * 而每一条常量都必须能落进这张表里（`MotionScaleGuardTest` 钉住）。
     */
    val durations: List<Int> = listOf(Press, State, Enter, Circle, Screen, Leak).distinct().sorted()

    /** 刻度 → 语义（同一个刻度下可以挂多个语义，用 `·` 并列）。 */
    val meaning: Map<Int, String> = mapOf(
        Press to "按压：手指还按着的时候就要看得见",
        State to "状态换色不换形 · 退场让位（Exit）",
        Enter to "进场：横带、页眉、遮罩、结论句 · 只换图（Replace）",
        Circle to "油笔圈选落下",
        Screen to "整屏替换（今天没有调用点）",
        Leak to "漏光/空态呼吸（不许挂在可交互控件上）",
    )

    /**
     * 每一条具名常量 → 它落在哪个刻度上。
     *
     * 守卫用它核对"每个常量都必须是刻度表里的某一档"，以及
     * "不许新增一个不在刻度表里的数"。这本子是全套动效的账。
     */
    val ledger: Map<String, Int> = mapOf(
        "Press" to Press,
        "Exit" to Exit,
        "State" to State,
        "Enter" to Enter,
        "Replace" to Replace,
        "Circle" to Circle,
        "Screen" to Screen,
        "Leak" to Leak,
    )
}

/**
 * 用户把"系统动画时长"调到多长，本仓的动作就该跟着多长。
 *
 * **只提供函数，本轮不接任何调用点** —— 这条判据还没装机验证过：
 * Compose 的 `tween`/`spring` 是否已经自己读了 `Settings.Global.ANIMATOR_DURATION_SCALE`，
 * 规格里标的是 [inferred]（未证实）。如果平台已经处理了，这里再乘一次就是双重缩放，
 * 用户关动画时我们仍然在动 —— 那比不做更糟。
 *
 * 接线之前必须先量一次：把开发者选项里的"动画时长缩放"设成 0x 与 2x，
 * 装机对比横带淡入的实际毫秒数。
 */
@Composable
fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }
}

/** 给读屏/无障碍用：用户是否关掉了动画。 */
@Composable
fun rememberAnimationsEnabled(): Boolean = rememberMotionScale() > 0f

/**
 * 「减弱动效」这一项的读数点：设置页拨了它，动效时长立刻按这个系数缩。
 *
 * 返回 0f 表示**这一屏不该动**（调用点据此把 `tween(ms * factor)` 变成 0），
 * 返回 1f 表示按原刻度动。为什么不直接返回毫秒：调用点各自用哪一档是它自己的语义决定，
 * 这里只提供"缩多少"，才不会再出现第二份"动效时长表"。
 *
 * ── 与系统的关系 ──
 *
 * **只乘自己这一档，不与系统的「动画时长缩放」叠加**。文档里的口径是"减弱动效不与系统
 * 移除动画叠加"，而 [rememberMotionScale] 读的正是系统那一档；两者相乘会出现
 * "系统 2x × 这里 0.5 = 1x"这种谁也解释不了的结果。系统关动画那一侧由平台自己处理
 * （`ANIMATOR_DURATION_SCALE = 0` 时 Compose 的 `tween` 本来就不跑）。
 *
 * ⚠️ 这条判据**没有装机验证过**（与 [rememberMotionScale] 头注释里记的是同一条未证实的
 * 假设：Compose 是否已经自己读了 `ANIMATOR_DURATION_SCALE`）。所以这里只接"减弱动效"
 * 这一个**用户显式拨的**开关 —— 它即使平台也处理了一遍，方向也一致（都是变短），
 * 不会出现"用户关了我们还在动"那种反向错误。
 */
@Composable
fun rememberMotionFactor(): Float {
    val context = LocalContext.current
    val revision = com.suipai.app.settings.SettingsSignal.revision
    return remember(context, revision) {
        val prefs = context.getSharedPreferences(
            com.suipai.app.settings.SettingsStore.PREFS_FILE,
            android.content.Context.MODE_PRIVATE,
        )
        if (com.suipai.app.settings.SettingsStore(prefs)
                .read(com.suipai.app.settings.SettingsKeys.Appearance.reduceMotion)
        ) {
            0f
        } else {
            1f
        }
    }
}
