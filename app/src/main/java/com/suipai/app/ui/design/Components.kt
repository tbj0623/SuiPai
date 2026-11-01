package com.suipai.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * 设计系统 v1 的组件层。
 *
 * ── 这一版要解决的问题：层级扁平 ──
 * 旧界面上工具条格子、抽屉格子、预设芯片、空态按钮**全是同一种 2dp 方框**。
 * 后果不只是"不好看"：
 *   · 照片不再是主角 —— 屏幕上最有力的形状是四十个一模一样的框；
 *   · 用户点哪儿都一样，因为看起来哪儿都一样（主操作没有比次操作更显眼）；
 *   · 危险操作（删除）与普通操作长得一样，只能靠读字分辨。
 *
 * 所以这一层按**语义角色**分形态，而不是按"能点什么"分：
 *
 *   角色          形态                        组件
 *   ────────────  ──────────────────────────  ──────────────────────
 *   主操作        琥珀实心，近直角            PrimaryButton
 *   次操作        无底、琥珀字（像签名）      TextButtonTonal
 *   第三级动作    1dp 描边格子（工具条用）    AppBarCell（旧，保留）
 *   筛选          胶囊、可多选                PillChip
 *   危险          定影红实心 / 红字           PrimaryButton(danger = true)
 *   导航          底栏 tab / 页首              不在本文件
 *
 * 所有组件依然守在同一个世界里：框线、编号、琥珀、近直角。
 * 圆角只有 `SuiPaiShapes.frame`（2dp）与 `pill`（只给 PillChip）。
 */

/**
 * 弹层/抽屉的页眉：标题 + 右侧收起。
 *
 * 与旧的 `AppSheet` 内联页眉的区别：这里把**标题的层级**与**关闭动作**分开了 ——
 * 标题用 `titleLarge` + Black（本世界的"马克笔页眉"），关闭是一个真正的文字按钮
 * （带 role 与 onClickLabel，读屏能念出来）。
 *
 * 用法：
 * ```
 * Column {
 *     SheetHeader(title = "筛选", onDismiss = { sheet = 0 })
 *     ...
 * }
 * ```
 */
@Composable
fun SheetHeader(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标题右侧的一行等宽副信息（例如"90 FRAMES"）。 */
    note: String? = null,
    /** 收起按钮的文案。相机等场景里希望写成"关闭"。 */
    dismissLabel: String = "收起",
    content: @Composable RowScope.() -> Unit = {},
) {
    val colors = SuiPaiColor.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                title,
                style = SuiPaiType.titleLarge,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (note != null) {
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    note,
                    style = SuiPaiType.marker,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
            Spacer(Modifier.width(Spacing.xxs))
            // 命中区 48dp、视觉只有一行字：与 AppBarCell 同一套"外层管命中、内层管画"的结构。
            Box(
                modifier = Modifier
                    .heightIn(min = Touch.min)
                    .clip(SuiPaiShapes.frame)
                    .pressable(
                        onClickLabel = "收起这个弹层",
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .padding(horizontal = Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dismissLabel,
                    style = SuiPaiType.labelMedium,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
    Divider(inset = Spacing.lg)
}

/**
 * 分组标题：弹层里的一段。
 *
 * 与标题的层级差别靠**尺寸 + 颜色 + 上方留白**三件事，而不是再加一个框：
 * 分组标题是"印样页上的小字注记"，它比正文重、比页眉轻。
 *
 * @param accent 是否用琥珀（只在"这一段是当前操作焦点"时用，全屏至多一处）。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    accent: Boolean = false,
) {
    val colors = SuiPaiColor.current
    Column(modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.xxs)) {
        Text(
            title,
            style = SuiPaiType.labelLarge,
            // 分组标题用琥珀是"当前焦点"的语义；普通分组用相纸白，
            // 免得整页都是琥珀色的小标题（那会让琥珀失去强调地位）。
            color = if (accent) colors.safelight else colors.ink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        if (note != null) {
            Text(
                note,
                style = SuiPaiType.labelSmall,
                color = colors.inkTertiary,
            )
        }
    }
}

/**
 * 筛选芯片：**与 AppBarCell 不是一回事**。
 *
 * AppBarCell 是"工具条上的一个动作格子"（有 1dp 框、选中 = 琥珀框）；
 * PillChip 是"一组互斥或多选的筛选项"（胶囊、选中 = 琥珀实心/琥珀洗色）。
 * 两者之前被写成同一个东西，于是筛选看起来像"又一组按钮"，
 * 用户不知道这一排是可以叠加的筛选条件。
 *
 * @param ticked 选中态。选中时用琥珀洗色底 + 琥珀字 + 琥珀描边（不用实心：
 *   一排全选中的实心琥珀会盖掉页面唯一的实心主按钮）。
 */
@Composable
fun PillChip(
    label: String,
    ticked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 计数角标（"筛选 3"里的 3）。0 不显示。 */
    badge: Int = 0,
) {
    val colors = SuiPaiColor.current
    val spoken = if (badge > 0) "$label $badge" else label
    val edge = when {
        !enabled -> colors.hairline
        ticked -> colors.safelight
        else -> colors.outline
    }
    val fg = when {
        !enabled -> colors.inkTertiary.copy(alpha = 0.5f)
        ticked -> colors.inkOnSafelightWash
        else -> colors.ink
    }
    val fill = when {
        ticked -> colors.safelightWash
        else -> Color.Transparent
    }
    // 外层管命中（48dp），内层管画（32dp 胶囊）—— 与 AppBarCell 同一套结构。
    Box(
        modifier = modifier
            .heightIn(min = Touch.min)
            .pressable(
                enabled = enabled,
                onClickLabel = if (ticked) "取消$label" else label,
                onClick = onClick,
            )
            // 选中态写进读屏：颜色对色盲用户等于不存在。
            .clearAndSetSemantics {
                contentDescription = if (ticked) "$spoken，已选中" else spoken
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(Touch.chip)
                .clip(SuiPaiShapes.pill)
                .background(fill)
                .border(if (ticked) Touch.selection else Touch.hairline, edge, SuiPaiShapes.pill)
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                spoken,
                style = SuiPaiType.labelMedium,
                color = fg,
                fontWeight = if (ticked) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 主操作按钮：**全屏至多一个**。
 *
 * 形态是本世界唯一被允许的"实心块"：琥珀底、近直角、暗房字。
 * 它之所以必须是实心，是因为其它一切（格子、芯片、次操作）都只有描边 ——
 * 层级靠"只有一个实心"立起来，而不是靠再加一种颜色。
 *
 * 这里**不用** Material 的 `Button`：装机量到它的语义树是两颗节点，
 * 可点那颗 `content-desc` 是空的（读屏只说「按钮，双击激活」）。
 * 结构与 `AppBarPrimary` 一致：外层管命中与语义，内层只管画。
 *
 * @param danger 危险动作（删除/清空）用定影红实心 + 纸白字，**不报"主操作"的语义**。
 * @param fillWidth 是否吃掉整行（弹层底部的主操作通常要）。
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    fillWidth: Boolean = false,
) {
    val colors = SuiPaiColor.current
    val container = when {
        !enabled -> colors.panelOverlay
        danger -> colors.dangerFill
        else -> colors.safelight
    }
    val onContainer = when {
        !enabled -> colors.inkTertiary.copy(alpha = 0.6f)
        danger -> colors.inkOnDangerFill
        else -> colors.inkOnSafelight
    }
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 96.dp))
            .heightIn(min = Touch.min)
            .pressable(
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Touch.button)
                .clip(SuiPaiShapes.frame)
                .background(container)
                .padding(horizontal = Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = SuiPaiType.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 次操作：不带底色的文字按钮，琥珀字。
 *
 * 它承担"重要但不是这一步的主线"的动作（还原、随机、复制信息）。
 * 与 [PrimaryButton] 的层级差是**有没有实心底**，不是字大字小 ——
 * 所以两者可以并排出现而不会被误认。
 *
 * 底下那道极淡的细线是"这是一颗按钮"的唯一线索：这个世界没有 hover 态、
 * 没有圆角胶囊可依，不给一点线的话它和一段普通文字长得一样。
 *
 * @param tonal 用次级字色而不是琥珀（用于"取消/收起"这类中性动作）。
 */
@Composable
fun TextButtonTonal(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
) {
    val colors = SuiPaiColor.current
    val fg = when {
        !enabled -> colors.inkTertiary.copy(alpha = 0.5f)
        tonal -> colors.inkSecondary
        else -> colors.safelight
    }
    Box(
        modifier = modifier
            .heightIn(min = Touch.min)
            .clip(SuiPaiShapes.frame)
            .pressable(
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = SuiPaiType.labelLarge,
                color = fg,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(
                Modifier
                    .height(Touch.hairline)
                    .widthIn(min = 12.dp)
                    .fillMaxWidth(0.6f)
                    .background(fg.copy(alpha = 0.35f)),
            )
        }
    }
}

/**
 * 空态：一整屏只有一件事的时候用它。
 *
 * 形态是"印样页中间空出来的一块"：上面一条发丝线、一个等宽编号（`00 / 00`）、
 * 一句主文案、一句说明、至多一个操作。
 * 不放假插画、不放假按钮 —— 空态最大的诱惑是拿装饰填满，而空态本身
 * 就是"这里还没有照片"的诚实表达。
 *
 * @param kicker 等宽编号位（`00 / 00`、`NO FRAMES`）。null 则不显示。
 * @param action 至多一个动作（通常是"去拍一张"）。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    note: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = SuiPaiColor.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 漏光：整屏唯一允许渐变的地方（这里没有照片，渐变不会污染画面）
        Box(
            Modifier
                .width(120.dp)
                .height(Touch.selection)
                .background(colors.leakBrush()),
        )
        if (kicker != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                kicker,
                style = SuiPaiType.marker,
                color = colors.inkTertiary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            title,
            style = SuiPaiType.titleLarge,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        if (note != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                note,
                style = SuiPaiType.bodyMedium,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.xl))
            action()
        }
    }
}

/**
 * 参数墙：一组「大数字 + 等宽标签」的并排展示（96 张 / 12 个相册 / 5446 配方）。
 *
 * 这是本世界唯一的"大数字"语汇，且**必须**与标签成对出现 ——
 * 单独一个大数字是 hero-metric 模板（品类默认），配上等宽标签之后
 * 才是印样页下方的曝光参数。
 *
 * @param values 每个元素是 (数值, 标签)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValueWall(
    values: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val colors = SuiPaiColor.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        values.forEach { (value, label) ->
            Column {
                Text(
                    value,
                    style = SuiPaiType.counter,
                    color = colors.ink,
                    maxLines = 1,
                )
                Text(
                    label,
                    style = SuiPaiType.frameNo,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 发丝线分隔。**不是** `HorizontalDivider`：后者的默认粗细/颜色属于 Material，
 * 而这条线是印样页上的印刷线 —— 1dp、装饰性、默认缩进与文字对齐。
 *
 * @param inset 左右各缩进（默认 0 = 通栏；弹层里通常给 `Spacing.lg` 与内容对齐）。
 */
@Composable
fun Divider(
    modifier: Modifier = Modifier,
    inset: androidx.compose.ui.unit.Dp = 0.dp,
    color: Color = Color.Unspecified,
) {
    val resolved = if (color == Color.Unspecified) SuiPaiColor.current.hairline else color
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(Touch.hairline)
            .background(resolved),
    )
}

/**
 * 一段可滚动的分组内容（弹层里用）：给每个 section 之间统一的 8dp 呼吸。
 *
 * 存在的理由：旧弹层里每个 section 自己写 `padding(top = 14.dp)`，于是间距有
 * 6/8/12/14/18 五种。这里收敛成一个。
 */
@Composable
fun ColumnScope.Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs), content = content)
}

/** 给弹层用的标准内容内边距：左右统一 20dp、底部 24dp（避开手势条）。 */
val SheetPadding: PaddingValues = PaddingValues(
    start = Spacing.lg,
    end = Spacing.lg,
    bottom = Spacing.xl,
)

/**
 * 一行"键 — 值"的信息条：查看器/设置里那种左标签右数值的行。
 *
 * 数值用等宽 —— 它总是数字、日期、比例或参数（印样语义）。
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    /** 数值用琥珀（当前生效的那一项）。 */
    accent: Boolean = false,
) {
    val colors = SuiPaiColor.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Touch.min)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = SuiPaiType.bodyMedium,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            value,
            style = SuiPaiType.parameter,
            color = if (accent) colors.safelight else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 把一组可选值渲染成一行筛选芯片（省得每个页面各写一遍 FlowRow）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}
