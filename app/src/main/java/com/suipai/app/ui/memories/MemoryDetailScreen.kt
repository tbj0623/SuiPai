package com.suipai.app.ui.memories

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 详情页那一行日期。声明在文件级：`ofPattern` 每次调用都要解析一遍模式（守卫 13）。 */
private val MemoryDay: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

/**
 * 回忆详情：横滑大图 + 底部信息 + 「全部一瞬」入口。
 *
 * 与查看器共用的是**手势语言**（左右滑翻页、底栏留一串格子），不是代码：
 * 查看器那一份带旋转/镜像/删除，回忆只需要"翻着看"。
 *
 * @param photos 这一组回忆的照片（顺序即 `Memory.photoIds` 的顺序，新→旧）
 * @param onAllMoments 进「全部一瞬」（外部导航；这一屏不认识一瞬那一层）
 */
@Composable
fun MemoryDetailScreen(
    memory: Memory,
    photos: List<PhotoItem>,
    onBack: () -> Unit,
    onAllMoments: () -> Unit,
    startIndex: Int = 0,
) {
    val colors = SuiPaiColor.current
    val items = remember(photos, memory.photoIds) {
        val byId = photos.associateBy { it.id }
        memory.photoIds.mapNotNull { byId[it] }
    }
    if (items.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .statusBarsPadding(),
        ) {
            AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                AppBarCell(label = "返回", on = false, onClick = onBack)
                AppBarText(
                    memory.title,
                    style = SuiPaiSheet.type.pageHead,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "这一组的照片都不在了",
                    color = colors.inkSecondary,
                    style = SuiPaiType.bodyMedium,
                )
            }
        }
        return
    }
    val restored = rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    val pager = rememberPagerState(initialPage = restored.intValue.coerceIn(0, items.lastIndex)) { items.size }
    LaunchedEffect(pager.currentPage) { restored.intValue = pager.currentPage }
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .testTag("memory_pager"),
        ) { page ->
            MemoryPhoto(
                photo = items[page],
                onTap = { chrome = !chrome },
            )
        }
        if (chrome) {
            AppBarRow(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
            ) {
                AppBarCell(label = "返回", on = false, onClick = onBack)
                AppBarText(
                    "${pager.currentPage + 1} / ${items.size}",
                    style = SuiPaiSheet.type.frameNo,
                    color = colors.inkSecondary,
                    layout = Modifier.weight(1f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                AppBarCell(
                    label = memory.title,
                    on = false,
                    // 这一格只是把组名放到顶栏上（长名字会省略），点它回到本组第一张。
                    onClick = {
                        if (pager.currentPage != 0) {
                            scope.launch { pager.animateScrollToPage(0) }
                        }
                    },
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.canvas.copy(alpha = 0.86f))
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            ) {
                Text(
                    memory.title,
                    style = SuiPaiType.titleMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    memory.subtitle,
                    style = SuiPaiType.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val first = items[pager.currentPage]
                Text(
                    dayLabel(first) + " · " + first.displayName,
                    style = SuiPaiType.marker,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = Spacing.xxs)) {
                    AppBarPrimary(
                        label = "全部一瞬",
                        modifier = Modifier.testTag("memory_all_moments"),
                        onClick = onAllMoments,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryPhoto(photo: PhotoItem, onTap: () -> Unit) {
    val context = LocalContext.current
    val colors = SuiPaiColor.current
    val request = remember(photo.id) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .size(2048)
            .tag(FastThumb(2048))
            .crossfade(true)
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request)
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { contentDescription = photo.displayName },
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (painter.state is coil.compose.AsyncImagePainter.State.Error) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("无法显示", color = colors.inkSecondary, style = SuiPaiType.bodyMedium)
            }
        }
        // 点一下收/放信息层。这里不叠 clickable：整页的点击由 pager 之外那层拿不到，
        // 所以用一个透明的手势盒——它没有语义动作，读屏不会在这里停住。
        Box(
            Modifier
                .fillMaxSize()
                .pointerTap(onTap),
        )
    }
}

/** 一行日期：`dateTaken <= 0` 的照片说"日期不可知"，而不是显示 1970 年。 */
internal fun dayLabel(photo: PhotoItem): String =
    if (photo.dateTaken <= 0L) "日期不可知"
    else Instant.ofEpochMilli(photo.dateTaken).atZone(ZoneId.systemDefault()).toLocalDate().format(MemoryDay)

/** 透明手势盒：只在"点一下"上占位，不加语义（读屏那一层由图片节点自己说话）。 */
private fun Modifier.pointerTap(onTap: () -> Unit): Modifier =
    this.pointerInput(onTap) {
        detectTapGestures(onTap = { onTap() })
    }
