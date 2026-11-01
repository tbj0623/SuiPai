package com.suipai.app.data

import android.content.Context

/**
 * 「藏起来」那批 id。真身在 `IdLists` —— 和 `Favorites` 共用一份，因为两份清单写在同一个 prefs 文件里，
 * 各自 `commit()` 会把整个文件按对方那份旧快照重写一遍。
 *
 * 这一份是隐私：没落盘，下次打开那些照片就又在时间线里了，而屏幕上当时说的是「藏了 N 张」。
 * 所以改动虽然只进内存（界面这一帧就能看到），结论一定要等 `persist()` 的返回值回来再说。
 */
class Hidden(context: Context) {

    private val lists = IdLists.of(context)

    fun ids(): Set<Long> = lists.hiddenIds()

    fun isHidden(id: Long): Boolean = lists.isHidden(id)

    /** @return 改完之后的整份隐藏 id，调用点直接拿它往下传，别再回 prefs 取一次旧的。 */
    fun toggle(id: Long): Set<Long> = lists.toggleHidden(id)

    fun addAll(ids: Collection<Long>): Set<Long> = lists.addHidden(ids)

    fun removeAll(ids: Collection<Long>): Set<Long> = lists.removeHidden(ids)

    /**
     * 等两份清单落盘，返回写进设置了没有。
     * 写者挂在进程级作用域上：调用点所在的组合被取消，也不会带走这一次写 ——
     * 这一份是隐私，"界面说已藏而磁盘没落"不能靠用户别在写盘时切屏幕来避免。
     */
    suspend fun persist(): Boolean = lists.awaitPersisted()

    companion object {
        const val ALBUM_ID = "suipai-hidden"
    }
}
