package com.suipai.app.state

/**
 * 批量动作的**纯状态机**。不含仓库调用、不含协程 —— 那些在 `BatchActions.kt`。
 *
 * 这一层存在的理由：改前那 5 个 `batch*` 函数里"界面说什么"与"仓库做了什么"缠在一起，
 * 于是三个语义在代码里看不出来，只能靠读注释：
 *
 *  1. **进度按真实成功数报**（`PerfSourceGuardTest.attemptCountProgress` 钉的那条）。
 *     写 `i + 1 / total` 时，23 张一张没删掉也能把进度条走到 23/23。
 *  2. **结论走 [Phase.Done]，永远不留在进度位**（`PerfSourceGuardTest:932` 钉的那条）。
 *     进度位是"处理中"那一格，写进去就再也没人清它 —— 装机上表现为那句结论永不消失。
 *  3. **被取消时也要收尾**（调用方用 `NonCancellable`），且取消时**不许**报张数：
 *     中途停下时手上没有可信的"成了几张"。
 *
 * 状态转移全是纯函数，每一步都能在 JVM 单测里断言"这一步界面该显示什么"。
 */
object BatchMachine {

    /**
     * 进度位那句话的动词前缀。
     *
     * 三个批量动作在改前各写各的，**必须逐字保留**（`GalleryScreen` 把它们原样贴进
     * 「处理 …」和页眉那一格，装机探针按这两处文字找节点）：
     *  - 删除：`已删 3/23`
     *  - 副本：`已存 3/23`
     *  - 一瞬：`7/23`（引擎回调给的就是这个形状，没有动词）
     */
    enum class Verb(val prefix: String) {
        DELETE("已删 "),
        COPY("已存 "),
        YISHUN(""),
    }

    /** 批量任务的三种状态。 */
    sealed interface Phase {
        /** 没有任务在跑。 */
        data object Idle : Phase

        /**
         * 跑着。[done] 是**真的做成功的张数**（不是试到第几张），[total] 是这批一共几张。
         * 界面把它渲染成「处理 已删 3/23」。
         */
        data class Running(val done: Int, val total: Int, val verb: Verb) : Phase

        /**
         * 结束了。[note] 是那句结论（来自 `ui/common/BatchNote.kt` 的纯函数，非空）。
         * [cancelled] = 用户中途停的，此时 [note] 里不报张数。
         */
        data class Done(val note: String, val cancelled: Boolean = false) : Phase
    }

    /**
     * 进度位的文案。`null` = 不显示。
     *
     * Idle 与 Done 都返回 null：结论已经走了 [Phase.Done.note]，若这一格还显示"处理 3/23"，
     * 用户看到的是一条永远走不完的进度条（改前 `batchYishun` 就把失败那句写在这里，
     * 于是它再也没人清）。
     */
    fun hint(phase: Phase): String? = when (phase) {
        Phase.Idle -> null
        is Phase.Running -> phase.verb.prefix + phase.done + "/" + phase.total
        is Phase.Done -> null
    }

    /** 界面这一帧该不该显示"停止"而不是"取消"。只有跑着的时候是停止。 */
    fun running(phase: Phase): Boolean = phase is Phase.Running

    /**
     * 收到一次进度。`done` 必须是**真的成了几张**，不是循环下标。
     *
     * 越界不抛异常（那会把一个显示问题升级成崩溃）：夹回 `0..total`。
     * 正常调用点不会越界 —— 单测钉住"越界被夹住"是为了让误用不炸。
     */
    fun progress(verb: Verb, done: Int, total: Int): Phase {
        val bound = total.coerceAtLeast(0)
        return Phase.Running(done = done.coerceIn(0, bound), total = bound, verb = verb)
    }

    /** 从"跑着"推进到新的成功数；不在跑就原样返回（幂等，late callback 不会复活任务）。 */
    fun advance(phase: Phase, done: Int): Phase {
        if (phase !is Phase.Running) return phase
        return phase.copy(done = done.coerceIn(0, phase.total))
    }

    /** 收尾。结论由调用方用量词纯函数算好（`deleteNote` / `copyNote` / `yishunNote` …）。 */
    fun finish(note: String, cancelled: Boolean = false): Phase =
        Phase.Done(note = note, cancelled = cancelled)
}
