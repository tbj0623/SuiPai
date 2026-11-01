package com.suipai.app.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SettingsStore` 的往返、坏值兜底与"只清自己的键"。
 *
 * 为什么这一份必须有：`SettingsStore` 是设置中心唯一的读写口，它的三种错法在设备上
 * 都不会立刻看出来 ——
 * 1. 存进去读出来不是同一个值（大小写、range 夹、集合被复用）；
 * 2. 同一个键上存过另一种类型（人手改 prefs、降级安装、别的版本写过）时**整个界面崩掉**；
 * 3. `resetAll()` 顺手把相机/一瞬的老键一起清了（用户的相机设置凭空丢了）。
 *
 * 三条都在 JVM 上钉住。假 `SharedPreferences` 按真实现的契约抛 `ClassCastException` ——
 * 这一点很关键：把 `getInt` 写成"永远返回默认值"的桩，坏值兜底这条就永远测不出来
 * （`IdListsTest` 的桩注释里记着同一个教训）。
 */
class SettingsStoreTest {

    /**
     * 按 Android 的**契约**实现的假 prefs：
     * - `getInt` / `getBoolean` / `getStringSet` 在类型不符时抛 `ClassCastException`；
     * - `getString` 拿到非字符串返回 `defValue`（真实现也是抛，但两条对我们都是"读不出来"）。
     */
    private class FakePrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {

        val data = LinkedHashMap<String, Any>(initial)
        var writes = 0

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            when (val v = data[key]) {
                null -> defValue
                is String -> v
                // 真实现（SharedPreferencesImpl.getString）对类型不符是抛 ClassCastException，
                // 不是回默认值。早先这里写成"回 defValue"，于是"Text 键上存了 Int"这类坏值
                // 永远测不出兜底 —— 判据在假实现上就失效了。
                else -> throw ClassCastException("$key 不是 String")
            }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            when (val v = data[key]) {
                null -> defValues
                is Set<*> -> HashSet(v as Set<String>)
                else -> throw ClassCastException("$key 不是 StringSet")
            }

        override fun getInt(key: String?, defValue: Int): Int =
            when (val v = data[key]) {
                null -> defValue
                is Int -> v
                else -> throw ClassCastException("$key 不是 Int")
            }

        override fun getLong(key: String?, defValue: Long): Long =
            when (val v = data[key]) {
                null -> defValue
                is Long -> v
                else -> throw ClassCastException("$key 不是 Long")
            }

        override fun getFloat(key: String?, defValue: Float): Float =
            when (val v = data[key]) {
                null -> defValue
                is Float -> v
                else -> throw ClassCastException("$key 不是 Float")
            }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            when (val v = data[key]) {
                null -> defValue
                is Boolean -> v
                else -> throw ClassCastException("$key 不是 Boolean")
            }

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any>()
            private val removed = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value ?: "" }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { pending[key!!] = HashSet(values.orEmpty()) }

            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value.toLong() }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }

            override fun remove(key: String?) = apply { removed.add(key!!) }

            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                if (clearAll) data.clear()
                removed.forEach { data.remove(it) }
                data.putAll(pending)
                writes++
                return true
            }

            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private fun store(prefs: FakePrefs = FakePrefs()) = SettingsStore(prefs)

    /**
     * 一条枚举键的"非默认档位名"。
     *
     * 为什么不写成 `SettingKey.Choice<E>` 收泛型：设置列表里的 Choice 是星投影
     * （`SettingKey.Choice<*>`），星投影下拿不到 E，也就写不回去。而落盘本来就存 name，
     * 所以测试也走 name 这条路 —— 这正是界面那边用的 API（`putChoiceName`）。
     */
    private fun otherName(key: SettingKey.Choice<*>): String? =
        key.options.map { it.name }.firstOrNull { it != key.defName }

    // ── 往返 ──────────────────────────────────────────────────────────

    @Test
    fun everyWritableKeySurvivesARoundTrip() {
        val prefs = FakePrefs()
        val s = store(prefs)
        // 每条可写键都写一个**与默认值不同**的值，再读回来比对。
        // 只写默认值等于什么都没测（默认值本来就走兜底那一路）。
        for (key in SettingsKeys.all) {
            when (key) {
                is SettingKey.Bool -> s.put(key, !key.def)
                is SettingKey.Int -> assertTrue("写 ${key.id} 失败", s.put(key, nonDefaultInt(key)))
                is SettingKey.Text -> s.put(key, "写入的值")
                is SettingKey.Strings -> s.put(key, setOf("甲", "乙"))
                is SettingKey.Choice<*> -> {
                    val other = otherName(key)
                    if (other != null) assertTrue("写 ${key.id} 失败", s.putChoiceName(key, other))
                }
            }
        }
        // 逐条读回（走 load() 快照那一路，与界面同一个入口）
        val snap = s.load()
        for (key in SettingsKeys.all) {
            when (key) {
                is SettingKey.Bool -> assertEquals("${key.id} 往返不一致", !key.def, snap.bool(key))
                is SettingKey.Int -> assertEquals("${key.id} 往返不一致", nonDefaultInt(key), snap.int(key))
                is SettingKey.Text -> assertEquals("${key.id} 往返不一致", "写入的值", snap.text(key))
                is SettingKey.Strings -> assertEquals("${key.id} 往返不一致", setOf("甲", "乙"), snap.strings(key))
                is SettingKey.Choice<*> -> {
                    val other = otherName(key)
                    if (other != null) {
                        assertEquals("${key.id} 往返不一致", other, snap.choiceName(key))
                    }
                }
            }
        }
        assertTrue("一条都没写盘：判据大概失效了", prefs.writes > 0)
    }

    private fun nonDefaultInt(key: SettingKey.Int): Int {
        // 默认值 + 1；越界就退到区间另一头，永远保证是**与默认不同**且在区间内。
        val d = key.def
        return if (d + 1 in key.range) d + 1 else key.range.first
    }

    @Test
    fun freshStoreReturnsDeclaredDefaults() {
        val snap = store().load()
        for (key in SettingsKeys.all) {
            when (key) {
                is SettingKey.Bool -> assertEquals("${key.id} 默认值不对", key.def, snap.bool(key))
                is SettingKey.Int -> assertEquals("${key.id} 默认值不对", key.def, snap.int(key))
                is SettingKey.Text -> assertEquals("${key.id} 默认值不对", key.def, snap.text(key))
                is SettingKey.Strings -> assertEquals("${key.id} 默认值不对", key.def, snap.strings(key))
                is SettingKey.Choice<*> -> assertEquals("${key.id} 默认值不对", key.defName, snap.choiceName(key))
            }
        }
        assertFalse("没存过的键不许被 has() 说成存过",
            SettingsKeys.all.any { snap.has(it) })
    }

    @Test
    fun choiceIsPersistedByNameNotOrdinal() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(SettingsKeys.Appearance.themeMode, ThemeMode.DAYLIGHT)
        assertEquals(
            "枚举必须按 name 落盘（ordinal 会在加档之后把老用户的选择解释成另一档）",
            "DAYLIGHT",
            prefs.data[SettingsKeys.Appearance.themeMode.id],
        )
    }

    @Test
    fun typeSafeChoiceReadAndWriteRoundTripInsideOneGeneric() {
        // 类型已知的那条 put/read 也要走一遍：生产代码里(例如引导、相机)会用它。
        val prefs = FakePrefs()
        val s = store(prefs)
        assertTrue(s.put(SettingsKeys.Appearance.gridColumns, GridColumns.LARGE))
        assertEquals(GridColumns.LARGE, s.read(SettingsKeys.Appearance.gridColumns))
        assertEquals("按 name 读也要一致", "LARGE", s.readChoiceName(SettingsKeys.Appearance.gridColumns))
        // 写表外档位被拒（这里用 name 那条 API 模拟"传了个不存在的档"）
        assertFalse(s.putChoiceName(SettingsKeys.Appearance.gridColumns, "ULTRA"))
    }

    // ── 坏值兜底 ──────────────────────────────────────────────────────

    @Test
    fun wrongTypeOnDiskFallsBackToDefaultInsteadOfCrashing() {
        // 这一次喂的就是任务里点名的那一格：把 Int 键存成 "abc"
        val key = SettingsKeys.Camera.jpegQuality
        val prefs = FakePrefs(mapOf(key.id to "abc"))
        val s = store(prefs)
        assertEquals("坏值必须回默认值，不许抛异常", key.def, s.read(key))
        // 快照那一路也一样（界面走的是它）
        assertEquals("快照读坏值也必须回默认值", key.def, s.load().int(key))
    }

    @Test
    fun everyKindOfWrongValueIsSurvivable() {
        val boolKey = SettingsKeys.General.confirmDelete
        val intKey = SettingsKeys.Yishun.defaultIntensity
        val textKey = SettingsKeys.Privacy.exportAt
        val setKey = SettingsKeys.Self.searchHistory
        val choiceKey = SettingsKeys.Appearance.themeMode
        val prefs = FakePrefs(
            mapOf(
                boolKey.id to "不是布尔",
                intKey.id to "abc",
                textKey.id to 42,
                setKey.id to 7,
                choiceKey.id to true,
            ),
        )
        val s = store(prefs)
        assertEquals(boolKey.def, s.read(boolKey))
        assertEquals(intKey.def, s.read(intKey))
        assertEquals(textKey.def, s.read(textKey))
        assertEquals(setKey.def, s.read(setKey))
        assertEquals(choiceKey.def, s.read(choiceKey))
        // 五条全读完仍然不崩，而且 repair 把它们清干净了
        assertEquals(
            "坏值应当被 repair 清掉，清的键名要报出来",
            listOf(boolKey.id, intKey.id, textKey.id, choiceKey.id, setKey.id).sorted(),
            s.repairBadValues().sorted(),
        )
        assertTrue("repair 之后磁盘上不该还留着坏值",
            listOf(boolKey.id, intKey.id, textKey.id, choiceKey.id, setKey.id)
                .none { prefs.contains(it) })
        // 清完之后读到的仍是默认值（不是"没了就崩"）
        assertEquals(intKey.def, s.read(intKey))
    }

    @Test
    fun outOfRangeIntFallsBackInsteadOfBeingClamped() {
        // 画质区间 95..100：写 0 与写 200 都不该被静默夹成 95/100 —— 那会让"磁盘上是多少"
        // 与"界面显示多少"两件事说不清。读路径直接回默认值。
        val key = SettingsKeys.Camera.jpegQuality
        val s = store(FakePrefs(mapOf(key.id to 0)))
        assertEquals("区间外的值必须回默认值（不是夹到边界）", key.def, s.read(key))
        // 写路径也拦：越界写返回 false，且真的没落盘
        val prefs = FakePrefs()
        val s2 = store(prefs)
        assertFalse("越界写必须被拒", s2.put(key, 200))
        assertFalse("被拒的写不许落盘", prefs.contains(key.id))
    }

    @Test
    fun unknownChoiceNameFallsBackToDefault() {
        val key = SettingsKeys.Appearance.themeMode
        val s = store(FakePrefs(mapOf(key.id to "NIGHT_VISION")))
        assertEquals("不认识的档位名必须回默认档", key.def, s.read(key))
        assertEquals("按 name 读也一样回默认档", key.defName, s.readChoiceName(key))
    }

    @Test
    fun choiceNameIsMatchedCaseInsensitively() {
        // 手改 prefs 写成小写也要能救回来：档位名是落盘格式，大小写不该决定设置丢不丢
        val key = SettingsKeys.Appearance.themeMode
        val s = store(FakePrefs(mapOf(key.id to "daylight")))
        assertEquals("大小写不该让设置失效", ThemeMode.DAYLIGHT, s.read(key))
        assertEquals("按 name 读也要救回来", "DAYLIGHT", s.readChoiceName(key))
    }

    // ── 清除 ──────────────────────────────────────────────────────────

    @Test
    fun resetAllOnlyTouchesOurOwnKeys() {
        val prefs = FakePrefs(
            mapOf(
                // 本模块的键
                SettingsKeys.General.confirmDelete.id to false,
                SettingsKeys.Appearance.themeMode.id to "DAYLIGHT",
                // 老表的键：一个都不许被清
                "dark_ui" to false,
                "camera_jpeg" to 100,
                "yishun_jpeg" to 95,
                "hide_shots" to true,
                "fav_ids" to setOf("1", "2"),
            ),
        )
        store(prefs).resetAll()
        assertFalse("本模块的键应当被清掉", prefs.contains(SettingsKeys.General.confirmDelete.id))
        assertFalse("本模块的键应当被清掉", prefs.contains(SettingsKeys.Appearance.themeMode.id))
        for (legacy in listOf("dark_ui", "camera_jpeg", "yishun_jpeg", "hide_shots", "fav_ids")) {
            assertTrue("老键 $legacy 被 resetAll 一起清了 —— 用户相机会凭空丢设置", prefs.contains(legacy))
        }
    }

    @Test
    fun resetAllLeavesStoreOnDefaults() {
        val prefs = FakePrefs()
        val s = store(prefs)
        s.put(SettingsKeys.General.confirmDelete, false)
        s.put(SettingsKeys.Camera.jpegQuality, 100)
        s.resetAll()
        assertEquals(SettingsKeys.General.confirmDelete.def, s.read(SettingsKeys.General.confirmDelete))
        assertEquals(SettingsKeys.Camera.jpegQuality.def, s.read(SettingsKeys.Camera.jpegQuality))
    }

    @Test
    fun forExportReportsEveryKey() {
        val snap = store().load()
        val rows = snap.forExport()
        assertEquals("导出应当覆盖全部键（漏一条，用户就看不到那条设置的真实值）",
            SettingsKeys.all.size, rows.size)
        assertTrue("导出里的值不许出现 Kotlin 类型名：$rows",
            rows.values.none { it.contains("kotlin.") || it.contains("@") })
        // 空串要给一个人话，而不是留一个空值让人以为没读到
        assertEquals("（空）", rows[SettingsKeys.Privacy.exportAt.id])
    }

    // ── 自检：假 prefs 真的会抛，判据真能变红 ─────────────────────────

    @Test
    fun fakePrefsThrowsLikeTheRealOne() {
        // 这条自检保护的是"坏值兜底"那一族断言的有效性：
        // 假 prefs 如果不抛 ClassCastException，store 的 runCatching 就从来没被走到过。
        val prefs = FakePrefs(mapOf("x" to "abc"))
        var threw = false
        try {
            prefs.getInt("x", 0)
        } catch (e: ClassCastException) {
            threw = true
        }
        assertTrue("假 prefs 必须像真实现一样对类型不符抛 ClassCastException，否则兜底测试是空跑", threw)
        assertNull("不存在的键 getString 应当回 null 而不是抛", prefs.getString("nope", null))
        assertEquals(7, prefs.getInt("nope", 7))
    }

    @Test
    fun badValueCheckerActuallyCatches() {
        // 若把 readRaw 的 runCatching 拿掉，上面那几条会以 ClassCastException 变红；
        // 这里反过来确认"读到坏值"这件事本身在假 prefs 上是可观测的。
        val key = SettingsKeys.Camera.jpegQuality
        val prefs = FakePrefs(mapOf(key.id to "abc"))
        assertTrue("假 prefs 里那条坏值应当真的存在", prefs.contains(key.id))
        assertTrue("坏值读 Int 必须抛（这就是兜底要处理的形状）",
            runCatching { prefs.getInt(key.id, 0) }.isFailure)
        assertEquals("兜底之后必须是默认值", key.def, store(prefs).read(key))
    }
}
