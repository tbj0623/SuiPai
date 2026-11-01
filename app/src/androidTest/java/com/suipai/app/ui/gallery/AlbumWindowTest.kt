package com.suipai.app.ui.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suipai.app.ui.camera.CameraFlag
import com.suipai.app.ui.yishun.YishunFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 相册列表两张表的钉子（日期窗口 + 开关）。
 *
 * 窗口表是把 19 段手写 filter 换成一张表的结果，所以必须证明：
 * 每个窗口覆盖的真实区间没变（测试里用 java.time 独立算一遍，不叫生产代码自己给自己背书）、
 * 相邻窗口首尾相接不重叠；开关表则要保证 key 不与另外三个界面撞——
 * 四个界面共用同一个 prefs 文件，撞 key 的两个开关会一起跳。
 */
@RunWith(AndroidJUnit4::class)
class AlbumWindowTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("suipai_album_tables_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun ms(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun dayWindowsCoverExactlyOneDayEach() {
        val today = LocalDate.now(zone)
        listOf(0, 1, 2, 3).forEach { back ->
            val day = today.minusDays(back.toLong())
            val w = AlbumWindow.values().first { it.unit == AlbumUnit.DAY && it.back == back }
            val r = w.range(zone)
            assertEquals(ms(day), r.first)
            assertEquals(ms(day.plusDays(1)) - 1, r.last)
        }
    }

    @Test
    fun weekWindowsStartOnMondayAndDoNotOverlap() {
        val thisMonday = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val weeks = (0..2).map { back ->
            val w = AlbumWindow.values().first { it.unit == AlbumUnit.WEEK && it.back == back }
            w.range(zone)
        }
        assertEquals(ms(thisMonday), weeks[0].first)
        assertEquals(ms(thisMonday.minusWeeks(1)), weeks[1].first)
        assertEquals(ms(thisMonday.minusWeeks(2)), weeks[2].first)
        // 首尾相接：上一段的最后一毫秒 + 1 == 下一段的第一毫秒
        assertEquals(weeks[1].last + 1, weeks[0].first)
        assertEquals(weeks[2].last + 1, weeks[1].first)
    }

    @Test
    fun monthAndYearWindowsAnchorToCalendarBoundaries() {
        val today = LocalDate.now(zone)
        val month = AlbumWindow.values().first { it.unit == AlbumUnit.MONTH && it.back == 1 }.range(zone)
        assertEquals(ms(today.withDayOfMonth(1).minusMonths(1)), month.first)
        assertEquals(ms(today.withDayOfMonth(1)) - 1, month.last)
        val year = AlbumWindow.values().first { it.unit == AlbumUnit.YEAR && it.back == 3 }.range(zone)
        assertEquals(ms(LocalDate.of(today.year - 3, 1, 1)), year.first)
        assertEquals(ms(LocalDate.of(today.year - 2, 1, 1)) - 1, year.last)
    }

    @Test
    fun everyWindowIsReachableAndDistinct() {
        val keys = AlbumWindow.values().map { it.prefKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.size, AlbumWindow.values().map { it.unit to it.back }.toSet().size)
        AlbumWindow.values().forEach {
            val r = it.range(zone)
            assertTrue("${it.name} 区间反了", r.first <= r.last)
            assertTrue(it.labelOff.isNotBlank() && it.labelOn.isNotBlank() && it.empty.isNotBlank())
        }
    }

    @Test
    fun albumFlagKeysDoNotCollideWithTheOtherScreens() {
        val mine = AlbumFlag.values().map { it.prefKey }
        assertEquals(mine.size, mine.toSet().size)
        val others = (
            YishunFlag.values().map { it.prefKey } +
                GalleryFlag.values().map { it.prefKey } +
                CameraFlag.values().map { it.key }
            ).toSet()
        assertTrue("撞 key: ${mine.filter { it in others }}", mine.none { it in others })
    }

    @Test
    fun flagsRoundTripThroughPrefs() {
        AlbumFlag.values().forEach { f ->
            val flipped = AlbumFlags().toggled(f)
            assertEquals("点了就该翻", !f.def, flipped.get(f))
            flipped.saveTo(prefs)
            val reloaded = AlbumFlags.load(prefs)
            assertEquals("${f.name} 没落盘", flipped, reloaded)
            AlbumFlags().saveTo(prefs)
            assertEquals(AlbumFlags(), AlbumFlags.load(prefs))
        }
    }

    @Test
    fun defaultsMatchTheOldHandWrittenOnes() {
        val d = AlbumFlags()
        assertTrue("圆封面默认是开的（老界面就是这么存的）", d.roundCover)
        assertFalse(d.hideShots || d.onlySuiPai || d.hideSocial || d.hideDownload)
        assertFalse(d.onlyPins || d.hideMode)
        assertEquals(AlbumFlags(), AlbumFlags.load(prefs))
    }

    @Test
    fun activeWindowsFollowPrefsInDeclarationOrder() {
        prefs.edit()
            .putBoolean(AlbumWindow.LAST_YEAR.prefKey, true)
            .putBoolean(AlbumWindow.TODAY.prefKey, true)
            .apply()
        assertEquals(listOf(AlbumWindow.TODAY, AlbumWindow.LAST_YEAR), AlbumWindow.active(prefs))
    }
}
