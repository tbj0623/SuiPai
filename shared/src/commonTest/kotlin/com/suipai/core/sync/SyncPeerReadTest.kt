package com.suipai.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 「连不上」与「对方没有照片」必须是两个值。
 *
 * 起因与 `MediaRepository` 那一路同形：`fetchPeerPhotos` 失败时返回空列表，
 * 而 `SyncScreen` 的 `list.isEmpty()` 分支把它念成「对方没有可读取的照片」——
 * 对方只是没开机，界面却说它没照片。
 *
 * 这里不打真网络：拨一个必然被拒的本机端口（127.0.0.1:1）就是"连不上"这条路径本身。
 */
class SyncPeerReadTest {

    private fun hub() = SyncHub(
        deviceId = "test-a",
        deviceName = "测试机 A",
        platform = "test",
        source = null,
        sink = null,
        tcpPort = 0,
    )

    private fun deadPeer() = PeerInfo(
        id = "dead",
        name = "不存在的机器",
        platform = "test",
        host = "127.0.0.1",
        tcpPort = 1,
        lastSeenMs = 0L,
    )

    @Test
    fun unreachablePeerIsNotReportedAsEmpty() = runTest {
        val h = hub()
        val got = h.fetchPeerPhotos(deadPeer(), 0, 10)
        assertNull(got, "连不上必须还 null；空表是「对方确实没有」，界面会念成「对方没有可读取的照片」")
        assertNotNull(h.state.value.lastError, "失败总要留下一个能说的原因")
    }

    @Test
    fun thumbOfDeadPeerIsNullNotZeroBytes() = runTest {
        val h = hub()
        val bytes = h.fetchPeerThumb(deadPeer(), "whatever", 128)
        assertNull(bytes, "读不到一张缩略图就是 null，不能给一个 0 字节的「成功」")
        assertNotNull(h.state.value.lastError)
    }

    @Test
    fun wholeBatchFailureStillHasAReason() = runTest {
        val h = hub()
        val got = h.fetchPeerThumbs(deadPeer(), listOf("a", "b"), 128)
        // 整批一起失败：拿回来的是空 map，但「空」在这里不等于「对方没有图」——
        // 撑起区别的是 lastError 必须被写上。
        assertNotNull(h.state.value.lastError, "整批失败必须写 lastError，否则界面无从分辨")
        assertNotNull(got, "这批的返回值本身不许是 null：它带着已经拿到的那部分")
    }
}
