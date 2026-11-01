package com.suipai.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 路由编码往返 + 导航优先级的单测。
 *
 * 为什么值得单独写：编码这一层一旦错，症状是"重建之后回到首页"或"回来之后删错照片"，
 * 编译器一条都不报；而返回键优先级一旦错，症状是"批量进行中按返回先把选择清了"，
 * 装机上要点很多次才看得出来。
 */
class AppRouteTest {

    /** 防的 bug：`album:` 里的 id 带冒号（MediaStore 的 bucketId 是数字串，但虚拟册是字符串），
     *  解析时被按冒号切碎 → 内页 title 串到 id 上。 */
    @Test
    fun albumIdWithSeparatorsSurvivesRoundTrip() {
        val route = AppRoute.AlbumDetail("suipai-favorites", "收藏")
        assertEquals(route, AppRoute.decode(route.encode()))
    }

    /** 防的 bug：相册标题里有中文/百分号/冒号/换行时，恢复出来的名字变了（读屏会念错）。 */
    @Test
    fun albumTitleWithSpecialCharactersSurvivesRoundTrip() {
        val titles = listOf(
            "最近",
            "2024-09 · 出差",
            "100% 原图",
            "a:b:c",
            "%3A 已经是转义串",
            "换\n行",
        )
        for (t in titles) {
            val route = AppRoute.AlbumDetail("bucket-1", t)
            assertEquals("标题「$t」往返之后变了", route, AppRoute.decode(route.encode()))
        }
    }

    /** 防的 bug：标题为空（`albumTitle = null`）时恢复成一个空字符串标题，页名那格变成空白。 */
    @Test
    fun nullAlbumTitleStaysNull() {
        val route = AppRoute.AlbumDetail("b1", null)
        assertEquals(route, AppRoute.decode(route.encode()))
        assertEquals(null, (AppRoute.decode(route.encode()) as AppRoute.AlbumDetail).albumTitle)
    }

    /** 防的 bug：一瞬的 content:// uri 里有斜杠、问号、等号时被编坏 → 打开一张不存在的图。 */
    @Test
    fun yishunUriWithQueryStringSurvivesRoundTrip() {
        val uris = listOf(
            "content://media/external/images/media/42",
            "file:///cache/camera/system.jpg",
            "content://com.android.providers.media.documents/document/image%3A1000000033",
            "content://x/a?b=1:c",
        )
        for (u in uris) {
            val route = AppRoute.Yishun(u)
            assertEquals("uri「$u」往返之后变了", route, AppRoute.decode(route.encode()))
        }
    }

    /** 防的 bug：查看器页号是负数或巨大值时解码抛异常（不许：Bundle 里的东西只许降级）。 */
    @Test
    fun viewerIndexIsClampedNotThrown() {
        assertEquals(AppRoute.Viewer(0), AppRoute.decode("viewer:-3"))
        assertEquals(AppRoute.Viewer(0), AppRoute.decode(AppRoute.Viewer(-3).encode()))
        assertEquals(AppRoute.Viewer(123456), AppRoute.decode("viewer:123456"))
        assertNull(AppRoute.decode("viewer:abc"))
    }

    /** 防的 bug：Tab 的编码和下标分家（`tab:1` 解成照片页）。 */
    @Test
    fun tabEncodingsMatchTheirIndexes() {
        assertEquals("tab:0", AppRoute.Gallery.encode())
        assertEquals("tab:1", AppRoute.Albums.encode())
        assertEquals("tab:2", AppRoute.Sync.encode())
        assertEquals(AppRoute.Gallery, AppRoute.decode("tab:0"))
        assertEquals(AppRoute.Albums, AppRoute.decode("tab:1"))
        assertEquals(AppRoute.Sync, AppRoute.decode("tab:2"))
        assertEquals(AppRoute.Gallery, AppRoute.decode("tab:9"))
    }

    /** 防的 bug：不认识的编码被当成某个默认路由（启动落在错误的地方而不是首页）。 */
    @Test
    fun unknownEncodingsDecodeToNull() {
        assertNull(AppRoute.decode(""))
        assertNull(AppRoute.decode("nope"))
        assertNull(AppRoute.decode("album:"))
        assertNull(AppRoute.decode("album:x"))       // 段数不够
        assertNull(AppRoute.decode("yishun:"))        // 空 uri
        assertNull(AppRoute.decode("tab:"))
    }

    /** 防的 bug：相机这种"无参数目的地"被编码成带冒号的形状后解不回来。 */
    @Test
    fun parameterlessRoutesRoundTrip() {
        assertEquals("camera", AppRoute.Camera.encode())
        assertEquals(AppRoute.Camera, AppRoute.decode("camera"))
    }

    /** 防的 bug：转义顺序做反了（先换 `%` 会把后面换出来的 `%3A` 再编码一遍，往返不还原）。 */
    @Test
    fun codecEscapeIsReversible() {
        val raw = "a%3Ab:c\nd\re"
        assertEquals(raw, RouteCodec.unescape(RouteCodec.escape(raw)))
        // 反向：已经是转义串的内容不许被二次解释
        assertEquals("%25", RouteCodec.escape("%"))
        assertEquals(":", RouteCodec.unescape("%3A"))
    }

    // ── 二级页面（设置 / 隐私 / 回收站 / 收藏夹 / 搜索 / 回忆 / 拼图 / 幻灯片） ──

    /**
     * 防的 bug：新增的 data object 少写了一条 `decode` 分支 ——
     * 症状是"进程被杀 / 转屏重建之后回到首页"，而且**不会报任何错**（`decode` 返回 null，
     * `restore` 静默丢掉那一层）。这条把 9 个枚举型目的地逐个钉一遍。
     */
    @Test
    fun everyParameterlessSecondaryRouteRoundTrips() {
        val routes = listOf(
            AppRoute.Settings,
            AppRoute.SettingsAppearance,
            AppRoute.SettingsStorage,
            AppRoute.SettingsAbout,
            AppRoute.Privacy,
            AppRoute.Onboarding,
            AppRoute.Trash,
            AppRoute.Collections,
            AppRoute.Memories,
            AppRoute.Backup,
            AppRoute.Videos,
        )
        for (r in routes) {
            assertEquals("${r::class.simpleName} 往返之后变了", r, AppRoute.decode(r.encode()))
        }
    }

    /**
     * 防的 bug：设置四页编码串互为前缀（`settings` / `settings.appearance`），
     * 解码顺序写反就会"进外观页却落在设置总目录"。
     */
    @Test
    fun settingsSubPagesDoNotCollide() {
        assertEquals(AppRoute.Settings, AppRoute.decode("settings"))
        assertEquals(AppRoute.SettingsAppearance, AppRoute.decode("settings.appearance"))
        assertEquals(AppRoute.SettingsStorage, AppRoute.decode("settings.storage"))
        assertEquals(AppRoute.SettingsAbout, AppRoute.decode("settings.about"))
        // 四个必须互不相同
        val encoded = listOf(
            AppRoute.Settings.encode(),
            AppRoute.SettingsAppearance.encode(),
            AppRoute.SettingsStorage.encode(),
            AppRoute.SettingsAbout.encode(),
        )
        assertEquals("设置四页的编码撞了", encoded.size, encoded.toSet().size)
    }

    /** 防的 bug：收藏夹名里有冒号/百分号时，返回栈恢复出来的册名被打断（同 AlbumDetail 那一族）。 */
    @Test
    fun collectionDetailSurvivesSpecialCharacters() {
        for (name in listOf("旅行", "2024:夏", "100% 精选", "a\nb")) {
            val r = AppRoute.CollectionDetail("col-1", name)
            assertEquals("册名「$name」往返之后变了", r, AppRoute.decode(r.encode()))
        }
        // 名可为空；空 id 一律解不出（宁可丢层，也不进一个没有 id 的页面）
        assertEquals(AppRoute.CollectionDetail("col-1", null), AppRoute.decode(AppRoute.CollectionDetail("col-1", null).encode()))
        assertNull(AppRoute.decode("collection::x"))
        assertNull(AppRoute.decode("collection:only-one"))
    }

    /** 防的 bug：回忆详情的 id 里带 `/` 或 `:` 时被编坏，点进去落到"回忆不存在"。 */
    @Test
    fun memoryDetailSurvivesSpecialCharacters() {
        for (id in listOf("mem-2024-09-24", "a:b", "x/y", "某年今天")) {
            val r = AppRoute.MemoryDetail(id)
            assertEquals("回忆 id「$id」往返之后变了", r, AppRoute.decode(r.encode()))
        }
        assertNull(AppRoute.decode("memory:"))
    }

    /**
     * 防的 bug：搜索页的初始词带着空格/冒号时被编坏；
     * 以及"没带词的搜索"被编成 `search:` 之后解不回来（那是两个不同的串，同一个目的地）。
     */
    @Test
    fun searchCarriesAnOptionalQuery() {
        assertEquals("search", AppRoute.Search(null).encode())
        assertEquals("search", AppRoute.Search("").encode())
        assertEquals(AppRoute.Search(null), AppRoute.decode("search"))
        assertEquals(AppRoute.Search(null), AppRoute.decode("search:"))
        for (q in listOf("日落", "2024:09", "a b", "100%", " 前后空格 ")) {
            val r = AppRoute.Search(q)
            assertEquals("搜索词「$q」往返之后变了", r, AppRoute.decode(r.encode()))
        }
    }

    /**
     * 防的 bug（这条最容易做错）：拼图带一长串 id 进 Bundle。
     * 选中 500 张时那串数字有几 KB，而 Bundle 有 1 MB 硬上限、进程被杀后索引本来也要重建。
     * 规则：**超过 24 个只存 count**，解码回来 ids 为空、count 保留（宿主按 count 重新取）。
     */
    @Test
    fun collageTruncatesLongSelectionToACount() {
        val few = AppRoute.Collage(listOf(1L, 2L, 3L))
        assertEquals(few, AppRoute.decode(few.encode()))
        assertEquals("collage:1,2,3", few.encode())

        val many = AppRoute.Collage((1L..100L).toList())
        val encoded = many.encode()
        assertEquals("超长选择只该存 count", "collage:~100", encoded)
        val restored = AppRoute.decode(encoded) as AppRoute.Collage
        assertEquals(0, restored.photoIds.size)
        assertEquals("count 要留住（宿主按它重新取 N 张）", 100, restored.count)

        // 边界：正好 24 个仍然存全（不是 bug，是刻意的边界）
        val exact = AppRoute.Collage((1L..24L).toList())
        assertEquals(24, (AppRoute.decode(exact.encode()) as AppRoute.Collage).photoIds.size)
    }

    /** 防的 bug：拼图的 count 编成负数/巨大值时解出非法值（Bundle 里的东西只许降级）。 */
    @Test
    fun collageDecodeClampsAndRejectsGarbage() {
        assertEquals(0, (AppRoute.decode("collage:~-5") as AppRoute.Collage).count)
        assertEquals(AppRoute.Collage.MAX_COUNT, (AppRoute.decode("collage:~999999") as AppRoute.Collage).count)
        assertNull(AppRoute.decode("collage:abc"))
        // 空 body 是合法的"没带选择"
        assertEquals(emptyList<Long>(), (AppRoute.decode("collage:") as AppRoute.Collage).photoIds)
    }

    /** 防的 bug：幻灯片的起始页编成负数/非数字时崩在恢复上（同 Viewer）。 */
    @Test
    fun slideshowStartIndexIsClampedNotThrown() {
        assertEquals(AppRoute.Slideshow(0), AppRoute.decode(AppRoute.Slideshow(-3).encode()))
        assertEquals(AppRoute.Slideshow(123456), AppRoute.decode("slideshow:123456"))
        assertNull(AppRoute.decode("slideshow:abc"))
        assertNull(AppRoute.decode("slideshow:"))
    }

    /**
     * 防的 bug：**新目的地混进 Tab 那一族**。二级页面如果 `family` 写成 "tab"，
     * `BackStack.push` 会把它当 Tab 丢掉 —— 用户点了「设置」什么都不发生。
     */
    @Test
    fun secondaryRoutesAreNotTabs() {
        val routes = listOf<AppRoute>(
            AppRoute.Settings, AppRoute.SettingsAppearance, AppRoute.SettingsStorage,
            AppRoute.SettingsAbout, AppRoute.Privacy, AppRoute.Onboarding, AppRoute.Trash,
            AppRoute.Collections, AppRoute.CollectionDetail("c", "n"), AppRoute.Memories,
            AppRoute.MemoryDetail("m"), AppRoute.Search(null), AppRoute.Collage(),
            AppRoute.Slideshow(0), AppRoute.Duplicates, AppRoute.StorageInsight,
            AppRoute.RecipeCard("content://a", "daylight", 0.5f),
            AppRoute.Backup, AppRoute.Videos, AppRoute.VideoPlayer(42L),
        )
        for (r in routes) {
            assertFalse("${r::class.simpleName} 不许是 Tab", r is AppRoute.Tab)
            assertFalse("${r::class.simpleName} 的 family 不许是 tab", r.family == "tab")
            // 每个二级页都要能真的入栈（push 会静默丢掉 Tab —— 这里保证它不被丢）
            val s = BackStack.of(AppRoute.Gallery).push(r)
            assertEquals("${r::class.simpleName} 没能入栈", 1, s.depth)
        }
    }

    // ── 分析工具与配方卡（本轮新增）─────────────────────────────────

    /**
     * 防的 bug：`duplicates` / `storage.insight` 少写一条 `decode` 分支 ——
     * 症状是"进程被杀 / 转屏重建之后回到首页"，且不报任何错。
     *
     * `storage.insight` 还多一层风险：**它不能与 `settings.storage` 撞 family**，
     * 否则 `push` 会把「设置 › 存储」顶掉（两个是完全不同的页面）。
     */
    @Test
    fun analysisToolsRoundTripAndDoNotCollideWithSettingsStorage() {
        assertEquals("duplicates", AppRoute.Duplicates.encode())
        assertEquals(AppRoute.Duplicates, AppRoute.decode("duplicates"))
        assertEquals("storage.insight", AppRoute.StorageInsight.encode())
        assertEquals(AppRoute.StorageInsight, AppRoute.decode("storage.insight"))

        // 与设置存储页的编码/家族都必须不同
        assertFalse(
            "「存储分析」与「设置 › 存储」的编码撞了",
            AppRoute.StorageInsight.encode() == AppRoute.SettingsStorage.encode(),
        )
        assertFalse(
            "「存储分析」与「设置 › 存储」的 family 撞了（push 会互相顶掉）",
            AppRoute.StorageInsight.family == AppRoute.SettingsStorage.family,
        )
        // 也不能与"重复照片"撞（同一族的页面重复进入会被替换，不是叠层）
        assertFalse(AppRoute.StorageInsight.family == AppRoute.Duplicates.family)
    }

    /**
     * 防的 bug：配方卡的三个参数丢一个。
     *
     * 最容易漏的是 `intensity` —— 只带 `lookId` 时，从一瞬进配方卡后重建一次，
     * 重新按 prefs 取强度会得到"上次保存的值"，与用户眼前那张卡不是同一张。
     * `uri` 里含 `:` 与 `%`，必须过 `RouteCodec`。
     */
    @Test
    fun recipeCardCarriesAllThreeParameters() {
        val cases = listOf(
            AppRoute.RecipeCard("content://media/external/images/media/42", "daylight", 0.55f),
            AppRoute.RecipeCard("file:///cache/x.jpg", "silver", 0f),
            AppRoute.RecipeCard("content://a?b=1:c", "look:with:colon", 1f),
            AppRoute.RecipeCard("content://media/中文", "夜", 0.123f),
        )
        for (r in cases) {
            val back = AppRoute.decode(r.encode())
            assertEquals("配方卡往返之后变了：$r", r, back)
            val cast = back as AppRoute.RecipeCard
            assertEquals("uri 丢了", r.uri, cast.uri)
            assertEquals("lookId 丢了", r.lookId, cast.lookId)
            assertEquals("intensity 丢了", r.intensity, cast.intensity, 0.0001f)
        }
    }

    /**
     * 防的 bug（照抄 `collage:abc` 那条坏例子口径）：脏强度不许被猜成默认值。
     *
     * `"abc".toFloatOrNull()` 是 null、`NaN` / `Infinity` 也能被 `toFloat` 认出来 ——
     * 三种都必须**整串作废返回 null**（让恢复丢掉这一层），不许 `?: 0.5f` 猜一个值：
     * 猜出来的强度会画出一张"看起来正常、但不是用户当时那一张"的配方卡。
     */
    @Test
    fun recipeCardRejectsBadIntensityInsteadOfGuessing() {
        // 注意这一族必须是**手工拼的裸串**：测的就是"编码里出现坏值"时的降级。
        // 但 uri 段这里用一个**不含冒号**的假串（`x`），否则 `content://a` 里的
        // 两个裸冒号会让 split 多切两段 —— 那是"没转义"的错，不是"强度坏"的错，
        // 两者混在一起就测不清了（第一版就是这么红的：NPE 来自强转 null）。
        // `content://` 这类真 uri 的往返另见 `recipeCardCarriesAllThreeParameters`。
        assertNull("强度是文字要判废", AppRoute.decode("recipeCard:x:look:abc"))
        assertNull("NaN 要判废", AppRoute.decode("recipeCard:x:look:NaN"))
        assertNull("Infinity 要判废", AppRoute.decode("recipeCard:x:look:Infinity"))
        assertNull("-Infinity 要判废", AppRoute.decode("recipeCard:x:look:-Infinity"))
        // 段数不对 / 空字段也一律作废
        assertNull("段数不够要判废", AppRoute.decode("recipeCard:x:look"))
        assertNull("空 uri 要判废", AppRoute.decode("recipeCard::look:0.5"))
        assertNull("空 lookId 要判废", AppRoute.decode("recipeCard:x::0.5"))

        // 越界强度**不**作废，夹回 0..1（它是个连续量，不是身份标识）。
        // 走**编码往返**而不是手写裸串：`encode()` 负责转义，`decode()` 只认转义过的形式
        // （`content://` 天生带冒号，这条路由不支持裸串 —— 见 decode 的注释）。
        val over = AppRoute.RecipeCard("content://a", "look", 3.5f)
        val overBack = AppRoute.decode(over.encode()) as AppRoute.RecipeCard
        assertEquals("越界强度必须夹到 1，不许原样留着", 1f, overBack.intensity, 1e-6f)

        val under = AppRoute.RecipeCard("content://a", "look", -2f)
        val underBack = AppRoute.decode(under.encode()) as AppRoute.RecipeCard
        assertEquals("负强度必须夹到 0", 0f, underBack.intensity, 1e-6f)
    }

    /**
     * 防的 bug：有人拿**裸串**去喂 decode 然后以为解析坏了。
     *
     * `uri` 里天然带 `://` 两个冒号，而本路由用 `:` 分段 —— 所以这条路由
     * **只支持 `encode()` 转义过的形式**。这一条钉住"裸串返回 null 而不是解出半截"，
     * 并把口径写在测试里，免得下一个人以为是 decoder 的 bug 去改分隔符。
     */
    @Test
    fun recipeCardRejectsUnescapedRawString() {
        // `content://a` 没转义 → 冒号多切两段 → 判废（返回 null，不是抛异常）
        assertNull(AppRoute.decode("recipeCard:content://a:look:0.5"))
        // 转义过的同一组参数就能解（证明上面那格的 null 是"格式不对"而不是"内容不对"）
        val escaped = AppRoute.RecipeCard("content://a", "look", 0.5f)
        assertEquals(escaped, AppRoute.decode(escaped.encode()))
    }

    // ── 备份与恢复（`settings.backup`）─────────────────────────────────

    /**
     * 防的 bug：`settings.backup` 少写一条 `decode` 分支 —— 症状是"导出/恢复进行中来一通电话
     * 回来，人已经在照片页了"，且不报任何错。
     *
     * 同时钉住它与设置四页的**编码互不相同**：四条 `settings.*` 前缀相同，
     * 解码顺序写反（例如先判 `startsWith("settings.")`）就会全部落进第一个分支。
     */
    @Test
    fun backupRouteRoundTripsAndDoesNotCollideWithSettingsSubPages() {
        assertEquals("settings.backup", AppRoute.Backup.encode())
        assertEquals(AppRoute.Backup, AppRoute.decode("settings.backup"))
        assertEquals("settings.backup", AppRoute.Backup.family)

        val all = listOf(
            AppRoute.Settings,
            AppRoute.SettingsAppearance,
            AppRoute.SettingsStorage,
            AppRoute.SettingsAbout,
            AppRoute.Backup,
        )
        assertEquals("设置五页的编码撞了", all.size, all.map { it.encode() }.toSet().size)
        assertEquals("设置五页的 family 撞了（push 会互相顶掉）", all.size, all.map { it.family }.toSet().size)
        // 每一页都要能解回它自己（前缀相同，最容易在这一格写错）
        for (r in all) assertEquals(r, AppRoute.decode(r.encode()))
    }

    /**
     * 防的 bug（喂坏例子必须变红）：`settings.backup` 的近亲串被认成本路由。
     *
     * `decode` 用的是**精确相等**而不是 `startsWith("settings.")`：
     * 后者会让 `settings.backup2` / `settings.backup.extra` 都落进备份页，
     * 而那两个串今天不存在、明天可能被别的 lane 用掉。
     */
    @Test
    fun backupRouteRejectsNearMissEncodings() {
        assertNull("近亲串不许被认成备份页", AppRoute.decode("settings.backup2"))
        assertNull("多一段的串不许被认成备份页", AppRoute.decode("settings.backup.extra"))
        assertNull("大小写不同不算同一条", AppRoute.decode("Settings.Backup"))
        assertNull("前缀少了 settings 也不算", AppRoute.decode("backup"))
        assertNull("空串要判废", AppRoute.decode(""))
        // 对照：真正的编码必须能解（证明上面那几个 null 是"格式不对"而不是"整族坏了"）
        assertEquals(AppRoute.Backup, AppRoute.decode("settings.backup"))
    }

    // ── 视频（`videos` / `video:<id>`）─────────────────────────────────

    /**
     * 防的 bug：视频列表与播放器少写一条 `decode` 分支 —— 播到一半来电话、
     * 或者切一次分屏，回来就退回照片页，而且不报错。
     *
     * 播放器只带 `id`（不带 `uri`）：`VideoItem` 有九个字段、其中一个是 `Uri`，
     * 整套塞进 Bundle 就是把重数据放进路由（与 `Viewer` 同一条理由）。
     */
    @Test
    fun videoRoutesRoundTrip() {
        assertEquals("videos", AppRoute.Videos.encode())
        assertEquals(AppRoute.Videos, AppRoute.decode("videos"))

        for (id in listOf(1L, 42L, 1234567890123L)) {
            val r = AppRoute.VideoPlayer(id)
            assertEquals("视频 id $id 往返之后变了", r, AppRoute.decode(r.encode()))
        }
        // 两个 family 必须不同：`push` 靠它判"是替换栈顶还是叠一层"。
        // 撞了的话"从列表点开一段视频"会把列表那一层顶掉 —— 返回键一下直接回照片页。
        assertFalse(AppRoute.Videos.family == AppRoute.VideoPlayer(1L).family)
    }

    /**
     * 防的 bug（喂坏例子必须变红）：播放器的 `id` 编成 0 / 负数 / 非数字时解出一个假 id。
     *
     * MediaStore 的行号从 1 起，所以 0 与负数都是**坏数据**。这里不许"夹到 0"或者
     * "猜一个 1"：猜出来的那个 id 会打开**另一段**视频，而用户以为自己点的是原来那段。
     * 判废（返回 null）让恢复流程丢掉这一层，落到下面的列表页。
     */
    @Test
    fun videoPlayerRejectsBadIdsInsteadOfGuessing() {
        assertNull("0 不是合法行号", AppRoute.decode("video:0"))
        assertNull("负数是坏数据", AppRoute.decode("video:-5"))
        assertNull("非数字要判废", AppRoute.decode("video:abc"))
        assertNull("空 id 要判废", AppRoute.decode("video:"))
        assertNull("小数要判废", AppRoute.decode("video:1.5"))
        assertNull("溢出的要判废", AppRoute.decode("video:99999999999999999999999999"))
        // 编码侧：负数不许被编成一个能解回来的串（`coerceAtLeast(0)` 之后 decode 会判废）
        assertNull("负 id 编出来的串自己也解不回来", AppRoute.decode(AppRoute.VideoPlayer(-1L).encode()))
        // 对照：合法 id 必须能解
        assertEquals(AppRoute.VideoPlayer(7L), AppRoute.decode("video:7"))
    }

    /**
     * 防的 bug：`videos` 被 `startsWith("video")` 那种写法吃掉 ——
     * `videos` 是 `video` 的**前缀超集**，判据顺序反了会"进列表却落进播放器"。
     *
     * `AppRoute.decode` 里 `encoded == "videos"` 那一行必须排在
     * `encoded.startsWith("video:")` 之前（前者精确相等，后者要冒号，本不会冲突，
     * 但这条测试把"两者不许互相吃掉"这件事钉死，免得以后有人改成 `startsWith("video")`）。
     */
    @Test
    fun videosListAndVideoPlayerDoNotSwallowEachOther() {
        assertEquals(AppRoute.Videos, AppRoute.decode("videos"))
        assertEquals(AppRoute.VideoPlayer(3L), AppRoute.decode("video:3"))
        assertNull("video: 家族里没有 id 时不许退回列表", AppRoute.decode("video:"))
        assertNull("videos: 不是任何一条路由", AppRoute.decode("videos:"))
        assertNull("video 单独一个词不是路由", AppRoute.decode("video"))
    }
}

/**
 * 备份文件名与设置页那一行搜索判据的单测。
 *
 * 这两个都是**纯函数**，而它们的错法全在"用户看不见的地方"：
 * 文件名错一位，用户在新设备上就挑错文件；搜索判据与 `SettingsModel` 的口径分家，
 * 用户搜同一个词会看到有的行出现、有的行不出现。两台设备上都不报错，只有人肉复核才看得见。
 */
class BackupUiTextTest {

    /**
     * 防的 bug：默认文件名不带日期 / 带非法字符 / 月份不补零。
     *
     * 三件事都会让用户在 SAF 里挑错文件：一排 `随拍备份 2026-1-8.json` 与
     * `随拍备份 2026-10-18.json`，前者看起来"更小"（少一位），实际是更旧的那份。
     */
    @Test
    fun backupFileNameCarriesAZeroPaddedDate() {
        // 用一个**固定时刻**而不是 `System.currentTimeMillis()`：这条要能逐字断言。
        // 2026-10-18 14:30 → 文件名里必须是 2026-10-18。
        val at = java.time.ZonedDateTime.of(2026, 10, 18, 14, 30, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val name = com.suipai.app.nav.backupSuggestedName(at)
        assertEquals("随拍备份 2026-10-18.json", name)

        // 个位数的月份与日子必须补零（1 月 8 日 → 01-08）
        val jan = java.time.ZonedDateTime.of(2026, 1, 8, 0, 0, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals("随拍备份 2026-01-08.json", com.suipai.app.nav.backupSuggestedName(jan))

        // 与屏幕上那句「上次导出 2026-10-18 14:30」必须能对上同一天
        val said = com.suipai.app.ui.settings.backupStamp(at)
        assertTrue("文件名里的日子在屏幕上那句里找不到：文件名=$name 屏幕=$said", said.contains("2026-10-18"))

        // 不许有路径分隔符 / 冒号：部分 provider 会把它们改名或截断
        assertFalse("文件名里有非法字符：$name", name.contains('/') || name.contains('\\') || name.contains(':'))
        assertTrue("扩展名必须是 .json（OpenDocument 那边按它筛）", name.endsWith(".json"))
    }

    /**
     * 防的 bug：设置页那一行「导出与恢复」不参与搜索 —— 用户搜「备份」看到一屏"没有匹配"，
     * 而那一行明明就挂在存储组末尾。
     *
     * 判据必须与 `SettingsModel.matches` **同一套**（标题/副标题/关键词，忽略大小写）。
     */
    @Test
    fun backupRowIsFoundByTheWordsUsersActuallyType() {
        for (q in listOf("备份", "导出", "恢复", "还原", "换机", "json", "JSON", "导入", "导出与恢复")) {
            assertTrue("搜「$q」应当命中导出与恢复那一行", com.suipai.app.ui.settings.settingsBackupRowMatches(q))
        }
        // 空词不命中：否则"没在搜索"的时候会凭空多出一行（它已经由分组自己画了）
        assertFalse(com.suipai.app.ui.settings.settingsBackupRowMatches(""))
        assertFalse("全是空白也不许命中", com.suipai.app.ui.settings.settingsBackupRowMatches("   "))
        // 不相干的词不许命中（否则搜索就失去过滤意义）
        assertFalse(com.suipai.app.ui.settings.settingsBackupRowMatches("快门声"))
        assertFalse(com.suipai.app.ui.settings.settingsBackupRowMatches("zzzz"))
    }
}

/**
 * 返回键优先级 + Tab 切换规则的单测。
 *
 * 这一层是改前那个 7 分支 `when` 的等价物，差别只在于它现在**能被测**。
 */
class NavPolicyTest {

    private val nothing = NavPolicy.Intercepts()

    /** 防的 bug：界面上什么都没有时还拦着返回键（用户按返回退不出 App）。 */
    @Test
    fun nothingOpenMeansNoIntercept() {
        assertEquals(false, NavPolicy.intercepts(nothing))
        assertEquals(NavPolicy.BackAction.EXIT, NavPolicy.resolveBack(nothing, 0))
    }

    /**
     * 防的 bug（改前注释里的第一优先级）：批量进行中按返回，必须**停任务**，
     * 而不是把选择清掉 —— 清了选择，用户就看不见自己在停哪一批。
     */
    @Test
    fun runningBatchOutranksEverythingElse() {
        val all = NavPolicy.Intercepts(
            batchRunning = true, hasSelection = true, yishunOpen = true,
            cameraOpen = true, viewerOpen = true, albumOpen = true,
        )
        assertEquals(NavPolicy.BackAction.CANCEL_BATCH, NavPolicy.resolveBack(all, 4))
        assertTrue(NavPolicy.intercepts(all))
    }

    /** 防的 bug：有选中时按返回退掉了路由（本该只清选择，让用户接着看这一屏）。 */
    @Test
    fun selectionOutranksRoutePop() {
        val s = NavPolicy.Intercepts(hasSelection = true, viewerOpen = true)
        assertEquals(NavPolicy.BackAction.CLEAR_SELECTION, NavPolicy.resolveBack(s, 2))
    }

    /**
     * 防的 bug：一瞬 / 相机 / 查看器 / 相册内页的先后顺序被重排 ——
     * 改前它们互斥地覆盖同一块屏幕，所以"谁在屏幕上就先关谁"；
     * 现在按栈顶退，四个分支的语义合成为一条，但**顺序不能变**。
     */
    @Test
    fun anyOpenLayerPopsOneStep() {
        val cases = listOf(
            NavPolicy.Intercepts(yishunOpen = true),
            NavPolicy.Intercepts(cameraOpen = true),
            NavPolicy.Intercepts(viewerOpen = true),
            NavPolicy.Intercepts(albumOpen = true),
        )
        for (case in cases) {
            assertEquals(NavPolicy.BackAction.POP, NavPolicy.resolveBack(case, 1))
            assertTrue(NavPolicy.intercepts(case))
        }
    }

    /** 防的 bug：栈里明明还有层，`Intercepts` 却没标（返回键被交给系统，App 直接退出）。 */
    @Test
    fun stackDepthAloneJustifiesIntercept() {
        // 未来新增的层（还没接进 Intercepts）也要靠层数判 POP
        assertEquals(NavPolicy.BackAction.POP, NavPolicy.resolveBack(nothing, 1))
        // 但 intercepts() 只认显式那六项：新界面接入时要自己加一行（5 步模板里写着）
        assertEquals(false, NavPolicy.intercepts(nothing))
    }

    /** 防的 bug：短路顺序写错 → 有选中时批量在跑却被判成 CLEAR_SELECTION。 */
    @Test
    fun priorityOrderIsExplicitAndStable() {
        val state = NavPolicy.Intercepts(batchRunning = true, hasSelection = true)
        assertEquals(NavPolicy.BackAction.CANCEL_BATCH, NavPolicy.resolveBack(state, 3))
        val noBatch = state.copy(batchRunning = false)
        assertEquals(NavPolicy.BackAction.CLEAR_SELECTION, NavPolicy.resolveBack(noBatch, 3))
        assertEquals(NavPolicy.BackAction.POP, NavPolicy.resolveBack(noBatch.copy(hasSelection = false), 3))
        assertEquals(NavPolicy.BackAction.EXIT, NavPolicy.resolveBack(noBatch.copy(hasSelection = false), 0))
    }

    /** 防的 bug：切 Tab 不清栈（返回键还能退到"上一个 tab 的内页"）。 */
    @Test
    fun tabSwitchClearsLayers() {
        val s = BackStack.of(AppRoute.Gallery).push(AppRoute.AlbumDetail("b", "册"))
        val after = NavPolicy.resolveTab(s, AppRoute.Albums)
        assertEquals(0, after.depth)
        assertEquals(AppRoute.Albums, after.current)
    }

    /**
     * 二级页面（从页眉「更多」进的那一族）必须能被 BACK 退回。
     *
     * 防的 bug：`Intercepts` 只认原有的六项（批量/选择/一瞬/相机/查看器/相册内页），
     * 新页面**没有自己的布尔**，于是 `intercepts()` 返回 false、`BackHandler` 不拦，
     * 按一下返回直接退出 App —— 用户从「设置」按返回会退出整个应用。
     *
     * 这条同时钉住分工：`resolveBack` 靠 `stackDepth` 就能给出 POP（不需要认识每一种路由），
     * 而 `intercepts()` 需要调用方把"栈里有层"也算进去。见 `AppNavHost` 的 `hasLayer`。
     */
    @Test
    fun secondaryPagesCanBePoppedByBack() {
        val pages = listOf(
            AppRoute.Settings, AppRoute.SettingsAppearance, AppRoute.SettingsStorage,
            AppRoute.SettingsAbout, AppRoute.Privacy, AppRoute.Onboarding, AppRoute.Trash,
            AppRoute.Collections, AppRoute.CollectionDetail("c", "夹"), AppRoute.Memories,
            AppRoute.MemoryDetail("m"), AppRoute.Search(null), AppRoute.Collage(),
            AppRoute.Slideshow(0),
            // 分析工具与配方卡：同样必须能被 BACK 退回（它们没有自己的布尔字段，
            // 全靠 `Intercepts.hasLayer` 那一格 —— 漏了就是"按返回退出整个 App"）。
            AppRoute.Duplicates, AppRoute.StorageInsight,
            AppRoute.RecipeCard("content://a", "daylight", 0.5f),
            // 本轮新接的两样：备份页（设置的子页）与视频两屏。
            // 视频播放器尤其要在这里 —— 它在栈上只带一个 id，没有自己的布尔字段，
            // 而"播到一半按返回"是最常见的那一下；`hasLayer` 漏了就是整个 App 退出去。
            AppRoute.Backup,
            AppRoute.Videos,
            AppRoute.VideoPlayer(42L),
        )
        for (page in pages) {
            val s = BackStack.of(AppRoute.Gallery).push(page)
            // resolveBack：栈里有层 → POP
            assertEquals(
                "${page::class.simpleName} 按返回没有退栈",
                NavPolicy.BackAction.POP,
                NavPolicy.resolveBack(NavPolicy.Intercepts(), s.depth),
            )
            // 退完之后回到进来的那个 tab
            assertEquals(AppRoute.Gallery, s.pop().current)
        }
    }

    /**
     * 防的 bug：把二级页面当成 `Tab` 处理 —— `switchTab` 会清栈，
     * 于是"从设置里点底栏"会连设置一起关掉（那是预期），
     * 但**从页眉进设置再按返回**必须是回到原 tab，而不是被清成照片页。
     */
    @Test
    fun openingSettingsKeepsTheTabUnderneath() {
        val fromAlbums = BackStack.of(AppRoute.Albums).push(AppRoute.Settings)
        assertEquals(AppRoute.Albums, fromAlbums.pop().current)

        val fromSync = BackStack.of(AppRoute.Sync).push(AppRoute.SettingsAbout)
        assertEquals(AppRoute.Sync, fromSync.pop().current)

        // 设置页里再进子页：两层，逐层退
        val deep = BackStack.of(AppRoute.Gallery)
            .push(AppRoute.Settings)
            .push(AppRoute.SettingsStorage)
        assertEquals(AppRoute.Settings, deep.pop().current)
        assertEquals(AppRoute.Gallery, deep.pop().pop().current)
    }

    /**
     * 防的 bug：设置子页之间互相跳（外观 ↔ 存储）时把上一层挤掉，
     * 返回键一次就回到底座（用户"我明明是从外观页进来的"）。
     */
    @Test
    fun settingsSubPagesStackOnEachOther() {
        val s = BackStack.of(AppRoute.Gallery)
            .push(AppRoute.Settings)
            .push(AppRoute.SettingsAppearance)
            .push(AppRoute.SettingsStorage)
        assertEquals(3, s.depth)
        // 注意 `pop()` 是不可变的：想退两层要链式调（`s.pop().pop()`），
        // 两次 `s.pop()` 各自都只退一层 —— 第一版这条就是这么写错的。
        assertEquals(AppRoute.SettingsAppearance, s.pop().current)
        assertEquals(AppRoute.Settings, s.pop().pop().current)
    }

    /** 防的 bug：`resolvePush` 变成"总是入栈"，同 family 开始叠层。 */
    @Test
    fun pushPolicyKeepsTheDedupeRule() {
        val s = BackStack.of(AppRoute.Gallery).push(AppRoute.Yishun("content://a"))
        val after = NavPolicy.resolvePush(s, AppRoute.Yishun("content://b"))
        assertEquals(1, after.depth)
        assertEquals(AppRoute.Yishun("content://b"), after.current)
    }

    /**
     * 防的 bug：**同一个二级页面重复进入**时叠出两层（连点两下「设置」→ 按两次返回才退出）。
     * 这一族都是单例页面，`push` 的同 family 去重规则正好覆盖它们。
     */
    @Test
    fun reenteringTheSameSecondaryPageDoesNotStack() {
        val sameFamily = listOf(
            AppRoute.Settings,
            AppRoute.Trash,
            AppRoute.Collections,
            AppRoute.Memories,
            AppRoute.Search(null),
        )
        for (page in sameFamily) {
            val s = BackStack.of(AppRoute.Gallery).push(page).push(page)
            assertEquals(
                "${page::class.simpleName} 重复进入叠了两层",
                1,
                s.depth,
            )
        }
        // 有参数的那种：同一 family 换了个参数 = 替换栈顶（不是叠层）
        val a = AppRoute.MemoryDetail("m1")
        val b = AppRoute.MemoryDetail("m2")
        val s = BackStack.of(AppRoute.Gallery).push(a).push(b)
        assertEquals(1, s.depth)
        assertEquals(b, s.current)
    }

    /**
     * 防的 bug：拼图 / 幻灯片的照片列表没了，路由还留在栈上 ——
     * 用户看到一屏空拼图，而且返回键要按两次。宿主**不该 push**（列表为空时不进），
     * 这一条钉住"空列表时这两层也不会被渲染成主屏"。
     */
    @Test
    fun collageAndSlideshowDoNotBecomeTheBaseScreen() {
        // 它们只能作为 layers 存在；`NavRender` 把它们当"进去就能画"，
        // 但宿主保证列表为空时不 push。这里钉住"不会退化成一个空 tab"这件事。
        val s = BackStack.of(AppRoute.Gallery).push(AppRoute.Collage()).push(AppRoute.Slideshow(0))
        assertTrue(s.layers.none { it is AppRoute.Tab })
        assertEquals(2, s.depth)
        assertEquals(AppRoute.Collage(), s.pop().current)
    }

    /**
     * 防的 bug（这一条决定 `BackHandler(enabled=…)` 用哪个判据）：查看器里删掉最后一张之后
     * `viewerPhotos == null`，但**路由还在栈里**。这一下返回键必须仍然由 App 处理
     * 并把那个空查看器层退掉 —— 若 `enabled` 按"数据在不在"判，这一下会穿透到系统、
     * 直接把 Activity 结束（用户以为"删完一张就退出 App 了"）。
     *
     * 所以 `AppNavHost` 传的是 `stack.containsFamily("viewer")` 而不是 `viewerPhotos != null`，
     * 而 `NavRender` 才是按数据裁层的那个。两个判据分工不同，单测分别钉住。
     */
    @Test
    fun backIsInterceptedByRouteEvenWhenTheDataIsGone() {
        val s = BackStack.of(AppRoute.Gallery).push(AppRoute.Viewer(0))
        val routeOnly = NavPolicy.Intercepts(viewerOpen = s.containsFamily("viewer"))
        assertTrue(
            "路由还在就必须拦住返回键，否则这一下会把 Activity 结束掉",
            NavPolicy.intercepts(routeOnly),
        )
        assertEquals(NavPolicy.BackAction.POP, NavPolicy.resolveBack(routeOnly, s.depth))
        // 对照：如果六项全是 false 且**底座上**（depth=0），返回键才交给系统
        val nothing = NavPolicy.Intercepts()
        assertEquals(NavPolicy.BackAction.EXIT, NavPolicy.resolveBack(nothing, 0))
        // 而"六项全 false、但栈里还有层"这一格也要 POP —— 这是新界面接入时最容易忘的地方：
        // 忘了往 Intercepts 里加字段，`enabled` 会是 false（返回键漏给系统），
        // 但只要有人调了 resolveBack，它仍然知道该退栈。单测把两条岔路都写下来。
        assertEquals(NavPolicy.BackAction.POP, NavPolicy.resolveBack(nothing, s.depth))
    }

    /** 防的 bug：系统相机拍完回来时一瞬**没**盖住相机（相机还在上面，用户看不到刚拍的）。 */
    @Test
    fun systemCapturePutsYishunOnTop() {
        val s = NavPolicy.afterSystemCapture(BackStack.of(AppRoute.Gallery).push(AppRoute.Camera), "file:///sys.jpg")
        assertEquals(AppRoute.Yishun("file:///sys.jpg"), s.current)
        // 关掉一瞬回到相机（改前：一瞬关掉后 showCamera 仍是 true）
        assertEquals(AppRoute.Camera, s.pop().current)
    }
}
