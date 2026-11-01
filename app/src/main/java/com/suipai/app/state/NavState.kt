package com.suipai.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * 导航状态的**唯一持有者**：一个可观察的 [BackStack]。
 *
 * 为什么单独一个类而不是把栈塞进 `AppState`：`rememberSaveable` 要的是"一个能存进 Bundle 的值"，
 * 而这个值必须是导航的真身 —— 若栈是 [AppState] 的普通字段、`AppState` 又是 `remember`
 * 出来的，那它就不会被保存，改前那套 `rememberSaveable` 的返回栈恢复就全丢了。
 * 所以：`NavState` 可保存（[Saver]），[AppState] 持有它并调它。
 *
 * 这一层只做"栈怎么变"，不碰照片、不碰仓库；所有语义都在 [NavPolicy] 里（可单测）。
 */
class NavState(initial: BackStack = BackStack.of(AppRoute.Gallery)) {

    /**
     * 当前返回栈。恢复时先做一次 [sanitize]：
     * **查看器那一层不恢复** —— 它要显示的 `List<PhotoItem>` 本来就没进 Bundle
     * （改前 `viewerPhotos` 就是普通 `remember`，重建后为 null、界面回到 tab），
     * 留着一个没有数据的查看器路由只会闪一帧空屏。
     * 相册内页 / 一瞬 / 相机有各自的 id/uri 进 Bundle，改前也会恢复，所以照留。
     */
    var stack: BackStack by mutableStateOf(sanitize(initial))
        private set

    /** 当前该渲染哪一屏。 */
    val current: AppRoute get() = stack.current

    /** 上层有几层（不含底座 tab）。 */
    val depth: Int get() = stack.depth

    fun push(route: AppRoute) {
        stack = stack.push(route)
    }

    /**
     * 用 [route] 替换栈顶（不同 family 的"顶掉"）。
     *
     * 用在两处，都是旧代码里"两个布尔状态同时存在，但渲染只认最上面那个"的等价改写：
     *  - 相机里拍完选"做一瞬"：`showCamera = false` + `yishunUri = uri` → 相机层被一瞬顶掉；
     *  - 系统相机拍完回来：同上（内置相机那一层不退，改前一瞬关掉后回到相机）。
     */
    fun replaceTop(route: AppRoute) {
        stack = stack.pop().push(route)
    }

    /** 插到栈顶下面一层（快捷方式「拍照」落在一瞬下面）。规则见 [BackStack.insertBelowTop]。 */
    fun insertBelowTop(route: AppRoute) {
        stack = stack.insertBelowTop(route)
    }

    /**
     * 打开查看器并停在某一页。
     *
     * 为什么需要这个方法而不是让调用方写 `push(AppRoute.Viewer(i))`：**有一处必须能重复调用**——
     * `AppState.refresh()` 里"一瞬保存完跳回查看器"那一支（`jumpUri`）。从一瞬保存完
     * 回到查看器时，栈顶是一瞬、下面是相机；`push` 会把查看器压到最上面（对，因为 family 不同），
     * 但若重入一次就会叠第二个查看器。用 `popTo("viewer")` 优先：栈里已经有查看器就退回去，
     * 没有才压新的 —— 这样"保存 → 回到刚才那张"与"保存 → 打开查看器"两种情况都只有一层。
     */
    fun showViewerAt(index: Int) {
        stack = if (stack.containsFamily("viewer")) {
            stack.popTo("viewer").push(AppRoute.Viewer(index))
        } else {
            stack.push(AppRoute.Viewer(index))
        }
    }

    fun pop() {
        stack = stack.pop()
    }

    /**
     * 退到栈里某一族（含它自己）。它不在栈里时**原样返回**，不 pop。
     *
     * 用在"回到上一级列表页"：回忆详情里的「所有时刻」要回到回忆清单，
     * 但清单可能不在栈里（用户从别处直接进来）—— 那时什么都不做，
     * 而不是把用户退到某个不相干的地方。
     */
    fun popTo(family: String) {
        stack = stack.popTo(family)
    }

    /** 点底栏：换底座并清上层（规则见 [NavPolicy.resolveTab]）。 */
    fun switchTab(tab: AppRoute.Tab) {
        stack = NavPolicy.resolveTab(stack, tab)
    }

    /** 只换底座、保留上层（桌面快捷方式「互通」：旧代码写 `tab = 2`，屏幕不变）。 */
    fun rebaseTo(tab: AppRoute.Tab) {
        stack = stack.rebaseTo(tab)
    }

    /** 从栈里拿掉某一族（分享导入 / 快捷方式 / 一瞬保存后要把相机层摘掉）。 */
    fun removeFamily(family: String) {
        stack = stack.removeFamilies(setOf(family))
    }

    /** 一次摘掉好几族（快捷方式「一瞬」要同时关掉相机与查看器）。 */
    fun removeFamilies(vararg families: String) {
        stack = stack.removeFamilies(families.toSet())
    }

    /** 整个换掉（分享导入这类"从外面进来"的跳转）。 */
    fun reset(tab: AppRoute.Tab, layers: List<AppRoute> = emptyList()) {
        stack = stack.reset(tab, layers)
    }

    companion object {

        /**
         * 恢复时的清理：查看器不恢复（见 [stack] 的注释）。
         * 只摘最上面那个查看器层，不牵连下面的相册内页。
         */
        internal fun sanitize(restored: BackStack): BackStack = restored.removeFamilies(setOf("viewer"))

        /**
         * `rememberSaveable` 用的 Saver。存的是 [BackStack.encode] 的 `List<String>`，
         * 不是 Parcelable —— Bundle 里只放字符串列表，恢复时脏数据一律降级回照片页（见 `BackStack.restore`）。
         */
        val Saver: Saver<NavState, List<String>> = Saver(
            save = { it.stack.encode() },
            restore = { NavState(BackStack.restore(it)) },
        )
    }
}
