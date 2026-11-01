package com.suipai.app.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suipai.core.sync.FrameCodec
import com.suipai.core.sync.FrameHeader
import com.suipai.core.sync.FrameIO
import com.suipai.core.sync.PeerInfo
import com.suipai.core.sync.SyncClient
import com.suipai.core.sync.SyncConnection
import com.suipai.core.sync.SyncHub
import com.suipai.core.sync.SyncPhotoMeta
import com.suipai.core.sync.SyncPhotoSink
import com.suipai.core.sync.SyncPhotoSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 互通层的**跨实现**端到端测试：另一端是 `tools/sync_peer.py`（按 SYNC.md 独立写的 Python 实现），
 * 不是 Kotlin 那边翻译过来的。补的是交接文档 §4-P0 第 3 条：以前互通只有单机帧编解码单测，
 * 「两个实现真能对上」从来没证过。
 *
 * 跑之前宿主机要先接上：
 *
 *     adb -s <dev> reverse tcp:47481 tcp:47481
 *     python tools/sync_peer.py serve --port 47481 --dir <jpg 目录> --journal <路径>
 *
 * 然后：
 *     ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.suipai.app.sync.SyncPeerInteropTest
 *
 * Python 那头没起来时，用例会给出一句能照着敲的提示，而不是六个「连接被拒」。
 */
private fun jpeg(edge: Int): ByteArray {
    val bmp = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
    bmp.recycle()
    return out.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class SyncPeerInteropTest {

    private val args = InstrumentationRegistry.getArguments()
    private val peerPort: Int = args.getString("peerPort", "47481").toInt()
    private val hubPort: Int = args.getString("hubPort", "47482").toInt()

    private class FakeSource : SyncPhotoSource {
        private val metas = (0 until 3).map { i ->
            SyncPhotoMeta(
                id = "$i",
                name = "mine-$i.jpg",
                dateTaken = 1700000000000L + i,
                width = 64,
                height = 32,
                sizeBytes = 1024L,
            )
        }
        var listCalls = 0
        var thumbCalls = 0

        override fun listPhotos(offset: Int, limit: Int): List<SyncPhotoMeta> {
            listCalls++
            return if (limit <= 0) emptyList() else metas.drop(offset).take(limit)
        }

        override fun readPhotoBytes(id: String): ByteArray? = if (id.toIntOrNull() != null) jpeg(40) else null

        override fun readThumbBytes(id: String, maxEdge: Int): ByteArray? {
            thumbCalls++
            return if (id.toIntOrNull() != null) jpeg(16) else null
        }
    }

    private class FakeSink : SyncPhotoSink {
        val saved = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun saveIncoming(bytes: ByteArray, suggestedName: String, dateTaken: Long): Boolean {
            saved += suggestedName to bytes.size
            return true
        }
    }

    private lateinit var source: FakeSource
    private lateinit var sink: FakeSink
    private lateinit var hub: SyncHub

    /** 对端（Python）的坐标：设备侧 127.0.0.1 经 adb reverse 打到宿主机。 */
    private fun peer() = PeerInfo(
        id = "py-peer",
        name = "PyPeer",
        platform = "python",
        host = "127.0.0.1",
        tcpPort = peerPort,
        lastSeenMs = System.currentTimeMillis(),
    )

    @Before
    fun setUp() {
        source = FakeSource()
        sink = FakeSink()
        hub = SyncHub(
            deviceId = "android-test-device",
            deviceName = "TestDevice",
            platform = "android",
            source = source,
            sink = sink,
            tcpPort = hubPort,
        )
        hub.start()
        // 服务端对「读我相册」这一类请求默认拒绝（SyncHub.ifAllowed 那道浏览授权闸）。
        // 少了这一句，本类的 list-photos 用例拿到的是
        // `{type:"list-photos", ok:false, error:"对方还没允许本机浏览其照片"}`，
        // 第一条断言就红 —— 而这条红灯一直没人看见（GOAL.md：仪器化只编译过、没跑过）。
        // 「不允许时必须被拒」由 unGrantedClientIsRefusedAndToldWhy 单独钉住，不是把闸拆掉。
        hub.grantBrowse("127.0.0.1")
        // start() 里的 bind 在别的线程上；等到真的能 accept 为止。
        awaitUnit {
            runCatching { SyncClient().connect("127.0.0.1", hubPort, 300).close() }.isSuccess
        }
    }

    @After
    fun tearDown() {
        hub.stop()
    }

    /**
     * 「读失败」与「对端没有照片」是两个值（§34 立的规矩）：这里不能 `?: emptyList()`，
     * 那样一旦读取失败就会伪装成空相册，而下面那些断言会把它读成"对端真的没照片"。
     */
    private fun List<SyncPhotoMeta>?.orFail(what: String): List<SyncPhotoMeta> =
        this ?: throw AssertionError("$what 读取失败：lastError=" + hub.state.value.lastError)

    private fun assumePeerUp() {
        val reachable = runCatching { SyncClient().connect("127.0.0.1", peerPort, 1500).close() }.isSuccess
        if (!reachable) {
            throw AssertionError(
                "Python 对端不可达 127.0.0.1:$peerPort。先跑：\n" +
                    "  adb -s emulator-5554 reverse tcp:$peerPort tcp:$peerPort\n" +
                    "  python tools/sync_peer.py serve --port $peerPort --dir <jpg目录> --journal <路径>",
            )
        }
    }

    // ---- 设备作为客户端，打独立的 Python 服务端 ----

    @Test
    fun peerLibraryReadsBackWithIndependentMetadata() {
        assumePeerUp()
        runBlocking {
            val items = hub.fetchPeerPhotos(peer(), offset = 0, limit = 60).orFail("对端相册")
            assertNull(hub.state.value.lastError)
            assertTrue("对端至少要有 1 张照片，拿到 ${items.size}", items.isNotEmpty())
            assertTrue("Python 侧要能把 JPEG 的宽高解出来", items.all { it.width > 0 && it.height > 0 })
            assertTrue("name 应当以 .jpg 结尾", items.all { it.name.endsWith(".jpg") })
            assertTrue("sizeBytes 非 0", items.all { it.sizeBytes > 0 })
        }
    }

    @Test
    fun batchedThumbsComeBackOverOneCall() {
        assumePeerUp()
        runBlocking {
            val ids = hub.fetchPeerPhotos(peer(), 0, 60).orFail("对端相册").map { it.id }
            val thumbs = hub.fetchPeerThumbs(peer(), ids, maxEdge = 256)
            assertEquals("每张都要拿到缩略图", ids.toSet(), thumbs.keys)
            thumbs.values.forEach { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("缩略图字节要能解码成 Bitmap", bmp)
                bmp?.recycle()
            }
            // 「一次调用一条连接」由宿主机上的 journal 断言，见 run_sync_interop.sh
        }
    }

    @Test
    fun downloadPhotoGoesThroughTheSink() {
        assumePeerUp()
        runBlocking {
            val meta = hub.fetchPeerPhotos(peer(), 0, 60).orFail("对端相册").first()
            assertTrue("下载失败：${hub.state.value.lastError}", hub.downloadPhoto(peer(), meta))
            assertEquals(1, sink.saved.size)
            assertEquals(meta.name, sink.saved[0].first)
            assertEquals(meta.sizeBytes.toInt(), sink.saved[0].second)
        }
    }

    @Test
    fun sendPhotoReachesTheIndependentPeer() {
        assumePeerUp()
        runBlocking {
            val bytes = jpeg(48)
            val meta = SyncPhotoMeta("t-1", "from-android.jpg", 1700000001000L, 48, 48, bytes.size.toLong())
            assertTrue("发送失败：${hub.state.value.lastError}", hub.sendPhoto(peer(), meta, bytes))
            val outgoing = hub.state.value.outgoing.single()
            assertTrue("outgoing 要标记完成", outgoing.done)
            assertNull(outgoing.error)
        }
    }

    // ---- 发现（UDP）----

    @Test
    fun beaconIsParsedAndSelfBeaconIgnored() {
        runBlocking {
            // 自己的信标反复发：监听线程是在 start() 里另起线程 bind 的，第一包很可能丢。
            val found = awaitBeacon("py-other", "Other", platform = "python", tcpPort = 47490) {
                sendBeacon(id = "android-test-device", name = "myself")
            }
            assertTrue("外机信标没进列表：${hub.state.value.peers.map { it.id }}", found)
            assertTrue(
                "自己发的信标绝不能进列表（否则会自己连自己）",
                hub.state.value.peers.none { it.id == "android-test-device" },
            )
            val peer = hub.state.value.peers.first { it.id == "py-other" }
            assertEquals("Other", peer.name)
            assertEquals("python", peer.platform)
            assertEquals(47490, peer.tcpPort)
            assertEquals("信标来源地址要成为 host", "127.0.0.1", peer.host)
        }
    }

    /** TTL 8s + 清扫 1s：不再广播的对端必须从列表里消失，否则 UI 会一直挂着死设备。 */
    @Test
    fun beaconExpiresAfterTtl() {
        runBlocking {
            assertTrue("信标没被收到", awaitBeacon("py-expiring", "Expiring"))
            val gone = awaitUnit(timeoutMs = 16_000) { hub.state.value.peers.none { it.id == "py-expiring" } }
            assertTrue("超过 TTL 还没被清掉：${hub.state.value.peers.map { it.id }}", gone)
        }
    }

    // ---- 设备自己的服务端（Python 那一端会连进来，这里先在 Kotlin 侧验语义）----

    @Test
    fun ourServerAnswersUnknownTypeWithNamedError() {
        runBlocking {
            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            conn.withConn {
                handshake(it)
                FrameIO.writeFrame(it, FrameHeader(type = "totally-made-up"))
                val reply = readHeader(it)
                assertEquals("totally-made-up", reply.type)
                assertEquals(false, reply.ok)
                assertEquals("unknown type", reply.error)
            }
        }
    }

    @Test
    fun ourServerListsAndServesThumbsToAClient() {
        runBlocking {
            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            conn.withConn {
                handshake(it)
                FrameIO.writeFrame(it, FrameHeader(type = "list-photos", offset = 1, limit = 2))
                val list = readHeader(it)
                assertEquals("photo-list", list.type)
                assertEquals(listOf("1", "2"), list.items.map { m -> m.id })
                assertEquals(1, source.listCalls)

                FrameIO.writeFrame(it, FrameHeader(type = "get-thumb", photoId = "1", maxEdge = 128))
                val frame = FrameIO.readFrame(it) ?: throw AssertionError("no reply")
                val header = FrameCodec.decodeHeader(frame) ?: throw AssertionError("bad header")
                assertEquals("1", header.photoId)
                assertEquals(true, header.ok)
                assertNotNull(frame.body)

                FrameIO.writeFrame(it, FrameHeader(type = "get-thumb", photoId = "no-such-id"))
                val missing = readHeader(it)
                assertEquals(false, missing.ok)
                assertEquals("not found", missing.error)
            }
        }
    }

    /**
     * 默认拒绝这一侧的对照用例：`setUp` 的 `grantBrowse` 只是把常规路径打开，
     * 不是把隐私闸拆掉 —— 所以这里**收回**授权，再发同一条 `list-photos`。
     *
     * 为什么必须单独有一条：把 `grantBrowse` 加进 setUp 之后，
     * 「没被允许时整本相册不许读」就再也没有仪器化证据了，而这句承诺
     * （`store/privacy-policy.html` 的「两端都确认」）一旦回退，所有其它用例照样全绿。
     */
    @Test
    fun unGrantedClientIsRefusedAndToldWhy() {
        runBlocking {
            hub.revokeBrowse("127.0.0.1")
            assertTrue("授权应已被收回", hub.state.value.browseGrants.isEmpty())

            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            conn.withConn {
                handshake(it)

                FrameIO.writeFrame(it, FrameHeader(type = "list-photos", offset = 0, limit = 2))
                val list = readHeader(it)
                assertEquals("list-photos", list.type, "拒绝也必须是同名 type，对端才知道拒的是哪一条")
                assertEquals("没被允许就读到相册 = 默认拒绝失效", false, list.ok)
                // 这句拒绝语是给对方用户看的（对端界面直接显示 error 字段），
                // 所以这里逐字比对，不写成 contains("允许")。
                // NEED_BROWSE_GRANT 在 shared 里是 internal，跨模块拿不到，故写死同一句：
                // 改文案时这条会红，正是想要的（文案改了就该有人回来确认对端显示没坏）。
                assertEquals("对方还没允许本机浏览其照片", list.error)
                assertTrue("被拒时不该带回任何照片条目：${list.items.size}", list.items.isEmpty())

                // get-thumb / get-photo 同属「读我相册」这一族，必须一起被闸住：
                // 只挡 list-photos 等于把闸装在门框上，门照样开。
                FrameIO.writeFrame(it, FrameHeader(type = "get-thumb", photoId = "1", maxEdge = 128))
                val thumb = readHeader(it)
                assertEquals(false, thumb.ok)
                assertEquals("对方还没允许本机浏览其照片", thumb.error)

                FrameIO.writeFrame(it, FrameHeader(type = "get-photo", photoId = "1"))
                val photo = readHeader(it)
                assertEquals(false, photo.ok)
                assertEquals("对方还没允许本机浏览其照片", photo.error)
            }
            // 被问过这件事本身要留在状态里：界面据此给一颗「允许浏览」。
            // 没有它，对方只会看到一句拒绝，而本机不知道有人在等 —— 请求永远批不下来。
            assertTrue(
                "被拒的对端要进 browseAsked，否则界面没有可以点的「允许浏览」：" +
                    hub.state.value.browseAsked,
                hub.state.value.browseAsked.any { it.host == "127.0.0.1" },
            )
            // 反向自检：授权之后同一条请求必须立刻能过（否则上面那些 ok=false
            // 可能只是「服务端本来就坏了」）。
            hub.grantBrowse("127.0.0.1")
            val again = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            again.withConn {
                handshake(it)
                FrameIO.writeFrame(it, FrameHeader(type = "list-photos", offset = 0, limit = 2))
                val allowed = readHeader(it)
                assertEquals("允许之后还读不到，说明上面测到的不是授权闸", true, allowed.ok)
                assertEquals(listOf("0", "1"), allowed.items.map { m -> m.id })
            }
        }
    }

    /**
     * 对端在 TCP `hello` 里自报了身份，但它的 UDP 信标到不了（跨网段 / 防火墙挡 47470 是常态）。
     * 这时来件必须显示它自报的名字，而不是「未知设备」—— 这条钉住 §3.17 之后的 peerFor 顺序。
     */
    @Test
    fun incomingPhotoIsAttributedToTheHellosSelfReportedName() {
        runBlocking {
            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            conn.withConn {
                FrameIO.writeFrame(
                    it,
                    FrameHeader(type = "hello", deviceId = "py-hidden", name = "隐藏路由器后的手机", platform = "python"),
                )
                assertEquals("hello", readHeader(it).type)
                // 没有对应的信标 → peers 列表里查不到它，只能靠 hello 自报。
                assertTrue(hub.state.value.peers.none { p -> p.host == "127.0.0.1" && p.id == "py-hidden" })
                val body = jpeg(32)
                FrameIO.writeFrame(
                    it,
                    FrameHeader(
                        type = "send-photo",
                        meta = SyncPhotoMeta("hid-1", "hid.jpg", 1700000009000L, 32, 32, body.size.toLong()),
                    ),
                    body,
                )
                assertEquals("send-ok", readHeader(it).type)
            }
            val incoming = hub.state.value.incoming.single()
            assertEquals("来件要挂上 hello 自报的名字", "隐藏路由器后的手机", incoming.fromPeer.name)
            assertEquals("py-hidden", incoming.fromPeer.id)
            assertEquals("python", incoming.fromPeer.platform)
        }
    }

    /** 信标认识的设备优先级更高：hello 名字与信标名字冲突时采信标（id 才是对得上的身份）。 */
    @Test
    fun beaconedPeerWinsOverTheHelloName() {
        runBlocking {
            assertTrue("信标没收到", awaitBeacon("known-1", "信标里的名字", tcpPort = hubPort))
            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            conn.withConn {
                FrameIO.writeFrame(
                    it,
                    FrameHeader(type = "hello", deviceId = "known-1", name = "自报的假名", platform = "android"),
                )
                readHeader(it)
                val body = jpeg(32)
                FrameIO.writeFrame(
                    it,
                    FrameHeader(
                        type = "send-photo",
                        meta = SyncPhotoMeta("known-1", "k.jpg", 1700000010000L, 32, 32, body.size.toLong()),
                    ),
                    body,
                )
                assertEquals("send-ok", readHeader(it).type)
            }
            assertEquals("信标里的名字", hub.state.value.incoming.single().fromPeer.name)
        }
    }

    /**
     * §3.11 的封顶：在途照片必须有上限。
     *
     * 走的是**一条连接连发 10 张**这条路——本机 UI 的批量发送本来就是在同一个协程里逐张发
     * （SyncScreen 的 targets.forEachIndexed），而对端一次挑十几张直接推也是常见用法；
     * 用户还没决定收/丢的时候，这些字节全压在 RAM 里。前 8 张收下、后 2 张回 busy。
     * （注意别用「开 9 条连接」来测：连接数本身另有上限，那样测到的是另一道闸。）
     */
    @Test
    fun incomingQueueIsCappedAndRepliesBusy() {
        runBlocking {
            val conn = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            val replies = conn.withConn {
                handshake(it)
                val sends = (1..10).map { i -> sendOne(it, i) }
                assertEquals(10, sends.size)
                assertEquals("前 8 张应全部收下", 8, sends.count { h -> h.type == "send-ok" })
                assertTrue(
                    "第 9、10 张要回 send-error/busy：${sends.drop(8)}",
                    sends.drop(8).all { h -> h.type == "send-error" && h.error == "busy" },
                )
                assertEquals(8, hub.state.value.incoming.size)

                // 用户「接受」之后腾出名额，才允许继续收 —— 否则封顶就变成永久拒绝。
                hub.state.value.incoming.forEach { t -> hub.acceptIncoming(t.transferId) }
                assertEquals(0, hub.state.value.incoming.size)
                assertEquals(8, sink.saved.size)
                listOf(sendOne(it, 11))
            }
            conn.close()
            assertEquals("腾出名额后还能继续收", "send-ok", replies.single().type)
        }
    }

    private fun sendOne(conn: SyncConnection, seq: Int): FrameHeader {
        val body = jpeg(32)
        val id = "cap-$seq"
        FrameIO.writeFrame(
            conn,
            FrameHeader(
                type = "send-photo",
                meta = SyncPhotoMeta(id, "$id.jpg", 1700000000000L + seq, 32, 32, body.size.toLong()),
            ),
            body,
        )
        return readHeader(conn)
    }

    /**
     * §3.11 MAJOR-4.5：握手后一声不吭的连接必须在空闲超时（30s）时被服务端关掉，
     * **并且把并发名额还回去** —— 没有它，几条静默连接就能永久占死 `Semaphore(8)` 的所有线程，
     * 之后谁都连不上来（这才是真正的拒绝服务面）。
     * 所以这条测的不只是「会断」，而是「断了之后还服务得了新客户端」。
     */
    @Test
    fun idleConnectionIsClosedAndItsSlotIsReleased() {
        runBlocking {
            // 故意不用 withConn：这条连接要一直开着当「哑巴」，提前 close 就测不到服务端超时了。
            val hog = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            handshake(hog) // 握手完就不发任何东西

            // 30s 空闲超时 + 一点余量。等到服务端把这条关掉：读到 EOF（readFrame 返回 null）。
            val closed = awaitUnit(timeoutMs = 45_000, stepMs = 1_000) {
                runCatching { FrameIO.readFrame(hog) == null }.getOrDefault(true)
            }
            assertTrue("静默连接 45s 还没被服务端关掉：空闲超时没生效", closed)
            hog.close()

            // 名额必须回来了：再走一遍完整的 list-photos。
            val fresh = SyncClient().connect("127.0.0.1", hubPort, 3_000)
            fresh.withConn {
                handshake(it)
                FrameIO.writeFrame(it, FrameHeader(type = "list-photos", offset = 0, limit = 2))
                assertEquals("photo-list", readHeader(it).type)
            }
        }
    }

    // ---- helpers ----

    private fun <T> SyncConnection.withConn(block: (SyncConnection) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun handshake(conn: SyncConnection) {
        FrameIO.writeFrame(
            conn,
            FrameHeader(type = "hello", deviceId = "probe", name = "probe", platform = "python"),
        )
        assertEquals("hello", readHeader(conn).type)
    }

    private fun readHeader(conn: SyncConnection): FrameHeader {
        val frame = FrameIO.readFrame(conn) ?: throw AssertionError("peer closed the connection")
        return FrameCodec.decodeHeader(frame) ?: throw AssertionError("bad frame header")
    }

    private fun sendBeacon(id: String, name: String, platform: String = "android", tcpPort: Int = 47471) {
        val json = "{\"id\":\"$id\",\"name\":\"$name\",\"platform\":\"$platform\",\"tcpPort\":$tcpPort}"
        val data = ("SPDISCOVER1|" + json).toByteArray(Charsets.UTF_8)
        DatagramSocket().use { sock ->
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), 47470))
        }
    }

    /**
     * 反复发信标，直到 `peerId` 出现在列表里。
     * 必须反复发：`start()` 里 UDP 监听是在另一个线程 bind 47470 的，第一包常常丢在没人听的空气里
     * （整套 instrumented 一起跑时就被这种时序坑过一次：单独跑绿、一起跑红）。
     */
    private fun awaitBeacon(
        peerId: String,
        name: String,
        platform: String = "android",
        tcpPort: Int = 47471,
        timeoutMs: Long = 8_000,
        also: () -> Unit = {},
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            also()
            sendBeacon(id = peerId, name = name, platform = platform, tcpPort = tcpPort)
            if (hub.state.value.peers.any { it.id == peerId }) return true
            Thread.sleep(250)
        }
        return hub.state.value.peers.any { it.id == peerId }
    }

    private fun awaitUnit(timeoutMs: Long = 4_000, stepMs: Long = 100, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(stepMs)
        }
        return predicate()
    }
}
