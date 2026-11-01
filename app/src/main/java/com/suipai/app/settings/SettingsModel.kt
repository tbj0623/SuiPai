package com.suipai.app.settings

/**
 * 设置中心的数据模型：八个分组，每组若干 [SettingItem]。**纯 Kotlin、可单测**。
 *
 * 为什么把"有哪些设置"做成数据而不是直接写成 Compose：这一层要被四件事同时消费
 * —— 分组列表、搜索、单测（完整性）、`docs/product/SETTINGS.md`（口径表）。
 * 写成数据之后，加一条设置必须先过 `SettingsModelTest` 的四条不变量，
 * 而不是"记得在三个地方各写一遍"。
 *
 * 这个文件不 import 任何 android / compose 符号。这是刻意的：
 * `:app:testDebugUnitTest` 里 android.jar 全是桩，粘上 `Context` 就测不动了。
 */

/**
 * 一条设置。
 *
 * 五种子类对应任务里说的五种形态：Toggle / Choice / Action / ReadOnly / Info。
 * 界面按类型分派渲染；单测按类型做各自的完整性检查
 * （例如「每个 Choice 的默认值必须在选项集合里」）。
 */
sealed interface SettingItem {

    /** 稳定标识：搜索、单测、埋点、`key` 的兜底都用它。全局唯一。 */
    val key: String

    /** 这一条属于哪个分组。 */
    val group: SettingsGroup

    /** 列表上显示的那一行字。同一分组内不允许重复（与 `FlagTableGuardTest` 同一条纪律）。 */
    val title: String

    /** 副标题：说清"这一项到底改什么"，也是读屏念完名字之后的那半句。 */
    val note: String

    /** 搜得到的关键词（除 title 之外）。中文用户会搜"夜间""深色""暗"，不一定会搜"暗房"。 */
    val keywords: List<String> get() = emptyList()

    /**
     * 这一条今天是否真的接上了读者。见 [SettingLive]：
     * 界面把 [SettingLive.PENDING] 的那几行灰掉并写一句说明 —— 不许假装生效。
     */
    val live: SettingLive get() = SettingLive.PENDING

    /** 布尔开关。 */
    data class Toggle(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        override val note: String,
        /** 落盘键；`null` 表示这一条只是本屏的状态（还没有键）。 */
        val pref: SettingKey.Bool,
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.PENDING,
    ) : SettingItem

    /**
     * 单选（枚举档位）。
     *
     * [options] 与 [pref] 的 `options` 是同一张表 —— 这里再留一份是为了让"界面上看得见哪几档"
     * 与"落盘认哪几档"由同一个地方决定（[SettingKey.Choice.options]），单测会逐项对账。
     */
    data class Choice<E : Enum<E>>(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        override val note: String,
        val pref: SettingKey.Choice<E>,
        val options: List<E> = pref.options,
        val labelOf: (E) -> String,
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.PENDING,
    ) : SettingItem

    /** 整数档位（画质、强度这类）。[step] 给滑杆或循环格用。 */
    data class Number(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        override val note: String,
        val pref: SettingKey.Int,
        val step: Int = 1,
        val unit: String = "",
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.PENDING,
    ) : SettingItem

    /** 动作：点它去做一件事（进子页、清缓存、跳系统设置）。 */
    data class Action(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        override val note: String,
        val action: SettingAction,
        /** 危险动作（清除数据）。界面用定影红，并且要点两次确认。 */
        val danger: Boolean = false,
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.LIVE,
    ) : SettingItem

    /** 只读值：运行时真值，点了没动作（例如版本号、占用多少）。 */
    data class ReadOnly(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        override val note: String,
        val source: ReadOnlySource,
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.LIVE,
    ) : SettingItem

    /** 纯说明：一段正文，不带任何控件（隐私页的"数据去哪了"就用它）。 */
    data class Info(
        override val key: String,
        override val group: SettingsGroup,
        override val title: String,
        /** 说明正文。可以有多段，逐段渲染。 */
        val paragraphs: List<String>,
        override val keywords: List<String> = emptyList(),
        override val live: SettingLive = SettingLive.LIVE,
    ) : SettingItem {
        override val note: String get() = paragraphs.firstOrNull().orEmpty()
    }
}

/**
 * 设置页要发出去的一次改动。
 *
 * 为什么不是三个泛型回调：Kotlin 的函数类型**不能带自己的类型参数**
 * （`onChoice: <E : Enum<E>>(SettingKey.Choice<E>, E) -> Unit` 是语法错误），
 * 而设置列表里混着五种类型的档位、遍历时只拿得到 `SettingItem.Choice<*>` 这个星投影。
 * 于是把"改什么"收成一个值对象：界面只管**描述**改动，谁去落盘由接线方决定
 * （生产路径是 `SettingsStore`，单测路径可以是一个 `MutableList` 收集器）。
 */
sealed interface SettingChange {

    /** 翻转一个布尔开关。 */
    data class Toggle(val pref: SettingKey.Bool, val value: Boolean) : SettingChange

    /**
     * 选一个枚举档位。按 **name** 传，不是按枚举实例：
     * 星投影下拿不到具体的 E，而落盘本来就存 name（`SettingsStore.putChoiceName` 会校验它确实是一档）。
     */
    data class Choice(val pref: SettingKey.Choice<*>, val optionName: String) : SettingChange

    /** 改一个整数档位。 */
    data class Number(val pref: SettingKey.Int, val value: Int) : SettingChange

    /** 触发一个动作。 */
    data class Action(val action: SettingAction) : SettingChange
}

/**
 * 全部设置项，按界面顺序。**这是设置中心的唯一目录**。
 *
 * 加一条的顺序：`SettingsKeys` 里加键 → 这里加一条 `SettingItem` → 需要就补一句文案。
 * `SettingsModelTest` 会核对：
 * 1. [key] 全局唯一；
 * 2. 每个分组非空；
 * 3. 每个 Choice 的默认值在选项集合里；
 * 4. 每个分组的 [SettingsGroup.title] 不重复（这一条其实由枚举保证，但单测照样钉一遍，
 *    因为以后换个来源就未必了）。
 */
object SettingsModel {

    /**
     * 生成目录。
     *
     * 为什么是函数而不是 `val`：以后可能要按版本/机型隐藏若干项（低端机不给「清晰」缩略图档），
     * 那样只有这一处要改。今天它不读任何参数，等价于常量，单测可以随便调。
     */
    fun items(): List<SettingItem> = buildList {

        // ── 通用 ──────────────────────────────────────────────────────
        add(
            SettingItem.Action(
                key = "general_appearance",
                group = SettingsGroup.GENERAL,
                title = "外观",
                note = "主题、字号、网格与缩略图",
                action = SettingAction.OPEN_APPEARANCE,
            )
        )
        add(
            SettingItem.Action(
                key = "general_replay_onboarding",
                group = SettingsGroup.GENERAL,
                title = "重看首次引导",
                note = "三屏，讲清相册、一瞬与照片去向",
                action = SettingAction.REPLAY_ONBOARDING,
                keywords = listOf("引导", "新手", "教程", "入门"),
            )
        )
        add(
            SettingItem.Choice(
                key = "general_start_tab",
                group = SettingsGroup.GENERAL,
                title = "启动页",
                note = "打开随拍时先落在哪一页（缺席时要重建任务才会生效）",
                pref = SettingsKeys.General.startTab,
                labelOf = { it.label },
                keywords = listOf("首页", "启动", "默认页"),
                // 读它是 `NavState` 的初值，而 `NavState` 住在 `state/**`（不在本 lane 的写作用域）。
                // 本 lane 只能把**值**写对，不能让已经在跑的会话换页 —— 所以要重建任务。
                live = SettingLive.PREVIEW,
            )
        )
        add(
            SettingItem.Toggle(
                key = "general_confirm_delete",
                group = SettingsGroup.GENERAL,
                title = "删除前确认",
                note = "批量删除前再问一次；关掉之后删了就没有了",
                pref = SettingsKeys.General.confirmDelete,
                keywords = listOf("删除", "确认", "二次确认"),
                // 读者：`ui/gallery/GalleryScreen.kt` 的批量工具条。关掉之后点「删除」直接删，
                // 不再先变成「确认删除」等第二下。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "general_camera_shortcut",
                group = SettingsGroup.GENERAL,
                title = "桌面拍照快捷方式",
                note = "长按图标的菜单里显示「拍照」",
                pref = SettingsKeys.General.cameraShortcut,
                keywords = listOf("快捷方式", "桌面", "图标"),
                // 仍是 PENDING：桌面菜单由 `widget/DynamicShortcuts.kt` 在启动时统一重发，
                // 它列的是**固定三条**（拍照 / 互通 / 一瞬），还没有任何地方读这一项。
                // 缺的是：`DynamicShortcuts.refresh` 读它并决定"拍照"那一条在不在，
                // 以及在设置页拨完之后主动叫一声刷新。那条链在 `widget/`，不在本 lane。
                live = SettingLive.PENDING,
            )
        )

        // ── 外观 ──────────────────────────────────────────────────────
        //
        // 这五项的读者都在这一轮接上了（`ui/theme/Theme.kt` + `ui/gallery/GalleryScreen.kt`
        // + `ui/design/Motion.kt`）。为什么能标 LIVE：拨完**下一次进入对应界面**就看得到变化，
        // 而且主题与字号连"下一次"都不用等 —— `Theme.kt` 订阅了 `SettingsSignal`，
        // 在同一帧里就地换皮。
        add(
            SettingItem.Choice(
                key = "appearance_theme",
                group = SettingsGroup.APPEARANCE,
                title = "主题",
                note = "暗房是默认；日光印样是纸白那套",
                pref = SettingsKeys.Appearance.themeMode,
                labelOf = { it.label },
                keywords = listOf("主题", "深色", "浅色", "暗色", "夜间", "暗房", "日光"),
                // 读者：`ui/theme/Theme.kt` 的 `SuiPaiTheme`，整屏立刻换。
                // 「跟随系统」今天等价于"沿用页眉那颗 DARKROOM/DAYLIGHT 开关的当前状态"，
                // 真正跟随系统要等 `ChromeState.darkUi` 改成从这一项派生（见 SETTINGS.md §3）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Choice(
                key = "appearance_font_scale",
                group = SettingsGroup.APPEARANCE,
                title = "字号",
                note = "在系统字号之上再调一档",
                pref = SettingsKeys.Appearance.fontScale,
                labelOf = { it.label },
                keywords = listOf("字号", "字体", "大小", "字级"),
                // 读者：`ui/theme/Theme.kt` 把 `percent` 乘进 `LocalDensity.fontScale`（叠在系统之上）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Choice(
                key = "appearance_grid_columns",
                group = SettingsGroup.APPEARANCE,
                title = "照片网格",
                note = "一格多宽；数字越小一屏塞得越多",
                pref = SettingsKeys.Appearance.gridColumns,
                labelOf = { it.label },
                keywords = listOf("网格", "列数", "密度", "缩略图大小"),
                // 读者：`GalleryScreen.kt` 的 `cellDp` 初值（`GridCells.Adaptive`）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Choice(
                key = "appearance_thumb_quality",
                group = SettingsGroup.APPEARANCE,
                title = "缩略图质量",
                note = "清晰更费内存；省电在低端机上更稳",
                pref = SettingsKeys.Appearance.thumbQuality,
                labelOf = { it.label },
                keywords = listOf("缩略图", "清晰度", "画质", "内存"),
                // 读者：`GalleryScreen.kt` 的 `ImageRequest.size` / `FastThumb(edgePx)`。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "appearance_reduce_motion",
                group = SettingsGroup.APPEARANCE,
                title = "减弱动效",
                note = "关掉淡入淡出与按压动画",
                pref = SettingsKeys.Appearance.reduceMotion,
                keywords = listOf("动效", "动画", "减弱", "无障碍"),
                // 读者：`ui/design/Motion.kt` 的 `rememberMotionFactor()`；
                // 今天唯一跨屏可见的动画（相册那条位置横带）已经按它取时长。
                live = SettingLive.LIVE,
            )
        )

        // ── 相册 ──────────────────────────────────────────────────────
        add(
            SettingItem.Choice(
                key = "gallery_default_sort",
                group = SettingsGroup.GALLERY,
                title = "默认排序",
                note = "时间线打开时按这个排",
                pref = SettingsKeys.Gallery.defaultSort,
                labelOf = { it.label },
                keywords = listOf("排序", "顺序", "最新", "最早", "文件名"),
                // 读者：`GalleryScreen.kt` 的 `sortMode` / `newestFirst` 初值。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Choice(
                key = "gallery_group_by",
                group = SettingsGroup.GALLERY,
                title = "分组",
                note = "时间线怎么断行：按日、按月、按年",
                pref = SettingsKeys.Gallery.groupBy,
                labelOf = { it.label },
                keywords = listOf("分组", "按日", "按月", "按年", "日期"),
                // 读者：`GalleryScreen.kt` 的 `groupMode` 初值。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "gallery_show_hidden",
                group = SettingsGroup.GALLERY,
                title = "显示已隐藏",
                note = "打开之后被藏起来的照片会回到时间线",
                pref = SettingsKeys.Gallery.showHidden,
                keywords = listOf("隐藏", "显示", "藏起来"),
                // 读者：`GalleryScreen.kt` 的 `showHiddenEffective`（与「已隐藏」入口取或）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "gallery_section_index",
                group = SettingsGroup.GALLERY,
                title = "年份索引",
                note = "网格右侧那条快速跳转的年月",
                pref = SettingsKeys.Gallery.sectionIndex,
                keywords = listOf("年份", "索引", "快速跳转", "侧边"),
                // 仍是 PENDING：相册页今天**没有**那条右侧索引轨 —— 年/月跳转是筛选弹层里的
                // 一格（`GalleryJumpRails`），不是一个独立的侧边控件。缺的是"那条轨"本身。
                // 把它标成 LIVE 等于让用户拨一颗不会改变任何东西的开关。
                live = SettingLive.PENDING,
            )
        )

        // ── 相机 ──────────────────────────────────────────────────────
        add(
            SettingItem.Number(
                key = "camera_jpeg",
                group = SettingsGroup.CAMERA,
                title = "JPEG 画质",
                note = "95 起跳；100 最大，体积也最大",
                pref = SettingsKeys.Camera.jpegQuality,
                step = 5,
                keywords = listOf("画质", "jpeg", "压缩", "质量"),
                // 读者：`CameraUiState.jpegQ`（喂给 `ImageCapture.setJpegQuality`）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "camera_show_grid",
                group = SettingsGroup.CAMERA,
                title = "构图辅助线",
                note = "取景时默认画上九宫格",
                pref = SettingsKeys.Camera.showGrid,
                keywords = listOf("网格", "辅助线", "九宫格", "构图"),
                // 读者：`CameraUiState.gridMode`（0 = 不画，> 0 = 画用户挑的那一款）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "camera_shutter_sound",
                group = SettingsGroup.CAMERA,
                title = "快门声",
                note = "关闭后拍照不发声",
                pref = SettingsKeys.Camera.shutterSound,
                keywords = listOf("快门", "声音", "静音"),
                // 读者：`CameraUiState.shutterSound`（`CameraScreen` 的 `MediaActionSound`）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "camera_mirror_front",
                group = SettingsGroup.CAMERA,
                title = "前置镜像",
                note = "自拍是否左右翻转保存",
                pref = SettingsKeys.Camera.mirrorFront,
                keywords = listOf("自拍", "前置", "镜像", "翻转"),
                // 读者：`CameraUiState.mirrorFront`（预览 scaleX 与落盘/后处理那一侧）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "camera_simple_ui",
                group = SettingsGroup.CAMERA,
                title = "极简取景",
                note = "只留快门和画幅，其余藏起来",
                pref = SettingsKeys.Camera.simpleUi,
                keywords = listOf("极简", "简洁", "界面", "取景"),
                // 读者：`CameraUiState.simpleUi`（`CameraScreen` 里那几处 `if (!simpleUi)`）。
                live = SettingLive.LIVE,
            )
        )

        // ── 一瞬 ──────────────────────────────────────────────────────
        add(
            SettingItem.Number(
                key = "yishun_export_jpeg",
                group = SettingsGroup.YISHUN,
                title = "成片画质",
                note = "保存成片时的 JPEG 质量",
                pref = SettingsKeys.Yishun.exportQuality,
                step = 5,
                keywords = listOf("成片", "导出", "画质", "jpeg"),
                // 读者：`YishunScreen.jpegQ`；一瞬顶栏那颗「画质」也回写这一项。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Number(
                key = "yishun_default_intensity",
                group = SettingsGroup.YISHUN,
                title = "默认强度",
                note = "新配方第一次套上时的强度（没拨过时用配方自己的）",
                pref = SettingsKeys.Yishun.defaultIntensity,
                step = 5,
                unit = "%",
                keywords = listOf("强度", "浓淡", "默认"),
                // 读者：`YishunScreen.firstIntensity()`（只在"这条配方还没存过强度"时生效）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "yishun_hold_compare",
                group = SettingsGroup.YISHUN,
                title = "按住看原图",
                note = "按住画面回到原片，松手回成片；关了之后仍可用顶栏那颗按钮",
                pref = SettingsKeys.Yishun.holdToCompare,
                keywords = listOf("对比", "原图", "按住"),
                // 读者：`YishunScreen` 的 `onPress`（只关"按住"这一条手势，不关按钮）。
                live = SettingLive.LIVE,
            )
        )
        add(
            SettingItem.Toggle(
                key = "yishun_auto_scene",
                group = SettingsGroup.YISHUN,
                title = "自动识别场景",
                note = "端侧分析这张照片，先挑一条相近的配方",
                pref = SettingsKeys.Yishun.autoScene,
                keywords = listOf("场景", "智能", "自动", "识别"),
                // 读者：`YishunScreen` 里 `SceneAnalyzer.analyze` 外面那一层判断。
                live = SettingLive.LIVE,
            )
        )

        // ── 隐私 ──────────────────────────────────────────────────────
        add(
            SettingItem.Action(
                key = "privacy_open",
                group = SettingsGroup.PRIVACY,
                title = "隐私与数据",
                note = "照片去哪了、每条权限做什么、怎么清干净",
                action = SettingAction.OPEN_PRIVACY,
                keywords = listOf("隐私", "数据", "权限", "上传", "去哪"),
            )
        )
        add(
            SettingItem.Action(
                key = "privacy_permission",
                group = SettingsGroup.PRIVACY,
                title = "相册权限",
                note = "改授权；部分授权时在这里补",
                action = SettingAction.OPEN_PHOTO_PERMISSION,
                keywords = listOf("权限", "授权", "照片", "相册"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "privacy_ai_status",
                group = SettingsGroup.PRIVACY,
                title = "智能配方",
                note = "唯一会出公网的功能，默认关闭",
                source = ReadOnlySource.AI_STATUS,
                keywords = listOf("智能配方", "ai", "出网", "上传", "网络"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "privacy_backup_scope",
                group = SettingsGroup.PRIVACY,
                title = "系统备份带什么",
                note = "只带偏好设置，不带照片、不带 Key",
                source = ReadOnlySource.BACKUP_SCOPE,
                keywords = listOf("备份", "云备份", "换机", "同步"),
            )
        )

        // ── 存储 ──────────────────────────────────────────────────────
        add(
            SettingItem.Action(
                key = "storage_manager",
                group = SettingsGroup.STORAGE,
                title = "存储管理",
                note = "本机数据占用、缓存清理、导出与清除",
                action = SettingAction.OPEN_STORAGE,
                keywords = listOf("存储", "占用", "缓存", "清理", "空间"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "storage_used",
                group = SettingsGroup.STORAGE,
                title = "本机数据",
                note = "随拍自己写下的大小（不含照片）",
                source = ReadOnlySource.STORAGE_USED,
                keywords = listOf("占用", "大小", "空间"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "storage_cache",
                group = SettingsGroup.STORAGE,
                title = "图片缓存",
                note = "缩放后的缩略图与预览，删了会重新生成",
                source = ReadOnlySource.CACHE_SIZE,
                keywords = listOf("缓存", "cache", "临时文件"),
            )
        )
        add(
            SettingItem.Action(
                key = "storage_clear_cache",
                group = SettingsGroup.STORAGE,
                title = "清除缓存",
                note = "只删可再生的临时文件，不动照片",
                action = SettingAction.CLEAR_CACHE,
                keywords = listOf("缓存", "清除", "清理"),
            )
        )
        add(
            SettingItem.Toggle(
                key = "storage_auto_cache_clean",
                group = SettingsGroup.STORAGE,
                title = "缓存过大时提醒",
                note = "只在设置页提示，不会自己删东西（没有后台任务）",
                pref = SettingsKeys.Storage.autoCacheClean,
                keywords = listOf("缓存", "提醒", "自动清理", "阈值"),
                // 仍是 PENDING：它要在**设置页读出缓存体积之后**决定要不要提示一句，
                // 而那个数在 `nav/AppNavHost.kt` 的 `storageNumbers()` 里算（不在本 lane）。
                // 缺的是：设置页拿到 `cacheBytes` 之后读这一项、并在超过阈值时显示一行提示。
                // 在那之前它拨了不会改变屏幕上的任何东西，所以不标 LIVE。
                live = SettingLive.PENDING,
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "storage_save_location",
                group = SettingsGroup.STORAGE,
                title = "照片保存位置",
                note = "随拍写出的照片落在哪里",
                source = ReadOnlySource.SAVE_LOCATION,
                keywords = listOf("位置", "路径", "pictures", "保存"),
            )
        )

        // ── 关于 ──────────────────────────────────────────────────────
        add(
            SettingItem.Action(
                key = "about_open",
                group = SettingsGroup.ABOUT,
                title = "关于随拍",
                note = "版本、权限与用途、数据去哪了、开源许可",
                action = SettingAction.OPEN_ABOUT,
                keywords = listOf("关于", "版本", "许可", "开源", "隐私政策"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "about_version",
                group = SettingsGroup.ABOUT,
                title = "版本",
                note = "构建号 · 机型 · 系统",
                source = ReadOnlySource.APP_VERSION,
                keywords = listOf("版本", "构建号", "机型"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "about_device",
                group = SettingsGroup.ABOUT,
                title = "这台设备",
                note = "机型与系统版本",
                source = ReadOnlySource.DEVICE_LINE,
                keywords = listOf("机型", "系统", "设备", "android"),
            )
        )
        add(
            SettingItem.ReadOnly(
                key = "about_onboarding",
                group = SettingsGroup.ABOUT,
                title = "引导状态",
                note = "首启引导走完了没有",
                source = ReadOnlySource.ONBOARDING_STATUS,
                keywords = listOf("引导", "首启", "状态"),
            )
        )
    }

    /** 按分组取，顺序与 [items] 一致。分组里一个都没有时返回空表（单测会先把这个挡掉）。 */
    fun itemsIn(group: SettingsGroup): List<SettingItem> = items().filter { it.group == group }

    /** 全部分组，按界面顺序。 */
    fun groups(): List<SettingsGroup> = SettingsGroup.entries

    /**
     * 搜索。
     *
     * 三个判据：标题、副标题、关键词，都用 `contains(ignoreCase = true)` ——
     * 中文没有词边界，拼音与英文混着搜是 C 端用户的常态（"jpeg"、"grid"、"ai"）。
     * `query` 去空白之后为空就返回全部（也就是"没在搜"）。
     */
    fun search(query: String): List<SettingItem> {
        val q = query.trim()
        if (q.isEmpty()) return items()
        return items().filter { it.matches(q) }
    }

    /** 一条设置是否命中搜索词。抽成扩展便于单测直接喂边界。 */
    fun SettingItem.matches(q: String): Boolean {
        if (q.isEmpty()) return true
        return title.contains(q, ignoreCase = true) ||
            note.contains(q, ignoreCase = true) ||
            keywords.any { it.contains(q, ignoreCase = true) } ||
            key.contains(q, ignoreCase = true)
    }

    /** 分组里所有项都被过滤掉时，分组标题也要藏起来：空标题挂在列表里比不显示更像坏了。 */
    fun searchGrouped(query: String): List<Pair<SettingsGroup, List<SettingItem>>> {
        val q = query.trim()
        return groups().mapNotNull { g ->
            val hit = itemsIn(g).filter { it.matches(q) }
            if (hit.isEmpty()) null else g to hit
        }
    }

    /**
     * 界面渲染一行需要的那一个值：`Toggle` 读布尔、`Choice` 读**档位名**、`Number` 读整数。
     *
     * 收在这里而不是让 `SettingsScreen` 各自拆箱：星投影的拆箱必须知道"这一条是什么类型"，
     * 而那件事只有 [SettingItem] 自己知道。`Action` / `ReadOnly` / `Info` 返回 null
     * （它们没有"当前值"这个概念）。
     */
    fun currentValue(item: SettingItem, snapshot: SettingsSnapshot): String? = when (item) {
        is SettingItem.Toggle -> if (snapshot.bool(item.pref)) "开" else "关"
        is SettingItem.Choice<*> -> snapshot.choiceName(item.pref)
        is SettingItem.Number -> "${snapshot.int(item.pref)}${item.unit}"
        else -> null
    }

    /**
     * 点一下这一行会发生什么；返回 null 表示"这一行点不动"（只读项、空选项表）。
     *
     * 判据放在模型里而不是界面里，是为了让"点不动"能单测（界面里那段代码跑不到 JVM 上）。
     * 三档循环、数值到头回起点的规则都在这里，界面只负责把返回值交给 [SettingChange]。
     */
    fun changeFor(item: SettingItem, snapshot: SettingsSnapshot): SettingChange? = when (item) {
        is SettingItem.Toggle -> SettingChange.Toggle(item.pref, !snapshot.bool(item.pref))
        is SettingItem.Choice<*> -> {
            val next = nextOptionName(item.options.map { it.name }, snapshot.choiceName(item.pref))
            if (next == null) null else SettingChange.Choice(item.pref, next)
        }
        is SettingItem.Number -> {
            val range = item.pref.range
            val v = snapshot.int(item.pref)
            val limit = if (range.isEmpty()) v else range.last
            val next = if (v + item.step > limit) range.first else v + item.step
            SettingChange.Number(item.pref, next)
        }
        is SettingItem.Action -> SettingChange.Action(item.action)
        is SettingItem.ReadOnly -> null
        is SettingItem.Info -> null
    }

    /**
     * 循环到下一档（按 name 列表）。表里只有一档时返回 null —— 点它没有意义。
     * 当前值不在表里（坏值）时回表头，与 `SettingsStore.read` 的兜底方向一致。
     */
    fun nextOptionName(options: List<String>, current: String): String? {
        if (options.size <= 1) return null
        val i = options.indexOf(current)
        if (i < 0) return options.first()
        return options[(i + 1) % options.size]
    }
}
