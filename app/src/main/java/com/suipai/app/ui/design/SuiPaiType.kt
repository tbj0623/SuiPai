package com.suipai.app.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/*
 * 字级系统：暗房接触印样只有两种声音。
 *
 * 1. **正文声** —— 系统黑体。标题用 Black/Bold，正文用 Regular/Medium。
 *    这是印样页上「用马克笔写的页眉」与「印刷体说明」。
 * 2. **编号声** —— 等宽。只用于编号、计数、日期、时间、参数、比例。
 *    这是印样片框下方的曝光参数（f/2.8 · 1/125 · ISO 400），
 *    也是本世界最可辨认的一层。**绝不作正文**：一段中文用等宽写，
 *    读起来像终端，而不像印样页。
 *
 * 中文行高：所有字级的行高倍数都 ≥ 1.32（大字）/ 1.45（正文）。
 * 这一条不是排版洁癖 —— 中文没有西文的上下伸部（ascender/descender）撑着，
 * 1.2 倍行高的中文段落会糊成一块黑。
 *
 * 数值全部集中在 `TypeScale.specs` 里，**不在** `SuiPaiType` 的构造里现写。
 * 原因：守卫测试要在纯 JVM（没有 Android 运行时）上核对行高倍数与字号阶梯，
 * 而 `TextStyle` 是 Compose 类型。规格与样式分离之后，守卫读规格、界面用样式，
 * 两边看的是同一份数字，不会各写各的。
 */

/**
 * 一条字级规格：名字 + 字号（sp）+ 行高倍数 + 字重名 + 是否等宽 + 字距（sp）。
 *
 * 纯 Kotlin data class，不依赖任何 Compose 类型 —— 守卫因此能在 JVM 单测里直接跑。
 */
data class TypeSpec(
    val name: String,
    val sizeSp: Float,
    val lineHeightRatio: Float,
    val weight: String,
    val mono: Boolean,
    val trackingSp: Float,
) {
    /** 行高（sp），由字号乘倍数得到 —— 界面与守卫用的是同一个算法。 */
    val lineHeightSp: Float get() = sizeSp * lineHeightRatio
}

/**
 * 全套字级规格。**加字级只能加在这里**，且必须加进 `SuiPaiType.allBody` / `allMono`，
 * 否则守卫看不到它（守卫按这张表穷举核对）。
 */
object TypeScale {

    /** 中文正文的最小行高倍数。 */
    const val BodyLineHeight: Float = 1.45f

    /** 大字（display/headline）的行高倍数：字越大行距相对越紧，否则整块会散开。 */
    const val DisplayLineHeight: Float = 1.32f

    /** 正文段落（body 族）的行高倍数：长段落要更松。 */
    const val ParagraphLineHeight: Float = 1.6f

    /** 等宽编号的行高倍数：编号是单行信息，不需要段落那么松。 */
    const val MonoLineHeight: Float = 1.45f

    private val bodySpecs: List<TypeSpec> = listOf(
        // ── Display：整屏只有一个的地方（首启页、空态主标题、相机倒计时） ──
        TypeSpec("displayLarge", 48f, DisplayLineHeight, "Black", mono = false, trackingSp = 0f),
        TypeSpec("displayMedium", 36f, DisplayLineHeight, "Black", mono = false, trackingSp = 0f),
        TypeSpec("displaySmall", 30f, DisplayLineHeight, "Black", mono = false, trackingSp = 0f),
        // ── Headline：页面主标题（"照片"、相册名） ──
        TypeSpec("headlineLarge", 26f, DisplayLineHeight, "Black", mono = false, trackingSp = 0f),
        TypeSpec("headlineMedium", 22f, 1.4f, "Black", mono = false, trackingSp = 0f),
        TypeSpec("headlineSmall", 19f, 1.4f, "Bold", mono = false, trackingSp = 0f),
        // ── Title：弹层标题、分组标题 ──
        TypeSpec("titleLarge", 20f, BodyLineHeight, "Bold", mono = false, trackingSp = 0f),
        TypeSpec("titleMedium", 17f, 1.5f, "Bold", mono = false, trackingSp = 0f),
        TypeSpec("titleSmall", 15f, 1.5f, "Bold", mono = false, trackingSp = 0f),
        // ── Body：正文 ──
        TypeSpec("bodyLarge", 16f, ParagraphLineHeight, "Normal", mono = false, trackingSp = 0f),
        TypeSpec("bodyMedium", 14f, ParagraphLineHeight, "Normal", mono = false, trackingSp = 0f),
        TypeSpec("bodySmall", 12f, ParagraphLineHeight, "Normal", mono = false, trackingSp = 0f),
        // ── Label：按钮、芯片、角标 ──
        TypeSpec("labelLarge", 14f, BodyLineHeight, "Bold", mono = false, trackingSp = 0f),
        TypeSpec("labelMedium", 12f, BodyLineHeight, "Medium", mono = false, trackingSp = 0f),
        TypeSpec("labelSmall", 11f, BodyLineHeight, "Medium", mono = false, trackingSp = 0f),
    )

    /**
     * 层级族，**从重到轻**：display → headline → title → body → label。
     *
     * 这是字号阶梯真正的结构性承诺，比"整张表单调递增"准确得多 ——
     * Material 的标题族（title）与正文族（body）在尺寸上是**交叉**的
     * （titleSmall 15 比 bodySmall 12 大，但整表看是 20,17,15,16,14,12 —— 非单调）。
     * 真正的规则是**同一档位下逐族递减**：
     *
     * ```
     *          Large   Medium  Small
     * display    48      36      30
     * headline   26      22      19
     * title      20      17      15
     * body       16      14      12
     * label      14      12      11
     * ```
     *
     * 每一列都严格递减，于是"这个字比那个字重要"在任何一档系统字级下都成立。
     * 守卫（`TypeScaleGuardTest`）按这张矩阵核对。
     */
    val hierarchy: List<List<String>> = listOf(
        listOf("displayLarge", "displayMedium", "displaySmall"),
        listOf("headlineLarge", "headlineMedium", "headlineSmall"),
        listOf("titleLarge", "titleMedium", "titleSmall"),
        listOf("bodyLarge", "bodyMedium", "bodySmall"),
        listOf("labelLarge", "labelMedium", "labelSmall"),
    )

    /** 等宽编号族（编号声）。只用于数字/日期/参数，绝不作正文。 */
    private val monoSpecs: List<TypeSpec> = listOf(
        /** 片框编号：`41 / 90`、`1/3`、`1920×1440`。 */
        TypeSpec("frameNo", 11f, MonoLineHeight, "Medium", mono = true, trackingSp = 0.6f),
        /** 日期与时间：`2026.09.21 20:54`。 */
        TypeSpec("stamp", 12f, MonoLineHeight, "Medium", mono = true, trackingSp = 0.5f),
        /** 参数值：`48%`、`f/1.8`、`ISO 400` —— 印样下方那行曝光参数。 */
        TypeSpec("parameter", 13f, MonoLineHeight, "Medium", mono = true, trackingSp = 0.3f),
        /** 大字计数值（ValueWall 用）：`5446`、`90`。 */
        TypeSpec("counter", 28f, MonoLineHeight, "Bold", mono = true, trackingSp = 0f),
        /** 等宽小标记：工具条上的英文标号（`DARKROOM`）。 */
        TypeSpec("marker", 11f, MonoLineHeight, "Bold", mono = true, trackingSp = 1.2f),
    )

    /** 正文声的规格表（守卫按它穷举）。 */
    val body: List<TypeSpec> = bodySpecs

    /** 编号声的规格表（守卫按它穷举）。 */
    val mono: List<TypeSpec> = monoSpecs

    /** 全部规格。 */
    val all: List<TypeSpec> = bodySpecs + monoSpecs
}

/**
 * 中文字形的墨迹中心比西文低，`LineHeightStyle.Trim` 会切掉大半行距；
 * 而 `Center` 让每一行在自己的行框里垂直居中，中文段落看起来才不"贴顶"。
 */
private val ChineseLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun FontWeight.named(weight: String): FontWeight = when (weight) {
    "Black" -> FontWeight.Black
    "ExtraBold" -> FontWeight.ExtraBold
    "Bold" -> FontWeight.Bold
    "SemiBold" -> FontWeight.SemiBold
    "Medium" -> FontWeight.Medium
    "Light" -> FontWeight.Light
    else -> FontWeight.Normal
}

/** 把一条规格变成 Compose 的 TextStyle。字号、行高、字距都从规格取，不现写数字。 */
internal fun TypeSpec.toTextStyle(): TextStyle = TextStyle(
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    fontWeight = FontWeight.Normal.named(weight),
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = trackingSp.sp,
    lineHeightStyle = ChineseLineHeightStyle,
)

/**
 * 完整字级系统。
 *
 * 正文声沿用 Material 的槽位名（display/headline/title/body/label）——
 * `MaterialTheme.typography.xxx` 与 `SuiPaiType.xxx` 是同一套东西，
 * 不会出现「两套字级各写各的」。
 *
 * 编号声不进 Material 的槽位表（Material 没有"编号"这个概念），
 * 挂在 `SuiPaiSheet.type` 上按语义取用。
 */
object SuiPaiType {

    // ── 正文声 ──
    val displayLarge: TextStyle = TypeScale.body.first { it.name == "displayLarge" }.toTextStyle()
    val displayMedium: TextStyle = TypeScale.body.first { it.name == "displayMedium" }.toTextStyle()
    val displaySmall: TextStyle = TypeScale.body.first { it.name == "displaySmall" }.toTextStyle()

    val headlineLarge: TextStyle = TypeScale.body.first { it.name == "headlineLarge" }.toTextStyle()
    val headlineMedium: TextStyle = TypeScale.body.first { it.name == "headlineMedium" }.toTextStyle()
    val headlineSmall: TextStyle = TypeScale.body.first { it.name == "headlineSmall" }.toTextStyle()

    val titleLarge: TextStyle = TypeScale.body.first { it.name == "titleLarge" }.toTextStyle()
    val titleMedium: TextStyle = TypeScale.body.first { it.name == "titleMedium" }.toTextStyle()
    val titleSmall: TextStyle = TypeScale.body.first { it.name == "titleSmall" }.toTextStyle()

    val bodyLarge: TextStyle = TypeScale.body.first { it.name == "bodyLarge" }.toTextStyle()
    val bodyMedium: TextStyle = TypeScale.body.first { it.name == "bodyMedium" }.toTextStyle()
    val bodySmall: TextStyle = TypeScale.body.first { it.name == "bodySmall" }.toTextStyle()

    val labelLarge: TextStyle = TypeScale.body.first { it.name == "labelLarge" }.toTextStyle()
    val labelMedium: TextStyle = TypeScale.body.first { it.name == "labelMedium" }.toTextStyle()
    val labelSmall: TextStyle = TypeScale.body.first { it.name == "labelSmall" }.toTextStyle()

    // ── 编号声（等宽家族） ──
    val frameNo: TextStyle = TypeScale.mono.first { it.name == "frameNo" }.toTextStyle()
    val stamp: TextStyle = TypeScale.mono.first { it.name == "stamp" }.toTextStyle()
    val parameter: TextStyle = TypeScale.mono.first { it.name == "parameter" }.toTextStyle()
    val counter: TextStyle = TypeScale.mono.first { it.name == "counter" }.toTextStyle()
    val marker: TextStyle = TypeScale.mono.first { it.name == "marker" }.toTextStyle()

    /** 交给 MaterialTheme 的那一份。 */
    val material: Typography = Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}
