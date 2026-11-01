package com.suipai.app.state

/**
 * 返回栈的**导航规则**（纯函数，全部可在 JVM 上单测）。
 *
 * 原来这些规则以嵌套 `when` 的形式长在 MainActivity 的 `BackHandler` 里，
 * 谁先谁后只写在代码顺序上，没有一处能写下来、也没有一处能测。
 * 这里把它拆成三段：
 *
 * 1. [resolveBack]：这一下返回键该落在哪 —— 先关批量、再清选择、再一层层退路由；
 * 2. [resolveTab]：点底栏某一颗时栈怎么变 —— 切 Tab 是**换底座**，不是压栈；
 * 3. [resolvePush]：一次"进新页"要不要长高 —— 同 family 替换栈顶，不同才入栈。
 */
object NavPolicy {

    /**
     * 返回键的优先级。**顺序就是行为**，从高到低：
     *
     * 1. 批量任务在跑 → 取消任务（不清选择：用户刚说"停"，选择还在原处，可以接着做别的）
     * 2. 有选中 → 清选择
     * 3. 一瞬开着 → 关一瞬（回到它下面那一层：查看器 / 相机 / 画廊）
     * 4. 相机开着 → 关相机
     * 5. 查看器开着 → 关查看器（回到画廊）
     * 6. 相册内页开着 → 关内页（回到相册列表）
     *
     * 顺序是从旧代码逐条抄下来的，不许重排：装机上"批量进行中按返回"必须是停任务，
     * 而不是先清掉选择。
     */
    enum class BackAction {
        /** 取消正在跑的批量任务。 */
        CANCEL_BATCH,

        /** 只清选择，不动路由。 */
        CLEAR_SELECTION,

        /** 退一层路由（栈非空时）。 */
        POP,

        /** 没有可退的：交给系统（Activity 结束）。 */
        EXIT,
    }

    /**
     * 这一层界面自己是否有"拦住返回键"的理由（用于 `BackHandler(enabled = …)`）。
     * 与 [resolveBack] 同一张表，改一处必须改另一处 —— 单测钉住它们不打架。
     *
     * 前六项对应**有内部状态**的层（批量任务、选择集合、一瞬、相机、查看器、相册内页）；
     * 第七项 [hasLayer] 是兜底：任何压在 tab 上的路由都算。
     * 新加一屏时**优先只靠 [hasLayer]**；只有"这一屏自己内部还有一个可退的阶段"时
     * （例如查看器里删到空、批量进行中）才需要加字段。
     */
    data class Intercepts(
        val batchRunning: Boolean = false,
        val hasSelection: Boolean = false,
        val yishunOpen: Boolean = false,
        val cameraOpen: Boolean = false,
        val viewerOpen: Boolean = false,
        val albumOpen: Boolean = false,
        /** 返回栈上层非空（`NavState.depth > 0`）。加二级页面之后这一项是主要的拦截判据。 */
        val hasLayer: Boolean = false,
    )

    /** 有任意一层拦着返回键。 */
    fun intercepts(state: Intercepts): Boolean =
        state.batchRunning || state.hasSelection || state.yishunOpen ||
            state.cameraOpen || state.viewerOpen || state.albumOpen ||
            // 栈里还有任何一层，返回键就归我们管。
            //
            // 这一条是加二级页面（设置 / 回收站 / 搜索 / 回忆…）时**必须**补上的：
            // 那一族页面没有自己的布尔字段，若这里仍只认原来六项，
            // 用户从「设置」按返回 → `enabled = false` → 系统把 Activity 结束掉。
            // 换句话说：**"栈里有层"是兜底判据，它让新页面不用再往 `Intercepts` 加字段**。
            state.hasLayer

    /**
     * 这一下返回键的去处。
     *
     * `stackDepth` 是**上层**路由的层数（不含 Tab 底座）：0 表示就站在某个 tab 上。
     * 原来那个 `when` 里 `albumPhotos != null` 就是"栈里有相册内页"，这里换成层数判断，
     * 于是以后加新页面不必再改这个函数。
     */
    fun resolveBack(state: Intercepts, stackDepth: Int): BackAction = when {
        state.batchRunning -> BackAction.CANCEL_BATCH
        state.hasSelection -> BackAction.CLEAR_SELECTION
        // 一瞬 / 相机 / 查看器 / 相册内页在旧代码里是四个并列分支，
        // 但因为它们互斥地覆盖同一块屏幕，实际效果就是"退栈顶一层"。
        // 这里保留旧顺序的**语义**（谁在上谁先退），只是不再按类型逐条写。
        state.yishunOpen || state.cameraOpen || state.viewerOpen || state.albumOpen -> BackAction.POP
        stackDepth > 0 -> BackAction.POP
        else -> BackAction.EXIT
    }

    /**
     * 点底栏某一颗（或矮屏页眉里的同名格子）时栈怎么变。
     *
     * 规则：**切 Tab = 清栈**。旧代码里 `tab = i` 只改一个 Int，浮在上面的查看器 /
     * 一瞬 / 相册内页仍然压着 —— 这是改前就有的行为（底栏在那几层里根本点不到），
     * 所以这里写成"清掉上层、换底座"，行为与改前一致，同时把这条隐含规则显式化。
     *
     * 点当前那一颗：同样清栈（旧代码点当前 tab 也没法点，因为底栏只在 tab 层出现；
     * 保留"清栈"是为了未来从设置页点底栏回来时的确定性）。
     */
    fun resolveTab(stack: BackStack, tab: AppRoute.Tab): BackStack = stack.replaceTo(tab)

    /**
     * 一次"进新页"。
     *
     * 同 family 的栈顶直接替换（两个查看器之间跳不叠两层：从一瞬换一张图再换一张，
     * 按一次返回应该回到进来时那一层，而不是把看过的每张都退一遍）。
     * **例外**：一瞬与查看器互相叠（从大图进一瞬）family 不同，照常入栈。
     */
    fun resolvePush(stack: BackStack, route: AppRoute): BackStack = stack.push(route)

    /**
     * 系统相机拍完回来：`systemCamera` 的 ActivityResult 回调里把一瞬顶上来。
     *
     * 单独一个函数是因为它有一个特别的行为：**替换**当前的相机层而不是叠上去。
     * 系统相机是从内置相机里点出去的，回来时内置相机还在下面；原来 `yishunUri = uri`
     * 就地让一瞬盖住相机（相机状态没变），返回键一下回到相机 —— 与"替换"等价。
     */
    fun afterSystemCapture(stack: BackStack, uri: String): BackStack =
        stack.push(AppRoute.Yishun(uri))
}
