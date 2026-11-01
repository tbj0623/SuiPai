package com.suipai.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * 「当前选中了哪几张」的**唯一持有者**，可进 `rememberSaveable`。
 *
 * ## 为什么单独一个类
 * `selectedIds` 原来只是 `AppState` 里的一个 `mutableStateOf`，而 `AppState` 是普通
 * `remember`（它要持有 `Context` / `CoroutineScope`，整体没法进 Bundle）。
 * 于是分屏、折叠屏换外屏、切系统深色、进程被杀之后重建 MainActivity —— 选中的一批格子
 * 静默全丢（`docs/arch/APP-ARCH.md` §九.1 记的就是这一条）。
 *
 * 修法**不是**给 `AppState` 整体加 Saver（那会把 `photos` 一起塞进 Bundle），
 * 而是把"可保存的那一格"拆出来：本类进 Saver，`AppState` 持有一个实例并把
 * `selectedIds` 转发到 [ids] 上 —— 于是 8 个写入点与所有只读点一个字都不用改。
 *
 * ## 恢复后的第一帧
 * Bundle 里**只存 id**，不存照片（照片是几个 MB 级的数据，Bundle 放不下也不需要：
 * 恢复之后已有的 `ON_RESUME → AppState.refresh()` 会把 `photos` 读回来）。
 * 代价是恢复后那一小段时间里选择条会闪一帧「已选 N 张 · 0 B」（`photos` 还是空的，
 * 算不出字节数）。这是 `APP-ARCH.md` §九.1 已经预见并接受的取舍 ——
 * **不要**为了这一帧去新增可见状态（"正在恢复选择"之类），那把一次闪变成了一个状态。
 */
class SelectionState(initial: Set<Long> = emptySet()) {

    /**
     * 选中的 MediaStore 行号。
     *
     * 写成 `var ... internal set`：写入只该经过 [AppState] 那 8 个动作
     * （toggle / add / clear / 全选 / 反选 / 删除后摘掉……），外面直接赋值会绕过
     * "批量动作收尾要清选择"这类约定。
     */
    var ids by mutableStateOf(initial)
        internal set

    companion object {

        /**
         * 最多**存**这么多个 id。
         *
         * Bundle 是 1 MB 硬上限（与 `AppRoute.Collage.MAX_IDS` 那条注释同一个口径）；
         * 一个 `Long` 在 `LongArray` 里是 8 字节，5000 个 = 40 KB，离上限有两个数量级，
         * 所以这个数不是性能考虑，而是"再多的选择本来也不该指望重建"。
         */
        const val MAX_SAVED = 5000

        /**
         * `rememberSaveable` 用的 Saver。存 `LongArray`（不是 `Set`）：
         * Bundle 能直接放原生长度数组，不需要 Parcelable，也不需要序列化。
         *
         * **超过 [MAX_SAVED] 就整体不存**，不是截断 —— 截断会让恢复后的
         * 「已选 N 张」变成一个**错误的** N'（用户选的是 6000，恢复成 5000，
         * 界面据此说"已选 5000 张"，而批量动作真的会作用到那 5000 张）。
         * 整体不存则是"这次没保住选择"，与改前行为一致，更好解释。
         *
         * 保存前 `sorted()`：`Set` 的迭代顺序取决于插入顺序，同一组 id 经不同路径
         * （点选 vs 框选）选出来会得到不同的数组，于是 `SavedStateHandle` 的相等判断
         * 与"重建前后 Bundle 是否一致"这类断言都会飘。排序之后是确定性输出。
         */
        val Saver: Saver<SelectionState, LongArray> = Saver(
            save = {
                if (it.ids.size <= MAX_SAVED) it.ids.sorted().toLongArray() else LongArray(0)
            },
            restore = { SelectionState(it.toSet()) },
        )
    }
}
