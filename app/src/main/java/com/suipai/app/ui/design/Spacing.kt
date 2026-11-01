package com.suipai.app.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 间距 / 圆角 / 触摸目标：设计系统 v1 的尺寸刻度。
 *
 * 这套刻度不是审美偏好，是从「暗房接触印样」这个世界推出来的：
 * 印样页的排布是**密排**（相纸之间只有一道 2dp 的缝），而印样页与页眉、
 * 页脚之间是**留白**。所以刻度必须同时容得下 2dp 与 32dp 两个极端，
 * 中间按 8dp 的倍数走 —— 这是印刷行当最稳的一套栅格。
 *
 * 约定（写新界面时按这个顺序取，不许现编数值）：
 *   - 元素内部：xxs(4) / xs(8) / sm(12)
 *   - 元素之间：sm(12) / md(16)
 *   - 分块之间：lg(20) / xl(24) / xxl(32)
 *   - 页面留边：md(16) 或 lg(20)（左右一致，不许左右不同）
 */

/** 间距阶梯。数值单调递增，单测 `ScaleGuardTest` 钉住这一点。 */
object Spacing {

    /** 发丝线宽度（1dp 框线 / 分隔线）。 */
    val hair: Dp = 1.dp

    /**
     * 相纸之间的缝。全 app 只有一处该用 2dp：时间线网格的 `gutter`。
     * 印样是**几乎贴边**的密排，缝一旦加到 8dp，一页印样就散成了卡片墙。
     */
    val gutter: Dp = 2.dp

    /** 最小间距：紧邻的图标与文字、格子内部的左右内边距。 */
    val xxs: Dp = 4.dp

    /** 8dp 基准的第一步：同一组内的控件之间。 */
    val xs: Dp = 8.dp

    val sm: Dp = 12.dp

    /** 默认页面留边（左右一致）。 */
    val md: Dp = 16.dp

    /** 弹层内容的左右留边。 */
    val lg: Dp = 20.dp

    val xl: Dp = 24.dp

    /** 分块与分块之间。 */
    val xxl: Dp = 32.dp

    /** 空态、首启页这类「整屏只有一件事」的页面：四周留白。 */
    val xxxl: Dp = 48.dp

    /** 阶梯本身：从紧到松。单测按这个顺序检查单调递增。 */
    val ladder: List<Dp> = listOf(xxs, xs, sm, md, lg, xl, xxl, xxxl)

    /** 页面留边的两档。 */
    val pageEdge: List<Dp> = listOf(md, lg)
}

/**
 * 圆角阶梯。
 *
 * 这个世界只有**三档**圆角，少到不需要选：
 *   · [none] 0dp —— 照片本身、印样网格的格子（相纸是方的）；
 *   · [frame] 2dp —— 本世界的签名圆角（视觉契约：近直角）；
 *   · [panel] 8dp —— 弹层顶角（它是"被掀起来的一页纸"，顶角稍微圆一点才不像贴纸）；
 *   · [pill] —— 只给筛选芯片这种小控件。
 *
 * 特意**没有** 4dp / 12dp / 16dp 这几档：中间档是"随便挑一个差不多"的温床，
 * 而"到处都差一点点"正是这套界面之前失去质感的原因。
 */
object Radius {

    /** 真正的直角：印样网格的格子、照片本身。 */
    val none: Dp = 0.dp

    /** 近直角 2dp：本世界的签名圆角 —— 格子、按钮、芯片的描边、输入框。 */
    val frame: Dp = 2.dp

    /** 弹层顶角 / 大面板。 */
    val panel: Dp = 8.dp

    /** 胶囊：只给筛选芯片这类「小控件」，永远不许用在按钮或卡片上。 */
    val pill: Dp = 50.dp

    /** 阶梯本身（严格递增，单测钉住）。 */
    val ladder: List<Dp> = listOf(none, frame, panel, pill)
}

/**
 * 触摸目标尺寸。
 *
 * 48dp 是装机能点中的下限（AppBar.kt 里量过：38dp 的框拇指点不中，命中区放宽到 48dp
 * 之后零视觉变化）；52dp 是顶部工具条那一行的高度上限（视觉契约要求 ≤64dp）；
 * 32dp 是筛选芯片的**视觉**高度 —— 它的命中区仍然必须补到 48dp，
 * 否则一排小芯片会变成一整片「点不准」。
 */
object Touch {

    /** 无障碍最小可点边长。任何可点东西的命中区都不许小于它。 */
    val min: Dp = 48.dp

    /** 拇指舒适区（右下角的主操作、快门）。 */
    val comfortable: Dp = 56.dp

    /** 顶部工具条一行的高度上限。 */
    val toolRow: Dp = 52.dp

    /** 主按钮的视觉高度。 */
    val button: Dp = 48.dp

    /** 筛选芯片的视觉高度（命中区另行补到 min）。 */
    val chip: Dp = 32.dp

    /** 指纹般的细线：描边、分隔线。 */
    val hairline: Dp = 1.dp

    /** 选中框线宽（油笔圈选的粗度）。 */
    val selection: Dp = 2.dp
}
