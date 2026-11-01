package com.suipai.app.data

import android.content.Context

/**
 * 「收藏」那批 id。真身在 `IdLists` —— 和 `Hidden` 共用一份，因为两份清单写在同一个 prefs 文件里，
 * 各自 `commit()` 会把整个文件按自己那份旧快照重写一遍。
 *
 * 界面要立刻看到变化 → 改动只动内存；"这一步落盘了没有"必须仍然观察得到 →
 * 落盘由 `persist()` 在 IO 线程上做，失败时调用点用 `notStoredTail` 说出来。
 */
class Favorites(context: Context) {

    private val lists = IdLists.of(context)

    fun ids(): Set<Long> = lists.favoriteIds()

    fun isFavorite(id: Long): Boolean = lists.isFavorite(id)

    /** @return 改完之后的整份收藏 id，调用点直接往下传，别再回 prefs 取一次旧的。 */
    fun toggle(id: Long): Set<Long> = lists.toggleFavorite(id)

    fun addAll(ids: Collection<Long>): Set<Long> = lists.addFavorites(ids)

    fun removeAll(ids: Collection<Long>): Set<Long> = lists.removeFavorites(ids)

    /**
     * 等两份清单落盘，返回写进设置了没有。
     * 写者挂在进程级作用域上：调用点所在的组合被取消，也不会带走这一次写。
     */
    suspend fun persist(): Boolean = lists.awaitPersisted()

    companion object {
        const val ALBUM_ID = "suipai-favorites"
    }
}
