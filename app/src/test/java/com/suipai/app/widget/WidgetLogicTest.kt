package com.suipai.app.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小组件与动态快捷方式的**纯逻辑**守卫。
 *
 * 这个仓库的纪律是"每条判据都要有喂坏例子必须变红的自检"（`docs/handoff.md` §3）。
 * 小组件的可测面积很小 —— 它跑在**别人的进程**里（launcher），我们既不能在那里跑断言，
 * 也没法在 JVM 上把 `RemoteViews` 真的渲染出来。所以退到能确定的那一层：
 * 选哪几张、说什么话、用哪个布局、什么时候放弃重试、id 撞不撞、预算超没超。
 *
 * 本文件**不许碰 `android.*`**：`unitTests.isReturnDefaultValues = true` 之下，
 * 静态方法一律返回默认值（`Context` 是 null、`Build.VERSION.SDK_INT` 是 0），
 * 那样的断言会"永远绿但什么都没验"。所以扫描类测试只读**源码与资源原文**。
 */
class WidgetLogicTest {

    // ══ 一、「取最近 N 张」的选取 ══════════════════════════════════════

    /** 正常路径：按给定顺序（调用方已按时间倒好）取前 N 张。 */
    @Test
    fun selectRecentKeepsOrderAndTrimsToLimit() {
        val raw = photos(9, 8, 7, 6, 5)
        assertEquals(listOf(9L, 8L, 7L, 6L), selectRecent(raw).map { it.id })
        assertEquals("limit 比库小的时候要真的截断", listOf(9L), selectRecent(raw, limit = 1).map { it.id })
        assertEquals("库比 limit 少的时候不许补空项", 5, selectRecent(raw, limit = 10).size)
    }

    /**
     * 去重：partial access（Android 14 的"选中的照片"）下 MediaStore 会把同一行报两遍。
     * 保留**第一次**出现的那一份，因为它是最新的那个位置。
     */
    @Test
    fun selectRecentDeduplicatesByIdKeepingTheFirst() {
        val raw = listOf(
            WidgetPhoto(7, "content://media/external/images/media/7", 700),
            WidgetPhoto(9, "content://media/external/images/media/9", 900),
            WidgetPhoto(7, "content://media/other/7", 700), // 同一个 id，另一条 uri
            WidgetPhoto(9, "content://media/other/9", 900),
        )
        val picked = selectRecent(raw)
        assertEquals("同一张照片不许占两格", listOf(7L, 9L), picked.map { it.id })
        assertEquals(
            "保留的必须是第一次出现的那一份（后一条 uri 是不同的路径）",
            "content://media/external/images/media/7",
            picked[0].uri,
        )
    }

    /**
     * 自检：乱序输入下"去重"真的会发生 —— 否则上面那条可能只是碰巧绿。
     * 同时钉住：去重之后再截断（顺序写反会把 limit 之外的那张留下、limit 之内的丢掉）。
     */
    @Test
    fun selectRecentSelfCheckDedupHappensBeforeTrimming() {
        // 3 张不同的照片，但前两张是同一个 id：正确结果是 1,2,3 里的前 2 个 = [1,2]
        val raw = listOf(
            WidgetPhoto(1, "u1", 0),
            WidgetPhoto(1, "u1-dup", 0),
            WidgetPhoto(2, "u2", 0),
            WidgetPhoto(3, "u3", 0),
        )
        assertEquals(listOf(1L, 2L), selectRecent(raw, limit = 2).map { it.id })
        // 喂一个"全是重复"的坏例子：只能出 1 张，不许出 4 张
        assertEquals(1, selectRecent(List(4) { WidgetPhoto(5, "u$it", 0) }).size)
    }

    /** 空库兜底：查不到就是空表，不许抛、不许补默认项。 */
    @Test
    fun selectRecentOnAnEmptyLibraryReturnsNothing() {
        assertEquals(emptyList<WidgetPhoto>(), selectRecent(emptyList()))
        assertEquals(emptyList<WidgetPhoto>(), selectRecent(photos(1, 2, 3), limit = 0))
        // 负数是调用点写错了。返回空表而不是"全给" —— 否则桌面会塞进五千张图。
        assertEquals(emptyList<WidgetPhoto>(), selectRecent(photos(1, 2, 3), limit = -7))
    }

    /** 坏行（id <= 0 / uri 空）让位给下一张，而不是画成一个空框。 */
    @Test
    fun selectRecentSkipsUnusableRows() {
        val raw = listOf(
            WidgetPhoto(0, "content://x/0", 0),
            WidgetPhoto(-3, "content://x/-3", 0),
            WidgetPhoto(4, "", 0),
            WidgetPhoto(5, "content://x/5", 0),
        )
        assertEquals(listOf(5L), selectRecent(raw).map { it.id })
    }

    // ══ 二、「没读到」vs「没有照片」 ═════════════════════════════════════

    /**
     * 这条是整个小组件最容易写错、后果最重的一条：
     * `null` 是**没读到**，空表是**确实没有**。混成一种的话，媒体提供方抖一下
     * 桌面就写「随拍 · 0 张」，用户看到的是"照片没了"。
     */
    @Test
    fun unavailableAndEmptyAreDifferentStates() {
        assertEquals(WidgetState.Empty, stateOf(emptyList(), total = 0))
        assertTrue(
            "查询没答话时必须是 Unavailable，不是 Empty",
            stateOf(null, total = 0) is WidgetState.Unavailable,
        )
        assertEquals("随拍 · 还没有照片", WidgetState.Empty.caption)
        assertEquals("随拍 · 没读到照片", WidgetState.Unavailable("x").caption)
    }

    /** 一行都留不下（全是坏 id）时按"没读到"处理：不许把上一次的画面擦成空。 */
    @Test
    fun rowsThatAllGetFilteredOutCountAsUnreadable() {
        val garbage = listOf(WidgetPhoto(0, "", 0), WidgetPhoto(-1, "u", 0))
        assertTrue(stateOf(garbage, total = 2) is WidgetState.Unavailable)
    }

    /** 读到的时候报的是**库里总数**，不是这四张。 */
    @Test
    fun captionReportsTheWholeLibraryNotJustTheVisibleFour() {
        val state = stateOf(photos(4, 3, 2, 1, 0), total = 5123)
        assertTrue(state is WidgetState.Loaded)
        state as WidgetState.Loaded
        assertEquals("随拍 · 5123 张", state.caption)
        assertEquals(4, state.photos.size)
        assertEquals(5123, state.total)
    }

    /**
     * 自检：总数比可见张数还小时要夹住。
     * 真会出现 —— `countAll` 查完之后用户又删了两张，两次查询之间不是同一个时刻。
     */
    @Test
    fun totalIsClampedToAtLeastTheVisibleCount() {
        val state = stateOf(photos(3, 2, 1), total = 1) as WidgetState.Loaded
        assertEquals("总数不许比屏幕上摆出来的张数还小", 3, state.total)
        assertEquals("随拍 · 3 张", state.caption)
    }

    // ══ 三、尺寸 → 布局 ═══════════════════════════════════════════════

    /** 门槛两边各喂一个值，确认是**按宽度**切的且边界算在宽那一侧。 */
    @Test
    fun sizePicksLayoutByWidth() {
        assertEquals(WidgetSize.Small2x2, WidgetSize.forWidthDp(110))
        assertEquals(WidgetSize.Small2x2, WidgetSize.forWidthDp(249))
        assertEquals("250dp 是 4×2 的下限，边界值本身要算宽的", WidgetSize.Wide4x2, WidgetSize.forWidthDp(250))
        assertEquals(WidgetSize.Wide4x2, WidgetSize.forWidthDp(320))
    }

    /** 拿不到宽度时退回 2×2：猜大了是"图被裁"，猜小了只是"少显示几张"。 */
    @Test
    fun missingWidthFallsBackToSmall() {
        assertEquals(WidgetSize.Small2x2, WidgetSize.forWidthDp(0))
        assertEquals(WidgetSize.Small2x2, WidgetSize.forWidthDp(-1))
        // min 没给、max 给了的时候按 max 判
        assertEquals(WidgetSize.Wide4x2, WidgetSize.forOptions(minWidthDp = 0, maxWidthDp = 300))
        // 两个都没给
        assertEquals(WidgetSize.Small2x2, WidgetSize.forOptions(0, 0))
    }

    /** 2×2 只显示 1 格；4×2 显示 min(有几张, 4) 格。 */
    @Test
    fun visibleSlotsNeverExceedsWhatTheLayoutCanShow() {
        assertEquals(1, WidgetShape.visibleSlots(WidgetSize.Small2x2, loaded = 4))
        assertEquals(0, WidgetShape.visibleSlots(WidgetSize.Small2x2, loaded = 0))
        assertEquals(4, WidgetShape.visibleSlots(WidgetSize.Wide4x2, loaded = 9))
        assertEquals(2, WidgetShape.visibleSlots(WidgetSize.Wide4x2, loaded = 2))
        assertEquals(0, WidgetShape.visibleSlots(WidgetSize.Wide4x2, loaded = 0))
        // 自检：负数不许变成"全部显示"
        assertEquals(0, WidgetShape.visibleSlots(WidgetSize.Wide4x2, loaded = -3))
    }

    /**
     * 位图边长必须夹在一个"Binder 装得下"的区间里。
     *
     * `RemoteViews` 带的位图走 Binder，一次事务约 1MB；4 张 144² 的 ARGB 是 331KB。
     * 密度传 0 / NaN 时不许返回 0 —— `loadThumbnail` 收到 0 抛 IllegalArgumentException。
     */
    @Test
    fun thumbEdgeStaysInsideTheBinderBudget() {
        for (d in listOf(0f, -1f, Float.NaN, 1f, 2f, 3f, 4f)) {
            val small = WidgetLogic.thumbEdgePx(WidgetSize.Small2x2, d)
            val wide = WidgetLogic.thumbEdgePx(WidgetSize.Wide4x2, d)
            assertTrue("2×2 在 density=$d 时给出 $small，超出可接受区间", small in 96..384)
            // 上界从 `WidgetShape.WIDE_EDGE_MAX` 取，不再写死 192（第一版写的是 192，
            // 而 3 倍屏上 4 张 192² 就是 576KB —— 那条断言本身在纵容超预算，见下一条）。
            assertTrue(
                "4×2 在 density=$d 时给出 $wide，超出可接受区间",
                wide in 72..WidgetShape.WIDE_EDGE_MAX,
            )
            assertTrue("4×2 单格必须比 2×2 的大图小", wide <= small)
        }
        // 4×2 四张的总字节数（ARGB_8888）必须在 1MB 的一半以内，给布局留余量。
        // **逐个密度都算一遍**：只在 4 倍屏上量一次的话，把上限改回 192 这条依然绿
        // （4 倍屏本来就撞到上限），只有拿 2/3 倍屏才看得出上限本身是不是超预算。
        for (d in listOf(1f, 2f, 3f, 4f)) {
            val edge = WidgetLogic.thumbEdgePx(WidgetSize.Wide4x2, d)
            val bytes = edge.toLong() * edge * 4 * WidgetShape.MAX_THUMBS
            assertTrue(
                "density=$d 时 4×2 的四张缩略图共 $bytes 字节，超了 Binder 的一半预算",
                bytes < 512L * 1024L,
            )
        }
        // 自检：把上限换回 192 必须让上面那条变红 —— 证明它真的在管这件事。
        val badEdge = 192
        val badBytes = badEdge.toLong() * badEdge * 4 * WidgetShape.MAX_THUMBS
        assertTrue(
            "自检失败：192 竟然也在预算内，那上面那条断言就管不住上限",
            badBytes >= 512L * 1024L,
        )
    }

    // ══ 四、刷新重试状态机 ═════════════════════════════════════════════

    /**
     * 状态机必须**有限**：`goAsync()` 的预算是 ~10 秒，广播的 ANR 时限也是 10 秒。
     * 这条把"最多几次 + 一共等多久"两件事一起钉住。
     */
    @Test
    fun retryIsBoundedAndFitsInsideTheGoAsyncBudget() {
        var r = WidgetRetry()
        var waits = 0L
        var attempts = 0
        while (true) {
            attempts++
            if (r.shouldGiveUp) break
            waits += r.backoffMillis
            r = r.next("第 $attempts 次坏了")
            // 防死循环：真写错了也要在这里红，而不是把测试进程挂住
            assertTrue("重试没有收敛，已经第 $attempts 次", attempts < 50)
        }
        assertEquals(WidgetShape.MAX_ATTEMPTS, attempts)
        assertTrue("一共只等了 ${waits}ms，太短了（冷启动时媒体提供方还没起来）", waits >= 100L)
        assertTrue("一共等了 ${waits}ms，装不进 goAsync 的 10 秒预算", waits < 3000L)
        assertNotNull("最后一次的失败原因要留着，日志才有得看", r.lastFailure)
    }

    /** 自检：到上限之后 `attempt` 不许继续涨 —— 否则日志里会看到第 4、5 次尝试，而实际只发了 3 次。 */
    @Test
    fun retryStopsIncrementingAtTheCeiling() {
        val maxed = WidgetRetry(attempt = WidgetShape.MAX_ATTEMPTS)
        assertTrue(maxed.shouldGiveUp)
        assertFalse(maxed.canRetry)
        assertEquals(WidgetShape.MAX_ATTEMPTS, maxed.next("再坏一次").attempt)
        // 起点就必须是"还能试"：把 attempt 从 1 改成 0 或 MAX 都会被这条抓住
        assertTrue(WidgetRetry().canRetry)
        assertEquals(1, WidgetRetry().attempt)
        assertNull(WidgetRetry().lastFailure)
    }

    /** 退避必须**单调不减**：先 50ms 再 5ms 那种写法在真机上等于没等。 */
    @Test
    fun backoffNeverShrinks() {
        val waits = (1..WidgetShape.MAX_ATTEMPTS).map { WidgetRetry(attempt = it).backoffMillis }
        assertEquals("退避序列必须是单调不减的：$waits", waits.sorted(), waits)
        assertTrue("第一次的退避 ${waits.first()}ms 不该是 0", waits.first() > 0L)
    }

    // ══ 五、快捷方式的 id 与预算 ══════════════════════════════════════

    /** id 常量不许撞：撞了就是同一个动作在长按菜单里出现两次，而两个都能点。 */
    @Test
    fun shortcutIdsAreUnique() {
        val all = ShortcutIds.STATIC + ShortcutIds.DYNAMIC
        assertEquals("快捷方式 id 撞车：$all", all.size, all.toSet().size)
        assertTrue("静态那三条的 id 不许为空", ShortcutIds.STATIC.all { it.isNotBlank() })
        assertTrue("动态那两条的 id 不许为空", ShortcutIds.DYNAMIC.all { it.isNotBlank() })
        // 自检：喂一份有重复的进去，同一个判据必须红。
        //
        // 判据是「去重后数量变少」= 有撞车。`listOf("camera","camera","sync")` 去重后是 2 条，
        // 少掉 **1** 条 —— 所以期望值是 1，不是 2。第一版这里写成 `dirty.size - 1`（= 2），
        // 那是把"重复项数"当成了"输入长度减一"，对两个重复项恰好相等、完全是巧合。
        // 换成三个重复项就会露馅，所以下面再喂一份三重复的把它钉死。
        val dirty = listOf("camera", "camera", "sync")
        assertEquals(
            "自检失败：重复 id 没被识别出来",
            1,
            dirty.size - dirty.toSet().size,
        )
        val dirtier = listOf("camera", "camera", "camera", "sync")
        assertEquals(
            "自检失败：三重复没被识别成少 2 条",
            2,
            dirtier.size - dirtier.toSet().size,
        )
        // 反向自检：没有重复时必须是 0（否则上面那条「少掉几条」的判据恒真）
        val clean = listOf("camera", "sync", "yishun")
        assertEquals("自检失败：干净的列表被判成有重复", 0, clean.size - clean.toSet().size)
    }

    /** 静态 3 + 动态 2 ≤ 5。系统的上限是总数，超了它会**静默丢掉**多出来的那几条。 */
    @Test
    fun shortcutBudgetFitsTheSystemCeiling() {
        assertEquals(3, ShortcutBudget.STATIC_COUNT)
        assertEquals(
            "静态张数必须与 ShortcutIds.STATIC 对得上",
            ShortcutBudget.STATIC_COUNT,
            ShortcutIds.STATIC.size,
        )
        assertEquals(5, ShortcutBudget.SYSTEM_MAX)
        assertEquals(2, ShortcutBudget.MAX_DYNAMIC)
        assertTrue(
            "静态 + 动态超了系统的 5 个上限",
            ShortcutIds.STATIC.size + ShortcutIds.DYNAMIC.size <= ShortcutBudget.SYSTEM_MAX,
        )
        assertTrue("动态那两条已经超出预算", ShortcutIds.DYNAMIC.size <= ShortcutBudget.MAX_DYNAMIC)
    }

    /** 收紧函数：超预算就裁、重复就留第一次出现。 */
    @Test
    fun budgetedShortcutsTrimsAndDeduplicates() {
        val many = (0..5).map { ShortcutSpec("id$it", "短", "长", null, it) }
        val trimmed = budgetedDynamicShortcuts(many)
        assertEquals(ShortcutBudget.MAX_DYNAMIC, trimmed.size)
        assertEquals("裁剪必须从前往后留（rank 小的先）", listOf("id0", "id1"), trimmed.map { it.id })

        val dup = listOf(
            ShortcutSpec("a", "短", "长", null, 0),
            ShortcutSpec("a", "短2", "长2", null, 1),
            ShortcutSpec("b", "短", "长", null, 2),
        )
        assertEquals(listOf("a", "b"), budgetedDynamicShortcuts(dup).map { it.id })
        assertEquals("去重保留的是第一次出现的那份", "短", budgetedDynamicShortcuts(dup)[0].shortLabel)
    }

    /** 有照片才发「最近一张」；「回收站」永远发。 */
    @Test
    fun dynamicSpecsDependOnWhetherTheLibraryHasAnything() {
        val withPhotos = dynamicShortcutSpecs(hasPhotos = true).map { it.id }
        assertEquals(listOf(ShortcutIds.RECENT, ShortcutIds.TRASH), withPhotos)
        val empty = dynamicShortcutSpecs(hasPhotos = false).map { it.id }
        assertEquals("空库时不许发「最近一张」（点进去只有空态）", listOf(ShortcutIds.TRASH), empty)
        assertTrue("空库时仍然要发回收站", ShortcutIds.TRASH in empty)
    }

    // ══ 六、深链：只发 MainActivity 认得的 ═════════════════════════════

    /**
     * `MainActivity.collectHop` 只认 `camera|sync|yishun` 三个 host。
     * 发别的 host 不会崩 —— app 会打开，然后停在相册页。
     * 所以这一层的判据是"白名单"，不是"能构造就行"。
     */
    @Test
    fun onlyDeepLinksMainActivityUnderstandsAreSupported() {
        assertTrue(SupportedDeepLinks.isSupported("suipai://camera"))
        assertTrue(SupportedDeepLinks.isSupported("suipai://sync"))
        assertTrue(SupportedDeepLinks.isSupported("suipai://yishun"))
        assertFalse("viewer 目前没有入口", SupportedDeepLinks.isSupported("suipai://viewer?index=0"))
        assertFalse("trash 目前没有 intent 入口", SupportedDeepLinks.isSupported("suipai://trash"))
        assertFalse(SupportedDeepLinks.isSupported("suipai://camera2"))
        assertFalse(SupportedDeepLinks.isSupported("https://camera"))
        assertFalse(SupportedDeepLinks.isSupported(""))
        assertFalse(SupportedDeepLinks.isSupported("camera"))
    }

    /** host 解析器：带路径 / 带 query 都要取到 host，认不出来的形状给 null 而不是猜。 */
    @Test
    fun hostParserHandlesTheShapesWeActuallyEmit() {
        assertEquals("camera", SupportedDeepLinks.hostOf("suipai://camera"))
        assertEquals("viewer", SupportedDeepLinks.hostOf("suipai://viewer?index=3"))
        assertEquals("album", SupportedDeepLinks.hostOf("suipai://album/x/y"))
        assertEquals("camera", SupportedDeepLinks.hostOf("SUIPAI://camera"))
        assertNull(SupportedDeepLinks.hostOf("suipai:/camera"))
        assertNull(SupportedDeepLinks.hostOf("suipai://"))
        assertNull(SupportedDeepLinks.hostOf("://camera"))
    }

    /**
     * 现在发出去的两条快捷方式都**不带**深链（普通启动）。
     * 这条是给未来的人看的：哪天有人给它们补上 `suipai://trash`，这里会红 ——
     * 那时要么去 `MainActivity` 加分支（本轮被禁止），要么把这条判据连同文档一起改。
     */
    @Test
    fun emittedShortcutsCarryOnlySupportedDeepLinks() {
        val specs = dynamicShortcutSpecs(hasPhotos = true) + dynamicShortcutSpecs(hasPhotos = false)
        val offending = specs.mapNotNull { s -> s.deepLink?.takeIf { !SupportedDeepLinks.isSupported(it) } }
        assertTrue("这些深链 MainActivity 不认，点了会停在相册页：$offending", offending.isEmpty())
        // 自检：喂一条 viewer 进去，同一个判据必须红
        val dirty = listOf(ShortcutSpec("dyn_viewer", "看大图", "看大图", "suipai://viewer?index=0", 0))
        assertTrue(
            "自检失败：不支持的深链没被识别出来",
            dirty.any { !SupportedDeepLinks.isSupported(it.deepLink!!) },
        )
    }

    // ══ 七、设备判据（纯函数，不碰 android.*） ═════════════════════════

    /**
     * 四个象限逐个钉：API 太老不发、宿主不支持钉也不发、两个都满足才发。
     * 这条在真机上没法规——`Build.VERSION.SDK_INT` 是常量，
     * 而"宿主不支持钉快捷方式"的那台机器我们在 CI 里拿不到。
     */
    @Test
    fun shortcutSupportNeedsBothApiAndLauncher() {
        assertEquals(25, ShortcutSupport.MIN_SDK)
        assertFalse("API 24 上没有动态快捷方式", ShortcutSupport.shouldPublish(sdkInt = 24, pinShortcutsSupported = true))
        assertFalse("宿主不支持钉到桌面时不发", ShortcutSupport.shouldPublish(sdkInt = 33, pinShortcutsSupported = false))
        assertTrue("API 25 + 支持钉 = 发", ShortcutSupport.shouldPublish(sdkInt = 25, pinShortcutsSupported = true))
        assertTrue(ShortcutSupport.shouldPublish(sdkInt = 36, pinShortcutsSupported = true))
        // 自检：判据把两个条件写成 `||` 的话上面两个 false 会变成 true
        assertFalse(
            "自检失败：两个条件缺一个时必须是不支持",
            ShortcutSupport.shouldPublish(sdkInt = 20, pinShortcutsSupported = false),
        )
    }

    // ══ 八、源码与资源的一致性（读文件，不碰 android.*） ═════════════════

    /**
     * 静态快捷方式的 id 那份真值在 `res/xml/shortcuts.xml` 里。`ShortcutIds.STATIC`
     * 是抄过去的一份 —— 抄漏了不会崩，只会让"静态 3 个"这个预算算错。
     */
    @Test
    fun staticShortcutIdsMatchTheXmlOnDisk() {
        val xml = resFile("xml/shortcuts.xml").readText()
        val ids = Regex("""android:shortcutId="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("shortcuts.xml 里抽到的 id 数量不对（抽取器或文件变了）", ShortcutBudget.STATIC_COUNT, ids.size)
        assertEquals("ShortcutIds.STATIC 与 res/xml/shortcuts.xml 对不上", ids, ShortcutIds.STATIC)
        // 静态那几条发的是这里认得的深链
        val links = Regex("""android:data="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("静态快捷方式的 data 数量与 id 数量不一致", ids.size, links.size)
        assertTrue("静态快捷方式里有不被支持的深链：$links", links.all { SupportedDeepLinks.isSupported(it) })
        // 自检：喂一份少一条的清单，上面的比较必须不成立
        assertTrue("自检失败：少一条的清单被判成相等了", ids.drop(1) != ShortcutIds.STATIC)
    }

    /**
     * 两套布局都得真的在磁盘上，且**只用 RemoteViews 认识的控件**。
     *
     * 这条是"编译得过的 ConstraintLayout"的守门员：`ConstraintLayout` 在普通布局里完全合法，
     * 放进 `RemoteViews` 之后在宿主里会渲染成空块，而 lint 不报、编译不报。
     */
    @Test
    fun widgetLayoutsExistAndUseOnlyRemoteViewsControls() {
        val allowed = setOf(
            "LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout", "TableLayout",
            "TableRow", "ImageView", "TextView", "Button", "ProgressBar", "ListView",
            "GridView", "StackView", "AdapterViewFlipper", "ViewFlipper", "Chronometer",
            "AnalogClock", "Space", "include", "merge",
        )
        for (size in WidgetSize.entries) {
            val f = resFile("layout/${size.layoutName}.xml")
            assertTrue("找不到 ${size.layoutName}.xml：${f.absolutePath}", f.isFile)
            val tags = Regex("<([A-Za-z][\\w.]*)").findAll(f.readText())
                .map { it.groupValues[1] }
                .filterNot { it == "shape" || it == "solid" || it == "corners" || it == "stroke" }
                .toList()
            assertTrue("${size.layoutName}.xml 里一个控件都没读到，抽取器失效了", tags.isNotEmpty())
            val banned = tags.filter { it.substringAfterLast('.') !in allowed }
            assertTrue(
                "${size.layoutName}.xml 里有 RemoteViews 不认识的控件：$banned（宿主里会渲染成空块）",
                banned.isEmpty(),
            )
            assertEquals(
                "${size.layoutName}.xml 里出现了 ConstraintLayout",
                false,
                tags.any { it.endsWith("ConstraintLayout") },
            )
        }
    }

    /**
     * 布局里的每一颗**可点控件**必须自带 `android:contentDescription`。
     *
     * 桌面是宿主画的，我们没法在那里补 Compose 语义 —— 这是唯一能给读屏名字的口子。
     * 判据只看"带 android:id 且会挂点击"的那几颗（provider 的 `setOnClickPendingIntent`
     * 落在 widget_root 与 widget_thumb_*）。喂一个漏写名字的坏例子必须红。
     */
    @Test
    fun clickableWidgetViewsCarryAContentDescription() {
        for (size in WidgetSize.entries) {
            val text = resFile("layout/${size.layoutName}.xml").readText()
            val bad = viewsMissingDescription(text)
            assertTrue("${size.layoutName}.xml 里这些可点控件没有名字：$bad", bad.isEmpty())
        }
        // 自检：缩略图那一条去掉 contentDescription 之后必须被抓到
        val dirty = """
            <LinearLayout android:id="@+id/widget_root" android:contentDescription="x">
                <ImageView android:id="@+id/widget_thumb_0" />
            </LinearLayout>
        """.trimIndent()
        assertEquals(listOf("widget_thumb_0"), viewsMissingDescription(dirty))
        // 自检 2：不可点的 TextView 不该被要求有名字
        val clean = """
            <LinearLayout android:id="@+id/widget_root" android:contentDescription="x">
                <TextView android:id="@+id/widget_caption" />
            </LinearLayout>
        """.trimIndent()
        assertTrue("只说字的 TextView 不该被要求 contentDescription", viewsMissingDescription(clean).isEmpty())
    }

    /**
     * `AppWidgetProviderInfo` 里的尺寸与 `WidgetSize.WIDE_MIN_WIDTH_DP` 必须对得上。
     *
     * 门槛 250dp 写在两个地方（provider 的常量、这里），对不上就会出现
     * "系统允许拖到 200dp 宽，而 200dp 已经算窄屏" 这类只在某种 launcher 上才看得见的事。
     */
    @Test
    fun widgetInfoAgreesWithTheSizeThreshold() {
        val xml = resFile("xml/widget_recent_info.xml").readText()
        assertEquals(250, WidgetSize.WIDE_MIN_WIDTH_DP)
        // 布局必须存在
        val initial = Regex("""android:initialLayout="@layout/([^"]+)"""")
            .find(xml)?.groupValues?.get(1)
        assertNotNull("widget_recent_info.xml 没有 initialLayout", initial)
        assertTrue(
            "initialLayout 指向 $initial，但磁盘上没有这个布局",
            resFile("layout/$initial.xml").isFile,
        )
        // 尺寸：minWidth/minResizeWidth 必须相等，且都小于"宽屏门槛"
        val minWidth = dpOf(xml, "minWidth")
        val minResize = dpOf(xml, "minResizeWidth")
        assertNotNull("widget_recent_info.xml 没有 minWidth", minWidth)
        assertNotNull("widget_recent_info.xml 没有 minResizeWidth", minResize)
        assertEquals("minWidth 与 minResizeWidth 不一致，2×2 会一放上去就跳成 4×2", minWidth, minResize)
        assertTrue(
            "minWidth=$minWidth 已经 ≥ 宽屏门槛 ${WidgetSize.WIDE_MIN_WIDTH_DP}dp，2×2 永远出不来",
            minWidth!! < WidgetSize.WIDE_MIN_WIDTH_DP,
        )
        // updatePeriod：0 = 永不周期刷新，"最近 4 张"会一直是旧的
        val period = Regex("""android:updatePeriodMillis="(\d+)"""").find(xml)?.groupValues?.get(1)?.toLong()
        assertNotNull("widget_recent_info.xml 没有 updatePeriodMillis", period)
        assertTrue("updatePeriodMillis=$period 不是正数，组件永远不会自己刷新", (period ?: 0L) > 0L)
        assertTrue("updatePeriodMillis=$period 小于系统允许的 30 分钟，会被静默抬上去", (period ?: 0L) >= 30L * 60L * 1000L)
    }

    /**
     * **`AndroidManifest.xml` 里那个 receiver 的形状**：这是本轮唯一改动的既有文件，
     * 而它错了的症状是"组件根本不出现"或"永远停在 initialLayout"。
     *
     * 三条都钉：
     *  - `android:name` 与实际类名对得上（改名忘了改 manifest = 组件从列表里消失）；
     *  - `android:exported="true"`（Android 12+ 的硬要求，launcher 是别的应用）；
     *  - `android.appwidget.provider` 的 meta-data 指向存在的 xml。
     */
    @Test
    fun manifestDeclaresTheWidgetReceiverCorrectly() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml 里没有 widget 的 receiver",
            ".widget.RecentWidgetProvider" in manifest,
        )
        // 取 receiver 那一段（从 <receiver 到下一个 </receiver>）
        val block = Regex("""<receiver[\s\S]*?android:name="\.widget\.RecentWidgetProvider"[\s\S]*?</receiver>""")
            .find(manifest)?.value
        assertNotNull("找不到 RecentWidgetProvider 的 receiver 节点", block)
        assertTrue(
            "receiver 缺 android:exported=\"true\"：Android 12+ 上它收不到 APPWIDGET_UPDATE，" +
                "桌面上那块会永远停在 initialLayout",
            """android:exported="true"""" in block!!.replace(" ", ""),
        )
        assertTrue(
            "receiver 里没有 APPWIDGET_UPDATE 的 intent-filter，系统不会唤它",
            "android.appwidget.action.APPWIDGET_UPDATE" in block,
        )
        val providerXml = Regex("""android:name="android\.appwidget\.provider"\s+android:resource="@xml/([^"]+)"""")
            .find(block)?.groupValues?.get(1)
        assertNotNull("receiver 没有 android.appwidget.provider 的 meta-data", providerXml)
        assertEquals("meta-data 指向的 xml 不是我们写的那个", "widget_recent_info", providerXml)
        assertTrue(
            "meta-data 指向的 ${providerXml}.xml 在磁盘上不存在",
            resFile("xml/$providerXml.xml").isFile,
        )
        // 自检：把 exported 改回 false，同一个判据必须红
        val broken = block!!.replace("""android:exported="true"""", """android:exported="false"""")
        assertFalse("自检失败：exported=false 没被识别出来", """android:exported="true"""" in broken.replace(" ", ""))
    }

    /**
     * `MainActivity` 认得的 host 清单必须与 `SupportedDeepLinks.HOSTS` 一致。
     *
     * `MainActivity` 那边是一条 `takeIf { it == "camera" || it == "sync" || it == "yishun" }`。
     * 改了那边而这里没跟上，我们就会继续发一个"点了停在相册页"的深链 ——
     * 而这正是快捷方式最容易无声腐烂的地方。本轮不许改 `MainActivity`，所以只读不写。
     */
    @Test
    fun supportedDeepLinksMatchMainActivity() {
        val main = repoFile("app/src/main/java/com/suipai/app/MainActivity.kt").readText()
        val line = main.lineSequence().firstOrNull { "launchHop" in it && "takeIf" in it }
        assertNotNull("MainActivity 里找不到 collectHop 那一行，读法失效了", line)
        val hosts = Regex("""it\s*==\s*"([^"]+)"""").findAll(line!!).map { it.groupValues[1] }.toSet()
        assertEquals("MainActivity 与 SupportedDeepLinks.HOSTS 对不上", hosts, SupportedDeepLinks.HOSTS)
        // 自检：那三个 host 里随便删一个，比较必须不成立
        assertTrue("自检失败：少一个 host 的集合被判成相等了", (hosts - "yishun") != SupportedDeepLinks.HOSTS)
    }

    /**
     * 小组件的代码**不许在主线程查库**，也**不许漏掉 `goAsync` 的 finish**。
     *
     * `onUpdate` 跑在主线程上；在那儿查 MediaStore 是"每次桌面重绘都可能触发一次磁盘 IO"。
     * 而 `goAsync()` 的 `PendingResult` 少 `finish()` 一次，系统会记一次"广播没结束"，
     * 连记几次之后这个接收器就再也不被唤醒了 —— 表现为"小组件永远停在旧画面"，
     * 且**不崩、不报错、没有任何日志**。
     */
    @Test
    fun providerNeverQueriesTheDatabaseOnTheMainThread() {
        val src = repoFile("app/src/main/java/com/suipai/app/widget/RecentWidgetProvider.kt").readText()
        assertTrue("provider 里没有 goAsync()：查询就落在主线程上了", "goAsync()" in src)
        assertTrue("goAsync() 的 PendingResult 没有 finish()", ".finish()" in src)
        // 每个 contentResolver.query 都必须在一段 withContext(Dispatchers.IO) 里 ——
        // 判据：含 query( 的那个函数里必须出现 Dispatchers.IO。
        val queryFnAt = src.lineSequence().indexOfFirst { "resolver.query(" in it }
        assertTrue("provider 里没有 resolver.query(，读法失效了", queryFnAt >= 0)
        val body = sourceFunctionBody(src.lines(), "queryRecent")
        assertNotNull("找不到 queryRecent 的函数体", body)
        assertTrue(
            "queryRecent 不在 Dispatchers.IO 上跑，查询会落在调用线程（广播是主线程）",
            body!!.any { "Dispatchers.IO" in it },
        )
        // 自检：把一个没有 withContext 的版本喂进同一个扫描器，必须报红
        val dirty = listOf(
            "private suspend fun queryRecent(context: Context) = withContext(Dispatchers.IO) {}",
            "private suspend fun countAll(context: Context) {}",
        )
        assertTrue(
            "自检失败：没有 Dispatchers.IO 的函数被判成合规了",
            sourceFunctionBody(dirty, "countAll")!!.none { "Dispatchers.IO" in it },
        )
    }

    /**
     * 单例锁必须是 **static**：`AppWidgetProvider` 每次广播都会新建一个实例，
     * 实例字段做锁等于没锁（`AtomicBoolean()` 当成员变量是这块最典型的错写法）。
     */
    @Test
    fun inFlightGuardIsProcessWide() {
        val src = repoFile("app/src/main/java/com/suipai/app/widget/RecentWidgetProvider.kt").readText()
        assertTrue("没有 AtomicBoolean：并发刷新会把同一批缩略图反复解码", "AtomicBoolean(" in src)
        // 它必须在 companion object 里（进程级），不能在类体顶部当作实例字段
        val companionAt = src.indexOf("companion object")
        val guardAt = src.indexOf("AtomicBoolean(")
        assertTrue("AtomicBoolean 不在 companion object 里 —— 每个广播一个新实例，这把锁没用", guardAt > companionAt)
        assertTrue("companion object 里没有 inFlight", "inFlight" in src.substring(companionAt, guardAt + 200))
        // 用的是 CAS，不是先读后写
        assertTrue("没看到 compareAndSet：`if (!flag.get()) flag.set(true)` 是竞态", "compareAndSet" in src)
    }

    // ══ 辅助 ═════════════════════════════════════════════════════════

    /** 造一串"从新到旧"的照片。 */
    private fun photos(vararg ids: Long): List<WidgetPhoto> =
        ids.map { WidgetPhoto(it, "content://media/external/images/media/$it", it * 1000L) }

    /** `:app` 单测的工作目录是 `app/`。 */
    private fun resFile(relative: String): File {
        val f = File("src/main/res/$relative")
        assertTrue("找不到资源 $relative（从 ${File("").absolutePath} 出发）", f.isFile)
        return f
    }

    /** 仓库根要往上退一层（同 `AiGuardTest.repoFile`）。 */
    private fun repoFile(relative: String): File {
        val f = File("../$relative")
        assertTrue("找不到 $relative（从 ${File("").absolutePath} 出发）", f.isFile)
        return f
    }

    private fun dpOf(xml: String, attr: String): Int? =
        Regex("""android:$attr="(\d+)dp"""").find(xml)?.groupValues?.get(1)?.toInt()

    /**
     * 布局里"会挂点击"的那几颗视图，哪些没写 `android:contentDescription`。
     *
     * 判据故意只看 `widget_root` 与 `widget_thumb_*`：那是 provider 里
     * `setOnClickPendingIntent` 唯一的两个落点（见 `RecentWidgetProvider.draw`）。
     * 给说明那行 `widget_caption` 也要求名字是错的 —— 它只说字，不承载点击。
     */
    private fun viewsMissingDescription(xml: String): List<String> =
        Regex("""<(ImageView|LinearLayout|FrameLayout|RelativeLayout)[^>]*android:id="@\+id/(widget_root|widget_thumb_\d)"[^>]*/?>""")
            .findAll(xml)
            .filter { !it.value.contains("android:contentDescription") }
            .map { it.groupValues[2] }
            .toList()

    /**
     * 从 `fun <fn>(` 起按花括号配平取出整个函数体（含签名行）。
     * 抄的是 `PerfSourceGuardTest.functionBody` 的形状；读不到返回 null。
     */
    private fun sourceFunctionBody(lines: List<String>, fn: String): List<String>? {
        val start = lines.indexOfFirst { Regex("fun\\s+$fn\\s*\\(").containsMatchIn(it) }
        if (start < 0) return null
        val body = ArrayList<String>()
        var depth = 0
        var seen = false
        var i = start
        while (i < lines.size) {
            val code = lines[i].substringBefore("//")
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (depth > 0) seen = true
            body += lines[i]
            i++
            if (seen && depth <= 0) break
        }
        return body
    }
}
