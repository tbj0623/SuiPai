package com.suipai.app.ui.yishun

import android.content.SharedPreferences

/**
 * 一瞬界面上所有「记住上次怎么选」的布尔开关，收成一张表。
 *
 * 做法照抄 [LookFilter] / [com.suipai.app.engine.Shifts]，动机也一样，而且这里两条都比「好看」硬：
 * 1. 原来这 20 个开关是 20 个 `by remember { mutableStateOf(...) }` 委托属性，全住在
 *    `YishunScreen()` 里。那个 composable 长到 1674 行时整类被 ART 校验器拒过
 *    （`VerifyError`，编译期看不出来，只在设备上表现为「进一瞬就没了」），收成 1 个委托是往悬崖外走。
 * 2. 20 段一模一样的 `TextButton + prefs.edit().putBoolean(...)`，文案、落盘键、初始值分散三处，
 *    已经出过「滑杆写淡化、摘要写褪色」那类两两对不上的问题。表是唯一事实来源。
 *
 * 落盘格式**不变**（仍是一个开关一个 boolean key，见 [saveTo]），老用户的设置不会丢。
 */
enum class YishunFlagGroup(val title: String) {
    FILTER("预设池"),
    LOOK("配方行"),
    COMPARE("对比"),
    VIEW("显示"),
    EXPORT("导出"),
}

/**
 * @param onlyWhen 该开关只在别的开关打开时才有意义（如「名称倒序」只在「按名排」时有用）。
 *                 顶栏和 sheet 都按它决定要不要显示，避免摆一排点了没反应的按钮。
 */
enum class YishunFlag(
    val prefKey: String,
    val group: YishunFlagGroup,
    val labelOff: String,
    val labelOn: String,
    val get: (YishunFlags) -> Boolean,
    val with: (YishunFlags, Boolean) -> YishunFlags,
    val onlyWhen: (YishunFlags) -> Boolean = { true },
) {
    // 预设池（哪些配方有资格出现在下面那一行里）
    ONLY_PINS(
        "yishun_only_pins", YishunFlagGroup.FILTER, "全部配方", "只看钉住",
        { it.onlyPins }, { f, v -> f.copy(onlyPins = v) },
    ),
    ONLY_RECENT(
        "yishun_only_recent", YishunFlagGroup.FILTER, "含更早", "只看最近",
        { it.onlyRecent }, { f, v -> f.copy(onlyRecent = v) },
    ),

    // 配方行
    PREFER_LAST(
        "yishun_prefer_last", YishunFlagGroup.LOOK, "每次智能", "沿用上次",
        { it.preferLast }, { f, v -> f.copy(preferLast = v) },
    ),
    LOCK_LOOK(
        "yishun_lock_look", YishunFlagGroup.LOOK, "跟图配方", "锁配方",
        { it.lockLook }, { f, v -> f.copy(lockLook = v) },
    ),
    CHIP_NAMES(
        "yishun_chip_names", YishunFlagGroup.LOOK, "只看图", "配方名",
        { it.chipNames }, { f, v -> f.copy(chipNames = v) },
    ),
    CHIP_SORT(
        "yishun_chip_sort", YishunFlagGroup.LOOK, "默认排", "按名排",
        { it.chipSort }, { f, v -> f.copy(chipSort = v) },
    ),
    CHIP_REV(
        "yishun_chip_rev", YishunFlagGroup.LOOK, "名称正序", "名称倒序",
        { it.chipRev }, { f, v -> f.copy(chipRev = v) },
        onlyWhen = { it.chipSort },
    ),

    // 对比
    SPLIT_VERT(
        "yishun_split_v", YishunFlagGroup.COMPARE, "横分", "竖分",
        { it.splitVert }, { f, v -> f.copy(splitVert = v) },
    ),
    SPLIT_FLIP(
        "yishun_split_flip", YishunFlagGroup.COMPARE, "原图在左", "成片在左",
        { it.splitFlip }, { f, v -> f.copy(splitFlip = v) },
    ),
    SPLIT_RED(
        "yishun_split_red", YishunFlagGroup.COMPARE, "白线", "红线",
        { it.splitRed }, { f, v -> f.copy(splitRed = v) },
    ),

    // 显示
    PREVIEW_FILL(
        "yishun_fill", YishunFlagGroup.VIEW, "适应", "铺满",
        { it.previewFill }, { f, v -> f.copy(previewFill = v) },
    ),
    SHOW_HIST(
        "yishun_hist", YishunFlagGroup.VIEW, "藏直方图", "显直方图",
        { it.showHist }, { f, v -> f.copy(showHist = v) },
    ),
    HIST_RGB(
        "yishun_hist_rgb", YishunFlagGroup.VIEW, "亮度条", "RGB 条",
        { it.histRgb }, { f, v -> f.copy(histRgb = v) },
        onlyWhen = { it.showHist },
    ),
    BLINK(
        "yishun_blink", YishunFlagGroup.VIEW, "闪比", "闪比开",
        { it.blink }, { f, v -> f.copy(blink = v) },
    ),
    SHOW_TUNE(
        "yishun_tune", YishunFlagGroup.VIEW, "藏微调", "显微调",
        { it.showTune }, { f, v -> f.copy(showTune = v) },
    ),

    // 导出
    LOCK_INT(
        "yishun_lock_int", YishunFlagGroup.EXPORT, "跟图强度", "锁强度",
        { it.lockInt }, { f, v -> f.copy(lockInt = v) },
    ),
    SAVE_PNG(
        "yishun_png", YishunFlagGroup.EXPORT, "存 JPG", "存 PNG",
        { it.savePng }, { f, v -> f.copy(savePng = v) },
    ),
    ALSO_COMPARE(
        "yishun_also_compare", YishunFlagGroup.EXPORT, "只存成片", "同存对比图",
        { it.alsoCompare }, { f, v -> f.copy(alsoCompare = v) },
    ),
    SAVE_NEXT(
        "yishun_savenext", YishunFlagGroup.EXPORT, "存后停留", "存后下一张",
        { it.saveNext }, { f, v -> f.copy(saveNext = v) },
    ),
    SHARE_AFTER(
        "yishun_share_after", YishunFlagGroup.EXPORT, "存后不分享", "存后分享",
        { it.shareAfter }, { f, v -> f.copy(shareAfter = v) },
    ),
    ;

    /** 当前这一组里该不该出现（[onlyWhen] 不满足就整格藏掉，不是灰掉）。 */
    fun visibleIn(flags: YishunFlags): Boolean = onlyWhen(flags)

    fun label(flags: YishunFlags): String = if (get(flags)) labelOn else labelOff

    companion object {
        fun inGroup(group: YishunFlagGroup): List<YishunFlag> = entries.filter { it.group == group }
    }
}

/**
 * 只放**落盘的显示/导出偏好**。会话级状态（如分屏模式、看原图）不在这里：
 * 那些重开一张图就该回零，存进 prefs 反而会把上一次的模式带进下一张。
 */
data class YishunFlags(
    val onlyPins: Boolean = false,
    val onlyRecent: Boolean = false,
    val preferLast: Boolean = false,
    val lockLook: Boolean = false,
    val chipNames: Boolean = true,
    val chipSort: Boolean = false,
    val chipRev: Boolean = false,
    val splitVert: Boolean = false,
    val splitFlip: Boolean = false,
    val splitRed: Boolean = false,
    val previewFill: Boolean = false,
    val showHist: Boolean = true,
    val histRgb: Boolean = false,
    val blink: Boolean = false,
    val showTune: Boolean = false,
    val lockInt: Boolean = false,
    val savePng: Boolean = false,
    val alsoCompare: Boolean = false,
    val saveNext: Boolean = false,
    val shareAfter: Boolean = false,
) {
    companion object {
        fun load(prefs: SharedPreferences): YishunFlags =
            YishunFlag.entries.fold(YishunFlags()) { acc, f -> f.with(acc, prefs.getBoolean(f.prefKey, f.get(YishunFlags()))) }
    }
}

fun YishunFlags.toggle(f: YishunFlag): YishunFlags = f.with(this, !f.get(this))

/** 落盘全部开关（一次 edit/apply，别在循环里逐条 apply）。 */
fun YishunFlags.saveTo(prefs: SharedPreferences) {
    val editor = prefs.edit()
    YishunFlag.entries.forEach { editor.putBoolean(it.prefKey, it.get(this)) }
    editor.apply()
}
