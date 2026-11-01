package com.suipai.app.ui.sync

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.design.EmptyState
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.TextButtonTonal
import com.suipai.app.ui.theme.SuiPaiSheet
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.suipai.app.sync.AndroidSyncSource
import com.suipai.app.sync.SyncManager
import com.suipai.core.sync.IncomingTransfer
import com.suipai.core.sync.OutgoingTransfer
import com.suipai.core.sync.PeerInfo
import com.suipai.core.sync.SyncHub
import com.suipai.core.sync.SyncPhotoMeta
import com.suipai.core.sync.appendRemotePage
import com.suipai.core.sync.hasMoreRemotePages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 互通主界面：发现附近设备、浏览/下载对方照片、向对方发送本机照片、处理来件。
 *
 * [onSaved] 在照片入库（下载成功或接受来件）后调用，让主界面刷新相册。
 */
@Composable
fun SyncScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val source = remember { AndroidSyncSource(context) }

    var hub by remember { mutableStateOf(SyncManager.ensure(context)) }
    val state by hub.state.collectAsState()

    var myName by remember { mutableStateOf(SyncManager.deviceName(context)) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(myName) }
    var selectedPeer by remember { mutableStateOf<PeerInfo?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hub.refreshPeers() }

    LaunchedEffect(state.lastError) {
        val err = state.lastError
        if (!err.isNullOrBlank()) snackbar.showSnackbar("互通出错：$err")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val sheetType = SuiPaiSheet.type
            // 页名 + 在线台数（等宽编号）+ 两颗动作格；和其它界面同一套排法。
            AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                AppBarText("互通", style = sheetType.pageHead)
                AppBarText(
                    "${state.peers.size} 台",
                    style = sheetType.frameNo,
                    color = SuiPaiSheet.colors.number,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                AppBarCell(
                    label = "改名",
                    on = false,
                    onClick = {
                        renameText = myName
                        showRename = true
                    },
                    modifier = Modifier.testTag("sync_rename"),
                )
                Spacer(Modifier.weight(1f))
                AppBarCell(
                    label = "刷新",
                    on = false,
                    onClick = { hub.refreshPeers() },
                    modifier = Modifier.testTag("sync_refresh"),
                )
            }
            // 状态行：等宽小字，和相机那条一样是「印样边上那行编号」
            Text(
                buildString {
                    append(if (state.running) "正在发现附近设备" else "互通未启动")
                    append("  ·  本机 ")
                    append(myName)
                    state.lastError?.takeIf { it.isNotBlank() }?.let { append("  ·  出错：$it") }
                },
                style = sheetType.frameNo,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            if (state.peers.isEmpty()) {
                // 空态：一条漏光 + 等宽 kicker + 主文案 + 说明 + 一个动作。
                // 改之前是三行居中文字（页首字号 + 说明 + 一颗方框格子），
                // 层级只有"大字/小字"两档，和别的界面不是一个世界。
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        kicker = "NO PEERS",
                        title = "还没发现别的设备",
                        note = "两台设备要连同一个 WiFi，并且都开着随拍这一页。",
                    )
                    TextButtonTonal(
                        label = "再找一次",
                        onClick = { hub.refreshPeers() },
                        modifier = Modifier.testTag("sync_rescan"),
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.peers, key = { it.id }) { peer ->
                        PeerCard(peer) { selectedPeer = peer }
                    }
                }
            }

            // ── 谁在看我的相册 ─────────────────────────────────────────
            // 服务端原来只要对面发个像样的 hello 就把相册列出去、随叫随取原图，
            // 而隐私政策写的是"两端都确认"。接收侧本来就有「接受/丢弃」，缺的是供片这一侧的开关。
            if (state.browseAsked.isNotEmpty() || state.browseGrants.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "谁在看我的相册",
                        style = SuiPaiSheet.type.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.browseAsked.forEach { ask ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "「${ask.name}」想浏览本机照片",
                                style = SuiPaiSheet.type.label,
                                modifier = Modifier.weight(1f),
                            )
                            AppBarCell(
                                label = "允许浏览",
                                on = false,
                                onClick = { hub.grantBrowse(ask.host) },
                                modifier = Modifier.testTag("sync_grant_" + ask.host),
                            )
                        }
                    }
                    state.browseGrants.forEach { host ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "已允许 $host",
                                style = SuiPaiSheet.type.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            AppBarCell(
                                label = "收回允许",
                                on = true,
                                onClick = { hub.revokeBrowse(host) },
                                modifier = Modifier.testTag("sync_revoke_" + host),
                            )
                        }
                    }
                }
            }

            if (state.outgoing.isNotEmpty()) {
                OutgoingSummary(state.outgoing)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("修改本机名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("设备名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isEmpty()) {
                        // 原来空名字只是把对话框关掉：点了「确定」，什么都没发生，也没人说明。
                        showRename = false
                        scope.launch { snackbar.showSnackbar("名字不能是空的") }
                    } else {
                        hub = SyncManager.rename(context, name)
                        myName = name
                        showRename = false
                        scope.launch { snackbar.showSnackbar("已改名为 $name") }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("取消") }
            },
        )
    }

    val peer = selectedPeer
    if (peer != null) {
        PeerSheet(
            hub = hub,
            peer = peer,
            source = source,
            scope = scope,
            snackbar = snackbar,
            onSaved = onSaved,
            onDismiss = { selectedPeer = null },
        )
    }

    state.incoming.firstOrNull()?.let { transfer ->
        IncomingDialog(
            hub = hub,
            transfer = transfer,
            scope = scope,
            snackbar = snackbar,
            onSaved = onSaved,
        )
    }
}

@Composable
private fun PeerCard(peer: PeerInfo, onClick: () -> Unit) {
    // 整行可点，不再嵌一颗按钮：点两下才知道能点的东西，不如把格子本身画成能点的样子。
    Row(
        Modifier
            .fillMaxWidth()
            // 描边用 `outline`（交互描边）而不是 `hairline`（装饰发丝线）：
            // 整行是可点的，1.4.11 要求"能点的边界"对背景 ≥3:1，而 hairline 只有 1.3:1 ——
            // 用它的那张卡看起来是"一块色块"，不是"一台能点开的设备"。
            .border(1.dp, SuiPaiColor.current.outline, SuiPaiSheet.frame)
            .background(SuiPaiColor.current.panel, SuiPaiSheet.frame)
            // 不再写 contentDescription = peer.name：`clickable` 会把子树的可见文字
            // （"安卓 · 3 分钟前"、"打开 ›"）合并进这一个节点，而显式的 contentDescription
            // 会把它整片盖掉 —— 听到的和看到的是两句话。这里只补角色与动作名。
            .clickable(role = Role.Button, onClickLabel = "打开这台设备的相册", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(if (peer.platform == "android") "安卓" else "iPhone")
                    append("  ·  ")
                    append(relTime(peer.lastSeenMs))
                },
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
            )
        }
        Text(
            "打开  ›",
            style = SuiPaiSheet.type.frameNo,
            color = SuiPaiSheet.colors.grease,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerSheet(
    hub: SyncHub,
    peer: PeerInfo,
    source: AndroidSyncSource,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }

    AppSheet(title = peer.name, tag = "sync_peer_sheet", onDismiss = onDismiss) {
        AppChipFlow {
            AppBarCell(
                label = "浏览照片",
                on = mode == 1,
                onClick = { mode = 1 },
                modifier = Modifier.testTag("sync_browse"),
            )
            AppBarCell(
                label = "发送照片",
                on = mode == 2,
                onClick = { mode = 2 },
                modifier = Modifier.testTag("sync_send"),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            when (mode) {
                1 -> BrowsePane(hub, peer, scope, snackbar, onSaved)
                2 -> SendPane(hub, peer, source, scope, snackbar)
                else -> Text(
                    "选一项：看对方相册，或把本机照片发过去。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

/** 一次翻页取多少张。协议里没有 `total`，末页只能靠「本页 < 这个数」判断（见 SYNC.md）。 */
private const val BROWSE_PAGE_SIZE = 60

@Composable
private fun BrowsePane(
    hub: SyncHub,
    peer: PeerInfo,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onSaved: () -> Unit,
) {
    var photos by remember(peer.id) { mutableStateOf<List<SyncPhotoMeta>?>(null) }
    var readError by remember(peer.id) { mutableStateOf<String?>(null) }
    var retryTick by remember(peer.id) { mutableIntStateOf(0) }
    var thumbs by remember(peer.id) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var thumbsFailed by remember(peer.id) { mutableStateOf<Set<String>>(emptySet()) }
    // 下一页的 offset 单独记，**不要**用 `photos.size` 推：新页可能带回上一页已经出现过的 id
    // （对端相册是活的，两页之间有人拍了张新的，offset 就整体后移一格），
    // 而 appendRemotePage 会把重复的丢掉 —— 于是 photos.size 比"已经消费掉多少条"小，
    // 拿它当 offset 会原地打转：每次拿回同一页、全被去重、size 不变、永远翻不完。
    var nextOffset by remember(peer.id) { mutableIntStateOf(0) }
    var hasMore by remember(peer.id) { mutableStateOf(false) }
    var loadingMore by remember(peer.id) { mutableStateOf(false) }
    var moreError by remember(peer.id) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<SyncPhotoMeta?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(peer.id, retryTick) {
        readError = null
        moreError = null
        // 「再读一次」只重读第一页：已经翻出来的后几页不留，
        // 否则重读会和旧页拼成两份、张数虚高（重新从第一页开始是用户能预期的语义）。
        nextOffset = 0
        hasMore = false
        loadingMore = false
        val metas = withContext(Dispatchers.IO) { hub.fetchPeerPhotos(peer, 0, BROWSE_PAGE_SIZE) }
        if (metas == null) {
            // 没读到 ≠ 对方没有。原来这一支会一路走到 `list.isEmpty()`，
            // 于是"对方没开机 / 握手失败"在界面上写成「对方没有可读取的照片」。
            photos = null
            thumbs = emptyMap()
            thumbsFailed = emptySet()
            readError = hub.state.value.lastError ?: "没读到「${peer.name}」的清单"
            return@LaunchedEffect
        }
        photos = metas
        nextOffset = metas.size
        hasMore = hasMoreRemotePages(metas.size, BROWSE_PAGE_SIZE)
        if (metas.isEmpty()) {
            thumbs = emptyMap()
            thumbsFailed = emptySet()
            return@LaunchedEffect
        }
        // 一次连接取回整页缩略图。之前是每个格子各自 fetchPeerThumb，
        // 一页就是几十次 TCP 连接 + 几十轮握手，对端也得开几十条线程。
        // 解码也放在 IO 里：一页 60 张位图在主线程解完是肉眼可见的一顿。
        val (decoded, failed) = withContext(Dispatchers.IO) {
            decodeRemoteThumbs(hub.fetchPeerThumbs(peer, metas.map { it.id }, 256), metas)
        }
        thumbs = decoded
        thumbsFailed = failed
    }

    /**
     * 读下一页并**追加**。
     *
     * 只取新那一页的元信息与缩略图：把前面所有页重拉一遍看着更简单，
     * 但翻到第 5 页时每点一次都在重做前 4 页（3000 个 marker 的解码 + 一次长列表重传），
     * 而用户要的只是最下面那 60 格。
     */
    fun loadNextPage() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        moreError = null
        val offset = nextOffset
        // 这一趟属于哪一代（「再读一次」会把下面的整页状态推倒重来）。
        // 不在回来时核对这一步，一次慢网络就能把旧页拼到新一页上，张数还看着像对的。
        val epoch = retryTick
        scope.launch {
            val page = withContext(Dispatchers.IO) {
                hub.fetchPeerPhotos(peer, offset, BROWSE_PAGE_SIZE)
            }
            if (epoch != retryTick) return@launch
            if (page == null) {
                // 已经读出来的页一张都不许丢：一页没拿到 ≠ 对方相册没了。
                moreError = hub.state.value.lastError ?: "下一页没读到"
                loadingMore = false
                return@launch
            }
            val before = photos.orEmpty()
            val known = before.mapTo(HashSet(before.size)) { it.id }
            val fresh = page.filter { it.id !in known }
            photos = appendRemotePage(before, page)
            // offset 按**服务端实际给的条数**前进，不是按去重后的净增张数（见 nextOffset 的注释）。
            nextOffset = offset + page.size
            hasMore = hasMoreRemotePages(page.size, BROWSE_PAGE_SIZE)
            if (fresh.isNotEmpty()) {
                val (decoded, failed) = withContext(Dispatchers.IO) {
                    decodeRemoteThumbs(hub.fetchPeerThumbs(peer, fresh.map { it.id }, 256), fresh)
                }
                thumbs = thumbs + decoded
                thumbsFailed = thumbsFailed + failed
            }
            loadingMore = false
        }
    }

    val list = photos
    when {
        readError != null -> Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                readError.orEmpty(),
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(10.dp))
            AppBarCell(label = "再读一次", on = false, onClick = { retryTick++ })
        }
        list == null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在读取对方照片…")
        }
        list.isEmpty() -> Text(
            "对方没有可读取的照片",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        else -> Column {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { meta ->
                    RemotePhotoCell(
                        bitmap = thumbs[meta.id],
                        failed = meta.id in thumbsFailed,
                        meta = meta,
                        onClick = { confirm = meta },
                    )
                }
            }
            // 已加载张数：这是「翻页真的生效了」唯一可读的证据。
            // 没有它，点「加载下一页」之后能观察到的只有网格滚出了一点 ——
            // 而在无头模拟器上那和"什么都没发生"长得一样。
            //
            // 标 liveRegion：这一句会自己从「已加载 60 张」变成「已加载 120 张」，
            // 而不标的话读屏只在有人碰它时才念（Compose 1.7.6 对普通 Text 原地改内容
            // 只发 WINDOW_CONTENT_CHANGED）—— 翻页对读屏用户就成了一个静默动作。
            Text(
                "已加载 ${list.size} 张",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
                modifier = Modifier
                    .testTag("sync_remote_count")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (moreError != null) {
                Text(
                    "下一页没读到：${moreError.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // 显式的「加载下一页」，不做自动触底。
            // 这个仓库的验证习惯是「可见即可点」：uiautomator 点得到的东西才好断言；
            // 自动触底在无头模拟器上很难证明它真的触发了（滚一下没滚到底就什么都不发生，
            // 而"什么都没发生"和"功能坏了"在 dump 里一模一样）。
            AppBarCell(
                label = when {
                    loadingMore -> "正在读下一页…"
                    hasMore -> "加载下一页"
                    // 末页之后格子**留着**、只是不可点，并说明为什么。
                    // 让它消失的话，用户会以为界面坏了（点了之后唯一那颗动作不见了）。
                    else -> "没有更多了"
                },
                on = false,
                enabled = hasMore && !loadingMore,
                onClick = { loadNextPage() },
                modifier = Modifier.testTag("sync_more_remote"),
            )
        }
    }

    if (downloading) {
        Text(
            "正在下载…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    val target = confirm
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("下载照片") },
            text = { Text("${target.name}\n${fmtSize(target.sizeBytes)}") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    downloading = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { hub.downloadPhoto(peer, target) }
                        downloading = false
                        if (ok) {
                            snackbar.showSnackbar("已保存到 Pictures/SuiPai")
                            onSaved()
                        } else {
                            snackbar.showSnackbar("下载失败")
                        }
                    }
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 把一批缩略图字节解成可显示的位图，并分出"拿到但解不开"的那几张。
 *
 * 两个返回值的差别是有意的：`decoded` 里没有的 id 有两种原因 ——
 * 网络没拿到、或者拿到了但不是能解的 JPEG。前者该继续转圈，后者必须变成
 * 「这张读不出来」的兜底格（`failed`），否则一个坏文件会让那颗格子永久转圈。
 */
private fun decodeRemoteThumbs(
    bytes: Map<String, ByteArray>,
    metas: List<SyncPhotoMeta>,
): Pair<Map<String, ImageBitmap>, Set<String>> {
    val decoded = LinkedHashMap<String, ImageBitmap>(bytes.size)
    val failed = LinkedHashSet<String>()
    for (meta in metas) {
        val blob = bytes[meta.id]
        val img = blob?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
        if (img != null) decoded[meta.id] = img else failed.add(meta.id)
    }
    return decoded to failed
}

@Composable
private fun RemotePhotoCell(
    bitmap: ImageBitmap?,
    failed: Boolean,
    meta: SyncPhotoMeta,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(SuiPaiSheet.frame)
            .background(SuiPaiColor.current.panelRaised)
            // 对端相册里这一格可点（看大图/下载），所以描边同样是交互描边而不是装饰线。
            .border(1.dp, SuiPaiColor.current.outline, SuiPaiSheet.frame)
            // 对端相册里这一格没有"选中"这一态（点它是看大图/下载），
            // 所以只补角色与动作名，名字仍由 contentDescription 给。
            .clickable(role = Role.Button, onClickLabel = "查看这张", onClick = onClick)
            .semantics { contentDescription = meta.name },
        contentAlignment = Alignment.Center,
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!failed) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp),
            ) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    meta.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    fmtSize(meta.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SendPane(
    hub: SyncHub,
    peer: PeerInfo,
    source: AndroidSyncSource,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
) {
    var photos by remember { mutableStateOf<List<SyncPhotoMeta>?>(null) }
    var thumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val metas = withContext(Dispatchers.IO) { source.listPhotos(0, 60) }
        photos = metas
        if (metas.isEmpty()) return@LaunchedEffect
        // 一整页缩略图放在**一个**协程、一次状态写里取。
        // 原来每个格子各自 `readThumbBytes` + 各自一个 `LaunchedEffect`：
        // 一屏就是 60 趟 MediaStore、60 次独立解码、60 次重组，
        // 而对面那一栏早就批量了（`fetchPeerThumbs` 那段注释记着同样的账）。
        thumbs = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, ImageBitmap>(metas.size)
            for (m in metas) {
                source.readThumbBytes(m.id, 256)?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                        .getOrNull()?.let { out[m.id] = it }
                }
            }
            out
        }
    }

    val list = photos
    when {
        list == null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在读取本机照片…")
        }
        list.isEmpty() -> Text(
            "本机没有可发送的照片",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height(300.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { meta ->
                LocalPhotoCell(
                    meta = meta,
                    bitmap = thumbs[meta.id],
                    selected = meta.id in selected,
                    onClick = {
                        selected = if (meta.id in selected) selected - meta.id else selected + meta.id
                    },
                )
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "已选 ${selected.size} 张",
            style = SuiPaiSheet.type.frameNo,
            color = SuiPaiSheet.colors.number,
        )
        AppBarPrimary(
            enabled = selected.isNotEmpty() && !sending,
            label = if (sending) "发送中 ${progress.first}/${progress.second}" else "发送 ${selected.size} 张",
            onClick = {
                val targets = list.orEmpty().filter { it.id in selected }
                if (targets.isEmpty()) return@AppBarPrimary
                sending = true
                result = null
                progress = 0 to targets.size
                scope.launch {
                    var ok = 0
                    var fail = 0
                    targets.forEachIndexed { index, meta ->
                        val bytes = withContext(Dispatchers.IO) { source.readPhotoBytes(meta.id) }
                        val sent = if (bytes != null) {
                            withContext(Dispatchers.IO) { hub.sendPhoto(peer, meta, bytes) }
                        } else {
                            false
                        }
                        if (sent) ok++ else fail++
                        progress = index + 1 to targets.size
                    }
                    sending = false
                    result = "完成：成功 $ok 张，失败 $fail 张"
                    selected = emptySet()
                    snackbar.showSnackbar(if (fail == 0) "已发送 $ok 张" else "发送完成，失败 $fail 张")
                }
            },
            modifier = Modifier.testTag("sync_send_go"),
        )
    }
    result?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalPhotoCell(
    meta: SyncPhotoMeta,
    bitmap: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(SuiPaiSheet.frame)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) SuiPaiSheet.colors.grease else SuiPaiSheet.colors.hairline,
                SuiPaiSheet.frame,
            )
            .clickable(role = Role.Button, onClickLabel = "选这张", onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = meta.name + if (selected) "，已选中" else ""
            },
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            // 选中语：琥珀油笔框（框已经画在上面）+ 角上一个实心点，不再整格蒙一层色。
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(10.dp)
                    .background(SuiPaiSheet.colors.grease, CircleShape),
            )
        }
    }
}

@Composable
private fun IncomingDialog(
    hub: SyncHub,
    transfer: IncomingTransfer,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onSaved: () -> Unit,
) {
    // compose 1.7.6 的 ProduceStateDoesNotAssignValue 在本项目里看不见 Kotlin 的 `value =`
    // 赋值（实测 5 种写法全部误报），故用等价写法而不是 @Suppress。
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(transfer.transferId) {
        preview = withContext(Dispatchers.IO) { decodePreview(transfer.bytes, 256) }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("收到照片") },
        text = {
            Column {
                Text("来自：${transfer.fromPeer.name}")
                Text(transfer.meta.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${transfer.meta.width}×${transfer.meta.height} · ${fmtSize(transfer.meta.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val img = preview
                if (img != null) {
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.Default) {
                    hub.acceptIncoming(transfer.transferId)
                    val stillPending = hub.state.value.incoming.any { it.transferId == transfer.transferId }
                    withContext(Dispatchers.Main) {
                        if (stillPending) {
                            snackbar.showSnackbar("保存失败")
                        } else {
                            onSaved()
                            snackbar.showSnackbar("已保存到 Pictures/SuiPai")
                        }
                    }
                }
            }) { Text("接受") }
        },
        dismissButton = {
            TextButton(onClick = { hub.rejectIncoming(transfer.transferId) }) { Text("拒绝") }
        },
    )
}

@Composable
private fun OutgoingSummary(items: List<OutgoingTransfer>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val active = items.count { !it.done }
        if (active > 0) {
            Text(
                "发送中 $active 张…",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.grease,
            )
        }
        items.filter { it.done }.takeLast(3).forEach { transfer ->
            Text(
                if (transfer.error == null) {
                    "已完成：${transfer.meta.name}"
                } else {
                    "失败：${transfer.meta.name}（${transfer.error}）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun decodePreview(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

private fun relTime(ms: Long): String {
    val seconds = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒前"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        else -> "${seconds / 3600} 小时前"
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}