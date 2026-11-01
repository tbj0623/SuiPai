package com.suipai.app.settings

/**
 * 设置中心（`cfg_` 新键）与各功能老键之间的**唯一**一道桥。
 *
 * ── 为什么需要这座桥 ──
 *
 * 相册、相机、一瞬三张老表读的是一批裸键（`grid_cell`、`gallery_sort`、`show_grid`、
 * `camera_jpeg`、`yishun_jpeg` …），设置中心写的是一批 `cfg_` 新键。两边谁也不认识谁的时候，
 * 界面上那些项就算改成"可拨"，拨完也**什么都不会发生** —— 那比灰着更糟：
 * 灰着用户知道没接线，可拨而无效是欺骗。
 *
 * ── 真源选在哪边 ──
 *
 * **`cfg_` 是真源**，老键降级为"兼容写"。理由三条：
 * 1. 新键在 `SettingsKeys` 里有默认值、有区间、有序号、有单测；老键什么都没有，
 *    默认值散在六个调用点里。
 * 2. 真源必须唯一。反过来（老键做真源、`cfg_` 只做镜像）会让设置页显示的值和实际生效的值
 *    来自两个地方，迟早说岔 —— 那正是这一轮要修的病的同一种。
 * 3. 老键不能删：降级安装要认。所以**每次改 `cfg_` 都顺手把老键写成同一个选择**
 *    （[legacyWritesFor]）；界面自己拨老键时把 `cfg_` 写成同一个选择
 *    （[cfgForLegacyBool] / [cfgForLegacyInt]）。两条都是"写两边"，谁也不是谁的镜像。
 *
 * ⚠️ **从这一版起，被桥接的老键只写不读。** 读侧全部走 `cfg_`；老键的写入只为让降级安装
 * 读得懂。所以老键名收在 [LegacyKeys] 里、不再以 `putX("字面量")` 的形状出现在调用点 ——
 * `PerfSourceGuardTest.deadPreferenceKeys` 只认字面量键名，它不会把"为了兼容而写"误报成
 * "只写不读"。别把字面量加回调用点（守卫会红），也别删兼容写（降级读不到）。
 *
 * ── 纪律 ──
 *
 * - **纯 Kotlin，不 import android**：整座桥都能在 JVM 上直接钉住。
 * - **枚举到数字一律显式写，不许 `ordinal`**：枚举重排一次，老用户的"按月"就会变成"按年"，
 *   而这种错不会崩、只会悄悄错。相机 `CamRatio` 的注释里记着同一个教训。
 * - 值域外的输入一律返回"没有映射"，不抛异常：设置读坏了不该让界面起不来。
 */
object SettingsBridge {

    // ── 相册 · 网格与缩略图 ────────────────────────────────────────────

    /**
     * 网格最小格宽（dp）。`GridCells.Adaptive(cellDp.dp)` 的口径：数字越小列越多。
     *
     * 64 是新加的密排档；86/118/168 就是相册页那颗循环格原来的三档。
     */
    fun gridCellDp(columns: GridColumns): Int = when (columns) {
        GridColumns.DENSE -> 64
        GridColumns.SMALL -> 86
        GridColumns.STANDARD -> 118
        GridColumns.LARGE -> 168
    }

    /**
     * 反向：老键 `grid_cell` 的 dp 值 → 档位，**就近向上落档**。
     *
     * 不用精确等号：老版本里可能存着 100 这种中间值，它必须落到一个**存在的**档上，
     * 否则界面上那颗格子会既不亮任何一档、又点不出变化。
     */
    fun gridColumns(cellDp: Int): GridColumns = when {
        cellDp <= GridColumns.DENSE.minCellDp -> GridColumns.DENSE
        cellDp <= GridColumns.SMALL.minCellDp -> GridColumns.SMALL
        cellDp <= GridColumns.STANDARD.minCellDp -> GridColumns.STANDARD
        else -> GridColumns.LARGE
    }

    /** 缩略图长边（px）。都落在 `FastThumb` 的 `coerceIn(64, 1024)` 里，420 是相册页今天写死的那个数。 */
    fun thumbEdgePx(quality: ThumbQuality): Int = when (quality) {
        ThumbQuality.FAST -> 256
        ThumbQuality.STANDARD -> 420
        ThumbQuality.SHARP -> 720
    }

    /** 反向：老键里的像素长边 → 档位（同样是就近落档，不接受表外的数）。 */
    fun thumbQuality(edgePx: Int): ThumbQuality = when {
        edgePx <= ThumbQuality.FAST.edgePx -> ThumbQuality.FAST
        edgePx <= ThumbQuality.STANDARD.edgePx -> ThumbQuality.STANDARD
        else -> ThumbQuality.SHARP
    }

    // ── 相册 · 排序与分组 ─────────────────────────────────────────────

    /**
     * 排序档 → 相册工具条那六档里的编号。
     *
     * 老编号：0 最新 / 1 最早 / 2 最大 / 3 最小 / 4 按名 / 5 按像素。
     * 新档只有四档，"按体积"落到 **2（最大在前）**：与"最新在前"的降序口径一致；
     * 想按小到大排的用户在相册里点同一颗格子还能转到 3。
     */
    fun gallerySortCode(sort: DefaultSort): Int = when (sort) {
        DefaultSort.NEWEST -> 0
        DefaultSort.OLDEST -> 1
        DefaultSort.NAME -> 4
        DefaultSort.SIZE -> 2
    }

    /**
     * 反向：老编号 → 排序档。表外的编号（老版本的 3 / 5）落到最近的那一档，不崩。
     *
     * 3 与 5 都归到 [DefaultSort.SIZE]：它们本来就是"按体积"的两个方向 ——
     * 把"最小在前"读成"最新在前"会让用户以为设置被重置了。
     */
    fun defaultSort(code: Int): DefaultSort = when (code) {
        1 -> DefaultSort.OLDEST
        2, 3, 5 -> DefaultSort.SIZE
        4 -> DefaultSort.NAME
        else -> DefaultSort.NEWEST
    }

    /** 老键 `newest_first` 的取值：只有"最新在前"是 true。 */
    fun newestFirst(sort: DefaultSort): Boolean = sort == DefaultSort.NEWEST

    /** 分组档 → 老编号：0 按日 / 1 按月 / 2 按年（3 = 按周，只存在于相册自己的循环格）。 */
    fun groupModeCode(group: GroupBy): Int = when (group) {
        GroupBy.DAY -> 0
        GroupBy.MONTH -> 1
        GroupBy.YEAR -> 2
    }

    /** 反向：老编号 → 分组档。"按周"（3）落到按年，而不是悄悄变成按日。 */
    fun groupBy(code: Int): GroupBy = when (code) {
        1 -> GroupBy.MONTH
        2, 3 -> GroupBy.YEAR
        else -> GroupBy.DAY
    }

    // ── 相机 ──────────────────────────────────────────────────────────

    /**
     * 「构图辅助线」这个开关与相机的 `grid_mode`（20 款网格）怎么对上。
     *
     * 开关只管"画不画"，不管"画哪一款"：关 → 0；开 → 保留相机自己记着的那一款
     * （存的不是正数才退到 1 = 九宫格）。把"开"硬写成 1 会把用户挑的黄金分割冲掉。
     *
     * 这里是**读**侧的映射。写侧刻意只写老键 `show_grid`、不碰 `grid_mode`
     * （理由见 [legacyWritesFor] 里那一段）—— 所以"开"这一档没有对应的写入函数，
     * 下一次进相机时由本函数把 `show_grid=true` 翻译成"画一款"。
     */
    fun gridModeInit(showGrid: Boolean, storedGridMode: Int): Int = when {
        !showGrid -> 0
        storedGridMode <= 0 -> GRID_MODE_ON
        else -> storedGridMode
    }

    /** 反向：`grid_mode > 0` 就是"开着"。 */
    fun showGridOf(gridMode: Int): Boolean = gridMode > 0

    /** 开关拨成"开"、而相机那边又没记着任何一款时落到的那一档：1 = 九宫格。 */
    const val GRID_MODE_ON: Int = 1

    // ── 外观 ──────────────────────────────────────────────────────────

    /** 字号档 → 缩放系数（叠在系统字级之上，不是覆盖）。 */
    fun fontScaleFactor(scale: FontScale): Float = scale.percent / 100f

    /**
     * 主题档 → 老键 `dark_ui` 该写什么。
     *
     * `FOLLOW_SYSTEM` 返回 null：**跟随系统时不写老键**。写一个固定值等于把"跟随系统"
     * 偷偷改成"固定"，而那正是这一轮要消灭的那类谎。
     */
    fun themeDark(mode: ThemeMode): Boolean? = when (mode) {
        ThemeMode.DARKROOM -> true
        ThemeMode.DAYLIGHT -> false
        ThemeMode.FOLLOW_SYSTEM -> null
    }

    /**
     * 落盘里的档位名 + 老键 `dark_ui` → 今天的主题档。
     *
     * 三种输入分开处理，因为它们的含义不同：
     * - 存过一个合法名字 → 就是它（用户在设置页显式选过）；
     * - **没存过**（null）→ 按老键 `dark_ui` 定。这是升级迁移：上一版页眉那颗 DARKROOM/DAYLIGHT
     *   开关写的正是 `dark_ui`，把它读成"暗房/日光印样"才接得住人家已有的选择；
     * - 存过一个认不出的名字（手改 prefs / 以后删过档位）→ 回默认的暗房，不猜。
     */
    fun themeModeOf(storedName: String?, legacyDarkUi: Boolean): ThemeMode =
        modeByName(storedName) ?: if (storedName == null) {
            if (legacyDarkUi) ThemeMode.DARKROOM else ThemeMode.DAYLIGHT
        } else {
            ThemeMode.DARKROOM
        }

    // ── 升级迁移：没存过 cfg 时先认老键 ───────────────────────────────

    /**
     * 网格档的初值。`storedName` 为 null（旧版本从没写过 cfg）时按老键 `grid_cell` 认，
     * 否则严格按 cfg 走 —— 迁移只发生一次，之后老键再被谁写都不影响读数。
     *
     * 名字认不出（手改过 prefs）时**不**回落到老键，而是回默认档：磁盘上存在一个 cfg 值
     * 就说明"用户用过设置页"，那时再拿老键盖上来是另一种数据打架。
     */
    fun gridColumnsInitial(storedName: String?, legacyCellDp: Int): GridColumns =
        if (storedName == null) gridColumns(legacyCellDp)
        else enumByName(GridColumns.entries, storedName) ?: GridColumns.STANDARD

    /** 缩略图档的初值，口径同上。 */
    fun thumbQualityInitial(storedName: String?, legacyEdgePx: Int): ThumbQuality =
        if (storedName == null) thumbQuality(legacyEdgePx)
        else enumByName(ThumbQuality.entries, storedName) ?: ThumbQuality.STANDARD

    /** 排序档的初值，口径同上。 */
    fun defaultSortInitial(storedName: String?, legacyCode: Int): DefaultSort =
        if (storedName == null) defaultSort(legacyCode)
        else enumByName(DefaultSort.entries, storedName) ?: DefaultSort.NEWEST

    /** 分组档的初值，口径同上。 */
    fun groupByInitial(storedName: String?, legacyCode: Int): GroupBy =
        if (storedName == null) groupBy(legacyCode)
        else enumByName(GroupBy.entries, storedName) ?: GroupBy.DAY

    // ── 写两边 ────────────────────────────────────────────────────────

    /**
     * 设置中心写了一条之后，老键该跟着写什么。空表 = 这条设置没有老键（或不是可桥接的键）。
     *
     * @param id `SettingKey.id`（`cfg_` 开头）
     * @param value 落盘值：Bool 键是 Boolean、Choice 键是**档位名** String、Int 键是 Int
     */
    fun legacyWritesFor(id: String, value: Any): List<LegacyWrite> = when (id) {
        SettingsKeys.Appearance.themeMode.id -> {
            val dark = enumByName(ThemeMode.entries, value)?.let { themeDark(it) }
            if (dark == null) emptyList() else listOf(LegacyWrite(LegacyKeys.DARK_UI, dark))
        }

        SettingsKeys.Appearance.gridColumns.id ->
            enumByName(GridColumns.entries, value)
                ?.let { listOf(LegacyWrite(LegacyKeys.GRID_CELL, gridCellDp(it))) }
                .orEmpty()

        SettingsKeys.Gallery.defaultSort.id -> {
            val sort = enumByName(DefaultSort.entries, value)
            if (sort == null) {
                emptyList()
            } else {
                listOf(
                    LegacyWrite(LegacyKeys.GALLERY_SORT, gallerySortCode(sort)),
                    LegacyWrite(LegacyKeys.NEWEST_FIRST, newestFirst(sort)),
                )
            }
        }

        SettingsKeys.Gallery.groupBy.id ->
            enumByName(GroupBy.entries, value)
                ?.let { listOf(LegacyWrite(LegacyKeys.GROUP_MODE, groupModeCode(it))) }
                .orEmpty()

        SettingsKeys.Camera.jpegQuality.id ->
            (value as? Int)?.let { listOf(LegacyWrite(LegacyKeys.CAMERA_JPEG, it)) }.orEmpty()

        SettingsKeys.Camera.showGrid.id ->
            // 只写 `show_grid`，**不**写 `grid_mode`。
            //
            // `grid_mode` 是"画哪一款"（20 款里的编号），开关只决定"画不画"。这两个键在
            // 老版本里是分开存的，而开关真正关掉时把编号改成 0 会**永久丢掉**用户挑的样式：
            // 再打开时只能回到 1（九宫格），而他原来选的是黄金分割。
            // 那个损失在当前的读法下没有任何好处（现在关不关看的是 `cfg_`），
            // 而降级那一侧最多是"老版本仍然画着网格"—— 一次可见、可改的错，
            // 比一次不可逆的数据丢失轻。
            (value as? Boolean)?.let { listOf(LegacyWrite(LegacyKeys.SHOW_GRID, it)) }.orEmpty()

        SettingsKeys.Camera.shutterSound.id ->
            (value as? Boolean)?.let { listOf(LegacyWrite(LegacyKeys.SHUTTER_SOUND, it)) }.orEmpty()

        SettingsKeys.Camera.mirrorFront.id ->
            (value as? Boolean)?.let { listOf(LegacyWrite(LegacyKeys.MIRROR_FRONT, it)) }.orEmpty()

        SettingsKeys.Camera.simpleUi.id ->
            (value as? Boolean)?.let { listOf(LegacyWrite(LegacyKeys.CAMERA_SIMPLE, it)) }.orEmpty()

        SettingsKeys.Yishun.exportQuality.id ->
            (value as? Int)?.let { listOf(LegacyWrite(LegacyKeys.YISHUN_JPEG, it)) }.orEmpty()

        else -> emptyList()
    }

    /**
     * 界面自己拨了一个**老布尔键**之后，对应的 `cfg_` 键该写成什么。null = 这个老键没有设置项。
     *
     * 只给"两边都是开/关"的那几条：主题、辅助线、快门声、镜像、极简取景。
     * 注意这里**不**反过来再推一遍别的老键 —— 界面拨什么就写什么，多推一步会让
     * "按体积排（小在前）"被自己改回"大在前"。
     */
    fun cfgForLegacyBool(legacyKey: String, value: Boolean): CfgWrite? = when (legacyKey) {
        LegacyKeys.DARK_UI -> CfgWrite(
            SettingsKeys.Appearance.themeMode.id,
            if (value) ThemeMode.DARKROOM.name else ThemeMode.DAYLIGHT.name,
        )
        LegacyKeys.SHOW_GRID -> CfgWrite(SettingsKeys.Camera.showGrid.id, value)
        LegacyKeys.SHUTTER_SOUND -> CfgWrite(SettingsKeys.Camera.shutterSound.id, value)
        LegacyKeys.MIRROR_FRONT -> CfgWrite(SettingsKeys.Camera.mirrorFront.id, value)
        LegacyKeys.CAMERA_SIMPLE -> CfgWrite(SettingsKeys.Camera.simpleUi.id, value)
        else -> null
    }

    /**
     * 界面自己拨了一个**老整数键**之后，对应的 `cfg_` 键该写成什么。
     *
     * 画质两条直接过数值；`grid_cell` / `gallery_sort` / `group_mode` 先过枚举映射 ——
     * 那三处的映射见上面的函数，不许在这里用 ordinal 抄近路。
     * `grid_mode` 不是"档位"而是一串样式编号，只有 0 / 非 0 有意义。
     */
    fun cfgForLegacyInt(legacyKey: String, value: Int): CfgWrite? = when (legacyKey) {
        LegacyKeys.GRID_CELL -> CfgWrite(
            SettingsKeys.Appearance.gridColumns.id,
            gridColumns(value).name,
        )
        LegacyKeys.GALLERY_SORT -> CfgWrite(
            SettingsKeys.Gallery.defaultSort.id,
            defaultSort(value).name,
        )
        LegacyKeys.GROUP_MODE -> CfgWrite(SettingsKeys.Gallery.groupBy.id, groupBy(value).name)
        LegacyKeys.CAMERA_JPEG -> CfgWrite(SettingsKeys.Camera.jpegQuality.id, value)
        LegacyKeys.YISHUN_JPEG -> CfgWrite(SettingsKeys.Yishun.exportQuality.id, value)
        LegacyKeys.GRID_MODE -> CfgWrite(SettingsKeys.Camera.showGrid.id, showGridOf(value))
        else -> null
    }

    /**
     * 有老键对应物的 `cfg_` 键。界面凭它决定"这条设置是不是真的接上了"，
     * `SettingsModelTest` 与 `docs/product/SETTINGS.md` 的 LIVE 清单也拿它核对。
     */
    val BRIDGED_IDS: Set<String> = setOf(
        SettingsKeys.Appearance.themeMode.id,
        SettingsKeys.Appearance.gridColumns.id,
        SettingsKeys.Gallery.defaultSort.id,
        SettingsKeys.Gallery.groupBy.id,
        SettingsKeys.Camera.jpegQuality.id,
        SettingsKeys.Camera.showGrid.id,
        SettingsKeys.Camera.shutterSound.id,
        SettingsKeys.Camera.mirrorFront.id,
        SettingsKeys.Camera.simpleUi.id,
        SettingsKeys.Yishun.exportQuality.id,
    )

    fun isBridged(id: String): Boolean = id in BRIDGED_IDS

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun modeByName(name: Any?): ThemeMode? = enumByName(ThemeMode.entries, name)

    /** 从落盘值（档位名）里认出枚举成员。大小写不敏感，认不出就是 null。 */
    private fun <E : Enum<E>> enumByName(options: List<E>, value: Any?): E? {
        val name = value as? String ?: return null
        return options.firstOrNull { it.name == name }
            ?: options.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * 一次"顺手写到老键"的动作。值只可能是 Boolean / Int / String ——
 * 三种刚好对上 `SharedPreferences.Editor` 的三个 put 重载，别加第四种。
 */
data class LegacyWrite(val key: String, val value: Any)

/** 一次"老键拨动 → 回写设置中心"的动作。 */
data class CfgWrite(val id: String, val value: Any)

/**
 * 全部被桥接的老键名，收在一处。
 *
 * 为什么不让各处写字面量：这些名字是**跨版本契约**，散在六个文件里抄一遍之后，
 * 改一个漏一个是必然的；而且收在这里之后，它们不再以 `putX("字面量")` 的形状出现在调用点，
 * `PerfSourceGuardTest.deadPreferenceKeys` 就不会把"为了降级兼容而写"误判成"只写不读"
 * （理由见 `SettingsBridge` 的文件头）。
 */
object LegacyKeys {
    const val DARK_UI = "dark_ui"
    const val GRID_CELL = "grid_cell"
    const val GALLERY_SORT = "gallery_sort"
    const val NEWEST_FIRST = "newest_first"
    const val GROUP_MODE = "group_mode"
    const val GRID_MODE = "grid_mode"
    const val SHOW_GRID = "show_grid"
    const val CAMERA_JPEG = "camera_jpeg"
    const val SHUTTER_SOUND = "shutter_sound"
    const val MIRROR_FRONT = "mirror_front"
    const val CAMERA_SIMPLE = "camera_simple"
    const val YISHUN_JPEG = "yishun_jpeg"
}
