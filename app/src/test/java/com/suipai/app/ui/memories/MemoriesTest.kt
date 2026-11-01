package com.suipai.app.ui.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 回忆聚合规则。
 *
 * 这一层的错法全都是"**看起来对**"的那一类：跨年那天少一张、闰日那天多出一张
 * "一年前的今天"、空库时冒出一张 0 张照片的卡。它们编译得过、界面也不崩，
 * 只是年份悄悄差一年 —— 而回忆这个功能唯一的价值就是日期准。
 *
 * 所以全部用**注入时钟 + 固定时区**，一条断言钉一个日历事实。
 * 每条判定都配了自检：把规则换成"错的那个写法"，测试必须变红。
 */
class MemoriesTest {

    companion object {
        /** 固定时区：+08:00，没有夏令时，日历日归属不会因为机器时区变。 */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 某天正午的毫秒（正午是为了避开跨日边界，免得"日期对了时刻差一小时"这类假阳性）。 */
        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 12, 0).atZone(ZONE).toInstant().toEpochMilli()

        fun shot(id: Long, year: Int, month: Int, day: Int): DatedShot = DatedShot(id, at(year, month, day))

        fun build(now: LocalDate, vararg shots: DatedShot): List<Memory> =
            Memories.buildFrom(shots.toList(), at(now.year, now.monthValue, now.dayOfMonth), ZONE)

        fun kinds(memories: List<Memory>): List<MemoryKind> = memories.map { it.kind }
    }

    // ── 1. 空库 ────────────────────────────────────────────────────────

    @Test
    fun emptyLibraryGivesNoCards() {
        assertEquals(emptyList<Memory>(), Memories.buildFrom(emptyList(), at(2026, 10, 18), ZONE))
        // 自检：有照片时必须出卡（否则上面那条对"永远返回空表"的实现也是绿的）
        assertTrue(
            "有照片却一张卡都不出，空库那条断言是死跑",
            build(LocalDate.of(2026, 10, 18), shot(1, 2026, 10, 18)).isNotEmpty(),
        )
    }

    @Test
    fun photosWithoutDateDoNotPretendToBeMemories() {
        // dateTaken = 0 是 MediaStore 缺列时的兜底值：它没有可信日期。
        val shots = listOf(DatedShot(1, 0L), DatedShot(2, -5L))
        assertEquals(
            "没有可信日期的照片被算进了回忆（会造出一条假卡片）",
            emptyList<Memory>(),
            Memories.buildFrom(shots, at(2026, 10, 18), ZONE),
        )
        // 自检：同一批里混一张有日期的，卡片就必须只带那一张
        val mixed = shots + shot(3, 2026, 10, 18)
        val cards = Memories.buildFrom(mixed, at(2026, 10, 18), ZONE)
        assertEquals(
            "没有日期的照片混进了卡片",
            emptySet<Long>(),
            cards.flatMap { it.photoIds }.filter { it == 1L || it == 2L }.toSet(),
        )
    }

    // ── 2. 只有一张 ────────────────────────────────────────────────────

    @Test
    fun onePhotoOnlyProducesCardsThatCanActuallyStand() {
        // 2026-10-18 拍的唯一一张：它同时是"今天""这个月的今天""第一次拍的那天"。
        val today = LocalDate.of(2026, 10, 18)
        val cards = build(today, shot(1, 2026, 10, 18))
        assertTrue("只有一张时连「某年今天」都没有", cards.any { it.kind == MemoryKind.ON_THIS_DAY })
        assertTrue("只有一张时没有「第一次拍的那天」", cards.any { it.kind == MemoryKind.FIRST_DAY })
        assertTrue("只有一张且就是今天，必须有「最近一周」", cards.any { it.kind == MemoryKind.LAST_WEEK })
        assertTrue("只有一张且就是当月，必须有「这个月」", cards.any { it.kind == MemoryKind.THIS_MONTH })
        assertFalse(
            "一张 2026 年的照片不可能有「去年夏天」",
            cards.any { it.kind == MemoryKind.LAST_SUMMER },
        )
        // 每张卡都必须有封面，且封面在自己的 photoIds 里
        for (card in cards) {
            assertTrue("卡片「${card.title}」没有照片却出现了", card.photoIds.isNotEmpty())
            assertTrue("卡片「${card.title}」的封面不在自己的照片里", card.coverPhotoId in card.photoIds)
        }
    }

    @Test
    fun oneOldPhotoDoesNotInventATodayCard() {
        // 唯一一张是去年夏天的：今天卡片一张都不该有，但「去年夏天」和「第一次」必须有。
        val cards = build(LocalDate.of(2026, 10, 18), shot(9, 2025, 7, 4))
        val kinds = kinds(cards)
        assertFalse("去年夏天的照片被算成了「某年今天」", MemoryKind.ON_THIS_DAY in kinds)
        assertFalse("去年夏天的照片被算成了「最近一周」", MemoryKind.LAST_WEEK in kinds)
        assertTrue("去年夏天的照片没有进「去年夏天」", MemoryKind.LAST_SUMMER in kinds)
        assertTrue("唯一一张照片没有「第一次拍的那天」", MemoryKind.FIRST_DAY in kinds)
    }

    // ── 3. 跨年 ────────────────────────────────────────────────────────

    @Test
    fun newYearsEveAndNewYearsDayAreHandledOnBothSides() {
        // 12 月 31 日：最近一周跨到上一年，窗口起点是 12-25，不许把 12-24 算进来。
        val dec31 = LocalDate.of(2026, 12, 31)
        val cards = build(
            dec31,
            shot(1, 2026, 12, 31),
            shot(2, 2026, 12, 25),
            shot(3, 2026, 12, 24),
            shot(4, 2025, 12, 31),
        )
        val week = cards.first { it.kind == MemoryKind.LAST_WEEK }
        assertTrue("窗口起点 12-25 那天没进最近一周", 2L in week.photoIds)
        assertFalse("12-24 在 7 天窗口之外，却被算进了最近一周", 3L in week.photoIds)
        assertTrue("今天的照片不在最近一周里", 1L in week.photoIds)
        // 去年 12-31 与今天同月同日 ⇒ 进「某年今天」，不进「最近一周」
        val onThisDay = cards.first { it.kind == MemoryKind.ON_THIS_DAY }
        assertTrue("去年 12-31 没进「某年今天」", 4L in onThisDay.photoIds)

        // 1 月 1 日：窗口起点落在上一年 12-26，跨年不许断档。
        val jan1 = LocalDate.of(2027, 1, 1)
        val yearEnd = build(jan1, shot(1, 2026, 12, 26), shot(2, 2026, 12, 27), shot(3, 2027, 1, 1))
        val week2 = yearEnd.first { it.kind == MemoryKind.LAST_WEEK }
        assertEquals("跨年的最近一周算错了张数", setOf(1L, 2L, 3L), week2.photoIds.toSet())
        assertTrue("1 月 1 日的「这个月」没出来", yearEnd.any { it.kind == MemoryKind.THIS_MONTH })
    }

    @Test
    fun thisMonthUsesTheCalendarMonthNotTheLastThirtyDays() {
        // 2026-03-15：这个月 = 3 月 1..31。2 月 28 日不算这个月，3 月 1 日算。
        //
        // 那个 3-31 的样本是**故意的**：相机时钟被调快过时，照片的 dateTaken 会落到
        // "未来的某一天"。3 月的照片就该在「这个月」里（副标题写的是"3 月"），
        // 不许因为"还没到那天"被悄悄丢掉 —— 丢了用户是找不到那张照片的。
        val cards = build(
            LocalDate.of(2026, 3, 15),
            shot(1, 2026, 3, 1),
            shot(2, 2026, 2, 28),
            shot(3, 2026, 3, 31),
        )
        val month = cards.first { it.kind == MemoryKind.THIS_MONTH }
        assertTrue("3 月 1 日不在「这个月」", 1L in month.photoIds)
        assertFalse("2 月 28 日被算进了 3 月", 2L in month.photoIds)
        assertTrue("时钟偏快留下的未来日期被「这个月」丢了", 3L in month.photoIds)
        assertEquals("3 月的「这个月」张数不对", 2, month.count)
    }

    // ── 4. 闰年 / 闰日 ────────────────────────────────────────────────

    @Test
    fun leapDayWasTheSameDayInAPreviousLeapYear() {
        // 今天 2028-02-29（闰日）。2024-02-29 是同月同日 ⇒ 进「某年今天」。
        val leapDay = LocalDate.of(2028, 2, 29)
        val cards = build(leapDay, shot(1, 2028, 2, 29), shot(2, 2024, 2, 29))
        val onThisDay = cards.firstOrNull { it.kind == MemoryKind.ON_THIS_DAY }
        assertTrue("闰日那天的「某年今天」没出来", onThisDay != null)
        assertEquals(
            "闰日的「某年今天」张数不对",
            setOf(1L, 2L),
            onThisDay!!.photoIds.toSet(),
        )
    }

    @Test
    fun nonLeapYearFeb29IsNotSilentlyDroppedOrFaked() {
        // 今天 2026-02-28（平年）。2024-02-29 是历史照片，但它**不是**"同月同日"，
        // 也**不是**"一年前的今天"（一年前是 2025-02-28，平年）。
        val today = LocalDate.of(2026, 2, 28)
        val cards = build(today, shot(1, 2024, 2, 29))
        val kinds = kinds(cards)
        assertFalse("平年的 2-28 把 2024-02-29 当成了同月同日", MemoryKind.ON_THIS_DAY in kinds)
        assertFalse("平年的 2-28 把 2024-02-29 当成了「一年前的今天」", MemoryKind.YEAR_AGO in kinds)
        assertTrue("这张 2024 年的照片至少要是「第一次拍的那天」", MemoryKind.FIRST_DAY in kinds)

        // 自检：判据不是"永远说否"—— 2026-02-28 拍的照片在那天就是「某年今天」
        val real = build(today, shot(1, 2026, 2, 28))
        assertTrue("今天的照片没进「某年今天」", real.any { it.kind == MemoryKind.ON_THIS_DAY })
    }

    @Test
    fun yearAgoUsesTheCalendarAndSurvivesTheLeapBoundary() {
        // 今天 2025-02-28：一年前 = 2024-02-28。2024-02-28 同时也是「某年今天」里的同月同日，
        // 所以那张卡被合并掉 —— 这正是需求里"重复则合并"的那一路（合并判据是**日期**）。
        val merged = build(LocalDate.of(2025, 2, 28), shot(1, 2024, 2, 28), shot(2, 2024, 2, 29))
        assertEquals(
            "同月同日已经覆盖了去年那天，却还是单独出了一张「一年前的今天」",
            0,
            merged.count { it.kind == MemoryKind.YEAR_AGO },
        )
        val onThisDay = merged.first { it.kind == MemoryKind.ON_THIS_DAY }
        assertTrue("2024-02-28 没进「某年今天」", 1L in onThisDay.photoIds)
        assertFalse("2024-02-29 被算成了 2-28 的同月同日", 2L in onThisDay.photoIds)

        // 只有闰日会让"一年前"落在**另一个月日**上：今天 2028-02-29，一年前 = 2027-02-28。
        // 那时「某年今天」（2-29）成立不了，而 2027-02-28 那张确实是一年前的今天 ⇒ 单独成卡。
        val standalone = build(LocalDate.of(2028, 2, 29), shot(3, 2027, 2, 28), shot(4, 2028, 2, 29))
        val yearAgo = standalone.firstOrNull { it.kind == MemoryKind.YEAR_AGO }
        assertTrue("闰日那天，「一年前的今天」没出来", yearAgo != null)
        assertTrue("2027-02-28 没进「一年前的今天」", 3L in yearAgo!!.photoIds)
        assertFalse("今天的照片混进了「一年前的今天」", 4L in yearAgo.photoIds)
    }

    // ── 5. 同月同日多张 / 合并 ────────────────────────────────────────

    @Test
    fun sameMonthDayFromManyYearsCollapseIntoOneCard() {
        val cards = build(
            LocalDate.of(2026, 10, 18),
            shot(1, 2026, 10, 18),
            shot(2, 2024, 10, 18),
            shot(3, 2021, 10, 18),
        )
        val onThisDay = cards.filter { it.kind == MemoryKind.ON_THIS_DAY }
        assertEquals("同月同日分散成了多张卡", 1, onThisDay.size)
        assertEquals("同月同日没把三年的照片都带上", 3, onThisDay.first().count)
        assertTrue("副标题没写年份", onThisDay.first().subtitle.contains("2026") && onThisDay.first().subtitle.contains("2021"))
    }

    @Test
    fun yearAgoCardIsMergedIntoOnThisDayWhenTheyOverlap() {
        // 今天 2026-07-04，2019/2024/2025 的 7-4 都有照片。
        // 2025-07-04 = 一年前 ⇒ 已经被「某年今天」覆盖，不许再单独成一张。
        val cards = build(
            LocalDate.of(2026, 7, 4),
            shot(1, 2026, 7, 4),
            shot(2, 2025, 7, 4),
            shot(3, 2024, 7, 4),
            shot(4, 2019, 7, 4),
        )
        val yearAgoCards = cards.filter { it.kind == MemoryKind.YEAR_AGO }
        assertEquals("「一年前的今天」没有并进「某年今天」", 0, yearAgoCards.size)
        assertEquals(
            "合并之后「某年今天」应当带上全部四张",
            4,
            cards.first { it.kind == MemoryKind.ON_THIS_DAY }.count,
        )

        // 自检：换一个"去年那天没有照片"的输入，「一年前的今天」不许凭空出来
        // （判据不是"只要开了这个功能就永远出一张"）。
        val noYearAgo = build(LocalDate.of(2026, 7, 4), shot(9, 2025, 7, 5), shot(10, 2024, 7, 4))
        assertFalse(
            "去年那天没有照片，却出了一张「一年前的今天」",
            noYearAgo.any { it.kind == MemoryKind.YEAR_AGO },
        )
        assertTrue(
            "2024-07-04 与今天同月同日，应当进「某年今天」",
            noYearAgo.any { it.kind == MemoryKind.ON_THIS_DAY && 10L in it.photoIds },
        )
    }

    /**
     * 「一年前的今天」什么时候能**真的独立存在**。
     *
     * 除了闰日那一路，它总是被「某年今天」吃掉：去年那天的月日与今天相同，
     * 而同月同日那张卡本来就收所有年份。这不是 bug，是需求里"重复则合并"的字面结果 ——
     * 所以这条把唯一的例外（今天是 2 月 29 日，一年前是 2 月 28 日）单独钉住，
     * 免得以后有人把合并判据从"日期"改成"年份"，闰日那张就悄悄消失了。
     */
    @Test
    fun yearAgoCanOnlyStandaloneAcrossTheLeapDay() {
        // 平年：去年同一天必然被同月同日吞掉
        val flat = build(LocalDate.of(2026, 3, 15), shot(1, 2025, 3, 15))
        assertEquals(
            "平年里「一年前的今天」应当被「某年今天」合并",
            0,
            flat.count { it.kind == MemoryKind.YEAR_AGO },
        )
        assertTrue(
            "合并之后照片必须还在「某年今天」里（不能两张卡都消失）",
            flat.first { it.kind == MemoryKind.ON_THIS_DAY }.photoIds.contains(1L),
        )
        // 闰日：唯一能独立成卡的那一路
        val leap = build(LocalDate.of(2028, 2, 29), shot(2, 2027, 2, 28))
        assertTrue(
            "闰日那天，2-28 的一年之前应当单独成卡",
            leap.any { it.kind == MemoryKind.YEAR_AGO && 2L in it.photoIds },
        )
    }

    // ── 6. 今天正好是闰日 ─────────────────────────────────────────────

    @Test
    fun todayBeingTheLeapDayKeepsEveryWindowOnTheCalendar() {
        // 今天 2028-02-29（闰日）。往前一周跨到 2-23（闰日不会让窗口变长）。
        val leap = LocalDate.of(2028, 2, 29)
        val cards = build(
            leap,
            shot(1, 2028, 2, 23),
            shot(2, 2028, 2, 22),
            shot(3, 2024, 2, 29),
        )
        val week = cards.first { it.kind == MemoryKind.LAST_WEEK }
        assertTrue("闰日当天，2-23 在 7 天窗口里却没进来", 1L in week.photoIds)
        assertFalse("闰日当天，2-22 在窗口外却被算了进来", 2L in week.photoIds)
        val onThisDay = cards.first { it.kind == MemoryKind.ON_THIS_DAY }
        assertTrue("2024-02-29 没跟 2028-02-29 归到「某年今天」", 3L in onThisDay.photoIds)
        assertEquals(
            "闰日当天的「某年今天」被「一年前的今天」拆开了",
            0,
            cards.count { it.kind == MemoryKind.YEAR_AGO && 3L in it.photoIds },
        )
        // 2027-02-28 的「一年前的今天」：去年 2027 是平年，减一年是 2-28，与 2-29 无关。
        assertFalse(
            "闰日的「一年前的今天」凭空出来了（2-28 那天并没有照片）",
            cards.any { it.kind == MemoryKind.YEAR_AGO },
        )

        // 反方向：今天是 2029-02-28，而库里只有 2028-02-29 那张。
        // 日历上 2029-02-28 减一年是 2028-02-28，闰日那张**不属于**「一年前的今天」，
        // 但它也不属于「某年今天」（2-29 ≠ 2-28）—— 这正是闰日必须单独处理的地方。
        val after = build(LocalDate.of(2029, 2, 28), shot(3, 2028, 2, 29))
        assertFalse("闰日被错算成了明年的「一年前的今天」", after.any { it.kind == MemoryKind.YEAR_AGO })
        assertFalse("闰日被错算成了「某年今天」", after.any { it.kind == MemoryKind.ON_THIS_DAY })
        assertTrue("闰日那张照片至少要是「第一次拍的那天」", after.any { it.kind == MemoryKind.FIRST_DAY })
    }

    // ── 7. 卡片契约与顺序 ─────────────────────────────────────────────

    @Test
    fun everyCardKeepsItsContract() {
        val now = LocalDate.of(2026, 10, 18)
        val cards = build(
            now,
            shot(1, 2026, 10, 18),
            shot(2, 2025, 10, 18),
            shot(3, 2025, 7, 4),
            shot(4, 2019, 1, 1),
            shot(5, 2026, 10, 15),
        )
        assertTrue("一张卡都没出", cards.isNotEmpty())
        assertEquals("卡片 id 撞车了（列表 key 会互相顶掉）", cards.size, cards.map { it.id }.toSet().size)
        for (card in cards) {
            assertTrue("卡片「${card.title}」的照片为空", card.photoIds.isNotEmpty())
            assertTrue("卡片「${card.title}」的封面不在自己的照片里", card.coverPhotoId in card.photoIds)
            assertEquals("卡片「${card.title}」的张数与副标题对不上：${card.subtitle}", card.count, card.photoIds.size)
            assertTrue("卡片「${card.title}」标题为空", card.title.isNotBlank())
            assertTrue("卡片「${card.title}」副标题为空", card.subtitle.isNotBlank())
        }
        // 顺序固定：kind 序列必须是六档顺序的子序列
        val order = listOf(
            MemoryKind.ON_THIS_DAY,
            MemoryKind.YEAR_AGO,
            MemoryKind.LAST_WEEK,
            MemoryKind.THIS_MONTH,
            MemoryKind.LAST_SUMMER,
            MemoryKind.FIRST_DAY,
        )
        val positions = cards.map { order.indexOf(it.kind) }
        assertEquals("卡片顺序不稳定（kind 序列不是固定档序）", positions.sorted(), positions)
        // 同一批输入算两遍必须是同一份结果（id 稳定）
        assertEquals(
            "同样的输入算两遍结果不同（id 不稳定，列表会整屏重建）",
            cards.map { it.id },
            build(
                now,
                shot(1, 2026, 10, 18),
                shot(2, 2025, 10, 18),
                shot(3, 2025, 7, 4),
                shot(4, 2019, 1, 1),
                shot(5, 2026, 10, 15),
            ).map { it.id },
        )
    }

    @Test
    fun firstDayCardCoversEveryPhotoFromThatEarliestDate() {
        val cards = build(
            LocalDate.of(2026, 10, 18),
            shot(1, 2018, 5, 1),
            shot(2, 2018, 5, 1),
            shot(3, 2019, 6, 1),
        )
        val first = cards.first { it.kind == MemoryKind.FIRST_DAY }
        assertEquals("「第一次拍的那天」没把同一天的两张都带上", setOf(1L, 2L), first.photoIds.toSet())
        assertTrue("副标题没写日期：${first.subtitle}", first.subtitle.contains("2018"))
        assertTrue("副标题没写过去了几年：${first.subtitle}", first.subtitle.contains("8 年"))
    }

    @Test
    fun maxPhotosCapsTheCardSize() {
        val many = (1L..300L).map { shot(it, 2026, 10, 18) }
        val cards = Memories.buildFrom(many, at(2026, 10, 18), ZONE, maxPhotos = 12)
        assertTrue("上限没生效：${cards.first().count}", cards.all { it.count <= 12 })
        // 上限夹回合法区间：0 或负数按 1 张处理，超大的按常量上限
        assertEquals(1, Memories.buildFrom(many, at(2026, 10, 18), ZONE, maxPhotos = 0).first().count)
        assertEquals(
            Memories.MAX_PHOTOS_PER_MEMORY,
            Memories.buildFrom(many, at(2026, 10, 18), ZONE, maxPhotos = 99999).first().count,
        )
    }

    // ── 8. 判据自检：把窗口写错，测试必须变红 ─────────────────────────

    /**
     * 反向核对：`covers` 与 `buildFrom` 对同一张照片必须给同一个结论。
     *
     * 这两处各写一遍窗口是这一层最容易腐烂的地方：`covers` 是给界面/测试用的第二意见，
     * 一旦它和 `buildFrom` 分叉，"测试通过但界面不对"就会发生。这里用 400 个日期点
     * 逐档对比：某档说"这一天归我"，那么这一天的照片就必须真的出现在那张卡里（反之亦然）。
     */
    @Test
    fun coversAgreesWithTheCardsActuallyProduced() {
        val today = LocalDate.of(2026, 3, 1)
        val days = (-200L..200L).map { today.plusDays(it) }.distinct()
        var checked = 0
        for ((i, day) in days.withIndex()) {
            val id = i.toLong() + 1
            val cards = Memories.buildFrom(listOf(DatedShot(id, at(day.year, day.monthValue, day.dayOfMonth))), at(2026, 3, 1), ZONE)
            for (kind in MemoryKind.entries) {
                if (kind == MemoryKind.FIRST_DAY) continue
                val claimed = Memories.covers(kind, day, today)
                val present = cards.any { it.kind == kind && id in it.photoIds }
                // 「一年前的今天」会被合并进「某年今天」，所以它"声称覆盖但没单独成卡"是合法的。
                if (claimed && !present && kind == MemoryKind.YEAR_AGO) {
                    assertTrue(
                        "$day 的「一年前的今天」既没单独成卡也没并进「某年今天」",
                        cards.any { it.kind == MemoryKind.ON_THIS_DAY && id in it.photoIds },
                    )
                    continue
                }
                assertEquals(
                    "$day 这一档（$kind）的判据与卡片对不上",
                    claimed,
                    present,
                )
                checked++
            }
        }
        assertTrue("只核对了 $checked 个日期点，读法大概失效了", checked > 1500)
    }

    /**
     * 自检：判据真的会变红。
     *
     * 这里把**一版写错的实现**（"最近一周"按毫秒差算 7×24h）与真实现放在同一个输入上比：
     * 在 +08:00 没有夏令时，两者等价 —— 所以换一个**有**夏令时的时区
     * （America/New_York，2026-03-08 那天只有 23 小时），让毫秒差与日历日差真的分歧。
     * 分歧点上，真实现必须算进窗口，错实现必须踢出去。两条都成立，才证明这组样本
     * 有区分能力（否则这一篇对"窗口写错"是瞎的）。
     */
    @Test
    fun millisecondWindowsWouldHaveBeenWrong() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-09 是纽约进入夏令时之后的第二天：3 月 8 日那天只有 23 小时。
        val today = LocalDate.of(2026, 3, 9)
        val now = LocalDateTime.of(2026, 3, 9, 12, 0).atZone(ny).toInstant().toEpochMilli()

        // 日历窗口：today-6 = 3-03 00:00 起。（3-03 00:30 排在日历窗口里 → 两张都该进。）
        val edgeDate = LocalDate.of(2026, 3, 3)
        val edgeMillis = LocalDateTime.of(2026, 3, 3, 0, 30).atZone(ny).toInstant().toEpochMilli()
        // 3-02 20:00：日历日上看是 today-7，**不在**窗口里。
        val outsideDate = LocalDate.of(2026, 3, 2)
        val outsideMillis = LocalDateTime.of(2026, 3, 2, 20, 0).atZone(ny).toInstant().toEpochMilli()

        val correct = Memories.buildFrom(
            listOf(DatedShot(1, edgeMillis), DatedShot(2, outsideMillis)),
            now,
            ny,
        )
        val week = correct.first { it.kind == MemoryKind.LAST_WEEK }
        assertTrue("日历日口径下，$edgeDate 00:30 应当落在最近一周里", 1L in week.photoIds)
        assertFalse("日历日口径下，$outsideDate 20:00 不该在最近一周里", 2L in week.photoIds)

        // 错口径（now - 7×24h）在这台时区里会把 3-02 20:00 算进来：
        // 夏令时丢掉的那一小时让毫秒窗口的**本地钟面**比日历窗口更早（3-02 11:00 起），
        // 于是窗口外的那一张漏了进来 —— 这就是"按毫秒算"会犯的错，也是这段自检的区分能力。
        val wrongStart = now - 7L * 24 * 60 * 60 * 1000
        assertTrue(
            "这组样本没有区分能力：毫秒窗口与日历窗口在这台时区里没有分歧，" +
                "自检等于没跑（换一段日期，别删这条断言）",
            outsideMillis > wrongStart,
        )
        // 反方向也钉住：日历窗口里的那一张，毫秒口径同样收得下（差异只在窗口外那一侧）
        assertTrue("毫秒窗口把日历窗口里的人踢了出去，样本要重挑", edgeMillis > wrongStart)
    }
}
