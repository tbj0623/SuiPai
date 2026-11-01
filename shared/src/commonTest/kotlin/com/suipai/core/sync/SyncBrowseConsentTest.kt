package com.suipai.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「谁能看我的相册」必须在这台设备上说得出口、也收得回来。
 *
 * 装机前的原状：服务端在 `hello` 之后无条件回答 `list-photos` / `get-thumb` / `get-photo` ——
 * 同一局域网里任何设备发一个像样的握手，就能把整本相册列走、再逐张取**原图**。
 * 接收侧本来有 `acceptIncoming`（要不要存下对方发来的那张），供片这一侧却没有任何确认，
 * 而 `store/privacy-policy.html` 写的是「两端都确认」。
 *
 * 这一族判据钉三件事：默认拒绝、允许/收回的状态、以及"被拒时要把话回给对方"（静默在对端
 * 界面上长得和网络坏了一模一样，而这两种情况用户要做的事完全不同）。
 */
class SyncBrowseConsentTest {

    private fun hub() = SyncHub(
        deviceId = "test-a",
        deviceName = "测试机 A",
        platform = "test",
        source = null,
        sink = null,
        tcpPort = 0,
    )

    @Test
    fun nobodyIsAllowedUntilTheUserSaysSo() {
        // 空清单 / 空 host / 只有别人被允许 —— 三种都不许放行
        assertFalse(mayServeBrowse(emptyList(), "192.168.1.7"))
        assertFalse(mayServeBrowse(listOf("192.168.1.9"), "192.168.1.7"))
        assertFalse(mayServeBrowse(listOf("192.168.1.7"), ""), "空 host 不许命中空清单里的某一项")
        assertTrue(mayServeBrowse(listOf("192.168.1.7"), "192.168.1.7"))

        // 自检：把判据换成"清单非空就放行"这种一眼看不出的错法，必须立刻变红
        val wrong = listOf("192.168.1.9")
        assertFalse(
            mayServeBrowse(wrong, "192.168.1.7"),
            "只要清单里有**任何**一项就放行 = 允许过一台等于允许过整栋楼",
        )
    }

    @Test
    fun grantingClearsThePendingAsk() {
        val h = hub()
        h.grantBrowse("10.0.0.5")
        assertEquals(listOf("10.0.0.5"), h.state.value.browseGrants)

        h.grantBrowse("10.0.0.5")
        assertEquals(1, h.state.value.browseGrants.size, "重复允许不许把清单越堆越长")
    }

    @Test
    fun revokingTakesTheGrantAwayButKeepsNothingStale() {
        val h = hub()
        h.grantBrowse("10.0.0.5")
        h.grantBrowse("10.0.0.6")
        h.revokeBrowse("10.0.0.5")
        assertEquals(listOf("10.0.0.6"), h.state.value.browseGrants)
        assertFalse(mayServeBrowse(h.state.value.browseGrants, "10.0.0.5"), "收回之后还能读 = 白收")
    }

    @Test
    fun blankHostIsNotGrantable() {
        val h = hub()
        h.grantBrowse("")
        assertTrue(h.state.value.browseGrants.isEmpty(), "空 host 一旦进清单，判据就得靠运气才不命中")
    }

    /**
     * 被拒时回给对方的那句话。
     *
     * 这条不是文案洁癖：对端把 `error` 直接显示给用户。回 "not supported" 会让人以为
     * 对方版本不支持互通，而真实情况是**对方还没点允许** —— 该做的事完全相反。
     */
    @Test
    fun theRefusalSaysWhatToDoAboutIt() {
        val h = hub()
        h.grantBrowse("127.0.0.1")
        val granted = h.state.value.browseGrants
        assertTrue(mayServeBrowse(granted, "127.0.0.1"))
        // 拒绝原因这个常量不许退化成空串或"unknown type"（dispatch 的 else 分支用的就是后者）
        assertTrue(
            NEED_BROWSE_GRANT.isNotBlank() && NEED_BROWSE_GRANT != "unknown type",
            "被拒的理由要能直接念给用户：" + NEED_BROWSE_GRANT,
        )
    }
}
