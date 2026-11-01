package com.suipai.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 app 的工具条语言。
 *
 * 存在的理由是三个界面（相册 / 查看器 / 一瞬）此前各自把几十个动作塞进一条
 * `Modifier.horizontalScroll(...)` 的行里，后果是实测过的：
 * - 一瞬入口排在查看器底栏第 40 个按钮之后，要横滚 8 次才够得到；
 * - 滚出视野的 Compose 节点**根本不在语义树里**，自动化验证只能靠改 shared_prefs 绕路；
 * - 不滚的那一行里，标签会被挤成竖排两行。
 * 所以统一成：**固定一行常驻动作 + 其余全部进分 section 的底部弹层**。
 */

/** 一行工具条的高度上限（设计契约：顶部一行工具条，不堆叠）。 */
val AppBarHeight = 52.dp
private val CellShape = RoundedCornerShape(2.dp)
/** 画出来的那一圈框。 */
private val CellVisualHeight = AppBarHeight - 14.dp
/**
 * 命中区高度 = 无障碍最小可点边长 48 dp。工具条那一行本来就有 52 dp，
 * 把余量给命中区是**零视觉变化**的：框还是 38 dp，只是上下各 5 dp 的空隙也点得中。
 * 这套界面每一屏有几十颗这样的格子，38 dp 是拇指点不中的主要来源。
 * 横向不能同样放宽：格子之间只有 3 dp 间距，加宽命中区会把整行挤出去（查看器底栏有 9 颗）。
 */
private val CellTouchHeight = 48.dp

/**
 * 工具条 / 弹层里的一个可点格子。
 * 选中态用这套界面唯一的语言：琥珀色直角框（印样上油笔圈选出来的那个框），
 * 不用 Material 默认的填充胶囊。
 */
@Composable
fun AppBarCell(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val edge = if (on) accent else MaterialTheme.colorScheme.outline
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        on -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    // 角标（"筛选 3"）是文字的一部分，也是朗读的一部分：合并子节点之后
    // contentDescription 会盖掉 Text，只写 label 就把「3 条筛选生效」这个信息弄丢了。
    val spoken = if (badge > 0) "$label $badge" else label
    // 外层只管命中与无障碍语义，内层才是画出来的框——所以框变矮/变高都不会影响点得中的范围。
    //
    // `clearAndSetSemantics` 不是风格问题：实测（API 35，装机量的）不加它时这一格在语义树里是三个节点
    // ——外层 clickable=true 却没有名字、中层带 content-desc 但不可点、再加一个 TextView。
    // TalkBack 会沿祖先找到动作，但按 ACTION_CLICK 点「带标签的那个节点」的自动化工具全部落空。
    // 试过 `semantics(mergeDescendants = true)`：没合并，反而多出一个没有名字的 Button 节点。
    // 选中态也写进标签：琥珀色框是「按日 / 最新」这类格子唯一的开/关线索，读屏与色盲用户拿不到颜色。
    Box(
        modifier = modifier
            .height(CellTouchHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (on) "$spoken，已选中" else spoken
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(CellVisualHeight)
                .border(1.dp, edge, CellShape)
                .background(if (on) accent.copy(alpha = 0.10f) else Color.Transparent, CellShape)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                spoken,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                // 大字号设置下宁可看到省略号，也不要把「取消隐藏」切成「取消隐」——
                // 切掉的半个字用户会当成另一个功能。
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 工具条里的主操作（保存 / 一瞬 / 完成）。
 * 近直角 2dp + 琥珀底 + 暗房字——不用 Material 默认的胶囊，
 * 否则整个界面只有它是圆的，和「印样框线」的世界打架。
 *
 * 这里**不用** `MaterialTheme` 的 `Button`：装机量到它的语义树是两颗节点，
 * 可点的那颗 `content-desc` 是空的（读屏只会说「按钮，双击激活」，不说这颗是「一瞬」还是「保存」），
 * 而 `Button` 自己的 `clickable` 接在我们传入的 modifier **之后**（更靠内），
 * 用 `clearAndSetSemantics` 合并会把那个 click 动作一起清掉（量到 `click=false`，更糟）；
 * 只在外层 `semantics { contentDescription = … }` 又落不到可点那一颗上（量到 `desc=''`、`cls=View`）。
 * 所以照 `AppBarCell` 那套已被装机验证过的结构自己搭：外层管命中与语义，内层只管画。
 */
@Composable
fun AppBarPrimary(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val onContainer = if (enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .height(CellTouchHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(AppBarHeight - 12.dp)
                .background(container, CellShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * 工具条容器：**不横向滚动**，装不下就换行。
 *
 * 原来是一颗固定 52dp 高的 `Row`。装机量到（系统字级 2.0，`suipai_r4`）：
 * 画廊那一排里页名 + 帧数先吃掉 547px，四颗动作格子从 178/178/178/185 被挤成
 * **178 / 139 / 15 / 95** —— 「筛选」只剩 15px 宽，看不见也点不着，
 * 而"把字调到最大"正是 Play 前置检查与无障碍用户最常踩的一档。
 * `Row` 没有"这颗不许缩"这个选项，横向滚动又是第 1 号守卫明令禁止的
 * （滚出视野的 Compose 节点根本不在语义树里，读屏与自动化都够不着），
 * 所以改成 `FlowRow`：**常驻动作一颗都不许消失，宁可工具条多占一行。**
 * 字级 1.0 / 1.3 时一行装得下，高度与原来逐像素相同。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBarRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppBarHeight)
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

/**
 * 工具条里的一段文字（页名、帧数、翻页计数）。
 *
 * 不直接用 `Text` 的理由是 `FlowRow` 这一版（1.7.6）没有 `itemVerticalAlignment`：
 * 一行里最高的那颗（格子 48 dp）定了行高，矮的 `Text` 就贴到行的上沿 ——
 * 装机量到页名比同排格子高 22 px。让它占满与格子同一个 48 dp，就对齐了。
 *
 * `modifier` 仍然挂在**文字那颗节点**上（`testTag`、`clickable` 都跟着它走），
 * 只有排布用的（`weight`）走 `layout` —— 否则 `gallery_note` 的 resource-id
 * 会跑到外层盒子上，探针与读屏拿到的就是空的。
 */
@Composable
fun AppBarText(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    layout: Modifier = Modifier,
) {
    Box(
        modifier = layout.height(CellTouchHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** 弹层里的分组标题。 */
@Composable
fun AppSheetSection(title: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一组自动换行的格子：不逼用户横向滚动找按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppChipFlow(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * 底部弹层。装不下的动作放这里。
 * 除了解决可达性，它还是**可验证性**的前提：弹层里的节点全在语义树里，
 * 横向滚动的 off-screen 节点不在。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AppSheet(
    title: String,
    tag: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 直接展开到内容高度：半开的抽屉会把 section 藏在下面，
        // 而印样这套界面的前提就是"打开即可见、可见即可点"。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(
                "收起",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    // 没有 role 的话 `clickable` 不写 Role（Clickable.kt:1111 只在非 null 时写），
                    // 于是相机抽屉 / 筛选 / 显示 / AI / 关于 —— 每一层弹层唯一看得见的关闭控件
                    // 在 TalkBack 里不是一颗按钮。
                    .clickable(role = Role.Button, onClickLabel = "收起这个弹层", onClick = onDismiss)
                    .testTag("sheet_dismiss")
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Column(
            // weight(fill = false)：内容短就贴内容，内容长就顶到弹层上限并自己滚。
            // 少了这一段，超出屏幕的 section 既看不见也点不到（实测一瞬「更多」
            // 的导出组 5 格全死），比原来的横向滚动更隐蔽，因为它看起来是完整的。
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                // tag 挂在内容区而不是弹层外壳：外壳的 bounds 是整块遮罩，
                // 自动化拿它当"可视区"会把背后的界面一起收进来，量不准到底露出几格。
                //
                // 同一块 semantics 里再开 `testTagsAsResourceId`：`Modifier.testTag` 的桥是
                // 按子树生效的，而主界面那一处（PermissionGate 的根 Box）够不到弹层——
                // ModalBottomSheet 自己是一个组合根。不在这儿再开一次，抽屉里的格子就只有中文标签可认，
                // 而循环格子的标签点一下就变（「时段」→「清晨」），按老标签找会抓到同名的分区标题。
                .semantics {
                    contentDescription = tag
                    testTagsAsResourceId = true
                }
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            content = content,
        )
    }
}
