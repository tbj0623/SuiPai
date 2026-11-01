package com.suipai.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * 色彩 token：暗房接触印样的两套底（深色 = 暗房，浅色 = 日光印样）。
 *
 * 这个世界只有三支颜料：
 *   · 暗房底 #101413（深灰绿，不是纯黑，也不是暖米白）
 *   · 相纸白 #EDEBE4（比纯白暖一档，像相纸的乳白涂层）
 *   · 琥珀 safelight #E8A33D（暗房灯的唯一强调色）
 * 定影红 #C4453B 是第四支，但它**只用于危险**，不算强调色。
 *
 * 浅色主题不是把深色反色：它是「同一页印样被拿到日光下看」——
 * 纸白 #F2EFE8、墨字 #1A1E1C、琥珀在日光下必须压深到 #855416 才读得清。
 *
 * ── 为什么 token 是 `ColorToken(argb: Long)` 而不是直接 `Color(...)` ──
 * 守卫测试（`token/TokenGuardTest`）要能在**纯 JVM** 上核对两件事：
 * 两套主题的键集合完全一致、每一对「字/底」的 WCAG 对比度达标。
 * 如果 token 直接就是 Compose 的 `Color`，守卫就得把 Compose 运行时拖进 JVM 单测；
 * 而 `Color` 的 ARGB 是内联的 ULong，读数还得解码。
 * 所以这里分成两层：**注册表**（名字 + ARGB 长整数，纯 Kotlin）与
 * **颜色对象**（`SuiPaiColors`，Compose 类型，只在界面层用）。
 * 加 token 只能加在注册表里，两套主题各加一次 —— 少加一边，守卫立刻红。
 */

/**
 * 一支颜色的登记项：`name` 是它在两套主题里的**同一个键**，`argb` 是 0xAARRGGBB。
 *
 * `name` 必须两套主题逐字相同，这是"成对"的定义。
 */
data class ColorToken(val name: String, val argb: Long) {

    /** 转成 Compose 的颜色。 */
    val color: Color get() = Color(argb.toInt())

    /** 便于报告：`#RRGGBB`（忽略 alpha，文档与失败信息都这么写）。 */
    val hex: String get() = "#%06X".format(argb and 0xFFFFFFL)
}

/** 暗房（深色，默认）的 token 注册表。 */
internal object DarkRoomTokens {

    // ── 面（从深到浅：越靠前越"远"，越靠后越"浮起"） ──
    /** 页面底：深灰绿暗房。 */
    val canvas = ColorToken("canvas", 0xFF101413)

    /** 最深的一层：查看器的相纸外沿、相机遮罩。 */
    val canvasSunken = ColorToken("canvasSunken", 0xFF0A0D0C)

    /** 面板：弹层、抽屉。 */
    val panel = ColorToken("panel", 0xFF161B1A)

    /** 浮起面：面板上的容器、芯片底。 */
    val panelRaised = ColorToken("panelRaised", 0xFF1E2422)

    /** 叠在面板上的又一层：展开的分组、按下的行。 */
    val panelOverlay = ColorToken("panelOverlay", 0xFF202624)

    /** 选中容器：被油笔圈住的那一格底（琥珀洗色偏纸）。 */
    val selectionSurface = ColorToken("selectionSurface", 0xFF2B291E)

    // ── 字（相纸白的三档，都带一点绿灰倾向，不用纯灰） ──
    /** 主文字：相纸白。 */
    val ink = ColorToken("ink", 0xFFEDEBE4)

    /** 次级文字：说明、副标题。 */
    val inkSecondary = ColorToken("inkSecondary", 0xFF9AA39E)

    /** 三级文字：编号、元数据、脚注。比次级再暗一档但仍 ≥4.5:1。 */
    val inkTertiary = ColorToken("inkTertiary", 0xFF929B96)

    /** 反相面（浅色块）上的字。 */
    val inkInverse = ColorToken("inkInverse", 0xFF101413)

    // ── 线 ──
    /** 发丝线：**纯装饰**的分隔（不承担可点性指示）。 */
    val hairline = ColorToken("hairline", 0xFF2A312E)

    /** 交互描边：格子的框。它承担"这一格能点"，所以对面板必须 ≥3:1。 */
    val outline = ColorToken("outline", 0xFF6B7570)

    // ── 琥珀 safelight（唯一强调色） ──
    /** 主强调：描边、图标、强调文字、主按钮底。 */
    val safelight = ColorToken("safelight", 0xFFE8A33D)

    /** 按下的琥珀（比主色深一档）。 */
    val safelightPressed = ColorToken("safelightPressed", 0xFFD08F2E)

    /** 琥珀洗色容器：徽标底、提示条底。 */
    val safelightWash = ColorToken("safelightWash", 0xFF3B2A0E)

    /** 画在琥珀填充上的字（主按钮文字）。 */
    val inkOnSafelight = ColorToken("inkOnSafelight", 0xFF101413)

    /** 画在琥珀洗色上的字。 */
    val inkOnSafelightWash = ColorToken("inkOnSafelightWash", 0xFFE8A33D)

    /** 油笔圈选：印样上圈照片的那支笔。**只抄 safelight 的值，不是第二套强调色**。 */
    val grease = ColorToken("grease", 0xFFE8A33D)

    // ── 定影红 fixer（危险，不是强调） ──
    /** 定影红本体：**只作装饰**（危险区的边、图标底），文字与可读信息不用它。 */
    val fixer = ColorToken("fixer", 0xFFC4453B)

    /** 危险文字/图标：提亮过的定影红，对暗房底 7.2:1。 */
    val fixerInk = ColorToken("fixerInk", 0xFFE5887E)

    /** 危险洗色容器。 */
    val fixerWash = ColorToken("fixerWash", 0xFF3A1512)

    /** 画在危险洗色上的字。 */
    val inkOnFixerWash = ColorToken("inkOnFixerWash", 0xFFE58D83)

    /** 危险填充按钮（删除）：纸白字的对比度 4.9:1。 */
    val dangerFill = ColorToken("dangerFill", 0xFFB33A31)

    /** 画在危险填充上的字。 */
    val inkOnDangerFill = ColorToken("inkOnDangerFill", 0xFFEDEBE4)

    // ── 胶片漏光（唯一的渐变语汇） ──
    /** 漏光起点：琥珀，带 alpha。 */
    val leakWarm = ColorToken("leakWarm", 0x2EE8A33D)

    /** 漏光终点：同色全透明。 */
    val leakEdge = ColorToken("leakEdge", 0x00E8A33D)

    // ── 遮罩 ──
    /** 弹层背后的压暗。 */
    val scrim = ColorToken("scrim", 0xB3000000)

    /** 全部 token，顺序即文档顺序。守卫按这张表核对"两套成对"。 */
    val all: List<ColorToken> = listOf(
        canvas, canvasSunken, panel, panelRaised, panelOverlay, selectionSurface,
        ink, inkSecondary, inkTertiary, inkInverse,
        hairline, outline,
        safelight, safelightPressed, safelightWash, inkOnSafelight, inkOnSafelightWash, grease,
        fixer, fixerInk, fixerWash, inkOnFixerWash, dangerFill, inkOnDangerFill,
        leakWarm, leakEdge,
        scrim,
    )
}

/** 日光印样（浅色）的 token 注册表：键名与 [DarkRoomTokens] 逐个对齐。 */
internal object DaylightTokens {

    // ── 面 ──
    /** 页面底：日光下的纸白。 */
    val canvas = ColorToken("canvas", 0xFFF2EFE8)

    /** 最深的一层：查看器外沿、区块底。 */
    val canvasSunken = ColorToken("canvasSunken", 0xFFE7E3D9)

    /** 面板：弹层、抽屉。 */
    val panel = ColorToken("panel", 0xFFFAF8F3)

    /** 浮起面：芯片底、卡面。 */
    val panelRaised = ColorToken("panelRaised", 0xFFFFFFFF)

    /** 叠在面板上的又一层。 */
    val panelOverlay = ColorToken("panelOverlay", 0xFFEFEBE2)

    /** 选中容器：被油笔圈住的那一格底。 */
    val selectionSurface = ColorToken("selectionSurface", 0xFFEFE8DD)

    // ── 字（墨黑三档，偏绿灰） ──
    /** 主文字：墨。 */
    val ink = ColorToken("ink", 0xFF1A1E1C)

    /** 次级文字。 */
    val inkSecondary = ColorToken("inkSecondary", 0xFF5C625E)

    /** 三级文字。 */
    val inkTertiary = ColorToken("inkTertiary", 0xFF5B615B)

    /** 反相面（深色块）上的字。 */
    val inkInverse = ColorToken("inkInverse", 0xFFF2EFE8)

    // ── 线 ──
    /** 发丝线：装饰性分隔。 */
    val hairline = ColorToken("hairline", 0xFFD8D4C9)

    /** 交互描边：对纸白与纯白都 ≥3:1。 */
    val outline = ColorToken("outline", 0xFF8A857A)

    // ── 琥珀 safelight（日光下压深，否则纸白上读不清） ──
    /** 主强调。 */
    val safelight = ColorToken("safelight", 0xFF855416)

    /** 按下的琥珀。 */
    val safelightPressed = ColorToken("safelightPressed", 0xFF6E4512)

    /** 琥珀洗色容器。 */
    val safelightWash = ColorToken("safelightWash", 0xFFEBD9B8)

    /** 画在琥珀填充上的字（主按钮文字）。 */
    val inkOnSafelight = ColorToken("inkOnSafelight", 0xFFFAF8F3)

    /** 画在琥珀洗色上的字。 */
    val inkOnSafelightWash = ColorToken("inkOnSafelightWash", 0xFF4A3413)

    /** 油笔圈选：与 safelight 同值。 */
    val grease = ColorToken("grease", 0xFF855416)

    // ── 定影红（日光下压深一档才够 4.5:1） ──
    /** 定影红本体：装饰用。 */
    val fixer = ColorToken("fixer", 0xFFA83028)

    /** 危险文字/图标。 */
    val fixerInk = ColorToken("fixerInk", 0xFF9E2E26)

    /** 危险洗色容器。 */
    val fixerWash = ColorToken("fixerWash", 0xFFF6DAD7)

    /** 画在危险洗色上的字。 */
    val inkOnFixerWash = ColorToken("inkOnFixerWash", 0xFF8A231C)

    /** 危险填充按钮。 */
    val dangerFill = ColorToken("dangerFill", 0xFFA83028)

    /** 画在危险填充上的字。 */
    val inkOnDangerFill = ColorToken("inkOnDangerFill", 0xFFFAF8F3)

    // ── 胶片漏光 ──
    /** 漏光起点：日光下的琥珀，更淡。 */
    val leakWarm = ColorToken("leakWarm", 0x1FA96F16)

    /** 漏光终点。 */
    val leakEdge = ColorToken("leakEdge", 0x00A96F16)

    // ── 遮罩 ──
    /** 弹层背后的压暗。 */
    val scrim = ColorToken("scrim", 0x66000000)

    /** 全部 token，顺序必须与 [DarkRoomTokens.all] 一致。 */
    val all: List<ColorToken> = listOf(
        canvas, canvasSunken, panel, panelRaised, panelOverlay, selectionSurface,
        ink, inkSecondary, inkTertiary, inkInverse,
        hairline, outline,
        safelight, safelightPressed, safelightWash, inkOnSafelight, inkOnSafelightWash, grease,
        fixer, fixerInk, fixerWash, inkOnFixerWash, dangerFill, inkOnDangerFill,
        leakWarm, leakEdge,
        scrim,
    )
}

/**
 * 一套主题的全部色彩 token（Compose 类型）。
 *
 * 字段顺序 = 使用频率：先取面（canvas/panel），再取字（ink/inkSecondary），
 * 再取强调（safelight），危险与渐变最后。逐字段与注册表一一对应，
 * 值一律从注册表取 —— **这里不许出现字面量颜色**（守卫会扫）。
 */
data class SuiPaiColors(
    // 面
    val canvas: Color,
    val canvasSunken: Color,
    val panel: Color,
    val panelRaised: Color,
    val panelOverlay: Color,
    val selectionSurface: Color,
    // 字
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val inkInverse: Color,
    // 线
    val hairline: Color,
    val outline: Color,
    // 琥珀
    val safelight: Color,
    val safelightPressed: Color,
    val safelightWash: Color,
    val inkOnSafelight: Color,
    val inkOnSafelightWash: Color,
    val grease: Color,
    // 定影红
    val fixer: Color,
    val fixerInk: Color,
    val fixerWash: Color,
    val inkOnFixerWash: Color,
    val dangerFill: Color,
    val inkOnDangerFill: Color,
    // 漏光
    val leakWarm: Color,
    val leakEdge: Color,
    // 遮罩
    val scrim: Color,
) {

    /**
     * 漏光的线性渐变：从左上角一道暖光斜着淡出。
     *
     * 只在**没有照片的地方**用（空态、加载占位、批量进度的底）。
     * 照片后面永远不用 —— 那会让画面变成一张加了滤镜的假照片。
     */
    fun leakBrush(): Brush = Brush.linearGradient(colors = listOf(leakWarm, leakEdge))

    /** 反相面（选中的胶囊、深色提示条）。 */
    val inverseSurface: Color get() = ink

    /** 反相面上的字。 */
    val inverseInk: Color get() = inkInverse

    /**
     * 这一套 token 的「名字 → 值」表，**顺序与注册表一致**。
     *
     * 为什么不让守卫用反射数 `SuiPaiColors` 的字段：`Color` 是 value class，
     * 它的字段是内联的 ULong，Kotlin 反射拿不到 `Color` 类型的字段
     * （`declaredFields` 数是 0 —— 第一版守卫就是这么空跑的）。
     * 所以这里显式列一遍：**加 token 必须同时加进这里**，否则守卫会红。
     */
    fun asMap(): Map<String, Color> = linkedMapOf(
        "canvas" to canvas,
        "canvasSunken" to canvasSunken,
        "panel" to panel,
        "panelRaised" to panelRaised,
        "panelOverlay" to panelOverlay,
        "selectionSurface" to selectionSurface,
        "ink" to ink,
        "inkSecondary" to inkSecondary,
        "inkTertiary" to inkTertiary,
        "inkInverse" to inkInverse,
        "hairline" to hairline,
        "outline" to outline,
        "safelight" to safelight,
        "safelightPressed" to safelightPressed,
        "safelightWash" to safelightWash,
        "inkOnSafelight" to inkOnSafelight,
        "inkOnSafelightWash" to inkOnSafelightWash,
        "grease" to grease,
        "fixer" to fixer,
        "fixerInk" to fixerInk,
        "fixerWash" to fixerWash,
        "inkOnFixerWash" to inkOnFixerWash,
        "dangerFill" to dangerFill,
        "inkOnDangerFill" to inkOnDangerFill,
        "leakWarm" to leakWarm,
        "leakEdge" to leakEdge,
        "scrim" to scrim,
    )
}

/** 深色那一套（暗房）。 */
val DarkRoomColors: SuiPaiColors = SuiPaiColors(
    canvas = DarkRoomTokens.canvas.color,
    canvasSunken = DarkRoomTokens.canvasSunken.color,
    panel = DarkRoomTokens.panel.color,
    panelRaised = DarkRoomTokens.panelRaised.color,
    panelOverlay = DarkRoomTokens.panelOverlay.color,
    selectionSurface = DarkRoomTokens.selectionSurface.color,
    ink = DarkRoomTokens.ink.color,
    inkSecondary = DarkRoomTokens.inkSecondary.color,
    inkTertiary = DarkRoomTokens.inkTertiary.color,
    inkInverse = DarkRoomTokens.inkInverse.color,
    hairline = DarkRoomTokens.hairline.color,
    outline = DarkRoomTokens.outline.color,
    safelight = DarkRoomTokens.safelight.color,
    safelightPressed = DarkRoomTokens.safelightPressed.color,
    safelightWash = DarkRoomTokens.safelightWash.color,
    inkOnSafelight = DarkRoomTokens.inkOnSafelight.color,
    inkOnSafelightWash = DarkRoomTokens.inkOnSafelightWash.color,
    grease = DarkRoomTokens.grease.color,
    fixer = DarkRoomTokens.fixer.color,
    fixerInk = DarkRoomTokens.fixerInk.color,
    fixerWash = DarkRoomTokens.fixerWash.color,
    inkOnFixerWash = DarkRoomTokens.inkOnFixerWash.color,
    dangerFill = DarkRoomTokens.dangerFill.color,
    inkOnDangerFill = DarkRoomTokens.inkOnDangerFill.color,
    leakWarm = DarkRoomTokens.leakWarm.color,
    leakEdge = DarkRoomTokens.leakEdge.color,
    scrim = DarkRoomTokens.scrim.color,
)

/** 浅色那一套（日光印样）。 */
val DaylightColors: SuiPaiColors = SuiPaiColors(
    canvas = DaylightTokens.canvas.color,
    canvasSunken = DaylightTokens.canvasSunken.color,
    panel = DaylightTokens.panel.color,
    panelRaised = DaylightTokens.panelRaised.color,
    panelOverlay = DaylightTokens.panelOverlay.color,
    selectionSurface = DaylightTokens.selectionSurface.color,
    ink = DaylightTokens.ink.color,
    inkSecondary = DaylightTokens.inkSecondary.color,
    inkTertiary = DaylightTokens.inkTertiary.color,
    inkInverse = DaylightTokens.inkInverse.color,
    hairline = DaylightTokens.hairline.color,
    outline = DaylightTokens.outline.color,
    safelight = DaylightTokens.safelight.color,
    safelightPressed = DaylightTokens.safelightPressed.color,
    safelightWash = DaylightTokens.safelightWash.color,
    inkOnSafelight = DaylightTokens.inkOnSafelight.color,
    inkOnSafelightWash = DaylightTokens.inkOnSafelightWash.color,
    grease = DaylightTokens.grease.color,
    fixer = DaylightTokens.fixer.color,
    fixerInk = DaylightTokens.fixerInk.color,
    fixerWash = DaylightTokens.fixerWash.color,
    inkOnFixerWash = DaylightTokens.inkOnFixerWash.color,
    dangerFill = DaylightTokens.dangerFill.color,
    inkOnDangerFill = DaylightTokens.inkOnDangerFill.color,
    leakWarm = DaylightTokens.leakWarm.color,
    leakEdge = DaylightTokens.leakEdge.color,
    scrim = DaylightTokens.scrim.color,
)

/**
 * 当前主题的色彩 token。
 *
 * 用 `staticCompositionLocalOf`：一套主题的 token 在整次组合里**不会变**
 * （翻暗/亮是重建整棵树，不是逐帧改值），所以静态表读起来零开销。
 *
 * 取值入口是 [SuiPaiColor.current]；组件里不要直接碰 `LocalSuiPaiColors`。
 */
val LocalSuiPaiColors = staticCompositionLocalOf { DarkRoomColors }

/** 便捷取值：[SuiPaiColor.current] 就是"当前这一套 token"。 */
object SuiPaiColor {
    val current: SuiPaiColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSuiPaiColors.current
}

/** 圆角 token → Shape。组件里直接取，别自己写 RoundedCornerShape。 */
object SuiPaiShapes {

    /** 相纸/格子：近直角。 */
    val frame = RoundedCornerShape(Radius.frame)

    /** 小控件（与 frame 同档：本世界没有 4dp 的软角）。 */
    val soft = RoundedCornerShape(Radius.frame)

    /** 弹层顶角。 */
    val panel = RoundedCornerShape(Radius.panel)

    /** 胶囊（只给筛选芯片）。 */
    val pill = RoundedCornerShape(Radius.pill)

    /** 弹层：只圆上面两个角 —— 它是一页被掀起来的纸，底边还贴在屏幕上。 */
    val sheet = RoundedCornerShape(topStart = Radius.panel, topEnd = Radius.panel)

    /** 输入框：近直角，与格子同族。 */
    val field = RoundedCornerShape(Radius.frame)
}
