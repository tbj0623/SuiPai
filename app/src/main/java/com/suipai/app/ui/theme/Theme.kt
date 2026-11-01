package com.suipai.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suipai.app.settings.FontScale
import com.suipai.app.settings.LegacyKeys
import com.suipai.app.settings.SettingsBridge
import com.suipai.app.settings.SettingsKeys
import com.suipai.app.settings.SettingsSignal
import com.suipai.app.settings.SettingsStore
import com.suipai.app.settings.ThemeMode
import com.suipai.app.settings.storedChoiceName
import com.suipai.app.ui.design.DarkRoomColors
import com.suipai.app.ui.design.DaylightColors
import com.suipai.app.ui.design.LocalSuiPaiColors
import com.suipai.app.ui.design.SuiPaiColors
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType

/*
 * 视觉世界：暗房接触印样（Contact Sheet）。
 * 相片是相纸，界面是印样页的框线与编号：深灰绿暗房底、相纸白文字、
 * 琥珀 safelight 唯一强调色、等宽编号、近直角 2dp 圆角。
 *
 * ── 这一版（设计系统 v1）改了什么 ──
 * 以前这个文件**就是**设计系统：17 个 private val 颜色 + 3 个 TextStyle，
 * 和 MaterialTheme 直接对接。于是"哪个颜色算强调色""行高该多少""间距用几 dp"
 * 全靠每个界面自己猜，最后长成四十个一模一样的 2dp 方框。
 *
 * 现在 token 全在 `ui/design/`，这个文件只做三件事：
 *   1. 把两套 token 映射到 Material 的完整 colorScheme（这是唯一允许做映射的地方）；
 *   2. 把字级、圆角挂到 MaterialTheme 上；
 *   3. 保持 `SuiPaiSheet.colors / type / frame / pill / gutter` 这些**既有 API 不变**
 *      —— 六个源码守卫测试与十几个界面文件按名字引用它们。
 *
 * 新代码请直接用 `com.suipai.app.ui.design.*`；`SuiPaiSheet` 保留给还没迁移的界面。
 */

/**
 * 印样语言的结构色：框线、油笔圈选、编号。
 *
 * **保留的老 API**（`SuiPaiSheet.colors` 的类型）。字段只有三个，因为它是为
 * "还在用老接口的界面"准备的**最小集**；完整的一套在 [SuiPaiColors]。
 */
data class ContactSheetColors(
    /** 片基边缘 1dp 框线。 */
    val hairline: Color,
    /** 油笔圈选（选中框）。 */
    val grease: Color,
    /** 片框编号 / 元数据。 */
    val number: Color,
)

/**
 * 印样排版：标题用系统黑体重磅；编号/计数/日期/参数一律等宽（印样语义）。
 *
 * **保留的老 API**（`SuiPaiSheet.type` 的类型）。三档之外的字级在 [SuiPaiType]。
 */
data class ContactSheetType(
    /** 页首。 */
    val pageHead: TextStyle,
    /** 片框编号（1/3、日期、参数）。 */
    val frameNo: TextStyle,
    /** 小标签。 */
    val label: TextStyle,
)

private val LocalSheetColors = staticCompositionLocalOf {
    ContactSheetColors(
        hairline = DarkRoomColors.hairline,
        grease = DarkRoomColors.safelight,
        number = DarkRoomColors.inkSecondary,
    )
}

private val LocalSheetType = staticCompositionLocalOf {
    ContactSheetType(
        pageHead = SuiPaiType.headlineMedium,
        frameNo = SuiPaiType.frameNo,
        label = SuiPaiType.labelMedium,
    )
}

/** 从完整 token 里抽出老的三个结构色。 */
internal fun SuiPaiColors.toContactSheetColors(): ContactSheetColors = ContactSheetColors(
    hairline = hairline,
    grease = grease,
    number = inkSecondary,
)

/** 老的三档字级：一律取自新的字级系统，不另写数值。 */
internal fun contactSheetType(): ContactSheetType = ContactSheetType(
    pageHead = SuiPaiType.headlineMedium,
    frameNo = SuiPaiType.frameNo,
    label = SuiPaiType.labelMedium,
)

/**
 * 深色（暗房）的 Material 色板。
 *
 * 这一版把**所有槽位都填满**：以前没填的那十几个槽位（surfaceContainerLow、
 * surfaceContainerLowest、onSurfaceInverse、primaryFixed…）由 Material 按默认算法推导，
 * 而它的推导是围绕品牌紫调算的 —— 在暗房底上会冒出一层淡紫。
 * 现在逐个按 token 给出，Material 组件（ModalBottomSheet / NavigationBar / Slider…）
 * 才和自绘的格子是同一套纸。
 */
private fun darkScheme(c: SuiPaiColors): ColorScheme = darkColorScheme(
    // 强调：琥珀。Material 的 primary 就是本世界的唯一强调色。
    primary = c.safelight,
    onPrimary = c.inkOnSafelight,
    primaryContainer = c.safelightWash,
    onPrimaryContainer = c.inkOnSafelightWash,
    inversePrimary = c.safelight,
    // 次级：相纸白。Material 的 secondary 用途很窄（少数组件的强调），
    // 这里给相纸白而不是第二支彩色 —— 不许出现第二个强调色。
    secondary = c.ink,
    onSecondary = c.canvas,
    secondaryContainer = c.panelRaised,
    onSecondaryContainer = c.ink,
    // 三级：同为琥珀（Material 需要它，但我们没有第三支颜料）。
    tertiary = c.safelight,
    onTertiary = c.inkOnSafelight,
    tertiaryContainer = c.safelightWash,
    onTertiaryContainer = c.inkOnSafelightWash,
    // 底与面
    background = c.canvas,
    onBackground = c.ink,
    surface = c.panel,
    onSurface = c.ink,
    surfaceVariant = c.panelRaised,
    onSurfaceVariant = c.inkSecondary,
    surfaceTint = c.safelight,
    surfaceBright = c.panelRaised,
    surfaceDim = c.canvasSunken,
    surfaceContainerLowest = c.canvasSunken,
    surfaceContainerLow = c.panel,
    surfaceContainer = c.panel,
    surfaceContainerHigh = c.panelRaised,
    surfaceContainerHighest = c.panelOverlay,
    // 反相面（Snackbar / 选中的芯片）
    inverseSurface = c.panelRaised,
    inverseOnSurface = c.ink,
    // 线
    outline = c.outline,
    outlineVariant = c.hairline,
    // 危险：红字用提亮过的 fixerInk（对底 7.2:1），
    // 而不是 fixer 本体（3.8:1，画成小字会读不清）。
    error = c.fixerInk,
    onError = c.canvas,
    errorContainer = c.fixerWash,
    onErrorContainer = c.inkOnFixerWash,
    // 遮罩
    scrim = c.scrim,
)

/**
 * 浅色（日光印样）的 Material 色板。
 *
 * 注意这不是反色：纸底 #F2EFE8、墨字 #1A1E1C，琥珀被压深到 #855416
 * （原色 #E8A33D 在白纸上只有 1.9:1，任何尺寸都不合格）。
 */
private fun lightScheme(c: SuiPaiColors): ColorScheme = lightColorScheme(
    primary = c.safelight,
    onPrimary = c.inkOnSafelight,
    primaryContainer = c.safelightWash,
    onPrimaryContainer = c.inkOnSafelightWash,
    inversePrimary = c.safelight,
    secondary = c.ink,
    onSecondary = c.panel,
    secondaryContainer = c.panelOverlay,
    onSecondaryContainer = c.ink,
    tertiary = c.safelight,
    onTertiary = c.inkOnSafelight,
    tertiaryContainer = c.safelightWash,
    onTertiaryContainer = c.inkOnSafelightWash,
    background = c.canvas,
    onBackground = c.ink,
    surface = c.panel,
    onSurface = c.ink,
    surfaceVariant = c.panelRaised,
    onSurfaceVariant = c.inkSecondary,
    surfaceTint = c.safelight,
    surfaceBright = c.panelRaised,
    surfaceDim = c.canvasSunken,
    surfaceContainerLowest = c.panelRaised,
    surfaceContainerLow = c.panel,
    surfaceContainer = c.panel,
    surfaceContainerHigh = c.panelOverlay,
    surfaceContainerHighest = c.canvasSunken,
    inverseSurface = c.ink,
    inverseOnSurface = c.inkInverse,
    outline = c.outline,
    outlineVariant = c.hairline,
    error = c.fixerInk,
    onError = c.panelRaised,
    errorContainer = c.fixerWash,
    onErrorContainer = c.inkOnFixerWash,
    scrim = c.scrim,
)

/**
 * 老接口的容器：`SuiPaiSheet.colors / type / frame / pill / gutter`。
 *
 * 这五个成员是**契约**（六个源码守卫测试 + 十几个界面按名字引用），不许改名、
 * 不许改类型、不许改语义。新代码请用 `ui/design/` 里的完整 token 与组件。
 */
object SuiPaiSheet {
    /** 老的结构色（hairline / grease / number）。完整色板见 `SuiPaiColor.current`。 */
    val colors: ContactSheetColors
        @Composable get() = LocalSheetColors.current

    /** 老的三档字级。完整字级见 `SuiPaiType`。 */
    val type: ContactSheetType
        @Composable get() = LocalSheetType.current

    /** 印样框：近直角。 */
    val frame = RoundedCornerShape(2.dp)

    /** 小控件圆角（pills 只用于小控件）。 */
    val pill = RoundedCornerShape(50)

    /** 网格间距：印样页的贴边密排。 */
    val gutter = 2.dp
}

@Composable
fun SuiPaiTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    // ── 主题与字号：设置中心那两项的**唯一**读数点 ──
    //
    // 这一层在导航宿主之上（`MainActivity` 直接包住 `AppNavHost`），所以设置页改完之后
    // 没有任何东西会自动让它重来一次 —— 除非它自己订阅点什么。这里订阅的是
    // `SettingsSignal.revision`（写设置时 +1，见 `settings/SettingsSignal.kt`），
    // 于是「选了日光 → 整屏立刻变纸白」在**同一个会话里**成立，不用等下次冷启动。
    val context = LocalContext.current
    val revision = SettingsSignal.revision
    val prefs = remember(context) {
        context.getSharedPreferences(SettingsStore.PREFS_FILE, android.content.Context.MODE_PRIVATE)
    }
    val storedTheme = remember(prefs, revision) {
        prefs.storedChoiceName(SettingsKeys.Appearance.themeMode)
    }
    val storedFont = remember(prefs, revision) {
        prefs.storedChoiceName(SettingsKeys.Appearance.fontScale)
    }
    val mode = SettingsBridge.themeModeOf(storedTheme, legacyDarkUi = dark)
    val effectiveDark = when (mode) {
        ThemeMode.DARKROOM -> true
        ThemeMode.DAYLIGHT -> false
        // 「跟随系统」暂时等价于「沿用页眉那颗 DARKROOM / DAYLIGHT 开关的当前状态」。
        // 为什么不去读 `uiMode`：那颗开关写的是 `dark_ui`，而它住在 `state/ChromeState.kt` 里
        // （本轮写作用域之外）。按 `uiMode` 走会让那颗开关变成"点了没反应" ——
        // 那正是这一轮要消灭的形状。要真正跟随系统，得先把 ChromeState.darkUi 改成
        // 从 `cfg_theme_mode` 派生（写进了 docs/product/SETTINGS.md 的待接线表）。
        ThemeMode.FOLLOW_SYSTEM -> dark
    }
    // 反向：页眉那颗开关拨了之后，把设置页那一项写成同一个选择。
    // 不写的话，用户在设置里选了日光之后再去拨页眉那颗，屏幕不会变 —— 又是"点了没反应"。
    // 首次组合只记下初值（`lastDark == null`），不写盘：安装完还没拨过任何东西时不该凭空落一条设置。
    var lastDark by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(dark) {
        val previous = lastDark
        lastDark = dark
        if (previous != null && previous != dark) {
            SettingsStore(prefs).syncFromLegacyBool(LegacyKeys.DARK_UI, dark)
        }
    }

    val colors = if (effectiveDark) DarkRoomColors else DaylightColors
    val scheme = if (effectiveDark) darkScheme(colors) else lightScheme(colors)
    // 字号：**叠在**系统字级之上（乘 `LocalDensity.fontScale`，不覆盖它）。
    // 系统已经放大很多时，这里选「标准」也不会把它缩回去 —— 这正是文档承诺的口径。
    val base = LocalDensity.current
    val factor = remember(storedFont) {
        storedFont?.let { name -> FontScale.entries.firstOrNull { it.name == name } }
            ?.let { SettingsBridge.fontScaleFactor(it) }
            ?: 1f
    }
    val density = remember(base.density, base.fontScale, factor) {
        Density(base.density, base.fontScale * factor)
    }
    // 一套主题对象要建一次 Material 的 colorScheme（~48 个 Color）与三份老结构色。
    // 唯一的输入就是 dark：记住它，翻暗/亮才重建。
    val sheet = remember(effectiveDark) {
        Pair(colors.toContactSheetColors(), contactSheetType())
    }
    val sheetColors = sheet.first
    val sheetType = sheet.second
    CompositionLocalProvider(
        LocalSuiPaiColors provides colors,
        LocalSheetColors provides sheetColors,
        LocalSheetType provides sheetType,
        LocalDensity provides density,
        // 兜底正文色：Compose 在没有 Surface 的子树里会把 LocalContentColor 留成 Unspecified，
        // Text 于是回退到**黑色**——权限门就是这么在暗房底上写出黑字的（实测截图里标题和正文几乎看不见）。
        // 更深处的 Surface / Scaffold 仍然会覆盖这一层，所以这里只是补漏，不改任何已有页面的观感。
        LocalContentColor provides scheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SuiPaiType.material,
            shapes = MaterialTheme.shapes.copy(
                extraSmall = SuiPaiShapes.frame,
                small = SuiPaiShapes.frame,
                medium = SuiPaiShapes.soft,
                large = SuiPaiShapes.panel,
                extraLarge = SuiPaiShapes.panel,
            ),
            content = content,
        )
    }
}

/**
 * 相机浮层的配色环境：预览是一块随时在变的暗表面，所以这里固定用暗房那一套，
 * 不跟随「日光印样」——纸白底的格子压在实时画面上等于给自己造眩光。
 * 只换颜色与结构色，不引入任何布局层级。
 */
@Composable
fun CameraOverlay(content: @Composable () -> Unit) {
    val colors = DarkRoomColors
    CompositionLocalProvider(
        LocalSuiPaiColors provides colors,
        LocalSheetColors provides colors.toContactSheetColors(),
        LocalSheetType provides contactSheetType(),
        LocalContentColor provides darkScheme(colors).onBackground,
    ) {
        MaterialTheme(
            colorScheme = darkScheme(colors),
            typography = SuiPaiType.material,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
