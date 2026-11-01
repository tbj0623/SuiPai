package com.suipai.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SettingsModel` 的四条完整性不变量。
 *
 * 为什么值得单独写一份：这一层是"设置中心有哪些项"的唯一目录，而它的错法全是
 * **编译得过、界面也画得出来**的那一类 ——
 * 两个分组里出现同名的一项（搜索时撞在一起，用户点了哪颗说不清）、
 * 一个 Choice 的默认值不在自己的选项表里（打开设置页时那颗格子不亮，看起来像坏了）、
 * 一个分组被删空了（页面上少一段，没人发现）。这些在设备上要点三次才看得出，在 JVM 上是三条断言。
 *
 * 纪律（与本项目其它守卫一致）：每一类判定都配一个"故意喂坏例子必须变红"的自检函数，
 * 而且判据做成纯函数，能直接喂假数据。
 */
class SettingsModelTest {

    // ── 判定（纯函数，便于自检）──────────────────────────────────────

    /** 重复的 key。 */
    fun duplicateKeys(items: List<SettingItem>): List<String> =
        items.groupBy { it.key }.filter { it.value.size > 1 }.keys.sorted()

    /** 同一分组里重复的标题（与 `FlagTableGuardTest` 的"同抽屉不许同名格"同一条纪律）。 */
    fun duplicateTitlesWithinGroup(items: List<SettingItem>): List<String> =
        items.groupBy { it.group }
            .flatMap { (g, list) ->
                list.groupBy { it.title }.filter { it.value.size > 1 }
                    .map { "${g.title}：${it.key}" }
            }
            .sorted()

    /**
     * Choice 的默认值不在选项集合里 —— 这一条正是任务里点名要钉的。
     *
     * 判据从 [SettingItem.Choice.pref] 取默认值与选项表（两者都在 `SettingsKeys` 里，
     * 是唯一事实来源），再要求 [SettingItem.Choice.options] 与它一致。
     */
    fun badChoiceDefaults(items: List<SettingItem>): List<String> = items.mapNotNull { item ->
        if (item !is SettingItem.Choice<*>) return@mapNotNull null
        val pref = item.pref
        val bad = pref.def !in pref.options
        val drifted = item.options.map { it.name } != pref.options.map { it.name }
        when {
            bad -> "${item.key}：默认值 ${pref.def.name} 不在选项 ${pref.options.map { it.name }} 里"
            drifted -> "${item.key}：界面选项 ${item.options.map { it.name }} 与落盘选项 ${pref.options.map { it.name }} 不一致"
            else -> null
        }
    }

    /** Number 的默认值不在自己的区间里。 */
    fun badNumberDefaults(items: List<SettingItem>): List<String> = items.mapNotNull { item ->
        if (item !is SettingItem.Number) return@mapNotNull null
        val pref = item.pref
        when {
            pref.def !in pref.range -> "${item.key}：默认值 ${pref.def} 不在 ${pref.range} 里"
            pref.range.isEmpty() -> "${item.key}：空区间"
            item.step <= 0 -> "${item.key}：步长 ${item.step} 必须是正数"
            else -> null
        }
    }

    /** 空分组（一个分组标题挂在列表里却没有任何内容）。 */
    fun emptyGroups(items: List<SettingItem>): List<String> =
        SettingsGroup.entries.filter { g -> items.none { it.group == g } }.map { it.title }

    /** 分组标题重复。 */
    fun duplicateGroupTitles(groups: List<SettingsGroup>): List<String> =
        groups.groupBy { it.title }.filter { it.value.size > 1 }.keys.sorted()

    /** 每一项都真的挂着落盘键（Toggle / Choice / Number 三种可写项不许没有 key）。 */
    fun writableWithoutPref(items: List<SettingItem>): List<String> = items.mapNotNull { item ->
        when (item) {
            is SettingItem.Toggle -> if (item.pref.id.isEmpty()) item.key else null
            is SettingItem.Choice<*> -> if (item.pref.id.isEmpty()) item.key else null
            is SettingItem.Number -> if (item.pref.id.isEmpty()) item.key else null
            else -> null
        }
    }

    // ── 真目录 ────────────────────────────────────────────────────────

    @Test
    fun everyKeyIsGloballyUnique() {
        assertEquals("目录里有重复的 key：", emptyList<String>(), duplicateKeys(SettingsModel.items()))
        // 非空判：读法失效（items() 返回空表）会让这一条绿着空跑。
        assertTrue("目录只有 ${SettingsModel.items().size} 项，抽取器大概失效了",
            SettingsModel.items().size >= 20)
    }

    @Test
    fun everyGroupIsNonEmpty() {
        assertEquals("这些分组一条设置都没有：", emptyList<String>(), emptyGroups(SettingsModel.items()))
    }

    @Test
    fun choiceDefaultsLiveInsideTheirOptionSets() {
        assertEquals("Choice 的默认值/选项对不上：", emptyList<String>(), badChoiceDefaults(SettingsModel.items()))
        // 非空判：Choice 一个都没抽到 = 判据失效
        val choices = SettingsModel.items().count { it is SettingItem.Choice<*> }
        assertTrue("只核对到 $choices 个 Choice，抽取器大概失效了", choices >= 6)
    }

    @Test
    fun numberDefaultsLiveInsideTheirRanges() {
        assertEquals("Number 的默认值/区间对不上：", emptyList<String>(), badNumberDefaults(SettingsModel.items()))
        val numbers = SettingsModel.items().count { it is SettingItem.Number }
        assertTrue("只核对到 $numbers 个 Number，抽取器大概失效了", numbers >= 3)
    }

    @Test
    fun groupTitlesAreDistinct() {
        assertEquals("分组标题重复（搜索与导航都会撞）：", emptyList<String>(),
            duplicateGroupTitles(SettingsGroup.entries.toList()))
    }

    @Test
    fun noDuplicateTitleWithinAGroup() {
        assertEquals("同一分组里有两项同名：", emptyList<String>(),
            duplicateTitlesWithinGroup(SettingsModel.items()))
    }

    @Test
    fun writableItemsCarryAPreferenceKey() {
        assertEquals("这些可写项没有落盘键：", emptyList<String>(),
            writableWithoutPref(SettingsModel.items()))
    }

    @Test
    fun everyModelKeyIsUniqueAcrossTheWholeSettingsTree() {
        // 目录 key 与落盘键 id 是两套名字：前者给人/给搜索，后者给磁盘。
        // 两条都要唯一；而且落盘键必须都带 cfg_ 前缀（`resetAll` 与"别撞老表"都靠它）。
        val ids = SettingsKeys.all.map { it.id }
        assertEquals("落盘键名重复：", emptyList<String>(),
            ids.groupBy { it }.filter { it.value.size > 1 }.keys.sorted())
        val noPrefix = ids.filterNot { it.startsWith(SettingsKeys.PREFIX) }
        assertTrue("这些键没带 ${SettingsKeys.PREFIX} 前缀：$noPrefix", noPrefix.isEmpty())
        // `FlagTableGuardTest.prefKeysAreGloballyUnique` 那边是老表的键；这里再确认新键一个都不落进老前缀。
        val legacyPrefixes = listOf("yishun_", "album_", "gallery_", "hide_", "viewer_", "camera_", "ai_")
        val clash = ids.filter { id -> legacyPrefixes.any { id.startsWith(it) } }
        assertTrue("新键用了老表的前缀，会撞上 FlagTableGuardTest：$clash", clash.isEmpty())
    }

    @Test
    fun everyDeclarationInKeysAppearsInTheCatalog() {
        // 反向：落盘键定义了却没有任何界面入口 = 死键（项目里已有 `deadPreferenceKeys` 那类教训）。
        val catalogPrefs = SettingsModel.items().mapNotNull { item ->
            when (item) {
                is SettingItem.Toggle -> item.pref.id
                is SettingItem.Choice<*> -> item.pref.id
                is SettingItem.Number -> item.pref.id
                else -> null
            }
        }.toSet()
        // 内部记账键（引导、搜索历史、导出时间这些）不进设置列表，是设计如此：
        // 它们没有"可拨的开关"，而是别的动作顺手写的。列在这里就是那份豁免名单，
        // 加一条新的内部键必须往这里加一行 —— 逼人想一次"它到底要不要出现在设置里"。
        val internal = setOf(
            SettingsKeys.General.onboarded.id,
            SettingsKeys.General.onboardingVersion.id,
            SettingsKeys.Privacy.exportAt.id,
            SettingsKeys.Privacy.noticeSeen.id,
            SettingsKeys.Storage.cacheCleanAt.id,
            SettingsKeys.Self.searchHistory.id,
            SettingsKeys.Self.hintSeen.id,
            SettingsKeys.Self.compact.id,
        )
        val orphans = SettingsKeys.all.map { it.id }
            .filterNot { it in catalogPrefs || it in internal }
        assertTrue("这些落盘键既不在设置列表里、也不在内部键豁免表里（写进去没人看得见）：$orphans",
            orphans.isEmpty())
        // 豁免名单本身不许腐烂：里面每一条都得真的存在于 SettingsKeys
        val known = SettingsKeys.all.map { it.id }.toSet()
        val ghost = internal.filterNot { it in known }
        assertTrue("豁免表里有点不存在的键：$ghost", ghost.isEmpty())
    }

    @Test
    fun searchFindsByTitleBodyAndKeyword() {
        // 中文搜标题
        assertTrue("搜「画质」应当命中相机那一项",
            SettingsModel.search("画质").any { it.key == "camera_jpeg" })
        // 英文搜关键词（C 端用户会打 jpeg / grid）
        assertTrue("搜 jpeg 应当命中相机画质",
            SettingsModel.search("jpeg").any { it.key == "camera_jpeg" })
        assertTrue("搜 grid 应当命中网格",
            SettingsModel.search("grid").any { it.key == "appearance_grid_columns" })
        // 搜 body 里的字
        assertTrue("搜「九宫格」应当命中辅助线（只在副标题里出现过）",
            SettingsModel.search("九宫格").any { it.key == "camera_show_grid" })
        // 空搜索返回全部
        assertEquals(SettingsModel.items().size, SettingsModel.search("   ").size)
        // 搜不到就是空
        assertTrue("搜一个不存在的词不该有结果", SettingsModel.search("zzzz没有这个东西").isEmpty())
    }

    @Test
    fun searchGroupingDropsEmptyGroups() {
        val groups = SettingsModel.searchGrouped("缓存")
        assertTrue("搜「缓存」至少应当命中存储那一组", groups.any { it.first == SettingsGroup.STORAGE })
        assertTrue("没有命中的分组不许留在列表里：${groups.map { it.first.title }}",
            groups.all { it.second.isNotEmpty() })
        // 全部命中时分组数等于全部分组数
        assertEquals("空词应当返回全部分组", SettingsGroup.entries.size,
            SettingsModel.searchGrouped("").size)
    }

    // ── 自检：每条判定都要能因为坏例子变红 ─────────────────────────────

    /**
     * 自检用假项：直接造，不从真目录 copy。
     * 走 copy 的那一版在泛型上要来回强转，反而容易把"判据本身有没有生效"掩盖掉。
     */
    private fun fakeToggle(key: String, group: SettingsGroup, title: String) = SettingItem.Toggle(
        key = key,
        group = group,
        title = title,
        note = "",
        pref = SettingsKeys.General.confirmDelete,
    )

    @Test
    fun checkersActuallyCatchBadCode() {
        // 1. 重复 key
        val dupItems = listOf(
            fakeToggle("dup", SettingsGroup.GENERAL, "甲"),
            fakeToggle("dup", SettingsGroup.APPEARANCE, "乙"),
        )
        assertEquals("重复 key 没被抓到", listOf("dup"), duplicateKeys(dupItems))

        // 2. 同组同名 —— 两个不同 key、同一个分组、同一个标题
        val sameTitle = listOf(
            fakeToggle("a", SettingsGroup.CAMERA, "同名"),
            fakeToggle("b", SettingsGroup.CAMERA, "同名"),
        )
        assertEquals(
            "同组同名没被抓到",
            listOf("相机：同名"),
            duplicateTitlesWithinGroup(sameTitle),
        )
        // 不同分组的同名不算违规（用户看到的是两段不同标题下的两个动作）
        val acrossGroups = listOf(
            fakeToggle("c", SettingsGroup.CAMERA, "同名"),
            fakeToggle("d", SettingsGroup.GALLERY, "同名"),
        )
        assertTrue("不同分组的同名被误判：${duplicateTitlesWithinGroup(acrossGroups)}",
            duplicateTitlesWithinGroup(acrossGroups).isEmpty())

        // 3. Choice：界面选项表与落盘选项表对不上（加了一档却忘了把默认值算进来）
        val badDefault = SettingItem.Choice(
            key = "fake_choice",
            group = SettingsGroup.APPEARANCE,
            title = "假选项",
            note = "",
            pref = SettingsKeys.Appearance.themeMode,
            options = ThemeMode.entries.filterNot { it == ThemeMode.FOLLOW_SYSTEM },
            labelOf = { it.label },
        )
        assertEquals(
            "选项表与落盘表不一致没被抓到",
            listOf("fake_choice：界面选项 [DARKROOM, DAYLIGHT] 与落盘选项 [FOLLOW_SYSTEM, DARKROOM, DAYLIGHT] 不一致"),
            badChoiceDefaults(listOf(badDefault)),
        )

        // 4. Number 默认值落在区间外
        val badNumber = SettingItem.Number(
            key = "fake_number",
            group = SettingsGroup.CAMERA,
            title = "假数值",
            note = "",
            pref = SettingKey.Int("cfg_fake", 50, SettingsGroup.CAMERA, range = 95..100),
        )
        assertEquals(
            "区间外的默认值没被抓到",
            listOf("fake_number：默认值 50 不在 95..100 里"),
            badNumberDefaults(listOf(badNumber)),
        )

        // 5. 空分组
        val onlyGeneral = listOf(fakeToggle("x", SettingsGroup.GENERAL, "通用项"))
        assertTrue("删空一个分组没被抓到", emptyGroups(onlyGeneral).isNotEmpty())
        assertEquals("删空 7 个分组应当报 7 条", 7, emptyGroups(onlyGeneral).size)

        // 6. 分组标题重复（枚举保证不了这一条，但判据本身要能红）
        assertEquals(
            "分组标题重复没被抓到",
            listOf("通用"),
            duplicateGroupTitles(listOf(SettingsGroup.GENERAL, SettingsGroup.GENERAL)),
        )

        // 7. 可写项缺落盘键
        assertTrue("可写项缺 pref 没被抓到",
            writableWithoutPref(
                listOf(
                    SettingItem.Toggle(
                        key = "no_pref",
                        group = SettingsGroup.GENERAL,
                        title = "缺键",
                        note = "",
                        pref = SettingKey.Bool("", false, SettingsGroup.GENERAL),
                    ),
                ),
            ).isNotEmpty())

        // 干净样本不许误判
        assertTrue("干净目录被误判成有重复：${duplicateKeys(SettingsModel.items())}",
            duplicateKeys(SettingsModel.items()).isEmpty())
        assertTrue("干净目录被误判成有同名标题",
            duplicateTitlesWithinGroup(SettingsModel.items()).isEmpty())
        assertTrue("干净目录被误判成有空分组", emptyGroups(SettingsModel.items()).isEmpty())
        assertTrue("干净目录被误判成缺落盘键", writableWithoutPref(SettingsModel.items()).isEmpty())
    }
}
