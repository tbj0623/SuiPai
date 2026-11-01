package com.suipai.core.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 浏览授权闸的**真 socket 证据**：起一个真的 [SyncHub] 服务端，用真的 [SyncClient] 连上去，
 * 走一遍 `ifAllowed` 那条分支。
 *
 * 为什么还要这一条（`SyncBrowseConsentTest` 已经测了 `mayServeBrowse` 这个纯函数）：
 * 纯函数只证明"判据本身对"，证明不了"服务端真的用它"。这个仓库踩过同形的坑 ——
 * 仪器化测试 `SyncPeerInteropTest` 里那条 `list-photos` 用例一直红着没人看见，
 * 原因正是**没人跑过**它（`GOAL.md`：仪器化只编译过、没跑过）。
 * 把这一条放在 commonTest 里，`:shared:testDebugUnitTest` 每次都会真的执行它。
 *
 * 端口是固定的几个候选而不是动态分配：commonTest 里没有 `java.net`，拿不到「让系统给我一个空端口」。
 * 候选端口都落在 47xxx（互通专用段），逐个试到能 bind 为止；全占满就直接失败并说清原因，
 * **不许 skip** —— 一条悄悄不跑的隐私判据等于没有。
 */
class SyncBrowseGateSocketTest {

    /** 每个用例都要一个独立的服务端；候选端口从 47491 起往后数。 */
    private fun startHubOnFreePort(grants: List<String>): Pair<SyncHub, Int> {
        for (port in 47_491..47_499) {
            val hub = SyncHub(
                deviceId = "gate-test",
                deviceName = "闸测试机",
                platform = "test",
                source = object : SyncPhotoSource {
                    override fun listPhotos(offset: Int, limit: Int): List<SyncPhotoMeta> =
                        listOf(
                            SyncPhotoMeta("a", "a.jpg", 1L, 4, 4, 8L),
                            SyncPhotoMeta("b", "b.jpg", 2L, 4, 4, 8L),
                            SyncPhotoMeta("c", "c.jpg", 3L, 4, 4, 8L),
                        ).drop(offset).take(limit)

                    override fun readPhotoBytes(id: String): ByteArray? = byteArrayOf(1, 2, 3)

                    override fun readThumbBytes(id: String, maxEdge: Int): ByteArray? = byteArrayOf(4, 5)
                },
                sink = null,
                tcpPort = port,
            )
            hub.start()
            if (hub.state.value.lastError != null) {
                // bind 失败（端口被占）：换下一个候选
                hub.stop()
                continue
            }
            grants.forEach { hub.grantBrowse(it) }
            return hub to port
        }
        throw AssertionError("47491–47499 全被占了，这条隐私判据没跑成 —— 换一台机器/等别的 lane 跑完再试")
    }

    /** 发一条请求，回它的应答帧头。走的是完整的「连接 → 握手 → 请求 → 读应答」。 */
    private fun ask(port: Int, header: FrameHeader): FrameHeader = runBlocking {
        val conn = SyncClient().connect("127.0.0.1", port, 3_000)
        try {
            // 服务端要求第一条帧就是 hello（不是的话直接回 unknown type 并关连接）。
            FrameIO.writeFrame(
                conn,
                FrameHeader(type = "hello", deviceId = "probe", name = "探针", platform = "test"),
            )
            val hello = FrameIO.readFrame(conn) ?: throw AssertionError("握手就被关了")
            assertEquals("hello", FrameCodec.decodeHeader(hello)?.type)

            FrameIO.writeFrame(conn, header)
            val frame = FrameIO.readFrame(conn) ?: throw AssertionError("请求之后没有应答")
            FrameCodec.decodeHeader(frame) ?: throw AssertionError("应答头解不开")
        } finally {
            conn.close()
        }
    }

    @Test
    fun listPhotosIsRefusedUntilTheHostSaysYes() {
        val (allowedHub, allowedPort) = startHubOnFreePort(grants = listOf("127.0.0.1"))
        val (deniedHub, deniedPort) = startHubOnFreePort(grants = emptyList())
        try {
            // 没允许：三条「读我相册」的请求一律被拒，且带回那句人话。
            for (header in listOf(
                FrameHeader(type = "list-photos", offset = 0, limit = 10),
                FrameHeader(type = "get-thumb", photoId = "a", maxEdge = 64),
                FrameHeader(type = "get-photo", photoId = "a"),
            )) {
                val reply = ask(deniedPort, header)
                assertEquals(header.type, reply.type, "拒绝也要用原请求的 type，对端才知道拒的是哪一条")
                // kotlin.test 的 assertEquals 是 (期望, 实际, 说明)，与 JUnit 的说明在前不同。
                assertEquals(false, reply.ok, "${header.type} 在没授权时不该成功")
                assertEquals(NEED_BROWSE_GRANT, reply.error)
                assertTrue(reply.items.isEmpty(), "被拒的应答里不许夹带照片条目")
            }
            // 被问过这件事本身要记下来：界面靠 browseAsked 长出「允许浏览」那一颗。
            assertTrue(
                deniedHub.state.value.browseAsked.any { it.host == "127.0.0.1" },
                "被拒的对端没进 browseAsked，界面就没有可以点的「允许浏览」：" +
                    deniedHub.state.value.browseAsked,
            )

            // 允许之后同一条请求立刻能过。
            // 这一半是上半段的反向自检：否则上面那几个 ok=false 可能只是「服务端本来就坏了」。
            val ok = ask(allowedPort, FrameHeader(type = "list-photos", offset = 1, limit = 1))
            assertEquals(true, ok.ok, "授权之后还读不到：${ok.error}")
            assertEquals("photo-list", ok.type)
            assertEquals(listOf("b"), ok.items.map { it.id }, "offset 要原样透传给 source")
            assertNull(allowedHub.state.value.lastError)
        } finally {
            allowedHub.stop()
            deniedHub.stop()
        }
    }

    /**
     * 收回授权之后必须立刻回到拒绝。
     *
     * 这条钉的是「允许」不是单向开关：用户点「收回允许」之后，
     * 已经连上来的对端下一条请求就该被拒（`ifAllowed` 每条请求都重新查一次 `browseGrants`，
     * 而不是连接建立时查一次就记住）。**只在连接建立时查**这种写法在这个测试里长得完全正常，
     * 只有把「换一条连接」写成「同一条连接连发两次」才分得出来 —— 所以这里刻意复用同一个 hub。
     */
    @Test
    fun revokingTakesEffectImmediately() {
        val (hub, port) = startHubOnFreePort(grants = listOf("127.0.0.1"))
        try {
            assertEquals(true, ask(port, FrameHeader(type = "list-photos", limit = 5)).ok)
            hub.revokeBrowse("127.0.0.1")
            val denied = ask(port, FrameHeader(type = "list-photos", limit = 5))
            assertEquals(false, denied.ok, "收回允许之后还能读 = 白收")
            assertEquals(NEED_BROWSE_GRANT, denied.error)
        } finally {
            hub.stop()
        }
    }
}
