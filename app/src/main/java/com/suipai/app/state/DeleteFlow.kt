package com.suipai.app.state

import androidx.activity.result.IntentSenderRequest
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.consentNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "删除 → RecoverableSecurityException → 系统授权框 → 回来查 stillPresent → 说结论"这条链。
 *
 * 为什么单独一个类：这条链是全项目最容易静默出错的一处（改前注释里记着两次装机事故），
 * 而且它跨越了**两个世界**：
 *
 *  - 发起授权框要 `ActivityResultLauncher`（只能在 Activity 里、只能在主线程、只能在 STARTED 之后）；
 *  - 回来之后的判定要读媒体库（IO）与改界面（Main）。
 *
 * 所以这里用回调把 `launchConsent` 交给 Activity（`MainActivity` 里那个
 * `rememberLauncherForActivityResult`），其余全部收在这个类里。
 *
 * 三段语义逐字对照改前：
 *
 * 1. **回来之后不猜用户点了什么**：直接查媒体库看哪几张真的没了。
 * 2. **没查到 ≠ 都删了**：`stillPresent` 返回 null 时不许顺嘴报一句"删了"，
 *    要说"没查到这几张还在不在，去相册里看一眼"。
 * 3. **系统框可以只放一部分**：所以三件事分开说 —— 真没了的、被扣下的、弹框之前就直删掉的
 *    （都由 `consentNote` 一次说清）。
 */
class DeleteFlow(
    private val state: AppState,
    private val consentLauncher: (IntentSenderRequest) -> Unit,
) {

    private val scope get() = state.scopeForActions()

    /**
     * 记录"要弹授权框了"并把它弹出去。
     *
     * 调用前**必须先写好票据**（`state.consent`）：授权框还挂着时重建 Activity，
     * 回调照常投递，这颗票据必须已经在 `rememberSaveable` 里。
     */
    fun launch(req: IntentSenderRequest) {
        consentLauncher(req)
    }

    /**
     * 授权框回来的回调（Activity 里那个 launcher 的 lambda 直接调这里）。
     *
     * 先在主线程取快照、作废票据，再切 IO 查库 —— 顺序与改前一致：
     * 票据的清理必须在**任何挂起点之前**完成，否则重建一次就重复消费。
     */
    fun onConsentResult() {
        val ticket = state.consent
        val asked = ticket.ids
        val already = ticket.direct
        val fromViewer = ticket.fromViewer
        // 这条链的两个末端（系统框回来了 / 用户根本没点）在界面上只差一句 4.5 秒的文案，
        // 装机复测时"没看到文案"和"回调没跑"长得一样。留一行日志把两者分开。
        android.util.Log.i(
            "DeleteFlow",
            "授权框回来 asked=" + asked.size + " 已直接删=" + already + " 来自查看器=" + fromViewer,
        )
        state.consent = ticket.consumed()
        if (asked.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val still = state.repository().stillPresent(asked)
            if (still == null) {
                // 没查到就是没查到，不许顺嘴报一句"删了"。
                android.util.Log.i("DeleteFlow", "查库失败，" + asked.size + " 张不下结论")
                withContext(Dispatchers.Main) {
                    state.batchNote = "没查到这几张还在不在，去相册里看一眼"
                    state.consent = ConsentTicket(ids = emptyList(), direct = 0)
                    state.refresh()
                }
                return@launch
            }
            val gone = asked.filter { it !in still }
            withContext(Dispatchers.Main) {
                if (gone.isNotEmpty()) {
                    state.pruneForDeleted(gone)
                    state.setPhotos(state.photosListing().filter { it.id !in gone })
                    state.setAlbumPhotos(state.albumListing()?.filter { it.id !in gone })
                    if (fromViewer) {
                        val next = state.viewerPhotos?.filter { it.id !in gone }.orEmpty()
                        if (next.isEmpty()) {
                            state.closeViewer()
                        } else {
                            state.viewerIndex = state.viewerIndex.coerceAtMost(next.lastIndex)
                            state.setViewerPhotos(next)
                        }
                    }
                }
                // 系统那个框可以只放一部分，所以三件事要分开说：
                // 真没了的、被扣下的、弹框之前就直删掉的。
                state.batchNote = consentNote(gone.size, asked.size, already)
                state.refresh()
            }
        }
    }

    /**
     * 查看器里删单张。
     *
     * 四条出口（第一条是本轮新增的回收站）：
     *  1. 本 app 的照片 → 搬进回收站（30 天可恢复），搬成功就收尾；
     *  2. 搬不动 / 本来就是外来照片 → 原来的真删（`DELETED` 也算收尾）；
     *  3. 真删要授权 → 弹系统框（票据标 `fromViewer`，回来时顺手修查看器那一页）；
     *  4. 真删失败 → 说"这张删不掉"。
     *
     * 顺序是**先回收站、再真删**：反过来就没有 30 天可退回了。
     */
    fun deleteOneFromViewer(photo: PhotoItem) {
        scope.launch {
            // ① 本 app 的照片先进回收站。
            val trashed = state.isOwnPhoto(photo) && state.moveToTrash(photo)
            if (!trashed) {
                // ② 搬不动或外来照片 → 原来的真删那条路。
                when (state.repository().deleteOne(photo.uri)) {
                    com.suipai.app.data.DeleteOutcome.DELETED -> Unit
                    com.suipai.app.data.DeleteOutcome.NEEDS_CONSENT -> {
                        // 外来照片：弹一次系统确认，回来再按"还在不在"收尾
                        val req = state.repository().deleteRequestFor(listOf(photo.uri))
                        if (req == null) {
                            state.batchNote = "这张要授权，但这台机器没给入口"
                            return@launch
                        }
                        state.consent = ConsentTicket(
                            ids = listOf(photo.id),
                            direct = 0,
                            fromViewer = true,
                        )
                        consentLauncher(req)
                        return@launch
                    }
                    com.suipai.app.data.DeleteOutcome.FAILED -> {
                        state.batchNote = "这张删不掉"
                        return@launch
                    }
                }
            }
            state.pruneForDeleted(listOf(photo.id))
            val next = state.viewerPhotos?.filter { it.id != photo.id }.orEmpty()
            state.setPhotos(state.photosListing().filter { it.id != photo.id })
            state.setAlbumPhotos(state.albumListing()?.filter { it.id != photo.id })
            state.selectedIds = state.selectedIds - photo.id
            if (next.isEmpty()) {
                state.closeViewer()
            } else {
                state.viewerIndex = state.viewerIndex.coerceAtMost(next.lastIndex)
                state.setViewerPhotos(next)
            }
            state.refresh()
        }
    }
}
