package com.suipai.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「浏览对端相册」翻页的两条判据：末页怎么认、新页怎么接。
 *
 * 为什么单独测：协议里**没有 `total` / `hasMore`**（`FrameHeader` 只有 `offset` / `limit`），
 * 于是"还有没有下一页"完全是客户端自己从「本页条数 vs 请求的 limit」推出来的。
 * 这条推理错一个方向的代价不对称：
 * - 判多了 → 多发一次 `list-photos`，拿回空表，多一次往返；
 * - 判少了 → 相册有 300 张，界面永远停在第一页 60 张，**而且界面上看不出少了什么**
 *   （这正是本次要修的现状：`fetchPeerPhotos(peer, 0, 60)` 一次性写死）。
 * 所以判据取保守的一侧：只有明确的短页才收工。
 */
class SyncRemotePagingTest {

    private fun meta(id: String) = SyncPhotoMeta(
        id = id,
        name = "$id.jpg",
        dateTaken = 1_700_000_000_000L,
        width = 64,
        height = 32,
        sizeBytes = 1024L,
    )

    // ---- hasMoreRemotePages ----

    @Test
    fun aFullPageAlwaysMeansAskAgain() {
        // 满页 = 可能还有。相册张数恰好是 limit 的整数倍时，最后一次会拿回空页，
        // 那是唯一"多用一次往返"的情形，比悄悄少一页便宜得多。
        assertTrue(hasMoreRemotePages(pageSize = 60, limit = 60))
        assertTrue(hasMoreRemotePages(pageSize = 61, limit = 60), "服务端多给也不算到底")
        assertTrue(hasMoreRemotePages(pageSize = 1, limit = 1))
    }

    @Test
    fun aShortPageIsTheLastPage() {
        assertFalse(hasMoreRemotePages(pageSize = 59, limit = 60))
        assertFalse(hasMoreRemotePages(pageSize = 0, limit = 60))
        assertFalse(hasMoreRemotePages(pageSize = 1, limit = 60))
    }

    @Test
    fun nonPositiveLimitIsNeverMore() {
        // limit <= 0 时服务端按约定回空表（AndroidSyncSource.listPhotos 第一行就 return emptyList）。
        // 若把这张空表读成"还有更多"，界面会永远在说「下一页」而每次都不涨 —— 死循环。
        assertFalse(hasMoreRemotePages(pageSize = 0, limit = 0))
        assertFalse(hasMoreRemotePages(pageSize = 10, limit = 0))
        assertFalse(hasMoreRemotePages(pageSize = 0, limit = -5))
    }

    /**
     * 反向自检：这里把两种一眼看不出的错法写出来，确认它们与正确判据给出不同答案 ——
     * 如果哪天有人把 `>=` 改成 `>`、或把判据换成"空页才算末页"，
     * 上面那几条会在不同位置变红，而不是整套一起沉默。
     */
    @Test
    fun theTwoPlausibleWrongRulesDisagreeWithOurs() {
        val ourRule = { size: Int, limit: Int -> hasMoreRemotePages(size, limit) }
        val strictGreater = { size: Int, limit: Int -> limit > 0 && size > limit }
        val emptyMeansLast = { size: Int, limit: Int -> limit > 0 && size != 0 }

        // 满页这一档：我们的判据说"还有"，`>` 说不许再问 → 恰好整除的相册会少问一页。
        assertTrue(ourRule(60, 60))
        assertFalse(strictGreater(60, 60), "自检失效：错法在这档与正确判据同答，这条就测不出东西")

        // 短页这一档：我们的判据说"到底了"，"非空就还有"说不 → 会无限翻。
        assertFalse(ourRule(59, 60))
        assertTrue(emptyMeansLast(59, 60), "自检失效：错法在这档与正确判据同答")

        // limit = 0 这一档：我们的判据说不翻，"非空就还有"直接死循环。
        assertFalse(ourRule(0, 0))
        assertFalse(emptyMeansLast(0, 0), "两套在这里都收敛，故 limit<=0 只由非负那一条守卫")
    }

    // ---- appendRemotePage ----

    @Test
    fun pagesAreAppendedInOrder() {
        val first = listOf(meta("1"), meta("2"))
        val second = listOf(meta("3"), meta("4"))
        assertEquals(listOf("1", "2", "3", "4"), appendRemotePage(first, second).map { it.id })
    }

    @Test
    fun anEmptyPageLeavesTheListAlone() {
        val loaded = listOf(meta("1"))
        assertEquals(loaded, appendRemotePage(loaded, emptyList()))
    }

    /**
     * 翻页用的是 offset，而对端相册是活的：用户在两页之间拍了一张新照片，
     * 后面每页的 offset 整体后移一格，上一页最后一张会在下一页再出现。
     * 重复 id 会让 `LazyVerticalGrid(key = { it.id })` 直接抛异常（整页崩，不是显示两张）。
     */
    @Test
    fun aPhotoThatSlidesFromOnePageIntoTheNextIsNotDuplicated() {
        val first = listOf(meta("1"), meta("2"), meta("3"))
        // 对端在第一页之后新增了一张，于是第二页从原来的第 2 张开始
        val second = listOf(meta("3"), meta("4"))
        assertEquals(listOf("1", "2", "3", "4"), appendRemotePage(first, second).map { it.id })
    }

    /**
     * 反向自检：把去重拿掉（直接 `loaded + page`）必须在这条上给出**不同**的结果。
     * 只断言正确结果而不给错法的形状，等于没测去重。
     */
    @Test
    fun droppingTheDedupProducesAKeyCrashShape() {
        val first = listOf(meta("1"), meta("2"))
        val second = listOf(meta("2"), meta("3"))
        val naive = first + second
        assertEquals(4, naive.size, "朴素拼接在这个样本上应当出现重复")
        assertEquals(3, naive.map { it.id }.toSet().size, "朴素拼接的 id 集合比行数少 = 有重复 key")
        assertEquals(3, appendRemotePage(first, second).size, "去重后的行数必须等于 id 集合大小")
        assertEquals(
            appendRemotePage(first, second).map { it.id },
            appendRemotePage(first, second).map { it.id }.distinct(),
            "去重后的清单里一个 id 都不许出现两次",
        )
    }

    /**
     * 追加不许把已经加载的那几页丢掉或重排：翻页时用户已经看过的格子不能跳位。
     * 这条用"前缀逐项相同"来钉，比 `size` 更严。
     */
    @Test
    fun alreadyLoadedPhotosKeepTheirPlaces() {
        val first = (1..60).map { meta("p$it") }
        val second = (61..120).map { meta("p$it") }
        val combined = appendRemotePage(first, second)
        assertEquals(first, combined.take(first.size), "旧页被重排了：用户看过的格子会跳位")
        assertEquals(second, combined.drop(first.size))
    }
}
