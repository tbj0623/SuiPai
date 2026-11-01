package com.suipai.app.state

/**
 * 全应用的一个目的地（路由）。
 *
 * 为什么不用 navigation-compose 的字符串路由：这一层的每条规则（同路由去重、Tab 切换清栈、
 * 保存恢复、返回键优先级）都必须能被 JVM 单测钉住，而不是只在真机上量得到。所以路由本身是
 * 纯 Kotlin 的 sealed interface，编码成 String（Bundle 只存得下 List<String>，
 * 不用 Parcelable —— 见 `docs/arch/APP-ARCH.md` 的状态所有权表）。
 *
 * 编码表（[encode] 的输出，[decode] 必须逐条对得上）：
 *
 * | 编码 | 目的地 |
 * | --- | --- |
 * | `tab:0` / `tab:1` / `tab:2` | 一级页：照片 / 相册 / 互通 |
 * | `album:<id>:<标题>` | 相册内页（两段都做 % 转义，标题可空） |
 * | `viewer:<页号>` | 大图查看器（页号只是初始页，滑动不写回路由） |
 * | `camera` | 内置相机 |
 * | `yishun:<uri>` | 一瞬（uri 是 String，转义后不含冒号） |
 *
 * 新增一个界面的接入方式见 `docs/arch/APP-ARCH.md` 的「新屏幕接入 5 步」：
 * 1. 在这里加一个 `data object` / `data class` 实现；[family] 决定"同类只留一份"。
 * 2. 在 [decode] 里补一行（漏了就是"重启后回到首页"，而且不会有任何报错）。
 * 3. 在 `AppNavHost` 的 `when` 里加一个分支。
 * 4. 要让返回键拦住它，就在 `NavPolicy` 的优先级表里排位置。
 * 5. 补一条编码往返单测（`AppRouteTest`）。
 */
sealed interface AppRoute {

    /**
     * "同一类目的地"的标识。返回栈的**同路由去重策略**按它判：
     * 栈顶 family 相同就替换栈顶而不长高（两个查看器页号不同仍是"同一个查看器"），
     * family 不同才真的入栈。Tab 是底座，不属于"入栈"那一族。
     */
    val family: String

    /** 编成一行 String，进 Bundle 用。必须与 [decode] 严格互逆。 */
    fun encode(): String

    /**
     * 一级页（三个 tab）。返回栈的**底座**永远是它，且栈里只许有一个。
     * 未来的「设置中心」「回收站」这类从页眉进入的页属于普通路由（上层），不是 Tab。
     */
    sealed interface Tab : AppRoute {
        /** 底栏 / 矮屏页眉那一行的下标。 */
        val index: Int
    }

    /** 照片时间线（tab 0）。 */
    data object Gallery : Tab {
        override val family: String get() = "tab"
        override val index: Int get() = 0
        override fun encode(): String = "tab:0"
    }

    /** 相册清单（tab 1）。 */
    data object Albums : Tab {
        override val family: String get() = "tab"
        override val index: Int get() = 1
        override fun encode(): String = "tab:1"
    }

    /** 双端互通（tab 2）。 */
    data object Sync : Tab {
        override val family: String get() = "tab"
        override val index: Int get() = 2
        override fun encode(): String = "tab:2"
    }

    /**
     * 相册内页。`albumId` 既可能是 MediaStore 的 bucketId，也可能是
     * `Favorites.ALBUM_ID` / `Hidden.ALBUM_ID` 这两个虚拟册。
     *
     * 标题一起编码：原来 `albumId` 与 `albumTitle` 是两份独立状态，改一处忘一处就会
     * "页名还在、册子已经不在了"。路由里两个字段同一个生命周期，分不开。
     */
    data class AlbumDetail(val albumId: String, val albumTitle: String? = null) : AppRoute {
        override val family: String get() = "album"
        override fun encode(): String =
            "album:" + RouteCodec.escape(albumId) + ":" + RouteCodec.escape(albumTitle.orEmpty())

        companion object {
            /** 编码里漏了 `album:` 前缀或段数不对都返回 null（宁可不恢复，也不猜）。 */
            fun decode(encoded: String): AlbumDetail? {
                val parts = encoded.split(':')
                // album : id : title —— 字段本身已经转义，所以冒号只可能是分隔符
                if (parts.size != 3 || parts[0] != "album") return null
                val id = RouteCodec.unescape(parts[1])
                if (id.isEmpty()) return null
                val title = RouteCodec.unescape(parts[2]).takeIf { it.isNotEmpty() }
                return AlbumDetail(id, title)
            }
        }
    }

    /**
     * 大图查看器。`index` 是**打开时的初始页**；用户在里面左右滑不写回路由
     * （查看器自己用 `rememberSaveable` 记住当前页，翻页不制造重组风暴，也不产生返回栈记录）。
     *
     * 真正被查看的 `List<PhotoItem>` 不进路由：那是带 Uri 的重数据，Bundle 存不下，
     * 由 `AppState.viewerPhotos` 持有（见状态所有权表）。所以进程被杀之后
     * 这个路由还在、照片没了 —— `AppNavHost` 会退回底座那屏，与改前一致。
     */
    data class Viewer(val index: Int = 0) : AppRoute {
        override val family: String get() = "viewer"
        override fun encode(): String = "viewer:" + index.coerceAtLeast(0)

        companion object {
            fun decode(encoded: String): Viewer? {
                if (!encoded.startsWith("viewer:")) return null
                val n = encoded.removePrefix("viewer:").toIntOrNull() ?: return null
                return Viewer(n.coerceAtLeast(0))
            }
        }
    }

    /** 内置相机（CameraX）。 */
    data object Camera : AppRoute {
        override val family: String get() = "camera"
        override fun encode(): String = "camera"
    }

    /**
     * 一瞬。uri 从 [android.net.Uri.toString] 来，回程用 `Uri.parse` 还原。
     *
     * 为什么它必须进路由：一瞬可以叠在相机上面（系统相机拍完回来）、也可以叠在查看器上面
     * （从大图进一瞬）；返回键的先后顺序就是这个栈的先后顺序。
     */
    data class Yishun(val uri: String) : AppRoute {
        override val family: String get() = "yishun"
        override fun encode(): String = "yishun:" + RouteCodec.escape(uri)

        companion object {
            fun decode(encoded: String): Yishun? {
                if (!encoded.startsWith("yishun:")) return null
                val uri = RouteCodec.unescape(encoded.removePrefix("yishun:"))
                if (uri.isEmpty()) return null
                return Yishun(uri)
            }
        }
    }

    // ══ 二级页面（从页眉「更多」/ 设置里进，不是 tab）═════════════════════
    //
    // 这一族**全部是无参数的 data object**（`CollectionDetail` / `MemoryDetail` /
    // `Search` / `Collage` / `Slideshow` 带参数）。判断标准就一条：**它是不是"同一个页面
    // 换了一份数据"**。设置的四页、隐私、引导、回收站、收藏夹、回忆列表、搜索页都是
    // "进去就好"，彼此互斥地占满整屏 —— 它们不叠层，所以用 data object 最省。
    //
    // 它们都是从页眉进入的**一级入口的二级页**（不是 tab）：底栏在它们上面不可见，
    // 返回键退一层就回到进来的那个 tab。接入方式见 docs/arch/APP-ARCH.md §六。

    /** 设置中心（总目录）。 */
    data object Settings : AppRoute {
        override val family: String get() = "settings"
        override fun encode(): String = "settings"
    }

    /** 设置 › 外观。 */
    data object SettingsAppearance : AppRoute {
        override val family: String get() = "settings.appearance"
        override fun encode(): String = "settings.appearance"
    }

    /** 设置 › 存储。 */
    data object SettingsStorage : AppRoute {
        override val family: String get() = "settings.storage"
        override fun encode(): String = "settings.storage"
    }

    /** 设置 › 关于。 */
    data object SettingsAbout : AppRoute {
        override val family: String get() = "settings.about"
        override fun encode(): String = "settings.about"
    }

    /**
     * 视频列表。
     *
     * 与「重复照片」「存储分析」同一族：从页眉「更多 › 看照片」进的二级页，
     * 进去就画（数据由宿主用 `VideoRepository.loadPage` 取好传进去），所以无参数。
     *
     * 为什么**没有**一个"视频 tab"：视频在相册里是少数（一个 5000 张的库通常只有几十段），
     * 给它占掉底栏三分之一是把照片挤走的代价，而用户找视频的频率远低于找照片。
     */
    data object Videos : AppRoute {
        override val family: String get() = "videos"
        override fun encode(): String = "videos"
    }

    /**
     * 某一段视频的播放器。
     *
     * 路由里只带 `id`（MediaStore 的行号），**不带 `uri`**：`VideoItem` 有九个字段、
     * 其中一个是 `Uri`，整套塞进 Bundle 是"把重数据放路由"那种错（同 [Viewer] 的理由）。
     * 宿主拿 id 回列表里找那一条；找不到（删了 / 换机重排）就退回列表并说一句 ——
     * 那条由 `AppNavHost` 负责，路由这一层只保证"编码可逆"。
     *
     * `id` 是 `Long` 且恒为正（MediaStore 的行号），所以编码不需要 [RouteCodec]：
     * 十进制数字里不可能出现 `:`。解不出来（非数字 / 0 / 负数）返回 null 让恢复流程丢掉这一层。
     */
    data class VideoPlayer(val id: Long) : AppRoute {
        override val family: String get() = "video"
        override fun encode(): String = "video:" + id.coerceAtLeast(0L)

        companion object {
            fun decode(encoded: String): VideoPlayer? {
                if (!encoded.startsWith("video:")) return null
                val id = encoded.removePrefix("video:").toLongOrNull() ?: return null
                // 0 与负数是坏数据（MediaStore 的行号从 1 起）：不猜一个 id，直接判废。
                if (id <= 0L) return null
                return VideoPlayer(id)
            }
        }
    }

    /**
     * 设置 › 导出与恢复（备份文件进出）。
     *
     * 与其它设置子页同一个形状：无参数、`settings.` 前缀、family 自己一档
     * （撞上 `"settings"` 会让 `push` 把设置总目录顶掉）。
     *
     * 为什么**没有参数**：这一屏的数据（本机条数、上次导出时刻）都是从
     * `Backup.collect()` 与 prefs 现读的，进不进得来不取决于路由带什么；
     * 用户选的合并方式（REPLACE / UNION）是**屏幕内的一次性输入**，
     * 不进 Bundle —— 进程被杀之后重新选一次，比"恢复一个上次选的 REPLACE"安全得多。
     */
    data object Backup : AppRoute {
        override val family: String get() = "settings.backup"
        override fun encode(): String = "settings.backup"
    }

    /** 隐私与数据（数据去向 / 权限逐项 / 导出）。 */
    data object Privacy : AppRoute {
        override val family: String get() = "privacy"
        override fun encode(): String = "privacy"
    }

    /** 首启引导（三屏）。只在 `OnboardingGate.shouldShow()` 为真时进。 */
    data object Onboarding : AppRoute {
        override val family: String get() = "onboarding"
        override fun encode(): String = "onboarding"
    }

    /** 回收站（30 天候车室）。 */
    data object Trash : AppRoute {
        override val family: String get() = "trash"
        override fun encode(): String = "trash"
    }

    /** 收藏夹清单。 */
    data object Collections : AppRoute {
        override val family: String get() = "collections"
        override fun encode(): String = "collections"
    }

    /**
     * 某个收藏夹的内容。`name` 一起编码的理由与 [AlbumDetail] 相同：
     * 册名与 id 是同一条数据的两个字段，分开存就会出现"名字还在、册子没了"。
     */
    data class CollectionDetail(val id: String, val name: String? = null) : AppRoute {
        override val family: String get() = "collection"
        override fun encode(): String =
            "collection:" + RouteCodec.escape(id) + ":" + RouteCodec.escape(name.orEmpty())

        companion object {
            fun decode(encoded: String): CollectionDetail? {
                val parts = encoded.split(':')
                if (parts.size != 3 || parts[0] != "collection") return null
                val id = RouteCodec.unescape(parts[1])
                if (id.isEmpty()) return null
                return CollectionDetail(id, RouteCodec.unescape(parts[2]).takeIf { it.isNotEmpty() })
            }
        }
    }

    /** 回忆清单。 */
    data object Memories : AppRoute {
        override val family: String get() = "memories"
        override fun encode(): String = "memories"
    }

    /**
     * 某条回忆的详情。`id` 是 `Memory.id`（由 Memories.build 生成的稳定串），
     * 详情页要的照片列表由宿主按 id 重新筛 —— 列表不进路由（理由同 [Viewer]）。
     */
    data class MemoryDetail(val id: String) : AppRoute {
        override val family: String get() = "memory"
        override fun encode(): String = "memory:" + RouteCodec.escape(id)

        companion object {
            fun decode(encoded: String): MemoryDetail? {
                if (!encoded.startsWith("memory:")) return null
                val id = RouteCodec.unescape(encoded.removePrefix("memory:"))
                if (id.isEmpty()) return null
                return MemoryDetail(id)
            }
        }
    }

    /**
     * 搜索页。`query` 是**进来时**带的初始词（从筛选格/历史点进来时用），
     * 之后的输入不写回路由 —— 每敲一个字都改路由会让返回栈每帧失效一次。
     */
    data class Search(val query: String? = null) : AppRoute {
        override val family: String get() = "search"
        override fun encode(): String =
            if (query.isNullOrEmpty()) "search" else "search:" + RouteCodec.escape(query)

        companion object {
            /** 空串按"没带词"处理：`search:` 与 `search` 是同一个目的地。 */
            fun decodeQuery(raw: String): String? =
                RouteCodec.unescape(raw).takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 拼图。
     *
     * `photoIds` 只存**当时选中的那几张**；超过 [MAX_IDS] 就不再往里塞，
     * 只留 `count`，进页面时由宿主按"当前可见集合"重新取前 `count` 张。
     * 为什么：Bundle 里一串几 KB 的数字没有意义（进程被杀后照片列表本来就要重读），
     * 而"取前 N 张"与"选中 N 张"在拼图这一屏的语义上等价（用户看到的就是 N 格）。
     */
    data class Collage(val photoIds: List<Long> = emptyList(), val count: Int = photoIds.size) : AppRoute {
        override val family: String get() = "collage"

        override fun encode(): String {
            // 超长时只编 count（`collage:~12`），解码回空 ids —— 宿主按 count 重新取。
            if (photoIds.size > MAX_IDS) return "collage:~" + count
            return "collage:" + photoIds.joinToString(",")
        }

        companion object {
            /** 最多**存**这么多个 id：一屏拼图最多 9 格，给到 24 已经很宽松。 */
            const val MAX_IDS = 24

            /**
             * `count` 的上界与 [MAX_IDS] **不是同一个数**，这一点容易写错：
             * ids 列表有 "最多存 24 个" 的限制，但 `count` 表示"当时选中了多少张"，
             * 它天然大于 24（选中 100 张来拼图时就是 100）。
             * 夹 `count` 用的是"一个人可能选多少张"的合理上限，不是 MAX_IDS ——
             * 第一版写成 `coerceIn(0, MAX_IDS)`，于是"选了 100 张"被静默记成 24 张，
             * 进拼图只排 24 格而用户选的是 100 张（单测当场判红）。
             *
             * 5000 = `Collections.MAX_PHOTOS` 那一档；再大就说明 Bundle 被人改过，
             * 按上限夹回去而不是照单全收。
             */
            const val MAX_COUNT = 5000

            fun decode(encoded: String): Collage? {
                if (!encoded.startsWith("collage:")) return null
                val body = encoded.removePrefix("collage:")
                // 空 body = "没带选择"（从页眉「拼图」直接进），合法。
                if (body.isEmpty()) return Collage()
                if (body.startsWith("~")) {
                    val n = body.removePrefix("~").toIntOrNull() ?: return null
                    return Collage(emptyList(), n.coerceIn(0, MAX_COUNT))
                }
                val parts = body.split(',')
                val ids = parts.mapNotNull { it.toLongOrNull() }
                // 有一个解析不出来就整串作废（返回 null，让恢复流程丢掉这一层）。
                // **不许**用 `mapNotNull` 静默吞掉坏项：`collage:abc` 会被吞成"空拼图"——
                // 用户点进来看到的是"一张都没选"，而实际是 Bundle 坏了；
                // 单测 `collageDecodeClampsAndRejectsGarbage` 钉的就是这一条。
                if (ids.size != parts.size) return null
                return Collage(ids)
            }
        }
    }

    /**
     * 幻灯片。**照片列表不进路由**（理由同 [Viewer]）：宿主用"当前可见集合"传进去，
     * 路由只记起始页。重建后列表没了就退回列表页 —— 与查看器同一条规则。
     */
    data class Slideshow(val startIndex: Int = 0) : AppRoute {
        override val family: String get() = "slideshow"
        override fun encode(): String = "slideshow:" + startIndex.coerceAtLeast(0)

        companion object {
            fun decode(encoded: String): Slideshow? {
                if (!encoded.startsWith("slideshow:")) return null
                val n = encoded.removePrefix("slideshow:").toIntOrNull() ?: return null
                return Slideshow(n.coerceAtLeast(0))
            }
        }
    }

    /** 重复照片：扫描全库、按组给"留哪张"的建议，删掉其余。 */
    data object Duplicates : AppRoute {
        override val family: String get() = "duplicates"
        override fun encode(): String = "duplicates"
    }

    /**
     * 存储分析：按尺寸档 / 体量直方图 / 最大若干张 / 可回收建议。
     *
     * family 用 `"storage.insight"` 而不是 `"settings.storage"`：后者已经是
     * 「设置 › 存储」那一屏（清缓存的那个）。两者是**不同的页面**，
     * family 撞了 `push` 会把设置存储页顶掉。
     */
    data object StorageInsight : AppRoute {
        override val family: String get() = "storage.insight"
        override fun encode(): String = "storage.insight"
    }

    /**
     * 配方卡（把某个配方 + 强度画成一张可分享的图）。
     *
     * 三个参数都必须进路由：`uri` 是被调色的那张原图，`lookId` 是配方，`intensity` 是强度。
     * **不能只带 `lookId`**：从一瞬顶栏进来时那一屏的强度是用户刚拖的滑块值，
     * 重建之后重新读 prefs 会得到"上次保存的强度"，与用户看到的配方卡不是同一张。
     *
     * `intensity` 是 `Float`，而路由编码只处理 `String`：
     * 编的时候 `toString()`、解的时 `toFloatOrNull()`，**解不出来返回 null**
     * （与 `collage:abc` 同一个口径：宁可丢掉这一层，也不猜一个默认强度画一张假卡）。
     * `uri` 走 [RouteCodec.escape]（它含 `:` 与 `%`）。
     */
    data class RecipeCard(
        val uri: String,
        val lookId: String,
        val intensity: Float,
    ) : AppRoute {
        override val family: String get() = "recipeCard"

        override fun encode(): String =
            "recipeCard:" + RouteCodec.escape(uri) + ":" +
                RouteCodec.escape(lookId) + ":" + intensity

        companion object {
            /**
             * 把 [encode] 的输出解回路由。
             *
             * **只接受 `encode()` 转义过的形式**：本路由用 `:` 分段，而 uri 里天然带
             * `://`（甚至查询串里还能再来几个冒号）。裸串喂进来时冒号会把字段切多，
             * 于是这里返回 null（丢掉这一层）—— 那是**格式不对**，不是内容不对。
             * 不要为了"让裸串也能解"去猜哪个冒号是分隔符：那个歧义解不干净
             * （`a:b:c` 到底是 1 段还是 2 段没有答案）。
             * `AppRouteTest.recipeCardRejectsUnescapedRawString` 把这条口径钉住了。
             */
            fun decode(encoded: String): RecipeCard? {
                if (!encoded.startsWith("recipeCard:")) return null
                val parts = encoded.split(':')
                if (parts.size != 4 || parts[0] != "recipeCard") return null
                val uri = RouteCodec.unescape(parts[1])
                val lookId = RouteCodec.unescape(parts[2])
                if (uri.isEmpty() || lookId.isEmpty()) return null
                val intensity = parts[3].toFloatOrNull() ?: return null
                if (intensity.isNaN() || intensity.isInfinite()) return null
                return RecipeCard(uri, lookId, intensity.coerceIn(0f, 1f))
            }
        }
    }

    companion object {
        /** 三个一级页，下标与底栏 / 矮屏页眉那一行一一对应。 */
        val Tabs: List<Tab> = listOf(Gallery, Albums, Sync)
        /** 底栏与矮屏页眉共用的名字（只留一份，改名不会只改到一半）。 */
        val TabTitles: List<String> = listOf("照片", "相册", "互通")

        /** 越界或负数一律回到底座那一页，不抛异常（Bundle 里的东西不许让应用起不来）。 */
        fun tab(index: Int): Tab = Tabs.getOrElse(index) { Gallery }

        /**
         * 把一行编码解回路由。任何不认识的东西返回 null ——
         * `BackStack.restore` 会把它丢掉，而不是让恢复流程崩在 `when` 上。
         */
        fun decode(encoded: String): AppRoute? = when {
            encoded.startsWith("tab:") -> {
                val n = encoded.removePrefix("tab:").toIntOrNull()
                if (n == null) null else tab(n)
            }
            encoded.startsWith("album:") -> AlbumDetail.decode(encoded)
            encoded.startsWith("viewer:") -> Viewer.decode(encoded)
            encoded == "camera" -> Camera
            encoded.startsWith("yishun:") -> Yishun.decode(encoded)
            // ── 二级页面（从页眉「更多」或设置里进） ──────────────────
            encoded == "settings" -> Settings
            encoded == "settings.appearance" -> SettingsAppearance
            encoded == "settings.storage" -> SettingsStorage
            encoded == "settings.about" -> SettingsAbout
            encoded == "settings.backup" -> Backup
            encoded == "privacy" -> Privacy
            encoded == "onboarding" -> Onboarding
            encoded == "trash" -> Trash
            encoded == "collections" -> Collections
            encoded.startsWith("collection:") -> CollectionDetail.decode(encoded)
            encoded == "memories" -> Memories
            encoded.startsWith("memory:") -> MemoryDetail.decode(encoded)
            encoded == "search" -> Search(null)
            encoded.startsWith("search:") -> Search(Search.decodeQuery(encoded.removePrefix("search:")))
            encoded == "collage" -> Collage(emptyList())
            encoded.startsWith("collage:") -> Collage.decode(encoded)
            encoded.startsWith("slideshow:") -> Slideshow.decode(encoded)
            encoded == "duplicates" -> Duplicates
            encoded == "storage.insight" -> StorageInsight
            encoded == "videos" -> Videos
            encoded.startsWith("video:") -> VideoPlayer.decode(encoded)
            encoded.startsWith("recipeCard:") -> RecipeCard.decode(encoded)
            else -> null
        }
    }
}

/**
 * 路由字段的转义。只做四件事：`%`、`:`、换行、回车。
 *
 * 不做成 URLEncoder 的理由：那是"编码整段"，回来还得处理 `+`/空格；
 * 这里只需要保证**分隔符不会出现在字段里**，四行就够了，且完全可逆。
 * 顺序是承重的：编码先换 `%`（否则会把后面换出来的 `%3A` 再编码一遍），
 * 解码最后换 `%25`（同理）。
 */
internal object RouteCodec {

    fun escape(raw: String): String = raw
        .replace("%", "%25")
        .replace(":", "%3A")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

    fun unescape(raw: String): String = raw
        .replace("%0D", "\r")
        .replace("%0A", "\n")
        .replace("%3A", ":")
        .replace("%25", "%")
}
