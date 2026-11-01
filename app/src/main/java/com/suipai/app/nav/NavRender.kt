package com.suipai.app.nav

import com.suipai.app.state.AppRoute
import com.suipai.app.state.BackStack

/**
 * 从返回栈里挑出**这一帧真的该画哪一屏**。
 *
 * 为什么不是直接 `stack.current`：改前那 7 个导航状态里，有的层"路由还在、数据已经没了"
 * （查看器删掉最后一张 → `viewerPhotos = null`；相册内页读失败 → `albumPhotos` 仍停在上一次的值）。
 * 改前的 `when` 是按**数据在不在**挑分支的，所以这里也按数据在不在裁掉那些层 ——
 * 否则会画出一屏空查看器。
 *
 * 优先级与改前的 `when` 完全一致：一瞬 > 相机 > 查看器 > 相册内页 > tab。
 * 栈的顺序天然与这个优先级一致（每一次 `push` 都发生在新层真的显示出来的时候），
 * 单测 `NavRenderTest` 钉住"栈顶向下取第一个可画的"这件事。
 */
object NavRender {

    /**
     * @param cameraReady 相机层在栈里（相机自己管权限与预览，不需要外部数据）
     * @param viewerReady 查看器那一层有 `viewerPhotos`
     * @param albumReady 相册内页那一层有 `albumPhotos`
     *
     * **没有 `yishunReady`**：一瞬要显示的 uri 就是路由自己（`AppRoute.Yishun.uri`），
     * 它不存在"路由在、数据没了"的状态。其它三个都可能有（数据在 `AppState` 里）。
     * 曾经写过一个 `yishunReady` 参数，单测当场判红：那一格会让"关不掉一瞬"变成可能。
     */
    fun effective(
        stack: BackStack,
        cameraReady: Boolean = true,
        viewerReady: Boolean = true,
        albumReady: Boolean = true,
    ): AppRoute {
        for (route in stack.layers.asReversed()) {
            val ready = when (route) {
                is AppRoute.Yishun -> true
                is AppRoute.Camera -> cameraReady
                is AppRoute.Viewer -> viewerReady
                is AppRoute.AlbumDetail -> albumReady
                is AppRoute.Tab -> false     // 不许出现在 layers 里（BackStack.push 已挡）

                // ── 二级页面：全部"进去就能画" ────────────────────────
                // 它们不靠 `AppState` 里某个可变列表存在才成立（设置页读 prefs、
                // 回收站读文件、搜索页自己起索引器）。数据读不到时由**屏幕自己**说
                // "回收站是空的 / 索引失败"，不许在导航层被裁掉 ——
                // 否则用户点进回收站却回到了画廊，而且一句解释都没有。
                is AppRoute.Settings,
                is AppRoute.SettingsAppearance,
                is AppRoute.SettingsStorage,
                is AppRoute.SettingsAbout,
                is AppRoute.Backup,
                is AppRoute.Privacy,
                is AppRoute.Onboarding,
                is AppRoute.Trash,
                is AppRoute.Collections,
                is AppRoute.CollectionDetail,
                is AppRoute.Memories,
                is AppRoute.MemoryDetail,
                is AppRoute.Search,
                is AppRoute.Collage,
                is AppRoute.Slideshow,
                is AppRoute.Duplicates,
                is AppRoute.StorageInsight,
                is AppRoute.Videos,
                // 播放器的 `VideoItem` 在 `AppState` 里（不进 Bundle），所以"路由在、数据没了"
                // 这一格真实存在 —— 但**不能**在这里裁掉它：裁掉会静默退回列表，
                // 用户看到的是"点了一下视频就回去了"而没有任何解释。
                // 宿主自己会画一句「这一段找不到了」并给一个返回键（见 `AppNavHost` 的
                // `VideoPlayer` 分支），所以它算"进去就能画"。
                is AppRoute.VideoPlayer,
                is AppRoute.RecipeCard,
                -> true

                // 幻灯片与拼图要照片列表（不进路由）。列表没了就退回下面那一层 ——
                // 与查看器同一条规则，避免画出一屏空幻灯片。
                // （写在这里只为了让 when 可读；`Slideshow`/`Collage` 的 ready 由宿主在
                //   传参时决定，见 `AppNavHost`：列表为空时它不 push 这两个路由。）
            }
            if (ready) return route
        }
        return stack.base
    }
}
