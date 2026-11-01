package com.suipai.app.ui.trash

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.suipai.app.data.Trash
import com.suipai.app.data.TrashItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet
import java.util.Locale

/**
 * 回收站。
 *
 * 这一屏只做**数据 + 回调**：它不知道照片是怎么搬进来的，也不导航 ——
 * `onRestore` / `onPurge` / `onPurgeAll` 由宿主接上 [Trash] 的挂起函数，
 * 宿主决定成功后要不要刷新 `items`。
 *
 * 为什么"清空"要二次确认而"恢复"不要：恢复是可逆的（再删一次就回来了），
 * 清空是不可逆的。把不可逆的那个放在一次误触之外，是这个界面的全部善意。
 *
 * @param items 当前回收站内容（宿主已经从 [Trash.list] 取好，按删除时间倒序）。
 * @param refreshing 正在搬/正在清 —— 这一段只把网格压暗并挡住重复点击。
 * @param note 宿主的一句话结论（"已恢复 1 张" / "存储空间不足，没搬成"）。
 */
@Composable
fun TrashScreen(
    items: List<TrashItem>,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRestore: (TrashItem) -> Unit,
    onPurge: (TrashItem) -> Unit,
    onPurgeAll: () -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    var confirmPurgeAll by remember { mutableStateOf(false) }
    var confirmPurgeOne by remember { mutableStateOf<TrashItem?>(null) }
    val now = remember(items) { System.currentTimeMillis() }
    val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "回收站",
                style = SuiPaiSheet.type.pageHead,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppBarText(
                "${items.size} 张 · ${formatBytes(totalBytes)}",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = 8.dp),
            )
            // 空回收站里「清空」没有意义：灰掉比藏起来好，用户不用猜它去哪了。
            AppBarCell(
                label = "清空",
                on = false,
                onClick = { confirmPurgeAll = true },
                enabled = items.isNotEmpty() && !refreshing,
            )
        }
        if (note != null) {
            Text(
                note,
                style = SuiPaiType.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 2.dp),
            )
        }
        when {
            refreshing && items.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "正在整理回收站",
                        color = colors.inkSecondary,
                        style = SuiPaiType.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }

            items.isEmpty() -> EmptyState(
                title = "回收站是空的",
                kicker = "00 / 00",
                note = "删除的照片会在这里保留 ${Trash.DEFAULT_RETENTION_DAYS} 天，之后自动清理。",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                contentPadding = PaddingValues(Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
            ) {
                items(items, key = { it.trashUri }) { item ->
                    TrashCell(
                        item = item,
                        daysLeft = Trash.daysLeftOf(item.deletedAt, now),
                        enabled = !refreshing,
                        onRestore = { onRestore(item) },
                        onPurge = { confirmPurgeOne = item },
                    )
                }
            }
        }
    }

    // 清空：不可逆，必须过一道确认。
    if (confirmPurgeAll) {
        AlertDialog(
            onDismissRequest = { confirmPurgeAll = false },
            title = { Text("清空回收站？") },
            text = {
                Text(
                    "这 ${items.size} 张会立刻从设备上删掉，无法恢复。" +
                        "（不清的话它们会在 ${Trash.DEFAULT_RETENTION_DAYS} 天后自动清理。）",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurgeAll = false
                    onPurgeAll()
                }) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurgeAll = false }) { Text("取消") }
            },
        )
    }

    // 单张彻底删除：同样是不可逆的，同样要确认。
    confirmPurgeOne?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmPurgeOne = null },
            title = { Text("彻底删除这张？") },
            text = { Text("「${item.displayName}」删掉之后无法恢复。想留着的话，先恢复再说。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurgeOne = null
                    onPurge(item)
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurgeOne = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 一格。
 *
 * 语义结构与 `AppBarCell` 同形：外层只管命中与**可点那一颗**的 contentDescription，
 * 内层只管画。这样读屏念的是「IMG_0421，还剩 12 天，双击恢复」，
 * 而不是「按钮，双击激活」。
 *
 * 角标（还剩 N 天）同时写进 contentDescription：它是**唯一的**过期线索，
 * 只画在缩略图角落，读屏与高对比度模式下都拿不到。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashCell(
    item: TrashItem,
    daysLeft: Int,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    // Coil 的 ImageRequest 没有 equals：不 remember 就是每次重组都新建一个，
    // 缓存键跟着变，缩略图会一片一片重新解码（PerfSourceGuardTest 第 3 条）。
    val request = remember(item.trashUri) {
        ImageRequest.Builder(context)
            .data(item.trashUri)
            .crossfade(true)
            .build()
    }
    val spoken = buildString {
        append(item.displayName.ifBlank { "一张照片" })
        append("，")
        append(if (daysLeft <= 0) "今天就清理" else "还剩 $daysLeft 天")
        append("，双击恢复，长按彻底删除")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = "恢复",
                role = Role.Button,
                onLongClickLabel = "彻底删除",
                onLongClick = onPurge,
                onClick = onRestore,
            )
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .border(Spacing.hair, colors.hairline, SuiPaiShapes.frame),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 越接近清理越亮：最后三天用告警色，别让"还剩 3 天"和"还剩 27 天"长得一样。
            val urgent = daysLeft <= URGENT_DAYS
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.xxs)
                    .background(
                        if (urgent) colors.fixer else Color.Black.copy(alpha = 0.55f),
                        SuiPaiShapes.frame,
                    )
                    .padding(horizontal = Spacing.xxs, vertical = 1.dp),
            ) {
                Text(
                    if (daysLeft <= 0) "今天清理" else "$daysLeft 天",
                    style = SuiPaiType.marker,
                    color = if (urgent) colors.inkOnFixerWash else Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 最后三天算"urgent"（角标换成告警色）。 */
private const val URGENT_DAYS = 3

/**
 * 字节 → 人能读的量。
 *
 * 只做到 MB/GB 两档：回收站里最大的是原图，最小的也有几百 KB，
 * 再往下分档只会让工具条里那串数字忽长忽短。
 *
 * 格式化一律用 [Locale.ROOT]：默认 locale 在阿拉伯语等区域会把小数点换成别的字形，
 * 同一列里的「1.5 MB / 12 MB」于是连小数点宽度都不一样，横向扫不齐。
 * 这里要的是稳定的数字形状，不是本地化。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        val gb = mb / 1024.0
        if (gb >= 10.0) "${gb.toInt()} GB" else String.format(Locale.ROOT, "%.1f GB", gb)
    } else {
        if (mb >= 10.0) "${mb.toInt()} MB" else String.format(Locale.ROOT, "%.1f MB", mb)
    }
}
