package com.suipai.app.ui.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suipai.app.data.VideoAccess
import com.suipai.app.data.VideoItem
import com.suipai.app.data.VideoThumbs
import com.suipai.app.data.formatDuration
import com.suipai.app.data.videoCellLabel
import com.suipai.app.data.videoEmptyCopy
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet
import kotlinx.coroutines.CancellationException

/**
 * 视频列表（印样网格）。
 *
 * ## 为什么这一屏只有"数据 + 回调"
 * 照 [com.suipai.app.ui.tools.DuplicatesScreen] / `TrashScreen` 的分工：这一屏
 * 不 import `state` 包、不导航、不自己查 `MediaStore`。列表从哪来（[com.suipai.app.data.VideoRepository.loadPage]）、
 * 点开之后去哪（播放器 / 还是别的地方）全由宿主决定。所以同一份网格能被"视频 tab"
 * 与将来的"某个相册里的视频"复用。
 *
 * ## 封面为什么必须走 [VideoThumbs] 而不是这里现取
 * `MediaMetadataRetriever.getFrameAtTime` 是这一屏最贵的一步（4K 一段量级几十到几百毫秒，
 * 还带一次解码器初始化），而 `LazyVerticalGrid` 滚动时会**反复销毁/重建**同一格。
 * 现取的话往回滚一屏就是几十次取帧。`VideoThumbs` 已经做了两层缓存
 * （`filesDir/video_thumbs/` 磁盘 + 8 MB 内存 LRU），磁盘文件名是
 * `<id>_<边长>_<字节数>.jpg`（`coverCacheName`）—— 内容一变名字就变，不会显示上一段的封面。
 * 这里**不新增**取帧路径：多一条路就多一处 native 句柄没 release 的机会。
 *
 * @param videos 当前这一页视频（宿主从 [com.suipai.app.data.VideoRepository] 取好，拍摄时间倒序）
 * @param loading 正在读 —— 此时空列表**不许**说"还没有视频"（见 [videoEmptyCopy]）
 * @param onOpen 点某一格：去播放（宿主决定推到哪一层）
 * @param onBack 页首「返回」
 * @param note 宿主的一句话结论（"读到 12 段" / "没读到，稍后再试"）
 * @param access 视频读权限的状态。**签名里这个参数是后加的**：没有它，这一屏分不出
 *   "系统没放行视频"和"库里确实没有"，而把前者说成后者正是 `videoEmptyCopy` 反复点名的假话。
 *   有默认值，宿主不传也能编译（默认 = 已放行）。
 */
@Composable
fun VideoGrid(
    videos: List<VideoItem>,
    loading: Boolean,
    onOpen: (VideoItem) -> Unit,
    onBack: () -> Unit,
    note: String? = null,
    access: VideoAccess = VideoAccess.GRANTED,
    onRequestAccess: (() -> Unit)? = null,
    readFailed: Boolean = false,
) {
    val colors = SuiPaiColor.current

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "视频",
                style = SuiPaiSheet.type.pageHead,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Spacing.xxs),
            )
            AppBarText(
                // 未授权、部分授权和读取失败时的数量都不代表全库。
                if (access == VideoAccess.GRANTED && !loading) "${videos.size} 段" else "可见 ${videos.size} 段",
                style = SuiPaiType.frameNo,
                color = colors.inkTertiary,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(start = Spacing.xxs),
            )
            if (onRequestAccess != null && (access == VideoAccess.MISSING || access == VideoAccess.PARTIAL)) {
                AppBarCell(
                    label = if (access == VideoAccess.PARTIAL) "补选视频" else "授权视频",
                    on = false,
                    onClick = onRequestAccess,
                )
            }
        }

        if (note != null) {
            NoteLine(note)
        }

        when {
            // 读着的时候先给进度，不先给结论 —— 第一次读整库会慢一点。
            videos.isEmpty() && loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colors.safelight, strokeWidth = 2.dp)
                    Spacer(Modifier.height(Spacing.md))
                    Text("正在读视频", style = SuiPaiType.bodyMedium, color = colors.ink)
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        "第一次读整库会慢一点，读完就记在缓存里",
                        style = SuiPaiType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
            }

            // 空态：三句话分三种情况（没放行 / 还没读完 / 确实没有），文案由数据层给，
            // 界面不另写一份 —— 看得见的与念出来的必须同源。
            videos.isEmpty() -> {
                val copy = videoEmptyCopy(access = access, loaded = !loading, readFailed = readFailed)
                EmptyState(
                    kicker = "NO CLIPS",
                    title = copy.title,
                    note = copy.note,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                // 横向用印样密排（2dp 缝），纵向留 8dp：格子下面还有一行文件名，
                // 缝贴死会把上一格的名字和下一格的封面粘成一块。
                horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // key = id：滚动时 Compose 靠它把"封面已解好"的那一格认回来。
                // 没有 key 的话格子被复用，`remember(item.id)` 全部失效，等于没缓存。
                items(videos, key = { it.id }) { item ->
                    VideoCell(
                        item = item,
                        onOpen = { onOpen(item) },
                    )
                }
            }
        }
    }
}

/**
 * 一格视频。
 *
 * 可点的那颗节点自己带名字：`videoCellLabel` 已经把"名字 / 时长 / 大小 / 画面尺寸 / 拍摄时间"
 * 拼成一句话，这里不再另写一份（两处各写一遍，迟早分叉，而分叉的那一半永远是读屏用户吃亏）。
 *
 * 动作只有一个"播放"，所以用 `clickable` 而不是 `combinedClickable` ——
 * 后者必须同时给 `onClickLabel` 与 `onLongClickLabel`（`PerfSourceGuardTest` 第 12 条），
 * 而这一版没有长按动作，凭空挂一个只会让读屏多出一个不做事的选项。
 */
@Composable
private fun VideoCell(
    item: VideoItem,
    onOpen: () -> Unit,
) {
    val colors = SuiPaiColor.current
    // 读屏那句在组合里只算一次：它跟着 item 走，不跟着选中态走。
    val label = remember(item) { videoCellLabel(item) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "播放", onClick = onOpen)
            // 语义收成一颗：封面 + 时长角标 + 文件名三块并成一句话，
            // 否则 TalkBack 会在这一格里走三步才说出它是什么。
            .clearAndSetSemantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(colors.canvasSunken, SuiPaiShapes.frame)
                .border(Spacing.hair, colors.hairline, SuiPaiShapes.frame),
        ) {
            VideoCover(item = item, modifier = Modifier.fillMaxSize())

            // 时长角标：右下角、等宽数字。底用 token 面而不是字面量黑色 ——
            // 两套主题（暗房 / 日光印样）里它都得自己压得住画面。
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xxs)
                    .background(colors.canvasSunken.copy(alpha = 0.86f), SuiPaiShapes.frame)
                    .padding(horizontal = Spacing.xxs, vertical = 1.dp),
            ) {
                Text(
                    formatDuration(item.durationMs),
                    style = SuiPaiType.frameNo,
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
        Text(
            item.displayName,
            style = SuiPaiType.labelSmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxs, start = 1.dp, end = 1.dp),
        )
    }
}

/**
 * 封面：磁盘缓存里的那张 JPEG。
 *
 * ## 两条硬约束都落在这六行里
 * 1. **不随重组重建**：`remember(item.id)` 拿着结果，`LaunchedEffect` 只在 id/字节数变了才跑。
 *    少了这一层，滚一下、选一下、主题一翻，整屏格子都会重新派发一次取帧。
 * 2. **必须落盘**：真正读盘 / 取帧 / 写盘的是 `VideoThumbs.load`（`filesDir/video_thumbs/`）。
 *    这一屏不自己碰 `MediaMetadataRetriever` —— native 句柄的 release 只在一个地方发生。
 *
 * `settled` 是"问过了"的意思：还没读完就画"无封面"是一句假话（与 `VideoAccess.UNKNOWN` 同一条纪律），
 * 所以加载中只留一块空相纸，读完确实没有才写字。
 */
@Composable
private fun VideoCover(item: VideoItem, modifier: Modifier = Modifier) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    var cover by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var settled by remember(item.id) { mutableStateOf(false) }

    // sizeBytes 也是键：`coverCacheName` 把字节数编进文件名，
    // 同一行被原地换掉内容时 id 不变而封面该换（见数据层那条注释）。
    LaunchedEffect(item.id, item.sizeBytes) {
        val bitmap = try {
            VideoThumbs.load(context, item)
        } catch (e: CancellationException) {
            // 取消不是"封面坏了"：这一格被滚出屏幕，直接让协程结束。
            throw e
        } catch (e: Exception) {
            android.util.Log.w("VideoGrid", "封面没取到：${item.id}", e)
            null
        }
        cover = bitmap?.asImageBitmap()
        settled = true
    }

    val bitmap = cover
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            // 名字已经在整格那颗可点节点上说过一遍了，图再报一次就是重复朗读。
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (settled) {
                // 取不到封面（解码失败 / 文件已经不在了）：写一句，别留一块灰色空洞。
                Text("无封面", style = SuiPaiType.marker, color = colors.inkTertiary)
            }
        }
    }
}

/** 宿主的一句话结论。与 `DuplicatesScreen` 里那一条同形：面板底 + 次级字。 */
@Composable
private fun NoteLine(text: String) {
    val colors = SuiPaiColor.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.panelOverlay)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Text(text, style = SuiPaiType.bodySmall, color = colors.inkSecondary)
    }
}

/**
 * 供宿主判断"要不要让用户去补权限"用的一点小工具：把权限状态翻成一句能贴进 `note` 的话。
 *
 * 放在这里而不是数据层，是因为它只说界面上的话；数据层那边 `videoEmptyCopy` 已经给了
 * 空态版本，这一句是给"列表里已经有几段、但列表并不完整"那一种情况用的。
 */
internal fun videoAccessHint(access: VideoAccess): String? = when (access) {
    VideoAccess.GRANTED -> null
    VideoAccess.PARTIAL -> "只授权了部分视频；未选中的视频不会出现在这里，可点「补选视频」更新授权"
    VideoAccess.MISSING -> "系统还没放行视频；点「授权视频」后才会申请 READ_MEDIA_VIDEO"
    VideoAccess.UNKNOWN -> "暂时无法确认视频权限，请稍后重试"
}
