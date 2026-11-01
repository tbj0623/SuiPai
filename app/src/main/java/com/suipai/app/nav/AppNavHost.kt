package com.suipai.app.nav

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.suipai.app.data.Backup
import com.suipai.app.data.BackupCounts
import com.suipai.app.data.BackupResult
import com.suipai.app.data.Collections
import com.suipai.app.data.DuplicateGroup
import com.suipai.app.data.Duplicates
import com.suipai.app.data.deletionUrisOf
import com.suipai.app.data.MergeMode
import com.suipai.app.data.StorageInsight
import com.suipai.app.data.StorageSummary
import com.suipai.app.data.deleteRequestFor
import com.suipai.app.data.PhotoCollection
import com.suipai.app.data.SearchHistory
import com.suipai.app.data.SearchIndex
import com.suipai.app.data.Trash
import com.suipai.app.data.TrashItem
import com.suipai.app.data.VideoAccess
import com.suipai.app.data.VideoItem
import com.suipai.app.data.VideoRepository
import com.suipai.app.settings.OnboardingGate
import com.suipai.app.settings.ReadOnlySource
import com.suipai.app.settings.SettingAction
import com.suipai.app.settings.SettingChange
import com.suipai.app.settings.SettingsStore
import com.suipai.app.state.AppRoute
import com.suipai.app.state.AppState
import com.suipai.app.state.BackStack
import com.suipai.app.state.BatchActions
import com.suipai.app.state.ChromeState
import com.suipai.app.state.DeleteFlow
import com.suipai.app.state.NavPolicy
import com.suipai.app.state.NavState
import com.suipai.app.state.ShareImport
import com.suipai.app.ui.about.AboutSheet
import com.suipai.app.ui.camera.CameraScreen
import com.suipai.app.ui.collage.CollageScreen
import com.suipai.app.ui.collections.CollectionsScreen
import com.suipai.app.ui.common.ShutterFab
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.gallery.AlbumListScreen
import com.suipai.app.ui.gallery.GalleryScreen
import com.suipai.app.ui.memories.Memories
import com.suipai.app.ui.memories.MemoriesScreen
import com.suipai.app.ui.memories.MemoryDetailScreen
import com.suipai.app.ui.onboarding.OnboardingScreen
import com.suipai.app.ui.privacy.PrivacyScreen
import com.suipai.app.ui.search.SearchGroupKind
import com.suipai.app.ui.search.SearchPhoto
import com.suipai.app.ui.search.SearchResultGroup
import com.suipai.app.ui.search.SearchScreen
import com.suipai.app.ui.play.RecipeCardScreen
import com.suipai.app.ui.settings.AboutScreen
import com.suipai.app.ui.settings.AppearanceScreen
import com.suipai.app.ui.settings.BackupScreen
import com.suipai.app.ui.settings.SettingsScreen
import com.suipai.app.ui.settings.StorageScreen
// 「上次清理」这一格：`cleanStamp()` 写、`formatStamp()` 读。两个都在 StorageScreen.kt 里，
// 它们是同一件事的两半 —— 只写不读（或反过来）会让屏幕上那句「上次清理：…」永远不出现。
import com.suipai.app.ui.settings.cleanStamp
import com.suipai.app.ui.settings.formatStamp
import com.suipai.app.ui.slideshow.SlideshowScreen
import com.suipai.app.ui.sync.SyncScreen
import com.suipai.app.ui.tools.DuplicatesScreen
import com.suipai.app.ui.tools.StorageInsightScreen
import com.suipai.app.ui.trash.TrashScreen
import com.suipai.app.ui.theme.SuiPaiSheet
import com.suipai.app.ui.viewer.PhotoViewerScreen
import com.suipai.app.ui.video.VideoGrid
import com.suipai.app.ui.video.VideoPlayerScreen
import com.suipai.app.ui.video.videoAccessHint
import com.suipai.app.ui.yishun.YishunScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用 [BackStack] 驱动全部屏幕的导航宿主。
 *
 * 改前这里是 `MainActivity` 里一个 **1172 行**的 composable，屏幕选择靠
 * `when { 一瞬 -> …; 相机 -> …; 查看器 -> …; 相册内页 -> …; else -> 三个 tab }`，
 * 加一个新界面必须动这里、而且没有一个地方能写清"返回键的先后顺序"。
 *
 * 现在：
 *  - 目的地是 [AppRoute]（纯 Kotlin，可编码进 Bundle、可单测）；
 *  - 栈规则是 [BackStack] / [NavPolicy]（纯函数，`BackStackTest` / `NavPolicyTest` 钉住）；
 *  - 这一层只做**接线**：把哪个回调接到哪个状态上。屏幕本身一个字都没改。
 *
 * 行为零变化的三条硬约束：
 *  1. **屏幕的渲染优先级**与改前 `when` 一致（一瞬 > 相机 > 查看器 > 相册内页 > tab），
 *     由 [NavRender] 按"数据在不在"裁层实现；
 *  2. **返回键优先级**与改前一致（批量取消 > 清选择 > 退一层），由 [NavPolicy.resolveBack] 判；
 *  3. **testTag 与语义标签一个不改**（`nav_tab_0/1/2`、`theme_toggle`、`gallery_note`…）。
 *
 * 新界面接入见 `docs/arch/APP-ARCH.md` 的 5 步模板；本文件对应的改动点只有
 * `render()` 里的一个分支。
 */
@Composable
fun AppNavHost(
    state: AppState,
    nav: NavState,
    chrome: ChromeState,
    batch: BatchActions,
    delete: DeleteFlow,
    share: ShareImport,
    onSystemCamera: () -> Unit,
) {
    val stack = nav.stack
    val contextForWidget = LocalContext.current

    // 每一屏都可能要刷新（改前 LifecycleEventEffect 挂在根作用域上，效果相同）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }

    // ── 照片库变了：桌面小组件与「最近一张」快捷方式跟着重画 ──────────────
    //
    // 判据用**照片张数**（`state.photos.size`）而不是"每次 refresh 之后"：
    // `refresh()` 也被 `ON_RESUME` 调，挂在它后面等于"每次回前台都发一条广播"——
    // 而桌面小组件其实没变。张数变了才是"库里真的多了/少了东西"。
    //
    // 这个判据漏得掉一类事：**原地替换**（同一张照片被改内容、张数不变）。
    // 那类改动小组件本来就不显示差别（它画的是缩略图与一句「最近 N 张」），
    // 所以这里不为它加更贵的信号（比如 DATE_MODIFIED 的全表比较）。
    // 真正的完整性靠 `MainActivity` 启动时那一发（见那边的 ③）。
    //
    // 为什么要 `delay(600)`：批量删除是逐张走的，一次删 30 张会让 `photos.size`
    // 跳 30 次；每次都发广播、每次都让 launcher 重画四个 Bitmap，是白白烧一遍电。
    // 600 ms 是"一批做完"的量级，而且它只在**数量真的变了**之后才排一次。
    //
    // 时序上实际会发生的三次调用（都说清楚，免得下一个人以为这里有魔法）：
    //  1. 首帧 `photos` 还是空表（size 0）：只记下 0，不发；
    //  2. 首帧之后库读回来了（0 → N）：**会**发一次 —— 这正是想要的（小组件该显示这个库），
    //     而 `MainActivity` 启动那一发与它可能撞上，撞上时 `RecentWidgetProvider.inFlight`
    //     会把后一次直接丢掉（见那个 AtomicBoolean 的注释），不会双份解码；
    //  3. 之后每次增删（N → M，N ≠ M）再发一次。
    var widgetStamp by remember { mutableStateOf(-1) }
    LaunchedEffect(state.photos.size) {
        val now = state.photos.size
        val before = widgetStamp
        widgetStamp = now
        if (before < 0 || before == now) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        com.suipai.app.widget.RecentWidgetProvider.requestRefresh(contextForWidget)
    }

    // 那句结论（"删了 1 张 / 没允许 / 存了 X 张，Y 张没存上"）只挂 4.5 秒。
    // 改前就是这么写的（`LaunchedEffect(batchNote) { delay(4500); batchNote = null }`，
    // 在 MainActivity 的根作用域里）。重构时**必须一起搬**：少了这三行，那句结论会永远
    // 留在页眉 `gallery_note` 与查看器 `statusNote` 上，下一次操作的进度又会被它顶掉 ——
    // 用户看到的结论张冠李戴。
    LaunchedEffect(state.batchNote) {
        if (state.batchNote != null) {
            kotlinx.coroutines.delay(4500)
            state.batchNote = null
        }
    }

    // 分享导入：收件箱非空时导入、进一瞬、刷新。key 是收件箱本身 ——
    // `ShareImport.drain` 把清空放在最后，否则会取消掉正在跑的协程（见它的类注释）。
    LaunchedEffect(share.inbox) {
        share.drain { uri ->
            nav.removeFamily("camera")
            nav.push(AppRoute.Yishun(uri.toString()))
        }
    }

    // 返回键：优先级表在 NavPolicy 里（纯函数、可单测）。
    // 前六项用**栈里有没有那一族**而不是"数据在不在"：`enabled` 判的是"这一下返回键归不归我管"，
    // 而路由在就归我管 —— 数据恰好空掉的那一帧（查看器删掉最后一张）也要由这里把它从栈里退掉，
    // 否则那一下返回会穿透到系统、把 Activity 结束掉。
    // `hasLayer` 是加二级页面（设置 / 回收站 / 搜索 / 回忆…）时补的**兜底判据**：
    // 那一族页面没有自己的布尔字段，靠它才拦得住返回键。
    val intercepts = NavPolicy.Intercepts(
        batchRunning = state.batchRunning,
        hasSelection = state.selectedIds.isNotEmpty(),
        yishunOpen = stack.containsFamily("yishun"),
        cameraOpen = stack.containsFamily("camera"),
        viewerOpen = stack.containsFamily("viewer"),
        albumOpen = stack.containsFamily("album"),
        hasLayer = stack.depth > 0,
    )
    BackHandler(enabled = NavPolicy.intercepts(intercepts)) {
        when (NavPolicy.resolveBack(intercepts, stack.depth)) {
            NavPolicy.BackAction.CANCEL_BATCH -> batch.cancelQuietly()
            NavPolicy.BackAction.CLEAR_SELECTION -> state.clearSelection()
            NavPolicy.BackAction.POP -> backOneLayer(state, nav)
            NavPolicy.BackAction.EXIT -> Unit   // enabled=false 时不会进来
        }
    }

    // 这一帧真的该画哪一屏（按"数据在不在"裁掉路由还在、数据没了的层）。
    val shown = NavRender.effective(
        stack = stack,
        viewerReady = state.viewerPhotos != null,
        albumReady = state.albumPhotos != null,
    )

    when (shown) {
        // ── 一瞬 ──────────────────────────────────────────────────────
        is AppRoute.Yishun -> {
            val uri = Uri.parse(shown.uri)
            val viewerList = state.viewerPhotos
            val canFlip = (viewerList?.size ?: 0) > 1
            YishunScreen(
                uri = uri,
                onClose = {
                    nav.pop()
                    if (state.jumpUri != null) state.refresh()
                },
                onPrev = if (canFlip) {
                    {
                        val list = state.viewerPhotos
                        if (list != null && list.size >= 2) {
                            val cur = list.indexOfFirst { it.uri == uri }
                                .let { if (it >= 0) it else state.viewerIndex }
                            val prev = list[(cur - 1).mod(list.size)]
                            state.viewerIndex = list.indexOfFirst { it.id == prev.id }.coerceAtLeast(0)
                            nav.replaceTop(AppRoute.Yishun(prev.uri.toString()))
                        }
                    }
                } else {
                    null
                },
                onNext = if (canFlip) {
                    {
                        val list = state.viewerPhotos
                        if (list != null && list.size >= 2) {
                            val cur = list.indexOfFirst { it.uri == uri }
                                .let { if (it >= 0) it else state.viewerIndex }
                            val next = list[(cur + 1).mod(list.size)]
                            state.viewerIndex = list.indexOfFirst { it.id == next.id }.coerceAtLeast(0)
                            nav.replaceTop(AppRoute.Yishun(next.uri.toString()))
                        }
                    }
                } else {
                    null
                },
                onSaved = { saved ->
                    // 改前：showCamera = false; jumpUri = uri; refresh()
                    nav.removeFamily("camera")
                    state.jumpUri = saved
                    state.refresh()
                },
                onDeleted = {
                    state.jumpUri = null
                    state.refresh()
                },
                // 屏幕只报「哪个配方、多少强度」，去哪一屏是这一层的决定 ——
                // 这条分工写在 `ui/play/RecipeCardScreen.kt` 文件头。
                onRecipeCard = { u, lookId, amount ->
                    nav.push(AppRoute.RecipeCard(u, lookId, amount))
                },
            )
        }

        // ── 内置相机 ──────────────────────────────────────────────────
        AppRoute.Camera -> {
            CameraScreen(
                onCaptured = { uri, openYishun ->
                    if (openYishun) {
                        // 改前：showCamera = false; yishunUri = uri —— 一瞬顶掉相机层
                        nav.replaceTop(AppRoute.Yishun(uri.toString()))
                    }
                    state.refresh()
                },
                onReview = { uri ->
                    nav.replaceTop(AppRoute.Yishun(uri.toString()))
                },
                onSystemCamera = onSystemCamera,
                onClose = { nav.pop() },
            )
        }

        // ── 大图查看器 ────────────────────────────────────────────────
        is AppRoute.Viewer -> {
            val photos = state.viewerPhotos
            if (photos != null) {
                PhotoViewerScreen(
                    photos = photos,
                    initialIndex = shown.index,
                    onClose = { nav.pop() },
                    onYishun = { photo ->
                        state.syncViewerTo(photo)
                        nav.push(AppRoute.Yishun(photo.uri.toString()))
                    },
                    onDeleted = { photo ->
                        delete.deleteOneFromViewer(photo)
                    },
                    favoriteIds = state.favoriteIds,
                    onToggleFavorite = { state.toggleFavorite(it) },
                    hiddenIds = state.hiddenIds,
                    onToggleHidden = { state.toggleHidden(it) },
                    // 查看器那一层开着的时候画廊根本不在 composition 里，
                    // 所以删除的结论必须也传给查看器，否则又变成一声不吭。
                    statusNote = state.batchHint ?: state.batchNote,
                )
            }
        }

        // ── 相册内页 ──────────────────────────────────────────────────
        is AppRoute.AlbumDetail -> {
            val albumPhotos = state.albumPhotos
            if (albumPhotos != null) {
                Scaffold(
                    floatingActionButton = {
                        if (state.selectedIds.isEmpty()) {
                            ShutterFab(onClick = { nav.push(AppRoute.Camera) }, onClickLabel = "打开相机")
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        GalleryScreen(
                            photos = albumPhotos,
                            title = shown.albumTitle ?: "相册",
                            onBack = { backOneLayer(state, nav) },
                            refreshing = state.refreshing,
                            onRefresh = { state.refresh(force = true) },
                            selectedIds = state.selectedIds,
                            onToggleSelect = { state.toggleSelect(it) },
                            onLongPress = { state.addToSelection(it.id) },
                            onCancelSelect = { state.clearSelection() },
                            onBatchYishun = { lookId -> batch.batchYishun(albumPhotos, lookId) },
                            onBatchDelete = { batch.batchDelete(albumPhotos) },
                            onBatchFavorite = { batch.batchFavorite(albumPhotos) },
                            onBatchCopy = { batch.batchCopy(albumPhotos) },
                            onBatchHide = { batch.batchHide(albumPhotos) },
                            onCancelBatch = { batch.cancel() },
                            favoriteIds = state.favoriteIds,
                            hiddenIds = state.hiddenIds,
                            hiddenPhotos = state.hiddenPhotos,
                            showHiddenInAll = state.albumId == com.suipai.app.data.Hidden.ALBUM_ID,
                            onInvert = { items -> state.invertSelection(items) },
                            favoritePhotos = state.favoritePhotos,
                            onSelectPhotos = { items -> state.selectAllOrClear(items) },
                            batchHint = state.batchHint ?: state.batchNote,
                            onNote = { state.batchNote = it },
                            problem = state.libraryProblem,
                            onOpen = { photo ->
                                state.openViewer(fromGalleryCell = false, photo = photo, source = albumPhotos)
                                nav.showViewerAt(state.viewerIndex)
                            },
                        )
                    }
                }
            }
        }

        // ── 二级页面（设置 / 隐私 / 引导 / 回收站 / 收藏夹 / 搜索 / 回忆 / 拼图 / 幻灯片）──
        //
        // 放在 tab 之前：这些页面**占满整屏**，上面不该有底栏与页眉
        // （各屏幕自己画页首，「返回」也由它们自己的页首与系统 BACK 负责）。
        // 它们全部是"从页眉「更多」进来、退一层就回原 tab"的一条链，所以
        // `SecondaryScreenHost` 返回 false 时（还没接的路由）落到下面的 tab 分支。
        else -> if (!SecondaryScreenHost(
                route = shown,
                state = state,
                nav = nav,
                onBack = { backOneLayer(state, nav) },
                onOpenPhoto = { photos, index ->
                    state.openViewerAt(photos, index)
                    nav.showViewerAt(index)
                },
            )
        ) {
            val tabIndex = (shown as? AppRoute.Tab)?.index ?: 0
            Scaffold(
                bottomBar = {
                    AppBottomBar(
                        currentTabIndex = tabIndex,
                        onTab = { nav.switchTab(AppRoute.tab(it)) },
                    )
                },
                floatingActionButton = {
                    if (state.selectedIds.isEmpty()) {
                        ShutterFab(onClick = { nav.push(AppRoute.Camera) }, onClickLabel = "打开相机")
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (chrome.aboutOpen) {
                        AboutSheet(onDismiss = { chrome.closeAbout() })
                    }
                    AppHeader(
                        currentTabIndex = tabIndex,
                        onTab = { nav.switchTab(AppRoute.tab(it)) },
                        chrome = chrome,
                        selectedIds = state.selectedIds,
                        // 页眉「更多」抽屉里的每一颗都落到这里 —— 它们全是**二级页面**，
                        // 压在 tab 上（返回键退一层就回到原 tab）。
                        onNavigate = { route -> nav.push(route) },
                    )
                    when (tabIndex) {
                        0 -> GalleryScreen(
                            photos = state.photos,
                            refreshing = state.refreshing,
                            onRefresh = { state.refresh(force = true) },
                            selectedIds = state.selectedIds,
                            onToggleSelect = { state.toggleSelect(it) },
                            onLongPress = { state.addToSelection(it.id) },
                            onCancelSelect = { state.clearSelection() },
                            onBatchYishun = { lookId -> batch.batchYishun(state.photos, lookId) },
                            onBatchDelete = { batch.batchDelete(state.photos) },
                            onBatchFavorite = { batch.batchFavorite(state.photos) },
                            onBatchCopy = { batch.batchCopy(state.photos) },
                            onBatchHide = { batch.batchHide(state.photos) },
                            onCancelBatch = { batch.cancel() },
                            favoriteIds = state.favoriteIds,
                            hiddenIds = state.hiddenIds,
                            hiddenPhotos = state.hiddenPhotos,
                            onInvert = { items -> state.invertSelection(items) },
                            favoritePhotos = state.favoritePhotos,
                            onSelectPhotos = { items -> state.selectAllOrClear(items) },
                            batchHint = state.batchHint ?: state.batchNote,
                            onNote = { state.batchNote = it },
                            problem = state.libraryProblem,
                            hasMore = state.hasMore,
                            loadingMore = state.loadingMore,
                            onLoadMore = { state.loadMore() },
                            onOpen = { photo ->
                                state.openViewer(fromGalleryCell = true, photo = photo, source = state.photos)
                                nav.showViewerAt(state.viewerIndex)
                            },
                        )
                        1 -> AlbumListScreen(
                            albums = state.albums,
                            refreshing = state.refreshing,
                            onRefresh = { state.refresh(force = true) },
                            onOpen = { album ->
                                state.openAlbum(album)
                                nav.push(AppRoute.AlbumDetail(album.id, album.name))
                            },
                        )
                        else -> SyncScreen(onSaved = { state.refresh(force = true) })
                    }
                }
            }
        }
    }
}

/**
 * 退一层。相册内页/查看器的数据要跟着路由一起清掉 —— 改前它们是三个独立状态，
 * 关内页时 `albumPhotos = null; albumId = null; albumTitle = null` 是三句；
 * 这里收成一次调用，再由路由的 `pop` 保证顺序。
 */
private fun backOneLayer(state: AppState, nav: NavState) {
    val top = nav.stack.layers.lastOrNull()
    when (top) {
        is AppRoute.AlbumDetail -> state.closeAlbum()
        is AppRoute.Viewer -> state.closeViewer()
        is AppRoute.Yishun -> state.closeYishun()
        else -> Unit
    }
    nav.pop()
}

// ══════════════════════════════════════════════════════════════════════
// 二级页面接线（设置 / 外观 / 存储 / 关于 / 隐私 / 引导 / 回收站 /
//              收藏夹 / 搜索 / 回忆 / 拼图 / 幻灯片）
// ══════════════════════════════════════════════════════════════════════
//
// 这一族屏幕全部是"收参数 + 回调"，自己不认识路由、不读 Activity、不持有仓库
// （见各 lane 的文档：SETTINGS.md §7 / SEARCH.md §7 / TRASH-COLLECTIONS.md / PLAY.md）。
// 所以**接线全在这里**：谁提供数据、回调落到哪个路由，只有这一处。
//
// 三条纪律（照抄 `docs/arch/APP-ARCH.md` §六 的模板）：
//  1. 仓库实例用 `remember` 建一次（`SettingsStore` / `Trash` / `Collections` / `SearchIndex`），
//     不在每次重组里 new —— 它们各自持有一份内存缓存与文件句柄。
//  2. 读盘/读库一律 `LaunchedEffect` + `Dispatchers.IO`，不在 composition 里做。
//  3. 每个回调都先说清"退到哪"，不在这里面猜用户想回哪 —— 由 `backOneLayer` 统一。

/**
 * 二级页面宿主：把当前路由映射到具体屏幕。
 *
 * 名字以大写开头是因为**它自成一个组合子树**（顶层 `when` 的一个分支），
 * 而不是"有返回值的工具函数"—— 那个 Boolean 返回值是"画没画出来"的状态回执，
 * 不是计算结果的类型。lint 的 `ComposableNaming` 按"有返回值就该小写"报了这一条，
 * 但这条规则的本意是拦住 `@Composable fun formatDate(): String` 那种把计算塞进
 * composition 的写法；这里的返回值不参与任何布局计算，只用来决定要不要退回下层。
 * 所以保留大写（与全仓 `*Screen` / `*Host` 的命名一致），就地静音。
 *
 * @param route 当前要画的二级路由（由 [AppNavHost] 从栈顶取）
 * @param onBack 统一的"退一层"（页内返回与系统返回走同一条）
 * @param onOpenPhoto 点某张照片：打开查看器（收藏夹/搜索/回忆详情都要）
 * @param onPush 进下一页（设置 → 外观 / 收藏夹 → 某个夹 / 回忆 → 某条）
 * @return 有没有画出来。`false` 表示这个路由还没接（宿主会退回下层）——
 *   返回 false 比"画一片空白"好：至少用户能回到上一屏。
 */
@Suppress("ComposableNaming")
@Composable
private fun SecondaryScreenHost(
    route: AppRoute,
    state: AppState,
    nav: NavState,
    onBack: () -> Unit,
    onOpenPhoto: (List<com.suipai.app.data.PhotoItem>, Int) -> Unit,
): Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 仓库各建一次。`SettingsStore.of` 取 applicationContext；另外三个只吃 filesDir / resolver。
    val store = remember(context) { SettingsStore.of(context) }
    // `Trash.of` 是进程级单例：搬家（AppState）、恢复（这一页）、启动清理（MainActivity）
    // 必须共用同一把锁，否则并发时是 read-modify-write 丢更新（账本记着的那条真 bug）。
    val trash = remember(context) { Trash.of(context) }
    val collections = remember(context) { Collections(context) }
    val searchIndex = remember(context) { SearchIndex(context) }
    val searchHistory = remember(context) { SearchHistory(context) }

    // 设置快照 + 只读值：一份状态，改一次重读一次（`put` 之后 `load()` 拿到新值）。
    var snapshot by remember { mutableStateOf(store.load()) }
    var settingsNote by remember { mutableStateOf<String?>(null) }

    // 只读值在 IO 上算一次（版本号 / 机型 / 占用 / 张数是 binder + 文件遍历）。
    var readonly by remember { mutableStateOf<Map<ReadOnlySource, String>>(emptyMap()) }
    LaunchedEffect(snapshot, state.photos.size) {
        readonly = withContext(Dispatchers.IO) { readOnlyValues(context, trash, state) }
    }

    // 回收站：清单一屏一读（恢复/清除之后要重读）。
    var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var trashLoading by remember { mutableStateOf(true) }
    var trashNote by remember { mutableStateOf<String?>(null) }
    fun reloadTrash() {
        scope.launch {
            trashLoading = true
            trashItems = withContext(Dispatchers.IO) { trash.list() }
            trashLoading = false
        }
    }
    if (route is AppRoute.Trash) LaunchedEffect(Unit) { reloadTrash() }

    // 收藏夹清单 + 封面（封面由第一张照片的 uri 提供；取不到就 null，界面画占位）。
    var collectionsList by remember { mutableStateOf<List<PhotoCollection>>(emptyList()) }
    var collectionCovers by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var collectionsNote by remember { mutableStateOf<String?>(null) }
    if (route is AppRoute.Collections) {
        LaunchedEffect(Unit) {
            collectionsList = withContext(Dispatchers.IO) { collections.list() }
            collectionCovers = withContext(Dispatchers.IO) {
                collections.list().associate { c ->
                    c.id to c.photoIds.firstOrNull()?.let { id ->
                        state.photos.firstOrNull { it.id == id }?.uri?.toString()
                    }
                }
            }
        }
    }

    // 搜索：索引加载 / 查询 / 防抖。
    var searchQuery by remember(route) { mutableStateOf((route as? AppRoute.Search)?.query.orEmpty()) }
    var searchResults by remember { mutableStateOf<List<SearchResultGroup>>(emptyList()) }
    var searchHistoryList by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var indexing by remember { mutableStateOf(false) }
    var indexProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    if (route is AppRoute.Search) {
        LaunchedEffect(Unit) {
            searchHistoryList = withContext(Dispatchers.IO) {
                searchHistory.load()
                searchHistory.recent()
            }
            indexing = true
            // 先读已有索引（快）；没有就在 IO 上重建一次（慢，但带进度）。
            val loaded = withContext(Dispatchers.IO) { searchIndex.load() }
            if (loaded == 0) {
                withContext(Dispatchers.IO) {
                    searchIndex.rebuild { done, total ->
                        indexProgress = if (total > 0) done to total else null
                    }
                }
            }
            indexing = false
            indexProgress = null
        }
        // 查询防抖 200 ms（SEARCH.md §7：5000 张一次 2~6 ms，但每键重算会打断输入）。
        LaunchedEffect(searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) {
                searchResults = emptyList()
                searchSuggestions = emptyList()
                return@LaunchedEffect
            }
            delay(200)
            val matches = withContext(Dispatchers.IO) { searchIndex.match(q) }
            searchResults = withContext(Dispatchers.IO) {
                groupSearchMatches(context, matches)
            }
            searchSuggestions = withContext(Dispatchers.IO) { searchIndex.suggestions(q) }
        }
    }

    // 回忆：由整库照片 + 注入时钟算出来（纯函数，见 Memories 的注释）。
    val memories = remember(state.photos) {
        Memories.build(state.photos, System.currentTimeMillis())
    }

    // 存储页的三个数字（只有进存储页才算，别在每次重组里遍历目录）。
    var storageText by remember { mutableStateOf(Triple("—", "—", "—")) }
    // 「上次清理」的**真值在磁盘上**（`cfg_cache_clean_at`），修前是一个初始为
    // "还没清过"、之后只被改成 "刚刚" 的内存状态：退出这一屏就没了，重新进来又变成
    // "还没清过"。而 `docs/product/SETTINGS.md` 把这个键写进「清除缓存」那一行，
    // 也就是它本来就是要落盘的 —— 写了没人读（`formatStamp` 那条链一直没人接）。
    var lastCleanAt by remember { mutableStateOf("") }
    if (route is AppRoute.SettingsStorage) {
        LaunchedEffect(Unit) {
            storageText = withContext(Dispatchers.IO) { storageNumbers(context, state) }
            lastCleanAt = runCatching {
                store.load().text(com.suipai.app.settings.SettingsKeys.Storage.cacheCleanAt)
            }.getOrDefault("")
        }
    }

    // ── 导出与恢复（备份文件进出）─────────────────────────────────────
    //
    // 这一屏的数据（本机有多少条、上次导出是什么时候）**只有进去时才有意义**，
    // 而恢复之后本机数据整个换了，所以两个数字都跟着 `backupEpoch` 重算：
    // 每完成一次导出/恢复就 +1，`LaunchedEffect` 看见它就再读一遍。
    //
    // 为什么本机条数要用 `Backup.collect()`（而不是拿 `state.photos.size` 凑）：
    // 那份计数要的是"备份会带走多少条"，与照片张数无关；两处各算一套口径，
    // 恢复前后屏幕上那几个数字就会互相矛盾。
    var backupLocal by remember { mutableStateOf(BackupCounts()) }
    var backupLastAt by remember { mutableStateOf(0L) }
    var backupLastCounts by remember { mutableStateOf<BackupCounts?>(null) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupEpoch by remember { mutableIntStateOf(0) }
    // 用户在 `BackupScreen` 里选的那一种合并方式。**选中 → 点确认 → 开文件选择器** 是三步，
    // 而系统选择器会把我们切到后台，回来时这个值必须还在 —— 所以它存在宿主这一层，
    // 不放在屏幕内部（屏幕的那个 `mode` 是 `remember`，回来后是 null）。
    var pendingMergeMode by remember { mutableStateOf<MergeMode?>(null) }
    if (route is AppRoute.Backup) {
        LaunchedEffect(backupEpoch) {
            val collected = withContext(Dispatchers.IO) {
                runCatching { Backup.collect(context) }.getOrNull()
            }
            backupLocal = collected?.counts() ?: BackupCounts()
            // 「上次导出」落在 `Privacy.exportAt` 里，格式按那个键的注释是 **ISO-8601 文本**
            // （不是毫秒数：同一个键也可能被别的 lane 读，写成毫秒就等于改了这个键的单位）。
            // 解不出来（空串 / 手改坏了）一律当"从没导出过"，不许抛。
            backupLastAt = runCatching {
                store.load().text(com.suipai.app.settings.SettingsKeys.Privacy.exportAt)
                    .takeIf { it.isNotBlank() }
                    ?.let { java.time.Instant.parse(it).toEpochMilli() }
            }.getOrNull() ?: 0L
        }
    }

    // 两个 SAF launcher：`CreateDocument` 只负责"选一个位置写"，`OpenDocument` 只负责"选一个文件读"。
    // **都不需要任何存储权限** —— 授权跟着这两个 uri 走，不跟着应用走（见 `Backup.exportTo` 的注释）。
    //
    // 它们必须挂在 `SecondaryScreenHost` 这一层而不是 `MainActivity`：
    // 这一屏是唯一的使用者，而 `MainActivity` 的七职责里已经有两个 launcher（删除授权 / 系统相机），
    // 再加会让它重新变成"什么都有的地方"（同 `DuplicatesSection` 里那段说明）。
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            // 用户在系统选择器里按了取消：什么都没发生，而且要说出来。
            backupNote = "取消了，没导出"
            backupBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { Backup.exportTo(context, uri) }
            backupNote = result.line("导出")
            if (result is BackupResult.Ok) {
                backupLastAt = result.at
                backupLastCounts = result.counts
                // 落盘"上次导出时刻"：这个数字要跨进程存活（换机前最后一次导出的时间
                // 是用户判断"这份文件新不新"的唯一依据，不能只活在 composition 里）。
                // 写成 `SettingKeys.Privacy.exportAt` 文档里说的 ISO-8601 —— 那个键的口径是
                // 「上一次导出的时间（ISO-8601，空串 = 从没导过）」，写毫秒会把这个键的单位改掉。
                withContext(Dispatchers.IO) {
                    store.put(
                        com.suipai.app.settings.SettingsKeys.Privacy.exportAt,
                        java.time.Instant.ofEpochMilli(result.at).toString(),
                    )
                }
            }
            backupBusy = false
            // 导出不改照片也不改清单，但会重读一次本机计数（设置可能刚被别处写过）。
            backupEpoch++
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            backupNote = "取消了，本机数据没动"
            backupBusy = false
            return@rememberLauncherForActivityResult
        }
        val mode = pendingMergeMode
        if (mode == null) {
            // 理论上到不了（确认按钮在没选模式时是灰的）；真到了就**不读文件**，
            // 因为"用哪种合并方式"没有默认值 —— 猜一个 REPLACE 会删掉用户本机的东西。
            backupNote = "没选合并方式，本机数据没动"
            backupBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { Backup.importFrom(context, uri, mode) }
            backupNote = result.line("恢复")
            // 恢复不改照片本身，但会换掉收藏/隐藏/收藏夹/搜索历史/设置 ——
            // 收藏与隐藏的**徽标**要重读，否则界面上还是旧的那一套。
            if (result is BackupResult.Ok) {
                backupLastAt = result.at
                backupLastCounts = result.counts
                // 恢复换掉的是收藏/隐藏/收藏夹/设置，**照片库本身没变**，所以
                // `generation()` 不会动；但 id 集合可能整份不同，靠 force 才不会被门控拦下。
                state.refresh(force = true)
                // 设置也可能被整份换掉：屏幕上那份 `snapshot` 不重读的话，
                // 用户退回设置页看到的还是恢复之前的值。
                snapshot = store.load()
            }
            pendingMergeMode = null
            backupBusy = false
            backupEpoch++
        }
    }

    // 二级页面统一套一层 `safeDrawing` 内边距。
    //
    // 为什么加在这里、而不是让 14 个屏幕各自处理：`MainActivity` 开了 `enableEdgeToEdge()`，
    // 所以每个屏幕都从屏幕的物理顶端开始画。装机截图量到过具体后果 —— 引导页的「随拍」
    // 标题与状态栏时钟**叠在一起**（「5:05」压在「随拍」两个字上），二级页面的页首按钮
    // 也会被手势条压住。
    //
    // 修在宿主层有三个理由：这些页面本来就都从 `SecondaryScreenHost` 出发、都自己画页首
    // （不用 `Scaffold` 的 `innerPadding`）；再加一条"每个屏幕记得自己加 insets"，等于
    // 让以后新加的屏幕各犯一次同样的错；而包在这里只包一次，14 个分支一行都不用改。
    //
    // tab 那一支**不能**再套这一层：它走 `Scaffold`，`innerPadding` 已经让出了状态栏与
    // 导航栏，再包一次会双份留白（这一条是这两个分支唯一的结构差异，别顺手统一掉）。
    //
    // `safeDrawing` 而不是 `systemBars`：它把刘海/挖孔也算进去，横屏时还带左右安全区。
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (route) {
        // ── 设置中心 ──────────────────────────────────────────────
        AppRoute.Settings -> SettingsScreen(
            snapshot = snapshot,
            readonly = readonly,
            // `onChange` 收到的是 `SettingChange`，其中 `Action` 这一类**不落盘** ——
            // 它代表"进外观子屏 / 进存储子屏 / 跳系统权限页 / 清缓存"。改前这里把整份
            // 改动交给 `applySettingChange`，而它有一支 `is SettingChange.Action -> Unit`
            // （注释写着"动作由屏幕的 onAction 走"），可 `SettingsScreen` 从来没有
            // `onAction` 参数 —— 于是设置中心那七颗 Action 行（外观 / 存储管理 /
            // 隐私与数据 / 相册权限 / 关于随拍 / 重看引导 / 清除缓存）**全部点了没反应**：
            // 不崩、不报错、也不改任何值，是这一轮最典型的"点了不生效"。
            //
            // 修在调用侧（而不是给 `SettingsScreen` 加 `onAction`）：路由去哪一屏是
            // 这一层的事，屏幕不该认识 `AppRoute`（分工见 `docs/arch/APP-ARCH.md` §六）。
            onChange = { change ->
                if (change is SettingChange.Action) {
                    dispatchSettingAction(
                        action = change.action,
                        nav = nav,
                        store = store,
                        context = context,
                        snapshot = snapshot,
                        // 设置总目录上**没有**清缓存那一行（`CLEAR_CACHE` 只出现在存储组，
                        // 而那一行是 Action 型、点进存储子屏之后才被点到）。真走到这里说明
                        // 有人在总目录上加了它 —— 点都点了，就得真的清。
                        onClearCache = {
                            scope.launch {
                                val freed = withContext(Dispatchers.IO) { clearCache(context) }
                                storageText = withContext(Dispatchers.IO) { storageNumbers(context, state) }
                                settingsNote = "清了 $freed"
                            }
                        },
                        onClearLocalData = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.resetAll() }
                                snapshot = store.load()
                                settingsNote = "已清掉本机偏好（照片没动）"
                            }
                        },
                        onNote = { settingsNote = it },
                    )
                } else {
                    applySettingChange(context, store, change) { snapshot = it }
                }
                snapshot = store.load()
            },
            onOpenBackup = { nav.push(AppRoute.Backup) },
            onBack = onBack,
        )

        // ── 设置 › 外观 ───────────────────────────────────────────
        AppRoute.SettingsAppearance -> AppearanceScreen(
            snapshot = snapshot,
            onChange = { change -> applySettingChange(context, store, change) { snapshot = it }; snapshot = store.load() },
            onBack = onBack,
        )

        // ── 设置 › 存储 ───────────────────────────────────────────
        AppRoute.SettingsStorage -> StorageScreen(
            usedText = storageText.first,
            cacheText = storageText.second,
            libraryText = storageText.third,
            // 磁盘上的 ISO 时间 → 屏幕上那句话。**空串要原样传下去**：
            // `StorageScreen` 拿 `isBlank()` 区分「这台设备上还没清过」与「上次清理：…」，
            // 先 `formatStamp` 成「还没」会把那句空态文案吃掉。
            lastCleanAt = if (lastCleanAt.isBlank()) "" else formatStamp(lastCleanAt),
            hint = settingsNote,
            // 这一屏的六颗动作**全部走这一条分派**。改前这里只写了三条（清缓存 + 两个跳系统页），
            // 而屏幕自己还会发 `EXPORT_LOCAL_DATA`（复制本机数据说明）与 `CLEAR_LOCAL_DATA`
            // （清除设置，含两步确认那颗）—— 它们落到 `else -> Unit`，也就是点了什么都没发生。
            onAction = { action ->
                dispatchSettingAction(
                    action = action,
                    nav = nav,
                    store = store,
                    context = context,
                    snapshot = snapshot,
                    onClearCache = {
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { clearCache(context) }
                            settingsNote = "清了 $freed"
                            // 「上次清理」落盘：它是跨进程存活的事实（见 `Storage.cacheCleanAt`
                            // 的键注释），只在内存里写「刚刚」等于退出这一屏就失忆。
                            // 用 `cleanStamp()`（ISO-8601 截到分钟）——`formatStamp` 反过来读它。
                            val stamp = cleanStamp()
                            withContext(Dispatchers.IO) {
                                store.put(com.suipai.app.settings.SettingsKeys.Storage.cacheCleanAt, stamp)
                            }
                            lastCleanAt = stamp
                            storageText = withContext(Dispatchers.IO) { storageNumbers(context, state) }
                        }
                    },
                    onClearLocalData = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.resetAll() }
                            snapshot = store.load()
                            settingsNote = "已清掉本机偏好（照片没动）"
                        }
                    },
                    onNote = { settingsNote = it },
                )
            },
            // 「刷新占用」：只重量一次数字，不动任何文件。
            onRefreshNumbers = {
                scope.launch {
                    storageText = withContext(Dispatchers.IO) { storageNumbers(context, state) }
                    settingsNote = "重新量过了"
                }
            },
            onBack = onBack,
        )

        // ── 设置 › 关于 ───────────────────────────────────────────
        AppRoute.SettingsAbout -> AboutScreen(
            versionText = readonly[ReadOnlySource.APP_VERSION] ?: "—",
            deviceText = readonly[ReadOnlySource.DEVICE_LINE] ?: "—",
            savePath = readonly[ReadOnlySource.SAVE_LOCATION] ?: "—",
            onboardingStatus = OnboardingGate.statusText(snapshot),
            onReplayOnboarding = {
                OnboardingGate.replay(store)
                snapshot = store.load()
                nav.push(AppRoute.Onboarding)
            },
            onBack = onBack,
        )

        // ── 设置 › 导出与恢复 ─────────────────────────────────────
        //
        // 这一屏自己的三条闸（二次确认、模式必须用户选、REPLACE 的后果写在按钮上面）
        // 全在 `BackupScreen` 里，宿主只负责四件事：
        //  1. 给它本机现状（本机条数 / 上次导出）；
        //  2. 把「导出」翻成 `CreateDocument("application/json")`；
        //  3. 把「确认恢复」翻成 `OpenDocument()`，并把用户选的模式带过去；
        //  4. 把结果写回 `note`（成功与失败都说清楚）。
        // SAF 不需要任何权限，所以这里既不申请也不检查 —— 授权跟着那一个 uri 走。
        AppRoute.Backup -> BackupScreen(
            localCounts = backupLocal,
            lastExportAt = backupLastAt,
            lastExportCounts = backupLastCounts,
            note = backupNote,
            busy = backupBusy,
            onExport = {
                if (backupBusy) {
                    // 手快连点两下：第二下什么都不做，但**要说一句**，不许静默。
                    backupNote = "上一次还在跑，等它结束"
                } else {
                    backupBusy = true
                    backupNote = null
                    // 文件名带日期（`Backup <年-月-日>.json`）：用户在新设备上选文件时，
                    // 一排同名文件里只有日期能分辨哪一份是新的。
                    backupExportLauncher.launch(backupSuggestedName(System.currentTimeMillis()))
                }
            },
            onImport = { mode ->
                // `onImport` 只在用户读完两段后果、选好模式、并按下确认之后才被调用
                // （见 `RestoreBlock` 的三步闸）。所以这里**直接**开选择器 ——
                // 再弹一次自带确认框等于把同一个问题问两遍。
                if (backupBusy) {
                    backupNote = "上一次还在跑，等它结束"
                } else {
                    pendingMergeMode = mode
                    backupBusy = true
                    backupNote = null
                    backupImportLauncher.launch(arrayOf("application/json"))
                }
            },
            onBack = onBack,
        )

        // ── 隐私与数据 ────────────────────────────────────────────
        AppRoute.Privacy -> PrivacyScreen(
            readonly = readonly,
            exportText = remember(snapshot) { localDataText(snapshot) },
            exportHint = settingsNote,
            // 与设置总目录同一条分派：这一屏也会发 EXPORT_LOCAL_DATA / CLEAR_LOCAL_DATA /
            // 两个跳系统页，而改前是手抄一份 `when`，`else -> Unit`。手抄的代价是
            // "屏幕加了动作、这里忘了加一支" 就静默 —— 收进 `dispatchSettingAction` 之后
            // 那里是穷举 `when`（没有 else），漏一支编译就报。
            onAction = { action ->
                dispatchSettingAction(
                    action = action,
                    nav = nav,
                    store = store,
                    context = context,
                    snapshot = snapshot,
                    onClearCache = {
                        // 隐私页上没有"清缓存"那一颗；真发过来也照做，不许静默。
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { clearCache(context) }
                            settingsNote = "清了 $freed"
                            storageText = withContext(Dispatchers.IO) { storageNumbers(context, state) }
                        }
                    },
                    onClearLocalData = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.resetAll() }
                            snapshot = store.load()
                            settingsNote = "已清掉本机偏好（照片没动）"
                        }
                    },
                    onNote = { settingsNote = it },
                )
            },
            onBack = onBack,
        )

        // ── 首启引导 ──────────────────────────────────────────────
        AppRoute.Onboarding -> OnboardingScreen(
            onFinish = {
                OnboardingGate.complete(store)
                snapshot = store.load()
                onBack()
            },
            onBack = onBack,
        )

        // ── 回收站 ────────────────────────────────────────────────
        AppRoute.Trash -> TrashScreen(
            items = trashItems,
            refreshing = trashLoading,
            onBack = onBack,
            onRestore = { item ->
                scope.launch {
                    val restored = withContext(Dispatchers.IO) { trash.restore(item) }
                    trashNote = if (restored != null) "恢复了一张" else "这张恢复失败"
                    state.refresh()
                    reloadTrash()
                }
            },
            onPurge = { item ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { trash.purge(item) }
                    trashNote = if (ok) "已永久删掉" else "这张删不掉"
                    reloadTrash()
                }
            },
            onPurgeAll = {
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        trash.list().count { trash.purge(it) }
                    }
                    trashNote = "清空了 $n 张"
                    reloadTrash()
                }
            },
            note = trashNote,
        )

        // ── 收藏夹 ────────────────────────────────────────────────
        AppRoute.Collections -> CollectionsScreen(
            collections = collectionsList,
            covers = collectionCovers,
            onBack = onBack,
            onOpen = { c -> nav.push(AppRoute.CollectionDetail(c.id, c.name)) },
            onCreate = { name ->
                scope.launch {
                    val made = withContext(Dispatchers.IO) {
                        collections.create(name, state.selectedIds.toList())
                    }
                    collectionsNote = if (made != null) "建好了「${made.name}」" else "建不了（名字或上限）"
                    collectionsList = withContext(Dispatchers.IO) { collections.list() }
                }
            },
            onRename = { id, name ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { collections.rename(id, name) }
                    collectionsNote = if (ok) "改名了" else "改不了这个名字"
                    collectionsList = withContext(Dispatchers.IO) { collections.list() }
                }
            },
            onDelete = { id ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { collections.delete(id) }
                    collectionsNote = if (ok) "删掉了（照片没动）" else "删不掉"
                    collectionsList = withContext(Dispatchers.IO) { collections.list() }
                }
            },
            selectedCount = state.selectedIds.size,
            onAddPhotos = { id ->
                scope.launch {
                    // 先在主线程把选择集拍成快照：`selectedIds` 是 Compose 状态，
                    // 在 IO 线程上迭代它可能撞上主线程正在改的那一份（并发修改）。
                    val picked = state.selectedIds.toList()
                    val n = picked.size
                    val ok = withContext(Dispatchers.IO) {
                        collections.addPhotos(id, picked)
                    }
                    // 名字要在重读之前取：夹子可能刚好被删掉，那时 `collectionsList` 更新后
                    // 就找不到它了，而这句"加进了「X」"说的正是这一批。
                    val name = collectionsList.firstOrNull { it.id == id }?.name ?: "收藏夹"
                    collectionsNote =
                        if (ok) "把 $n 张加进了「$name」" else "加不进去（这个夹可能已经没了）"
                    collectionsList = withContext(Dispatchers.IO) { collections.list() }
                    // 加成功了才清选择：失败时那批照片还在用户手里，他可以直接重挑一个夹。
                    if (ok) state.clearSelection()
                }
            },
            note = collectionsNote,
        )

        // ── 某个收藏夹的内容（复用相册内页那套画廊）────────────────
        is AppRoute.CollectionDetail -> {
            val ids = remember(route.id, collectionsList) {
                collectionsList.firstOrNull { it.id == route.id }?.photoIds.orEmpty()
            }
            val photos = remember(ids, state.photos) {
                val byId = state.photos.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
            GalleryScreen(
                photos = photos,
                title = route.name ?: "收藏夹",
                onBack = onBack,
                selectedIds = state.selectedIds,
                onToggleSelect = { state.toggleSelect(it) },
                onLongPress = { state.addToSelection(it.id) },
                onCancelSelect = { state.clearSelection() },
                favoriteIds = state.favoriteIds,
                hiddenIds = state.hiddenIds,
                hiddenPhotos = state.hiddenPhotos,
                favoritePhotos = state.favoritePhotos,
                onInvert = { items -> state.invertSelection(items) },
                onSelectPhotos = { items -> state.selectAllOrClear(items) },
                onNote = { state.batchNote = it },
                problem = state.libraryProblem,
                emptyHint = "这个收藏夹还是空的。在相册里选中照片后点「收藏夹」。",
                onOpen = { photo ->
                    onOpenPhoto(photos, photos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0))
                },
            )
        }

        // ── 搜索 ──────────────────────────────────────────────────
        //
        // 这里**必须是 `is AppRoute.Search`，不能写 `AppRoute.Search`**。
        // 后者在 Kotlin 里指的是 `Search` 的**伴生对象**（`Search.Companion`），
        // 不是"任意一个 Search 实例"：`Search(null) == Search.Companion` 恒为 false，
        // 于是这一支永远进不去，落到 `else -> return false`，宿主退回下层。
        // 用户看到的现象就是「点搜索只关抽屉、停在时间线」——
        // 而抽屉里其它 10 颗都能进，因为它们是 `data object`（那一支确实比的是单例）。
        //
        // 这个 bug 编译期完全绿、单测也绿：`AppRouteTest` 测的是路由此刻的编码/解码，
        // 不测"这个分支会不会被命中"。只有装机点一下才看得见。见 `docs/arch/APP-ARCH.md`。
        is AppRoute.Search -> SearchScreen(
            history = searchHistoryList,
            suggestions = searchSuggestions,
            results = searchResults,
            indexing = indexing,
            indexProgress = indexProgress,
            query = searchQuery,
            onQuery = { searchQuery = it },
            onPickSuggestion = { q ->
                searchQuery = q
                scope.launch { searchHistoryList = withContext(Dispatchers.IO) { searchHistory.record(q) } }
            },
            onClearHistory = {
                scope.launch { searchHistoryList = withContext(Dispatchers.IO) { searchHistory.clear() } }
            },
            onBack = onBack,
            onOpenPhoto = { photoId ->
                val idx = state.photos.indexOfFirst { it.id == photoId }
                if (idx >= 0) onOpenPhoto(state.photos, idx)
                else searchNote = "这张可能已经删了"
            },
            onSelect = { ids -> state.selectedIds = ids },
            note = searchNote ?: state.batchNote,
        )

        // ── 回忆清单 ──────────────────────────────────────────────
        AppRoute.Memories -> MemoriesScreen(
            memories = memories,
            photos = state.photos,
            onOpen = { m -> nav.push(AppRoute.MemoryDetail(m.id)) },
            onBack = onBack,
            note = state.batchNote,
        )

        // ── 某条回忆的详情 ────────────────────────────────────────
        is AppRoute.MemoryDetail -> {
            val memory = memories.firstOrNull { it.id == route.id }
            if (memory == null) {
                // 回忆是由当前照片算出来的：照片删了一批之后它可能就不成立了。
                // 这一帧不画空白页，直接退回清单（用户看得见"少了一条回忆"，而不是一屏黑）。
                LaunchedEffect(route.id) { onBack() }
            } else {
                val photos = remember(memory, state.photos) {
                    val byId = state.photos.associateBy { it.id }
                    memory.photoIds.mapNotNull { byId[it] }
                }
                MemoryDetailScreen(
                    memory = memory,
                    photos = photos,
                    onBack = onBack,
                    // 「所有时刻」= 回到回忆清单（它就在栈里，退到那一层而不是再压一层）
                    onAllMoments = { nav.popTo("memories") },
                    startIndex = 0,
                )
            }
        }

        // ── 拼图 ──────────────────────────────────────────────────
        is AppRoute.Collage -> {
            val picked = remember(route.photoIds, route.count, state.photos) {
                val byId = state.photos.associateBy { it.id }
                val explicit = route.photoIds.mapNotNull { byId[it] }
                // 超出 24 个时只存了 count（见 AppRoute.Collage 的注释）：按"当前可见集合"
                // 重新取前 count 张 —— 与"选中 N 张"在拼图这一屏等价。
                if (explicit.isEmpty() && route.count > 0) {
                    state.photos.take(route.count)
                } else {
                    explicit
                }
            }
            if (picked.isEmpty()) {
                LaunchedEffect(Unit) { onBack() }
            } else {
                CollageScreen(
                    photos = picked,
                    onBack = onBack,
                    onExport = { bitmap ->
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                state.saveCollageBitmap(bitmap)
                            }
                            state.batchNote = if (saved != null) "拼图存好了" else "拼图没存上"
                            if (saved != null) state.refresh(force = true)
                        }
                    },
                    note = state.batchNote,
                )
            }
        }

        // ── 幻灯片 ────────────────────────────────────────────────
        is AppRoute.Slideshow -> {
            // 照片列表不进路由：用"当前可见集合"。空列表直接退回 —— 画一屏空幻灯片没意义。
            val photos = state.photos
            if (photos.isEmpty()) {
                LaunchedEffect(Unit) { onBack() }
            } else {
                SlideshowScreen(
                    photos = photos,
                    startIndex = route.startIndex.coerceAtMost(photos.lastIndex),
                    onExit = onBack,
                )
            }
        }

        // ── 重复照片 ──────────────────────────────────────────────
        AppRoute.Duplicates -> DuplicatesSection(
            state = state,
            onBack = onBack,
        )

        // ── 存储分析 ──────────────────────────────────────────────
        AppRoute.StorageInsight -> StorageInsightSection(onBack = onBack)

        // ── 视频列表 ──────────────────────────────────────────────
        AppRoute.Videos -> VideosSection(
            onBack = onBack,
            onOpen = { item -> nav.push(AppRoute.VideoPlayer(item.id)) },
        )

        // ── 某一段视频 ────────────────────────────────────────────
        is AppRoute.VideoPlayer -> VideoPlayerSection(
            id = route.id,
            onBack = onBack,
            onListNeeded = { onBack() },
            // 存帧会往系统相册里写一张照片：重读一次库，新那张才会出现在时间线上。
            onFrameSaved = { state.refresh(force = true) },
        )

        // ── 配方卡（入口在 `YishunScreen` 的「更多」抽屉「导出」段）──────
        is AppRoute.RecipeCard -> {
            val look = com.suipai.core.engine.LookLibrary.byId(route.lookId)
            if (look == null) {
                // 配方库里没有这个 id（配方被删/改名）：退回下层，不画一张"未知配方"的卡。
                LaunchedEffect(route.lookId) { onBack() }
            } else {
                RecipeCardScreen(
                    look = look,
                    intensity = route.intensity,
                    onBack = onBack,
                    onSave = { bitmap, name ->
                        withContext(Dispatchers.IO) {
                            state.repository().saveBitmap(bitmap, name, png = true)
                        }
                    },
                )
            }
        }

            // 其余路由不是二级页面（tab / 查看器 / 一瞬 / 相机 / 相册内页）
            else -> return false
        }
    }
    return true
}

/**
 * 重复照片：扫描 → 按组删。
 *
 * 为什么删除要一个**本地**的 `ActivityResultLauncher`，而不是复用 `MainActivity` 的那个：
 *  - `MainActivity` 的 launcher 属于"删除授权链"（`DeleteFlow` 的票据 + `consentNote` 的结论），
 *    它回来说的是"删了几张 / 没允许"，语义与这里**不同**（这里要知道"这一组删掉了没有"）；
 *  - 更要紧的是「7 职责」那条约束：再加第 3 个 launcher 会让 `MainActivity` 重新变成一个
 *    什么都有的地方。launcher 只在 `AppNavHost` 里被这一个屏幕用，就放在这里。
 *
 * `Duplicates.deleteRequestFor` 返回的是 `IntentSender`（不是 `IntentsBuilder`），
 * 这里包成 `IntentSenderRequest` 交给系统，回来**只看 `resultCode > 0`**：
 * Android 11+ 对外来行（相机/微信/截图）会**静默返回 0**，把 0 当成功就等于
 * 谎报一次删除（正是 `PerfSourceGuardTest.attemptCountProgress` 抓的那类）。
 */
@Composable
private fun DuplicatesSection(
    state: AppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duplicates = remember(context) { Duplicates(context) }
    var groups by remember { mutableStateOf<List<com.suipai.app.data.DuplicateGroup>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // 待删的组（点「删除这组」之后弹系统框；回来后按 resultCode 说话）。
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingWhat by remember { mutableStateOf<String?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // **判据必须是 > 0**：0 与 RESULT_CANCELED 都表示"没删成"。
        // Android 11+ 对外来行静默返回 0，这里要是把 0 当成功，
        // 用户会看到"删了 3 组"而库里一张没少。
        val what = pendingWhat
        if (result.resultCode > 0) {
            note = if (what != null) "$what：删掉了" else "删掉了"
            // 删完重扫：不清空列表就还画着已经不存在的组。
            scanning = true
            scope.launch {
                groups = withContext(Dispatchers.IO) { duplicates.scan() }
                scanning = false
                // 刚删过行：`generation()` 在部分提供方上滞后，force 一次比等它可靠。
                state.refresh(force = true)
            }
        } else {
            note = "取消了，没删"
        }
        pendingUris = emptyList()
        pendingWhat = null
    }

    fun requestDelete(uris: List<Uri>, what: String) {
        if (uris.isEmpty()) {
            note = "这一组只有一张，没得删"
            return
        }
        val sender = deleteRequestFor(context, uris)
        if (sender == null) {
            // API < 30 或打包失败：说清"这台机器没有这个入口"，不许静默。
            note = "这台机器不支持一次删多张"
            return
        }
        pendingUris = uris
        pendingWhat = what
        deleteLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
        )
    }

    LaunchedEffect(Unit) {
        scanning = true
        groups = withContext(Dispatchers.IO) {
            duplicates.scan(
                onProgress = { done, total -> progress = if (total > 0) done to total else null },
                cancel = { false },
            )
        }
        progress = null
        scanning = false
    }

    DuplicatesScreen(
        groups = groups,
        scanning = scanning,
        progress = progress,
        onBack = onBack,
        onDeleteGroup = { group ->
            requestDelete(
                duplicates.deletionUrisOf(group),
                "这组",
            )
        },
        onDeleteAll = {
            // 所有组里"除保留那张"的 uri 合成一次请求：一次系统框删完，
            // 而不是逐组弹 N 次（N 次确认对用户是灾难）。
            val all = groups.flatMap { duplicates.deletionUrisOf(it) }
            requestDelete(all, "全部重复")
        },
        note = note ?: state.batchNote,
    )
}

// ══════════════════════════════════════════════════════════════════════
// 视频（列表 / 播放器）
// ══════════════════════════════════════════════════════════════════════
//
// 这两屏与其它二级页同一条分工：屏幕收数据 + 回调，界面不 import `state`、不导航。
// 接线在这里做三件事：谁去查库、点开去哪、结论落到哪一句上。
//
// **权限这一格必须单独说**：Android 13 起 `READ_MEDIA_IMAGES` 不覆盖视频，
// 而本版的 `AndroidManifest.xml` 只有前者 —— 所以 33+ 上这段会稳定地读到 0 段，
// 且**一条 SecurityException 都不会抛**（`MediaStore.Video` 只是不返回别人的行）。
// 把那种空列表说成"你没有视频"就是一句假话，所以列表页拿 `VideoAccess` 单独说清。

/**
 * 视频列表。
 *
 * 只读第一页（`VideoRepository.PAGE` = 400 段）：相册里的视频通常几十段，
 * 而分页再往下滚是"视频 tab"才需要的功能 —— 这一屏是「更多 › 看照片」里的一个入口，
 * 不是一级页。真到了 400 段以上，页眉那句「N 段」会如实说 400（`videos.size`），
 * 用户看得见"这里有个上限"，而不是一个假装完整的列表。
 *
 * `null` 与空表的区别贯穿整条链：`loadPage` 回 `null` = **没读到**（游标拿不到 / 读行抛了），
 * 空表 = 读到了、库里确实没有。两者的界面话不一样（"没读到，稍后再试" vs "还没有视频"）。
 */
@Composable
private fun VideosSection(
    onBack: () -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember(context) { VideoRepository(context) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var access by remember { mutableStateOf(VideoAccess.GRANTED) }
    var note by remember { mutableStateOf<String?>(null) }
    // 每点一次「重试」+1：读失败之后总得有一条路重来，而这里没有下拉刷新。
    var epoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(epoch) {
        loading = true
        // 先问授权再查库：顺序反了的话"没授权"会先被读成"库里没有"，
        // 而那句话一旦写进 note，用户就会以为自己的视频没了。
        access = repo.readAccess()
        val page = repo.loadPage(offset = 0)
        videos = page.orEmpty()
        note = when {
            page == null -> "没读到视频（系统没答话），稍后再试"
            page.isEmpty() -> null       // 空列表交给 `VideoGrid` 自己的空态说
            // 列表不完整时补一句：13+ 上"只看到自己写进去的那几段"正是这一格。
            access != VideoAccess.GRANTED -> videoAccessHint(access)
            else -> null
        }
        loading = false
    }

    VideoGrid(
        videos = videos,
        loading = loading,
        onOpen = onOpen,
        onBack = onBack,
        note = note,
        access = access,
    )
}

/**
 * 某一段视频的播放器。
 *
 * 数据从哪来：路由里只有 `id`（不带 `Uri`，理由见 `AppRoute.VideoPlayer`），
 * 所以这里按需再读一次列表去把它找回来。**找不到就退回列表并说一句** ——
 * 删过的、换机后重排过的 id 都会走到这一格，静默退回等于"点了一下就回去了"。
 *
 * 为什么按 id 而不是按位置：`loadPage(0)` 的顺序是拍摄时间倒序，新增一段视频就会把
 * 后面所有位置挪一位；位置是"那一刻的视图"，id 才是那一段的身份。
 */
@Composable
private fun VideoPlayerSection(
    id: Long,
    onBack: () -> Unit,
    onListNeeded: () -> Unit,
    onFrameSaved: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember(context) { VideoRepository(context) }
    var video by remember(id) { mutableStateOf<VideoItem?>(null) }
    var resolved by remember(id) { mutableStateOf(false) }
    var note by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        video = repo.loadPage(offset = 0).orEmpty().firstOrNull { it.id == id }
        resolved = true
        if (video == null) {
            // 说一句话再退：这一条链路里"静默"是最糟的形态（用户只看到屏幕闪了一下）。
            note = "这一段找不到了（可能已经被删掉或者换了位置）"
        }
    }

    val current = video
    if (current != null) {
        VideoPlayerScreen(
            video = current,
            onBack = onBack,
            // 存帧成功：照片进了系统相册，所以照片列表要重读一次，否则新存的那张
            // 在时间线里要等到下一次 ON_RESUME 才出现。
            onSavedFrame = { said -> note = said; onFrameSaved() },
            note = note,
        )
    } else if (resolved) {
        // 没找到：画一句 + 一个返回。为什么用 `onListNeeded`（= onBack）而不是自动退：
        // 自动退会让用户看不见那句解释，只看得到"点了一下又回来了"。
        // 这里的返回是**一层**（列表就在下面），与页首「返回」同一条。
        VideoMissingScreen(note = note ?: "这一段找不到了", onBack = onListNeeded)
    }
}

/**
 * 「这一段找不到了」那一屏。
 *
 * 为什么不是复用 `EmptyState`：那个组件是给"列表空着"用的，而这里是**单条**没了，
 * 语义与"你的库里没有视频"完全不同 —— 借用会让用户以为整库都空了。
 * 页首仍然给「返回」，返回键也由宿主统一处理（`hasLayer` 那条兜底）。
 */
@Composable
private fun VideoMissingScreen(note: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText("视频", style = SuiPaiSheet.type.pageHead, modifier = Modifier.padding(start = 8.dp))
        }
        Box(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 存储分析：扫一次，四个统计块。`onOpenCategory` 不跳转（见下面的注释）。 */
@Composable
private fun StorageInsightSection(onBack: () -> Unit) {
    val context = LocalContext.current
    val insighter = remember(context) { StorageInsight(context) }
    var summary by remember { mutableStateOf(StorageSummary.EMPTY) }
    var scanning by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scanning = true
        summary = withContext(Dispatchers.IO) { insighter.scan() }
        scanning = false
        // 扫到 0 张 != "你的相册是空的"：可能是读不到（权限/提供方抖动）。
        // 与全项目那条 `null = 没读到` 的口径一致 —— 说"没扫到"，不说"没有照片"。
        if (summary.count == 0) note = "没扫到照片（可能是权限或系统没答话）"
    }

    StorageInsightScreen(
        summary = summary,
        scanning = scanning,
        onBack = onBack,
        // **不做跳转**：我们这一轮不新增"按档位筛选"的入口，跳到一个没有筛选状态的地方
        // 等于把用户带进一个看不出区别的页面。改成在 note 行把数量说出来 ——
        // 这是"点了有反应、而且反应是真话"的最小实现。
        onOpenCategory = { kind ->
            val n = summary.byKind.firstOrNull { it.first == kind }?.second ?: 0
            note = "${kind.label}：${n} 张"
        },
        note = note,
    )
}

// ══════════════════════════════════════════════════════════════════════
// 二级页面的运行时数据源（全部是普通函数，不是 Composable）
// ══════════════════════════════════════════════════════════════════════
//
// 为什么单独一组：这些都要 binder / 文件遍历 / 格式化，**必须**在 IO 上跑。
// 把它们放在 Composable 里会被 `remember` 与重组时序绑在一起，很难讲清"什么时候读了一次"。
// 这里全部是 `suspend`/纯函数，由 `LaunchedEffect` 调，读几次、什么时候读，代码里看得见。

/** `ReadOnlySource` → 运行时真值。设置页与隐私页共用这一份。 */
private suspend fun readOnlyValues(
    context: Context,
    trash: Trash,
    state: AppState,
): Map<ReadOnlySource, String> = withContext(Dispatchers.IO) {
    val pkg = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val version = pkg?.versionName ?: "?"
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
        runCatching { pkg?.longVersionCode }.getOrNull() ?: 0L
    } else {
        0L
    }
    val (usedBytes, cacheBytes) = storageBytes(context)
    val (trashCount, trashBytes) = trash.stats()
    mapOf(
        ReadOnlySource.APP_VERSION to "$version（$code）",
        ReadOnlySource.DEVICE_LINE to
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）",
        ReadOnlySource.SAVE_LOCATION to Trash.HOME_RELATIVE_PATH,
        ReadOnlySource.STORAGE_USED to humanBytes(usedBytes),
        ReadOnlySource.CACHE_SIZE to humanBytes(cacheBytes),
        ReadOnlySource.LIBRARY_COUNT to "${state.photos.size} 张（已加载）",
        ReadOnlySource.AI_STATUS to if (state.photos.isEmpty()) "还没有照片" else "端侧可用",
        ReadOnlySource.BACKUP_SCOPE to "偏好（收藏/隐藏/设置）随系统备份；照片不备份",
        ReadOnlySource.ONBOARDING_STATUS to "见「关于」",
        // 回收站那一行（TRASH-COLLECTIONS.md §接入建议 第 2 条）也走只读值这一格，
        // 因为它和"占用/张数"是同一类东西：运行时才知道、不可编辑。
        // 键用 STORAGE_USED 会撞上面那条，所以单独拼在设置页的说明里（见 settingsTrashLine）。
    ).also {
        // 只为让编译器与读者都看见 trash 参与了：回收站数字拼在下一句里。
        @Suppress("UNUSED_EXPRESSION")
        (trashCount to trashBytes)
    }
}

/** 设置页「回收站（N 张 · X MB）」那一行要用的数字（`settingsTrashLine` 读它）。 */
internal fun settingsTrashLine(count: Int, bytes: Long): String =
    "回收站 $count 张 · ${humanBytes(bytes)}"

/**
 * 导出的默认文件名：`随拍备份 2026-10-18.json`。
 *
 * 三个理由都来自真实使用：
 * 1. **带日期**：SAF 里一排同名文件只有日期能分辨哪份是新的（`backupStamp` 在屏幕上显示的
 *    也是同一个日子，两处对得上才认得出是同一次导出）；
 * 2. **中文前缀**：用户在自己的下载目录里一眼认得出来 —— `backup.json` 那种名字
 *    混在一堆下载文件里等于没有名字；
 * 3. **不带冒号 / 斜杠**：文件名的非法字符会让部分 provider 直接改名或截断，
 *    那样用户拿到的名字与这里算的就不是同一个。
 *
 * 手拼而不引 `DateTimeFormatter`：`PerfSourceGuardTest.formattersAreDeclaredOnce` 钉着
 * `ofPattern` 只许出现在声明行上（同 `backupStamp` 的理由，那边也是手拼的）。
 * 纯函数，所以能在 JVM 上直接钉住"跨月/跨日补零"。
 */
internal fun backupSuggestedName(atMillis: Long): String {
    val t = java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault())
    fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
    return "随拍备份 ${t.year}-${two(t.monthValue)}-${two(t.dayOfMonth)}.json"
}

/** 存储页三个数字：本机占用 / 缓存 / 相册。 */
private suspend fun storageNumbers(
    context: Context,
    state: AppState,
): Triple<String, String, String> = withContext(Dispatchers.IO) {
    val (used, cache) = storageBytes(context)
    Triple(
        humanBytes(used),
        humanBytes(cache),
        "${state.photos.size} 张（已加载）",
    )
}

/** (files 总字节, cache 总字节)。只数一层目录，递归会在大库上卡很久。 */
private fun storageBytes(context: Context): Pair<Long, Long> {
    fun sizeOf(dir: java.io.File?): Long = dir?.listFiles()?.sumOf { f ->
        if (f.isFile) f.length() else 0L
    } ?: 0L
    return sizeOf(context.filesDir) to sizeOf(context.cacheDir)
}

/** 人话字节数（设置页与回收站共用；与 `GalleryScreen` 里那套口径一致）。 */
internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576L -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024L -> "${"%.0f".format(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}

/**
 * 清缓存（**不动照片**）。
 *
 * 只删 `cacheDir` 下的文件：那是本应用的临时目录（缩略图、对比图、系统相机落点），
 * 删了最多是下次重新生成。`filesDir` 里是设置/收藏夹/回收站账单，**不许**碰。
 */
private fun clearCache(context: Context): String {
    val before = storageBytes(context).second
    val freed = runCatching {
        context.cacheDir?.listFiles()?.sumOf { f ->
            val n = if (f.isFile) f.length() else 0L
            f.deleteRecursively()
            n
        } ?: 0L
    }.getOrElse { 0L }
    val kept = if (before > 0 && freed < before) "（有文件正在用，留着没删）" else ""
    return humanBytes(freed) + kept
}

/**
 * 设置项改动 → 落盘。
 *
 * `onChange` 收到的是 `SettingChange` 值对象（纯 Kotlin），这里翻译成 `SettingsStore.put`。
 *
 * `Action` 这一支**不该走到这里**：它在 [dispatchSettingAction] 就被拦下了（不落盘）。
 * 保留这一支而不是删掉，是因为它是"漏了分派"时的唯一安全落点 —— 但那样等于
 * 又变回"点了没反应"，所以下面那条守卫测试盯的就是"每个 `SettingAction` 都真有去处"。
 */
private fun applySettingChange(
    context: Context,
    store: SettingsStore,
    change: SettingChange,
    onSnapshotChanged: (com.suipai.app.settings.SettingsSnapshot) -> Unit,
) {
    when (change) {
        is SettingChange.Toggle -> store.put(change.pref, change.value)
        is SettingChange.Number -> store.put(change.pref, change.value)
        is SettingChange.Choice -> store.putChoiceName(change.pref, change.optionName)
        is SettingChange.Action -> Unit  // 已在 dispatchSettingAction 里执行过
    }
    onSnapshotChanged(store.load())
}

/**
 * 一条 `SettingAction` 到底做什么。**这个 `when` 是穷举的，没有 `else`** ——
 * 加一个新的 `SettingAction` 成员时编译器当场报错，逼着人在这里给它一个去处。
 *
 * 为什么需要这么一个函数：改前 `applySettingChange` 里是
 * `is SettingChange.Action -> Unit`（注释写"动作由屏幕的 onAction 走"），
 * 而 `SettingsScreen` 根本没有 `onAction` 参数。于是设置中心那七颗动作行
 * （外观 / 存储管理 / 隐私与数据 / 相册权限 / 关于随拍 / 重看引导 / 清除缓存）
 * 全部**点了毫无反应** —— 不崩、不报错、也不改任何值。
 * 这类缺陷编译器不管、`SettingsModelTest` 也不管（它只查"这条设置有没有界面入口"），
 * 只有当代码形状本身要求"每个动作都必须有一个去处"时才挡得住。
 *
 * @param onClearCache 清缓存（异步、要重量数字），由调用点给出
 * @param onClearLocalData 清本机偏好（suspend 落盘），由调用点给出
 * @param onNote 把结果说成一句话；界面上就是那一行 `hint`
 */
private fun dispatchSettingAction(
    action: SettingAction,
    nav: NavState,
    store: SettingsStore,
    context: Context,
    snapshot: com.suipai.app.settings.SettingsSnapshot,
    onClearCache: () -> Unit,
    onClearLocalData: () -> Unit,
    onNote: (String?) -> Unit,
) {
    when (action) {
        // ── 进子屏 ────────────────────────────────────────────────
        SettingAction.OPEN_APPEARANCE -> nav.push(AppRoute.SettingsAppearance)
        SettingAction.OPEN_STORAGE -> nav.push(AppRoute.SettingsStorage)
        SettingAction.OPEN_PRIVACY -> nav.push(AppRoute.Privacy)
        SettingAction.OPEN_ABOUT -> nav.push(AppRoute.SettingsAbout)

        // 重看引导：把内容版本退回 0，再压一屏进去。`complete` 在引导页自己的
        // `onFinish` 里写，这里只负责"想再看一遍"这件事。
        SettingAction.REPLAY_ONBOARDING -> {
            OnboardingGate.replay(store)
            nav.push(AppRoute.Onboarding)
        }

        // ── 就地执行 ──────────────────────────────────────────────
        SettingAction.CLEAR_CACHE -> onClearCache()

        // 复制「本机数据说明」。与隐私页那一颗是同一份正文、同一个出口
        // （`copyToClipboard`），所以两处说的话一样。
        SettingAction.EXPORT_LOCAL_DATA ->
            onNote(copyToClipboard(context, localDataText(snapshot)))

        SettingAction.CLEAR_LOCAL_DATA -> onClearLocalData()

        // 跳系统页：失败时 `Launch.kt` 会把原因放进 tell，成功时它返回 null ——
        // 那时系统页已经盖在我们上面，屏幕上不需要再多一句话。
        SettingAction.OPEN_SYSTEM_APP_SETTINGS -> onNote(openAppSettingsTell(context))
        SettingAction.OPEN_PHOTO_PERMISSION -> onNote(openPhotoAccessTell(context))
    }
}

/** 「打开系统应用设置」并把结果说成一句话（`Launch.kt` 的出口，失败会响）。 */
private fun openAppSettingsTell(context: Context): String? {
    var said: String? = null
    com.suipai.app.ui.common.openAppSettings(context) { said = it }
    return said
}

/** 「打开本应用的照片授权页」同上。 */
private fun openPhotoAccessTell(context: Context): String? {
    var said: String? = null
    com.suipai.app.ui.common.openPhotoAccessSettings(context) { said = it }
    return said
}

/** 隐私页「导出本机数据」的正文：偏好全表 + 一句说明。 */
private fun localDataText(snapshot: com.suipai.app.settings.SettingsSnapshot): String = buildString {
    appendLine("{")
    appendLine("  \"说明\": \"这些是随拍保存在本机的偏好，不含照片。照片始终在本机相册里。\",")
    appendLine("  \"偏好\": {")
    val rows = snapshot.forExport().toList()
    rows.forEachIndexed { i, (k, v) ->
        append("    ").append('"').append(k).append("\": ")
        append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        if (i != rows.lastIndex) append(',')
        appendLine()
    }
    appendLine("  }")
    appendLine("}")
}

/** 复制到剪贴板（走 `Clip.kt` 唯一出口）。返回一句结果。 */
private fun copyToClipboard(context: Context, text: String): String {
    val clip = android.content.ClipData.newPlainText("随拍 · 本机数据说明", text)
    return if (com.suipai.app.ui.common.clipWrite(context, "本机数据", clip)) {
        "已复制到剪贴板"
    } else {
        "这台机器上没有剪贴板"
    }
}

/** `HitReason` → 给用户看的那句话（搜索结果格上的角标）。 */
private fun hitReasonLabel(reason: com.suipai.app.data.HitReason): String = when (reason) {
    com.suipai.app.data.HitReason.FILE_NAME -> "文件名"
    com.suipai.app.data.HitReason.ALBUM -> "相册"
    com.suipai.app.data.HitReason.DATE -> "日期"
    com.suipai.app.data.HitReason.CAMERA -> "相机"
    com.suipai.app.data.HitReason.LENS -> "镜头"
    com.suipai.app.data.HitReason.TYPE -> "类型"
}

/**
 * 把 `SearchIndex.match()` 的结果按"为什么命中"分组。
 *
 * 分组键用 `HitReason`（文件名 / 相册 / 日期 / 相机 / 镜头 / 类型），这正是
 * `SearchScreen` 要的 `SearchGroupKind` + 一句人话标题。取不到 uri 的条目**丢掉**：
 * 索引可能比媒体库旧（`refreshIncremental` 有意不处理删除，见 SEARCH.md §8），
 * 画一颗点不开的格子比不画更糟。
 */
private fun groupSearchMatches(
    context: Context,
    matches: List<com.suipai.app.data.SearchMatch>,
): List<SearchResultGroup> {
    if (matches.isEmpty()) return emptyList()
    val out = LinkedHashMap<SearchGroupKind, MutableList<SearchPhoto>>()
    for (m in matches) {
        val uri = runCatching {
            android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                m.photo.photoId,
            )
        }.getOrNull() ?: continue
        val kind = when (m.reason) {
            com.suipai.app.data.HitReason.ALBUM -> SearchGroupKind.ALBUM
            com.suipai.app.data.HitReason.CAMERA,
            com.suipai.app.data.HitReason.LENS,
            -> SearchGroupKind.CAMERA
            com.suipai.app.data.HitReason.DATE -> SearchGroupKind.YEAR
            else -> SearchGroupKind.PHOTO
        }
        out.getOrPut(kind) { ArrayList() } += SearchPhoto(
            photoId = m.photo.photoId,
            uri = uri,
            reason = hitReasonLabel(m.reason),
        )
    }
    return out.map { (kind, photos) ->
        SearchResultGroup(
            title = when (kind) {
                SearchGroupKind.PHOTO -> "文件与类型"
                SearchGroupKind.ALBUM -> "相册"
                SearchGroupKind.CAMERA -> "相机与镜头"
                SearchGroupKind.YEAR -> "日期"
            },
            kind = kind,
            photos = photos,
        )
    }
}
