package com.suipai.app.state

/**
 * `AppState.refresh()` 的**决策纯函数**：这一趟到底还要不要真去读媒体库。
 *
 * ## 它挡的是什么
 * `refresh()` 被 `LifecycleEventEffect(ON_RESUME)` 触发，另有 20 来处主动调用。
 * 原来每次都是四份清单全量重读：`readLibrary()`（时间线 + 相册清单）+
 * `loadByIds(收藏)` + `loadByIds(隐藏)` + 条件性的 `loadAlbum(id)`。
 * 5000 张的库上这是回到 app 第一帧的主要开销，而绝大多数 `ON_RESUME` 发生的时候
 * 库里**什么都没变**（用户只是切了个应用再切回来）。
 *
 * ## 为什么是"generation"而不是"按路由挑一份读"
 * `state.photos` 不是只有相册页在用：搜索、收藏夹详情、回忆、幻灯片、页眉那颗
 * 「最近一张」入口全从它派生。只读当前路由那一份，退页之后别的屏看到的是旧数据 ——
 * 那是一类"看起来正常、只有装机走一条长路径才发现"的错。所以这里不问"现在看哪一屏"，
 * 只问"库有没有变"。
 *
 * ## 为什么是纯函数 + 对象
 * 决策的全部风险都在边界上（首次 `lastGen == null`、提供方不给 generation、
 * TTL 刚过期那一下、id 集合同不同），而这些在 JVM 上都能钉死；真正去查
 * `MediaStore.getGeneration` 的那一步反而没什么可测的。
 * 计数器本身由 `AppState` 持有，这里连状态都没有。
 */
internal object RefreshGate {

    /**
     * 时间线/相册清单要不要重读。
     *
     * @param force 调用方明确知道"库刚被改过"（下拉刷新、保存了一瞬、导入分享、
     *   恢复备份、拼图落盘……），必须真读。
     * @param lastGen 上一次**成功读完之后**看到的 generation；`null` = 还没成功读过。
     * @param gen 这一趟现查到的 generation；`null` = 这台机器/这个提供方给不出来。
     * @param ageMs 距上一次成功读完整库过去了多久。
     * @param ttlMs 最多允许缓存这么久。过期就重读 —— 这是"库里变了但我们观测不到"
     *   （某些 ROM 的 generation 不更新、或提供方重新挂载后计数器归零）时的兜底。
     *
     * 三条"必读"，每一条都对应一类会把界面卡在旧数据上的情形：
     *  1. `force` —— 调用方刚改过库；
     *  2. `lastGen == null` —— 首次冷启动，没有任何可用的缓存；
     *  3. `gen == null` —— 拿不到变更信号，等于无法判断，只能读。
     * 其余情况必须**同时**满足"代数没变"与"没到 TTL"才跳过。
     */
    fun needsLibraryRead(
        force: Boolean,
        lastGen: String?,
        gen: String?,
        ageMs: Long,
        ttlMs: Long = 60_000L,
    ): Boolean {
        if (force) return true
        if (lastGen == null) return true
        if (gen == null) return true
        if (lastGen != gen) return true
        // `ageMs` 为负只可能是时钟被调过：那是"不可信"，按到期处理，别拿它当新鲜。
        if (ageMs < 0L) return true
        return ageMs >= ttlMs
    }

    /**
     * 按 id 读的那两份清单（收藏 / 隐藏）要不要重读。
     *
     * 它们的"库变没变"与 generation 无关：这份数据只取决于 id 集合本身
     * （`trail.json` / prefs 里的清单）与那些行还在不在。行没了会被
     * `AppState.pruneForDeleted` 摘掉 id，所以 id 集合相同 = 内容相同。
     *
     * ⚠️ 注意这条**只**用于"跳过"，不用于"证明数据是对的"：
     * 调用方仍然可能在 id 没变的情况下丢掉行（别的 app 删了我们的照片），
     * 那种情况由 generation 那一支兜住（库变了 → 全量重读）。
     */
    fun needsIdsRead(last: Set<Long>, now: Set<Long>): Boolean = last != now
}
