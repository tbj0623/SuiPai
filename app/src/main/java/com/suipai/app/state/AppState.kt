package com.suipai.app.state

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suipai.app.data.AlbumItem
import com.suipai.app.data.Favorites
import com.suipai.app.data.Hidden
import com.suipai.app.data.MediaRepository
import com.suipai.app.data.PhotoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用层状态：**改前 MainActivity 里那 40 个 `remember` 的真身**。
 *
 * 为什么搬出来（不是搬进 ViewModel）：这个类的创建、保存、订阅全都只发生在 Composition
 * 内部（`PermissionGate` 里面，因为 `MediaRepository` 需要权限之后的 Context），
 * 而它的生命周期必须与 Activity 的 `onSaveInstanceState` 对齐 ——
 * 用 `remember` + `rememberSaveable` 组合就能做到，而 ViewModel 会让
 * "重建后还要重读一遍相册"这件事变成两套真相。
 * 分工写进 `docs/arch/APP-ARCH.md` 的状态所有权表。
 *
 * 职责边界：
 * - **本类**：数据状态（照片/相册/收藏/隐藏/查看器列表/选择/batchNote/libraryProblem…）
 *   与"读"（[refresh] / [loadMore]）。
 * - **[BatchActions]**：写（删除/收藏/隐藏/复制/一瞬批量）。
 * - **[DeleteFlow]**：删除授权链。
 * - **[ShareImport]**：外部分享导入。
 * - **[NavState]**：导航（它自己可保存）。
 *
 * 所有 `var` 都是 Compose 可观察状态；构造函数里不启动任何协程（创建发生在首帧的重组里）。
 */
class AppState(
    private val context: Context,
    private val repo: MediaRepository,
    private val favorites: Favorites,
    private val hidden: Hidden,
    private val scope: CoroutineScope,
    /** 导航。批量/删除收尾时要按"当时在看哪一屏"决定话说到哪里，所以这里要能读到。 */
    val nav: NavState,
    /**
     * 选中集。**必须是 `rememberSaveable` 出来的那一个**（见 `MainActivity`）——
     * 它本身是好几个 MB 的 `AppState` 里唯一能进 Bundle 的一格，重建后靠它把选择带回来。
     * 不传的话默认建一个空实例，只影响"重建后选择还在不在"，不影响任何读写路径。
     */
    private val selection: SelectionState = SelectionState(),
) {

    // ── 相册数据 ──────────────────────────────────────────────────────

    /** 时间线（第一页起）。 */
    var photos by mutableStateOf<List<PhotoItem>>(emptyList())
        private set

    /** 媒体库里的相册清单（不含下面那两个虚拟册）。 */
    var remoteAlbums by mutableStateOf<List<AlbumItem>>(emptyList())
        private set

    /** 相册内页的照片；`null` = 没开内页。 */
    var albumPhotos by mutableStateOf<List<PhotoItem>?>(null)
        private set

    /**
     * 内页对应的册子 id / 标题。**导航层的相册内页路由与这两个值必须同步**，
     * 同步点只有 [openAlbum] 与 [closeAlbum] 两处；[AppNavHost] 用路由当唯一真值，
     * 这两个值是为了沿用 `GalleryScreen(title = …)` 的签名。
     */
    var albumId by mutableStateOf<String?>(null)
        private set
    var albumTitle by mutableStateOf<String?>(null)
        private set

    /** 查看器要显示的那一串（已经按隐藏规则过滤过）。`null` = 没开查看器。 */
    var viewerPhotos by mutableStateOf<List<PhotoItem>?>(null)
        private set

    /** 查看器当前页（**唯一可变的那一格**，返回栈里不记它）。 */
    var viewerIndex by mutableIntStateOf(0)

    /** 收藏 / 隐藏两族的缓存（`loadByIds` 的结果，供画廊与查看器判断角标）。 */
    var favoritePhotos by mutableStateOf<List<PhotoItem>>(emptyList())
        private set
    var hiddenPhotos by mutableStateOf<List<PhotoItem>>(emptyList())
        private set

    /** 收藏 / 隐藏 id 集合的快照。`*Tick` 变一次就重读一次（旧代码的 remember 键）。 */
    var favoriteIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var hiddenIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    private var favTick by mutableIntStateOf(0)
    private var hideTick by mutableIntStateOf(0)

    /** 收藏 / 隐藏两个虚拟册 + 媒体库相册，合并后的清单（相册页看到的那个）。 */
    var albums by mutableStateOf<List<AlbumItem>>(emptyList())
        private set

    /** 还有没有下一页。 */
    var hasMore by mutableStateOf(true)
        private set

    /** 正在翻下一页。 */
    var loadingMore by mutableStateOf(false)
        private set

    /** 正在刷新（首屏转圈与下拉都看它）。 */
    var refreshing by mutableStateOf(false)
        private set

    // ── 选择与批量 ────────────────────────────────────────────────────

    /**
     * 选中的 id 集合。
     *
     * **转发属性**，真身在 [selection]（`SelectionState`）：本类整体是普通 `remember`，
     * 进不了 Bundle；`SelectionState` 是 `rememberSaveable` 出来的，重建后选择还在。
     * 转发之后 8 个写入点与所有只读点（`state.selectedIds`）一个字都不用改。
     *
     * 复杂度与改前完全一样（每次写的都是一个新的不可变 `Set`），没有新增观察层。
     */
    var selectedIds: Set<Long>
        get() = selection.ids
        internal set(value) {
            selection.ids = value
        }

    /** 批量任务状态（进度位与结论位都由它派生）。 */
    var batchPhase by mutableStateOf<BatchMachine.Phase>(BatchMachine.Phase.Idle)
        internal set
    /**
     * 那句结论（"删了 1 张 / 没允许 / 存了 X 张，Y 张没存上"）。
     *
     * 它必须落在**永远在**的那一格上：页眉的 FRAMES 计数位（`gallery_note`），
     * 以及查看器自己的计数位（`statusNote`）—— 选择条在弹授权框之前就随选中清空了，
     * 只往那一格写等于什么都没说（装机实测过：整棵树里找不到这句话）。
     */
    var batchNote by mutableStateOf<String?>(null)

    /**
     * "这一屏的数据是没读到的，不是空的"。撤权限 / 提供方抖动时，上一次的结果还在屏幕上，
     * 没有这句话，用户看到的就是"照片变少了 / 没了"。页眉那格只挂 4.5 秒，
     * 所以空态那一格也要能说出它。
     */
    var libraryProblem by mutableStateOf<String?>(null)
        private set

    /** 授权框的票据（见 [ConsentTicket] 的注释：三个值必须一起保存）。 */
    internal var consent by mutableStateOf(ConsentTicket.EMPTY)

    /** 一批删除/一瞬之后要跳去的 uri（一瞬保存完成后回到查看器看这张）。 */
    var jumpUri by mutableStateOf<Uri?>(null)

    init {
        favoriteIds = favorites.ids()
        hiddenIds = hidden.ids()
        rebuildAlbums()
    }

    // ── 派生值 ────────────────────────────────────────────────────────

    /** 进度位那句话；`null` = 不显示。 */
    val batchHint: String? get() = BatchMachine.hint(batchPhase)

    /** 有没有批量任务在跑（返回键第一优先级、封快门）。 */
    val batchRunning: Boolean get() = BatchMachine.running(batchPhase)

    /** 在看的这一批（当前屏的完整列表，批量动作都从它里按选中集过滤）。 */
    fun visiblePhotos(): List<PhotoItem> = albumPhotos ?: photos

    // ── 读 ────────────────────────────────────────────────────────────

    /**
     * 上一次**成功读完整库**之后看到的媒体库 generation（见 [RefreshGate]）。
     * `null` = 还没成功读过。只有读成功才推进 —— 否则一次失败会被缓存成"库没变"。
     */
    private var lastGen: String? = null

    /** 上一次成功读完整库的时刻（`SystemClock.elapsedRealtime` 的口径由调用方给，这里只存毫秒）。 */
    private var lastFullReadAt = 0L

    /**
     * 上一趟读收藏 / 隐藏那两份**失败了**。
     *
     * 有它才敢跳过：这两份的"要不要读"只看 id 集合，而 id 集合在读失败时不会变 ——
     * 于是"读失败"会被缓存成"不用读"，一次提供方抖动就能让角标停在旧数据上整整一个 TTL。
     * 与 `lastGen` 同一条原则：**失败不许写缓存**。
     */
    private var favReadFailed = false
    private var hiddenReadFailed = false

    /**
     * 库**刚被我们自己这一侧改过**（删掉/搬走了照片），下一趟 `refresh` 必须真读。
     *
     * 为什么不只靠调用方传 `force = true`：删除的三条收尾全在 `DeleteFlow` /
     * `BatchActions` 里，它们调的是无参 `refresh()`（那两个文件属别的 lane）。
     * 而"我们自己删了东西"是有可靠信号的 —— [pruneForDeleted] 是**所有**删除路径
     * 的共同收尾（搬进回收站、真删、系统授权框回来），在那里标一下脏就够了。
     *
     * 读成功后清掉；读失败**不清**（下一次仍然必读）—— 与 [lastGen] 同一条原则。
     */
    private var libraryDirty = false

    /**
     * 重新读一遍媒体库。
     *
     * @param force 调用方**明确知道"库刚被改过"**时传 true：下拉刷新、一瞬保存、
     *   分享导入、备份恢复、拼图/存帧落盘、互通收件。不传就是"例行看一眼"
     *   （`ON_RESUME`），交给 [RefreshGate] 判断要不要真读。
     *
     * 与改前逐条一致的地方（都是踩过的坑，注释保留）：
     *  - `null` = 没读到；空表 = 读到了、库里确实没有。两者不许混（否则提供方抖一下，
     *    画廊就成了"这就是你的全部照片"，`hasMore` 也被关掉）。
     *  - 没读到的那些**不许**覆盖上一次的好结果，只把问题句写进 [libraryProblem]。
     *  - 内页开着时跟着重读那一册；读不到就留着上一册并说一句（直接清空会被读成"这一册是空的"）。
     *  - `jumpUri` 有值时把查看器定位到那张（一瞬保存后回来）。
     *
     * ## 跳过时**不闪转圈**
     * 跳过的一趟连 `refreshing = true` 都不写：它本来就什么都没做，闪一下转圈等于
     * 让用户以为读了一遍。`refreshing` 保持 false 是"这一趟无事发生"的正确表达。
     *
     * ## 只有真读成功才推进 [lastGen] / [lastFullReadAt]
     * 这正是 `MediaRepository.readLibrary` 注释里那条原则的延续：**失败不许写缓存**。
     * 把一次失败记成"库里没变"，接下来整整一个 TTL 都拿不到新数据 ——
     * 用户看到的是"拍完照片回到相册，什么都没有"。
     *
     * ## 为什么不做"按当前路由只读一份"
     * [photos] 是搜索、收藏夹详情、回忆、幻灯片、页眉「最近一张」的共同数据源。
     * 只读当前屏那一份，退页之后别的屏就是旧数据 —— 那类错只在装机长路径上才暴露。
     * 所以这里是"库有没有变"，不是"现在看哪一屏"。
     */
    fun refresh(force: Boolean = false) {
        scope.launch {
            val favSnapshot = favorites.ids()
            val hideSnapshot = hidden.ids()
            val gen = repo.generation()
            // TTL 用**单调时钟**：墙钟会被用户/网络改，一次回拨就能把 TTL 变成
            // "永远新鲜"（库变了却一直跳过）或"永远过期"（每趟都全量读）。
            val now = android.os.SystemClock.elapsedRealtime()
            // 每一项各自判断：库没变但收藏 id 变了（用户刚点了一颗星）时仍要重读那一份。
            //
            // `jumpUri != null` 也必须算"必读"：它只在"刚保存了一张、要回到查看器看这张"
            // 时出现（一瞬成片 / 相机原片）。那一刻库**确实**刚变了，可 generation 的更新
            // 在个别提供方上是异步的 —— 只看代数会跳过这一趟，于是查看器永远不跳过去，
            // 而且一句错都不报。宁可多读一次。
            // `libraryDirty` 同理，覆盖"我们自己刚删了照片"的所有路径（见它的注释）。
            val readLibrary = libraryDirty || jumpUri != null || RefreshGate.needsLibraryRead(
                force = force,
                lastGen = lastGen,
                gen = gen,
                ageMs = if (lastFullReadAt == 0L) Long.MAX_VALUE else now - lastFullReadAt,
            )
            // 收藏 / 隐藏这两份只看 id 集合本身（见 RefreshGate.needsIdsRead 的注释）。
            // `*ReadFailed` 那一项是必需的：读失败时 id 集合不会变，没有它就会把
            // "一次失败"缓存成"不用读"，角标停在旧数据上整整一个 TTL。
            val readFav = readLibrary || favReadFailed || RefreshGate.needsIdsRead(favoriteIds, favSnapshot)
            val readHidden = readLibrary || hiddenReadFailed ||
                RefreshGate.needsIdsRead(hiddenIds, hideSnapshot)
            // 这一趟什么都不用读：**连转圈都不闪**（它确实什么都没做）。`refreshing`
            // 保持 false 是"无事发生"的正确表达，闪一下等于骗用户说读过了。
            if (!readLibrary && !readFav && !readHidden) return@launch
            refreshing = true
            loadingMore = false
            val lib = if (readLibrary) repo.readLibrary() else null
            val gotPhotos = lib?.photos
            val gotAlbums = lib?.albums
            val gotFav = if (readFav) repo.loadByIds(favSnapshot) else null
            val gotHidden = if (readHidden) repo.loadByIds(hideSnapshot) else null
            // **只有真读全了才推进 generation**：把一次失败记成"库里没变"，
            // 接下来整整一个 TTL 都拿不到新数据（用户看到"拍完回相册什么都没有"）。
            val libraryOk = readLibrary && gotPhotos != null && gotAlbums != null
            if (libraryOk) {
                lastGen = gen
                lastFullReadAt = now
                // 只有读成功才清脏；失败那一次留着，下一趟仍然必读。
                libraryDirty = false
            }
            if (gotPhotos != null) {
                photos = gotPhotos
                hasMore = gotPhotos.size >= MediaRepository.PAGE
            }
            if (gotFav != null) {
                favoritePhotos = gotFav
                favoriteIds = favSnapshot
                favReadFailed = false
            } else if (readFav) {
                favReadFailed = true
            }
            if (gotHidden != null) {
                hiddenPhotos = gotHidden
                hiddenIds = hideSnapshot
                hiddenReadFailed = false
            } else if (readHidden) {
                hiddenReadFailed = true
            }
            if (gotAlbums != null) remoteAlbums = gotAlbums
            val missed = listOfNotNull(
                if (readLibrary && gotPhotos == null) "照片库" else null,
                if (readLibrary && gotAlbums == null) "相册清单" else null,
                if (readFav && gotFav == null) "收藏那份清单" else null,
                if (readHidden && gotHidden == null) "已隐藏那份清单" else null,
            )
            // 跳过的那几项没有"没读到"，所以 `missed` 只说这一趟真的试过的东西。
            // 清问题句也只在这一趟**真读全了**的时候做：一次"因为没变所以跳过"没有资格
            // 宣告"没问题"（上一次读失败留下的那句话得留着，直到真的读成功一次）。
            if (missed.isNotEmpty()) readMissed(missed) else if (libraryOk) libraryProblem = null
            rebuildAlbums()
            val id = albumId
            if (id != null) {
                albumPhotos = when (id) {
                    Favorites.ALBUM_ID -> favoritePhotos
                    Hidden.ALBUM_ID -> hiddenPhotos
                    // 内页除了 generation 没有别的信号：库没变（或被 force）才重读那一册。
                    // 跳过时保留原列表，避免凭空清成 null 让内页闪空。
                    else -> if (readLibrary) {
                        repo.loadAlbum(id) ?: albumPhotos.also {
                            readMissed(listOf("这一册的照片"))
                        }
                    } else {
                        albumPhotos
                    }
                }
            }
            val jump = jumpUri
            if (jump != null && currentYishunUri() == null) {
                val idx = photos.indexOfFirst { it.uri == jump }
                if (idx >= 0) {
                    viewerPhotos = photos
                    viewerIndex = idx
                    // 改前这里只有一句 `viewerPhotos = photos`：查看器是 `when` 里按**数据在不在**
                    // 挑分支的，所以这一句本身就等于"打开查看器"。现在查看器还要一个路由层，
                    // 两条路都要走 —— 只设数据不压路由，屏幕上不会有任何反应（一瞬保存后的那张
                    // 就再也看不到，而且不报错）。
                    nav.showViewerAt(idx)
                    jumpUri = null
                }
            }
            refreshing = false
        }
    }

    /**
     * 翻下一页。
     *
     * 两个坑照抄：
     *  - 没读到**不等于**翻到底了：关掉 `hasMore` 就等于这一趟永远不再往下翻。
     *  - 去重与合并放到 Default 线程：`map{it.id}.toHashSet()` + `photos + added` 挂在 Main 上时
     *    是"每翻一页都比上一页贵"（5000 张起 0.3 ms，越往后越大），而那正是手指还在往上甩的时候。
     */
    fun loadMore() {
        if (!hasMore || loadingMore || refreshing) return
        loadingMore = true
        scope.launch {
            val more = repo.loadPhotosPage(photos.size)
            if (more == null) {
                readMissed(listOf("下一页"))
            } else if (more.isEmpty()) {
                hasMore = false
            } else {
                val current = photos
                val merged = withContext(Dispatchers.Default) {
                    val seen = current.map { it.id }.toHashSet()
                    current + more.filter { it.id !in seen }
                }
                photos = merged
                if (more.size < MediaRepository.PAGE || merged.size == current.size) hasMore = false
            }
            loadingMore = false
        }
    }

    /** 没读到某些东西：留日志、写问题句、给一句 4.5 秒的结论。 */
    fun readMissed(what: List<String>) {
        android.util.Log.w("MediaRead", "没读到：" + what.joinToString("、"))
        libraryProblem = "没读到" + what.joinToString("、") + "（系统那边没答话）"
        batchNote = "没读到" + what.joinToString("、") + "，先显示上一次读到的"
    }

    // ── 导航驱动的数据装载 ────────────────────────────────────────────

    /**
     * 进相册内页：先把上一册的样子留着，读完再换（读不到就保留 + 说一句）。
     * 与改前 `AlbumListScreen(onOpen = …)` 里的那段一致。
     */
    fun openAlbum(album: AlbumItem) {
        albumId = album.id
        albumTitle = album.name
        scope.launch {
            val got = when (album.id) {
                Favorites.ALBUM_ID -> repo.loadByIds(favorites.ids())
                Hidden.ALBUM_ID -> repo.loadByIds(hidden.ids())
                else -> repo.loadAlbum(album.id)
            }
            if (got == null) readMissed(listOf("「" + album.name + "」这一册")) else albumPhotos = got
        }
    }

    /** 关相册内页（返回键 / 页名那颗返回）。 */
    fun closeAlbum() {
        albumPhotos = null
        albumId = null
        albumTitle = null
    }

    /**
     * 打开查看器。
     *
     * 两个入口的"要不要剔除隐藏"不同，但都走这里（[fromGalleryCell] 区分）：
     *  - 从时间线某格进：**剔除**隐藏的那些（除自己），因为隐藏的照片不该在左右滑里出现；
     *  - 从相册内页进：内页本身就是那一册，不剔除。
     */
    fun openViewer(fromGalleryCell: Boolean, photo: PhotoItem, source: List<PhotoItem>) {
        val shown = if (fromGalleryCell) {
            source.filter { it.id !in hiddenIds || it.id == photo.id }
        } else {
            source
        }
        viewerPhotos = shown
        viewerIndex = shown.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)
    }

    fun closeViewer() {
        viewerPhotos = null
    }

    /**
     * 打开查看器并停在某一页。
     *
     * [openViewer] 是"按一张照片去源列表里找下标"（时间线 / 相册内页用），
     * 这里是"列表与下标都已知"（收藏夹 / 搜索 / 回忆详情用）—— 后者的列表**不是**
     * `photos`，按 uri 反查会查不到。两条路都落到同一对状态上（`viewerPhotos` + `viewerIndex`）。
     */
    fun openViewerAt(next: List<PhotoItem>, index: Int) {
        if (next.isEmpty()) return
        viewerPhotos = next
        viewerIndex = index.coerceIn(0, next.lastIndex)
    }

    /**
     * 存一张拼图。返回新 uri；失败 null（调用方必须说一句，不许静默）。
     *
     * 命名沿用全项目那一套（`SuiPai_一瞬_…` / `SuiPai_对比_…`）：拼图是 `SuiPai_拼图_…`。
     * 它不参与"批量一瞬跳过 `SuiPai_一瞬_` 开头"那条判断 —— 拼图不是一瞬出品。
     */
    suspend fun saveCollageBitmap(bitmap: android.graphics.Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            repo.saveBitmap(bitmap, "SuiPai_拼图_${System.currentTimeMillis()}.jpg", quality = 95)
        }

    // ── 回收站 ────────────────────────────────────────────────────────

    /**
     * 这张照片是不是**本 app 写进媒体库**的。
     *
     * 判据是文件名前缀 `SuiPai_`：本 app 的四个写入口（相机原片 / 一瞬成片 / 副本 /
     * 对比图 / 拼图）全部用它，而相机、微信、截图的照片都不是。
     *
     * 为什么不用"`Pictures/SuiPai` 目录"判：`MediaRepository.saveBytes` 只往
     * `Pictures/SuiPai` 写，但**读**回来的 `PhotoItem.bucketName` 是系统给的
     * 相册显示名（可能是 "SuiPai" 也可能是别的），拿它当判据在国产 ROM 上会漏。
     * 而前缀是我们自己写进去的，稳定。
     *
     * 判错的代价是不对称的：**误判成"自己的"** → 走回收站失败 → 退回真删（最多丢 30 天可恢复性）；
     * **误判成"外来的"** → 走系统授权框（多一次确认，不会丢东西）。所以宁可宽一点。
     */
    internal fun isOwnPhoto(photo: PhotoItem): Boolean =
        photo.displayName.startsWith(OWN_NAME_PREFIX)

    /**
     * 把一张搬进回收站。**这是删除的第一选择**（30 天内可恢复）。
     *
     * 返回 true 才算删掉了；false 时调用方必须走原来的真删 —— 不许把 false 当成功
     * （那正是 `PerfSourceGuardTest.attemptCountProgress` 抓的那类"进度条走到 N/N
     * 而一张都没动"）。
     */
    internal suspend fun moveToTrash(photo: PhotoItem): Boolean = withContext(Dispatchers.IO) {
        trashOf().moveToTrash(
            photoId = photo.id,
            uri = photo.uri,
            displayName = photo.displayName,
            sizeBytes = photo.sizeBytes,
            width = photo.width,
            height = photo.height,
            // 原始拍摄时间必须一起搬：不带的话进回收站那一行与恢复出来的那一行都会被
            // MediaProvider 补成"现在"，照片在时间线里跳到今天。
            // `PhotoItem.dateTaken` 是 `readPhoto` 从 `DATE_TAKEN` 读出来的
            // （缺失时才退 `DATE_ADDED * 1000`），所以它已经是能拿到的最好的原时间。
            dateTaken = photo.dateTaken,
        )
    }

    /**
     * 回收站句柄。走 [com.suipai.app.data.Trash.of] 的进程级单例 —— **不要**改成
     * `Trash(context)`：它的 `lock` 是实例字段，多一个实例就多一把锁保护同一份
     * `trash.json`，启动清理与本类删除并发时会丢条目。
     */
    private fun trashOf(): com.suipai.app.data.Trash =
        com.suipai.app.data.Trash.of(context)

    /** 回收站数字（设置页/隐私页要）。 */
    internal fun trashStats(): Pair<Int, Long> = trashOf().stats()

    /**
     * 关一瞬。
     *
     * 改前 `yishunUri = null` 只有一句，但**相机层是另一个布尔**（`showCamera`），
     * 所以从相机进一瞬再关掉，回到的是相机（相机那个 `if` 排在 tab 前面）。
     * 现在相机是一个路由层，`pop()` 掉一瞬自然露出来 —— 这里只需要清 `jumpUri`。
     * 保留这个函数是为了让"关一瞬"有一个说得出名字的动作（[DeleteFlow] 与
     * `AppNavHost` 都走它），而不是散在各处写 `pop()`。
     */
    fun closeYishun() {
        // 一瞬本身不带状态，它的 uri 就在路由里；这里只做导航层的收尾。
    }

    /**
     * 从查看器进一瞬前，把查看器当前页对齐到这张照片（一瞬的「上一张 / 下一张」读的是 [viewerIndex]）。
     */
    fun syncViewerTo(photo: PhotoItem) {
        viewerIndex = viewerPhotos?.indexOfFirst { it.id == photo.id }?.coerceAtLeast(0) ?: 0
    }

    // ── 选择 ──────────────────────────────────────────────────────────

    fun toggleSelect(photo: PhotoItem) {
        selectedIds = if (photo.id in selectedIds) selectedIds - photo.id else selectedIds + photo.id
    }

    fun addToSelection(id: Long) {
        selectedIds = selectedIds + id
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    /**
     * 全选 / 反选（列表工具栏那两颗）。`items` 是**当前可见**的那些。
     * 语义：全都已选 → 取消这几张；否则并上。
     */
    fun selectAllOrClear(items: List<PhotoItem>) {
        val ids = items.map { it.id }.toSet()
        selectedIds = if (ids.isNotEmpty() && ids.all { it in selectedIds }) {
            selectedIds - ids
        } else {
            selectedIds + ids
        }
    }

    /** 反选（只对可见的那些取反，不可见的选中项保持原样 —— 与改前一致）。 */
    fun invertSelection(items: List<PhotoItem>) {
        val vis = items.map { it.id }.toSet()
        selectedIds = (selectedIds - vis) + (vis - selectedIds)
    }

    // ── 收藏 / 隐藏（单张） ───────────────────────────────────────────

    /**
     * 单张收藏 / 取消收藏。
     *
     * 两处细节照抄：
     *  - 落盘挪到 IO：主线程那次同步 commit 要 69 ms（`PersistCostTest`）。
     *  - 用点击当场拿到的新集合去回读（`remember(favTick)` 要到下一次组合才重算，
     *    在它之后回读 `favorites.ids()` 等于在 Main 上再整表装箱一回）。
     */
    fun toggleFavorite(photo: PhotoItem) {
        val next = favorites.toggle(photo.id)
        favoriteIds = next
        favTick++
        scope.launch {
            if (!withContext(Dispatchers.IO) { favorites.persist() }) {
                batchNote = com.suipai.app.ui.common.persistWarning("收藏与隐藏的清单")
            }
            repo.loadByIds(next)?.let { favoritePhotos = it }
                ?: readMissed(listOf("收藏那份清单"))
            if (albumId == Favorites.ALBUM_ID) albumPhotos = favoritePhotos
            rebuildAlbums()
        }
    }

    /** 单张隐藏 / 取消隐藏。与 [toggleFavorite] 同一套。 */
    fun toggleHidden(photo: PhotoItem) {
        val next = hidden.toggle(photo.id)
        hiddenIds = next
        hideTick++
        scope.launch {
            if (!withContext(Dispatchers.IO) { hidden.persist() }) {
                batchNote = com.suipai.app.ui.common.persistWarning("收藏与隐藏的清单")
            }
            repo.loadByIds(next)?.let { hiddenPhotos = it }
                ?: readMissed(listOf("已隐藏那份清单"))
            if (albumId == Hidden.ALBUM_ID) albumPhotos = hiddenPhotos
            rebuildAlbums()
        }
    }

    // ── 供 BatchActions / DeleteFlow 用的内部口 ──────────────────────

    /** 供 BatchActions / DeleteFlow / ShareImport 用的作用域（它们不是 Composable，拿不到 rememberCoroutineScope）。 */
    internal fun scopeForActions(): CoroutineScope = scope

    internal fun photosListing(): List<PhotoItem> = photos

    internal fun albumListing(): List<PhotoItem>? = albumPhotos

    internal fun setPhotos(next: List<PhotoItem>) {
        photos = next
    }

    internal fun setAlbumPhotos(next: List<PhotoItem>?) {
        albumPhotos = next
    }

    internal fun setViewerPhotos(next: List<PhotoItem>?) {
        viewerPhotos = next
    }

    internal fun setFavoritePhotos(next: List<PhotoItem>) {
        favoritePhotos = next
    }

    internal fun setHiddenPhotos(next: List<PhotoItem>) {
        hiddenPhotos = next
    }

    internal fun replaceFavoriteIds(next: Set<Long>) {
        favoriteIds = next
    }

    internal fun replaceHiddenIds(next: Set<Long>) {
        hiddenIds = next
    }

    /** 当前收藏 / 隐藏集合的实时句柄（批量动作要读"现在是哪一批"）。 */
    internal fun favoritesStore(): Favorites = favorites

    internal fun hiddenStore(): Hidden = hidden

    internal fun repository(): MediaRepository = repo

    internal fun appContext(): Context = context

    /** 一瞬当前开着的话，它的 uri（`refresh` 里判"别抢查看器"用）。 */
    internal fun currentYishunUri(): String? =
        nav.stack.layers.lastOrNull { it is AppRoute.Yishun }?.let { (it as AppRoute.Yishun).uri }

    /**
     * 有照片真的没了之后，把 id 从收藏/隐藏里摘掉。
     *
     * 照片没了、id 却还留在集合里就是纯泄漏：两个集合无界增长，
     * 而且每次进相册都要按整串 id 去 loadByIds 查一遍。
     */
    internal fun pruneForDeleted(ids: List<Long>) {
        if (ids.isEmpty()) return
        // 这一步之后库确实变了：下一趟 refresh 必须真读，不许被 generation 门控跳过
        // （我们自己删的照片，个别提供方的 generation 更新有延迟）。
        libraryDirty = true
        favorites.removeAll(ids)
        hidden.removeAll(ids)
        replaceFavoriteIds(favorites.ids())
        replaceHiddenIds(hidden.ids())
        selectedIds = selectedIds - ids.toSet()
        scope.launch {
            // 收藏与隐藏写在同一个 suipai.xml 里：一次 persist 把两份一起落盘。
            withContext(Dispatchers.IO) { favorites.persist() }
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 重算相册清单（批量收藏/隐藏之后调）。
     *
     * 只在数据变化时调（改前是 `remember(favoritePhotos, hiddenPhotos, remoteAlbums)`）——
     * 两趟 `maxOfOrNull` 是 O(收藏)/O(隐藏) 的全表扫，不能跟着每帧重组跑。
     */
    internal fun refreshAlbumsPublic() = rebuildAlbums()

    /**
     * 重算相册清单：收藏 / 隐藏两个虚拟册的封面与数量从实时数据派生，
     * 切 Tab、收藏/隐藏后立即可见。
     */
    private fun rebuildAlbums() {
        albums = listOf(
            AlbumItem(
                id = Favorites.ALBUM_ID,
                name = "收藏",
                cover = favoritePhotos.firstOrNull()?.uri,
                count = favoritePhotos.size,
                lastTaken = favoritePhotos.maxOfOrNull { it.dateTaken } ?: 0L,
            ),
            AlbumItem(
                id = Hidden.ALBUM_ID,
                name = "隐藏",
                cover = hiddenPhotos.firstOrNull()?.uri,
                count = hiddenPhotos.size,
                lastTaken = hiddenPhotos.maxOfOrNull { it.dateTaken } ?: 0L,
            ),
        ) + remoteAlbums
    }

    companion object {
        /**
         * 本 app 写进媒体库的照片统一用这个前缀 —— 它是"这张删了能进回收站"的判据。
         *
         * 三个写入口都遵守：`MediaRepository.saveBytes` 的调用方（相机原片
         * `SuiPai_<ts>.jpg`、一瞬成片 `SuiPai_一瞬_…`、副本 `SuiPai_副本_…`）、对比图
         * `SuiPai_对比_…`、拼图 `SuiPai_拼图_…`。
         * 改名前先读 [isOwnPhoto] 的注释：这个前缀一改，历史照片就全都"变成外来的"了
         * （它们仍在回收站里，但新的删除会走系统授权框）。
         */
        const val OWN_NAME_PREFIX = "SuiPai_"
    }
}
