package com.suipai.app.settings

/**
 * 设置中心的全部偏好键与它们的默认值 —— **唯一事实来源**。
 *
 * 三条纪律决定了这个文件的形状：
 *
 * 1. **键名一律 `cfg_` 前缀**。相机 / 一瞬 / 相册三张老表（`CameraFlag`、`YishunFlag`、
 *    `GalleryFlag`）共用 `suipai.xml` 这一个文件，而 `FlagTableGuardTest.prefKeysAreGloballyUnique`
 *    钉着"落盘键名全局唯一"。新键走独立前缀，既不会和老键撞车，
 *    也让 `SettingsStore.resetAll()` 能安全地只清本模块的键（`cfg_` 开头），
 *    不会顺手把用户的相机设置清掉。
 * 2. **默认值只写在这里**。[SettingKey.def] 是读路径的兜底，也是 `docs/product/SETTINGS.md`
 *    那一张表与单测的唯一来源；界面不许再抄一份默认值。
 * 3. **不碰老键**。`dark_ui`、`camera_jpeg`、`yishun_jpeg` 这些今天已经被各处直接读写，
 *    本模块只定义新键，老键的搬迁留给后续接线（见 `docs/product/SETTINGS.md` 的「接线」一列）。
 *
 * 任何地方都不许再写裸字符串键名：只有 [SettingsStore] 通过 [SettingKey.id] 读写。
 * 裸字符串会让 `PerfSourceGuardTest.deadPreferenceKeys`（只写不读）与
 * `FlagTableGuardTest`（撞车）同时失去取证能力。
 */
sealed class SettingKey<T> {

    /** 落盘键名。全项目唯一，`cfg_` 开头。 */
    abstract val id: String

    /** 没存过、或存了一个坏值时读到的值。 */
    abstract val def: T

    /** 这条设置在 `docs/product/SETTINGS.md` 里属于哪一节（也是分组单测的核对项）。 */
    abstract val group: SettingsGroup

    /** 布尔开关。 */
    class Bool(
        override val id: String,
        override val def: Boolean,
        override val group: SettingsGroup,
    ) : SettingKey<Boolean>()

    /**
     * 整数。
     *
     * [range] 不只是给人看的：读路径上落在区间外的值（老版本写过、别的版本写过、人手改过
     * prefs）一律退回 [def]。相机的画质档就是那种"95..100 之外没有意义"的数。
     */
    class Int(
        override val id: String,
        override val def: kotlin.Int,
        override val group: SettingsGroup,
        val range: IntRange = kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE,
    ) : SettingKey<kotlin.Int>()

    /** 文本（时间戳、说明之类，空串表示"从来没写过"）。 */
    class Text(
        override val id: String,
        override val def: String,
        override val group: SettingsGroup,
    ) : SettingKey<String>()

    /**
     * 枚举单选。**按名字落盘**，不按 ordinal ——
     * 加一档、调一次顺序都发生在未来的版本里，而 ordinal 会把这些老用户的选择
     * 悄悄解释成另一档（相机 `CamRatio` 的注释里记着同一个教训）。
     *
     * @param options 这一档的**全部**可选值（顺序即界面顺序）。默认值必须在这张表里。
     */
    class Choice<E : Enum<E>>(
        override val id: String,
        override val def: E,
        override val group: SettingsGroup,
        val options: List<E>,
    ) : SettingKey<E>() {
        /** 落盘用的默认值名字（`readRaw` 里当 `getString` 的兜底）。 */
        val defName: String get() = def.name
    }

    /** 字符串集合（设置中心的搜索历史用）。 */
    class Strings(
        override val id: String,
        override val def: Set<String>,
        override val group: SettingsGroup,
    ) : SettingKey<Set<String>>()
}

// ── 枚举档位 ────────────────────────────────────────────────────────────
// 落盘存的是档位的 name（不是 ordinal），所以这些枚举**可以**在后续版本里加成员；
// 只有改名会让老值退回默认值 —— 这是安全的坏方向：用户看到的是"回到默认"，不是"变成别的"。

/** 冷启动落在哪个一级页。 */
enum class StartTab(val label: String) {
    PHOTOS("照片"),
    ALBUMS("相册"),
    SYNC("互通"),
}

/** 界面主题。默认跟随系统：这是 Android 13+ 用户的预期。 */
enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    DARKROOM("暗房"),
    DAYLIGHT("日光印样"),
}

/**
 * 字号缩放档。
 *
 * 百分比只是**建议值**，不直接写进 sp：接线方（MainActivity / 设计系统）要按系统字级叠加，
 * 所以这里只给档位名与一个可读的百分比，免得本模块替系统做决定。
 */
enum class FontScale(val label: String, val percent: Int) {
    SMALL("小", 85),
    STANDARD("标准", 100),
    LARGE("大", 115),
    HUGE("特大", 130),
}

/**
 * 时间线网格密度。`minCellDp` 直接对得上 `GridCells.Adaptive(cellDp.dp)` 的口径：
 * 数值越小列越多。86/118/168 就是现在相册页那颗循环格的三档，另加一档 64 的密排。
 */
enum class GridColumns(val label: String, val minCellDp: Int) {
    DENSE("密排", 64),
    SMALL("小格", 86),
    STANDARD("标准", 118),
    LARGE("大格", 168),
}

/**
 * 缩略图质量：对应 `ThumbnailFetcher.FastThumb(edgePx)` 要的那条边长。
 * 256/420/720 都落在它 `coerceIn(64, 1024)` 的合法区间里，而且 420 就是今天相册页写死的那个数。
 */
enum class ThumbQuality(val label: String, val edgePx: Int) {
    FAST("省电", 256),
    STANDARD("标准", 420),
    SHARP("清晰", 720),
}

/** 相册默认排序。 */
enum class DefaultSort(val label: String) {
    NEWEST("最新在前"),
    OLDEST("最早在前"),
    NAME("按文件名"),
    SIZE("按体积"),
}

/** 时间线分组粒度。 */
enum class GroupBy(val label: String) {
    DAY("按日"),
    MONTH("按月"),
    YEAR("按年"),
}

/**
 * 设置项里的「动作」。纯 Kotlin 枚举，界面把它翻译成回调；
 * 这样 `SettingsModel` 不依赖 `Context` / 路由，能被 JVM 单测完整覆盖。
 */
enum class SettingAction {
    /** 打开外观子页。 */
    OPEN_APPEARANCE,

    /** 打开存储子页。 */
    OPEN_STORAGE,

    /** 打开隐私与数据子页。 */
    OPEN_PRIVACY,

    /** 打开关于子页。 */
    OPEN_ABOUT,

    /** 重看首启引导。 */
    REPLAY_ONBOARDING,

    /** 清理缓存（不动照片）。 */
    CLEAR_CACHE,

    /** 把「本机数据说明」复制到剪贴板。 */
    EXPORT_LOCAL_DATA,

    /** 清掉本模块全部偏好（不清照片）。 */
    CLEAR_LOCAL_DATA,

    /** 跳系统的「本应用信息」页。 */
    OPEN_SYSTEM_APP_SETTINGS,

    /** 跳系统的照片授权页（两条出口见 `Launch.kt`）。 */
    OPEN_PHOTO_PERMISSION,
}

/**
 * 设置项里的「只读值」来源。同样是纯 Kotlin 枚举：
 * 界面按它去取运行时真值（版本号、机型、占用、张数），模型本身可单测。
 */
enum class ReadOnlySource {
    APP_VERSION,
    DEVICE_LINE,
    SAVE_LOCATION,
    STORAGE_USED,
    CACHE_SIZE,
    LIBRARY_COUNT,
    AI_STATUS,
    BACKUP_SCOPE,
    ONBOARDING_STATUS,
}

/**
 * 一条设置在**今天**被谁读走。
 *
 * 这不是审美分类，是诚实性的分类：这一波只落数据层，绝大多数设置的读数点还在别的 lane 手里
 * （相机 / 相册 / 一瞬 / MainActivity）。界面上把"已经生效"和"等接线"分开说，
 * 比让用户拨了一颗开关却什么都没发生要好 —— 后者在装机复测里会被当成"设置中心坏了"。
 */
enum class SettingLive {
    /** 今天就有读者：拨了立刻生效。 */
    LIVE,

    /** 在本屏内即时预览（例如外观页的主题与网格预览），全局生效等接线。 */
    PREVIEW,

    /** 只落数据，暂无读者。界面上灰着 + 一句说明，不许假装生效。 */
    PENDING,
}

/**
 * 全部新键。八个分组。
 *
 * 加一条设置的动作是：在这里加一个 `val`，挑好 [SettingsGroup]，
 * 再在 `SettingsCatalog` 里挂一条 `SettingItem` —— 单测会核对这三处对得上。
 */
object SettingsKeys {

    // ── 通用 ────────────────────────────────────────────────────────────
    object General {
        /** 首启引导是否已经走完。**这是首启判定的唯一真值**。 */
        val onboarded = SettingKey.Bool("cfg_onboarding_done", false, SettingsGroup.GENERAL)

        /**
         * 引导内容版本。引导改了内容想让老用户再看一遍，就把它 +1：
         * 判定是 `onboarded && version >= 当前版本`，所以只加版本号不用清 [onboarded]。
         *
         * **0 是有意义的值**：设置里点「重看引导」就是把这一条退回 0（见 `OnboardingGate.replay`），
         * 它表示"没看过当前这一版内容"，而不是"走完了 v0"。所以区间从 0 起。
         */
        val onboardingVersion = SettingKey.Int(
            "cfg_onboarding_version", 1, SettingsGroup.GENERAL, range = 0..99,
        )

        /** 冷启动落在哪个一级页。 */
        val startTab = SettingKey.Choice(
            "cfg_start_tab", StartTab.PHOTOS, SettingsGroup.GENERAL, StartTab.entries,
        )

        /** 删除照片前是否要再确认一次。默认要（批量删除不可逆）。 */
        val confirmDelete = SettingKey.Bool("cfg_confirm_delete", true, SettingsGroup.GENERAL)

        /** 桌面快捷方式是否显示「拍照」。 */
        val cameraShortcut = SettingKey.Bool("cfg_shortcut_camera", false, SettingsGroup.GENERAL)
    }

    // ── 外观 ────────────────────────────────────────────────────────────
    object Appearance {
        val themeMode = SettingKey.Choice(
            "cfg_theme_mode", ThemeMode.FOLLOW_SYSTEM, SettingsGroup.APPEARANCE, ThemeMode.entries,
        )
        val fontScale = SettingKey.Choice(
            "cfg_font_scale", FontScale.STANDARD, SettingsGroup.APPEARANCE, FontScale.entries,
        )
        val gridColumns = SettingKey.Choice(
            "cfg_grid_columns", GridColumns.STANDARD, SettingsGroup.APPEARANCE, GridColumns.entries,
        )
        val thumbQuality = SettingKey.Choice(
            "cfg_thumb_quality", ThumbQuality.STANDARD, SettingsGroup.APPEARANCE, ThumbQuality.entries,
        )

        /** 减弱动效。默认关：动效是这套界面的一部分。 */
        val reduceMotion = SettingKey.Bool("cfg_reduce_motion", false, SettingsGroup.APPEARANCE)
    }

    // ── 相册 ────────────────────────────────────────────────────────────
    object Gallery {
        val defaultSort = SettingKey.Choice(
            "cfg_gallery_sort", DefaultSort.NEWEST, SettingsGroup.GALLERY, DefaultSort.entries,
        )
        val groupBy = SettingKey.Choice(
            "cfg_gallery_group", GroupBy.DAY, SettingsGroup.GALLERY, GroupBy.entries,
        )

        /** 时间线默认是否显示已隐藏的照片。默认不显示（隐藏就是不想看见）。 */
        val showHidden = SettingKey.Bool("cfg_gallery_show_hidden", false, SettingsGroup.GALLERY)

        /** 时间线右侧年份索引轨。 */
        val sectionIndex = SettingKey.Bool("cfg_gallery_section_index", true, SettingsGroup.GALLERY)
    }

    // ── 相机 ────────────────────────────────────────────────────────────
    object Camera {
        /** JPEG 画质。和相机页那颗 `camera_jpeg` 同一件事，但键是新的（见文件头第 3 条）。 */
        val jpegQuality = SettingKey.Int(
            "cfg_camera_jpeg", 95, SettingsGroup.CAMERA, range = 95..100,
        )
        val showGrid = SettingKey.Bool("cfg_camera_show_grid", true, SettingsGroup.CAMERA)
        val shutterSound = SettingKey.Bool("cfg_camera_shutter_sound", true, SettingsGroup.CAMERA)
        val mirrorFront = SettingKey.Bool("cfg_camera_mirror_front", true, SettingsGroup.CAMERA)
        val simpleUi = SettingKey.Bool("cfg_camera_simple_ui", false, SettingsGroup.CAMERA)
    }

    // ── 一瞬 ────────────────────────────────────────────────────────────
    object Yishun {
        val exportQuality = SettingKey.Int(
            "cfg_yishun_export_jpeg", 95, SettingsGroup.YISHUN, range = 95..100,
        )
        val defaultIntensity = SettingKey.Int(
            "cfg_yishun_default_intensity", 60, SettingsGroup.YISHUN, range = 0..100,
        )
        val holdToCompare = SettingKey.Bool("cfg_yishun_hold_compare", true, SettingsGroup.YISHUN)
        val autoScene = SettingKey.Bool("cfg_yishun_auto_scene", true, SettingsGroup.YISHUN)
    }

    // ── 隐私 ────────────────────────────────────────────────────────────
    object Privacy {
        /** 上一次导出「本机数据说明」的时间（ISO-8601，空串 = 从没导过）。 */
        val exportAt = SettingKey.Text("cfg_privacy_export_at", "", SettingsGroup.PRIVACY)

        /** 隐私说明横幅是否已经点掉。 */
        val noticeSeen = SettingKey.Bool("cfg_privacy_notice_seen", false, SettingsGroup.PRIVACY)
    }

    // ── 存储 ────────────────────────────────────────────────────────────
    object Storage {
        /** 上一次清理缓存的时间（ISO-8601，空串 = 从没清过）。 */
        val cacheCleanAt = SettingKey.Text("cfg_cache_clean_at", "", SettingsGroup.STORAGE)

        /** 缓存超过阈值时在设置页提示。默认关：没有后台任务，不做自动清理。 */
        val autoCacheClean = SettingKey.Bool("cfg_auto_cache_clean", false, SettingsGroup.STORAGE)
    }

    // ── 设置中心自己 ────────────────────────────────────────────────────
    object Self {
        /** 紧凑行距。 */
        val compact = SettingKey.Bool("cfg_settings_compact", false, SettingsGroup.GENERAL)

        /** 设置搜索的最近词（最多 [SETTINGS_SEARCH_HISTORY_MAX] 条）。 */
        val searchHistory = SettingKey.Strings(
            "cfg_settings_search_history", emptySet(), SettingsGroup.GENERAL,
        )

        /** 首屏那句「点这里重看引导」的提示是否已经点掉了。 */
        val hintSeen = SettingKey.Bool("cfg_settings_hint_seen", false, SettingsGroup.GENERAL)
    }

    /** 搜索历史最多留几条。多于此数丢最旧的。 */
    const val SETTINGS_SEARCH_HISTORY_MAX = 8

    /** 新键的统一前缀。 */
    const val PREFIX = "cfg_"

    /** 全部键，按分组顺序。单测、`repairBadValues()`、`resetAll()` 都走这一张。 */
    val all: List<SettingKey<*>> = listOf(
        General.onboarded, General.onboardingVersion, General.startTab,
        General.confirmDelete, General.cameraShortcut,
        Appearance.themeMode, Appearance.fontScale, Appearance.gridColumns,
        Appearance.thumbQuality, Appearance.reduceMotion,
        Gallery.defaultSort, Gallery.groupBy, Gallery.showHidden, Gallery.sectionIndex,
        Camera.jpegQuality, Camera.showGrid, Camera.shutterSound, Camera.mirrorFront, Camera.simpleUi,
        Yishun.exportQuality, Yishun.defaultIntensity, Yishun.holdToCompare, Yishun.autoScene,
        Privacy.exportAt, Privacy.noticeSeen,
        Storage.cacheCleanAt, Storage.autoCacheClean,
        Self.compact, Self.searchHistory, Self.hintSeen,
    )

    private val byId: Map<String, SettingKey<*>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        all.associateBy { it.id }
    }

    fun byId(id: String): SettingKey<*>? = byId[id]

    /** 这一条是不是本模块的键（`resetAll()` 只清这些，绝不碰相机/一瞬/相册的老键）。 */
    fun isOurs(id: String): Boolean = id.startsWith(PREFIX)
}

/** 设置页的八个分组。`title` 同时是导航里的页名，单测钉住不重复。 */
enum class SettingsGroup(val title: String, val note: String) {
    GENERAL("通用", "启动、引导与设置中心自己的样子"),
    APPEARANCE("外观", "主题、字号、网格与缩略图"),
    GALLERY("相册", "时间线怎么排、怎么分组"),
    CAMERA("相机", "拍照时的默认值"),
    YISHUN("一瞬", "成片与对比的默认值"),
    PRIVACY("隐私", "数据去哪、权限用来做什么"),
    STORAGE("存储", "占用了多少、怎么清"),
    ABOUT("关于", "版本、开源许可与隐私政策"),
    ;
}
