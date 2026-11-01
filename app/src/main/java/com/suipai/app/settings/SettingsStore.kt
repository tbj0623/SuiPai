package com.suipai.app.settings

import android.content.Context
import android.content.SharedPreferences
/**
 * 设置中心唯一的读写入口。
 *
 * 收这一层而不是让每个界面自己 `prefs.getInt("cfg_xxx", 95)`，是因为这个项目已经栽过三次
 * 同一类事（见 `PerfSourceGuardTest` 的"只写不读"、`FlagTableGuardTest` 的"键名撞车"、
 * `CameraUiState` 里满地的 `coerceIn`）：**默认值、键名、坏值兜底分散在调用点**，
 * 任何一处改口径，其它几处就会悄悄不一致。
 *
 * 这一层管三件事：
 * 1. **类型安全**：读一条 `SettingKey.Int` 只会拿到 Int，拿不到就回默认值，不抛异常；
 * 2. **坏值兜底**：`SharedPreferences.getInt` 在同一个键上存过 String 时会抛
 *    `ClassCastException`（人手改 prefs、老版本写过别的类型、降级安装都会落到这一格）。
 *    这里一律 `runCatching` 回默认值 —— **设置项读坏了不该让界面起不来**；
 * 3. **快照读取**：[load] 一次读全表交给界面，避免列表里几十行各自问一次 prefs，
 *    也保证"这一屏读到的是同一帧的值"。
 *
 * 写只有 [put] 一族：一次 `edit().apply()`，不做批量事务（今天的设置都是单条即时生效）。
 * 落盘的**文件**是 `suipai.xml` —— 与相机/一瞬/相册三张老表同一个文件，也是 `backup_rules.xml`
 * 唯一 include 的那一个；所以本模块的键会随系统备份走（见 `docs/product/SETTINGS.md`）。
 *
 * 构造方式只有两个：
 * - 生产：`SettingsStore.of(context)`（取 `applicationContext` 的 prefs，绝不持有 Activity）；
 * - 单测：`SettingsStore(fakePrefs)`，直接喂一份 `SharedPreferences` 假实现。
 */
class SettingsStore(private val prefs: SharedPreferences) {

    // ── 读 ────────────────────────────────────────────────────────────

    /**
     * 读全表快照。
     *
     * 界面拿它渲染，一次列表重建只读一遍；任何一次 [put] 之后再 [load] 就得到新的一份。
     * 快照自己不持有 prefs 句柄，所以它可以安全地跨组合传递。
     */
    fun load(): SettingsSnapshot {
        val values = HashMap<String, Any>(SettingsKeys.all.size * 2)
        for (key in SettingsKeys.all) {
            if (!prefs.contains(key.id)) continue
            val raw = readRaw(key) ?: continue
            values[key.id] = raw
        }
        return SettingsSnapshot(values)
    }

    /** 单条读：界面做局部刷新时用（例如只翻转一颗开关）。 */
    fun read(key: SettingKey.Bool): Boolean = readRaw(key) as? Boolean ?: key.def

    fun read(key: SettingKey.Int): Int = readRaw(key) as? Int ?: key.def

    fun read(key: SettingKey.Text): String = readRaw(key) as? String ?: key.def

    fun read(key: SettingKey.Strings): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return readRaw(key) as? Set<String> ?: key.def
    }

    /**
     * 枚举单选。落盘按 **name**，回来时做一次大小写无关的匹配：
     * 手改过 prefs、或者在老版本里存过另一个大小写的名字，都能救回来；
     * 救不回来（成员已改名 / 值被写成别的）就回默认档，界面上看到的是"回到默认"，不是崩溃。
     */
    fun <E : Enum<E>> read(key: SettingKey.Choice<E>): E {
        val name = readRaw(key) as? String ?: return key.def
        return key.options.firstOrNull { it.name == name }
            ?: key.options.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: key.def
    }

    // ── 写 ────────────────────────────────────────────────────────────
    //
    // 每一个写方法都做同样两件**顺手**的事（见 `SettingsBridge` 的头注释）：
    //  1. 把 `SettingsBridge.legacyWritesFor` 给出的老键写成同一个选择 —— 降级安装要认；
    //     老键从这一版起**只写不读**，读侧一律回到 `cfg_`。
    //  2. `SettingsSignal.bump()` —— 主题/字号那一层在导航宿主之外，不通知它就刷不到。

    fun put(key: SettingKey.Bool, value: Boolean) {
        commit(key.id, value)
    }

    /**
     * 写整数。落在 [SettingKey.Int.range] 之外的值**不写**（返回 false），
     * 而不是先写进去、读的时候再夹 —— 后者会让"磁盘上有个非法值"这件事一直存在，
     * 而下一个读它的人未必记得夹。
     */
    fun put(key: SettingKey.Int, value: Int): Boolean {
        if (value !in key.range) return false
        commit(key.id, value)
        return true
    }

    fun put(key: SettingKey.Text, value: String) {
        commit(key.id, value)
    }

    fun put(key: SettingKey.Strings, value: Set<String>) {
        // getStringSet 契约：存进去的集合之后再被改，行为未定义。这里一律拷一份。
        commit(key.id, HashSet(value))
    }

    fun <E : Enum<E>> put(key: SettingKey.Choice<E>, value: E): Boolean {
        if (value !in key.options) return false
        commit(key.id, value.name)
        return true
    }

    /**
     * 按**档位名**写一条枚举设置。
     *
     * 存在的理由：设置列表是混着不同类型档位的（主题、字号、网格、缩略图…），
     * 界面那一层拿到的 `SettingItem.Choice<*>` 是星投影，写不回去 —— 星投影下拿不到 E。
     * 而落盘本来就是按 name 存的，所以这里直接收 name，并用 `key.options` 校验它确实是一档，
     * **不需要任何强转**。类型安全的那条 [put] 留给已知具体类型的调用点。
     */
    fun putChoiceName(key: SettingKey.Choice<*>, name: String): Boolean {
        if (key.options.none { it.name == name }) return false
        commit(key.id, name)
        return true
    }

    /**
     * 唯一的落盘点：`cfg_` 键 + 桥给出的老键**在同一次 edit 里**写完，再广播。
     *
     * 一次 edit 而不是两次，是为了让"设置页那一格"和"相册读到的那个数"永远同时落地：
     * 分两次写的话，中间任何一个时刻被杀进程，都会留下一个"设置页说大格、相册还是中格"的
     * 世界，而这种不一致没有任何地方会发现。
     */
    private fun commit(id: String, value: Any, fanBackToLegacy: Boolean = true) {
        val editor = prefs.edit()
        // 枚举值是按**名字**落盘的：桥与老键都认名字，不认枚举对象本身。
        val stored: Any = if (value is Enum<*>) value.name else value
        when (stored) {
            is Boolean -> editor.putBoolean(id, stored)
            is Int -> editor.putInt(id, stored)
            is String -> editor.putString(id, stored)
            is Set<*> -> editor.putStringSet(id, stored.mapNotNull { it as? String }.toSet())
            else -> return   // 不该发生：SettingKey 只有这四种落盘类型
        }
        // 从老键**反向**同步过来的那一路不再扇回去：老键此刻已经是用户精确选的那一档，
        // 而 `legacyWritesFor` 是**有损**的（20 款网格 → 一个开关、六档排序 → 四档），
        // 扇回去会把用户挑的"黄金分割"改写成"九宫格"。
        if (fanBackToLegacy) {
            for (legacy in SettingsBridge.legacyWritesFor(id, stored)) {
                when (val v = legacy.value) {
                    is Boolean -> editor.putBoolean(legacy.key, v)
                    is Int -> editor.putInt(legacy.key, v)
                    is String -> editor.putString(legacy.key, v)
                    else -> Unit
                }
            }
        }
        editor.apply()
        SettingsSignal.bump()
    }

    /**
     * 界面自己拨了一个老键之后，把 `cfg_` 写成同一个选择（`SettingsBridge` 的反向那一半）。
     *
     * 返回 true 表示"这条老键确实有设置项，已经同步"。没有对应项的老键（相机那 20 个
     * 网格样式、一瞬的各种开关）一律返回 false —— 那些**没有**出现在设置中心，
     * 硬给它们编一个 `cfg_` 键会让 `SettingsModelTest` 的"每个落盘键都要有界面入口"变红。
     */
    fun syncFromLegacyBool(legacyKey: String, value: Boolean): Boolean {
        val write = SettingsBridge.cfgForLegacyBool(legacyKey, value) ?: return false
        commit(write.id, write.value, fanBackToLegacy = false)
        return true
    }

    /** 同上，整数老键那一半。 */
    fun syncFromLegacyInt(legacyKey: String, value: Int): Boolean {
        val write = SettingsBridge.cfgForLegacyInt(legacyKey, value) ?: return false
        commit(write.id, write.value, fanBackToLegacy = false)
        return true
    }

    /** 按 [SettingKey.Choice] 读档位名（星投影也能读）。界面显示当前档位用它。 */
    fun readChoiceName(key: SettingKey.Choice<*>): String {
        val raw = readRaw(key) as? String ?: return key.defName
        return key.options.firstOrNull { it.name == raw }?.name
            ?: key.options.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.name
            ?: key.defName
    }

    // ── 维护 ──────────────────────────────────────────────────────────

    /**
     * 扫一遍全部键，把**读不出来的那些**从磁盘上删掉，返回被清掉的键名。
     *
     * 删而不是改写：坏值多半来自"别的版本写过另一种类型"，删掉之后读路径回默认值，
     * 而默认值是这一层唯一保证过的东西。调用点在设置页首帧顺手跑一次。
     */
    fun repairBadValues(): List<String> {
        val bad = SettingsKeys.all.filter { key ->
            prefs.contains(key.id) && readRaw(key) == null
        }.map { it.id }
        if (bad.isNotEmpty()) {
            val editor = prefs.edit()
            bad.forEach { editor.remove(it) }
            editor.apply()
        }
        return bad
    }

    /**
     * 清掉**本模块的**全部偏好。
     *
     * 只删 `cfg_` 开头的键：`suipai.xml` 里还住着相机、一瞬、相册三张老表，
     * 用户点"清除本机数据"时预期清的是设置中心这一层，不该把相机开关一起重置。
     * 照片（MediaStore）与收藏/隐藏清单（`suipai.private.xml`）都不在这里。
     */
    fun resetAll() {
        val editor = prefs.edit()
        var touched = false
        for (id in prefs.all.keys) {
            if (SettingsKeys.isOurs(id)) {
                editor.remove(id)
                touched = true
            }
        }
        if (touched) editor.apply()
    }

    /**
     * 把一条设置在**磁盘上**的真实值取出来；读不出来返回 null。
     *
     * 这就是"坏值兜底"的唯一实现处：`getInt` 在类型不符时抛 `ClassCastException`，
     * `getStringSet` 同理；`getString` 拿到非字符串返回 null。全都收成 null，
     * 上层一律按"没有这一条"处理，于是自然回默认值。
     */
    private fun readRaw(key: SettingKey<*>): Any? = runCatching {
        when (key) {
            is SettingKey.Bool -> prefs.getBoolean(key.id, key.def)
            is SettingKey.Int -> prefs.getInt(key.id, key.def).takeIf { it in key.range }
            is SettingKey.Text -> prefs.getString(key.id, key.def)
            is SettingKey.Choice<*> -> prefs.getString(key.id, key.defName)
            is SettingKey.Strings -> prefs.getStringSet(key.id, emptySet())?.let { HashSet(it) }
        }
    }.getOrNull()

    companion object {
        /** 参与系统备份的那份设置（与 `IdLists.BACKED_UP_FILE` 同一个文件）。 */
        const val PREFS_FILE = "suipai"

        /** 生产路径。取 `applicationContext`：设置的生命周期比任何一个 Activity 都长。 */
        fun of(context: Context): SettingsStore = SettingsStore(
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
        )
    }
}

// ── 迁移感知的读 ────────────────────────────────────────────────────────
//
// 各功能界面要读一条设置，但必须处理"这个键从来没被写过"这一种情况：
// 那说明用户是从**老版本升上来的**，他真正选过的值还在老键里。把它当成"用户选了默认值"
// 会让所有老用户的画质、网格、排序在升级那一刻被悄悄重置 —— 而这不会崩、只会错。
//
// 所以这三个函数的口径是：**存过就按 cfg 读，没存过才看老键**。老键只在迁移那一次有效，
// 一旦设置页（或桥）写过一次 cfg，老键就再也影响不了读数。
// 反过来的另一种写法（每次都先看老键）看起来更"兼容"，实际会让用户改完设置又被老值盖回去。

/** 布尔：`cfg` 存过就用它，否则用老键读到的那个值。 */
fun SharedPreferences.settingsBool(key: SettingKey.Bool, legacy: Boolean): Boolean =
    if (contains(key.id)) SettingsStore(this).read(key) else legacy

/** 整数：口径同上；`cfg` 上存了一个坏值时 [SettingKey.Int.range] 会把它退回默认值。 */
fun SharedPreferences.settingsInt(key: SettingKey.Int, legacy: Int): Int =
    if (contains(key.id)) SettingsStore(this).read(key) else legacy

/** 枚举档位名：**没存过**返回 null（调用方据此决定要不要看老键），存过一定返回合法名字。 */
fun SharedPreferences.storedChoiceName(key: SettingKey.Choice<*>): String? =
    if (contains(key.id)) SettingsStore(this).readChoiceName(key) else null

/**
 * 一次读全表的快照。
 *
 * 为什么不做成 `Map<String, Any>` 直接在界面里拆箱：拆箱代码会散到每个 item 的渲染分支里，
 * 而"这个键的默认值是什么"只有 [SettingKey.def] 说了算。快照把类型收在这五个方法里，
 * 界面只写 `snap.bool(SettingsKeys.General.confirmDelete)`。
 */
class SettingsSnapshot(private val values: Map<String, Any>) {

    fun bool(key: SettingKey.Bool): Boolean = values[key.id] as? Boolean ?: key.def

    fun int(key: SettingKey.Int): Int = values[key.id] as? Int ?: key.def

    fun text(key: SettingKey.Text): String = values[key.id] as? String ?: key.def

    fun strings(key: SettingKey.Strings): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return values[key.id] as? Set<String> ?: key.def
    }

    fun <E : Enum<E>> choice(key: SettingKey.Choice<E>): E {
        val name = values[key.id] as? String ?: return key.def
        return key.options.firstOrNull { it.name == name } ?: key.def
    }

    /**
     * 按**档位名**读一条枚举设置（星投影也能调）。
     *
     * 与 [choice] 的分工：类型已知时用 [choice] 拿枚举成员；设置列表遍历时只知道
     * `SettingKey.Choice<*>`，那时用这个拿 name（落盘存的就是 name）。
     */
    fun choiceName(key: SettingKey.Choice<*>): String {
        val name = values[key.id] as? String ?: return key.defName
        return key.options.firstOrNull { it.name == name }?.name
            ?: key.options.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
            ?: key.defName
    }

    /** 这一条在磁盘上有没有存过（"没存过"与"存了默认值"对界面的含义不同）。 */
    fun has(key: SettingKey<*>): Boolean = values.containsKey(key.id)

    /** 给"本机数据说明"用：键名 → 可读值，一律字符串，免得 JSON 里出现 Kotlin 类型名。 */
    fun forExport(): Map<String, String> =
        SettingsKeys.all.sortedBy { it.id }.associate { key ->
            key.id to when (key) {
                is SettingKey.Bool -> bool(key).toString()
                is SettingKey.Int -> int(key).toString()
                is SettingKey.Text -> text(key).ifEmpty { "（空）" }
                is SettingKey.Strings -> strings(key).sorted().joinToString(",").ifEmpty { "（空）" }
                is SettingKey.Choice<*> -> values[key.id] as? String ?: key.defName
            }
        }
}
