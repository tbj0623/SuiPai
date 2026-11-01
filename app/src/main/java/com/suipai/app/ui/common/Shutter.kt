package com.suipai.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 快门。设计契约里它是**整页唯一的圆形**，而且是琥珀描边、暗房底填充、无阴影。
 *
 * 没有用 Material3 的 `FloatingActionButton`：那个组件的底色取 `primaryContainer`、
 * 既不接受 `border` 也不接受 `enabled`，而本项目早期根本没定义任何 `*Container` 角色，
 * M3 就从 `primary` 用 HCT 自己推导——结果相册主屏的快门成了一只**紫色方块**，
 * 和「暗房接触印样」不是一个世界（见交接文档 §3.9）。自己画反而更贴合契约。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShutterFab(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    // 动作名字是必填的：这只圆钮在两个地方做的事不一样 ——
    // 主屏上它是「打开相机」，相机页里它是「拍照」+「长按连拍」。
    // 让调用方自己说，比给一个会骗人的默认值强。
    require(onLongClick == null || onLongClickLabel != null) {
        "ShutterFab：给了长按就得给长按起个名字，否则读屏里是个无名动作"
    }
    val accent = MaterialTheme.colorScheme.primary
    val source = remember { MutableInteractionSource() }
    // 长按连拍只有相机页用得到；传了才换成 combinedClickable，其它页面保持原样。
    val clickable = if (onLongClick == null) {
        modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClickLabel = onClickLabel,
            interactionSource = source,
            indication = null,
            onClick = onClick,
        )
    } else {
        modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel,
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    Box(
        modifier = clickable
            .size(60.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(if (enabled) 1.5.dp else 1.dp, accent.copy(alpha = if (enabled) 1f else 0.35f), CircleShape)
            // 名字跟着调用方走：主屏这只圆钮是「打开相机」，相机页里才是「拍照」。
            // 原来写死"拍照"，于是画廊主屏的 `onClickLabel = "打开相机"` 被覆盖 ——
            // 主控件在 TalkBack 里报的是它**不会做**的那件事。
            .semantics { contentDescription = onClickLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = accent.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(26.dp),
        )
    }
}
