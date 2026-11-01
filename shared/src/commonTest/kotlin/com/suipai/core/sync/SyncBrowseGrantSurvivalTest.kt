package com.suipai.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「改名不该顺手把别人踢出去」。
 *
 * ## 这条测试挡的是什么
 *
 * `SyncManager.rename` 必须**重建** `SyncHub`（`deviceName` 是构造期冻结的，没有 setter），
 * 而 [SyncState.browseGrants] 只活在内存里。改前是 `hub.stop()` + 新建：
 * 用户改一次设备名，之前逐台点过「允许浏览」的授权全部消失 ——
 * 对方还在同一台设备、同一个 IP 上，却突然读不了，界面上还多出一条待确认的请求。
 *
 * 那不是"把权限收得更严"（收回是 [SyncHub.revokeBrowse] 的活，用户主动），
 * 是一个没人预期的副作用：用户做的是一个与隐私无关的动作（改名字）。
 *
 * [SyncHub.restoreBrowseGrants] 是新实例把旧清单接过去的那一跳；`rename` 在
 * `stop()` 之前拍快照。这里钉住那一跳的语义 —— 以及它**不许**顺手把
 * `browseAsked` 也搬过去（重建后"谁在问"由新连接重新产生，
 * 拿旧清单去糊会把一台已经走掉的设备留在界面上）。
 */
class SyncBrowseGrantSurvivalTest {

    private fun hub() = SyncHub(
        deviceId = "test-a",
        deviceName = "测试机 A",
        platform = "test",
        source = null,
        sink = null,
        tcpPort = 0,
    )

    @Test
    fun aRebuiltHubCanTakeOverTheOldGrants() {
        val old = hub()
        old.grantBrowse("10.0.0.5")
        old.grantBrowse("10.0.0.6")

        val rebuilt = hub()
        assertTrue(rebuilt.state.value.browseGrants.isEmpty(), "前提：新实例从空开始")

        rebuilt.restoreBrowseGrants(old.state.value.browseGrants)

        assertEquals(
            listOf("10.0.0.5", "10.0.0.6"),
            rebuilt.state.value.browseGrants.sorted(),
            "改名重建之后授权丢了：对方还在同一个 IP 上，却突然读不了",
        )
        // 判据落到"能不能真的读"上，而不只是清单里有没有那个字符串
        assertTrue(mayServeBrowse(rebuilt.state.value.browseGrants, "10.0.0.5"))
        assertTrue(mayServeBrowse(rebuilt.state.value.browseGrants, "10.0.0.6"))
        assertFalse(mayServeBrowse(rebuilt.state.value.browseGrants, "10.0.0.7"), "没允许过的不许放行")
    }

    @Test
    fun restoringIsIdempotentAndIgnoresGarbage() {
        val h = hub()
        h.restoreBrowseGrants(listOf("10.0.0.5", "10.0.0.5", "", "  "))
        assertEquals(listOf("10.0.0.5"), h.state.value.browseGrants, "重复与空白项要收敛掉")

        h.restoreBrowseGrants(listOf("10.0.0.5", "10.0.0.9"))
        assertEquals(
            listOf("10.0.0.5", "10.0.0.9"),
            h.state.value.browseGrants.sorted(),
            "重复灌一次不许把清单越堆越长",
        )

        val empty = hub()
        empty.restoreBrowseGrants(emptyList())
        empty.restoreBrowseGrants(listOf("", "   "))
        assertTrue(empty.state.value.browseGrants.isEmpty(), "全是空白 = 没有授权，不许凭空加一条")
    }

    @Test
    fun restoreDoesNotSmugglePendingAsksAcrossTheRebuild() {
        val h = hub()
        h.grantBrowse("10.0.0.5")
        // `browseAsked` 只由服务端在"有人来问且没被允许"时写入，这一层拿不到公开写入口；
        // 所以判据落在"restore 的输入里根本没有 asked 这一项"这件事上 —— 它的签名只收 host。
        // 这一条是**结构**判据：真拿旧 asked 糊上去，就要给这个函数加第二个参数。
        h.restoreBrowseGrants(listOf("10.0.0.5"))
        assertTrue(
            h.state.value.browseAsked.isEmpty(),
            "重建之后的「谁在问」该由新连接重新产生，不许从旧实例搬过来",
        )
    }

    /**
     * 自检：把 `restoreBrowseGrants` 写成"什么都不做"，上面那些断言必须变红。
     *
     * 这是这一族测试最容易退化成空跑的地方 —— `browseGrants` 本来就是空表，
     * 一个 no-op 实现能让"空表"那几条全绿。
     */
    @Test
    fun theAssertionsWouldCatchANoOpRestore() {
        val h = hub()
        h.grantBrowse("10.0.0.5")
        val carried = h.state.value.browseGrants

        val pretendRebuilt = hub()   // 不调 restoreBrowseGrants —— 就是改前的行为
        assertFalse(
            mayServeBrowse(pretendRebuilt.state.value.browseGrants, "10.0.0.5"),
            "自检失败：一个没接授权的实例居然也被放行了，那整族断言都没在比",
        )
        assertTrue(
            carried.isNotEmpty(),
            "自检失败：旧实例里根本没攒下授权，前面的断言无从谈起",
        )
    }
}
