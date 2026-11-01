package com.suipai.app.ui.yishun

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一瞬偏好表的守卫。
 *
 * 这 20 个开关原来散在 `YishunScreen()` 里（20 个委托属性 + 20 段手写落盘），
 * 现在由 [YishunFlag] 一张表生成。表驱动的风险是「表漏一项」或「两个开关抢同一个 prefKey」——
 * 两种错都不会让界面看起来有问题，只会让用户某个设置在下次打开时静默丢掉。
 */
class YishunFlagsTest {

    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("suipai_flags_test", android.content.Context.MODE_PRIVATE)

    @Test
    fun prefKeysAreUnique() {
        val keys = YishunFlag.entries.map { it.prefKey }
        assertEquals(
            "prefKey 有重复：${keys.groupBy { it }.filter { it.value.size > 1 }.keys}",
            keys.size, keys.distinct().size,
        )
    }

    @Test
    fun everyFlagWritesItsOwnField() {
        // 两个开关指向同一个字段 = 改一个会动另一个。逐个从基线翻转一次，结果必须两两不同。
        val base = YishunFlags()
        val flipped = YishunFlag.entries.map { it.with(base, !it.get(base)) }
        assertEquals("有开关共用了同一个字段", YishunFlag.entries.size, flipped.distinct().size)
        assertTrue("翻转后必须确实不同于基线", flipped.none { it == base })
    }

    /**
     * 落盘键名冻结。改名/删项在界面上完全看不出来，代价是老用户那一项设置静默归零——
     * 所以这里逐字钉住，要改就得连这段一起改（等于逼人想起来「这是用户数据」）。
     */
    @Test
    fun prefKeysMatchTheFrozenList() {
        assertEquals(
            listOf(
                "yishun_only_pins", "yishun_only_recent",
                "yishun_prefer_last", "yishun_lock_look", "yishun_chip_names", "yishun_chip_sort", "yishun_chip_rev",
                "yishun_split_v", "yishun_split_flip", "yishun_split_red",
                "yishun_fill", "yishun_hist", "yishun_hist_rgb", "yishun_blink", "yishun_tune",
                "yishun_lock_int", "yishun_png", "yishun_also_compare", "yishun_savenext", "yishun_share_after",
            ),
            YishunFlag.entries.map { it.prefKey },
        )
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val base = YishunFlags()
        val cases = listOf(base) + YishunFlag.entries.map { it.with(base, !it.get(base)) }
        cases.forEach { flags ->
            flags.saveTo(prefs)
            assertEquals("落盘再读回必须一模一样", flags, YishunFlags.load(prefs))
        }
    }

    @Test
    fun loadKeepsDefaultsForKeysNeverWritten() {
        prefs.edit().clear().apply()
        val fresh = YishunFlags.load(prefs)
        assertEquals(base, fresh)
        assertTrue("配方行默认显示名字", fresh.chipNames)
        assertTrue("直方图默认显示", fresh.showHist)
        assertFalse("其余默认关", fresh.lockInt || fresh.savePng || fresh.onlyPins || fresh.blink)
    }

    @Test
    fun conditionalFlagsAreHiddenUnlessTheirParentIsOn() {
        assertFalse("「名称倒序」在没开按名排时不该摆出来", YishunFlag.CHIP_REV.visibleIn(base))
        assertTrue("开了按名排就该出现", YishunFlag.CHIP_REV.visibleIn(YishunFlag.CHIP_SORT.with(base, true)))
        assertFalse("关掉直方图就不该再有 RGB 条开关", YishunFlag.HIST_RGB.visibleIn(YishunFlag.SHOW_HIST.with(base, false)))
        assertTrue("直方图开着就该给 RGB 条", YishunFlag.HIST_RGB.visibleIn(base))
    }

    @Test
    fun toggleIsItsOwnInverse() {
        YishunFlag.entries.forEach { f ->
            assertEquals("${f.name} 连点两次要回到原值", f.get(base), f.get(base.toggle(f).toggle(f)))
        }
    }

    private companion object {
        val base = YishunFlags()
    }
}
