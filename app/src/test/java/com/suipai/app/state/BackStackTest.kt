package com.suipai.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回栈与路由编码的单测。
 *
 * 每一条都写清"它防的是什么 bug"——因为这一层的错**编译器不报、跑起来也不崩**，
 * 只表现为"点返回回错地方""重建后回到首页""返回键退不掉"这类要装机才看得见的事。
 * 改前这些规则只存在于 `MainActivity` 里那个 7 分支的 `when`，一条都测不到。
 */
class BackStackTest {

    private val gallery = AppRoute.Gallery

    // ── 1. 入栈 ────────────────────────────────────────────────────────

    /** 防的 bug：进查看器之后返回键退不掉（栈没长高）。 */
    @Test
    fun pushAddsOneLayerAndBecomesCurrent() {
        val s = BackStack.of(gallery).push(AppRoute.Viewer(3))
        assertEquals(1, s.depth)
        assertEquals(AppRoute.Viewer(3), s.current)
        assertEquals(gallery, s.base)
    }

    /** 防的 bug：从大图进一瞬再返回，退不回到大图（只留了最上面那层）。 */
    @Test
    fun pushIsLifoSoBackReturnsToTheScreenBelow() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Viewer(1))
            .push(AppRoute.Yishun("content://a"))
        assertEquals(AppRoute.Yishun("content://a"), s.current)
        val back = s.pop()
        assertEquals(AppRoute.Viewer(1), back.current)
        assertEquals(gallery, back.pop().current)
    }

    /** 防的 bug：从查看器里换一张照片进一瞬，返回要按两次（栈里留下两个一瞬）。 */
    @Test
    fun pushingSameFamilyReplacesTopInsteadOfGrowing() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Viewer(0))
            .push(AppRoute.Yishun("content://a"))
            .push(AppRoute.Yishun("content://b"))
        assertEquals(2, s.depth)
        assertEquals(AppRoute.Yishun("content://b"), s.current)
        // 一次返回就回到查看器，不是先看到上一张一瞬
        assertEquals(AppRoute.Viewer(0), s.pop().current)
    }

    /**
     * 防的 bug：两个**不同 family** 的同名层被误当成重复（从大图进一瞬只留一瞬，
     * 返回直接回画廊 —— 用户丢了"刚才在看哪张"）。
     */
    @Test
    fun differentFamiliesBothStay() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Viewer(2))
            .push(AppRoute.Camera)
            .push(AppRoute.Yishun("content://a"))
        assertEquals(3, s.depth)
        assertEquals(listOf("viewer", "camera", "yishun"), s.layers.map { it.family })
    }

    /**
     * 防的 bug：Tab 被塞进上层，于是返回栈里出现"压着 tab 的 tab"，
     * 返回键开始在两颗 tab 之间来回跳。
     */
    @Test
    fun tabCanNeverBePushed() {
        val s = BackStack.of(gallery).push(AppRoute.Albums)
        assertEquals(0, s.depth)
        assertEquals(gallery, s.base)
    }

    // ── 2. 出栈 ────────────────────────────────────────────────────────

    /** 防的 bug：在底座上按返回被 pop 成 null / 抛异常（应用崩在返回键上）。 */
    @Test
    fun popOnEmptyStackIsANoOpNotACrash() {
        val s = BackStack.of(gallery)
        assertEquals(s, s.pop())
        assertEquals(gallery, s.pop().current)
        assertEquals(0, s.depth)
    }

    /** 防的 bug：上层退完之后没有回到底座那一屏（屏幕停在最后一层）。 */
    @Test
    fun poppingEverythingLandsOnTheBaseTab() {
        val s = BackStack.of(AppRoute.Albums)
            .push(AppRoute.AlbumDetail("bucket-1", "最近"))
            .push(AppRoute.Viewer(0))
        assertEquals(AppRoute.Albums, s.pop().pop().current)
    }

    /** 防的 bug："退回到某一层"退多了/退少了（从查看器退到相册内页却把内页也退了）。 */
    @Test
    fun popToKeepsTheNamedLayerAndDropsThoseAbove() {
        val s = BackStack.of(gallery)
            .push(AppRoute.AlbumDetail("b", "册"))
            .push(AppRoute.Viewer(0))
            .push(AppRoute.Yishun("content://a"))
        val back = s.popTo("album")
        assertEquals(1, back.depth)
        assertEquals(AppRoute.AlbumDetail("b", "册"), back.current)
    }

    /** 防的 bug：`popTo` 遇到不存在的层时把整个栈清空（本该原样返回）。 */
    @Test
    fun popToMissingFamilyChangesNothing() {
        val s = BackStack.of(gallery).push(AppRoute.Viewer(0))
        assertEquals(s, s.popTo("album"))
    }

    // ── 3. Tab 切换清栈 ───────────────────────────────────────────────

    /**
     * 防的 bug：切 tab 之后上一屏的相册内页还压着 —— 用户点了"相册"却还是停在内页，
     * 会以为 tab 坏了。
     */
    @Test
    fun switchingTabClearsTheLayers() {
        val s = BackStack.of(gallery)
            .push(AppRoute.AlbumDetail("b", "册"))
            .replaceTo(AppRoute.Albums)
        assertEquals(0, s.depth)
        assertEquals(AppRoute.Albums, s.current)
        assertEquals(AppRoute.Albums, s.base)
    }

    /** 防的 bug：点当前这颗 tab 也重建栈，把用户正在看的那一屏关掉（幂等性）。 */
    @Test
    fun switchingToTheSameTabOnAnEmptyStackIsIdentity() {
        val s = BackStack.of(gallery)
        assertEquals(s, s.replaceTo(gallery))
    }

    /**
     * 防的 bug（这条最容易做错）：快捷方式「互通」在改前只是 `tab = 2`，
     * 而渲染顺序把一瞬/相机/查看器/相册内页都排在 tab 之上 —— 屏幕**不变**。
     * 一次"点快捷方式然后返回"在改后必须仍然回到原来那屏，而不是直接落到互通页。
     */
    @Test
    fun rebaseKeepsLayersButSwapsTheTabUnderneath() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Viewer(0))
            .push(AppRoute.Yishun("content://a"))
        val r = s.rebaseTo(AppRoute.Sync)
        assertEquals(2, r.depth)
        assertEquals(AppRoute.Yishun("content://a"), r.current)
        assertEquals(AppRoute.Sync, r.base)
        assertEquals(AppRoute.Viewer(0), r.pop().current)
        assertEquals(AppRoute.Sync, r.pop().pop().current)
    }

    /**
     * 防的 bug：快捷方式「拍照」落在已经开着的一瞬上时，相机盖到一瞬**上面**
     * （改前 `showCamera = true` 是个布尔，渲染顺序让一瞬在前 —— 屏幕仍是一瞬）。
     */
    @Test
    fun insertBelowTopKeepsTheYishunOnTop() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Viewer(0))
            .push(AppRoute.Yishun("content://a"))
            .insertBelowTop(AppRoute.Camera)
        assertEquals(listOf("viewer", "camera", "yishun"), s.layers.map { it.family })
        assertEquals(3, s.depth)
        // 关掉一瞬之后看到的是相机（改前就是这个顺序）
        assertEquals(AppRoute.Camera, s.pop().current)
    }

    /** 插到空栈上等价于 push（快捷方式「拍照」在 tab 层上）。 */
    @Test
    fun insertBelowTopOnEmptyStackBehavesLikePush() {
        val s = BackStack.of(gallery).insertBelowTop(AppRoute.Camera)
        assertEquals(1, s.depth)
        assertEquals(AppRoute.Camera, s.current)
    }

    // ── 4. 保存 / 恢复 ────────────────────────────────────────────────

    /** 防的 bug：分屏/折叠屏换外屏重建之后回到首页 —— 正在看的一瞬、相册内页全丢。 */
    @Test
    fun encodeRestoreRoundTripsEveryRoute() {
        val routes = listOf(
            AppRoute.AlbumDetail("bucket:with:colons", "夹 张%三"),
            AppRoute.Viewer(7),
            AppRoute.Camera,
            AppRoute.Yishun("content://media/external/images/media/42?x=1:2"),
        )
        var s = BackStack.of(AppRoute.Albums)
        routes.forEach { s = s.push(it) }
        val restored = BackStack.restore(s.encode())
        assertEquals(s, restored)
        assertEquals(routes, restored.layers)
    }

    /** 防的 bug：恢复之后返回键退不掉（底座回来了、上层没回来）。 */
    @Test
    fun restoreKeepsTheBaseTabToo() {
        val restored = BackStack.restore(BackStack.of(AppRoute.Sync).encode())
        assertEquals(AppRoute.Sync, restored.base)
        assertEquals(0, restored.depth)
    }

    /** 防的 bug：Bundle 里是空的（首次启动）时崩溃或落在一个空导航态。 */
    @Test
    fun restoreFromEmptyFallsBackToGallery() {
        assertEquals(BackStack.of(gallery), BackStack.restore(emptyList()))
    }

    /**
     * 防的 bug：老版本 / 被人改过的 Bundle 让启动崩在解码上。
     * 规则是"能救的救、救不了的丢"，绝不是抛异常。
     */
    @Test
    fun restoreDropsGarbageInsteadOfCrashing() {
        val dirty = listOf(
            "base:0",
            "viewer:not-a-number",
            "album:只有两段",
            "totally-unknown",
            "yishun:",
            "camera",
        )
        val s = BackStack.restore(dirty)
        assertEquals(AppRoute.Gallery, s.base)
        assertEquals(listOf(AppRoute.Camera), s.layers)
    }

    /** 防的 bug：底座那一段坏掉时，整串上层被当成底座（导航随机落在某屏）。 */
    @Test
    fun restoreWithoutBaseLineFallsBackToGallery() {
        assertEquals(gallery, BackStack.restore(listOf("viewer:3")).base)
        assertEquals(0, BackStack.restore(listOf("viewer:3")).depth)
        assertEquals(gallery, BackStack.restore(listOf("base:x")).base)
    }

    /** 防的 bug：底座下标越界（换了 tab 数量之后的老 Bundle）导致落在一个不存在的页。 */
    @Test
    fun restoreWithOutOfRangeBaseIndexFallsBackToGallery() {
        assertEquals(gallery, BackStack.restore(listOf("base:99")).base)
        assertEquals(gallery, BackStack.restore(listOf("base:-1")).base)
    }

    /** 防的 bug：脏数据里混进 tab，于是上层出现第二个 tab。 */
    @Test
    fun restoreIgnoresTabsInTheLayers() {
        val s = BackStack.restore(listOf("base:1", "tab:2", "camera"))
        assertEquals(AppRoute.Albums, s.base)
        assertEquals(listOf(AppRoute.Camera), s.layers)
    }

    // ── 5. contains ───────────────────────────────────────────────────

    /** 防的 bug：`contains` 按 uri/页号比，于是"一瞬开着"判成 false（返回键优先级全错）。 */
    @Test
    fun containsComparesByFamilyNotByPayload() {
        val s = BackStack.of(gallery).push(AppRoute.Yishun("content://a"))
        assertTrue(s.contains(AppRoute.Yishun("content://b")))
        assertTrue(s.containsFamily("yishun"))
        assertFalse(s.containsFamily("camera"))
        // 底座也算"在栈里"：判断"要不要显示底栏"不能只看 layers
        assertTrue(s.containsFamily("tab"))
    }

    // ── 6. removeFamilies ─────────────────────────────────────────────

    /** 防的 bug：一瞬保存完之后相机层没被摘掉（改前 `showCamera = false` 那一句）。 */
    @Test
    fun removeFamiliesDropsOnlyTheNamedOnes() {
        val s = BackStack.of(gallery)
            .push(AppRoute.Camera)
            .push(AppRoute.Yishun("content://a"))
            .removeFamilies(setOf("camera"))
        assertEquals(listOf("yishun"), s.layers.map { it.family })
        // 没有要摘的就不产生新对象（避免无意义的 Snapshot 失效）
        assertTrue(s === s.removeFamilies(setOf("camera")))
    }

    // ── 7. 组合场景（改前那个 when 链的等价性） ──────────────────────

    /**
     * 防的 bug：从"相册 tab → 进某册 → 看大图 → 进一瞬 → 关一瞬 → 返回"这一串里，
     * 少退或多退一层（改前这串由 4 个独立状态变量维持，最容易错）。
     */
    @Test
    fun theAlbumViewerYishunWalkBacksOutOneLayerAtATime() {
        var s = BackStack.of(AppRoute.Albums)
        s = s.push(AppRoute.AlbumDetail("bucket-1", "最近"))
        s = s.push(AppRoute.Viewer(0))
        s = s.push(AppRoute.Yishun("content://a"))
        assertEquals("yishun", s.current.family)
        s = s.pop()
        assertEquals("viewer", s.current.family)
        s = s.pop()
        assertEquals("album", s.current.family)
        s = s.pop()
        assertEquals("tab", s.current.family)
        assertEquals(AppRoute.Albums, s.current)
    }

    /** 防的 bug：Tab 那三颗的名字与下标分家（底栏点第 2 颗却切到第 3 个页）。 */
    @Test
    fun tabIndexesAndTitlesAreAligned() {
        assertEquals(listOf(0, 1, 2), AppRoute.Tabs.map { it.index })
        assertEquals(3, AppRoute.TabTitles.size)
        assertEquals(AppRoute.Gallery, AppRoute.tab(0))
        assertEquals(AppRoute.Albums, AppRoute.tab(1))
        assertEquals(AppRoute.Sync, AppRoute.tab(2))
        assertEquals(AppRoute.Gallery, AppRoute.tab(99))
        assertEquals(AppRoute.Gallery, AppRoute.tab(-1))
    }

    /**
     * 防的 bug：`rememberSaveable` 的 Saver 与 `encode/restore` 分家（存进去的和取出来的不是一回事）。
     *
     * 注意 `NavState` **故意**在构造时剥掉查看器层（重建后 `viewerPhotos` 已经没了，
     * 留一个空查看器只会闪一帧空屏），所以这里用不含查看器的栈验形状，
     * 查看器那条另见 `navStateRestoreDropsTheViewerLayer`。
     */
    @Test
    fun saverRoundTripsThroughAListOfStrings() {
        val s = BackStack.of(gallery)
            .push(AppRoute.AlbumDetail("b", "册"))
            .push(AppRoute.Camera)
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val holder = NavState(s)
        val saved = with(NavState.Saver) { scope.save(holder) }
        assertEquals(s.encode(), saved)
        assertEquals(s, NavState.Saver.restore(saved!!)?.stack)
        // 坏数据也不许在 Saver 这一层炸
        assertEquals(
            BackStack.of(gallery),
            NavState.Saver.restore(listOf("nonsense"))?.stack,
        )
    }

    /** 防的 bug：`NavState` 恢复时把没有数据的查看器层也带回来（闪一帧空屏）。 */
    @Test
    fun navStateRestoreDropsTheViewerLayer() {
        val s = BackStack.of(gallery)
            .push(AppRoute.AlbumDetail("b", "册"))
            .push(AppRoute.Viewer(1))
        val restored = NavState(BackStack.restore(s.encode()))
        assertEquals(listOf("album"), restored.stack.layers.map { it.family })
    }

    /** 防的 bug：一瞬 / 相机这类有 uri 的层在重建后丢光（改前它们都是 rememberSaveable）。 */
    @Test
    fun navStateRestoreKeepsYishunAndCamera() {
        val s = BackStack.of(gallery).push(AppRoute.Camera).push(AppRoute.Yishun("content://a"))
        val restored = NavState(BackStack.restore(s.encode()))
        assertEquals(s, restored.stack)
    }

    /**
     * 防的 bug（这一条是我自己写的第一版里的真缺陷）：`jumpUri` 那条路（一瞬保存完跳回
     * 查看器）在改前只需要设 `viewerPhotos` 就够 —— 因为查看器是 `when` 里按数据在不在挑的。
     * 现在查看器还需要一个路由层：只设数据不压路由，屏幕上**不会有任何反应**，也不报错。
     */
    @Test
    fun showViewerAtPushesALayerSoTheJumperActuallyShows() {
        val nav = NavState(BackStack.of(gallery).push(AppRoute.Yishun("content://a")))
        nav.showViewerAt(3)
        assertEquals("viewer", nav.current.family)
        assertEquals(AppRoute.Viewer(3), nav.current)
    }

    /** 防的 bug：重复调用 `showViewerAt` 叠出两个查看器层（返回键要按两次才退出查看器）。 */
    @Test
    fun showViewerAtIsIdempotent() {
        val nav = NavState(BackStack.of(gallery))
        nav.showViewerAt(0)
        nav.showViewerAt(5)
        assertEquals(1, nav.stack.layers.count { it.family == "viewer" })
        assertEquals(AppRoute.Viewer(5), nav.current)
    }

    /** 防的 bug：栈里已经有查看器（从大图进一瞬再保存）时，`showViewerAt` 叠第二层。 */
    @Test
    fun showViewerAtReturnsToTheExistingViewerInsteadOfStacking() {
        val nav = NavState(BackStack.of(gallery))
        nav.push(AppRoute.Viewer(1))
        nav.push(AppRoute.Yishun("content://a"))
        nav.showViewerAt(1)
        assertEquals(1, nav.stack.layers.count { it.family == "viewer" })
        // 一瞬被退掉了（保存完就离开），查看器重新在最上面 —— 改前 `yishunUri` 那一刻被清空
        assertEquals("viewer", nav.current.family)
        assertEquals(1, nav.stack.depth)
    }

    /** 防的 bug：导航层没有"上层为空"这个判据，底栏跑到查看器上面去。 */
    @Test
    fun onlyTabsAreAtDepthZero() {
        assertTrue(BackStack.of(gallery).layers.isEmpty())
        assertFalse(BackStack.of(gallery).push(AppRoute.Camera).layers.isEmpty())
    }

    // ── 8. 重置（分享导入 / 快捷方式） ───────────────────────────────

    /** 防的 bug：分享导入进来之后，原来的相册内页还压在下面（返回键退到"看不见的册子"）。 */
    @Test
    fun resetBuildsAFreshStackFromTabAndLayers() {
        val s = BackStack.of(AppRoute.Albums)
            .push(AppRoute.AlbumDetail("b", "册"))
            .reset(AppRoute.Gallery, listOf(AppRoute.Yishun("content://share")))
        assertEquals(AppRoute.Gallery, s.base)
        assertEquals(listOf("yishun"), s.layers.map { it.family })
    }

    /** 防的 bug：reset 的 layers 里混进 tab，于是返回栈里出现两个 tab。 */
    @Test
    fun resetFiltersTabsOutOfTheLayers() {
        val s = BackStack.of(AppRoute.Albums).reset(AppRoute.Gallery, listOf(AppRoute.Sync, AppRoute.Camera))
        assertEquals(listOf("camera"), s.layers.map { it.family })
        assertEquals(AppRoute.Gallery, s.base)
    }
}
