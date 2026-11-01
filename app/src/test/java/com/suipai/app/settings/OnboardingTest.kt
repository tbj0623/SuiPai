package com.suipai.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启引导的两条：
 * 1. "连读三次结果一样"（幂等）—— 首启判定会在每次冷启动、每次从设置回来、每次重组里被读到，
 *    而它读的是两个键的与。写坏了的现象是"引导弹两次/永远弹"，在设备上要点两次冷启动才看得见；
 * 2. 版号退回（重看引导）之后，**下一次冷启动要弹**，但用户中途退出时不该把
 *    "他已走过一次"这件事忘掉。
 *
 * 判定全在 [OnboardingGate] 这个纯对象里，所以这一份不需要 Context、不需要 Compose。
 * 落盘用一个最小假 prefs（只在写路径上用得到），读路径一律走 [SettingsSnapshot]。
 */
class OnboardingTest {

    /** 只要能把 6 个键写进去就行；读路径不经过它。 */
    private class TinyPrefs : android.content.SharedPreferences {
        val data = LinkedHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST")
            (data[key] as? Set<String>)?.let { HashSet(it) } ?: defValues

        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String?) = data.containsKey(key)

        override fun edit(): android.content.SharedPreferences.Editor =
            object : android.content.SharedPreferences.Editor {
                private val pending = LinkedHashMap<String, Any>()
                private val removed = LinkedHashSet<String>()
                override fun putString(key: String?, value: String?) = apply { pending[key!!] = value ?: "" }
                override fun putStringSet(key: String?, values: MutableSet<String>?) =
                    apply { pending[key!!] = HashSet(values.orEmpty()) }

                override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
                override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
                override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
                override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
                override fun remove(key: String?) = apply { removed.add(key!!) }
                override fun clear() = apply { data.clear() }
                override fun commit(): Boolean {
                    removed.forEach { data.remove(it) }
                    data.putAll(pending)
                    return true
                }

                override fun apply() {
                    commit()
                }
            }

        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private fun store(prefs: TinyPrefs = TinyPrefs()) = SettingsStore(prefs)

    @Test
    fun firstLaunchShowsTheIntro() {
        assertTrue("装完第一次打开必须弹引导", OnboardingGate.shouldShow(store().load()))
    }

    @Test
    fun completingHidesItForever() {
        val s = store()
        OnboardingGate.complete(s)
        // 连读三次：每次冷启动（= 重新 load）都读到同一个答案，且都是"不弹"
        repeat(3) { i ->
            assertFalse("第 ${i + 1} 次读仍然说该弹 —— 引导会被重复弹出", OnboardingGate.shouldShow(s.load()))
        }
        assertEquals(
            "连读三次的结论必须完全一致（幂等）",
            1,
            List(3) { OnboardingGate.shouldShow(s.load()) }.distinct().size,
        )
    }

    @Test
    fun completingTwiceIsIdempotent() {
        val s = store()
        OnboardingGate.complete(s)
        val once = s.load()
        OnboardingGate.complete(s)
        OnboardingGate.complete(s)
        val thrice = s.load()
        // 两个键的值必须一字不差：重复完成不许把版本号越写越大
        assertEquals(
            "重复完成把落盘值改了",
            once.int(SettingsKeys.General.onboardingVersion),
            thrice.int(SettingsKeys.General.onboardingVersion),
        )
        assertEquals(
            once.bool(SettingsKeys.General.onboarded),
            thrice.bool(SettingsKeys.General.onboarded),
        )
        assertEquals(
            "连读三次的结论必须一样",
            List(3) { OnboardingGate.shouldShow(s.load()) }.distinct().size,
            1,
        )
    }

    @Test
    fun replayBringsItBackOnceWithoutForgettingCompletion() {
        val s = store()
        OnboardingGate.complete(s)
        assertFalse(OnboardingGate.shouldShow(s.load()))

        OnboardingGate.replay(s)
        assertTrue("点「重看引导」之后下一次冷启动要弹", OnboardingGate.shouldShow(s.load()))
        // 但"他走过一次"这件事没被忘掉：onboarded 仍是 true。
        // 意义在于：引导页自己的"跳过"按钮走的是 complete()，用户中途退出也不会被当成新用户。
        assertTrue(
            "replay 不许把 onboarded 清掉（清掉之后中途退出会被永远当成新用户）",
            s.load().bool(SettingsKeys.General.onboarded),
        )

        // 再看一遍 → 又回到不弹，且版本号被重新写回当前内容版本
        OnboardingGate.complete(s)
        assertFalse(OnboardingGate.shouldShow(s.load()))
        assertEquals(
            OnboardingGate.CONTENT_VERSION,
            s.load().int(SettingsKeys.General.onboardingVersion),
        )
    }

    @Test
    fun oldContentVersionShowsItAgain() {
        // 老用户：走过 v0 那种（其实等价于没看过当前内容）
        val prefs = TinyPrefs()
        prefs.data[SettingsKeys.General.onboarded.id] = true
        prefs.data[SettingsKeys.General.onboardingVersion.id] = 0
        assertTrue("看过旧版内容的老用户，升级后要再看一次新版", OnboardingGate.shouldShow(store(prefs).load()))
    }

    @Test
    fun statusTextSaysWhichCaseItIs() {
        val fresh = store()
        assertTrue(OnboardingGate.statusText(fresh.load()).contains("还没"))
        OnboardingGate.complete(fresh)
        assertTrue("走完之后的状态话要说出当前版本",
            OnboardingGate.statusText(fresh.load()).contains("v${OnboardingGate.CONTENT_VERSION}"))
        OnboardingGate.replay(fresh)
        // replay 之后是 "看过旧版（v0）"，不是"还没走完" —— 用户已经看过一次了
        val afterReplay = OnboardingGate.statusText(fresh.load())
        assertTrue("重看之后的状态话不对：$afterReplay", afterReplay.contains("旧版"))
        assertFalse("重看之后不该说「还没走完」（那会让人以为从没看过）", afterReplay.contains("还没"))
    }

    @Test
    fun contentVersionGateActuallyCatchesADifference() {
        // 自检：如果判定写成"只看 onboarded"，下面这条会红。
        // 造一个"走完了、但版本比当前内容旧"的快照，判定必须是 show。
        val stale = SettingsSnapshot(
            mapOf(
                SettingsKeys.General.onboarded.id to true,
                SettingsKeys.General.onboardingVersion.id to (OnboardingGate.CONTENT_VERSION - 1),
            ),
        )
        assertTrue("版本闸门失效了：老内容版本没被放回来", OnboardingGate.shouldShow(stale))
        val current = SettingsSnapshot(
            mapOf(
                SettingsKeys.General.onboarded.id to true,
                SettingsKeys.General.onboardingVersion.id to OnboardingGate.CONTENT_VERSION,
            ),
        )
        assertFalse("当前版本被误判成要弹", OnboardingGate.shouldShow(current))
    }
}
