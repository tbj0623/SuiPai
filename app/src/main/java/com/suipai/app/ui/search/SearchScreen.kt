package com.suipai.app.ui.search

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import com.suipai.app.data.HitReason
import com.suipai.app.ui.design.Divider
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.design.Touch
import com.suipai.app.ui.design.pressable

/**
 * 搜索页。**参数全部是"数据 + 回调"** —— 这一层不持有仓库、不开协程、不落盘。
 *
 * 这样分的理由是可验证性：真实数据在真机上才跑得动（MediaStore + 权限），
 * 而这页有四种空态、四种分组、两种索引状态，每一种都得能单独摆出来看。
 * 现在它们全是入参，`@Preview` 或设备探针只要把参数摆好就能逐一核对。
 *
 * ## 四种空态（互斥，顺序就是判据）
 *  1. **没输入** → 历史 + 建议。这是"搜索还没开始"，不是"没搜到"。
 *  2. **正在索引** → 进度（索引没建完时搜到的永远只是一部分，必须先说清这件事）。
 *  3. **有输入无结果** → 说"只搜了已索引的 N 张"（没索引完时）+ 建议/历史。
 *  4. **索引失败** → `note` 已经带着原因；这里把"能怎么办"也说清（重建/重试）。
 *  另外有一态不算空态：**有结果**（分组渲染）。
 *
 * @param indexing 索引器正在跑。
 * @param indexProgress (已索引, 总数)。总数 ≤0 表示数不出来 —— 那时只报已索引数，
 *   不许编一个分母出来（编出来的百分比会在 37% 停住，看起来像卡死）。
 * @param note 一句话说明。索引失败、只搜了一部分、权限只有部分照片，都走这一句。
 */
@Composable
fun SearchScreen(
    history: List<String>,
    suggestions: List<String>,
    results: List<SearchResultGroup>,
    indexing: Boolean,
    indexProgress: Pair<Int, Int>?,
    query: String,
    onQuery: (String) -> Unit,
    onPickSuggestion: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onSelect: (Set<Long>) -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    val total = results.sumOf { it.photos.size }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("search_screen"),
    ) {
        SearchTopBar(onBack = onBack, title = "搜索")
        SearchField(
            query = query,
            onQuery = onQuery,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
        // note 不是装饰：它承担"这次结果为什么只有这么点"的唯一解释。
        if (note != null) {
            Text(
                note,
                style = SuiPaiType.labelMedium,
                color = colors.inkSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xxs)
                    .testTag("search_note"),
            )
        }
        if (total > 0) {
            // 有结果时"全选"是这一页唯一的主操作：搜索的价值一半在于"找到之后批量处理"。
            SelectionBar(
                count = total,
                onSelectAll = { onSelect(results.flatMap { group -> group.photos.map { it.photoId } }.toSet()) },
                onClearSelection = { onSelect(emptySet()) },
            )
        }
        Divider()
        // 四种空态的判据顺序就是这里的分支顺序，别调：
        //   没输入 → 正在索引（且还没有结果）→ 有输入无结果 → 有结果。
        // "有结果"时即使索引还在跑也直接展示结果 —— 有东西看比看进度强。
        when {
            query.isBlank() -> SearchIdle(
                history = history,
                suggestions = suggestions,
                onPickSuggestion = onPickSuggestion,
                onClearHistory = onClearHistory,
                indexing = indexing,
                indexProgress = indexProgress,
            )
            total == 0 && indexing -> SearchIndexing(progress = indexProgress)
            total == 0 -> SearchNoResults(
                query = query,
                note = note,
                suggestions = suggestions,
                onPickSuggestion = onPickSuggestion,
                indexProgress = indexProgress,
            )
            else -> SearchResults(
                results = results,
                onOpenPhoto = onOpenPhoto,
                onPickGroup = { group -> onQuery(group.title) },
            )
        }
    }
}

// ── 顶栏 ─────────────────────────────────────────────────────────────

@Composable
private fun SearchTopBar(onBack: () -> Unit, title: String) {
    val colors = SuiPaiColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.toolRow)
            .padding(horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「返回」做成文字而不是箭头图标：这个世界的工具条只有文字格子，
        // 而 `pressable` 已经带 role=Button 与 onClickLabel，读屏念得出它是动作。
        Box(
            Modifier
                .heightIn(min = Touch.min)
                .pressable(onClickLabel = "返回", onClick = onBack)
                .padding(horizontal = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text("返回", style = SuiPaiType.labelLarge, color = colors.ink, maxLines = 1)
        }
        Spacer(Modifier.width(Spacing.xxs))
        Text(
            title,
            style = SuiPaiType.titleMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 结果选择条：**只在有结果时出现**。
 *
 * 两个动作：全选（把这一页所有命中的 id 交给调用方）、取消选择。
 * 这里不显示"已选 N" —— 已选状态属于上层的多选模型（`GalleryScreen` 那套），
 * 而这页拿不到它；传进来的 `onSelect` 就是"把这一批交出去"这个动作本身。
 */
@Composable
private fun SelectionBar(count: Int, onSelectAll: () -> Unit, onClearSelection: () -> Unit) {
    val colors = SuiPaiColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            "找到 $count 张",
            style = SuiPaiType.frameNo,
            color = colors.safelight,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        TextAction("全选") { onSelectAll() }
        TextAction("取消选择") { onClearSelection() }
    }
}

/** 一颗文字动作：48dp 命中区 + 自己报名字。 */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val colors = SuiPaiColor.current
    Box(
        Modifier
            .heightIn(min = Touch.min)
            .pressable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = SuiPaiType.labelMedium, color = colors.inkSecondary, maxLines = 1)
    }
}

// ── 空态 1：没输入 ───────────────────────────────────────────────────

@Composable
private fun SearchIdle(
    history: List<String>,
    suggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    onClearHistory: () -> Unit,
    indexing: Boolean,
    indexProgress: Pair<Int, Int>?,
) {
    val colors = SuiPaiColor.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("search_idle"),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (indexing) {
            // 索引还没建完就开始搜是很自然的事（用户不会等），所以这一条摆在最上面。
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        indexLine(indexProgress),
                        style = SuiPaiType.frameNo,
                        color = colors.safelight,
                        maxLines = 1,
                        modifier = Modifier.testTag("search_indexing_line"),
                    )
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "最近搜过",
                        style = SuiPaiType.labelLarge,
                        color = colors.ink,
                        maxLines = 1,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.weight(1f))
                    TextAction("清空") { onClearHistory() }
                }
            }
            items(history) { term ->
                TermRow(term, onClick = { onPickSuggestion(term) }, tag = "search_history_row")
            }
        }
        if (suggestions.isNotEmpty()) {
            item {
                Text(
                    "可以试试",
                    style = SuiPaiType.labelLarge,
                    color = colors.ink,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .semantics { heading() },
                )
            }
            items(suggestions) { term ->
                TermRow(term, onClick = { onPickSuggestion(term) }, tag = "search_suggestion_row")
            }
        }
        if (history.isEmpty() && suggestions.isEmpty() && !indexing) {
            item {
                EmptyState(
                    title = "还没有可搜的东西",
                    kicker = "NO INDEX",
                    note = "索引还没建好时只能搜文件名与相册名。回到相册下拉一次试试，" +
                        "或者等这台机器把照片清点完。",
                )
            }
        }
    }
}

/** 一个可点的词条：整行 48dp 命中区，右侧一颗淡箭头位留给"填进输入框"。 */
@Composable
private fun TermRow(term: String, onClick: () -> Unit, tag: String) {
    val colors = SuiPaiColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.min)
            .pressable(onClickLabel = "用「$term」搜索", onClick = onClick)
            .padding(vertical = Spacing.xxs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            term,
            style = SuiPaiType.bodyMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── 空态 2：正在索引 ─────────────────────────────────────────────────

@Composable
private fun SearchIndexing(progress: Pair<Int, Int>?) {
    val colors = SuiPaiColor.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .testTag("search_indexing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 漏光那道线：这是本世界"正在发生什么"的唯一装饰（空态、加载占位都用它）。
        Box(
            Modifier
                .width(120.dp)
                .height(Touch.selection)
                .background(colors.leakBrush()),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            indexLine(progress),
            style = SuiPaiType.frameNo,
            color = colors.safelight,
            maxLines = 2,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "正在清点这台机器上的照片。索引建完之前，搜到的只是已经清点过的那一部分。",
            style = SuiPaiType.bodyMedium,
            color = colors.inkSecondary,
        )
    }
}

/** 进度那一行。**数不出总数时只报已索引数**，不编分母。 */
internal fun indexLine(progress: Pair<Int, Int>?): String {
    if (progress == null) return "正在清点照片…"
    val done = progress.first.coerceAtLeast(0)
    val all = progress.second
    return if (all > 0) "已索引 $done / $all 张" else "已索引 $done 张"
}

// ── 空态 3：有输入无结果 ─────────────────────────────────────────────

@Composable
private fun SearchNoResults(
    query: String,
    note: String?,
    suggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    indexProgress: Pair<Int, Int>?,
) {
    val colors = SuiPaiColor.current
    val covered = indexProgress?.first?.takeIf { it > 0 }
    LazyColumn(
        Modifier.fillMaxSize().testTag("search_no_results"),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        item {
            EmptyState(
                title = "没有匹配「$query」的照片",
                kicker = "0 FRAMES",
                // 这一句是这一页最要紧的一句：搜索**只覆盖已索引的那部分**。
                // 说"没找到"而不说这个，等于把"还没扫到"说成"不存在"。
                // 索引跑过一次之后（`covered` 非空且没有 note）就退化成普通的一句"换个词"。
                note = note ?: if (covered != null) {
                    "这次只搜了已索引的 $covered 张。"
                } else {
                    "换个词试试 —— 文件名、相册名、相机型号、年份都能搜。"
                },
            )
        }
        if (suggestions.isNotEmpty()) {
            item {
                Text(
                    "换个词试试",
                    style = SuiPaiType.labelLarge,
                    color = colors.ink,
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() },
                )
            }
            items(suggestions) { term ->
                TermRow(term, onClick = { onPickSuggestion(term) }, tag = "search_suggestion_row")
            }
        }
    }
}

// ── 有结果：分组 ─────────────────────────────────────────────────────

@Composable
private fun SearchResults(
    results: List<SearchResultGroup>,
    onOpenPhoto: (Long) -> Unit,
    onPickGroup: (SearchResultGroup) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("search_results"),
        contentPadding = PaddingValues(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        // 每组两块：标题（含"搜这一组"）与一条横向预览行。
        // 写成 `results.forEach { item {…}; item {…} }` 而不是 `items(results) { … item { } }`——
        // 后者的 content 是**一个 item 的 content 槽**，里面再开 `item{}` 编译得过但什么也不渲染。
        results.forEach { group ->
            item(key = "head-${group.kind}-${group.title}") {
                GroupHeader(group = group, onPick = { onPickGroup(group) })
            }
            item(key = "row-${group.kind}-${group.title}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
                ) {
                    items(group.photos, key = { it.photoId }) { photo ->
                        ResultThumb(photo = photo, onClick = { onOpenPhoto(photo.photoId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SearchResultGroup, onPick: () -> Unit) {
    val colors = SuiPaiColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.sm, bottom = Spacing.xxs)
            .heightIn(min = Touch.min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.title,
            style = SuiPaiType.labelLarge,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { heading() },
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            "${group.photos.size} 张",
            style = SuiPaiType.frameNo,
            color = colors.safelight,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // 组标题自己可点：点它 = 用这个标题再搜一次（"相机型号 / 年份"这两组的自然动作）。
        TextAction("搜这一组") { onPick() }
    }
}

@Composable
private fun ResultThumb(photo: SearchPhoto, onClick: () -> Unit) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    // ImageRequest 没有 equals：直接写在 composable 里等于每重组一次就是一个"新"请求，
    // Coil 会重新派发（滚动时整屏格子都在重组，表现是一边滚一边闪）。按照片记住它。
    val request = remember(photo.photoId) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .size(ThumbEdgePx)
            .tag(FastThumb(ThumbEdgePx))
            .crossfade(true)
            .build()
    }
    Box(
        Modifier
            .width(ThumbWidth)
            .aspectRatio(1f)
            .clip(SuiPaiShapes.frame)
            .border(Touch.hairline, colors.outline, SuiPaiShapes.frame)
            .pressable(onClickLabel = "打开这张照片", onClick = onClick)
            .testTag("search_thumb"),
    ) {
        AsyncImage(
            model = request,
            // 名字由整格的 `onClickLabel` + 下面那行小字承担，这里再报一次会被念两遍。
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (photo.reason != null) {
            // 命中原因是**看得见**的：用户得知道"为什么这张会出现在这里"，
            // 否则搜「上海」跳出一张名字里没有上海的照片就成了玄学。
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xxs)
                    .background(colors.canvas.copy(alpha = 0.82f)),
            ) {
                Text(
                    photo.reason,
                    style = SuiPaiType.frameNo,
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 缩略图尺寸（dp 与 px 两个刻度，一处定义）。 */
private val ThumbWidth = 96.dp
private const val ThumbEdgePx = 320

// ── 入参模型 ─────────────────────────────────────────────────────────

/**
 * 结果分组。四类：照片（按命中原因再分组）／相册／相机型号／年份。
 *
 * `@Immutable` 不是文档：这些对象会进 `LazyColumn` / `LazyRow`，
 * 模型不稳定会让 Compose 在每次输入时把整屏格子全部重组。
 */
@Immutable
data class SearchResultGroup(
    val title: String,
    val kind: SearchGroupKind,
    val photos: List<SearchPhoto>,
)

@Immutable
data class SearchPhoto(
    val photoId: Long,
    val uri: Uri,    /** 这一格为什么命中（"文件名"/"相册"/…）。null = 不显示角标。 */
    val reason: String? = null,
)

/** 分组的来源。调用方据此决定"点这一组"是什么意思（再搜一次 / 打开相册 / …）。 */
enum class SearchGroupKind { PHOTO, ALBUM, CAMERA, YEAR }

/** 把 `HitReason` 翻成人话。列表在 `docs/product/SEARCH.md` 里是同一张表。 */
fun HitReason.label(): String = when (this) {
    HitReason.FILE_NAME -> "文件名"
    HitReason.ALBUM -> "相册"
    HitReason.DATE -> "日期"
    HitReason.CAMERA -> "相机"
    HitReason.LENS -> "镜头"
    HitReason.TYPE -> "类型"
}
