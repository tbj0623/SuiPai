package com.suipai.app.ui.memories

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 回忆列表。卡片流：一张大封面 + 标题 + 副标题 + 张数。
 *
 * 这一屏只做"把 [Memories.build] 的结果画出来"，不自己算日期 ——
 * 聚合规则全在 `Memories.kt` 里，注入时钟，JVM 上可测。
 *
 * `photos` 传整库（任意顺序）。为了封面能显示，这里按 `id → PhotoItem` 建一张表；
 * `Memory.photoIds` 里只有 id（聚合层不该持有 `Uri`，那是 Android 类型）。
 */
@Composable
fun MemoriesScreen(
    memories: List<Memory>,
    photos: List<PhotoItem>,
    onOpen: (Memory) -> Unit,
    onBack: () -> Unit,
    loading: Boolean = false,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    val byId = remember(photos) { photos.associateBy { it.id } }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "回忆",
                style = SuiPaiSheet.type.pageHead,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppBarText(
                "${memories.size} 组",
                style = SuiPaiSheet.type.frameNo,
                color = colors.inkTertiary,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = 8.dp),
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
            loading && memories.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "正在翻旧片",
                        color = colors.inkSecondary,
                        style = SuiPaiType.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }

            memories.isEmpty() -> EmptyState(
                title = "还没有能拼成回忆的照片",
                kicker = "00 / 00",
                note = "多拍几天，某年今天、最近一周、去年夏天会自己长出来",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("memories_list"),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    top = Spacing.xs,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                items(memories, key = { it.id }) { memory ->
                    val cover = byId[memory.coverPhotoId]
                    MemoryCard(memory, cover?.uri?.toString(), onOpen = { onOpen(memory) })
                }
            }
        }
    }
}

/**
 * 一张回忆卡。
 *
 * 封面用 `ImageRequest` 走 Coil（`FastThumb` 那套缩略图口径），并且**必须** `remember`
 * —— 见 `PerfSourceGuardTest.imageRequestsAreRemembered`：直接写在 composable 里，
 * 每次重组都是一个新请求，边滚边重新派发。
 */
@Composable
private fun MemoryCard(memory: Memory, coverKey: String?, onOpen: () -> Unit) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    val label = "${memory.title}，${memory.subtitle}，${memory.count} 张"
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "打开「${memory.title}」",
                onClick = onOpen,
            )
            .clearAndSetSemantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 封面 4:3：大封面要在第一屏就占住视线，但不吃掉整屏（下面还要露出下一张卡的头）。
                .aspectRatio(4f / 3f)
                .background(colors.panelRaised, SuiPaiShapes.frame)
                .border(1.dp, colors.hairline, SuiPaiShapes.frame),
        ) {
            if (coverKey == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("封面读不到", style = SuiPaiType.marker, color = colors.inkTertiary)
                }
            } else {
                val request = remember(coverKey) {
                    ImageRequest.Builder(context)
                        .data(coverKey)
                        .size(1024)
                        .tag(FastThumb(1024))
                        .crossfade(true)
                        .build()
                }
                val painter = rememberAsyncImagePainter(model = request)
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (painter.state is AsyncImagePainter.State.Error) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("封面读不到", style = SuiPaiType.marker, color = colors.inkTertiary)
                    }
                }
            }
            // 张数角标：和相册卡面同一个位置、同一套等宽字。
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xs)
                    .background(colors.canvas.copy(alpha = 0.82f), SuiPaiShapes.frame),
            ) {
                Text(
                    "${memory.count}",
                    style = SuiPaiType.frameNo,
                    color = colors.ink,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                memory.title,
                style = SuiPaiType.titleMedium,
                color = colors.ink,
            )
            Spacer(Modifier.size(Spacing.xs))
            Text(
                memory.kind.marker(),
                style = SuiPaiType.marker,
                color = colors.inkTertiary,
            )
        }
        Text(
            memory.subtitle,
            style = SuiPaiType.bodySmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 卡片右上角那枚等宽编号：与相册页的 `frameNo` 同一族。 */
internal fun MemoryKind.marker(): String = when (this) {
    MemoryKind.ON_THIS_DAY -> "ON THIS DAY"
    MemoryKind.LAST_WEEK -> "LAST 7 DAYS"
    MemoryKind.THIS_MONTH -> "THIS MONTH"
    MemoryKind.LAST_SUMMER -> "LAST SUMMER"
    MemoryKind.FIRST_DAY -> "FIRST FRAME"
    MemoryKind.YEAR_AGO -> "A YEAR AGO"
}
