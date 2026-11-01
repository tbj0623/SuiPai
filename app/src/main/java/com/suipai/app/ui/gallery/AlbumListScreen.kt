package com.suipai.app.ui.gallery

import java.time.format.DateTimeFormatter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.suipai.app.data.AlbumItem
import com.suipai.app.data.FastThumb
import com.suipai.app.data.Favorites
import com.suipai.app.data.Hidden
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppInlineSearch
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.rememberWideShortViewport
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.TextButtonTonal
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 相册列表。
 *
 * 这一屏原来是整套界面里最后一块没改造型过的地方：一条**不换行也不滚动**的 Row 里塞了
 * 近三十个 TextButton（411dp 宽的屏上排到两千多 dp，绝大多数筛选按不到），
 * 外加 17 个日期布尔 + 230 行几乎一样的 filter 链。
 * 现在：固定工具条 5 格 + 两个抽屉，窗口筛选查 [AlbumWindow] 表，开关查 [AlbumFlag] 表。
 */
/**
 * 一册相册卡面那行小字（也是读屏那句的正文）。
 *
 * 抽出来的理由和 `photoCellLabel` 一样：**看得见的和念出来的必须同一个来源**。
 * 之前它是 `Text(buildString { … })` 里的一段内联代码，读屏那边想复用得再抄一遍，
 * 而抄的那份迟早和界面分叉。
 */
/** 共享一个：`ofPattern` 每次调用都要解析一遍模式，挂在卡片的每次重组上就是浪费。 */
private val MonthDayCn: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

internal fun albumCellSub(
    album: AlbumItem,
    pinned: Boolean,
    hidden: Boolean,
    recent: Boolean,
    special: Boolean,
    sep: String,
): String = buildString {
    append("${album.count} 张")
    if (pinned) append(sep).append("钉住")
    if (hidden) append(sep).append("已藏")
    if (recent) append(sep).append("最近")
    if (album.lastTaken > 0L) {
        append(sep)
        append(
            java.time.Instant.ofEpochMilli(album.lastTaken)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(MonthDayCn),
        )
    }
}

/** 整张卡片在读屏里说的那一句：册名 + 上面那行小字 + 当前模式下的点法。 */
internal fun albumCellLabel(
    album: AlbumItem,
    pinned: Boolean,
    hidden: Boolean,
    recent: Boolean,
    isSpecial: Boolean,
    hideMode: Boolean,
): String = buildString {
    append(album.name)
    append("，")
    append(albumCellSub(album, pinned, hidden, recent, isSpecial, "，"))
    if (isSpecial) append("，系统册：整理藏册时也照常显示")
    if (hideMode && !isSpecial) {
        append("，正在整理藏册：点它是")
        append(if (hidden) "把它放出来" else "把它藏起来")
    }
    append("。长按是")
    append(if (pinned) "取消钉住" else "钉到最前面")
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumListScreen(
    albums: List<AlbumItem>,
    onOpen: (AlbumItem) -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("suipai", android.content.Context.MODE_PRIVATE) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(prefs.getInt("album_sort", 0)) }
    var coverDp by rememberSaveable {
        mutableIntStateOf(prefs.getInt("album_cover", 160).coerceIn(112, 220))
    }
    var albumMin by rememberSaveable {
        mutableIntStateOf(prefs.getInt("album_min", if (prefs.getBoolean("album_big", false)) 12 else 0))
    }
    var lastAlbum by rememberSaveable { mutableStateOf(prefs.getString("last_album", "") ?: "") }
    var flags by remember { mutableStateOf(AlbumFlags.load(prefs)) }
    var windows by remember { mutableStateOf(AlbumWindow.active(prefs)) }
    var pins by remember { mutableStateOf(prefs.getStringSet("album_pins", emptySet())?.toSet().orEmpty()) }
    var hides by remember { mutableStateOf(prefs.getStringSet("album_hides", emptySet())?.toSet().orEmpty()) }
    /** 0 = 都关着；1 = 筛选抽屉；2 = 显示抽屉。工具条因此不必横向滚动。 */
    var sheet by rememberSaveable { mutableIntStateOf(0) }

    val special = { a: AlbumItem -> a.id == Favorites.ALBUM_ID || a.id == Hidden.ALBUM_ID }
    val visible = remember(albums, query, sort, albumMin, lastAlbum, flags, windows, pins, hides) {
        val q = query.trim()
        var list = if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
        if (flags.onlySuiPai) {
            list = list.filter { special(it) || it.name.startsWith("SuiPai", ignoreCase = true) }
        }
        if (flags.hideSocial) list = list.filter { special(it) || !isSocialAlbum(it.name) }
        if (flags.hideDownload) list = list.filter { special(it) || !isDownloadAlbum(it.name) }
        if (flags.hideShots) list = list.filter { special(it) || !isScreenshotAlbum(it.name) }
        if (albumMin > 0) list = list.filter { special(it) || it.count >= albumMin }
        if (!flags.hideMode) list = list.filter { special(it) || it.id !in hides }
        // 多个日期窗口同时勾选 = 交集（原来的 19 段链式 filter 也是这个语义，与顺序无关）。
        windows.forEach { w ->
            val r = w.range()
            list = list.filter { special(it) || it.lastTaken in r }
        }
        val fav = list.filter { it.id == Favorites.ALBUM_ID }
        val hidden = list.filter { it.id == Hidden.ALBUM_ID }
        val rest = list.filter { !special(it) }
        val suipai = rest.filter { it.name.equals("SuiPai", ignoreCase = true) }
        val other = rest.filter { !it.name.equals("SuiPai", ignoreCase = true) }
        val recent = if (lastAlbum.isNotBlank()) other.filter { it.id == lastAlbum } else emptyList()
        val remain = other.filter { it.id != lastAlbum }
        val pinned = remain.filter { it.id in pins }
        val unpinned = remain.filter { it.id !in pins }
        val ordered = when (sort) {
            1 -> unpinned.sortedBy { it.name.lowercase() }
            2 -> unpinned.sortedByDescending { it.lastTaken }
            3 -> unpinned.sortedByDescending { it.name.lowercase() }
            4 -> unpinned.sortedBy { it.lastTaken }
            5 -> unpinned.sortedBy { it.count }
            else -> unpinned.sortedByDescending { it.count }
        }
        if (flags.onlyPins) fav + hidden + suipai + pinned else fav + hidden + suipai + recent + pinned + ordered
    }

    val scopeFlags = AlbumFlag.inGroup(AlbumFlagGroup.SCOPE).count { flags.get(it) }
    val filterBadge = windows.size + scopeFlags + (if (albumMin > 0) 1 else 0)
    val sortLabel = when (sort) {
        1 -> "按名称"
        2 -> "按最近"
        3 -> "名称倒序"
        4 -> "最旧"
        5 -> "数量升序"
        else -> "按数量"
    }
    val coverLabel = when (coverDp) {
        200 -> "大封面"
        112 -> "小封面"
        else -> "中封面"
    }
    val minLabel = when (albumMin) {
        12 -> "12张+"
        24 -> "24张+"
        48 -> "48张+"
        else -> "全部相册"
    }
    val emptyHint = when {
        visible.isNotEmpty() -> ""
        windows.isNotEmpty() -> windows.first().empty
        flags.onlyPins -> "还没有钉住的相册，长按封面钉住"
        hides.isNotEmpty() && !flags.hideMode -> "没有可见相册，点整理藏册"
        flags.hideSocial -> "没有不含社交相册的结果"
        flags.hideDownload -> "没有不含下载相册的结果"
        flags.hideShots -> "没有不含截图的相册"
        albumMin > 0 -> "没有超过${albumMin}张的相册"
        else -> "没有匹配的相册"
    }

    // 矮屏：搜索框并进工具条那一行，省下的 56 dp 整行还给相册网格。
    val inlineSearch = rememberWideShortViewport()
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            // 页名 + 册数就是这行的开头（契约：网格紧贴工具条，页首不再单独占一行）
            AppBarText("相册", style = SuiPaiSheet.type.pageHead)
            AppBarText(
                "${visible.size} ALBUMS",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (onRefresh != null) {
                // 重新扫描 MediaStore 今天只有"下拉"这一个入口，而下拉这个手势
                // 对读屏与开关访问的用户是不可发现的（SyncScreen 里就有「刷新」格子的先例）。
                AppBarCell(
                    label = "刷新",
                    on = false,
                    modifier = Modifier.testTag("album_refresh"),
                    onClick = { onRefresh() },
                )
            }
            AppBarCell(
                label = sortLabel,
                on = sort != 0,
                onClick = {
                    sort = (sort + 1) % 6
                    prefs.edit().putInt("album_sort", sort).apply()
                },
            )
            AppBarCell(
                label = coverLabel,
                on = coverDp != 160,
                onClick = {
                    coverDp = when (coverDp) {
                        200 -> 160
                        112 -> 200
                        else -> 112
                    }
                    prefs.edit().putInt("album_cover", coverDp).apply()
                },
            )
            AppBarCell(
                label = "筛选",
                on = sheet == 1 || filterBadge > 0,
                badge = filterBadge,
                onClick = { sheet = 1 },
            )
            AppBarCell(
                label = "显示",
                on = sheet == 2 || flags.roundCover != true || albumMin > 0,
                onClick = { sheet = 2 },
            )
            if (inlineSearch) {
                AppInlineSearch(query, { query = it })
            }
        }
        if (!inlineSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = {
                    // 单行输入框的提示语也必须单行：placeholder 不受 `singleLine` 管，
                    // 字级 2.0 + 窄屏时它自己折行，把输入框撑高、把网格顶出屏幕
                    // （装机量到 720×1600 / 字级 2.0：搜索框 528 px 高，时间线一颗不剩）。
                    Text("搜索相册名", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                // 「完成」收键盘，和印样那两处搜索框一致。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = { query = "" }) { Text("清除") }
                    }
                },
            )
        }
        val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
        val grid: @Composable () -> Unit = {
            if (albums.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "正在读取相册",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    } else {
                        // 一个相册都没有：这是"新装 App / 还没拍过照"的空态，
                        // 与下面"筛完没命中"是两件事，kicker 也分开写。
                        EmptyState(
                            kicker = "NO ALBUMS",
                            title = "还没有相册",
                            note = "拍一张照片，系统会把它们收进相册",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else if (visible.isEmpty()) {
                // 有相册但筛完没命中：主文案沿用 `emptyHint`（那张 when 表是判据，
                // 每个窗口各有各的说法），动作降级为次操作 —— 「清掉筛选」不是主操作。
                EmptyState(
                    kicker = "NO MATCH",
                    title = emptyHint,
                    note = if (filterBadge > 0) "还有 $filterBadge 处条件开着" else null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (filterBadge > 0) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(Modifier.padding(bottom = 96.dp)) {
                            TextButtonTonal(
                                label = "清掉筛选",
                                onClick = {
                                    windows.forEach { prefs.edit().putBoolean(it.prefKey, false).apply() }
                                    windows = emptyList()
                                    prefs.edit()
                                        .putBoolean("album_hide_shots", false)
                                        .putBoolean("album_suipai", false)
                                        .putBoolean("album_hide_social", false)
                                        .putBoolean("album_hide_dl", false)
                                        .putBoolean("album_only_pins", false)
                                        .putInt("album_min", 0)
                                        .apply()
                                    flags = AlbumFlags.load(prefs)
                                    albumMin = 0
                                },
                            )
                        }
                    }
                }
            } else LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(coverDp.dp),
                contentPadding = PaddingValues(bottom = 88.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { album ->
                    Column(
                        Modifier.combinedClickable(
                            // 这格上一个点击、一个长按，做的是两件完全不同的事
                            // （打开 / 钉住），而"整理藏册"模式下点击又变成收放。
                            // 不给动作起名字，读屏用户只能"两个无名动作"里猜。
                            onClickLabel = if (flags.hideMode && !special(album)) {
                                if (album.id in hides) "把这册放出来" else "把这册藏起来"
                            } else {
                                "打开这册"
                            },
                            onLongClickLabel = if (album.id in pins) "取消钉住" else "把这册钉到最前面",
                            onClick = {
                                if (flags.hideMode && !special(album)) {
                                    val next = hides.toMutableSet()
                                    if (album.id in next) next.remove(album.id) else next.add(album.id)
                                    hides = next
                                    prefs.edit().putStringSet("album_hides", next).apply()
                                } else {
                                    lastAlbum = album.id
                                    prefs.edit().putString("last_album", album.id).apply()
                                    onOpen(album)
                                }
                            },
                            onLongClick = {
                                val next = pins.toMutableSet()
                                if (album.id in next) next.remove(album.id) else next.add(album.id)
                                pins = next
                                prefs.edit().putStringSet("album_pins", next).apply()
                            },
                        )
                            // 卡面上看得见的一站式：张数、钉住、已藏、最近、日期。
                            // 这里必须用 clearAndSetSemantics：装机语义树量过，
                            // `semantics(mergeDescendants = true)` 会另起一个子节点，
                            // 结果带点击动作的那个节点是空标签 —— 标签念不到，正是要修的东西。
                            .clearAndSetSemantics {
                                contentDescription = albumCellLabel(
                                    album,
                                    album.id in pins,
                                    album.id in hides,
                                    album.id == lastAlbum && !special(album),
                                    special(album),
                                    flags.hideMode,
                                )
                            },
                    ) {
                        Box {
                            if (album.cover == null) {
                                // 空相册：占位图，而不是走加载失败分支。
                                //
                                // 描边用 `outline`（交互描边）而不是 `hairline`（装饰发丝线）：
                                // 整张卡是可点的，1.4.11 要求"能点的边界"对背景 ≥3:1，
                                // 而 hairline 只有 1.3:1 —— 用它的那颗卡看起来是"一块图"，
                                // 不是"一颗能点开的相册"。
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .background(SuiPaiColor.current.panelRaised)
                                        .border(1.dp, SuiPaiColor.current.outline),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Collections,
                                        contentDescription = album.name,
                                        tint = SuiPaiColor.current.inkTertiary,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            } else {
                                var coverRetry by remember(album.id) { mutableIntStateOf(0) }
                                key(coverRetry) {
                                    val coverReq = remember(album.id) {
                                        ImageRequest.Builder(context)
                                            .data(album.cover)
                                            .size(512)
                                            .tag(FastThumb(512))
                                            .crossfade(true)
                                            .memoryCacheKey("album-cover-${album.id}")
                                            .build()
                                    }
                                    val coverPainter = rememberAsyncImagePainter(model = coverReq)
                                    Image(
                                        painter = coverPainter,
                                        contentDescription = album.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(if (flags.roundCover) 12.dp else 2.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                    when (coverPainter.state) {
                                        is AsyncImagePainter.State.Success -> Unit
                                        is AsyncImagePainter.State.Error -> {
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable(
                                                        role = Role.Button,
                                                        onClickLabel = "重新读这张封面",
                                                        onClick = { coverRetry += 1 },
                                                    ),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    "无法显示",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                                Icon(
                                                    imageVector = Icons.Filled.Refresh,
                                                    contentDescription = "重试",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .padding(top = 4.dp)
                                                        .size(20.dp),
                                                )
                                            }
                                        }
                                        else -> {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                            RoundedCornerShape(2.dp),
                                        ),
                                ) {
                                    Text(
                                        "${album.count}",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = SuiPaiSheet.type.frameNo,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (album.id in pins) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(8.dp)
                                            .background(SuiPaiSheet.colors.grease, RoundedCornerShape(2.dp)),
                                    ) {
                                        Text(
                                            "钉",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (flags.hideMode && album.id in hides) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                                RoundedCornerShape(2.dp),
                                            ),
                                    ) {
                                        Text(
                                            "藏",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(album.name, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                        Text(
                            albumCellSub(
                                album,
                                album.id in pins,
                                album.id in hides,
                                album.id == lastAlbum && !special(album),
                                special(album),
                                "  ·  ",
                            ),
                            style = SuiPaiSheet.type.frameNo,
                            color = SuiPaiSheet.colors.number,
                        )
                    }
                }
            }
        }
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) { grid() }
        } else {
            grid()
        }
    }

    if (sheet == 1) {
        AppSheet(title = "筛选", tag = "album_sheet_filter", onDismiss = { sheet = 0 }) {
            AppSheetSection("看哪些相册", AlbumFlagGroup.SCOPE.hint)
            AppChipFlow {
                AlbumFlag.inGroup(AlbumFlagGroup.SCOPE).forEach { f ->
                    AppBarCell(
                        label = if (flags.get(f)) f.labelOn else f.labelOff,
                        on = flags.get(f),
                        onClick = {
                            flags = flags.toggled(f)
                            flags.saveTo(prefs)
                        },
                    )
                }
                AppBarCell(
                    label = minLabel,
                    on = albumMin > 0,
                    onClick = {
                        albumMin = when (albumMin) {
                            0 -> 12
                            12 -> 24
                            24 -> 48
                            else -> 0
                        }
                        prefs.edit().putInt("album_min", albumMin).apply()
                    },
                )
            }
            AppSheetSection("最近拍摄", "同时勾多个就是取交集")
            AppChipFlow {
                AlbumWindow.values().forEach { w ->
                    val on = w in windows
                    AppBarCell(
                        label = if (on) w.labelOn else w.labelOff,
                        on = on,
                        onClick = {
                            windows = if (on) windows - w else windows + w
                            prefs.edit().putBoolean(w.prefKey, !on).apply()
                        },
                    )
                }
            }
            if (windows.isNotEmpty()) {
                AppSheetSection("当前窗口", windows.joinToString(" · ") { it.labelOn })
            }
        }
    }
    if (sheet == 2) {
        AppSheet(title = "显示", tag = "album_sheet_display", onDismiss = { sheet = 0 }) {
            AppSheetSection("封面与排布", AlbumFlagGroup.LOOK.hint)
            AppChipFlow {
                AlbumFlag.inGroup(AlbumFlagGroup.LOOK).forEach { f ->
                    AppBarCell(
                        label = if (flags.get(f)) f.labelOn else f.labelOff,
                        on = flags.get(f),
                        onClick = {
                            flags = flags.toggled(f)
                            flags.saveTo(prefs)
                        },
                    )
                }
                AppBarCell(
                    label = coverLabel,
                    on = coverDp != 160,
                    onClick = {
                        coverDp = when (coverDp) {
                            200 -> 160
                            112 -> 200
                            else -> 112
                        }
                        prefs.edit().putInt("album_cover", coverDp).apply()
                    },
                )
                AppBarCell(
                    label = sortLabel,
                    on = sort != 0,
                    onClick = {
                        sort = (sort + 1) % 6
                        prefs.edit().putInt("album_sort", sort).apply()
                    },
                )
                if (pins.isNotEmpty()) {
                    AppBarCell(
                        label = "清掉 ${pins.size} 个钉",
                        on = false,
                        onClick = {
                            pins = emptySet()
                            prefs.edit()
                                .putStringSet("album_pins", emptySet())
                                .putBoolean("album_only_pins", false)
                                .apply()
                            flags = AlbumFlags.load(prefs)
                        },
                    )
                }
            }
            AppSheetSection("藏起来的相册", "${hides.size} 个；整理藏册模式下点封面即藏/取消")
        }
    }
}

private fun isSocialAlbum(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("wechat") ||
        n.contains("weixin") ||
        n.contains("微信") ||
        n.contains("微博") ||
        n.contains("weibo") ||
        n.contains("whatsapp") ||
        n.contains("telegram") ||
        n.contains("钉钉") ||
        n.contains("dingtalk") ||
        n == "qq" ||
        n.contains("qq_") ||
        n.contains("qq相册") ||
        n.contains("qq图片")
}

private fun isDownloadAlbum(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("download") || n.contains("下载")
}

private fun isScreenshotAlbum(name: String): Boolean {
    return name.contains("Screenshot", ignoreCase = true) ||
        name.contains("截屏") ||
        name.contains("截图")
}
