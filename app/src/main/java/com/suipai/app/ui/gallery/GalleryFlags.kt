package com.suipai.app.ui.gallery

import androidx.compose.runtime.Immutable
import android.content.SharedPreferences
import androidx.compose.runtime.Composable

/**
 * 相册里所有「记住上次怎么看」的布尔开关，收成一张表。
 *
 * 做法与动机和 `YishunFlags` 完全一样（那份的注释里有完整理由）：
 * 原来这 29 个开关是 29 个 `by remember` 委托属性 + 29 段手写落盘，
 * 而且 29 个按钮全塞在同一条 `horizontalScroll` 行里——实测这条行有 41 个子节点，
 * 滚出视野的按钮不在语义树里，人和自动化都找不到它们。
 * 现在：委托属性 1 个、界面从表生成、落盘格式一字未改（仍是一开关一个 boolean key）。
 */
enum class GalleryFlagGroup(val title: String) {
    HIDE("隐藏这些"),
    GRID("网格外观"),
    TAP("点按行为"),
}

/**
 * @param labelOff 关着的时候显示什么（「含截图」——说明现在还没藏起来）
 * @param labelOn 开着的时候显示什么（「藏截图」——说明点它会取消隐藏）
 */
enum class GalleryFlag(
    val prefKey: String,
    val group: GalleryFlagGroup,
    val labelOff: String,
    val labelOn: String,
    val get: (GalleryFlags) -> Boolean,
    val with: (GalleryFlags, Boolean) -> GalleryFlags,
) {
    HIDE_SHOTS(
        "hide_screenshots", GalleryFlagGroup.HIDE, "含截图", "藏截图",
        { it.hideShots }, { f, v -> f.copy(hideShots = v) },
    ),
    HIDE_COPIES(
        "hide_copies", GalleryFlagGroup.HIDE, "含副本", "藏副本",
        { it.hideCopies }, { f, v -> f.copy(hideCopies = v) },
    ),
    HIDE_EDITS(
        "hide_edits", GalleryFlagGroup.HIDE, "含裁切件", "藏裁切件",
        { it.hideEdits }, { f, v -> f.copy(hideEdits = v) },
    ),
    HIDE_YISHUN(
        "hide_yishun", GalleryFlagGroup.HIDE, "含一瞬", "藏一瞬",
        { it.hideYishun }, { f, v -> f.copy(hideYishun = v) },
    ),
    HIDE_COMPARE(
        "hide_compare", GalleryFlagGroup.HIDE, "含对比", "藏对比",
        { it.hideCompare }, { f, v -> f.copy(hideCompare = v) },
    ),
    HIDE_TURNED(
        "hide_turned", GalleryFlagGroup.HIDE, "含转向", "藏转向",
        { it.hideTurned }, { f, v -> f.copy(hideTurned = v) },
    ),
    HIDE_WIDE(
        "hide_wide", GalleryFlagGroup.HIDE, "含宽银幕", "藏宽银幕",
        { it.hideWide }, { f, v -> f.copy(hideWide = v) },
    ),
    HIDE_POSTCARD(
        "hide_postcard", GalleryFlagGroup.HIDE, "含明信片", "藏明信片",
        { it.hidePostcard }, { f, v -> f.copy(hidePostcard = v) },
    ),
    // 裁切家族里 239/53 各有开关，黄金裁与学院裁原来**没有**：
    // 「隐藏这些」这一组对它们的覆盖是漏的（只看得到、藏不起来）。补在两处
    // 相邻位置，让裁切类开关排在一起；新增一行就自动进 HIDE 组、
    // 自动被「全部清空」的 fold 清到（那张表就是为这个改的）。
    HIDE_GOLDEN_CROP(
        "hide_golden_crop", GalleryFlagGroup.HIDE, "含黄金裁", "藏黄金裁",
        { it.hideGoldenCrop }, { f, v -> f.copy(hideGoldenCrop = v) },
    ),
    HIDE_ACADEMY_CROP(
        "hide_academy_crop", GalleryFlagGroup.HIDE, "含学院裁", "藏学院裁",
        { it.hideAcademy }, { f, v -> f.copy(hideAcademy = v) },
    ),
    HIDE_MIRROR(
        "hide_mirror", GalleryFlagGroup.HIDE, "含镜像", "藏镜像",
        { it.hideMirror }, { f, v -> f.copy(hideMirror = v) },
    ),
    HIDE_GRAY(
        "hide_gray", GalleryFlagGroup.HIDE, "含灰度", "藏灰度",
        { it.hideGray }, { f, v -> f.copy(hideGray = v) },
    ),
    HIDE_FLIP(
        "hide_flip", GalleryFlagGroup.HIDE, "含上下翻", "藏上下翻",
        { it.hideFlip }, { f, v -> f.copy(hideFlip = v) },
    ),
    HIDE_ROUND(
        "hide_round", GalleryFlagGroup.HIDE, "含圆角", "藏圆角",
        { it.hideRound }, { f, v -> f.copy(hideRound = v) },
    ),
    HIDE2K(
        "hide_2k", GalleryFlagGroup.HIDE, "含2K", "藏2K",
        { it.hide2k }, { f, v -> f.copy(hide2k = v) },
    ),
    HIDE1080(
        "hide_1080", GalleryFlagGroup.HIDE, "含1080", "藏1080",
        { it.hide1080 }, { f, v -> f.copy(hide1080 = v) },
    ),
    HIDE720(
        "hide_720", GalleryFlagGroup.HIDE, "含720", "藏720",
        { it.hide720 }, { f, v -> f.copy(hide720 = v) },
    ),
    HIDE480(
        "hide_480", GalleryFlagGroup.HIDE, "含480", "藏480",
        { it.hide480 }, { f, v -> f.copy(hide480 = v) },
    ),
    HIDE320(
        "hide_320", GalleryFlagGroup.HIDE, "含320", "藏320",
        { it.hide320 }, { f, v -> f.copy(hide320 = v) },
    ),
    HIDE240(
        "hide_240", GalleryFlagGroup.HIDE, "含240", "藏240",
        { it.hide240 }, { f, v -> f.copy(hide240 = v) },
    ),
    HIDE160(
        "hide_160", GalleryFlagGroup.HIDE, "含160", "藏160",
        { it.hide160 }, { f, v -> f.copy(hide160 = v) },
    ),
    HIDE120(
        "hide_120", GalleryFlagGroup.HIDE, "含120", "藏120",
        { it.hide120 }, { f, v -> f.copy(hide120 = v) },
    ),
    HIDE96(
        "hide_96", GalleryFlagGroup.HIDE, "含96", "藏96",
        { it.hide96 }, { f, v -> f.copy(hide96 = v) },
    ),
    HIDE64(
        "hide_64", GalleryFlagGroup.HIDE, "含64", "藏64",
        { it.hide64 }, { f, v -> f.copy(hide64 = v) },
    ),
    HIDE48(
        "hide_48", GalleryFlagGroup.HIDE, "含48", "藏48",
        { it.hide48 }, { f, v -> f.copy(hide48 = v) },
    ),
    HIDE32(
        "hide_32", GalleryFlagGroup.HIDE, "含32", "藏32",
        { it.hide32 }, { f, v -> f.copy(hide32 = v) },
    ),
    HIDE24(
        "hide_24", GalleryFlagGroup.HIDE, "含24", "藏24",
        { it.hide24 }, { f, v -> f.copy(hide24 = v) },
    ),
    HIDE16(
        "hide_16", GalleryFlagGroup.HIDE, "含16", "藏16",
        { it.hide16 }, { f, v -> f.copy(hide16 = v) },
    ),
    NATIVE_ASPECT(
        "grid_native", GalleryFlagGroup.GRID, "方格", "原比例",
        { it.nativeAspect }, { f, v -> f.copy(nativeAspect = v) },
    ),
    ROUND_THUMBS(
        "round_thumbs", GalleryFlagGroup.GRID, "直角", "圆角",
        { it.roundThumbs }, { f, v -> f.copy(roundThumbs = v) },
    ),
    PEEK_MODE(
        "peek_mode", GalleryFlagGroup.TAP, "点按打开", "按住预览",
        { it.peekMode }, { f, v -> f.copy(peekMode = v) },
    ),
    ;

    fun label(flags: GalleryFlags): String = if (get(flags)) labelOn else labelOff

    companion object {
        fun inGroup(group: GalleryFlagGroup): List<GalleryFlag> = entries.filter { it.group == group }
    }
}

@Immutable
data class GalleryFlags(
    val hideShots: Boolean = false,
    val hideCopies: Boolean = false,
    val hideEdits: Boolean = false,
    val hideYishun: Boolean = false,
    val hideCompare: Boolean = false,
    val hideTurned: Boolean = false,
    val hideWide: Boolean = false,
    val hidePostcard: Boolean = false,
    val hideGoldenCrop: Boolean = false,
    val hideAcademy: Boolean = false,
    val hideMirror: Boolean = false,
    val hideGray: Boolean = false,
    val hideFlip: Boolean = false,
    val hideRound: Boolean = false,
    val hide2k: Boolean = false,
    val hide1080: Boolean = false,
    val hide720: Boolean = false,
    val hide480: Boolean = false,
    val hide320: Boolean = false,
    val hide240: Boolean = false,
    val hide160: Boolean = false,
    val hide120: Boolean = false,
    val hide96: Boolean = false,
    val hide64: Boolean = false,
    val hide48: Boolean = false,
    val hide32: Boolean = false,
    val hide24: Boolean = false,
    val hide16: Boolean = false,
    val nativeAspect: Boolean = false,
    val roundThumbs: Boolean = false,
    val peekMode: Boolean = false,
) {
    companion object {
        fun load(prefs: SharedPreferences): GalleryFlags =
            GalleryFlag.entries.fold(GalleryFlags()) { acc, f -> f.with(acc, prefs.getBoolean(f.prefKey, f.get(GalleryFlags()))) }
    }
}

fun GalleryFlags.toggle(f: GalleryFlag): GalleryFlags = f.with(this, !f.get(this))

/** 落盘全部开关（一次 edit/apply）。 */
fun GalleryFlags.saveTo(prefs: SharedPreferences) {
    val editor = prefs.edit()
    GalleryFlag.entries.forEach { editor.putBoolean(it.prefKey, it.get(this)) }
    editor.apply()
}

/** 一组开关的格子，整组由 [GalleryFlag] 表生成。 */
@Composable
fun GalleryFlagChips(
    group: GalleryFlagGroup,
    flags: GalleryFlags,
    onToggle: (GalleryFlag) -> Unit,
) {
    val shown = GalleryFlag.inGroup(group)
    if (shown.isEmpty()) return
    com.suipai.app.ui.common.AppChipFlow {
        shown.forEach { f ->
            com.suipai.app.ui.common.AppBarCell(label = f.label(flags), on = f.get(flags), onClick = { onToggle(f) })
        }
    }
}
