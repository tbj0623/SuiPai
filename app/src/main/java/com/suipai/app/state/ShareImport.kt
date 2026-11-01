package com.suipai.app.state

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 外部分享导入（别的 App「分享 → 随拍」）。
 *
 * 两个语义照抄，都是踩出来的：
 *
 * 1. **先落成 Pictures/SuiPai 里的真照片**（临时授权一读就没），再直接进一瞬。
 * 2. **收完再清收件箱**。先清等于改了这个 effect 自己的 key，`LaunchedEffect` 会立刻
 *    取消正在跑的协程 —— 导入会在第一个挂起点上静默死掉：不崩、不落库、也不报错
 *    （真机上实测到过）。所以 [drain] 里是"读完 → 说结论 → 清空 → 改导航"这个顺序。
 */
class ShareImport(
    private val state: AppState,
    private val scope: CoroutineScope,
) {

    /** 待导入的外部分享 uri。Activity 的 onCreate / onNewIntent 都往这里放。 */
    var inbox by mutableStateOf<List<Uri>>(emptyList())
        private set

    /** 调试包才出声（URI 属于用户内容，不许进正式包日志）。 */
    var verbose: Boolean = false

    /** Activity 收集到一次分享（已经过滤成 content:// 与 file://）。 */
    fun receive(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (verbose) android.util.Log.d(SHARE_TAG, "收到 ${uris.size} 条")
        inbox = uris
    }

    /**
     * 消费收件箱。由 `AppNavHost` 的 `LaunchedEffect(shareImport.inbox)` 调，
     * key 是收件箱本身 —— 所以清空放在**最后**（见类注释）。
     *
     * 导入成功时：关相机、关查看器、清选择、进一瞬、刷新。
     * （改前那段 `showCamera = false; viewerPhotos = null; selectedIds = emptySet()`
     * 在导航层面就是"这两个层被一瞬顶掉"，由 [NavState] 一并完成。）
     */
    fun drain(onImported: (Uri) -> Unit) {
        val batch = inbox
        if (batch.isEmpty()) return
        scope.launch {
            val imported = batch.mapNotNull { state.repository().copyToSuiPai(it) }
            if (verbose) android.util.Log.d(SHARE_TAG, "导入 ${imported.size}/${batch.size}")
            ShareImportOutcome.note(batch.size, imported.size)?.let { state.batchNote = it }
            // 收完再清（顺序承重，见类注释）
            inbox = emptyList()
            if (imported.isNotEmpty()) {
                state.closeViewer()
                state.clearSelection()
                onImported(imported.first())
                // 刚往库里写入了新行：这一次必须重读，不能等 `generation()` 传播。
                state.refresh(force = true)
            }
        }
    }

    private companion object {
        const val SHARE_TAG = "SuiPaiShare"
    }
}
