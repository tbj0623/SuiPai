package com.suipai.app.ui.gallery

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.suipai.app.ui.theme.SuiPaiSheet
import com.suipai.app.ui.common.Motion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppInlineSearch
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.openPhotoAccessSettings
import com.suipai.app.ui.common.rememberShortViewport
import com.suipai.app.ui.common.rememberWideShortViewport
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.design.TextButtonTonal
import com.suipai.app.ui.design.greaseSelection
import com.suipai.app.ui.design.hairline
import com.suipai.app.ui.partialPhotoNotice
import com.suipai.app.ui.photoAccessProbe
import com.suipai.core.engine.LookLibrary
import com.suipai.app.settings.settingsBool
import com.suipai.app.settings.storedChoiceName
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    photos: List<PhotoItem>,
    onOpen: (PhotoItem) -> Unit,
    title: String? = "照片",
    onBack: (() -> Unit)? = null,
    emptyHint: String = "还没有照片。点快门拍一张。",
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: ((PhotoItem) -> Unit)? = null,
    onLongPress: ((PhotoItem) -> Unit)? = null,
    onCancelSelect: (() -> Unit)? = null,
    onBatchYishun: ((String) -> Unit)? = null,
    onBatchDelete: (() -> Unit)? = null,
    onCancelBatch: (() -> Unit)? = null,
    onSelectPhotos: ((List<PhotoItem>) -> Unit)? = null,
    batchHint: String? = null,
    /** 这一屏的数据是不是"没读到"（不是"读到了、确实没有"）。空态第一句要用它。 */
    problem: String? = null,
    /** 这一页自己要做但做不成的事，写到页眉那一格（和批量结论同一个位置）。 */
    onNote: (String) -> Unit = {},
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    favoriteIds: Set<Long> = emptySet(),
    favoritePhotos: List<PhotoItem> = emptyList(),
    onBatchFavorite: (() -> Unit)? = null,
    onInvert: ((List<PhotoItem>) -> Unit)? = null,
    onBatchCopy: (() -> Unit)? = null,
    hiddenIds: Set<Long> = emptySet(),
    hiddenPhotos: List<PhotoItem> = emptyList(),
    onBatchHide: (() -> Unit)? = null,
    showHiddenInAll: Boolean = false,
) {
    // 矮屏（手机横屏那一类）纵向最缺，所以那里把搜索框并进工具条、日期行收掉留白。
    // 两个判定分开：窄而矮的是横屏分屏，那种宽度塞不下内嵌框，得退回原来的独占一行。
    val compactChrome = rememberShortViewport()
    val inlineSearch = rememberWideShortViewport()
    var emptyRetry by rememberSaveable { mutableIntStateOf(0) }
    // 「只授权了部分照片」（Android 14+ 的 partial access）。判据与文案都是
    // `ui/PermissionGate.kt` 里的纯函数，这一屏只负责在页眉和空态里把它说出来。
    // 状态从缓存读、不在这里查权限：这一屏没有"权限可能变了"的时机，
    // 而 `checkSelfPermission` / `resolveActivity` 都是 binder 调用（13 号、17 号守卫那一族的老账）。
    val context = LocalContext.current
    val probe = photoAccessProbe.value
    val partialNotice = probe?.let {
        partialPhotoNotice(
            it.access,
            // 分页还没读到底的时候 `photos.size` 不是"系统放行的全部"，宁可不带数字
            visibleCount = if (hasMore) null else photos.size,
            settingsReachable = it.settingsReachable,
        )
    }
    if (photos.isEmpty()) {
        val empty = @Composable {
            Column(Modifier.fillMaxSize()) {
                if (onBack != null) {
                    // 空相册也得有看得见的出口：只靠系统 BACK 键离开，用户会以为自己被关在这里
                    // （真机上「收藏」为空时就是这样一屏黑，实测只能盲按 BACK）。
                    AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        AppBarText(
                            "‹ $title",
                            style = SuiPaiSheet.type.pageHead,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable(onClick = onBack)
                                .testTag("gallery_back"),
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "正在读取相册",
                                style = SuiPaiType.frameNo,
                                color = SuiPaiColor.current.inkSecondary,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    } else {
                        // 空态用 `EmptyState`：一条漏光 + 等宽 kicker + 主文案 + 动作。
                        //
                        // 主文案的优先级**一个字都没改** —— `partialNotice` 压过 `emptyHint`
                        // 是判据（四行真值表钉在 `PartialAccessTest`）：
                        // 部分授权时说「还没有照片。点快门拍一张」是**假话**，
                        // 用户相册里有几百张，只是系统没交给我们。
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(
                                kicker = when {
                                    partialNotice != null -> "PARTIAL ACCESS"
                                    emptyRetry > 0 -> "READ FAILED"
                                    else -> "00 / 00 FRAMES"
                                },
                                title = partialNotice ?: if (emptyRetry > 0) {
                                    "读取相册失败，或没有可显示的照片。"
                                } else {
                                    emptyHint
                                },
                                note = if (partialNotice == null && emptyRetry == 0) {
                                    "拍完的照片会出现在这里"
                                } else {
                                    null
                                },
                            )
                            // 两个动作分成两级：补授权是**主操作**（唯一实心），
                            // 重试是次操作（无底色文字按钮）。
                            // 两格都没有时不许留一个空的容器：它会白占一截顶边距。
                            if (partialNotice != null || onRefresh != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (partialNotice != null) {
                                        PhotoAccessCell(
                                            enabled = probe?.settingsReachable == true,
                                            onNote = onNote,
                                        )
                                    }
                                    if (onRefresh != null) {
                                        TextButtonTonal(
                                            label = "重试",
                                            onClick = {
                                                emptyRetry += 1
                                                onRefresh()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) { empty() }
        } else {
            empty()
        }
        return
    }
    val focus = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("suipai", android.content.Context.MODE_PRIVATE) }
    // ── 设置中心那几项：`cfg_` 是真源，老键只在"从没写过 cfg"时兜底 ──
    //
    // 这一段是本文件唯一读设置的地方。写法是"存过就按 cfg、没存过才看老键"：
    // 升级上来的用户第一次打开相册，看到的还是他自己在老版本里挑的网格与排序，
    // 而不是被静默重置成默认值。详见 `settings/SettingsStore.kt` 里那三个扩展函数。
    val settings = remember { com.suipai.app.settings.SettingsStore(prefs) }
    val cfgGrid = com.suipai.app.settings.SettingsBridge.gridColumnsInitial(
        prefs.storedChoiceName(com.suipai.app.settings.SettingsKeys.Appearance.gridColumns),
        prefs.getInt("grid_cell", 118),
    )
    val cfgThumb = com.suipai.app.settings.SettingsBridge.thumbQualityInitial(
        prefs.storedChoiceName(com.suipai.app.settings.SettingsKeys.Appearance.thumbQuality),
        420,
    )
    val cfgSort = com.suipai.app.settings.SettingsBridge.defaultSortInitial(
        prefs.storedChoiceName(com.suipai.app.settings.SettingsKeys.Gallery.defaultSort),
        prefs.getInt(
            "gallery_sort",
            if (prefs.getBoolean("newest_first", true)) 0 else 1,
        ),
    )
    val cfgGroup = com.suipai.app.settings.SettingsBridge.groupByInitial(
        prefs.storedChoiceName(com.suipai.app.settings.SettingsKeys.Gallery.groupBy),
        prefs.getInt("group_mode", if (prefs.getBoolean("group_month", false)) 1 else 0),
    )
    // 「显示已隐藏」：设置页与相册自己的「已隐藏」入口是**或**的关系。
    // 不用"设置覆盖入口"，因为那个入口是"我现在要看隐藏的"这个临时意图，
    // 而设置是"我一直想看"。两者取或才不会出现"设置里开着却还是看不到"。
    val cfgShowHidden = prefs.settingsBool(com.suipai.app.settings.SettingsKeys.Gallery.showHidden, false)
    // 「删除前确认」（通用那一组）。默认**要**确认：批量删除不可逆，这一条宁可多问一次。
    // 它是唯一一条"关掉之后少了步骤"的设置，所以读在批量工具条那份代码旁边，别处不抄第二份。
    val cfgConfirmDelete = prefs.settingsBool(
        com.suipai.app.settings.SettingsKeys.General.confirmDelete,
        true,
    )
    val thumbPx = com.suipai.app.settings.SettingsBridge.thumbEdgePx(cfgThumb)
    var filter by rememberSaveable {
        mutableIntStateOf(
            prefs.getInt(
                "gallery_filter",
                if (prefs.getBoolean("only_yishun", false)) 1 else 0,
            ),
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    var cellDp by rememberSaveable {
        mutableIntStateOf(com.suipai.app.settings.SettingsBridge.gridCellDp(cfgGrid))
    }
    var newestFirst by rememberSaveable {
        mutableStateOf(com.suipai.app.settings.SettingsBridge.newestFirst(cfgSort))
    }
    var sortMode by rememberSaveable {
        mutableIntStateOf(com.suipai.app.settings.SettingsBridge.gallerySortCode(cfgSort))
    }
    var orient by rememberSaveable { mutableIntStateOf(prefs.getInt("orient_filter", 0)) }
    var gflags by remember { mutableStateOf(GalleryFlags.load(prefs)) }
    /** 0 = 都关着；1 = 筛选弹层；2 = 显示与隐藏弹层。顶栏因此不必横向滚动。 */
    var gSheet by remember { mutableIntStateOf(0) }
    var extFilter by rememberSaveable { mutableIntStateOf(prefs.getInt("ext_filter", 0)) }
    var recency by rememberSaveable { mutableIntStateOf(prefs.getInt("gallery_recency", 0)) }
    var mega by rememberSaveable { mutableIntStateOf(prefs.getInt("mega_filter", 0)) }
    var gridGap by rememberSaveable { mutableIntStateOf(prefs.getInt("grid_gap", 2).let { if (it == 8) 8 else 2 }) }
    var minMb by rememberSaveable { mutableIntStateOf(prefs.getInt("min_mb", 0)) }
    var minSide by rememberSaveable { mutableIntStateOf(prefs.getInt("min_side", 0)) }
    var hourFilter by rememberSaveable { mutableIntStateOf(prefs.getInt("hour_filter", 0)) }
    var weekFilter by rememberSaveable { mutableIntStateOf(prefs.getInt("week_filter", 0)) }
    var ratioFilter by rememberSaveable { mutableIntStateOf(prefs.getInt("ratio_filter", 0)) }
    var bucketFilter by rememberSaveable { mutableStateOf(prefs.getString("gallery_bucket", "") ?: "") }
    var groupMode by rememberSaveable {
        mutableIntStateOf(com.suipai.app.settings.SettingsBridge.groupModeCode(cfgGroup))
    }
    // 设置页那一条「显示已隐藏」与相册自己的「已隐藏」入口（参数 `showHiddenInAll`）取**或**。
    // 为什么不覆盖：那个参数是"我现在要看隐藏的"这个临时意图（进的是隐藏相册），
    // 而设置是"我一直想看到它们"。两者取或，才不会出现"设置里明明开着却还是看不到"。
    val showHiddenEffective = showHiddenInAll || cfgShowHidden
    val visible = remember(photos, filter, query, favoriteIds, favoritePhotos, orient, gflags.hideShots, gflags.hideCopies, gflags.hideEdits, gflags.hideYishun, gflags.hideCompare, gflags.hideTurned, gflags.hideWide, gflags.hidePostcard, gflags.hideMirror, gflags.hideGray, gflags.hideFlip, gflags.hideRound, gflags.hide2k, gflags.hide1080, gflags.hide720, gflags.hide480, gflags.hide320, gflags.hide240, gflags.hide160, gflags.hide120, gflags.hide96, gflags.hide64, gflags.hide48, gflags.hide32, gflags.hide24, gflags.hide16, gflags.hideGoldenCrop, gflags.hideAcademy, extFilter, recency, mega, minMb, minSide, hourFilter, weekFilter, ratioFilter, bucketFilter, hiddenIds, hiddenPhotos, showHiddenInAll, cfgShowHidden) {
        visiblePhotos(
            photos,
            GalleryFilters(
                filter = filter,
                query = query,
                favoriteIds = favoriteIds,
                favoritePhotos = favoritePhotos,
                orient = orient,
                hideShots = gflags.hideShots,
                hideCopies = gflags.hideCopies,
                hideEdits = gflags.hideEdits,
                hideYishun = gflags.hideYishun,
                hideCompare = gflags.hideCompare,
                hideTurned = gflags.hideTurned,
                hideWide = gflags.hideWide,
                hidePostcard = gflags.hidePostcard,
                hideMirror = gflags.hideMirror,
                hideGray = gflags.hideGray,
                hideFlip = gflags.hideFlip,
                hideRound = gflags.hideRound,
                hide2k = gflags.hide2k,
                hide1080 = gflags.hide1080,
                hide720 = gflags.hide720,
                hide480 = gflags.hide480,
                hide320 = gflags.hide320,
                hide240 = gflags.hide240,
                hide160 = gflags.hide160,
                hide120 = gflags.hide120,
                hide96 = gflags.hide96,
                hide64 = gflags.hide64,
                hide48 = gflags.hide48,
                hide32 = gflags.hide32,
                hide24 = gflags.hide24,
                hide16 = gflags.hide16,
                extFilter = extFilter,
                recency = recency,
                mega = mega,
                minMb = minMb,
                minSide = minSide,
                hourFilter = hourFilter,
                weekFilter = weekFilter,
                ratioFilter = ratioFilter,
                bucketFilter = bucketFilter,
                hiddenIds = hiddenIds,
                hiddenPhotos = hiddenPhotos,
                showHiddenInAll = showHiddenEffective,
            ),
        )
    }
    // 页眉两格的徽标数，空态那句话也要用这两个数。
    // 装机复测：搜一个只被「藏副本」藏起来的词，屏幕只说"没有匹配的照片" ——
    // 可那张照片是被藏了，不是不匹配。两处各抄一份清单就会说岔，所以这里只算一遍。
    val filterChips = listOf(
        orient != 0, extFilter != 0, recency != 0, mega != 0, minMb != 0, minSide != 0,
        hourFilter != 0, weekFilter != 0, ratioFilter != 0, bucketFilter.isNotEmpty(), filter != 0,
    ).count { it }
    val hideChips = GalleryFlag.inGroup(GalleryFlagGroup.HIDE).count { it.get(gflags) }
    val sections = remember(visible, newestFirst, groupMode, sortMode) {
        val grouped = when (groupMode) {
            1 -> groupByMonth(visible)
            2 -> groupByYear(visible)
            3 -> groupByWeek(visible)
            else -> groupByDay(visible)
        }
        when (sortMode) {
            1 -> grouped.asReversed().map { it.copy(photos = it.photos.asReversed()) }
            2 -> grouped.map { it.copy(photos = it.photos.sortedByDescending { p -> p.sizeBytes }) }
            3 -> grouped.map { it.copy(photos = it.photos.sortedBy { p -> p.sizeBytes }) }
            4 -> grouped.map { it.copy(photos = it.photos.sortedBy { p -> p.displayName.lowercase() }) }
            5 -> grouped.map {
                it.copy(photos = it.photos.sortedByDescending { p -> p.width.toLong() * p.height })
            }
            else -> grouped
        }
    }
    val years = remember(sections) { sections.map { it.key.take(4) }.distinct() }
    val months = remember(sections) { sections.map { it.key.take(7) }.distinct() }
    val scope = rememberCoroutineScope()
    var pickLook by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var peekPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    // 网格行号 → 第几组：每组占 1 行标题 + N 张照片，所以这张表只跟分组走。
    val starts = remember(sections) { sectionStarts(sections.map { it.photos.size }) }
    // 横带只在「本组自己的标题行已经滚出去」时出现：标题还在屏幕上就再压一条横带是重复。
    // 收起的那一刻要留住刚才那一组的名字，否则淡出过程中闪出来的是下一组的标题。
    val position = remember(starts) {
        derivedStateOf {
            galleryPosition(starts, gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1)
        }
    }
    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty()) confirmDelete = false
    }
    LaunchedEffect(gridState, photos.size, hasMore, loadingMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (hasMore && !loadingMore && total > 0 && last >= total - 12) {
                    onLoadMore?.invoke()
                }
            }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (selectedIds.isNotEmpty()) {
            // 选择条会换行，不横滚：横滚出去的「隐藏 / 删除 / 一瞬」既看不见也不在语义树里，
            // 而它们正是多选之后最该一戳就中的那几个。
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            AppChipFlow {
                AppBarCell(
                    label = if (batchHint != null) "停止" else "取消",
                    on = batchHint != null,
                    onClick = {
                        if (batchHint != null) onCancelBatch?.invoke()
                        else onCancelSelect?.invoke()
                    },
                )
                Text(
                    if (batchHint != null) {
                        "处理 $batchHint"
                    } else {
                        // 要 remember：这一趟是**整库**扫一遍挑出选中的，
                        // 而多选期间每点一张、每写一次进度（批量一瞬最多 30 次）都会重组这一段。
                        val bytes = remember(photos, selectedIds) {
                            photos.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
                        }
                        val size = when {
                            bytes >= 1_000_000L -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
                            bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
                            else -> ""
                        }
                        if (size.isBlank()) "已选 ${selectedIds.size}"
                        else "已选 ${selectedIds.size}  ·  $size"
                    },
                )
                if (batchHint == null) {
                    AppBarCell(
                        label = "全选",
                        on = false,
                        onClick = { onSelectPhotos?.invoke(visible) },
                    )
                    AppBarCell(
                        label = "反选",
                        on = false,
                        onClick = { onInvert?.invoke(visible) },
                    )
                    AppBarCell(
                        label = "分享",
                        on = false,
                        onClick = {
                            val picked = photos.filter { it.id in selectedIds }
                            if (picked.isEmpty()) return@AppBarCell
                            val send = if (picked.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, picked.first().uri)
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "image/jpeg"
                                    putParcelableArrayListExtra(
                                        Intent.EXTRA_STREAM,
                                        ArrayList(picked.map { it.uri }),
                                    )
                                }
                            }
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            sendOrTell(context, Intent.createChooser(send, "分享"), "分享") { onNote(it) }
                        },
                    )
                    if (onBatchCopy != null) {
                        AppBarCell(
                            label = "副本",
                            on = false,
                            onClick = {
                                confirmDelete = false
                                onBatchCopy()
                            },
                        )
                    }
                    if (onBatchFavorite != null) {
                        AppBarCell(
                            label = if (selectedIds.isNotEmpty() && selectedIds.all { it in favoriteIds }) "取消藏"
                            else "收藏",
                            on = selectedIds.isNotEmpty() && selectedIds.all { it in favoriteIds },
                            onClick = {
                                confirmDelete = false
                                onBatchFavorite()
                            },
                        )
                    }
                    if (onBatchHide != null) {
                        AppBarCell(
                            label = if (selectedIds.isNotEmpty() && selectedIds.all { it in hiddenIds }) "取消隐藏"
                            else "隐藏",
                            on = selectedIds.isNotEmpty() && selectedIds.all { it in hiddenIds },
                            onClick = {
                                confirmDelete = false
                                onBatchHide()
                            },
                        )
                    }
                    if (onBatchDelete != null) {
                        AppBarCell(
                            label = if (confirmDelete) "确认删除" else "删除",
                            on = confirmDelete,
                            onClick = {
                                when {
                                    // ① 已经点过一次"删除"，这一下才真的删。
                                    confirmDelete -> {
                                        confirmDelete = false
                                        onBatchDelete()
                                    }
                                    // ② 设置里关掉了「删除前确认」：一下删掉，不再多问。
                                    !cfgConfirmDelete -> onBatchDelete()
                                    // ③ 默认：第一次只是把格子换成"确认删除"。
                                    else -> {
                                        confirmDelete = true
                                        pickLook = false
                                    }
                                }
                            },
                        )
                    }
                }
                AppBarPrimary(
                    label = batchHint ?: if (pickLook) "收起" else "一瞬",
                    enabled = batchHint == null,
                    onClick = {
                        pickLook = !pickLook
                        confirmDelete = false
                    },
                )
            }
            }
            if (pickLook && batchHint == null) {
                val chips = LookLibrary.pickerChips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lazyItems(chips, key = { it.first }) { (id, name) ->
                        AppBarCell(
                            label = name,
                            on = false,
                            onClick = {
                                pickLook = false
                                onBatchYishun?.invoke(id)
                            },
                        )
                    }
                }
            }
        } else if (title != null) {
            val sheetType = SuiPaiSheet.type
            val sheetColors = SuiPaiSheet.colors
            Column {
                // 印样页首：页名重磅，帧数等宽编号
                AppBarRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    // 印样页首不再单独占一行：页名与帧数就是这行的开头，网格紧贴工具栏开始。
                    // 两段分开排：页名重磅、帧数等宽（契约：等宽体只用于编号/计数）。
                    if (onBack != null) {
                        // 专辑页里页名本身就是返回键：省下一整行也省下一格宽度，窄屏不会挤爆。
                        AppBarText(
                            "‹ $title",
                            style = sheetType.pageHead,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable(onClick = onBack)
                                .testTag("gallery_back"),
                        )
                    } else {
                        AppBarText(title, style = sheetType.pageHead)
                    }
                    if (batchHint != null) {
                        // 结论必须落在页眉这一格：`batchDelete` 会先清空选中再弹授权框，
                        // 回来时选择条整条不在了 —— 原来那句"删了 1 张"根本没有渲染的地方，
                        // 装机复测时顶栏一句都没说（实测见 ANDROID-VERIFY §24）。
                        AppBarText(
                            batchHint,
                            style = sheetType.frameNo,
                            color = sheetColors.number,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .testTag("gallery_note"),
                        )
                    } else if (visible.isNotEmpty()) {
                        AppBarText(
                            "${visible.size} FRAMES",
                            style = sheetType.frameNo,
                            color = sheetColors.number,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 成组循环格：琥珀框 = 这一格已经不是默认值，不用读字也能扫出来。
                    AppBarCell(
                        label = when (groupMode) {
                            1 -> "按月"
                            2 -> "按年"
                            3 -> "按周"
                            else -> "按日"
                        },
                        on = groupMode != 0,
                        onClick = {
                            groupMode = (groupMode + 1) % 4
                            // 老写回只写 `group_mode`，于是设置页那一格永远说"按日"。
                            // 现在两边写：老键给降级安装，cfg 给设置页与下一版。
                            prefs.edit().putInt("group_mode", groupMode).apply()
                            settings.syncFromLegacyInt(
                                com.suipai.app.settings.LegacyKeys.GROUP_MODE, groupMode,
                            )
                        },
                    )
                    AppBarCell(
                        label = when (sortMode) {
                            1 -> "最早"
                            2 -> "最大"
                            3 -> "最小"
                            4 -> "按名"
                            5 -> "按像素"
                            else -> "最新"
                        },
                        on = sortMode != 0,
                        onClick = {
                            sortMode = (sortMode + 1) % 6
                            newestFirst = sortMode == 0
                            prefs.edit()
                                .putInt("gallery_sort", sortMode)
                                .putBoolean("newest_first", newestFirst)
                                .apply()
                            settings.syncFromLegacyInt(
                                com.suipai.app.settings.LegacyKeys.GALLERY_SORT, sortMode,
                            )
                        },
                    )
                    AppBarCell(
                        label = "筛选",
                        on = filterChips > 0 || query.isNotEmpty(),
                        badge = filterChips + if (query.isNotEmpty()) 1 else 0,
                        onClick = { gSheet = 1 },
                    )
                    AppBarCell(
                        label = "显示",
                        on = gSheet == 2 || hideChips > 0,
                        badge = hideChips,
                        onClick = { gSheet = 2 },
                    )
                    // 只在"只给了部分照片"时出现：别的人一个字都不该多看见。
                    if (partialNotice != null) {
                        PhotoAccessCell(
                            enabled = probe?.settingsReachable == true,
                            onNote = onNote,
                        )
                    }
                    if (inlineSearch) {
                        AppInlineSearch(query, { query = it })
                    }
                }

                // 那句解释单独占一行，不塞进 `AppBarText`：后者是工具条里的高度契约（单行 + 省略号），
                // 而这句话被截掉半句就变成另一回事了 ——「只授权了部分照片：相册里有」这种半句
                // 比不说更糟（同一个教训见搜索框提示语被折成四行那一次）。
                partialNotice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 4.dp)
                            .testTag("partial_access_notice"),
                    )
                }

                if (gSheet == 1) {
                    AppSheet(title = "筛选", tag = "gallery_sheet_filter", onDismiss = { gSheet = 0 }) {
                        AppSheetSection("日期与时段", "三格各自循环，全默认就是不筛")
                        AppChipFlow {
                            AppBarCell(
                                modifier = Modifier.testTag("gf_recency"),
                                label = recencyLabel(recency),
                                on = recency != 0,
                                onClick = {
                            recency = (recency + 1) % 28
                            prefs.edit().putInt("gallery_recency", recency).apply()
                            },
                            )
                            AppBarCell(
                                modifier = Modifier.testTag("gf_hour"),
                                label = if (hourFilter == 0) "时段" else hourBucketLabel(hourFilter),
                                on = hourFilter != 0,
                                onClick = {
                            hourFilter = (hourFilter + 1) % 7
                            prefs.edit().putInt("hour_filter", hourFilter).apply()
                            },
                            )
                            AppBarCell(
                                modifier = Modifier.testTag("gf_week"),
                                label = when (weekFilter) {
                                    1 -> "工作日"
                                    2 -> "周末"
                                    else -> "星期"
                                    },
                                on = weekFilter != 0,
                                onClick = {
                            weekFilter = (weekFilter + 1) % 3
                            prefs.edit().putInt("week_filter", weekFilter).apply()
                            },
                            )
                            if (recency != 0 || hourFilter != 0 || weekFilter != 0) {
                            AppBarCell(
                                label = "清掉这三格",
                                on = false,
                                modifier = Modifier.testTag("gf_clear_date"),
                                onClick = {
                            recency = 0
                            hourFilter = 0
                            weekFilter = 0
                            prefs.edit()
                            .putInt("gallery_recency", 0)
                            .putInt("hour_filter", 0)
                            .putInt("week_filter", 0)
                            .apply()
                            },
                            )
                            }
                        }
                        AppSheetSection("跳到", "点一下会滚到那一格并收起弹层")
                        GalleryJumpRails(
                            sections = sections,
                            years = years,
                            months = months,
                            photos = photos,
                            bucketFilter = bucketFilter,
                            onBucketFilter = { picked ->
                                bucketFilter = picked
                                prefs.edit().putString("gallery_bucket", picked).apply()
                                gSheet = 0
                            },
                            gridState = gridState,
                            onPicked = { gSheet = 0 },
                        )
                        AppSheetSection("画幅、大小与格式")
                        AppChipFlow {
                    AppBarCell(
                        modifier = Modifier.testTag("gf_orient"),
                        label = when (orient) {
                            1 -> "竖图"
                            2 -> "横图"
                            3 -> "方图"
                            else -> "方向"
                            },
                        on = orient != 0,
                        onClick = {
                        orient = (orient + 1) % 4
                        prefs.edit().putInt("orient_filter", orient).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_ratio"),
                        label = if (ratioFilter == 0) "画幅" else ratioFilterLabel(ratioFilter),
                        on = ratioFilter != 0,
                        onClick = {
                        ratioFilter = (ratioFilter + 1) % 22
                        prefs.edit().putInt("ratio_filter", ratioFilter).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_mega"),
                        label = when (mega) {
                            1 -> "800万+"
                            2 -> "1200万+"
                            3 -> "2000万+"
                            4 -> "4000万+"
                            else -> "像素"
                            },
                        on = mega != 0,
                        onClick = {
                        mega = (mega + 1) % 5
                        prefs.edit().putInt("mega_filter", mega).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_minmb"),
                        label = when (minMb) {
                            2 -> "2MB+"
                            5 -> "5MB+"
                            10 -> "10MB+"
                            20 -> "20MB+"
                            else -> "体积"
                            },
                        on = minMb != 0,
                        onClick = {
                        minMb = when (minMb) {
                            0 -> 2
                            2 -> 5
                            5 -> 10
                            10 -> 20
                            else -> 0
                        }
                        prefs.edit().putInt("min_mb", minMb).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_minside"),
                        label = when (minSide) {
                            1000 -> "短边1000+"
                            2000 -> "短边2000+"
                            3000 -> "短边3000+"
                            else -> "短边"
                            },
                        on = minSide != 0,
                        onClick = {
                        minSide = when (minSide) {
                            0 -> 1000
                            1000 -> 2000
                            2000 -> 3000
                            else -> 0
                        }
                        prefs.edit().putInt("min_side", minSide).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_ext"),
                        label = when (extFilter) {
                            1 -> "仅JPEG"
                            2 -> "仅PNG"
                            3 -> "仅HEIC"
                            4 -> "仅WEBP"
                            else -> "格式"
                            },
                        on = extFilter != 0,
                        onClick = {
                        extFilter = (extFilter + 1) % 5
                        prefs.edit().putInt("ext_filter", extFilter).apply()
                    },
                    )
                    AppBarCell(
                        modifier = Modifier.testTag("gf_only"),
                        label = onlyLabel(filter),
                        on = filter != 0,
                        onClick = {
                        filter = (filter + 1) % 32
                        prefs.edit().putInt("gallery_filter", filter).apply()
                    },
                    )
                    AppBarCell(
                        label = "全部清空",
                        on = false,
                        modifier = Modifier.testTag("gf_clear_all"),
                        onClick = {
                        filter = 0
                        query = ""
                        orient = 0
                        extFilter = 0
                        recency = 0
                        mega = 0
                        minMb = 0
                        minSide = 0
                        hourFilter = 0
                        weekFilter = 0
                        ratioFilter = 0
                        bucketFilter = ""
                        // 「隐藏这些」那一整组按表清。原来是 26 行 `gflags.copy(hideX = false)`，
                        // 每行还各带一次 `saveTo` —— 点一下写 26 遍偏好，再加下面那 26 行 putBoolean
                        // 就是 52 次落盘；更要紧的是新加一个隐藏开关必须记得来这儿补一行，
                        // 漏了界面上一切正常、偏好里那格永远清不掉（看不见的漏）。
                        gflags = GalleryFlag.inGroup(GalleryFlagGroup.HIDE)
                            .fold(gflags) { acc, f -> f.with(acc, false) }
                            .also { it.saveTo(prefs) }
                        prefs.edit()
                            .putInt("gallery_filter", 0)
                            .putInt("orient_filter", 0)
                            .putInt("ext_filter", 0)
                            .putInt("gallery_recency", 0)
                            .putInt("mega_filter", 0)
                            .putInt("min_mb", 0)
                            .putInt("min_side", 0)
                            .putInt("hour_filter", 0)
                            .putInt("week_filter", 0)
                            .putInt("ratio_filter", 0)
                            .putString("gallery_bucket", "")
                            .apply()
                    },
                    )
                        }
                    }
                }
                if (gSheet == 2) {
                    AppSheet(title = "显示", tag = "gallery_sheet_display", onDismiss = { gSheet = 0 }) {
                        AppSheetSection("网格与点按")
                        AppChipFlow {
                            AppBarCell(
                                label = when (cellDp) {
                                    168 -> "大图"
                                    64 -> "密排"
                                    86 -> "小图"
                                    else -> "中图"
                                    },
                                on = cellDp != 0,
                                onClick = {
                                cellDp = when (cellDp) {
                                    168 -> 118
                                    86 -> 168
                                    else -> 86
                                }
                                prefs.edit().putInt("grid_cell", cellDp).apply()
                                settings.syncFromLegacyInt(
                                    com.suipai.app.settings.LegacyKeys.GRID_CELL, cellDp,
                                )
                            },
                            )
                            AppBarCell(
                                label = if (gridGap == 8) "疏" else "密",
                                on = gridGap == 8,
                                onClick = {
                        gridGap = if (gridGap == 2) 8 else 2
                        prefs.edit().putInt("grid_gap", gridGap).apply()
                    },
                            )
                    AppBarCell(
                        label = if (gflags.peekMode) "按住预览" else "点按打开",
                        on = gflags.peekMode,
                        onClick = {
                        gflags = gflags.toggle(GalleryFlag.PEEK_MODE).also { it.saveTo(prefs) }
                        peekPhoto = null
                        prefs.edit().putBoolean("peek_mode", gflags.peekMode).apply()
                    },
                    )
                        }
                        AppSheetSection("隐藏这些", "共 ${GalleryFlag.inGroup(GalleryFlagGroup.HIDE).size} 类；开着的那类不进时间线")
                        GalleryFlagChips(GalleryFlagGroup.HIDE, gflags) { f ->
                            gflags = gflags.toggle(f).also { it.saveTo(prefs) }
                        }
                        AppSheetSection("网格外观")
                        GalleryFlagChips(GalleryFlagGroup.GRID, gflags) { f ->
                            gflags = gflags.toggle(f).also { it.saveTo(prefs) }
                        }
                    }
                }
            }
        }
        if (selectedIds.isEmpty() && !inlineSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                // 提示语必须和输入框同一个单行约定：`singleLine = true` 只管用户输入，
                // 不管 placeholder。装机量到（720×1280 + 字级 2.0）那句提示在 274 dp 宽里
                // 折成四行，把搜索框撑到 528 px 高，整片网格被顶出屏幕（时间线一颗都不剩）。
                placeholder = {
                    Text(
                        "文件名、相册、2024-09、今天",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                // 「完成」收键盘：不收的话键盘一直挂着，结果列表被压在下面看不见。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        AppBarCell(
                            label = "清除",
                            on = false,
                            onClick = { query = "" },
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val grid: @Composable () -> Unit = {
        // 长按做范围选择要用的「照片 id → 可见行号」表，`visible` 变了才算一次。
        // 以前每次长按都先 `visible.map { it.id }` 再对每个已选项做 `indexOf`：
        // 5000 张里选 3000 张那一发是 15 M 次比较，全压在一次点击的响应路径上。
        val rowIndex = remember(visible) {
            HashMap<Long, Int>(visible.size).also { m -> visible.forEachIndexed { i, p -> m[p.id] = i } }
        }
        // 每格现造 Modifier 链的话，选中一张就是整屏格子各造一遍；这一条只依赖开关。
        //
        // 圆角从 10dp 收到 2dp（设计系统 v1 §4：这个世界只有 0/2/8/pill 四档）。
        // 原来那个 10dp 是"社交 App 头像"的圆角，开了「圆角」开关之后照片像贴纸，
        // 和印样世界打架；2dp 是本世界的签名近直角。
        val clipRound = remember(gflags.roundThumbs) {
            if (gflags.roundThumbs) Modifier.clip(SuiPaiShapes.frame) else Modifier
        }
        val hint = topEmptyHint(problem, query, filterChips, hideChips)
        if (visible.isEmpty()) {
            val emptyTitle = when {
                // "没读到"和"搜不到"的优先顺序是判据，钉在 `topEmptyHint` 上
                hint != null -> hint
                bucketFilter.isNotBlank() -> "这个相册没有匹配的照片"
                filter != 0 -> onlyHint(filter)
                gflags.hidePostcard -> "没有不含明信片裁的照片"
                gflags.hideMirror -> "没有不含镜像的照片"
                gflags.hideGray -> "没有不含灰度的照片"
                gflags.hideFlip -> "没有不含上下翻的照片"
                gflags.hideRound -> "没有不含圆角的照片"
                gflags.hide2k -> "没有不含2K的照片"
                gflags.hide1080 -> "没有不含1080的照片"
                gflags.hide720 -> "没有不含720的照片"
                gflags.hide480 -> "没有不含480的照片"
                gflags.hide320 -> "没有不含320的照片"
                gflags.hide240 -> "没有不含240的照片"
                gflags.hide160 -> "没有不含160的照片"
                gflags.hide120 -> "没有不含120的照片"
                gflags.hide96 -> "没有不含96的照片"
                gflags.hide64 -> "没有不含64的照片"
                gflags.hide48 -> "没有不含48的照片"
                gflags.hide32 -> "没有不含32的照片"
                gflags.hide24 -> "没有不含24的照片"
                gflags.hide16 -> "没有不含16的照片"
                gflags.hideWide -> "没有不含宽银幕的照片"
                gflags.hideTurned -> "没有未转向的照片"
                gflags.hideYishun -> "没有不含一瞬的照片"
                gflags.hideCompare -> "没有不含对比图的照片"
                gflags.hideCopies -> "没有不含副本的照片"
                gflags.hideEdits -> "没有未裁切的照片"
                extFilter == 1 -> "没有 JPEG"
                extFilter == 2 -> "没有 PNG"
                extFilter == 3 -> "没有 HEIC"
                extFilter == 4 -> "没有 WEBP"
                mega == 1 -> "没有800万像素以上的照片"
                mega == 2 -> "没有1200万像素以上的照片"
                mega == 3 -> "没有2000万像素以上的照片"
                mega == 4 -> "没有4000万像素以上的照片"
                minMb == 2 -> "没有超过2MB的照片"
                minMb == 5 -> "没有超过5MB的照片"
                minMb == 10 -> "没有超过10MB的照片"
                minMb == 20 -> "没有超过20MB的照片"
                minSide == 1000 -> "没有短边超过1000的照片"
                minSide == 2000 -> "没有短边超过2000的照片"
                minSide == 3000 -> "没有短边超过3000的照片"
                recency != 0 -> recencyHint(recency)
                ratioFilter != 0 -> ratioFilterHint(ratioFilter)
                hourFilter in 1..6 -> "没有${hourBucketLabel(hourFilter)}拍的照片"
                weekFilter == 1 -> "没有工作日的照片"
                weekFilter == 2 -> "没有周末的照片"
                orient == 1 -> "没有竖图"
                orient == 2 -> "没有横图"
                orient == 3 -> "没有方图"
                else -> emptyHint
            }
            // 空态从"一行居中灰字"升级成印样页里空出来的一块：
            // 等宽 kicker（`00 / 00`）+ 主文案 + 说明。没有照片的时候，
            // 屏幕上唯一有形状的东西不该只是一句话。
            EmptyState(
                kicker = if (visible.isEmpty() && hint == null && filterChips == 0 && hideChips == 0) {
                    "00 / 00 FRAMES"
                } else {
                    "NO MATCH"
                },
                title = emptyTitle,
                note = when {
                    // 空了但筛开着：告诉用户"不是你相册空了"。
                    filterChips > 0 || hideChips > 0 || query.isNotBlank() -> "「显示」或搜索里还有条件开着"
                    else -> null
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(cellDp.dp),
            contentPadding = PaddingValues(bottom = 88.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(gridGap.dp),
            horizontalArrangement = Arrangement.spacedBy(gridGap.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEach { section ->
                item(
                    key = "day-${section.key}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        if (selectedIds.isNotEmpty()) "${section.label}  · 选这天"
                        else "${section.label}  ·  ${section.photos.size}张",
                        style = SuiPaiSheet.type.frameNo.copy(fontSize = 13.sp),
                        color = SuiPaiSheet.colors.number,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = if (compactChrome) 3.dp else 10.dp)
                            .combinedClickable(
                                onClickLabel = "选中这一天的全部照片",
                                onLongClickLabel = "选中这一天的全部照片",
                                onClick = {
                                    if (selectedIds.isNotEmpty()) onSelectPhotos?.invoke(section.photos)
                                },
                                onLongClick = { onSelectPhotos?.invoke(section.photos) },
                            ),
                    )
                }
                items(section.photos, key = { it.id }) { photo ->
                    val selected = photo.id in selectedIds
                    // 日期与大小**每格算一次就够**：滚动时整屏格子都在重组，
                    // 而这两个值只跟着照片本身变。
                    val dayText = remember(photo.dateTaken) {
                        Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L))
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(MonthDay)
                    }
                    val sizeText = remember(photo.sizeBytes) {
                        when {
                            photo.sizeBytes >= 1_000_000L -> "${"%.1f".format(photo.sizeBytes / 1_000_000.0)}MB"
                            photo.sizeBytes >= 1_000L -> "${"%.0f".format(photo.sizeBytes / 1_000.0)}KB"
                            else -> ""
                        }
                    }
                    val cellLabel = photoCellLabel(
                        name = photo.displayName,
                        width = photo.width,
                        height = photo.height,
                        sizeText = sizeText,
                        dayText = dayText,
                        favorite = photo.id in favoriteIds,
                        hidden = photo.id in hiddenIds,
                        selected = selected,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                if (gflags.nativeAspect && photo.width > 0 && photo.height > 0) {
                                    (photo.width.toFloat() / photo.height).coerceIn(0.55f, 1.85f)
                                } else {
                                    1f
                                },
                            )
                            .then(clipRound)
                            .combinedClickable(
                                // 动作要有名字：不写这两个标签，读屏的"操作"列表里
                                // 只有两个无名动作，而"长按=多选"正是这个界面最不显然的规则。
                                onClickLabel = if (selectedIds.isNotEmpty()) "选中或取消这张"
                                else "打开这张",
                                onLongClickLabel = if (selectedIds.isNotEmpty()) "从这格续选到已选的那格"
                                else "把这格加进多选",
                                onClick = {
                                    when {
                                        selectedIds.isNotEmpty() -> onToggleSelect?.invoke(photo)
                                        gflags.peekMode -> peekPhoto = photo
                                        else -> onOpen(photo)
                                    }
                                },
                                onLongClick = {
                                    if (selectedIds.isEmpty()) {
                                        onLongPress?.invoke(photo)
                                    } else {
                                        val t = rowIndex[photo.id] ?: -1
                                        val anchors = selectedIds.mapNotNull { rowIndex[it] }
                                        if (t >= 0 && anchors.isNotEmpty()) {
                                            val lo = minOf(anchors.minOrNull() ?: t, t)
                                            val hi = maxOf(anchors.maxOrNull() ?: t, t)
                                            onSelectPhotos?.invoke(visible.subList(lo, hi + 1))
                                        } else {
                                            onLongPress?.invoke(photo)
                                        }
                                    }
                                },
                            )
                            // 整格合成一个节点，且**标签要挂在可点的那个节点上**：
                            // 装机语义树量过，`semantics(mergeDescendants = true)` 会另起一个子节点，
                            // 结果父节点（带点击动作的那个）是空标签，读屏念不到这句。
                            // `clearAndSetSemantics` 才是"这一格就一个元素，它叫这个"。
                            .clearAndSetSemantics {
                                contentDescription = cellLabel
                                role = Role.Image
                            },
                    ) {
                        // ImageRequest 没有 equals：每重组一次就是一个"新"请求，
                        // Coil 会重新派发、crossfade 从头再来。滚动时整屏格子都在重组，
                        // 这就是一边滚一边闪。按照片记住它。
                        //
                        // `thumbPx` 进 key：设置页改了缩略图质量之后，**下一次进相册**
                        // 每格都会用新边长重建请求（420 是原来写死的那个数，也就是标准档）。
                        val thumbReq = remember(photo.id, thumbPx) {
                            ImageRequest.Builder(context)
                                .data(photo.uri)
                                .size(thumbPx)
                                .tag(FastThumb(thumbPx))
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = thumbReq,
                            // 标签在整格的 semantics 上（photoCellLabel 那句），
                            // 这里再报一次文件名，读屏就会把名字念两遍。
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                // 空框也得像印样：一条片基框线，而不是一口黑洞
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        // ── 选中态：油笔圈选的琥珀直角框 ──
                        //
                        // 改之前这里是 `border(3.dp, secondary)`：secondary 在本项目里是
                        // **相纸白/墨色**，不是琥珀 —— 于是多选时照片被一条 3dp 白框框住，
                        // 看起来像"被框住的异常"而不是契约要求的"印样上被油笔圈中的那张"。
                        //
                        // 两个实现细节：
                        // 1. `greaseSelection` 是 drawBehind，画在内容**背后**；
                        //    照片是子节点、不透明，所以直接挂在外层 Box 上会被盖掉。
                        //    这里在照片**之后**再叠一个空 Box 承载圈选，它就在最上面了。
                        // 2. 圈选框向外留 1dp（`padding(1.dp)` 在描边上而不是在内容上），
                        //    否则 2dp 的框有一半压在照片边缘之外、在密排网格里被下一格切掉。
                        if (selected) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .greaseSelection(ticked = true),
                            )
                        }
                        if (photo.id in favoriteIds && !selected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(SuiPaiColor.current.safelightWash),
                            ) {
                                Text(
                                    "藏",
                                    color = SuiPaiColor.current.inkOnSafelightWash,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                        if (photo.id in hiddenIds && (filter == 5 || showHiddenInAll) && !selected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(SuiPaiColor.current.panelOverlay),
                            ) {
                                Text(
                                    "隐",
                                    color = SuiPaiColor.current.inkSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                        if (cellDp >= 160 && selectedIds.isEmpty()) {
                            // 日期与大小用格子上面算好的那两份：界面和读屏念的是同一套值，
                            // 不会出现"看得见 6.2MB、听说是 5MB"这种分叉。
                            //
                            // 底色从 `surface.copy(alpha = 0.78f)` 改成不透明的 `panelOverlay`：
                            // 半透明块压在照片上 = 给照片加了一层假滤镜，而且透出来的颜色
                            // 会把角标本身染花（截图里青色的图把那块染成了青灰）。
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(SuiPaiColor.current.panelOverlay),
                            ) {
                                Text(
                                    buildString {
                                        append(dayText)
                                        if (sizeText.isNotBlank()) {
                                            append("  ")
                                            append(sizeText)
                                        }
                                        if (photo.width > 0 && photo.height > 0) {
                                            append("  ")
                                            append("${photo.width}×${photo.height}")
                                        }
                                    },
                                    color = SuiPaiColor.current.inkSecondary,
                                    style = SuiPaiType.frameNo,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (photo.displayName.startsWith("SuiPai_一瞬_")) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .background(SuiPaiColor.current.panelOverlay),
                            ) {
                                Text(
                                    "瞬",
                                    color = SuiPaiColor.current.safelight,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                        if (selected) {
                            // 「选」角标原来是一块 secondary（相纸白）实底 ——
                            // 在暗房里它比照片本身还亮，是整屏最刺眼的东西。
                            // 选中态的语言已经是"油笔圈住的琥珀框"，角标只该是它的注脚：
                            // 琥珀洗色底 + 琥珀字，压在框的右上角。
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(SuiPaiColor.current.safelightWash),
                            ) {
                                Text(
                                    "选",
                                    color = SuiPaiColor.current.inkOnSafelightWash,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (loadingMore || hasMore) {
                item(
                    key = "more",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    if (loadingMore) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "加载更多",
                                modifier = Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text("", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        }
        Box(Modifier.fillMaxSize()) {
            if (onRefresh != null) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) { grid() }
            } else {
                grid()
            }
            GalleryPositionChip(
                position.value,
                sections,
                starts,
                onScrollTo = { at -> scope.launch { gridState.scrollToItem(at) } },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
        peekPhoto?.let { photo ->
            Box(
                Modifier
                    .fillMaxSize()
                    // peek 是"按住偷看一下"的整屏遮罩。底色必须**不透明**：
                    // 半透明会把身后那一片缩略图透出来，等于给正在看的照片加了一圈
                    // 会变色的假画框（而且透出来的颜色随滚动变化）。
                    // 用 `canvasSunken`（比页面底再深一档），让被看的那张浮在上面。
                    .background(SuiPaiColor.current.canvasSunken)
                    .clickable { peekPhoto = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    val peekReq = remember(photo.id) {
                        ImageRequest.Builder(context)
                            .data(photo.uri)
                            .size(1600)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = peekReq,
                        contentDescription = photo.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    Text(
                        photo.displayName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    AppBarCell(
                        label = "打开",
                        on = false,
                        onClick = {
                        val target = photo
                        peekPhoto = null
                        onOpen(target)
                    },
                    )
                }
            }
        }
    }
}


/**
 * 相册筛选的全部输入。收进一个对象，只是为了把下面那条筛选链搬出 GalleryScreen 那个巨型方法。
 *
 * 每个字段都带默认值，为的是让 `GalleryOnlyKindTest` 能只写被测的那一两格：
 * 31 个「只看 X」 × 26 个「隐藏 Y」的组合，靠 40 个具名参数是写不出来的。
 */
internal class GalleryFilters(
    val filter: Int = 0,
    val query: String = "",
    val favoriteIds: Set<Long> = emptySet(),
    val favoritePhotos: List<PhotoItem> = emptyList(),
    val orient: Int = 0,
    val hideShots: Boolean = false,
    val hideCopies: Boolean = false,
    val hideEdits: Boolean = false,
    val hideYishun: Boolean = false,
    val hideCompare: Boolean = false,
    val hideTurned: Boolean = false,
    val hideWide: Boolean = false,
    val hidePostcard: Boolean = false,
    val hideMirror: Boolean = false,
    val hideGray: Boolean = false,
    val hideFlip: Boolean = false,
    val hideRound: Boolean = false,
    val hide2k: Boolean = false,
    val hide1080: Boolean = false,
    val hide720: Boolean = false,
    val hide480: Boolean = false,
    val hide320: Boolean = false,
    val hide240: Boolean = false,
    val hide160: Boolean = false,
    val hide120: Boolean = false,
    val hide96: Boolean = false,
    val hide64: Boolean = false,
    val hide48: Boolean = false,
    val hide32: Boolean = false,
    val hide24: Boolean = false,
    val hide16: Boolean = false,
    val hideGoldenCrop: Boolean = false,
    val hideAcademy: Boolean = false,
    val extFilter: Int = 0,
    val recency: Int = 0,
    val mega: Int = 0,
    val minMb: Int = 0,
    val minSide: Int = 0,
    val hourFilter: Int = 0,
    val weekFilter: Int = 0,
    val ratioFilter: Int = 0,
    val bucketFilter: String = "",
    val hiddenIds: Set<Long> = emptySet(),
    val hiddenPhotos: List<PhotoItem> = emptyList(),
    val showHiddenInAll: Boolean = false,
)

/**
 * 相册筛选链，从 `visible = remember(...) { ... }` 里**逐字搬出来**，行为不变。
 *
 * 为什么必须搬：`remember` 是 inline 函数，那 392 行原先被内联进 `GalleryScreen()` 这一个方法，
 * 编出 3 万多条指令 —— 超过 ART 的 JIT 指令上限后该方法**永远不被编译、全程解释执行**。
 * 实测症状：`Method exceeds compiler instruction limit: 34512` + `Skipped 323 frames`
 * + `Displayed +9s390ms`，也就是冷启动要 9~20 秒、滚两下就 ANR。
 */
/**
 * 「只看某一类」这 31 档的唯一真相：编号、格子上的词、空状态的话、以及"怎么认出这一类"。
 *
 * 原来同一件事抄了三遍，共 93 行分支：格子的 31 个标签、空态的 31 句话、
 * 取数的 31 行 `photos.filter { 前缀 }`。三处任何一处漏改，用户看到的就是
 * "格子上写明信片、筛出来的是学院裁"；而 2026-09-22 那次更狠——
 * 与它配套的 26 段「隐藏」豁免漏了一条并集开关，31 档里 21 档点开就是恒空
 * （见 `GalleryOnlyKindTest` 与 §19）。所以这一张表按 `GalleryOnlyKindTest.reps` 的顺序写，
 * 编号↔代表照↔前缀三者对不上，那条扫描就会红。
 */
/**
 * 「只看某一类」31 档的唯一真相：编号、格子上的词、空态的话、以及**这一类的成员判据**。
 *
 * 原来这件事抄了三遍（31 标签 + 31 空态 + 31 行 `photos.filter { 前缀 }`），
 * 而下面 `HideRules` 那几十段又把同一批前缀抄了第四、五遍 —— 一份漏改就是
 * "格子上写明信片、筛出来的是学院裁"，或者 §19 量到的那种死胡同（21 档一点就恒空）。
 * 现在前缀只出现在这一张表里：`pick`（挑出这一类）与 `match`（某样东西算不算这一类）
 * 是同一条判据的两种用法，`HideRules` 也只引用编号。
 *
 * `pickFrom` 只有两行用得上：「收藏」「已隐藏」的真正来源是外面算好的列表
 * （分页库里它可能比当前页多），那一行照旧走那条路，`match` 只拿来给隐藏规则当判据。
 */
internal class OnlyKind(
    val id: Int,
    val label: String,
    val hint: String,
    val match: ((GalleryFilters, PhotoItem) -> Boolean)? = null,
    val pickFrom: ((GalleryFilters, List<PhotoItem>) -> List<PhotoItem>)? = null,
) {
    fun pick(f: GalleryFilters, ps: List<PhotoItem>): List<PhotoItem> =
        pickFrom?.invoke(f, ps) ?: ps.filter { match != null && match(f, it) }
}

internal val OnlyKinds: List<OnlyKind> = listOf(
    OnlyKind(1, "仅一瞬", "还没有一瞬成片",
        match = { _, p -> p.displayName.startsWith("SuiPai_一瞬_") }),
    OnlyKind(2, "收藏", "还没有收藏",
        match = { f, p -> p.id in f.favoriteIds },
        pickFrom = { f, ps -> f.favoritePhotos.ifEmpty { ps.filter { it.id in f.favoriteIds } } }),
    OnlyKind(3, "未一瞬", "没有未处理的原片",
        match = { _, p -> !p.displayName.startsWith("SuiPai_一瞬_") }),
    OnlyKind(4, "仅随拍", "还没有随拍原片",
        match = { _, p -> isSuiPaiShot(p) }),
    OnlyKind(5, "已隐藏", "没有隐藏的照片",
        match = { f, p -> p.id in f.hiddenIds },
        pickFrom = { f, ps -> f.hiddenPhotos.ifEmpty { ps.filter { it.id in f.hiddenIds } } }),
    OnlyKind(6, "对比图", "还没有对比图",
        match = { _, p -> p.displayName.startsWith("SuiPai_对比_") }),
    OnlyKind(7, "副本", "还没有副本",
        match = { _, p -> p.displayName.startsWith("SuiPai_副本_") }),
    OnlyKind(8, "裁切", "还没有裁切件",
        match = { _, p -> p.displayName.startsWith("SuiPai_裁切_") }),
    OnlyKind(9, "转向", "还没有转向件",
        match = { _, p -> isTurnedShot(p) }),
    OnlyKind(10, "黄金裁", "还没有黄金裁切",
        match = { _, p -> p.displayName.startsWith("SuiPai_裁切黄金_") }),
    OnlyKind(11, "宽银幕", "还没有宽银幕裁切",
        match = { _, p -> p.displayName.startsWith("SuiPai_裁切239_") }),
    OnlyKind(12, "学院裁", "还没有学院裁切",
        match = { _, p -> p.displayName.startsWith("SuiPai_裁切185_") }),
    OnlyKind(13, "明信片", "还没有明信片裁切",
        match = { _, p -> p.displayName.startsWith("SuiPai_裁切53_") }),
    OnlyKind(14, "镜像", "还没有镜像件",
        match = { _, p -> p.displayName.startsWith("SuiPai_镜像_") }),
    OnlyKind(15, "灰度", "还没有灰度件",
        match = { _, p -> p.displayName.startsWith("SuiPai_灰度_") }),
    OnlyKind(16, "上下翻", "还没有上下翻件",
        match = { _, p -> p.displayName.startsWith("SuiPai_上下翻_") }),
    OnlyKind(17, "圆角", "还没有圆角件",
        match = { _, p -> p.displayName.startsWith("SuiPai_圆角_") }),
    OnlyKind(18, "2K", "还没有2K件",
        match = { _, p -> p.displayName.startsWith("SuiPai_2K_") }),
    OnlyKind(19, "1080", "还没有1080件",
        match = { _, p -> p.displayName.startsWith("SuiPai_1080_") }),
    OnlyKind(20, "720", "还没有720件",
        match = { _, p -> p.displayName.startsWith("SuiPai_720_") }),
    OnlyKind(21, "480", "还没有480件",
        match = { _, p -> p.displayName.startsWith("SuiPai_480_") }),
    OnlyKind(22, "320", "还没有320件",
        match = { _, p -> p.displayName.startsWith("SuiPai_320_") }),
    OnlyKind(23, "240", "还没有240件",
        match = { _, p -> p.displayName.startsWith("SuiPai_240_") }),
    OnlyKind(24, "160", "还没有160件",
        match = { _, p -> p.displayName.startsWith("SuiPai_160_") }),
    OnlyKind(25, "120", "还没有120件",
        match = { _, p -> p.displayName.startsWith("SuiPai_120_") }),
    OnlyKind(26, "96", "还没有96件",
        match = { _, p -> p.displayName.startsWith("SuiPai_96_") }),
    OnlyKind(27, "64", "还没有64件",
        match = { _, p -> p.displayName.startsWith("SuiPai_64_") }),
    OnlyKind(28, "48", "还没有48件",
        match = { _, p -> p.displayName.startsWith("SuiPai_48_") }),
    OnlyKind(29, "32", "还没有32件",
        match = { _, p -> p.displayName.startsWith("SuiPai_32_") }),
    OnlyKind(30, "24", "还没有24件",
        match = { _, p -> p.displayName.startsWith("SuiPai_24_") }),
    OnlyKind(31, "16", "还没有16件",
        match = { _, p -> p.displayName.startsWith("SuiPai_16_") }),
)

/**
 * 「隐藏 X」那 28 条开关的表：每条 = 哪个开关 + 不许被哪几档「只看 X」豁免 + （并集开关才有的）自带谓词。
 *
 * `exempt` 的语义就一句话：**开着「只看副本」时「藏副本」必须让路**，否则那一格永远筛出空。
 * §19 修之前有 21 档是这个状态 —— 26 段 `if (hideX && filter != N)` 各自只豁免与自己同名的一格，
 * 而「修过的」是一条覆盖 20 个前缀的并集开关、一段豁免都没写。
 * 两条并集开关（截图、修过的）不归任何单一编号管，所以自带 `drop`；
 * 「修过的」的豁免表就是 `EditedKindIds`，那里漏一个编号，那一档又是死胡同。
 */
internal class HideRule(
    val flag: GalleryFlag,
    val on: (GalleryFilters) -> Boolean,
    val exempt: Set<Int> = emptySet(),
    val drop: ((PhotoItem) -> Boolean)? = null,
)

/**
 * 「隐藏修过的」是一个**并集**开关：它掉的是上面那一整串前缀。
 * 而筛选抽屉里每一个「只看 X」只豁免与自己同名的那一个开关（`hideGray && filter != 15` 那种），
 * 于是这一条原来谁都不豁免 —— 一旦打开，下面这些「只看」全成了死胡同：
 * 结果恒为空，空状态还谎报「没有灰度照片」。这里把「成员全落在修过的里」的编号列出来。
 *
 * 不含 10 黄金裁 / 11 宽银幕 / 12 学院裁 / 13 明信片：它们的前缀不在 `isEditedShot` 里，
 * 掉不到；4「仅随拍」也不是子集（一张没动过的 SuiPai_ 原片不该被这条豁免救回来）。
 */
internal val EditedKindIds: Set<Int> = setOf(8, 9, 14, 15, 16, 17) + (18..31).toSet()

internal val HideRules: List<HideRule> = listOf(
    HideRule(GalleryFlag.HIDE_SHOTS, { it.hideShots }, drop = { isScreenshot(it) }),
    HideRule(GalleryFlag.HIDE_COPIES, { it.hideCopies }, setOf(7)),
    HideRule(GalleryFlag.HIDE_EDITS, { it.hideEdits }, EditedKindIds, drop = { isEditedShot(it) }),
    HideRule(GalleryFlag.HIDE_YISHUN, { it.hideYishun }, setOf(1)),
    HideRule(GalleryFlag.HIDE_COMPARE, { it.hideCompare }, setOf(6)),
    HideRule(GalleryFlag.HIDE_TURNED, { it.hideTurned }, setOf(9, 14)),
    HideRule(GalleryFlag.HIDE_GOLDEN_CROP, { it.hideGoldenCrop }, setOf(10)),
    HideRule(GalleryFlag.HIDE_WIDE, { it.hideWide }, setOf(11)),
    HideRule(GalleryFlag.HIDE_ACADEMY_CROP, { it.hideAcademy }, setOf(12)),
    HideRule(GalleryFlag.HIDE_POSTCARD, { it.hidePostcard }, setOf(13)),
    HideRule(GalleryFlag.HIDE_MIRROR, { it.hideMirror }, setOf(14)),
    HideRule(GalleryFlag.HIDE_GRAY, { it.hideGray }, setOf(15)),
    HideRule(GalleryFlag.HIDE_FLIP, { it.hideFlip }, setOf(16)),
    HideRule(GalleryFlag.HIDE_ROUND, { it.hideRound }, setOf(17)),
    HideRule(GalleryFlag.HIDE2K, { it.hide2k }, setOf(18)),
    HideRule(GalleryFlag.HIDE1080, { it.hide1080 }, setOf(19)),
    HideRule(GalleryFlag.HIDE720, { it.hide720 }, setOf(20)),
    HideRule(GalleryFlag.HIDE480, { it.hide480 }, setOf(21)),
    HideRule(GalleryFlag.HIDE320, { it.hide320 }, setOf(22)),
    HideRule(GalleryFlag.HIDE240, { it.hide240 }, setOf(23)),
    HideRule(GalleryFlag.HIDE160, { it.hide160 }, setOf(24)),
    HideRule(GalleryFlag.HIDE120, { it.hide120 }, setOf(25)),
    HideRule(GalleryFlag.HIDE96, { it.hide96 }, setOf(26)),
    HideRule(GalleryFlag.HIDE64, { it.hide64 }, setOf(27)),
    HideRule(GalleryFlag.HIDE48, { it.hide48 }, setOf(28)),
    HideRule(GalleryFlag.HIDE32, { it.hide32 }, setOf(29)),
    HideRule(GalleryFlag.HIDE24, { it.hide24 }, setOf(30)),
    HideRule(GalleryFlag.HIDE16, { it.hide16 }, setOf(31)),
)

private fun onlyKind(id: Int): OnlyKind? = OnlyKinds.firstOrNull { it.id == id }

internal fun onlyLabel(id: Int): String = onlyKind(id)?.label ?: "只看"

internal fun onlyHint(id: Int): String = onlyKind(id)?.hint ?: "没有照片"

/**
 * 一格照片在读屏里说的那一句。
 *
 * 眼睛扫一眼看到的是一整叠线索：文件名、右下角那条「日期 · 大小 · 尺寸」、
 * 左上角的「藏」、右上角的「隐」、左下角的「瞬」、选中时的边框。
 * 而 Compose 默认只把 `contentDescription = 文件名` 交给 TalkBack，
 * 其余角标各自成节点 —— 念出来是"藏""隐""瞬"三个孤字，落在哪个格子上全靠摸。
 * 这一句把**看得见的状态一次说完**，格子用 `semantics(mergeDescendants = true)` 挂上它。
 *
 * 判据做成纯函数：状态词与顺序、以及"没有的尺寸/日期就不念"这两条，都钉在 JVM 测试里。
 */
internal fun photoCellLabel(
    name: String,
    width: Int,
    height: Int,
    sizeText: String,
    dayText: String,
    favorite: Boolean,
    hidden: Boolean,
    selected: Boolean,
): String = buildString {
    append(name)
    if (width > 0 && height > 0) append("，").append(width).append("×").append(height)
    // 收的是角标上那句原文（"2.4MB" / "72KB"），不是字节数：两处各算一遍迟早分叉，
    // 装机探针就抓到过一次 —— 只报 MB 时，那批几十 KB 的测试图在标签里等于没有大小。
    if (sizeText.isNotBlank()) append("，").append(sizeText)
    if (dayText.isNotBlank()) append("，").append(dayText)
    if (favorite) append("，已收藏")
    if (hidden) append("，已隐藏")
    // 加工件的判据就是文件名前缀（界面上那个「瞬」角标读的是同一个事实）
    if (name.startsWith("SuiPai_一瞬_")) append("，一瞬加工过")
    if (selected) append("，已选中")
}

/**
 * 空屏那一句里最前面的两个候选：**先说"没读到"，再说"搜不到"**。
 * 顺序是判据：媒体库那一趟没答话时说"没有匹配的照片"，是把人往搜索词上引，
 * 而真实情况是这一屏压根没拿到数据。
 */
internal fun topEmptyHint(problem: String?, query: String, filterChips: Int, hideChips: Int): String? = when {
    problem != null -> problem
    query.isNotBlank() -> noMatchHint(filterChips, hideChips)
    else -> null
}

/**
 * 搜不到的那一句。
 *
 * 装机复测：搜索框里是一个只被「藏副本」藏着的词，屏幕说"没有匹配的照片"——
 * 可那张照片是被藏了，不是不匹配。空屏得自己说清是谁把它藏了，
 * 所以这里带上页眉那两格的同一个计数。
 */
internal fun noMatchHint(filterChips: Int, hideChips: Int): String = when {
    filterChips == 0 && hideChips == 0 -> "没有匹配的照片"
    filterChips == 0 -> "没有匹配的照片（「显示」里还藏着 $hideChips 类）"
    hideChips == 0 -> "没有匹配的照片（还有 $filterChips 处筛选开着）"
    else -> "没有匹配的照片（还有 $filterChips 处筛选开着，「显示」里又藏着 $hideChips 类）"
}

internal fun visiblePhotos(photos: List<PhotoItem>, f: GalleryFilters): List<PhotoItem> = with(f) {
        // 31 个「只看 X」的取数从 OnlyKinds 那张表来；0（没选）才走"整册、按隐藏开关收起来"。
        val kind = onlyKind(filter)
        val base = if (kind == null) {
            if (showHiddenInAll) photos else photos.filter { it.id !in hiddenIds }
        } else {
            kind.pick(this, photos)
        }
        val shaped = when (orient) {
            1 -> base.filter { it.height >= it.width && it.height > 0 }
            2 -> base.filter { it.width > it.height }
            3 -> base.filter {
                val m = maxOf(it.width, it.height).coerceAtLeast(1)
                minOf(it.width, it.height).toFloat() / m >= 0.9f
            }
            else -> base
        }
        // 「隐藏这些」那 28 条按 HideRules 走一遍。原来是 26 段手抄的 `if (hideX && filter != N)`，
        // 每段还要自己带一条豁免 —— 少写一条就是 §19 那 21 个死胡同。
        // 现在一条开关一行，豁免与判据都在表里；`filter in r.exempt` 就是"正只看这一类，让路"。
        var hided = shaped
        for (r in HideRules) {
            if (!r.on(this)) continue
            if (filter in r.exempt) continue
            val drop = r.drop ?: { pic ->
                r.exempt.any { id -> onlyKind(id)?.match?.invoke(this, pic) == true }
            }
            hided = hided.filter { !drop(it) }
        }
        val noShot = hided
        val win = recencyWindow(recency, LocalDate.now(ZoneId.systemDefault()), ZoneId.systemDefault())
        val timed = if (win == null) noShot else noShot.filter { it.dateTaken in win }
        val megaed = when (mega) {
            1 -> timed.filter { it.width.toLong() * it.height >= 8_000_000L }
            2 -> timed.filter { it.width.toLong() * it.height >= 12_000_000L }
            3 -> timed.filter { it.width.toLong() * it.height >= 20_000_000L }
            4 -> timed.filter { it.width.toLong() * it.height >= 40_000_000L }
            else -> timed
        }
        val sized = when (minMb) {
            2 -> megaed.filter { it.sizeBytes >= 2_000_000L }
            5 -> megaed.filter { it.sizeBytes >= 5_000_000L }
            10 -> megaed.filter { it.sizeBytes >= 10_000_000L }
            20 -> megaed.filter { it.sizeBytes >= 20_000_000L }
            else -> megaed
        }
        val sided = when (minSide) {
            1000 -> sized.filter { minOf(it.width, it.height) >= 1000 }
            2000 -> sized.filter { minOf(it.width, it.height) >= 2000 }
            3000 -> sized.filter { minOf(it.width, it.height) >= 3000 }
            else -> sized
        }
        val clocked = when (hourFilter) {
            1, 2, 3, 4, 5, 6 -> sided.filter { hourBucket(it.dateTaken) == hourFilter }
            else -> sided
        }
        val weeked = when (weekFilter) {
            1 -> clocked.filter { !isWeekend(it.dateTaken) }
            2 -> clocked.filter { isWeekend(it.dateTaken) }
            else -> clocked
        }
        val ratioed = applyRatioFilter(weeked, ratioFilter)
        val bucketed = if (bucketFilter.isBlank()) ratioed else ratioed.filter { it.bucketName == bucketFilter }
        val typed = when (extFilter) {
            1 -> bucketed.filter {
                it.displayName.endsWith(".jpg", ignoreCase = true) ||
                    it.displayName.endsWith(".jpeg", ignoreCase = true)
            }
            2 -> bucketed.filter { it.displayName.endsWith(".png", ignoreCase = true) }
            3 -> bucketed.filter {
                it.displayName.endsWith(".heic", ignoreCase = true) ||
                    it.displayName.endsWith(".heif", ignoreCase = true)
            }
            4 -> bucketed.filter { it.displayName.endsWith(".webp", ignoreCase = true) }
            else -> bucketed
        }
        val q = query.trim()
        if (q.isEmpty()) typed
        else typed.filter { matchesPhotoQuery(it, q) }
}

@Immutable
private data class DaySection(
    val key: String,
    val label: String,
    val photos: List<PhotoItem>,
)

/**
 * 滚动位置：`section` 是浮标该念哪一组，`shown` 是该不该露出来。
 * 两者分开是因为淡出要留住刚才那一组，而那一刻"当前组"已经换成下一组了。
 */
@Immutable
internal data class GalleryPosition(
    val section: Int,
    val shown: Boolean,
)

/** 分组条数 → 每组标题在网格里占的行号（每组 1 行标题 + N 张照片）。 */
internal fun sectionStarts(sizes: List<Int>): IntArray {
    val out = IntArray(sizes.size)
    var at = 0
    for (i in sizes.indices) {
        out[i] = at
        at += sizes[i] + 1
    }
    return out
}

/** 行号 → 它属于第几组（二分：最后一行号 ≤ index 的那一组）。空表回 -1。 */
internal fun sectionAt(starts: IntArray, index: Int): Int {
    if (starts.isEmpty()) return -1
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (starts[mid] <= index) lo = mid else hi = mid - 1
    }
    return lo
}

/** 由「屏幕上第一行的行号」算出浮标该念哪一组、该不该露出来。 */
internal fun galleryPosition(starts: IntArray, firstVisible: Int): GalleryPosition {
    val s = sectionAt(starts, firstVisible)
    if (s < 0) return GalleryPosition(0, false)
    val shown = firstVisible > starts[s]
    // 收起的那一刻要留住刚才那一组：此刻屏幕上第一行已经是**下一组**的标题，
    // 直接报 s 的话淡出过程中闪出来的是下一天的名字。
    return GalleryPosition(if (shown) s else (s - 1).coerceAtLeast(0), shown)
}

/**
 * 「补授权」那一颗：只在系统只放行了部分照片时出现。
 *
 * 两个要求都写死在这一颗上，`PartialAccessTest` 里各钉一条源码守卫：
 * 1. **做不了的时候它自己就是灰的**（守卫 #19 那条老规矩）：`enabled` 由
 *    `probePhotoAccess` 探出来的可达性说了算，两条出口都解析不到就不许亮着让人点。
 * 2. **点下去不许一声不响**（守卫 #23）：`openPhotoAccessSettings` 的失败那句
 *    走 `onNote` —— 也就是页眉那一格（`gallery_note`），批量结论用的是同一个位置。
 *    这一格没有"拿不到就悄悄收尾"的那种形状：发不出去一定有话说。
 */
@Composable
private fun PhotoAccessCell(enabled: Boolean, onNote: (String) -> Unit) {
    val context = LocalContext.current
    // **必须保持 `AppBarCell` + `enabled =` 这个形状**，不能改成 `PrimaryButton`：
    // `PartialAccessTest.settingsCellGreysOutWhenNoExitResolves` 的判据是
    // 「每一处 `openPhotoAccessSettings(` 所属的那一次 `AppBarCell(` 里必须有 `enabled =`」，
    // 换成别的控件时它会报「读不到所属格子」—— 那条守卫要保的是
    // **「做不了的时候它自己就是灰的，不许亮着让人点」**，与外形无关。
    // 所以这里只换视觉参数（由 `SuiPaiSheet` 的琥珀框语言负责），不动控件种类。
    AppBarCell(
        label = "补授权",
        on = false,
        enabled = enabled,
        modifier = Modifier.testTag("photo_access_settings"),
        onClick = { openPhotoAccessSettings(context) { onNote(it) } },
    )
}

/**
 * 时间线滚到一半时告诉你"眼前这屏是哪一天"的那条横带。
 *
 * 一天标题滚出去之后，屏幕上就没有任何东西说明这一屏是哪天拍的了；
 * 「跳到」那一栏能跳，但跳完位置信息又交回给同一行会滚走的标题。
 * 横带点一下回到本组开头（`scrollToItem`，和「跳到」那条栏用的是同一个动作）。
 *
 * 通栏而不是药丸：字级 2.0 时药丸会在 260 dp 上被截成半句话（装机量到 683 px 顶死上限），
 * 而截断的日期比没有日期更容易让人以为列表坏了。通栏的宽度就是标题那一行的宽度，
 * 标题排得下，这条就排得下 —— 少报的那半句因此不需要再判一次。
 * 文案只留日期：张数在标题里，滚到本组开头就看得见，不必占掉横带的位置。
 * 底色用不透明的 surface：0.94 的半透明压在亮色缩略图上会透出底下的颜色，
 * 横带看起来像糊了一层（装机截图里"昨天 · 周二"那一条被青色的图染成青灰色）。
 */
@Composable
private fun GalleryPositionChip(
    position: GalleryPosition,
    sections: List<DaySection>,
    starts: IntArray,
    onScrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val at = position.section.coerceIn(0, (sections.size - 1).coerceAtLeast(0))
    val label = sections.getOrNull(at)?.label.orEmpty()
    // 「减弱动效」：设置页拨了它，这一条横带的淡入淡出就变成 0ms（位置横带是相册里
    // 唯一一处跨屏可见的动画）。系数只乘自己这一档，不与系统的动画时长缩放叠加。
    val motion = com.suipai.app.ui.design.rememberMotionFactor()
    AnimatedVisibility(
        visible = label.isNotEmpty() && position.shown,
        // 数字一个都没换：这一步只是把散在调用点的两个值并到 `Motion` 上，
        // 好让下一处加动画的人不用再猜。规格 §1 建议退场换 `Motion.Accelerate`，
        // 那是**看得见**的改变，不在这一刀里做。
        enter = fadeIn(tween((Motion.Enter * motion).toInt(), easing = Motion.Standard)),
        exit = fadeOut(tween((Motion.Exit * motion).toInt(), easing = Motion.Standard)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                // 位置横带是**压在印样阵上**的一条：底色必须不透明，
                // 否则亮色缩略图会把它染成那块图的颜色（实测"昨天 · 周二"那条被青图染成青灰）。
                // 用面板色 + 一道发丝线收边，它就从"糊了一层"变成"印样页上压的一条标签"。
                .background(SuiPaiColor.current.panelOverlay)
                .clickable(role = Role.Button) { starts.getOrNull(at)?.let(onScrollTo) }
                .clearAndSetSemantics { contentDescription = "$label，回到这一组开头" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                label,
                style = SuiPaiType.frameNo,
                color = SuiPaiColor.current.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            // 发丝线收下沿：不透明块与照片之间需要一道边，否则仍然像"糊了一块"
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .hairline(),
            )
        }
    }
}

private fun nearRatio(width: Int, height: Int, target: Float): Boolean {
    if (width <= 0 || height <= 0) return false
    val r = width.toFloat() / height
    val inv = 1f / target
    return kotlin.math.abs(r - target) < 0.08f || kotlin.math.abs(r - inv) < 0.08f
}

private fun isUltraWide(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val m = maxOf(width, height).toFloat()
    val n = minOf(width, height).toFloat().coerceAtLeast(1f)
    return m / n >= 2.05f
}

/**
 * 「时段」这一格的全部知识收成一张表：编号、标签、管哪几个小时。
 *
 * 原来同样的六个标签抄在两处（抽屉里那格的 `when`、空态文案），
 * 而 `when (h)` 里 **`11..13 正午` 必须排在 `9..16 白天` 前面** —— 这个顺序是承重的：
 * 谁把两行换个位置，「白天」就悄悄变成"不含正午"，两处文案一处都不会报错。
 * 守卫 `HourBucketTest`（24 小时全覆盖、每桶的小时集合、标签唯一、顺序依赖都在它手里）。
 */
internal data class HourBucket(val id: Int, val label: String, val hours: IntRange)

internal val HourBuckets = listOf(
    HourBucket(6, "午夜", 0..4),
    HourBucket(1, "清晨", 5..8),
    HourBucket(5, "正午", 11..13),      // 必须排在「白天」之前
    HourBucket(2, "白天", 9..16),
    HourBucket(3, "傍晚", 17..19),
    HourBucket(4, "夜晚", 20..23),
)

internal fun hourBucketOf(hour: Int): Int = HourBuckets.first { hour in it.hours }.id

internal fun hourBucketLabel(id: Int): String = HourBuckets.first { it.id == id }.label

/**
 * 「画幅」那一格的表：编号、标签、要接近的长宽比（`null` = 超宽那条特殊判据）。
 *
 * 这张表原来抄了三遍：格子的 `when`（21 个标签）、筛的 `when`（21 个 `nearRatio`）、
 * 空态文案的 `when`（**只有 20 条** —— 第 21 档「7:10」筛空之后只能说"没有匹配的照片"，
 * 而那一格明明写着 7:10）。三遍抄写里任何一处漏改，用户看到的就是"写着 A 筛的是 B"。
 * 守卫 `RatioFilterTest` 会把标签里的比例（"16:9" → 16/9）与真正用来筛的那个数对一遍。
 */
internal data class RatioFilter(val id: Int, val label: String, val aspect: Float?)

internal val RatioFilters = listOf(
    RatioFilter(1, "16:9", 16f / 9f),
    RatioFilter(2, "4:3", 4f / 3f),
    RatioFilter(3, "3:2", 3f / 2f),
    RatioFilter(4, "超宽", null),
    RatioFilter(5, "5:3", 5f / 3f),
    RatioFilter(6, "8:5", 8f / 5f),
    RatioFilter(7, "1:1", 1f),
    RatioFilter(8, "4:5", 4f / 5f),
    RatioFilter(9, "9:16", 9f / 16f),
    RatioFilter(10, "3:4", 3f / 4f),
    RatioFilter(11, "2:3", 2f / 3f),
    RatioFilter(12, "21:9", 21f / 9f),
    RatioFilter(13, "7:5", 7f / 5f),
    RatioFilter(14, "5:7", 5f / 7f),
    RatioFilter(15, "6:5", 6f / 5f),
    RatioFilter(16, "8:7", 8f / 7f),
    RatioFilter(17, "7:6", 7f / 6f),
    RatioFilter(18, "5:6", 5f / 6f),
    RatioFilter(19, "11:8", 11f / 8f),
    RatioFilter(20, "5:8", 5f / 8f),
    RatioFilter(21, "7:10", 7f / 10f),
)

internal fun ratioFilterById(id: Int): RatioFilter? = RatioFilters.firstOrNull { it.id == id }

internal fun ratioFilterLabel(id: Int): String = ratioFilterById(id)?.label ?: "画幅"

internal fun ratioFilterHint(id: Int): String {
    val f = ratioFilterById(id) ?: return "没有匹配的照片"
    return if (f.aspect == null) "没有${f.label}照片" else "没有接近${f.label}的照片"
}

internal fun applyRatioFilter(list: List<PhotoItem>, id: Int): List<PhotoItem> {
    val f = ratioFilterById(id) ?: return list
    return if (f.aspect == null) list.filter { isUltraWide(it.width, it.height) }
    else list.filter { nearRatio(it.width, it.height, f.aspect) }
}

/**
 * 「日期」那一格 28 档的唯一真相：编号、格子上的词、空状态的话、窗口起止。
 *
 * 为什么必须是一张表：这三样原来分开抄了三遍（格子 28 臂 when、空状态 29 臂 when、
 * 取数 157 行 27 臂 when），三处要同时改对。取数那一处 7..27 每臂 7 行只差一个
 * 「减几个月」的数字，抄错一位就是「格子上写十二月前、筛的是别的月」——界面上完全看不出来。
 *
 * 一条规则就是 `[from, untilNext)` 两个日期，都从「今天」算，落到当天 0 点：
 * `from` 为 null 表示不筛；`untilNext` 为 null 表示一直开放到将来（那几档「近 X 天」）。
 *
 * 记一笔已知的不齐（保持的是现状，不是漏改）：11「近半年」起点是 today-182，含当天共 **183** 天，
 * 而 1/2/3/6 是按「N 天含今天」写的（近7天 = today-6）。要统一得先定「近半年」到底是几天。
 */
internal class RecencyRule(
    val id: Int,
    val label: String,
    val hint: String,
    val from: ((LocalDate) -> LocalDate)? = null,
    val untilNext: ((LocalDate) -> LocalDate)? = null,
)

private val MondayOf: (LocalDate) -> LocalDate = { it.with(java.time.DayOfWeek.MONDAY) }
private val FirstOfMonth: (LocalDate) -> LocalDate = { it.withDayOfMonth(1) }
private val FirstOfYear: (LocalDate) -> LocalDate = { it.withDayOfYear(1) }

internal val RecencyRules: List<RecencyRule> = listOf(
    RecencyRule(0, "全部日期", "没有照片"),
    RecencyRule(1, "近7天", "近7天没有照片", { it.minusDays(6) }),
    RecencyRule(2, "近30天", "近30天没有照片", { it.minusDays(29) }),
    RecencyRule(3, "近90天", "近90天没有照片", { it.minusDays(89) }),
    RecencyRule(4, "今年", "今年还没有照片", FirstOfYear),
    RecencyRule(5, "今天", "今天还没有照片", { it }),
    RecencyRule(6, "近3天", "近3天没有照片", { it.minusDays(2) }),
    RecencyRule(7, "去年", "去年没有照片", { it.minusYears(1).withDayOfYear(1) }, FirstOfYear),
    RecencyRule(8, "本周", "本周还没有照片", MondayOf),
    RecencyRule(9, "本月", "本月还没有照片", FirstOfMonth),
    RecencyRule(10, "前年", "前年没有照片", { it.minusYears(2).withDayOfYear(1) }, { it.minusYears(1).withDayOfYear(1) }),
    RecencyRule(11, "近半年", "近半年没有照片", { it.minusDays(182) }),
    RecencyRule(12, "近一年", "近一年没有照片", { it.minusDays(364) }),
    RecencyRule(13, "昨天", "昨天没有照片", { it.minusDays(1) }, { it }),
    RecencyRule(14, "前天", "前天没有照片", { it.minusDays(2) }, { it.minusDays(1) }),
    RecencyRule(15, "大前天", "大前天没有照片", { it.minusDays(3) }, { it.minusDays(2) }),
    RecencyRule(16, "上个月", "上个月没有照片", { it.minusMonths(1).withDayOfMonth(1) }, FirstOfMonth),
    RecencyRule(17, "上上个月", "上上个月没有照片", { it.minusMonths(2).withDayOfMonth(1) }, { it.minusMonths(1).withDayOfMonth(1) }),
    RecencyRule(18, "三月前", "三月前没有照片", { it.minusMonths(3).withDayOfMonth(1) }, { it.minusMonths(2).withDayOfMonth(1) }),
    RecencyRule(19, "四月前", "四月前没有照片", { it.minusMonths(4).withDayOfMonth(1) }, { it.minusMonths(3).withDayOfMonth(1) }),
    RecencyRule(20, "五月前", "五月前没有照片", { it.minusMonths(5).withDayOfMonth(1) }, { it.minusMonths(4).withDayOfMonth(1) }),
    RecencyRule(21, "六月前", "六月前没有照片", { it.minusMonths(6).withDayOfMonth(1) }, { it.minusMonths(5).withDayOfMonth(1) }),
    RecencyRule(22, "七月前", "七月前没有照片", { it.minusMonths(7).withDayOfMonth(1) }, { it.minusMonths(6).withDayOfMonth(1) }),
    RecencyRule(23, "八月前", "八月前没有照片", { it.minusMonths(8).withDayOfMonth(1) }, { it.minusMonths(7).withDayOfMonth(1) }),
    RecencyRule(24, "九月前", "九月前没有照片", { it.minusMonths(9).withDayOfMonth(1) }, { it.minusMonths(8).withDayOfMonth(1) }),
    RecencyRule(25, "十月前", "十月前没有照片", { it.minusMonths(10).withDayOfMonth(1) }, { it.minusMonths(9).withDayOfMonth(1) }),
    RecencyRule(26, "十一月前", "十一月前没有照片", { it.minusMonths(11).withDayOfMonth(1) }, { it.minusMonths(10).withDayOfMonth(1) }),
    RecencyRule(27, "十二月前", "十二月前没有照片", { it.minusMonths(12).withDayOfMonth(1) }, { it.minusMonths(11).withDayOfMonth(1) }),
)

private fun recencyRule(id: Int): RecencyRule? = RecencyRules.firstOrNull { it.id == id }

internal fun recencyLabel(id: Int): String = recencyRule(id)?.label ?: "全部日期"

internal fun recencyHint(id: Int): String = recencyRule(id)?.hint ?: "没有照片"

/**
 * 这一档的毫秒窗口，含首端、含末端；null = 不筛。
 * `today` 与 `zone` 由调用方给 —— 27 档的边界全靠固定日期才测得出来。
 */
internal fun recencyWindow(id: Int, today: LocalDate, zone: ZoneId): LongRange? =
    RecencyRules.firstOrNull { it.id == id }?.let { recencyWindowOf(it, today, zone) }

/** 窗口只依赖规则本身，所以测试可以拿一份改过的表来跑（否则自检是死的：改动传不进去）。 */
internal fun recencyWindowOf(r: RecencyRule, today: LocalDate, zone: ZoneId): LongRange? {
    val from = r.from ?: return null
    val start = from(today).atStartOfDay(zone).toInstant().toEpochMilli()
    val next = r.untilNext ?: return start..Long.MAX_VALUE
    return start..(next(today).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
}

private fun hourBucket(millis: Long): Int =
    hourBucketOf(Instant.ofEpochMilli(millis.coerceAtLeast(0L)).atZone(ZoneId.systemDefault()).hour)

private fun isWeekend(millis: Long): Boolean {
    val d = Instant.ofEpochMilli(millis.coerceAtLeast(0L)).atZone(ZoneId.systemDefault()).dayOfWeek
    return d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY
}

private fun matchesPhotoQuery(photo: PhotoItem, q: String): Boolean {
    if (photo.displayName.contains(q, ignoreCase = true) ||
        photo.bucketName.contains(q, ignoreCase = true)
    ) {
        return true
    }
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L)).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    when (q) {
        "今天" -> return day == today
        "昨天" -> return day == today.minusDays(1)
        "今年" -> return day.year == today.year
    }
    if (q.matches(FourDigits)) return day.year.toString() == q
    if (q.matches(YearMonthQuery)) {
        val parts = q.split('-', '/')
        val y = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return day.year == y && day.monthValue == m
    }
    val monthDay = MonthDayQuery.matchEntire(q)
    if (monthDay != null) {
        val m = monthDay.groupValues[1].toIntOrNull() ?: return false
        val d = monthDay.groupValues[2].toIntOrNull() ?: return false
        return day.monthValue == m && day.dayOfMonth == d
    }
    val monthOnly = MonthOnlyQuery.matchEntire(q)
    if (monthOnly != null) {
        val m = monthOnly.groupValues[1].toIntOrNull() ?: return false
        return day.monthValue == m
    }
    return false
}

/**
 * 「随拍原片」= 相机自己存的那一批。名字形状是 `SuiPai_<时间戳>.jpg`，**只有一个下划线**；
 * 任何加工件都叫 `SuiPai_<动作>_...`（一瞬/副本/灰度/2K…），两个下划线。
 *
 * 原来这里是 6 个 `!startsWith` 的手抄排除表，所以 `SuiPai_灰度_x.jpg`、`SuiPai_2K_x.jpg`
 * 这些加工件全都被算进「仅随拍」——而这一档自己的空态写的是「还没有随拍**原片**」
 * （`OnlyKinds` 编号 4）。按名字的数一下划线来判，以后新增任何加工动作都自动不算原片。
 */
private fun isSuiPaiShot(photo: PhotoItem): Boolean = isSuiPaiOriginalName(photo.displayName)

/** 纯字符串判据，JVM 里就能测（`PhotoItem` 持有 `android.net.Uri`，单测造不出来）。 */
internal fun isSuiPaiOriginalName(name: String): Boolean =
    name.startsWith("SuiPai_") && !name.substring("SuiPai_".length).contains("_")


private fun isEditedShot(photo: PhotoItem): Boolean {
    val name = photo.displayName
    return name.startsWith("SuiPai_旋转_") ||
        name.startsWith("SuiPai_转向_") ||
        name.startsWith("SuiPai_镜像_") ||
        name.startsWith("SuiPai_灰度_") ||
        name.startsWith("SuiPai_上下翻_") ||
        name.startsWith("SuiPai_圆角_") ||
        name.startsWith("SuiPai_2K_") ||
        name.startsWith("SuiPai_1080_") ||
        name.startsWith("SuiPai_720_") ||
        name.startsWith("SuiPai_480_") ||
        name.startsWith("SuiPai_320_") ||
        name.startsWith("SuiPai_240_") ||
        name.startsWith("SuiPai_160_") ||
        name.startsWith("SuiPai_120_") ||
        name.startsWith("SuiPai_96_") ||
        name.startsWith("SuiPai_64_") ||
        name.startsWith("SuiPai_48_") ||
        name.startsWith("SuiPai_32_") ||
        name.startsWith("SuiPai_24_") ||
        name.startsWith("SuiPai_16_") ||
        name.startsWith("SuiPai_12_") ||
        name.startsWith("SuiPai_裁切_")
}


private fun isTurnedShot(photo: PhotoItem): Boolean {
    val name = photo.displayName
    return name.startsWith("SuiPai_旋转_") ||
        name.startsWith("SuiPai_转向_") ||
        name.startsWith("SuiPai_镜像_")
}

private fun isScreenshot(photo: PhotoItem): Boolean {
    val bucket = photo.bucketName
    val name = photo.displayName
    return bucket.contains("Screenshot", ignoreCase = true) ||
        bucket.contains("截屏") ||
        bucket.contains("截图") ||
        name.startsWith("Screenshot", ignoreCase = true) ||
        name.contains("screenshot", ignoreCase = true)
}

private val MonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val YearMonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val Weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("E", java.util.Locale.CHINA)
private val MonthOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("M月")
private val YearMonthOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月")

// `String.matches(Regex("..."))` 每次调用都**重新编译一个 Pattern**，
// 而搜索那一路是**每键每张照片**都走一遍（5000 张 × 4 个模式）。
// 桌面 JVM 实测：5000 张一次 0.30~0.37 ms，提成常量之后 0.054 ms（~6 倍）。
private val FourDigits = Regex("\\d{4}")
private val YearMonthQuery = Regex("\\d{4}[-/]\\d{1,2}")
private val MonthDayQuery = Regex("(\\d{1,2})月(\\d{1,2})日?")
private val MonthOnlyQuery = Regex("(\\d{1,2})月")

private fun groupByDay(photos: List<PhotoItem>): List<DaySection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    return photos
        .groupBy { photo ->
            Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L)).atZone(zone).toLocalDate()
        }
        .toSortedMap(compareByDescending { it })
        .map { (date, items) ->
            val day = when (date) {
                today -> "今天"
                yesterday -> "昨天"
                else -> if (date.year == today.year) date.format(MonthDay) else date.format(YearMonthDay)
            }
            val label = "$day  ·  ${date.format(Weekday)}"
            DaySection(date.toString(), label, items)
        }
}

private fun groupByMonth(photos: List<PhotoItem>): List<DaySection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return photos
        .groupBy { photo ->
            Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L)).atZone(zone).toLocalDate().withDayOfMonth(1)
        }
        .toSortedMap(compareByDescending { it })
        .map { (date, items) ->
            val label = if (date.year == today.year) {
                date.format(MonthOnly)
            } else {
                date.format(YearMonthOnly)
            }
            DaySection(date.toString().take(7), "$label  ·  ${items.size}张", items)
        }
}

private fun groupByYear(photos: List<PhotoItem>): List<DaySection> {
    val zone = ZoneId.systemDefault()
    return photos
        .groupBy { photo ->
            Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L)).atZone(zone).year
        }
        .toSortedMap(compareByDescending { it })
        .map { (year, items) ->
            DaySection("$year", "${year}年  ·  ${items.size}张", items)
        }
}

private fun groupByWeek(photos: List<PhotoItem>): List<DaySection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return photos
        .groupBy { photo ->
            Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L))
                .atZone(zone)
                .toLocalDate()
                .with(java.time.DayOfWeek.MONDAY)
        }
        .toSortedMap(compareByDescending { it })
        .map { (start, items) ->
            val end = start.plusDays(6)
            val label = if (start.year == today.year) {
                "${start.monthValue}月${start.dayOfMonth}日–${end.dayOfMonth}日"
            } else {
                "${start.year}年${start.monthValue}月${start.dayOfMonth}日–${end.dayOfMonth}日"
            }
            DaySection(start.toString(), "$label  ·  ${items.size}张", items)
        }
}

/**
 * 一个分组在网格里的条目下标。每个分组占 1 行标题 + N 张照片，所以偏移要按 `1 + photos.size` 累加
 * —— 这个「+1」以前在 5 个按钮里各抄了一遍，改布局时要同时改 5 处。
 */
private fun sectionIndex(sections: List<DaySection>, matching: (DaySection) -> Boolean): Int {
    var index = 0
    for (section in sections) {
        if (matching(section)) return index
        index += 1 + section.photos.size
    }
    return -1
}

/** 从 from 那天起最早的一个分组（「本周」「本月」都是找它）。 */
private fun sectionIndexOnOrAfter(sections: List<DaySection>, from: java.time.LocalDate): Int {
    var index = 0
    var best = -1
    var bestKey = "9999-99-99"
    for (section in sections) {
        val day = runCatching { java.time.LocalDate.parse(section.key) }.getOrNull()
        if (day != null && day >= from && section.key <= bestKey) {
            best = index
            bestKey = section.key
        }
        index += 1 + section.photos.size
    }
    return best
}

/**
 * 快速跳转到某个时间分组 + 按年/按月/按册的横向筛选条。
 *
 * 从 GalleryScreen 里拆出来有两个理由：那些跳转按钮各自把「累加下标」的循环抄了一遍；
 * 更要紧的是 GalleryScreen 编出来是一个 3.4 万条指令的单方法，超过 ART 的 JIT 上限后
 * **永远不编译、全程解释执行**，冷启动因此要 9~20 秒（实测 `Method exceeds compiler
 * instruction limit` + `Skipped 323 frames`）。拆函数是唯一能让它回到可编译区间的办法。
 */
@Composable
private fun GalleryJumpRails(
    sections: List<DaySection>,
    years: List<String>,
    months: List<String>,
    photos: List<PhotoItem>,
    bucketFilter: String,
    onBucketFilter: (String) -> Unit,
    gridState: LazyGridState,
    onPicked: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // 这四个都要扫一遍分组表（`sectionIndexOnOrAfter` 里对每个 key 做一次 `LocalDate.parse`），
    // 桌面 JVM 量到 1400 组 × 2 趟 = 6.4~13.0 ms；而抽屉里每点一个筛选格都会重组这一段。
    // 下面 `buckets` 那一段本来就是 remember 的，这里同一做法。
    val monday = remember(sections) {
        sectionIndexOnOrAfter(sections, java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY))
    }
    val monthStart = remember(sections) {
        sectionIndexOnOrAfter(sections, java.time.LocalDate.now().withDayOfMonth(1))
    }
    val today = remember(sections) { sectionIndex(sections) { it.label.startsWith("今天") } }
    val yesterday = remember(sections) { sectionIndex(sections) { it.label.startsWith("昨天") } }

    // 以前这四个按钮各占一列（四个整行高的条子挤掉一屏照片），合成一行。
    if (today >= 0 || yesterday >= 0 || monday >= 0 || monthStart >= 0) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (today >= 0) {
                AppBarCell(
                    label = "今天",
                    on = false,
                    onClick = { scope.launch { gridState.scrollToItem(today) }; onPicked() },
                )
            }
            if (yesterday >= 0) {
                AppBarCell(
                    label = "昨天",
                    on = false,
                    onClick = { scope.launch { gridState.scrollToItem(yesterday) }; onPicked() },
                )
            }
            if (monday >= 0) {
                AppBarCell(
                    label = "本周",
                    on = false,
                    onClick = { scope.launch { gridState.scrollToItem(monday) }; onPicked() },
                )
            }
            if (monthStart >= 0) {
                AppBarCell(
                    label = "本月",
                    on = false,
                    onClick = { scope.launch { gridState.scrollToItem(monthStart) }; onPicked() },
                )
            }
        }
    }
    if (years.size > 1) {
        // 跳转条用会换行的 FlowRow：LazyRow 滚出视野的那几格既点不到，
        // 也不在语义树里（读屏的人和自动化脚本都看不见）。
        AppChipFlow {
            years.forEach { year ->
                AppBarCell(
                    label = "${year}年",
                    on = false,
                    onClick = {
                        val at = sectionIndex(sections) { it.key.startsWith(year) }
                        if (at >= 0) scope.launch { gridState.scrollToItem(at) }; onPicked()
                    },
                )
            }
        }
    }
    val buckets = remember(photos) {
        photos.groupingBy { it.bucketName }
            .eachCount()
            .entries
            .filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }
            .take(16)
            .map { it.key }
    }
    if (buckets.size > 1) {
        AppChipFlow {
            AppBarCell(
                label = if (bucketFilter.isBlank()) "全部册" else "册",
                on = bucketFilter.isBlank(),
                onClick = { onBucketFilter(""); onPicked() },
            )
            buckets.forEach { name ->
                AppBarCell(
                    label = if (bucketFilter == name) "· $name" else name,
                    on = bucketFilter == name,
                    onClick = { onBucketFilter(if (bucketFilter == name) "" else name); onPicked() },
                )
            }
        }
    }
    if (months.size > 1) {
        AppChipFlow {
            months.forEach { month ->
                val label = month.substringAfter('-').trimStart('0').ifBlank { month }
                AppBarCell(
                    label = "${label}月",
                    on = false,
                    onClick = {
                        val at = sectionIndex(sections) { it.key.startsWith(month) }
                        if (at >= 0) scope.launch { gridState.scrollToItem(at) }; onPicked()
                    },
                )
            }
        }
    }
}
