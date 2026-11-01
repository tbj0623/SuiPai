package com.suipai.app.nav

import com.suipai.app.state.AppRoute
import com.suipai.app.state.BackStack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `NavRender` 的单测：**这一帧该画哪一屏**。
 *
 * 这是改前那个 `when { 一瞬 -> …; 相机 -> …; 查看器 -> …; 相册内页 -> …; else -> tab }`
 * 的等价物。为什么值得单独钉：改前的分支是按**数据在不在**挑的，不是按"有没有进过这一屏"——
 * 查看器删到最后一张时 `viewerPhotos = null`，那一帧必须落回画廊，而不是画一屏空查看器。
 * 这种"路由还在、数据已经没了"的时刻只在运行时出现，光靠返回栈的单测看不出来。
 */
class NavRenderTest {

    private val gallery = AppRoute.Gallery

    /** 防的 bug：栈空时返回 null / 抛异常（本该画 tab）。 */
    @Test
    fun emptyStackRendersTheBaseTab() {
        assertEquals(AppRoute.Albums, NavRender.effective(BackStack.of(AppRoute.Albums)))
    }

    /** 防的 bug：有上层时还画底座（查看器打开着，屏幕却是画廊）。 */
    @Test
    fun topLayerWinsWhenItsDataIsThere() {
        val s = BackStack.of(gallery).push(AppRoute.Viewer(0))
        assertEquals(AppRoute.Viewer(0), NavRender.effective(s))
    }

    /**
     * 防的 bug（改前真实存在的那一帧）：查看器里删掉最后一张 → `viewerPhotos = null`，
     * 但返回栈里那一层还在。必须落回下面那屏，不许画空查看器。
     */
    @Test
    fun viewerWithoutDataFallsBackToTheBase() {
        val s = BackStack.of(gallery).push(AppRoute.Viewer(0))
        assertEquals(gallery, NavRender.effective(s, viewerReady = false))
    }

    /** 防的 bug：查看器下面还有相册内页时，数据没了要落到内页而不是直接回 tab。 */
    @Test
    fun viewerWithoutDataFallsBackToTheAlbumBelow() {
        val s = BackStack.of(gallery)
            .push(AppRoute.AlbumDetail("b", "册"))
            .push(AppRoute.Viewer(0))
        assertEquals(
            AppRoute.AlbumDetail("b", "册"),
            NavRender.effective(s, viewerReady = false),
        )
    }

    /** 防的 bug：相册内页数据没了还画内页（读失败时 `albumPhotos` 被清空的那一帧）。 */
    @Test
    fun albumWithoutDataFallsBackToTheTab() {
        val s = BackStack.of(gallery).push(AppRoute.AlbumDetail("b", "册"))
        assertEquals(gallery, NavRender.effective(s, albumReady = false))
    }

    /**
     * 防的 bug：一瞬被误判成"数据没就绪"就退不下去（屏幕卡在一瞬上面）。
     * 它的 uri 就在路由里，不存在"路由在、数据没了"这一格 —— 所以 `effective` 里
     * **没有** `yishunReady` 参数（第一版写了，就是这条判红的）。
     */
    @Test
    fun yishunIsAlwaysReady() {
        val s = BackStack.of(gallery).push(AppRoute.Yishun("content://a"))
        assertEquals(AppRoute.Yishun("content://a"), NavRender.effective(s))
        // 即使别的层都声明没数据，一瞬仍然优先
        assertEquals(
            AppRoute.Yishun("content://a"),
            NavRender.effective(
                s.push(AppRoute.Camera),
                cameraReady = false, viewerReady = false, albumReady = false,
            ),
        )
    }

    /**
     * 防的 bug：三个层同时"有数据"时，改前的渲染顺序是
     * 一瞬 > 相机 > 查看器 > 相册内页 —— 现在栈的顺序必须与之等价。
     *
     * 注意一瞬**恒就绪**（uri 就在路由里），所以它永远不会让位；
     * 要验证它下面那几层，必须把它从栈里退掉（这正是返回键干的事）。
     */
    @Test
    fun renderOrderMatchesTheOldWhenChain() {
        var s = BackStack.of(gallery)
        s = s.push(AppRoute.AlbumDetail("b", "册"))
        s = s.push(AppRoute.Viewer(0))
        s = s.push(AppRoute.Camera)
        s = s.push(AppRoute.Yishun("content://a"))
        assertEquals(AppRoute.Yishun("content://a"), NavRender.effective(s))
        // 退掉一瞬（它就是"关掉一瞬"那一下）：相机露出来，且不依赖任何 Ready 参数
        assertEquals(AppRoute.Camera, NavRender.effective(s.pop(), viewerReady = false))
        // 再退掉相机：查看器的数据还在 → 查看器；数据没了 → 内页
        assertEquals(AppRoute.Viewer(0), NavRender.effective(s.pop().pop()))
        assertEquals(
            AppRoute.AlbumDetail("b", "册"),
            NavRender.effective(s.pop().pop(), viewerReady = false),
        )
        assertEquals(
            gallery,
            NavRender.effective(s.pop().pop(), viewerReady = false, albumReady = false),
        )
    }

    /** 防的 bug：`Tab` 混进上层时把底座换掉（脏恢复数据）。 */
    @Test
    fun tabInLayersIsSkipped() {
        // BackStack.push 已经挡住 Tab，这里直接构造一个"脏"栈来验证 NavRender 的兜底
        val dirty = BackStack.restore(listOf("base:1", "tab:2", "camera"))
        assertEquals(AppRoute.Camera, NavRender.effective(dirty))
        assertEquals(AppRoute.Albums, dirty.base)
    }
}
