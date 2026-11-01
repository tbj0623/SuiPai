package com.suipai.app.ui.gallery

import androidx.compose.runtime.Immutable
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 相册列表的「按最近时间筛」预设。
 *
 * 原来这里是 17 个布尔 + 17 段几乎一样的 `if (!onlyX) list else list.filter { ... }`，
 * 一共 230 行，改一个口径要动 17 处；而且它们全是 AND 关系（交集），
 * 顺序根本不影响结果——所以一张表 + 一次 fold 就够了。
 *
 * 顺手修掉一处口径错：老的窗口 filter 只有下界（`lastTaken >= 段首`），
 * 于是「仅上周」会把本周的相册也算进来、「仅去年」会连今年的一起显示。
 * 现在每段都是左闭右开的完整区间，和格子上写的话一致。
 *
 * @param back 往前数几个单位：0 = 本周/本月/今年/今天
 */
enum class AlbumUnit { DAY, WEEK, MONTH, YEAR }

enum class AlbumWindow(
    val prefKey: String,
    val labelOff: String,
    val labelOn: String,
    val empty: String,
    val unit: AlbumUnit,
    val back: Int,
) {
    TODAY("album_today", "含往日", "仅今天", "今天没有新照片的相册", AlbumUnit.DAY, 0),
    YESTERDAY("album_yest", "含非昨", "仅昨天", "昨天没有新照片的相册", AlbumUnit.DAY, 1),
    TWO_AGO("album_ago", "含非前", "仅前天", "前天没有新照片的相册", AlbumUnit.DAY, 2),
    THREE_AGO("album_three", "含非大前", "仅大前天", "大前天没有新照片的相册", AlbumUnit.DAY, 3),
    THIS_WEEK("album_week", "含上周", "仅本周", "本周没有新照片的相册", AlbumUnit.WEEK, 0),
    LAST_WEEK("album_last_week", "含非上周", "仅上周", "上周没有新照片的相册", AlbumUnit.WEEK, 1),
    TWO_WEEKS_AGO("album_two_weeks", "含非上上周", "仅上上周", "上上周没有新照片的相册", AlbumUnit.WEEK, 2),
    THIS_MONTH("album_month", "含旧册", "仅本月", "本月没有新照片的相册", AlbumUnit.MONTH, 0),
    LAST_MONTH("album_last_month", "含非上月", "仅上月", "上个月没有新照片的相册", AlbumUnit.MONTH, 1),
    THIS_YEAR("album_year", "含往年", "仅今年", "今年没有新照片的相册", AlbumUnit.YEAR, 0),
    LAST_YEAR("album_last_year", "含非去年", "仅去年册", "去年没有新照片的相册", AlbumUnit.YEAR, 1),
    YEAR_BEFORE("album_year_before", "含非前年", "仅前年册", "前年没有新照片的相册", AlbumUnit.YEAR, 2),
    THREE_YEARS_AGO("album_three_years", "含非大前年", "仅大前年", "大前年没有新照片的相册", AlbumUnit.YEAR, 3),
    FOUR_YEARS_AGO("album_four_years", "含非四年", "仅四年前", "四年前没有新照片的相册", AlbumUnit.YEAR, 4),
    FIVE_YEARS_AGO("album_five_years", "含非五年", "仅五年前", "五年前没有新照片的相册", AlbumUnit.YEAR, 5),
    SIX_YEARS_AGO("album_six_years", "含非六年", "仅六年前", "六年前没有新照片的相册", AlbumUnit.YEAR, 6),
    SEVEN_YEARS_AGO("album_seven_years", "含非七年", "仅七年前", "七年前没有新照片的相册", AlbumUnit.YEAR, 7),
    ;

    /** 这个预设覆盖的时间区间（左闭右开，epoch ms）。 */
    fun range(zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val today = LocalDate.now(zone)
        val startDay = when (unit) {
            AlbumUnit.DAY -> today.minusDays(back.toLong())
            AlbumUnit.WEEK -> today.with(DayOfWeek.MONDAY).minusWeeks(back.toLong())
            AlbumUnit.MONTH -> today.withDayOfMonth(1).minusMonths(back.toLong())
            AlbumUnit.YEAR -> today.withDayOfYear(1).minusYears(back.toLong())
        }
        val endDay = when (unit) {
            AlbumUnit.DAY -> startDay.plusDays(1)
            AlbumUnit.WEEK -> startDay.plusWeeks(1)
            AlbumUnit.MONTH -> startDay.plusMonths(1)
            AlbumUnit.YEAR -> startDay.plusYears(1)
        }
        val from = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = endDay.atStartOfDay(zone).toInstant().toEpochMilli()
        return from..(until - 1)
    }

    companion object {
        /** 读当前勾了哪些窗口；按声明顺序返回，界面文案与提示都跟着这个顺序。 */
        fun active(prefs: SharedPreferences): List<AlbumWindow> =
            values().filter { prefs.getBoolean(it.prefKey, false) }
    }
}

enum class AlbumFlagGroup(val title: String, val hint: String) {
    SCOPE("看哪些相册", "只影响列表，不动手机里的文件"),
    LOOK("封面与排布", "封面大小、圆角、排序"),
}

/**
 * 相册列表的布尔开关，一张表（与一瞬/相册/相机同一做法）：
 * 界面从表生成，落盘 key 与默认值一字未改。
 */
enum class AlbumFlag(
    val prefKey: String,
    val group: AlbumFlagGroup,
    val labelOff: String,
    val labelOn: String,
    val def: Boolean,
    val get: (AlbumFlags) -> Boolean,
    val with: (AlbumFlags, Boolean) -> AlbumFlags,
) {
    HIDE_SHOTS(
        "album_hide_shots", AlbumFlagGroup.SCOPE, "含截图册", "藏截图册", false,
        { it.hideShots }, { f, v -> f.copy(hideShots = v) },
    ),
    ONLY_SUIPAI(
        "album_suipai", AlbumFlagGroup.SCOPE, "全部册", "仅随拍册", false,
        { it.onlySuiPai }, { f, v -> f.copy(onlySuiPai = v) },
    ),
    HIDE_SOCIAL(
        "album_hide_social", AlbumFlagGroup.SCOPE, "含社交册", "藏社交册", false,
        { it.hideSocial }, { f, v -> f.copy(hideSocial = v) },
    ),
    HIDE_DOWNLOAD(
        "album_hide_dl", AlbumFlagGroup.SCOPE, "含下载册", "藏下载册", false,
        { it.hideDownload }, { f, v -> f.copy(hideDownload = v) },
    ),
    ONLY_PINS(
        "album_only_pins", AlbumFlagGroup.SCOPE, "含未钉", "只看钉住", false,
        { it.onlyPins }, { f, v -> f.copy(onlyPins = v) },
    ),
    HIDE_MODE(
        "album_hide_mode", AlbumFlagGroup.SCOPE, "整理藏册", "完成藏册", false,
        { it.hideMode }, { f, v -> f.copy(hideMode = v) },
    ),
    ROUND_COVER(
        "album_round", AlbumFlagGroup.LOOK, "方封面", "圆封面", true,
        { it.roundCover }, { f, v -> f.copy(roundCover = v) },
    ),
    ;

    companion object {
        fun inGroup(group: AlbumFlagGroup): List<AlbumFlag> = values().filter { it.group == group }
    }
}

/** 不可变的开关集合：一个状态替掉七个 `by rememberSaveable`。 */
@Immutable
data class AlbumFlags(
    val hideShots: Boolean = false,
    val onlySuiPai: Boolean = false,
    val hideSocial: Boolean = false,
    val hideDownload: Boolean = false,
    val onlyPins: Boolean = false,
    val hideMode: Boolean = false,
    val roundCover: Boolean = true,
) {
    fun get(f: AlbumFlag): Boolean = f.get(this)

    fun toggled(f: AlbumFlag): AlbumFlags = f.with(this, !f.get(this))

    companion object {
        fun load(prefs: SharedPreferences): AlbumFlags {
            var flags = AlbumFlags()
            AlbumFlag.values().forEach { f -> flags = f.with(flags, prefs.getBoolean(f.prefKey, f.def)) }
            return flags
        }
    }
}

fun AlbumFlags.saveTo(prefs: SharedPreferences) {
    prefs.edit().apply {
        AlbumFlag.values().forEach { putBoolean(it.prefKey, it.get(this@saveTo)) }
    }.apply()
}
