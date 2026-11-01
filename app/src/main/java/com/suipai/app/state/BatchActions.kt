package com.suipai.app.state

import android.net.Uri
import com.suipai.app.data.DeleteOutcome
import com.suipai.app.data.PhotoItem
import com.suipai.app.engine.BatchReport
import com.suipai.app.engine.BatchYishun
import com.suipai.app.ui.common.copyNote
import com.suipai.app.ui.common.deleteNote
import com.suipai.app.ui.common.favoriteNote
import com.suipai.app.ui.common.hideNote
import com.suipai.app.ui.common.yishunNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 五个批量动作：删除 / 收藏 / 隐藏 / 复制 / 一瞬。
 *
 * 每个函数逐行对对着改前 `MainActivity` 里同名的局部函数搬，三条语义一个字都不许丢：
 *
 *  1. **进度按真实成功数报**，不按尝试下标。写 `i + 1` 时，23 张一张都没删掉也能把
 *     进度条走到 23/23（`PerfSourceGuardTest.attemptCountProgress` 钉的就是这条）。
 *  2. **结论走 `batchNote`，不留在进度位 `batchHint`**。进度位是"处理中"那一格，
 *     写进去就再也没人清它（`batchYishun` 原来就犯了这个）。
 *  3. **收尾走 `NonCancellable`**：用户中途按"停止"时，选择要清掉、结论要说出来，
 *     否则屏幕上留一条走到一半的进度条。
 *
 * 本类不持有 Activity：`launchConsent` 由 [DeleteFlow] 通过回调交给 Activity
 * （因为系统授权框必须由 `ActivityResultLauncher` 发起）。
 */
class BatchActions(
    private val state: AppState,
    private val deleteFlow: DeleteFlow,
) {

    /** 正在跑的批量任务（返回键第一优先级要 cancel 它）。 */
    var currentJob: Job? = null
        private set

    private val scope get() = state.scopeForActions()

    /** 这一批到底要不处理哪几张：从**点击那一屏的列表**里按选中集过滤（与改前一致）。 */
    private fun picked(from: List<PhotoItem>): List<PhotoItem> = from.filter { it.id in state.selectedIds }

    /**
     * 批量删除。
     *
     * **先走回收站**（`Trash.moveToTrash`），搬不动才退回原来的真删。
     * 三条语义一个都没变：
     *  - 进度按**真的删掉几张**报（回收站搬成功也算"删掉了"：系统相册里确实看不见了）；
     *  - 结论走 `batchNote`（`deleteNote` / `consentNote`），不留在进度位；
     *  - 外来照片（`NEEDS_CONSENT`）仍然走系统授权链 —— 回收站只搬得动
     *    `Pictures/SuiPai` 下由本 app 写入的行（见 `Trash` 的类注释）。
     *
     * 为什么"先回收站、失败再真删"而不是二选一：回收站对小文件/元数据不全的照片
     * 会失败（打不开源图、源行删不掉），那种情况下**退回真删**才是用户点的那个意思；
     * 反过来（真删优先）就没有 30 天可退回了。
     *
     * `awaiting` 是因为**弹了授权框之后收尾交给回调**：这时候如果清理进度位，
     * 用户会先看到进度消失、几秒后结论才出现（中间那段是系统框，本来就不该有进度）。
     */
    fun batchDelete(from: List<PhotoItem>) {
        if (state.batchRunning) return
        val targets = picked(from)
        if (targets.isEmpty()) return
        currentJob = scope.launch(Dispatchers.IO) {
            val deleted = mutableListOf<Long>()
            val trashed = mutableListOf<Long>()
            val consent = ArrayList<Pair<Long, Uri>>()
            var refused = 0
            var awaiting = false
            try {
                targets.forEach { photo ->
                    // ① 本 app 的照片先进回收站（30 天可恢复）。
                    if (state.isOwnPhoto(photo) && state.moveToTrash(photo)) {
                        deleted += photo.id
                        trashed += photo.id
                    } else {
                        // ② 搬不动（或本来就是外来照片）→ 原来的真删那条路。
                        when (state.repository().deleteOne(photo.uri)) {
                            DeleteOutcome.DELETED -> deleted += photo.id
                            // 不是本应用存的东西：Android 11 起只能问用户要一次授权。
                            // 原来这条抛出的异常被 `runCatching` 吞成 false，
                            // 于是"删除"对外来照片永远是空转，还不说。
                            DeleteOutcome.NEEDS_CONSENT -> consent += photo.id to photo.uri
                            DeleteOutcome.FAILED -> refused++
                        }
                    }
                    withContext(Dispatchers.Main) {
                        state.batchPhase = BatchMachine.progress(
                            BatchMachine.Verb.DELETE, deleted.size, targets.size,
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    state.pruneForDeleted(deleted)
                    state.clearSelection()
                    state.batchPhase = BatchMachine.Phase.Idle
                    if (consent.isNotEmpty()) {
                        val req = state.repository().deleteRequestFor(consent.map { it.second })
                        state.consent = ConsentTicket(
                            ids = consent.map { it.first },
                            direct = deleted.size,
                            fromViewer = false,
                        )
                        awaiting = req != null
                        if (req != null) {
                            android.util.Log.i(
                                "DeleteFlow",
                                "弹系统授权框 " + consent.size + " 张（已直接删掉 " + deleted.size +
                                    " 张，其中进回收站 " + trashed.size + " 张）",
                            )
                            deleteFlow.launch(req)
                        } else {
                            state.batchNote = "删了 ${deleted.size} 张，" +
                                "${consent.size} 张要授权但这台机器没给入口"
                            state.consent = ConsentTicket.EMPTY
                        }
                    } else {
                        state.batchNote = deleteNote(deleted.size, refused, targets.size)
                    }
                    state.refresh()
                }
            } finally {
                if (!awaiting) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        state.batchPhase = BatchMachine.Phase.Idle
                    }
                }
            }
        }
    }

    /**
     * 批量复制副本。
     *
     * 与 [batchDelete] 同一个坑：进度原来按下标报 `i + 1/N`，而 `copyToSuiPai` 打不开源文件时
     * 返回 null 而不是抛，于是一张没存上也能把进度条走到 N/N。
     */
    fun batchCopy(from: List<PhotoItem>) {
        if (state.batchRunning) return
        val targets = picked(from)
        if (targets.isEmpty()) return
        currentJob = scope.launch(Dispatchers.IO) {
            var done = 0
            var failed = 0
            try {
                targets.forEach { photo ->
                    if (state.repository().copyToSuiPai(photo.uri) != null) done++ else failed++
                    withContext(Dispatchers.Main) {
                        state.batchPhase = BatchMachine.progress(
                            BatchMachine.Verb.COPY, done, targets.size,
                        )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    state.batchPhase = BatchMachine.Phase.Idle
                    state.clearSelection()
                    state.batchNote = copyNote(done, failed, targets.size)
                    state.refresh()
                }
            }
        }
    }

    /**
     * 批量收藏 / 取消收藏。
     *
     * 判定"这是取消还是收藏"用的是**选中集是否全在收藏里**（全在 = 取消，否则 = 全收）。
     * 结论等落盘回来再说（界面这一帧先动，格子已经收藏掉了）。
     */
    fun batchFavorite(from: List<PhotoItem>) {
        val targets = picked(from)
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }
        val releasing = ids.all { it in state.favoriteIds }
        val next = if (releasing) {
            state.favoritesStore().removeAll(ids)
        } else {
            state.favoritesStore().addAll(ids)
        }
        state.replaceFavoriteIds(next)
        state.clearSelection()
        scope.launch {
            val stored = withContext(Dispatchers.IO) { state.favoritesStore().persist() }
            state.batchNote = favoriteNote(releasing, ids.size, stored)
            state.repository().loadByIds(next)?.let { state.setFavoritePhotos(it) }
                ?: state.readMissed(listOf("收藏那份清单"))
            if (state.albumListing() != null && state.albumId == com.suipai.app.data.Favorites.ALBUM_ID) {
                state.setAlbumPhotos(state.favoritePhotos)
            }
            state.refreshAlbumsPublic()
        }
    }

    /** 批量隐藏 / 取消隐藏。与 [favorite] 同一套（含落盘失败那句）。 */
    fun batchHide(from: List<PhotoItem>) {
        val targets = picked(from)
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }
        val releasing = ids.all { it in state.hiddenIds }
        val next = if (releasing) {
            state.hiddenStore().removeAll(ids)
        } else {
            state.hiddenStore().addAll(ids)
        }
        state.replaceHiddenIds(next)
        state.clearSelection()
        scope.launch {
            val stored = withContext(Dispatchers.IO) { state.hiddenStore().persist() }
            state.batchNote = hideNote(releasing, ids.size, stored)
            state.repository().loadByIds(next)?.let { state.setHiddenPhotos(it) }
                ?: state.readMissed(listOf("已隐藏那份清单"))
            if (state.albumListing() != null && state.albumId == com.suipai.app.data.Hidden.ALBUM_ID) {
                state.setAlbumPhotos(state.hiddenPhotos)
            }
            state.refreshAlbumsPublic()
        }
    }

    /**
     * 批量一瞬。
     *
     * 两个细节照抄：
     *  - 已经是一瞬出品的（`SuiPai_一瞬_` 开头）不再拿去再出一遍；全是这种时才原样用。
     *  - 结论**不许**报张数当取消：`runDetailed` 的进度回调是 `onProgress(index + 1, total)`，
     *    那是尝试次数不是成功数，中途停下时手上没有可信的"出了几张"（`yishunNote` 里说清了）。
     */
    fun batchYishun(from: List<PhotoItem>, lookId: String) {
        if (state.batchRunning) return
        val chosen = picked(from)
        val fresh = chosen.filter { !it.displayName.startsWith("SuiPai_一瞬_") }
        val uris = (if (fresh.isEmpty()) chosen else fresh).map { it.uri }
        if (uris.isEmpty()) return
        currentJob = scope.launch(Dispatchers.Default) {
            var report: BatchReport? = null
            try {
                report = BatchYishun.runDetailed(state.appContext(), uris, lookId) { cur, total ->
                    withContext(Dispatchers.Main) {
                        state.batchPhase = BatchMachine.progress(
                            BatchMachine.Verb.YISHUN, cur, total,
                        )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    // 结论走 batchNote（4.5 秒自己退），不要留在进度位里：
                    // 那一格是进度位，写进去就再也没有人清它。
                    val r = report
                    state.batchPhase = BatchMachine.Phase.Idle
                    state.clearSelection()
                    state.batchNote = yishunNote(
                        r?.succeeded ?: 0, r?.failures?.size ?: 0, r == null,
                    )
                    state.refresh()
                }
            }
        }
    }

    /**
     * 用户按了"停止"（选择条那颗格子 / 返回键）。
     *
     * 取消 Job 之后收尾在各路 `finally` 里（`NonCancellable`）—— 这里只负责把进度位清掉。
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        state.batchPhase = BatchMachine.Phase.Idle
    }

    /** 取消并额外记一句"停了"（返回键路径用：改前 `batchHint = null` 之后什么都不说）。 */
    fun cancelQuietly() {
        currentJob?.cancel()
        currentJob = null
        state.batchPhase = BatchMachine.Phase.Idle
    }

    /** `CancellationException` 必须原样抛出去（`finally` 里的 NonCancellable 段不许吞它）。 */
    internal fun rethrowIfCancelled(e: Throwable) {
        if (e is CancellationException) throw e
    }
}

/** `persist()` 走 IO（`IdLists` 的 commit 是同步写盘）。 */
private suspend fun ioPersist(block: suspend () -> Boolean): Boolean =
    withContext(Dispatchers.IO) { block() }
