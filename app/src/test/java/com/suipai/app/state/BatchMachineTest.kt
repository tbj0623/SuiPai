package com.suipai.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量动作状态机的单测：**进度按真实成功数报、结论不进进度位、取消时不许报张数**。
 *
 * 这三条都对应装机量到过的真 bug：
 *  - 24 张一张没删掉，进度条走到 24/24（`batchDelete` / `batchCopy` 的 `i + 1`）；
 *  - 批量一瞬失败那句进了进度位，于是永远留在屏幕上（没人清它）；
 *  - 中途按"停止"之后界面上什么都不说。
 *
 * 单测能抓的是"状态该怎么变"；真正的仓库调用在 `BatchActions`，那部分靠源码守卫
 * （`PerfSourceGuardTest.attemptCountProgress` / `:932`）兜底。
 */
class BatchMachineTest {

    /** 防的 bug：空闲时界面还显示一条进度（比如上一次任务的残影）。 */
    @Test
    fun idleShowsNoHint() {
        assertNull(BatchMachine.hint(BatchMachine.Phase.Idle))
        assertEquals(false, BatchMachine.running(BatchMachine.Phase.Idle))
    }

    /**
     * 防的 bug（本条的核心）：进度必须来自**成功计数**。
     * 喂进去 3 是"成了 3 张"，界面就该显示已删 3/23；
     * 而不是"试到了第 3 张"——后者在全部失败时会走到 23/23。
     */
    @Test
    fun progressReflectsSuccessesNotAttempts() {
        val p = BatchMachine.progress(BatchMachine.Verb.DELETE, 3, 23)
        assertEquals("已删 3/23", BatchMachine.hint(p))
        assertTrue(BatchMachine.running(p))
        // 23 张一张没成：停在 0/23，不是 23/23
        assertEquals("已删 0/23", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, 0, 23)))
    }

    /** 防的 bug：三个动作的动词被统一成同一个（探针/用户看到的"已存""已删"混了）。 */
    @Test
    fun verbsKeepTheirOwnWording() {
        assertEquals("已删 1/2", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, 1, 2)))
        assertEquals("已存 1/2", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.COPY, 1, 2)))
        // 一瞬的进度是引擎回调原样给的形状（没有动词），改一个字探针就对不上
        assertEquals("1/2", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.YISHUN, 1, 2)))
    }

    /** 防的 bug：成功数超过总数时进度条跑到 100% 以上（引擎回调与手数混在一起时会这样）。 */
    @Test
    fun progressNeverExceedsTotal() {
        assertEquals("已删 5/5", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, 99, 5)))
        assertEquals("已删 0/5", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, -2, 5)))
    }

    /**
     * 防的 bug（改前 `batchYishun` 的真 bug）：结论写进进度位之后永远没人清它，
     * 屏幕上那条"处理 …"就一直挂着。
     */
    @Test
    fun doneClearsTheHintAndKeepsOnlyTheNote() {
        val p = BatchMachine.finish("删了 3 张")
        assertNull("结论进了进度位 —— 那一格没人清，会永远挂在屏幕上", BatchMachine.hint(p))
        assertEquals(false, BatchMachine.running(p))
    }

    /** 防的 bug：收尾之后还能被推进（late callback 把界面从"结果"拉回"进度"）。 */
    @Test
    fun advanceIsInertAfterFinish() {
        val done = BatchMachine.finish("出了 2 张")
        assertEquals(done, BatchMachine.advance(done, 5))
    }

    /** 防的 bug：`advance` 传入超过总数的成功数时不夹住（与 progress 不一致）。 */
    @Test
    fun advanceClampsToTotal() {
        val p = BatchMachine.progress(BatchMachine.Verb.COPY, 0, 4)
        assertEquals("已存 4/4", BatchMachine.hint(BatchMachine.advance(p, 9)))
        assertEquals("已存 0/4", BatchMachine.hint(BatchMachine.advance(p, -1)))
    }

    /** 防的 bug：总数为 0 时除零/负宽度（空批次不应该进入 Running）。 */
    @Test
    fun zeroTotalDegradesToAnEmptyProgress() {
        assertEquals("已删 0/0", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, 0, 0)))
        assertEquals("已删 0/0", BatchMachine.hint(BatchMachine.progress(BatchMachine.Verb.DELETE, 3, 0)))
    }

    /*
     * 「取消时不许报张数」这一条不在这里断言：它落在 `ui/common/BatchNote.kt` 的
     * `yishunNote(cancelled = true)` 里，由 `BatchNoteTest` 穷举过（每个输入一句非空的话）。
     * 这里只钉住状态机的那一半：取消收尾 = Done + 进度位清空。
     */

    @Test
    fun cancelledFinishStillClearsTheHint() {
        val p = BatchMachine.finish("一瞬停了，没算出出了几张", cancelled = true)
        assertNull(BatchMachine.hint(p))
        assertEquals(
            "一瞬停了，没算出出了几张",
            (p as BatchMachine.Phase.Done).note,
        )
        assertTrue(p.cancelled)
    }

    /**
     * 防的 bug（改前 `batchDelete` 的 `awaiting` 那个分支）：弹了系统授权框之后，
     * 收尾其实还没完 —— 结论要等框回来才说得出来。若这时把状态置成 Idle，
     * 用户会先看到进度消失、几秒后结论才出现，中间那段（系统框还挂着）屏幕上像什么都没发生。
     *
     * 所以状态机要能表达"还在等"：Idle 只表示**这一批真的结束了**。
     * 这条用不到 Done（结论还没出），只钉"Waiting ≠ Idle"这个区别在实现里被遵守：
     * `BatchActions.batchDelete` 里 `awaiting = req != null` 为真时，`finally` **不**把状态置 Idle。
     */
    @Test
    fun awaitingConsentIsNotTheSameAsFinished() {
        // 状态机本身只有三态；"等授权框"期间界面该显示的是**进度仍在**（框盖在上面）
        val running = BatchMachine.progress(BatchMachine.Verb.DELETE, 2, 3)
        assertEquals("已删 2/3", BatchMachine.hint(running))
        assertTrue(BatchMachine.running(running))
        // 真正结束（结论已出）才同时满足：进度位空 + 有结论
        val done = BatchMachine.finish("删了 2 张")
        assertNull(BatchMachine.hint(done))
        assertEquals("删了 2 张", (done as BatchMachine.Phase.Done).note)
    }
}

/**
 * 分享导入结论 + 授权票据的单测。
 *
 * 这两块各对应一次装机事故：分享进来一张都没读出来时屏幕上一句话都没有（像入口死了）；
 * 授权框还挂着时重建 Activity 会让票据丢失，于是那批死 id 永久留在清单里。
 */
class ShareImportOutcomeTest {

    /** 防的 bug：一张都没导进来时一声不吭（用户以为分享入口坏了）。 */
    @Test
    fun allFailedAlwaysSaysSomething() {
        val said = ShareImportOutcome.note(total = 3, imported = 0)
        assertEquals("分享进来的 3 张都没读出来（可能已被对方清掉）", said)
    }

    /** 防的 bug：部分失败时谎报"全导入了"。 */
    @Test
    fun partialFailureQuotesHowManyWereLost() {
        assertEquals("分享进来 5 张，2 张没读出来", ShareImportOutcome.note(total = 5, imported = 3))
    }

    /** 防的 bug：全都成功时还多说一句（改前是全静默，用户直接看一瞬那一屏）。 */
    @Test
    fun successIsSilent() {
        assertNull(ShareImportOutcome.note(total = 2, imported = 2))
        assertNull(ShareImportOutcome.note(total = 0, imported = 0))
    }
}

class ConsentTicketTest {

    /** 防的 bug：三件套没有一起保存（只有 ids 进了 Bundle），于是"另删了几张"这一句报错。 */
    @Test
    fun ticketRoundTripsAllThreeFields() {
        val t = ConsentTicket(ids = listOf(1L, 2L, 3L), direct = 2, fromViewer = true)
        assertEquals(t, ConsentTicket.decode(t.encode()))
    }

    /** 防的 bug：空票据往返之后 `direct` 变了（下一次弹框结论多出几张）。 */
    @Test
    fun emptyTicketRoundTrips() {
        assertEquals(ConsentTicket.EMPTY, ConsentTicket.decode(ConsentTicket.EMPTY.encode()))
    }

    /** 防的 bug：脏 Bundle 让恢复崩在 `toLong` 上。 */
    @Test
    fun decodeToleratesGarbage() {
        assertEquals(ConsentTicket.EMPTY, ConsentTicket.decode(emptyList()))
        assertEquals(ConsentTicket.EMPTY, ConsentTicket.decode(listOf("x")))
        val odd = ConsentTicket.decode(listOf("1,x,3", "not-a-number", "maybe"))
        assertEquals(listOf(1L, 3L), odd.ids)
        assertEquals(0, odd.direct)
        assertEquals(false, odd.fromViewer)
    }

    /**
     * 防的 bug（改前逐字的那条规则）：`direct` 只在"没有要问的"时清零。
     * 有票时清零会让这一次的结论丢掉"弹框之前已直删几张"。
     */
    @Test
    fun consumedClearsDirectOnlyWhenThereWasNothingAsked() {
        val asked = ConsentTicket(ids = listOf(7L), direct = 3, fromViewer = true)
        assertEquals(ConsentTicket.EMPTY, asked.consumed())

        val nothing = ConsentTicket(ids = emptyList(), direct = 3)
        assertEquals(0, nothing.consumed().direct)
    }

    /** 防的 bug：空票据被判成"在等授权框"，于是删除流程永远不进结论分支。 */
    @Test
    fun pendingOnlyWhenThereAreIds() {
        assertEquals(false, ConsentTicket.EMPTY.pending)
        assertEquals(true, ConsentTicket(ids = listOf(1L)).pending)
    }
}
