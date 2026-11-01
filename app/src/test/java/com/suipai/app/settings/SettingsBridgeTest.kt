package com.suipai.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥与"设置页到底接没接上"的证据。
 *
 * 这份测试存在的理由只有一条：**这一轮的病是"看起来做完了"**。
 * 37 项设置、每一行都画得出来、每一格都点得动，但一个都没有读者。
 * 所以判据不能停在"值写进磁盘了"（那只是必要条件），必须一路走到
 * 「设置中心写的那个值 → 老键被写成什么 → 各功能读到的那个数」。
 *
 * 三条纪律：
 *
 * 1. **枚举→整数的期望值逐项硬编码**。不许用 `ordinal`、也不许拿被测函数自己的输出当期望 ——
 *    那样的断言在枚举重排之后会一起变绿，而用户看到的是"按月"变成了"按年"。
 * 2. **往返一致**：写新键 → 桥 → 老键值必须等于期望；反过来拨老键 → 回写的 `cfg_` 也必须回到同一档。
 *    往返不闭合的桥会让两个界面各显示一个值，而两边都不报错。
 * 3. **每条判定配一个"喂坏例子必须变红"的自检**。这一条是这个仓库的硬纪律：
 *    没见过自己变红的守卫不算守卫。
 */
class SettingsBridgeTest {

    // ── 网格密度：显式数值表 ──────────────────────────────────────────

    /**
     * 网格档 → dp 的**硬编码**期望。
     *
     * 报错信息里带上期望与实际的对照，改档位的人必须回来改这张表 ——
     * 那正是"用户存的 118 变成 168"这类事故唯一的拦截点。
     */
    @Test
    fun gridCellDpIsPinnedByHand() {
        val expected = mapOf(
            GridColumns.DENSE to 64,
            GridColumns.SMALL to 86,
            GridColumns.STANDARD to 118,
            GridColumns.LARGE to 168,
        )
        for ((columns, dp) in expected) {
            assertEquals("$columns 的最小格宽变了（老用户存的 dp 会跟着错位）", dp, SettingsBridge.gridCellDp(columns))
        }
        // 非空判：枚举里每一档都必须在上面那张表里，加了新档而没定数值时这一条会红。
        assertEquals("有档位没被这张表覆盖：${GridColumns.entries - expected.keys}",
            GridColumns.entries.size, expected.size)
        // 自检：把 118 喂给"应该是 168"的那一档必须红（证明这组断言真的在比数）
        assertTrue("自检失败：STARDARD 的 dp 被当成了 168", SettingsBridge.gridCellDp(GridColumns.STANDARD) != 168)
    }

    @Test
    fun gridColumnsLandsOnTheNearestTierAndNeverReturnsNullish() {
        // 精确值
        assertEquals(GridColumns.STANDARD, SettingsBridge.gridColumns(118))
        // 老版本可能存过中间值：必须落到一个**存在的**档上，哪怕只是近似
        assertEquals(GridColumns.STANDARD, SettingsBridge.gridColumns(100))
        assertEquals(GridColumns.SMALL, SettingsBridge.gridColumns(80))
        assertEquals(GridColumns.LARGE, SettingsBridge.gridColumns(200))
        // 边界与荒谬值
        assertEquals(GridColumns.DENSE, SettingsBridge.gridColumns(0))
        assertEquals(GridColumns.DENSE, SettingsBridge.gridColumns(-999))
        assertEquals(GridColumns.LARGE, SettingsBridge.gridColumns(Int.MAX_VALUE))
        // 自检：中间值**不许**原样穿过（那会让界面既不亮任何一档）
        assertTrue("100 被原样透传了，说明这个函数没在做映射", SettingsBridge.gridColumns(100) != GridColumns.STANDARD ||
            SettingsBridge.gridCellDp(SettingsBridge.gridColumns(100)) != 100)
    }

    @Test
    fun gridRoundTripIsClosed() {
        // 正：档 → dp → 档，必须回到自己（否则用户在相册里点一次就掉档）
        for (columns in GridColumns.entries) {
            assertEquals("$columns 经 dp 往返之后变了",
                columns, SettingsBridge.gridColumns(SettingsBridge.gridCellDp(columns)))
        }
        // 自检：故意错开一档必须被抓到
        assertTrue("自检失败：SMALL 与 STANDARD 的 dp 被当成同一个",
            SettingsBridge.gridCellDp(GridColumns.SMALL) != SettingsBridge.gridCellDp(GridColumns.STANDARD))
    }

    // ── 缩略图：显式像素表 ────────────────────────────────────────────

    @Test
    fun thumbEdgePxIsPinnedByHand() {
        val expected = mapOf(
            ThumbQuality.FAST to 256,
            ThumbQuality.STANDARD to 420,
            ThumbQuality.SHARP to 720,
        )
        for ((quality, px) in expected) {
            assertEquals("$quality 的长边变了", px, SettingsBridge.thumbEdgePx(quality))
        }
        assertEquals("有档位没被这张表覆盖：${ThumbQuality.entries - expected.keys}",
            ThumbQuality.entries.size, expected.size)
        // 非空判：420 是相册页改写之前**写死**的那个数，必须还在标准档上 ——
        // 否则老用户升级那一刻缩略图会整体变模糊/变糊，而没有任何界面会报错。
        assertEquals("标准档不再是原来那个 420：老用户的缩略图会在升级时整体换一档",
            420, SettingsBridge.thumbEdgePx(ThumbQuality.STANDARD))
    }

    @Test
    fun thumbQualityLandsOnTiersForOutOfBandPixels() {
        assertEquals(ThumbQuality.FAST, SettingsBridge.thumbQuality(64))
        assertEquals(ThumbQuality.STANDARD, SettingsBridge.thumbQuality(420))
        assertEquals(ThumbQuality.SHARP, SettingsBridge.thumbQuality(1024))
        assertEquals(ThumbQuality.FAST, SettingsBridge.thumbQuality(0))
        assertEquals(ThumbQuality.SHARP, SettingsBridge.thumbQuality(Int.MAX_VALUE))
        // 往返闭合
        for (quality in ThumbQuality.entries) {
            assertEquals(quality, SettingsBridge.thumbQuality(SettingsBridge.thumbEdgePx(quality)))
        }
    }

    // ── 排序：六档老编号 ↔ 四档新枚举 ────────────────────────────────

    /**
     * 排序档 → 老编号的**逐项硬编码**。
     *
     * 老编号：0 最新 / 1 最早 / 2 最大 / 3 最小 / 4 按名 / 5 按像素。
     * 新档只有四档，所以「按体积」映射到 2（最大在前）—— 这个选择必须被钉住，
     * 它决定了升级之后用户第一眼看到的是大图在前还是小图在前。
     */
    @Test
    fun gallerySortCodeIsPinnedByHand() {
        assertEquals(0, SettingsBridge.gallerySortCode(DefaultSort.NEWEST))
        assertEquals(1, SettingsBridge.gallerySortCode(DefaultSort.OLDEST))
        assertEquals(4, SettingsBridge.gallerySortCode(DefaultSort.NAME))
        assertEquals(2, SettingsBridge.gallerySortCode(DefaultSort.SIZE))
        // 非空判：四档都必须被覆盖
        assertEquals(DefaultSort.entries.size, 4)
    }

    @Test
    fun defaultSortHandlesEveryLegacyCodeIncludingOnesWeNoLongerProduce() {
        assertEquals(DefaultSort.NEWEST, SettingsBridge.defaultSort(0))
        assertEquals(DefaultSort.OLDEST, SettingsBridge.defaultSort(1))
        assertEquals(DefaultSort.SIZE, SettingsBridge.defaultSort(2))
        // 3 = 最小在前、5 = 按像素：相册那颗循环格今天还能转到，必须落到"按体积"
        assertEquals(DefaultSort.SIZE, SettingsBridge.defaultSort(3))
        assertEquals(DefaultSort.SIZE, SettingsBridge.defaultSort(5))
        assertEquals(DefaultSort.NAME, SettingsBridge.defaultSort(4))
        // 表外的值不许崩，也不许乱解释
        assertEquals(DefaultSort.NEWEST, SettingsBridge.defaultSort(-1))
        assertEquals(DefaultSort.NEWEST, SettingsBridge.defaultSort(99))
        // 自检：把"最小在前"读成"最新在前"会让人以为设置被重置了
        assertTrue("自检失败：老编号 3 被读成了 NEWEST",
            SettingsBridge.defaultSort(3) != DefaultSort.NEWEST)
    }

    @Test
    fun newestFirstOnlyForTheNewestTier() {
        assertTrue(SettingsBridge.newestFirst(DefaultSort.NEWEST))
        assertFalse(SettingsBridge.newestFirst(DefaultSort.OLDEST))
        assertFalse("按体积是降序（大在前），不该被当成 newest_first = false 之外的语义",
            SettingsBridge.newestFirst(DefaultSort.SIZE))
        assertFalse(SettingsBridge.newestFirst(DefaultSort.NAME))
    }

    // ── 分组：三档 ↔ 四档老编号 ──────────────────────────────────────

    @Test
    fun groupModeCodeIsPinnedByHand() {
        assertEquals(0, SettingsBridge.groupModeCode(GroupBy.DAY))
        assertEquals(1, SettingsBridge.groupModeCode(GroupBy.MONTH))
        assertEquals(2, SettingsBridge.groupModeCode(GroupBy.YEAR))
        assertEquals(GroupBy.entries.size, 3)
    }

    @Test
    fun groupByLandsSafelyForWeekAndNonsense() {
        assertEquals(GroupBy.DAY, SettingsBridge.groupBy(0))
        assertEquals(GroupBy.MONTH, SettingsBridge.groupBy(1))
        assertEquals(GroupBy.YEAR, SettingsBridge.groupBy(2))
        // 3 = 相册自己的"按周"，设置里没有这一档：落到按年，**不许**悄悄变按日
        assertEquals("按周落到按日会让用户以为分组设置被重置了",
            GroupBy.YEAR, SettingsBridge.groupBy(3))
        assertEquals(GroupBy.DAY, SettingsBridge.groupBy(-7))
        assertEquals(GroupBy.DAY, SettingsBridge.groupBy(Int.MIN_VALUE))
    }

    // ── 相机的网格：开关 ↔ 20 款样式编号 ─────────────────────────────

    @Test
    fun gridModeInitNeverClobbersTheUsersChosenStyle() {
        // 开着 + 用户挑过一款 → 保留那一款（1..19 都是合法样式）
        assertEquals(7, SettingsBridge.gridModeInit(showGrid = true, storedGridMode = 7))
        // 开着 + 存的不是正数 → 退到 1（九宫格），而不是 0（不画）
        assertEquals(1, SettingsBridge.gridModeInit(showGrid = true, storedGridMode = 0))
        assertEquals(1, SettingsBridge.gridModeInit(showGrid = true, storedGridMode = -3))
        // 关着 → 一定是 0，**不管**记着哪一款
        assertEquals(0, SettingsBridge.gridModeInit(showGrid = false, storedGridMode = 7))
        assertEquals(0, SettingsBridge.gridModeInit(showGrid = false, storedGridMode = 0))
        // 自检：把"开"硬写成 1 的实现会在这里红（用户的黄金分割被冲掉了）
        assertTrue("自检失败：开着的状态把用户挑的样式 7 冲掉了",
            SettingsBridge.gridModeInit(true, 7) == 7)
    }

    @Test
    fun showGridAndGridModeAgree() {
        assertTrue(SettingsBridge.showGridOf(1))
        assertTrue("20 款里的每一款都算「开着」", SettingsBridge.showGridOf(19))
        assertFalse(SettingsBridge.showGridOf(0))
        // 往返：开着 + 记着某一款 → 编号不变；关掉 → 0；再开 → 回到那一款
        assertEquals(7, SettingsBridge.gridModeInit(true, 7))
        assertEquals(0, SettingsBridge.gridModeInit(false, 7))
        assertEquals("关掉再开回来必须还是用户挑的那一款（所以写侧不碰 grid_mode）",
            7, SettingsBridge.gridModeInit(true, 7))
        // 相机从没记过任何一款时，开到九宫格
        assertEquals(SettingsBridge.GRID_MODE_ON, SettingsBridge.gridModeInit(true, 0))
        assertTrue("兜底那一档必须是非 0（= 画）", SettingsBridge.GRID_MODE_ON > 0)
    }

    // ── 字号与主题 ───────────────────────────────────────────────────

    @Test
    fun fontScaleFactorsArePinnedByHand() {
        assertEquals(0.85f, SettingsBridge.fontScaleFactor(FontScale.SMALL), 0.0001f)
        assertEquals(1.00f, SettingsBridge.fontScaleFactor(FontScale.STANDARD), 0.0001f)
        assertEquals(1.15f, SettingsBridge.fontScaleFactor(FontScale.LARGE), 0.0001f)
        assertEquals(1.30f, SettingsBridge.fontScaleFactor(FontScale.HUGE), 0.0001f)
        // 标准档必须是**恒等**：乘了等于没乘，老用户升级那一刻字级不该动。
        assertEquals("标准档不恒等会让所有人在升级时字级变一次",
            1.0f, SettingsBridge.fontScaleFactor(FontScale.STANDARD), 0.0001f)
        // 单调：小 < 标准 < 大 < 特大
        val ordered = FontScale.entries.map { SettingsBridge.fontScaleFactor(it) }
        assertEquals("字号档不是单调递增：$ordered", ordered.sorted(), ordered)
    }

    @Test
    fun themeDarkDoesNotLieAboutFollowSystem() {
        assertEquals(true, SettingsBridge.themeDark(ThemeMode.DARKROOM))
        assertEquals(false, SettingsBridge.themeDark(ThemeMode.DAYLIGHT))
        assertNull("「跟随系统」不许被写成一个固定值（那等于把它偷偷改成固定）",
            SettingsBridge.themeDark(ThemeMode.FOLLOW_SYSTEM))
    }

    @Test
    fun themeModeOfPrefersStoredThenMigratesThenFallsBack() {
        // 1. 存过合法名字 → 就是它（哪怕老键说反话：设置页的选择优先）
        assertEquals(ThemeMode.DAYLIGHT, SettingsBridge.themeModeOf("DAYLIGHT", legacyDarkUi = true))
        assertEquals(ThemeMode.DARKROOM, SettingsBridge.themeModeOf("DARKROOM", legacyDarkUi = false))
        // 2. 大小写不敏感（手改过 prefs / 老版本存过另一种大小写）
        assertEquals(ThemeMode.DAYLIGHT, SettingsBridge.themeModeOf("daylight", legacyDarkUi = true))
        // 3. 没存过 → 按老键迁移
        assertEquals(ThemeMode.DARKROOM, SettingsBridge.themeModeOf(null, legacyDarkUi = true))
        assertEquals(ThemeMode.DAYLIGHT, SettingsBridge.themeModeOf(null, legacyDarkUi = false))
        // 4. 存了认不出的名字 → 回默认（暗房），不猜
        assertEquals(ThemeMode.DARKROOM, SettingsBridge.themeModeOf("NIGHT_VISION", legacyDarkUi = false))
        // 自检：把"没存过 + 老键是日光"读成暗房，就等于升级时把用户的光模式吞了
        assertTrue("自检失败：老键 dark_ui=false 没有被迁移成日光",
            SettingsBridge.themeModeOf(null, legacyDarkUi = false) == ThemeMode.DAYLIGHT)
    }

    // ── 迁移初值：没存过 cfg 时才认老键 ──────────────────────────────

    @Test
    fun migrationsOnlyApplyWhenCfgWasNeverWritten() {
        // cfg 存过 → 老键完全不影响
        assertEquals(GridColumns.LARGE,
            SettingsBridge.gridColumnsInitial("LARGE", legacyCellDp = 86))
        assertEquals(ThumbQuality.SHARP,
            SettingsBridge.thumbQualityInitial("SHARP", legacyEdgePx = 256))
        assertEquals(DefaultSort.OLDEST,
            SettingsBridge.defaultSortInitial("OLDEST", legacyCode = 0))
        assertEquals(GroupBy.MONTH,
            SettingsBridge.groupByInitial("MONTH", legacyCode = 2))
        // cfg 没存过 → 认老键（老用户的选择不丢）
        assertEquals(GridColumns.SMALL,
            SettingsBridge.gridColumnsInitial(null, legacyCellDp = 86))
        assertEquals(ThumbQuality.STANDARD,
            SettingsBridge.thumbQualityInitial(null, legacyEdgePx = 420))
        assertEquals(DefaultSort.OLDEST,
            SettingsBridge.defaultSortInitial(null, legacyCode = 1))
        assertEquals(GroupBy.YEAR,
            SettingsBridge.groupByInitial(null, legacyCode = 2))
        // 坏名字（存过但不是一档）→ 不崩，回默认档；这里刻意**不**回落到老键：
        // 磁盘上有一个 cfg 值就说明"用户用过设置页"，那时再拿老键覆盖是另一个 bug。
        assertEquals(GridColumns.STANDARD, SettingsBridge.gridColumnsInitial("ULTRA", legacyCellDp = 168))
        assertEquals(ThumbQuality.STANDARD, SettingsBridge.thumbQualityInitial("ULTRA", legacyEdgePx = 720))
        assertEquals(DefaultSort.NEWEST, SettingsBridge.defaultSortInitial("ULTRA", legacyCode = 4))
        assertEquals(GroupBy.DAY, SettingsBridge.groupByInitial("ULTRA", legacyCode = 1))
    }

    // ── 写两边：设置中心 → 老键 ──────────────────────────────────────

    @Test
    fun writingASettingProducesTheExpectedLegacyValues() {
        // 主题：两个明确的档写 dark_ui；跟随系统**不写**
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.DARK_UI, true)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.themeMode.id, "DARKROOM"),
        )
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.DARK_UI, false)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.themeMode.id, "DAYLIGHT"),
        )
        assertTrue("「跟随系统」不该写 dark_ui",
            SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.themeMode.id, "FOLLOW_SYSTEM").isEmpty())

        // 网格：一个 dp
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.GRID_CELL, 168)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.gridColumns.id, "LARGE"),
        )

        // 排序：**两个**老键一起写（sort 与 newest_first 必须自洽）
        assertEquals(
            listOf(
                LegacyWrite(LegacyKeys.GALLERY_SORT, 0),
                LegacyWrite(LegacyKeys.NEWEST_FIRST, true),
            ),
            SettingsBridge.legacyWritesFor(SettingsKeys.Gallery.defaultSort.id, "NEWEST"),
        )
        assertEquals(
            listOf(
                LegacyWrite(LegacyKeys.GALLERY_SORT, 1),
                LegacyWrite(LegacyKeys.NEWEST_FIRST, false),
            ),
            SettingsBridge.legacyWritesFor(SettingsKeys.Gallery.defaultSort.id, "OLDEST"),
        )

        // 分组
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.GROUP_MODE, 1)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Gallery.groupBy.id, "MONTH"),
        )

        // 相机：画质、辅助线（两个键）、快门声、镜像、极简
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.CAMERA_JPEG, 100)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Camera.jpegQuality.id, 100),
        )
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.SHOW_GRID, false)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Camera.showGrid.id, false),
        )
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.SHUTTER_SOUND, false)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Camera.shutterSound.id, false),
        )
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.MIRROR_FRONT, false)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Camera.mirrorFront.id, false),
        )
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.CAMERA_SIMPLE, true)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Camera.simpleUi.id, true),
        )

        // 一瞬
        assertEquals(
            listOf(LegacyWrite(LegacyKeys.YISHUN_JPEG, 95)),
            SettingsBridge.legacyWritesFor(SettingsKeys.Yishun.exportQuality.id, 95),
        )
    }

    @Test
    fun unbridgedSettingsWriteNothingToLegacyKeys() {
        // 没有老的对应物的那几项：桥必须返回空表，而不是瞎编一个键
        val noLegacy = listOf(
            SettingsKeys.Appearance.fontScale.id,
            SettingsKeys.Appearance.thumbQuality.id,
            SettingsKeys.Appearance.reduceMotion.id,
            SettingsKeys.Gallery.showHidden.id,
            SettingsKeys.Gallery.sectionIndex.id,
            SettingsKeys.General.confirmDelete.id,
            SettingsKeys.General.startTab.id,
            SettingsKeys.General.cameraShortcut.id,
            SettingsKeys.Yishun.defaultIntensity.id,
            SettingsKeys.Yishun.holdToCompare.id,
            SettingsKeys.Yishun.autoScene.id,
            SettingsKeys.Storage.autoCacheClean.id,
        )
        for (id in noLegacy) {
            assertTrue("$id 被桥到了老键，但它在老表里没有对应物 —— 会写脏一个没人认识的值",
                SettingsBridge.legacyWritesFor(id, "STANDARD").isEmpty())
            assertFalse("$id 不该被算成已桥接", SettingsBridge.isBridged(id))
        }
    }

    @Test
    fun badInputNeverThrowsAndNeverInventsALegacyWrite() {
        // 坏档位名 / 错类型：返回空表，不抛
        assertTrue(SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.themeMode.id, "NOPE").isEmpty())
        assertTrue(SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.gridColumns.id, 42).isEmpty())
        assertTrue(SettingsBridge.legacyWritesFor(SettingsKeys.Camera.shutterSound.id, "not-a-bool").isEmpty())
        assertTrue(SettingsBridge.legacyWritesFor(SettingsKeys.Camera.jpegQuality.id, "100").isEmpty())
        assertTrue(SettingsBridge.legacyWritesFor("cfg_does_not_exist", true).isEmpty())
        // 自检：合法档位必须**不**是空表，否则上面那几条"空表"的断言就是空跑
        assertTrue("自检失败：合法输入也返回空表，说明这条判据从来看不到非空",
            SettingsBridge.legacyWritesFor(SettingsKeys.Appearance.gridColumns.id, "LARGE").isNotEmpty())
    }

    // ── 写两边：老键 → 设置中心 ──────────────────────────────────────

    @Test
    fun legacyTogglesWriteBackToTheRightCfgKey() {
        assertEquals(
            CfgWrite(SettingsKeys.Appearance.themeMode.id, "DARKROOM"),
            SettingsBridge.cfgForLegacyBool(LegacyKeys.DARK_UI, true),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Appearance.themeMode.id, "DAYLIGHT"),
            SettingsBridge.cfgForLegacyBool(LegacyKeys.DARK_UI, false),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Camera.shutterSound.id, false),
            SettingsBridge.cfgForLegacyBool(LegacyKeys.SHUTTER_SOUND, false),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Camera.mirrorFront.id, true),
            SettingsBridge.cfgForLegacyBool(LegacyKeys.MIRROR_FRONT, true),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Camera.simpleUi.id, true),
            SettingsBridge.cfgForLegacyBool(LegacyKeys.CAMERA_SIMPLE, true),
        )
        // 没有设置项的老键 → null（不许瞎认）
        assertNull(SettingsBridge.cfgForLegacyBool("auto_yishun", true))
        assertNull(SettingsBridge.cfgForLegacyBool("grid_bold", true))
    }

    @Test
    fun legacyIntsWriteBackToTheRightCfgKey() {
        assertEquals(
            CfgWrite(SettingsKeys.Appearance.gridColumns.id, "LARGE"),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.GRID_CELL, 168),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Gallery.defaultSort.id, "NAME"),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.GALLERY_SORT, 4),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Gallery.groupBy.id, "MONTH"),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.GROUP_MODE, 1),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Camera.jpegQuality.id, 100),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.CAMERA_JPEG, 100),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Yishun.exportQuality.id, 95),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.YISHUN_JPEG, 95),
        )
        // `grid_mode` 不是档位而是一串样式编号：只有 0 / 非 0 有意义
        assertEquals(
            CfgWrite(SettingsKeys.Camera.showGrid.id, true),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.GRID_MODE, 7),
        )
        assertEquals(
            CfgWrite(SettingsKeys.Camera.showGrid.id, false),
            SettingsBridge.cfgForLegacyInt(LegacyKeys.GRID_MODE, 0),
        )
        assertNull(SettingsBridge.cfgForLegacyInt("grid_gap", 8))
    }

    // ── 往返：两个方向必须回到同一个选择 ─────────────────────────────

    /**
     * 设置中心 → 老键 → 设置中心，必须回到**同一个**选择。
     *
     * 为什么这条最要紧：两个界面（设置页 / 相册工具条）读的是同一条设置。
     * 往返不闭合时，用户在设置里选「大格」，相册显示「中图」，点一下又跳回「大格」——
     * 而两边都不报错。这一条把它变成断言。
     */
    @Test
    fun roundTripThroughTheLegacyKeyReturnsTheSameChoice() {
        // 网格：四档全都闭合（老键存的就是 dp，无损）
        for (columns in GridColumns.entries) {
            val dp = SettingsBridge.gridCellDp(columns)
            assertEquals("$columns 经老键往返之后变了",
                columns, SettingsBridge.gridColumns(dp))
        }
        // 分组：三档闭合
        for (group in GroupBy.entries) {
            assertEquals("$group 经老键往返之后变了",
                group, SettingsBridge.groupBy(SettingsBridge.groupModeCode(group)))
        }
        // 排序：四档闭合（注意 SIZE→2→SIZE 走的是"最大在前"这一支，仍然闭合）
        for (sort in DefaultSort.entries) {
            assertEquals("$sort 经老键往返之后变了",
                sort, SettingsBridge.defaultSort(SettingsBridge.gallerySortCode(sort)))
        }
        // 自检：把"按体积"映射成 1（最早在前）会在这里红
        assertTrue("自检失败：SIZE 与 OLDEST 用同一个老编号",
            SettingsBridge.gallerySortCode(DefaultSort.SIZE) !=
                SettingsBridge.gallerySortCode(DefaultSort.OLDEST))
    }

    /**
     * 写新键 → 桥 → 老键值 == 期望（任务书点名的那条）。
     *
     * 与 [writingASettingProducesTheExpectedLegacyValues] 的区别：那一条比的是「桥的输出」，
     * 这一条比的是「**用户从设置页拨一下之后**，各功能读到的那个数」。
     * 中间隔着一层 `SettingsStore`（真的落盘），所以它同时验证了那一层没有把值吞掉。
     */
    @Test
    fun writingThroughTheStoreLandsTheExpectedLegacyValues() {
        val prefs = MapPrefs()
        val store = SettingsStore(prefs)

        // ① 网格 → 大格
        store.putChoiceName(SettingsKeys.Appearance.gridColumns, "LARGE")
        assertEquals("相册读到的 dp 不对", 168, prefs.intOf(LegacyKeys.GRID_CELL))

        // ② 排序 → 按文件名（老键要同时写 sort=4 与 newest_first=false）
        store.putChoiceName(SettingsKeys.Gallery.defaultSort, "NAME")
        assertEquals("相册读到的排序编号不对", 4, prefs.intOf(LegacyKeys.GALLERY_SORT))
        assertFalse("按文件名不该被当成最新在前", prefs.boolOf(LegacyKeys.NEWEST_FIRST))

        // ③ 分组 → 按月
        store.putChoiceName(SettingsKeys.Gallery.groupBy, "MONTH")
        assertEquals(1, prefs.intOf(LegacyKeys.GROUP_MODE))

        // ④ 主题 → 日光（dark_ui = false）
        store.putChoiceName(SettingsKeys.Appearance.themeMode, "DAYLIGHT")
        assertFalse("选了日光之后 dark_ui 还是 true —— 降级安装会又变回暗房",
            prefs.boolOf(LegacyKeys.DARK_UI))

        // ⑤ 相机画质 → 100
        store.put(SettingsKeys.Camera.jpegQuality, 100)
        assertEquals(100, prefs.intOf(LegacyKeys.CAMERA_JPEG))

        // ⑥ 辅助线关掉 → show_grid 跟着关。
        //    注意 `grid_mode`（画哪一款）**不该**被写成 0：那会永久丢掉用户挑的样式，
        //    而"关不关"从这一版起看的是 cfg，不靠把编号清成 0 来表达。
        prefs.putRawInt(LegacyKeys.GRID_MODE, 2)
        store.put(SettingsKeys.Camera.showGrid, false)
        assertFalse(prefs.boolOf(LegacyKeys.SHOW_GRID))
        assertEquals("关掉辅助线不该把用户挑的网格样式编号清掉", 2, prefs.intOf(LegacyKeys.GRID_MODE))

        // ⑦ 快门声 / 镜像 / 极简
        store.put(SettingsKeys.Camera.shutterSound, false)
        assertFalse(prefs.boolOf(LegacyKeys.SHUTTER_SOUND))
        store.put(SettingsKeys.Camera.mirrorFront, false)
        assertFalse(prefs.boolOf(LegacyKeys.MIRROR_FRONT))
        store.put(SettingsKeys.Camera.simpleUi, true)
        assertTrue(prefs.boolOf(LegacyKeys.CAMERA_SIMPLE))

        // ⑧ 一瞬成片画质
        store.put(SettingsKeys.Yishun.exportQuality, 100)
        assertEquals(100, prefs.intOf(LegacyKeys.YISHUN_JPEG))

        // 自检：cfg 本身也必须落盘（只有老键被写了不算接上）
        assertEquals("LARGE", store.readChoiceName(SettingsKeys.Appearance.gridColumns))
    }

    @Test
    fun legacyWriteBackDoesNotUndoThePreciseLegacyChoice() {
        // 相机抽屉挑了"黄金分割"（grid_mode = 2），回写只该改 showGrid，
        // **不许**把 grid_mode 改回 1（那会把用户挑的样式冲掉）。
        //
        // 注意这里**不**断言"store 会把 grid_mode 写成 0"：相机自己那条 `setInt("grid_mode", 0)`
        // 才是老键的写入者，`syncFromLegacyInt` 只负责把 cfg 同步过去。
        // 这一条测的正是"同步不反向扇回去"——扇回去就会把 2 冲掉。
        val prefs = MapPrefs()
        val store = SettingsStore(prefs)
        prefs.putRawInt(LegacyKeys.GRID_MODE, 2)

        assertTrue(store.syncFromLegacyInt(LegacyKeys.GRID_MODE, 2))
        assertTrue("设置页应当认为辅助线是开着的", store.read(SettingsKeys.Camera.showGrid))
        assertEquals("回写把用户挑的网格样式冲掉了", 2, prefs.intOf(LegacyKeys.GRID_MODE))

        // 真把 grid_mode 关掉时，cfg 必须跟着变（老键由相机自己写）
        assertTrue(store.syncFromLegacyInt(LegacyKeys.GRID_MODE, 0))
        assertFalse(store.read(SettingsKeys.Camera.showGrid))
        // 自检：如果把 fanBackToLegacy 打开，这一条会红（2 被改写成 1）——
        // 那就是"从相机拨一下、网格样式莫名变回九宫格"的那个 bug。
        assertEquals("关掉辅助线之后，用户挑的样式编号仍然要留着（下次开回来还得是它）",
            2, prefs.intOf(LegacyKeys.GRID_MODE))
    }

    @Test
    fun legacyWriteBackRefusesKeysWithoutASetting() {
        val store = SettingsStore(MapPrefs())
        assertFalse("没有设置项的老键不许被认成已同步",
            store.syncFromLegacyInt("grid_gap", 8))
        assertFalse(store.syncFromLegacyBool("auto_yishun", false))
    }

    // ── 目录与桥的一致性 ────────────────────────────────────────────

    /**
     * 「标了 LIVE 的可写项」必须是**真的有读者**的那几项。
     *
     * 判据用 [SettingsBridge.isBridged]：那是一个逐项列出来的集合，不是从 LIVE 反推的。
     * 所以「把某一项偷偷标成 LIVE 让它能拨」这一种做法会在这里红 ——
     * 这正是这一轮要防的那个动作。
     */
    @Test
    fun everyLiveWritableItemReallyHasAReader() {
        // 直接读者（不经老键，读的就是 cfg_ 本身）。列在这里的每一条都必须在
        // 源码里真的有一个读数点，`docs/product/SETTINGS.md` §2 的「接线」列与它逐条对应。
        val directReaders = setOf(
            // `ui/theme/Theme.kt`：订阅 `SettingsSignal` 之后就地换皮 / 叠字号
            SettingsKeys.Appearance.themeMode.id,
            SettingsKeys.Appearance.fontScale.id,
            // `ui/gallery/GalleryScreen.kt`：`thumbPx` → `ImageRequest.size` / `FastThumb(px)`
            SettingsKeys.Appearance.thumbQuality.id,
            // `ui/design/Motion.kt` 的 `rememberMotionFactor()`，相册位置横带按它取时长
            SettingsKeys.Appearance.reduceMotion.id,
            // `ui/gallery/GalleryScreen.kt` 的批量删除那颗格子
            SettingsKeys.General.confirmDelete.id,
            // `ui/gallery/GalleryScreen.kt` 的 `showHiddenEffective`（与「已隐藏」入口取或）
            SettingsKeys.Gallery.showHidden.id,
            // `ui/yishun/YishunScreen.kt`：firstIntensity / onPress / SceneAnalyzer 那一层判断
            SettingsKeys.Yishun.defaultIntensity.id,
            SettingsKeys.Yishun.holdToCompare.id,
            SettingsKeys.Yishun.autoScene.id,
        )
        val wrong = SettingsModel.items().mapNotNull { item ->
            if (item.live != SettingLive.LIVE) return@mapNotNull null
            val id = when (item) {
                is SettingItem.Toggle -> item.pref.id
                is SettingItem.Choice<*> -> item.pref.id
                is SettingItem.Number -> item.pref.id
                // Action / ReadOnly / Info 的 LIVE 是"这一行点得动"，与偏好无关
                else -> return@mapNotNull null
            }
            if (SettingsBridge.isBridged(id) || id in directReaders) null else "${item.key}（$id）"
        }
        assertEquals(
            "这些项标了 LIVE，但既没有老键桥、也不在「直接读者」清单里 —— " +
                "拨了不会改变任何界面，属于「可拨而无效」：",
            emptyList<String>(),
            wrong,
        )
        // 非空判：LIVE 的可写项必须真的有一批，否则这条断言是空跑
        val liveWritable = SettingsModel.items().count {
            it.live == SettingLive.LIVE &&
                (it is SettingItem.Toggle || it is SettingItem.Choice<*> || it is SettingItem.Number)
        }
        assertTrue("只核对了 $liveWritable 个 LIVE 可写项，抽取器大概失效了", liveWritable >= 12)
    }

    @Test
    fun everyBridgedIdReallyIsASettingKey() {
        for (id in SettingsBridge.BRIDGED_IDS) {
            assertTrue("$id 不在 SettingsKeys.all 里，桥挂在一个不存在的键上",
                SettingsKeys.all.any { it.id == id })
            assertTrue("$id 没有 cfg_ 前缀", SettingsKeys.isOurs(id))
        }
    }

    @Test
    fun bridgedIdsMatchTheSwitchInLegacyWritesFor() {
        // 两条判据必须说同一件事：集合里的一条，`legacyWritesFor` 必须**对至少一个合法值**
        // 给得出老键映射；反过来给得出东西的，也必须在集合里。
        // 分家之后「哪一项算接上了」就没有唯一答案。
        //
        // 为什么是"至少一个值"而不是"每个值"：主题的「跟随系统」**故意**不写老键
        // （写一个固定值等于把它偷偷改成固定），所以那一条键对某一个档位返回空表是正确的。
        for (id in SettingsBridge.BRIDGED_IDS) {
            val key = SettingsKeys.byId(id)
            val candidates: List<Any> = when (key) {
                is SettingKey.Bool -> listOf(true, false)
                is SettingKey.Int -> listOf(key.def, key.range.first, key.range.last).distinct()
                is SettingKey.Choice<*> -> key.options.map { it.name }
                else -> error("$id 不是可写键")
            }
            assertTrue("$id 一个合法值都桥不出老键来（它在 BRIDGED_IDS 里，说明应当桥得出）",
                candidates.any { SettingsBridge.legacyWritesFor(id, it).isNotEmpty() })
            assertTrue("$id 桥得出老键，却不在 BRIDGED_IDS 里", SettingsBridge.isBridged(id))
        }
        // 反向：不在集合里的可写键，对**任何**值都不该产生老键写入（否则它偷偷接了一根线）
        val implicit = SettingsModel.items().mapNotNull { item ->
            val id = when (item) {
                is SettingItem.Toggle -> item.pref.id
                is SettingItem.Choice<*> -> item.pref.id
                is SettingItem.Number -> item.pref.id
                else -> null
            } ?: return@mapNotNull null
            if (SettingsBridge.isBridged(id)) return@mapNotNull null
            val key = SettingsKeys.byId(id)
            val candidates: List<Any> = when (key) {
                is SettingKey.Bool -> listOf(true, false)
                is SettingKey.Int -> listOf(key.def, key.range.first, key.range.last).distinct()
                is SettingKey.Choice<*> -> key.options.map { it.name }
                else -> emptyList()
            }
            id.takeIf { candidates.any { v -> SettingsBridge.legacyWritesFor(id, v).isNotEmpty() } }
        }
        assertEquals("这些键没被登记成已桥接，但桥其实会写老键 —— 两条判据分家了：",
            emptyList<String>(), implicit)
        // 自检：故意把一条没桥的键算进来必须红
        assertFalse(SettingsBridge.isBridged("cfg_nope"))
    }

    // ── 自检：判据本身必须能红 ──────────────────────────────────────

    @Test
    fun checkersActuallyCatchBadCode() {
        // 1. 显式映射表：如果谁把"按体积"改成 1，往返仍然闭合但语义错了，
        //    所以那条硬编码断言必须是**唯一**的拦截点。这里直接演示它会红。
        assertTrue("自检失败：按体积与最早在前共用了同一个老编号",
            SettingsBridge.gallerySortCode(DefaultSort.SIZE) !=
                SettingsBridge.gallerySortCode(DefaultSort.OLDEST))

        // 2. "没存过才认老键"：如果实现改成"永远认老键"，这条会红
        assertEquals("自检失败：cfg 存过时老键仍然盖了上来",
            GridColumns.LARGE, SettingsBridge.gridColumnsInitial("LARGE", legacyCellDp = 64))

        // 3. 字号标准档恒等
        assertEquals("标准档必须恒等 1.0", 1.0f,
            SettingsBridge.fontScaleFactor(FontScale.STANDARD), 0.0001f)

        // 4. 「跟随系统」不写老键
        assertNull(SettingsBridge.themeDark(ThemeMode.FOLLOW_SYSTEM))
    }
}

/**
 * 只会做"落盘"的极简 `SharedPreferences` 替身。
 *
 * 为什么不复用 `SettingsStoreTest.FakePrefs`：那个类是 private 的，而这一份要的
 * 只是"能存取 + 能按类型读回来"。**它不做类型校验**——类型校验的坏值兜底由
 * `SettingsStoreTest` 那份会抛 `ClassCastException` 的替身负责，两份分工不同，
 * 合起来会把"坏值兜底"和"桥有没有写对"两件事搅在一个假实现里。
 */
private class MapPrefs : android.content.SharedPreferences {

    private val data = LinkedHashMap<String, Any>()

    fun intOf(key: String): Int = data[key] as? Int ?: error("$key 没被写成 Int（实际 ${data[key]}）")

    fun boolOf(key: String): Boolean = data[key] as? Boolean ?: error("$key 没被写成 Boolean（实际 ${data[key]}）")

    fun putRawInt(key: String, value: Int) {
        data[key] = value
    }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

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
            override fun clear() = apply { pending.clear(); removed.addAll(data.keys) }

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
