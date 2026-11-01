// 编排层：发现、握手、照片列表与传输。
// 原生端用 Dispatchers.Default 承载阻塞 socket 读写，v1 可接受。
package com.suipai.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlin.native.ObjCName
import kotlin.random.Random

/** 平台侧照片来源：Android 由 app 层实现，iOS 由 Swift 实现。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncPhotoSource", exact = true)
interface SyncPhotoSource {
    fun listPhotos(offset: Int, limit: Int): List<SyncPhotoMeta>

    fun readPhotoBytes(id: String): ByteArray?

    fun readThumbBytes(id: String, maxEdge: Int): ByteArray?
}

/** 平台侧接收落地：保存收到的照片。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncPhotoSink", exact = true)
interface SyncPhotoSink {
    fun saveIncoming(bytes: ByteArray, suggestedName: String, dateTaken: Long): Boolean
}

/** 收到但尚未由 UI 决定接收/丢弃的照片。bytes 在内存中持有。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("IncomingTransfer", exact = true)
data class IncomingTransfer(
    val transferId: String,
    val fromPeer: PeerInfo,
    val meta: SyncPhotoMeta,
    val bytes: ByteArray,
)

/** 发起的传输状态。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("OutgoingTransfer", exact = true)
data class OutgoingTransfer(
    val transferId: String,
    val toPeer: PeerInfo,
    val meta: SyncPhotoMeta,
    val done: Boolean,
    val error: String? = null,
)

/** UI 观察的聚合状态。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("SyncState", exact = true)
data class SyncState(
    val running: Boolean = false,
    val peers: List<PeerInfo> = emptyList(),
    val incoming: List<IncomingTransfer> = emptyList(),
    val outgoing: List<OutgoingTransfer> = emptyList(),
    /**
     * 本机已经允许哪些对端**浏览/取片**（键 = 对端 IP）。
     *
     * 为什么要有这一层：`hello` 握手不证明任何事 —— 同一局域网里任何设备发一个像样的握手，
     * 服务端就会把整本相册列给它、并随叫随取原图。而隐私政策写的是"两端都确认"。
     * 接收侧本来就有 `acceptIncoming`，缺的是**供片这一侧的确认**，就是这里。
     */
    val browseGrants: List<String> = emptyList(),
    /** 来问过、但还没被允许的对端（界面据此给一颗「允许浏览」）。 */
    val browseAsked: List<BrowseAsk> = emptyList(),
    val lastError: String? = null,
)

/** 一个想浏览本机照片、还没被允许的对端。 */
data class BrowseAsk(val host: String, val name: String)

/** 对方没被允许浏览时回的那一句。对端界面直接显示它，所以要是人话。 */
internal const val NEED_BROWSE_GRANT = "对方还没允许本机浏览其照片"

/**
 * 服务端要不要答这一趟浏览/取片请求。**默认拒绝**：没在 [SyncState.browseGrants] 里出现过就不答。
 *
 * 做成顶层纯函数，是因为这条判据值得被穷举测过（空清单、命中、只有别的键命中、
 * 撤销之后再问）—— 它守着的是"整本相册对局域网裸奔"这个后果。
 */
internal fun mayServeBrowse(grants: List<String>, host: String): Boolean =
    host.isNotBlank() && host in grants

/**
 * 这一页之后还有没有下一页。**协议里没有 `total` / `hasMore`**，唯一依据是「本页条数 < 请求的 limit」。
 *
 * 为什么做成纯函数并且测过（`SyncRemotePagingTest`）：这条判据错一端的代价不一样 ——
 * 判多了只是多问一次空页；判少了则是「相册有 300 张，界面永远只显示前 60 张」，
 * 而界面上看不出少了什么。所以只有**明确的短页**才允许收工：
 * 条数正好等于 `limit` 时即便真的到底了也要再问一次（相册张数恰好是 limit 的整数倍就是这种情形）。
 *
 * `limit <= 0` 一律当末页：服务端对 `limit <= 0` 回空表，把「空表」再读成「还有更多」会变成死循环。
 */
fun hasMoreRemotePages(pageSize: Int, limit: Int): Boolean =
    limit > 0 && pageSize >= limit

/**
 * 把新一页接在已加载的后面，**按 id 去重**。
 *
 * 去重不是洁癖：翻页用的是 offset，而对端相册是活的 —— 用户在两页之间拍了一张新照片，
 * 后面每一页的 offset 都会整体后移一格，于是上一页最后一张会在下一页再出现一次。
 * 界面那层用 `LazyVerticalGrid(key = { it.id })`，重复 key 会直接抛
 * `IllegalArgumentException: Key ... was already used`（不是"显示两张一样的"，是整页崩掉）。
 * 所以去重放在这里，而不是指望 UI 记得过滤。
 */
fun appendRemotePage(
    loaded: List<SyncPhotoMeta>,
    page: List<SyncPhotoMeta>,
): List<SyncPhotoMeta> {
    if (page.isEmpty()) return loaded
    val seen = loaded.mapTo(HashSet(loaded.size + page.size)) { it.id }
    val out = ArrayList<SyncPhotoMeta>(loaded.size + page.size)
    out.addAll(loaded)
    for (meta in page) {
        if (seen.add(meta.id)) out.add(meta)
    }
    return out
}

/** UDP 广播载荷形态（PROTO_MAGIC 之后的 JSON）。 */
@Serializable
internal data class BeaconPayload(
    val id: String,
    val name: String,
    val platform: String,
    val tcpPort: Int,
)

/**
 * 双端互通编排层。
 *
 * - [source] 为 null 时只收不发（list/get 回 not supported）。
 * - [sink] 为 null 时只发不收（send-photo 回 not supported）。
 * - 所有网络异常都收敛到 [SyncState.lastError]，不抛到 UI 层。
 */
@ObjCName("SyncHub", exact = true)
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
class SyncHub(
    private val deviceId: String,
    private val deviceName: String,
    private val platform: String,
    private val source: SyncPhotoSource?,
    private val sink: SyncPhotoSink?,
    private val tcpPort: Int = SyncProtocol.TCP_PORT,
    // 保留参数以冻结构造契约；当前 DiscoveryBeacon 固定使用 SyncProtocol.UDP_PORT。
    @Suppress("unused")
    private val udpPort: Int = SyncProtocol.UDP_PORT,
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val server = SyncServer()
    private val client = SyncClient()
    private val beacon = DiscoveryBeacon()

    private var scope: CoroutineScope? = null

    @Volatile
    private var running: Boolean = false

    private val handler = SyncServerHandler { conn, host -> handleConnection(conn, host) }

    /** 起 TCP server + UDP announce + UDP listen + TTL 清理。非阻塞。 */
    fun start() {
        if (running) return
        running = true
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc

        try {
            server.start(tcpPort, handler)
        } catch (e: Throwable) {
            setError(e)
        }
        try {
            beacon.startListen { payload, host -> onBeacon(payload, host) }
        } catch (e: Throwable) {
            setError(e)
        }
        try {
            beacon.startAnnounce(announcePayload())
        } catch (e: Throwable) {
            setError(e)
        }

        sc.launch {
            while (running) {
                delay(PEER_SWEEP_MS)
                prunePeers()
            }
        }
        _state.update { it.copy(running = true) }
    }

    /** 停掉全部后台活动。 */
    fun stop() {
        if (!running) return
        running = false
        scope?.cancel()
        scope = null
        try {
            server.stop()
        } catch (e: Throwable) {
            setError(e)
        }
        try {
            beacon.stop()
        } catch (e: Throwable) {
            setError(e)
        }
        _state.update { it.copy(running = false) }
    }

    /**
     * 催一轮广播。之前这里调 startAnnounce，而平台实现的 startAnnounce 会先 stopAnnounce
     * 再新建 socket 和新线程 —— 于是每次 ON_RESUME 都拆建一次信标，还会留出一个
     * 没人广播的空窗，恰好是对方发现不到你的时候。
     */
    fun refreshPeers() {
        try {
            beacon.nudgeAnnounce()
        } catch (e: Throwable) {
            setError(e)
        }
    }

    /**
     * 拉取对端照片列表。
     *
     * `null` = **没读到**（连不上、握手失败、对端回了错误），原因写在 `lastError`；
     * 空表 = 读到了、对面确实没有可分享的照片。以前两者都是空表，于是"对方没开机"
     * 在界面上被写成「对方没有可读取的照片」——一句没有依据的结论。
     */
    suspend fun fetchPeerPhotos(
        peer: PeerInfo,
        offset: Int = 0,
        limit: Int = 60,
    ): List<SyncPhotoMeta>? = withContext(Dispatchers.Default) {
        var conn: SyncConnection? = null
        try {
            conn = client.connect(peer.host, peer.tcpPort, CONNECT_TIMEOUT_MS)
            handshake(conn)
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_LIST_PHOTOS, offset = offset, limit = limit))
            val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during list-photos")
            val header = decodeHeaderOrThrow(frame)
            if (!header.ok) {
                setErrorMessage(header.error ?: "list-photos failed")
                null
            } else {
                header.items
            }
        } catch (e: CancellationException) {
            // 取消是用户关页面 / stop() 发出来的，不是「网络坏了」：吞了它这一趟会装作
            // 正常返回，上层的 loading 态与循环都不肯停。
            throw e
        } catch (e: Throwable) {
            setError(e)
            null
        } finally {
            conn?.close()
        }
    }

    /** 拉取对端缩略图字节。失败返回 null 并写入 lastError。 */
    suspend fun fetchPeerThumb(
        peer: PeerInfo,
        photoId: String,
        maxEdge: Int = 512,
    ): ByteArray? = withContext(Dispatchers.Default) {
        var conn: SyncConnection? = null
        try {
            conn = client.connect(peer.host, peer.tcpPort, CONNECT_TIMEOUT_MS)
            handshake(conn)
            FrameIO.writeFrame(
                conn,
                FrameHeader(type = TYPE_GET_THUMB, photoId = photoId, maxEdge = maxEdge),
            )
            val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during get-thumb")
            val header = decodeHeaderOrThrow(frame)
            if (!header.ok || frame.body == null) {
                setErrorMessage(header.error ?: "get-thumb failed")
                null
            } else {
                frame.body
            }
        } catch (e: CancellationException) {
            // 取消是用户关页面 / stop() 发出来的，不是「网络坏了」：吞了它这一趟会装作
            // 正常返回，上层的 loading 态与循环都不肯停。
            throw e
        } catch (e: Throwable) {
            setError(e)
            null
        } finally {
            conn?.close()
        }
    }

    /**
     * 一次连接取多张缩略图。
     *
     * 之前每个格子各自 [fetchPeerThumb]：浏览一页 60 张就是 60 次 TCP 连接 + 60 轮握手，
     * 对端还得为每格开一个线程。服务端 `handleConnection` 本来就是在一个连接上循环收帧的，
     * 所以这只是把客户端的用法改对，不动协议。
     *
     * 返回的 map 只含成功的项；连不上/握手失败时返回已拿到的部分并写入 lastError。
     */
    suspend fun fetchPeerThumbs(
        peer: PeerInfo,
        photoIds: List<String>,
        maxEdge: Int = 512,
    ): Map<String, ByteArray> = withContext(Dispatchers.Default) {
        val out = LinkedHashMap<String, ByteArray>(photoIds.size)
        if (photoIds.isEmpty()) return@withContext out
        var conn: SyncConnection? = null
        try {
            conn = client.connect(peer.host, peer.tcpPort, CONNECT_TIMEOUT_MS)
            handshake(conn)
            for (id in photoIds) {
                FrameIO.writeFrame(
                    conn,
                    FrameHeader(type = TYPE_GET_THUMB, photoId = id, maxEdge = maxEdge),
                )
                val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during get-thumb")
                val header = decodeHeaderOrThrow(frame)
                val body = frame.body
                if (header.ok && body != null) out[id] = body
            }
            out
        } catch (e: CancellationException) {
            // 取消是用户关页面 / stop() 发出来的，不是「网络坏了」：吞了它这一趟会装作
            // 正常返回，上层的 loading 态与循环都不肯停。
            throw e
        } catch (e: Throwable) {
            setError(e)
            out
        } finally {
            conn?.close()
        }
    }

    /** 下载原图并交给 [sink] 落地。需要 sink。 */
    suspend fun downloadPhoto(peer: PeerInfo, meta: SyncPhotoMeta): Boolean =
        withContext(Dispatchers.Default) {
            val sink = this@SyncHub.sink
            if (sink == null) {
                setErrorMessage("sink not configured")
                return@withContext false
            }
            var conn: SyncConnection? = null
            try {
                conn = client.connect(peer.host, peer.tcpPort, CONNECT_TIMEOUT_MS)
                handshake(conn)
                FrameIO.writeFrame(conn, FrameHeader(type = TYPE_GET_PHOTO, photoId = meta.id))
                val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during get-photo")
                val header = decodeHeaderOrThrow(frame)
                val bytes = frame.body
                if (!header.ok || bytes == null) {
                    setErrorMessage(header.error ?: "get-photo failed")
                    false
                } else {
                    sink.saveIncoming(bytes, meta.name, meta.dateTaken)
                }
            } catch (e: CancellationException) {
                // 取消是用户关页面 / stop() 发出来的，不是「网络坏了」：吞了它这一趟会装作
                // 正常返回，上层的 loading 态与循环都不肯停。
                throw e
            } catch (e: Throwable) {
                setError(e)
                false
            } finally {
                conn?.close()
            }
        }

    /** 发送一张照片。需要 source（提供 meta/bytes）由调用方给出 bytes。 */
    suspend fun sendPhoto(peer: PeerInfo, meta: SyncPhotoMeta, bytes: ByteArray): Boolean {
        val transferId = "out-${meta.id}-${syncNowMs()}"
        addOutgoing(OutgoingTransfer(transferId, peer, meta, done = false))
        val ok = withContext(Dispatchers.Default) {
            var conn: SyncConnection? = null
            try {
                conn = client.connect(peer.host, peer.tcpPort, CONNECT_TIMEOUT_MS)
                handshake(conn)
                FrameIO.writeFrame(
                    conn,
                    FrameHeader(type = TYPE_SEND_PHOTO, meta = meta),
                    bytes,
                )
                val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during send-photo")
                val header = decodeHeaderOrThrow(frame)
                if (!header.ok) {
                    setErrorMessage(header.error ?: "send-photo failed")
                    false
                } else {
                    true
                }
            } catch (e: CancellationException) {
                // 取消是用户关页面 / stop() 发出来的，不是「网络坏了」：吞了它这一趟会装作
                // 正常返回，上层的 loading 态与循环都不肯停。
                throw e
            } catch (e: Throwable) {
                setError(e)
                false
            } finally {
                conn?.close()
            }
        }
        finishOutgoing(transferId, if (ok) null else _state.value.lastError ?: "send failed")
        return ok
    }

    /** 接受一张在途照片：交给 sink 落地并从 incoming 移除。 */
    fun acceptIncoming(transferId: String) {
        val transfer = _state.value.incoming.firstOrNull { it.transferId == transferId } ?: return
        val sink = this.sink
        if (sink == null) {
            setErrorMessage("sink not configured")
            return
        }
        try {
            val saved = sink.saveIncoming(transfer.bytes, transfer.meta.name, transfer.meta.dateTaken)
            if (saved) {
                removeIncoming(transferId)
            } else {
                setErrorMessage("save failed")
            }
        } catch (e: Throwable) {
            setError(e)
        }
    }

    /** 丢弃一张在途照片。 */
    fun rejectIncoming(transferId: String) {
        removeIncoming(transferId)
    }

    // ---- 服务端 ----

    private fun handleConnection(conn: SyncConnection, host: String) {
        try {
            val helloFrame = FrameIO.readFrame(conn) ?: return
            val hello = decodeHeaderOrThrow(helloFrame)
            if (hello.type != TYPE_HELLO) {
                FrameIO.writeFrame(conn, FrameHeader(type = hello.type, ok = false, error = "unknown type"))
                return
            }
            var identity = peerFor(host, hello)
            FrameIO.writeFrame(conn, helloHeader())
            while (true) {
                val frame = FrameIO.readFrame(conn) ?: break
                val header = FrameCodec.decodeHeader(frame) ?: continue
                when (header.type) {
                    TYPE_HELLO -> {
                        // 同一条连接上再握手：更新身份，别让它停在第一次握手看到的样子。
                        identity = peerFor(host, header)
                        FrameIO.writeFrame(conn, helloHeader())
                    }
                    TYPE_LIST_PHOTOS -> ifAllowed(conn, host, identity.name, header) { handleListPhotos(conn, header) }
                    TYPE_GET_THUMB -> ifAllowed(conn, host, identity.name, header) { handleGetThumb(conn, header) }
                    TYPE_GET_PHOTO -> ifAllowed(conn, host, identity.name, header) { handleGetPhoto(conn, header) }
                    TYPE_SEND_PHOTO -> handleSendPhoto(conn, header, frame.body, identity)
                    TYPE_BYE -> break
                    else -> FrameIO.writeFrame(
                        conn,
                        FrameHeader(type = header.type, ok = false, error = "unknown type"),
                    )
                }
            }
        } catch (e: Throwable) {
            setError(e)
        } finally {
            conn.close()
        }
    }

    /**
     * 浏览/取片这一类"读我相册"的请求，先过本机授权这一关。
     *
     * 没允许过就把请求记成 [SyncState.browseAsked]（界面据此给一颗「允许浏览」），
     * 并回一句对方看得懂的话 —— 不许静默：静默在对端界面上长得和"网络坏了"一模一样。
     */
    private fun ifAllowed(
        conn: SyncConnection,
        host: String,
        peerName: String,
        header: FrameHeader,
        serve: () -> Unit,
    ) {
        if (mayServeBrowse(_state.value.browseGrants, host)) {
            serve()
            return
        }
        _state.update {
            if (it.browseAsked.any { a -> a.host == host }) it
            else it.copy(browseAsked = it.browseAsked + BrowseAsk(host, peerName))
        }
        FrameIO.writeFrame(
            conn,
            FrameHeader(type = header.type, ok = false, error = NEED_BROWSE_GRANT),
        )
    }

    /** 允许某个对端浏览/取本机照片。键 = 对端 IP。 */
    fun grantBrowse(host: String) {
        if (host.isBlank()) return
        _state.update {
            it.copy(
                browseGrants = (it.browseGrants + host).distinct(),
                browseAsked = it.browseAsked.filterNot { a -> a.host == host },
            )
        }
    }

    /**
     * 把一批授权灌进新实例。
     *
     * 存在的理由只有一个：**改名会把 hub 整个重建**（`SyncHub.deviceName` 是构造期冻结的，
     * 没有 setter，见 `SyncManager.rename`）。原实现是 `hub.stop()` + 新建 —— 而
     * [SyncState.browseGrants] 只活在内存里，于是用户改一次设备名，之前逐台点过
     * 「允许浏览」的授权**全部消失**：对方还在同一台设备、同一个 IP 上，
     * 却突然读不了，界面上还多出一条「想浏览本机照片」等着再点一次。
     * 那不是安全（收回授权是用户主动的动作），是一个没人预期的副作用。
     *
     * 为什么不用构造参数：`SyncHub` 的构造签名被 iOS 壳按位置引用
     * （`ios/SuiPaiIOS/SyncBridge.swift`），而 Kotlin 的默认值不导出给 Swift ——
     * 加一个参数会让那一侧在 Mac 上编不过，而这里**没有任何编译证据**能提前发现。
     * 用方法灌入是纯增量，两端都不受影响。
     *
     * 只收授权的**结果**、不重放 `browseAsked`：重建之后的"谁在问"由新连接重新产生，
     * 拿旧清单去糊会把一台已经走掉的设备留在界面上。
     */
    fun restoreBrowseGrants(hosts: List<String>) {
        val clean = hosts.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        _state.update { it.copy(browseGrants = (it.browseGrants + clean).distinct()) }
    }

    /** 收回允许。正在传的那一趟管到底，之后的请求一律回绝。 */
    fun revokeBrowse(host: String) {
        _state.update { it.copy(browseGrants = it.browseGrants.filterNot { g -> g == host }) }
    }

    private fun handleListPhotos(conn: SyncConnection, header: FrameHeader) {
        val source = this.source
        if (source == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_LIST, ok = false, error = "not supported"))
            return
        }
        val items = try {
            source.listPhotos(header.offset, header.limit)
        } catch (e: Throwable) {
            setError(e)
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_LIST, ok = false, error = "list failed"))
            return
        }
        FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_LIST, items = items))
    }

    private fun handleGetThumb(conn: SyncConnection, header: FrameHeader) {
        val source = this.source
        val photoId = header.photoId
        if (source == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, ok = false, error = "not supported"))
            return
        }
        if (photoId == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, ok = false, error = "missing photoId"))
            return
        }
        val bytes = try {
            source.readThumbBytes(photoId, header.maxEdge)
        } catch (e: Throwable) {
            setError(e)
            null
        }
        if (bytes == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, photoId = photoId, ok = false, error = "not found"))
        } else {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, photoId = photoId), bytes)
        }
    }

    private fun handleGetPhoto(conn: SyncConnection, header: FrameHeader) {
        val source = this.source
        val photoId = header.photoId
        if (source == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, ok = false, error = "not supported"))
            return
        }
        if (photoId == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, ok = false, error = "missing photoId"))
            return
        }
        val bytes = try {
            source.readPhotoBytes(photoId)
        } catch (e: Throwable) {
            setError(e)
            null
        }
        if (bytes == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, photoId = photoId, ok = false, error = "not found"))
        } else {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_PHOTO_BYTES, photoId = photoId), bytes)
        }
    }

    private fun handleSendPhoto(conn: SyncConnection, header: FrameHeader, body: ByteArray?, from: PeerInfo) {
        if (sink == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_ERROR, ok = false, error = "not supported"))
            return
        }
        val meta = header.meta
        if (meta == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_ERROR, ok = false, error = "missing meta"))
            return
        }
        if (body == null) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_ERROR, ok = false, error = "missing body"))
            return
        }
        if (body.size.toLong() > SyncProtocol.MAX_BODY) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_ERROR, ok = false, error = "body too large"))
            return
        }
        // 在途照片全部驻留内存，直到用户在 UI 上决定收还是丢。对端连着发就能把本机 RAM 打满，
        // 所以条数和总字节都要封顶，超了直接回 send-error 让对端知道该等。
        val transferId = "in-${meta.id}-${syncNowMs()}-${Random.nextInt(1 shl 20)}"
        _state.update { current ->
            val heldBytes = current.incoming.sumOf { it.bytes.size.toLong() }
            val fits = current.incoming.size < MAX_INCOMING_ITEMS &&
                heldBytes + body.size <= MAX_INCOMING_BYTES
            if (fits) {
                current.copy(
                    incoming = current.incoming + IncomingTransfer(transferId, from, meta, body),
                )
            } else {
                current
            }
        }
        // 用「有没有真的进队列」来回答，而不是凭 update 的 lambda 是否执行过——
        // update 在 CAS 竞争下可能重跑，标志位会不可靠。
        if (_state.value.incoming.any { it.transferId == transferId }) {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_OK))
        } else {
            FrameIO.writeFrame(conn, FrameHeader(type = TYPE_SEND_ERROR, ok = false, error = "busy"))
        }
    }

    // ---- 发现 ----

    private fun onBeacon(payload: String, host: String) {
        val beacon = try {
            SyncJson.decodeFromString(BeaconPayload.serializer(), payload)
        } catch (_: Throwable) {
            return
        }
        if (beacon.id == deviceId) return
        val peer = PeerInfo(
            id = beacon.id,
            name = beacon.name,
            platform = beacon.platform,
            host = host,
            tcpPort = if (beacon.tcpPort > 0) beacon.tcpPort else tcpPort,
            lastSeenMs = wallNowMs(),
        )
        _state.update { current ->
            current.copy(peers = (current.peers.filterNot { it.id == peer.id } + peer).sortedBy { it.id })
        }
    }

    private fun prunePeers() {
        val cutoff = wallNowMs() - PEER_TTL_MS
        _state.update { current ->
            val alive = current.peers.filter { it.lastSeenMs >= cutoff }
            if (alive.size == current.peers.size) current else current.copy(peers = alive)
        }
    }

    private fun announcePayload(): String =
        SyncProtocol.PROTO_MAGIC + SyncJson.encodeToString(
            BeaconPayload.serializer(),
            BeaconPayload(deviceId, deviceName, platform, tcpPort),
        )

    // ---- 客户端辅助 ----

    private fun handshake(conn: SyncConnection) {
        FrameIO.writeFrame(conn, helloHeader())
        val frame = FrameIO.readFrame(conn) ?: throw SyncException("peer closed during handshake")
        val header = decodeHeaderOrThrow(frame)
        if (header.type != TYPE_HELLO || !header.ok) throw SyncException("bad hello: ${header.error ?: header.type}")
    }

    private fun helloHeader(): FrameHeader = FrameHeader(
        type = TYPE_HELLO,
        deviceId = deviceId,
        name = deviceName,
        platform = platform,
    )

    private fun decodeHeaderOrThrow(frame: SyncFrame): FrameHeader =
        FrameCodec.decodeHeader(frame) ?: throw SyncException("invalid frame header")

    /**
     * 这条 TCP 连接的对端是谁。按可靠性依次取：
     * 1. UDP 信标里已按 IP 认出的设备（带真实 id，能对上设备列表）；
     * 2. 对方在 TCP `hello` 里自报的 name/platform —— 信标到不了（跨网段、防火墙挡 UDP）时
     *    这是唯一知道「谁发来的」的机会。以前这一步被丢掉了，来件一律显示「未知设备」。
     * 3. 两者都没有才退回占位。
     *
     * 之前只按 IP 取 [SyncState.peers] 的第一个（多设备在网时会挂错人），后来改成只按 IP 匹配
     * 信标结果，仍然漏掉了 hello 自报身份这条更好的信息。
     */
    private fun peerFor(host: String, hello: FrameHeader): PeerInfo {
        _state.value.peers.firstOrNull { host.isNotEmpty() && it.host == host }?.let { return it }
        return PeerInfo(
            id = hello.deviceId?.takeIf { it.isNotBlank() } ?: "unknown",
            name = hello.name?.takeIf { it.isNotBlank() } ?: "未知设备",
            platform = hello.platform?.takeIf { it.isNotBlank() } ?: "unknown",
            host = host,
            tcpPort = tcpPort,
            lastSeenMs = wallNowMs(),
        )
    }

    private fun addOutgoing(transfer: OutgoingTransfer) {
        _state.update { it.copy(outgoing = it.outgoing + transfer) }
    }

    private fun finishOutgoing(transferId: String, error: String?) {
        _state.update { current ->
            current.copy(
                outgoing = current.outgoing.map {
                    if (it.transferId == transferId) it.copy(done = true, error = error) else it
                },
            )
        }
    }

    private fun removeIncoming(transferId: String) {
        _state.update { current ->
            current.copy(incoming = current.incoming.filterNot { it.transferId == transferId })
        }
    }

    private fun setError(e: Throwable) {
        setErrorMessage(e.message ?: e::class.simpleName ?: "unknown error")
    }

    private fun setErrorMessage(message: String) {
        _state.update { it.copy(lastError = message) }
    }

    private companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_LIST_PHOTOS = "list-photos"
        const val TYPE_PHOTO_LIST = "photo-list"
        const val TYPE_GET_THUMB = "get-thumb"
        const val TYPE_GET_PHOTO = "get-photo"
        const val TYPE_PHOTO_BYTES = "photo-bytes"
        const val TYPE_SEND_PHOTO = "send-photo"
        const val TYPE_SEND_OK = "send-ok"
        const val TYPE_SEND_ERROR = "send-error"
        const val TYPE_BYE = "bye"


        const val CONNECT_TIMEOUT_MS = 5000
        const val PEER_TTL_MS = 8000L
        const val PEER_SWEEP_MS = 1000L

        /** 在途（未决定收/丢）照片的封顶：条数与总字节。单帧 body 最大 64MB。 */
        const val MAX_INCOMING_ITEMS = 8
        const val MAX_INCOMING_BYTES = 160L * 1024 * 1024
    }
}