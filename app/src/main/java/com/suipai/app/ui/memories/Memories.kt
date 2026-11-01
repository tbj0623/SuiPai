package com.suipai.app.ui.memories

import androidx.compose.runtime.Immutable
import com.suipai.app.data.PhotoItem
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 回忆卡片的种类。界面按 kind 决定封面比例与那行小字，测试按 kind 断言"该出的出了、不该出的没出"。 */
enum class MemoryKind {
    /** 历年同月同日（含今年的今天）。 */
    ON_THIS_DAY,

    /** 最近 7 天。 */
    LAST_WEEK,

    /** 当月。 */
    THIS_MONTH,

    /** 去年 6-8 月。 */
    LAST_SUMMER,

    /** 第一次拍的那天。 */
    FIRST_DAY,

    /** 一年前的今天。与 [ON_THIS_DAY] 撞了会被合并掉，不会单独出现。 */
    YEAR_AGO,
}

/**
 * 一张回忆卡。
 *
 * `id` 由 kind + 覆盖范围生成，**同一批照片重复算得到同一个 id**（列表 key 用它，
 * 否则每进一次回忆页整屏卡片重建一次）。
 * `coverPhotoId` 指向 `photoIds` 里的某一张 —— 测试钉住这一点，界面才好直接按 id 找封面。
 */
@Immutable
data class Memory(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverPhotoId: Long,
    val photoIds: List<Long>,
    val kind: MemoryKind,
) {
    val count: Int get() = photoIds.size
}

/**
 * 聚合层的输入：只要 **id + 拍摄时间**。
 *
 * 单独立这个类不是为了好看 —— `PhotoItem` 里有个 `android.net.Uri`，JVM 单测造不出来
 * （`Uri.parse` 在 stub android.jar 里返回 null，而构造参数是非空的）。
 * 把规则收在纯表上，跨年、闰日、空库这些情况才能在普通 JUnit 里一条断言钉死，不用起模拟器。
 */
@Immutable
data class DatedShot(val id: Long, val dateTaken: Long)

/**
 * 回忆的聚合规则。**纯 Kotlin + 注入时钟**，一行 `System.currentTimeMillis()` 都没有：
 * 所有"今天"都从参数来，所以跨年、闰日、空库这些情况在 JVM 上一条 `assertEquals` 就能钉死。
 *
 * 时间口径：
 * - 一切按**本地时区**的日历日归属（`dateTaken` 是 UTC 毫秒，落到 `ZoneId` 再取 `LocalDate`）；
 * - `dateTaken <= 0` 的照片（MediaStore 里 DATE_TAKEN 缺列时会被退回 0）**不参与任何卡片**：
 *   它们没有可信的日期，塞进"某年今天"就是造一条假回忆。
 *
 * 出卡顺序是固定的六档（某年今天、一年前的今天、最近一周、这个月、去年夏天、第一次拍的那天），
 * 界面按这个顺序铺，测试也按这个顺序断言 —— 顺序固定，才不会因为照片集合里 hash 顺序变化而抖动。
 */
object Memories {

    /** 「最近一周」的天数（含今天）。 */
    const val RECENT_DAYS: Long = 7L

    /** 夏天的月份区间：6、7、8 月。 */
    val SUMMER_MONTHS: Set<Int> = setOf(6, 7, 8)

    /** 卡片最多带多少张照片：240 张的回忆详情页会把内存和横滑手势一起拖垮。 */
    const val MAX_PHOTOS_PER_MEMORY: Int = 240

    /**
     * 从整库照片生成卡片。
     *
     * @param photos 任意顺序都行（内部按 `dateTaken` 降序排一遍再算）
     * @param nowMillis 注入的"现在"
     * @param zone 日历日归属用的时区（默认系统时区；测试传固定时区）
     */
    fun build(
        photos: List<PhotoItem>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxPhotos: Int = MAX_PHOTOS_PER_MEMORY,
    ): List<Memory> = buildFrom(
        shots = photos.map { DatedShot(it.id, it.dateTaken) },
        nowMillis = nowMillis,
        zone = zone,
        maxPhotos = maxPhotos,
    )

    /**
     * 规则本体（整库入口 [build] 只做一次类型转换，规则全在这里）。
     *
     * @param shots 任意顺序都行（内部按 `dateTaken` 降序排一遍再算）
     * @param nowMillis 注入的"现在"
     * @param zone 日历日归属用的时区（默认系统时区；测试传固定时区）
     */
    fun buildFrom(
        shots: List<DatedShot>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxPhotos: Int = MAX_PHOTOS_PER_MEMORY,
    ): List<Memory> {
        if (shots.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        // 只留日期可信的，按时间倒序（封面取最新那张，卡内也是新→旧）。
        val dated = shots
            .filter { it.dateTaken > 0L }
            .sortedByDescending { it.dateTaken }
        if (dated.isEmpty()) return emptyList()
        val byDay: List<Pair<DatedShot, LocalDate>> = dated.map { it to dayOf(it.dateTaken, zone) }
        val limit = maxPhotos.coerceIn(1, MAX_PHOTOS_PER_MEMORY)

        val out = ArrayList<Memory>(6)

        // ── 某年今天：历年同月同日。今天本身也算一张卡（"今年的今天"），
        //    所以只要库里有"今天"的照片，这条就一定成立。
        val sameDay = byDay.filter { (_, d) -> d.month == today.month && d.dayOfMonth == today.dayOfMonth }
        if (sameDay.isNotEmpty()) {
            val years = sameDay.map { it.second.year }.distinct().sortedDescending()
            out += card(
                kind = MemoryKind.ON_THIS_DAY,
                title = "某年今天",
                subtitle = years.joinToString(" · ") { "$it 年" } + "的 ${today.monthValue} 月 ${today.dayOfMonth} 日",
                items = sameDay.map { it.first },
                limit = limit,
                idTag = "${today.monthValue}-${today.dayOfMonth}",
            )
        }

        // ── 一年前的今天：**只在"某年今天"没有覆盖到去年那一天**时才单独成卡。
        //    这就是需求里那句"与某年今天重复则合并"——合并的判据是日期相同，不是标题相同。
        val yearAgo = today.minusYears(1)
        val yearAgoShots = byDay.filter { (_, day) -> day == yearAgo }
        val sameDayCoversYearAgo = sameDay.any { it.second == yearAgo }
        if (yearAgoShots.isNotEmpty() && !sameDayCoversYearAgo) {
            out += card(
                kind = MemoryKind.YEAR_AGO,
                title = "一年前的今天",
                subtitle = "${yearAgo.year} 年 ${yearAgo.monthValue} 月 ${yearAgo.dayOfMonth} 日",
                items = yearAgoShots.map { it.first },
                limit = limit,
                idTag = yearAgo.toString(),
            )
        }

        // ── 最近一周：today-6 .. today（含今天）。用"日历日差 0..6"而不是毫秒差，
        //    这样夏令时切的那天不会把某张照片挤出窗口或算进来两次。
        val weekStart = today.minusDays(RECENT_DAYS - 1)
        val recent = byDay.filter { (_, day) ->
            !day.isBefore(weekStart) && !day.isAfter(today)
        }
        if (recent.isNotEmpty()) {
            out += card(
                kind = MemoryKind.LAST_WEEK,
                title = "最近一周",
                subtitle = "${recent.map { it.second }.distinct().size} 天 · 第 ${weekStart.monthValue} 月 ${weekStart.dayOfMonth} 日 起",
                items = recent.map { it.first },
                limit = limit,
                idTag = weekStart.toString(),
            )
        }

        // ── 这个月：当月 1 日 .. 月末（月末取 YearMonth 的，闰年 2 月就是 29 天）。
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        val thisMonth = byDay.filter { (_, day) -> !day.isBefore(monthStart) && !day.isAfter(monthEnd) }
        if (thisMonth.isNotEmpty()) {
            out += card(
                kind = MemoryKind.THIS_MONTH,
                title = "这个月",
                subtitle = "${today.year} 年 ${today.monthValue} 月 · ${thisMonth.map { it.second }.distinct().size} 天",
                items = thisMonth.map { it.first },
                limit = limit,
                idTag = "${today.year}-${today.monthValue}",
            )
        }

        // ── 去年夏天：去年的 6 月 1 日 .. 8 月 31 日。
        //    闰年 2 月 29 日跟这一条无关，但"去年"本身可能是闰年，减年要落在日历上，
        //    所以用 LocalDate.minusYears（2/29 → 2/28，java.time 自己会处理，不抛异常）。
        val lastSummerStart = LocalDate.of(today.year - 1, 6, 1)
        val lastSummerEnd = LocalDate.of(today.year - 1, 8, 31)
        val summer = byDay.filter { (_, day) ->
            !day.isBefore(lastSummerStart) && !day.isAfter(lastSummerEnd)
        }
        if (summer.isNotEmpty()) {
            out += card(
                kind = MemoryKind.LAST_SUMMER,
                title = "去年夏天",
                subtitle = "${today.year - 1} 年 6-8 月 · ${summer.map { it.second }.distinct().size} 天",
                items = summer.map { it.first },
                limit = limit,
                idTag = "${today.year - 1}-summer",
            )
        }

        // ── 第一次拍的那天：整库最早的**日历日**，把那天所有照片都带上。
        val firstDay = byDay.minByOrNull { it.second }?.second
        if (firstDay != null) {
            val items = byDay.filter { (_, day) -> day == firstDay }.map { it.first }
            val yearsAgo = ChronoUnit.YEARS.between(firstDay, today)
            out += card(
                kind = MemoryKind.FIRST_DAY,
                title = "第一次拍的那天",
                subtitle = buildString {
                    append(firstDay.year)
                    append(" 年 ")
                    append(firstDay.monthValue)
                    append(" 月 ")
                    append(firstDay.dayOfMonth)
                    append(" 日")
                    if (yearsAgo > 0) {
                        append(" · 已经过去 ")
                        append(yearsAgo)
                        append(" 年")
                    }
                },
                items = items,
                limit = limit,
                idTag = firstDay.toString(),
            )
        }
        return out
    }

    /**
     * 某一档"这一天归不归它"。
     *
     * 界面用它给卡片兜底（例如详情页显示"这一天还在不在窗口里"），测试用它反向核对
     * `build` 的筛选 —— 两处如果各写一遍窗口边界，迟早会有一处先改。
     * [MemoryKind.FIRST_DAY] 没有可判的窗口（它由整库最早那天决定），返回 false。
     */
    fun covers(kind: MemoryKind, day: LocalDate, today: LocalDate): Boolean = when (kind) {
        MemoryKind.ON_THIS_DAY -> day.month == today.month && day.dayOfMonth == today.dayOfMonth
        MemoryKind.YEAR_AGO -> day == today.minusYears(1)
        MemoryKind.LAST_WEEK -> !day.isBefore(today.minusDays(RECENT_DAYS - 1)) && !day.isAfter(today)
        MemoryKind.THIS_MONTH -> YearMonth.from(day) == YearMonth.from(today)
        MemoryKind.LAST_SUMMER -> day.year == today.year - 1 && day.monthValue in SUMMER_MONTHS
        MemoryKind.FIRST_DAY -> false
    }

    private fun card(
        kind: MemoryKind,
        title: String,
        subtitle: String,
        items: List<DatedShot>,
        limit: Int,
        idTag: String,
    ): Memory {
        val trimmed = items.take(limit)
        val ids = trimmed.map { it.id }
        return Memory(
            id = "${kind.name}:$idTag",
            title = title,
            subtitle = subtitle,
            // 封面取这张卡里最新的一张（items 已经是时间倒序），并且一定在 photoIds 里。
            coverPhotoId = ids.first(),
            photoIds = ids,
            kind = kind,
        )
    }

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
