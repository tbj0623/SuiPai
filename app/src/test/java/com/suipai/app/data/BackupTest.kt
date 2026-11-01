package com.suipai.app.data

import com.suipai.app.settings.SettingKey
import com.suipai.app.settings.SettingsKeys
import com.suipai.app.settings.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「导出与恢复」的纯逻辑：编码 / 解码 / 坏数据兜底 / 合并。
 *
 * 这个文件的形状是这份代码最大的风险决定的：**输入来自用户手里的任意一个 uri**。
 * SAF 里选错文件是常态（一张照片、一个 APK、半截下载），而"选错"在界面上只有一种表现
 * —— 点了恢复，然后什么都没发生，或者更糟：本机数据被一份看不懂的文件覆盖掉半截。
 * 所以每条判据都配了一个**必须变红**的坏例子，而不是只断言正常路径。
 *
 * 这里只测不碰 `Context` 的部分。`exportTo` / `importFrom` 要真的读写文件与四份清单，
 * 在 JVM 上 `ContentResolver` 是返回默认值的桩，在那里测"写成功了没有"测到的是桩。
 * 它们被拆成 [Backup.settingWrites] 这类纯函数，测试逐条钉住那些跳过规则。
 *
 * 注意用的是 `org.junit.Assert`：它的 `assertX(message, actual)` 参数顺序
 * 与 `kotlin.test` 的 `assertX(actual, message)` **相反**（`CustomLooksTest` 里记着同一条）。
 */
class BackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    private fun bundle(
        settings: Map<String, String> = mapOf(
            SettingsKeys.Appearance.themeMode.id to "DARKROOM",
            SettingsKeys.Camera.jpegQuality.id to "97",
            SettingsKeys.General.confirmDelete.id to "false",
            SettingsKeys.Privacy.exportAt.id to "2026-10-18T13:20",
        ),
        looks: List<String> = listOf(lookPayload("user_1001", "黄昏")),
        collections: List<PhotoCollection> = listOf(
            PhotoCollection("c1", "2024 夏天", listOf(1L, 2L, 3L), t0, t0),
        ),
        favorites: List<Long> = listOf(11L, 22L),
        hidden: List<Long> = listOf(33L),
        searchHistory: List<String> = listOf("日落", "海边"),
        createdAt: Long = t0,
        appVersion: String = "0.7.0",
    ) = BackupBundle(
        schema = Backup.CURRENT_SCHEMA,
        createdAt = createdAt,
        appVersion = appVersion,
        settings = settings,
        looks = looks,
        collections = collections,
        favorites = favorites,
        hidden = hidden,
        searchHistory = searchHistory,
    )

    /** 造一条 `SPLOOK1:` 文本。真的走 `CustomLooks.exportText` 的编码路径（不手搓 Base64）。 */
    private fun lookPayload(id: String, name: String): String {
        val repo = CustomLooks(java.io.File(tmp.root, "looks-$id.json"))
        return repo.exportText(
            CustomLook.from(
                name = name,
                baseLookId = "daylight",
                intensity = 0.6f,
                shifts = com.suipai.app.engine.Shifts(),
                id = id,
                created = t0,
                modified = t0,
                now = t0,
            ),
        )
    }

    // ── 往返 ──────────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsEverything() {
        val original = bundle()
        val text = Backup.encode(original)
        val back = Backup.decode(text)
        assertEquals("往返之后整包必须一模一样\n--- 文本 ---\n$text", original, back)
    }

    /**
     * 中文、emoji、超长字符串、空集合 —— 四类都是"会写进这个文件"的真实内容：
     * 收藏夹名可能带 emoji（用户就是这么命名的）、配方摘要可能很长、新用户的所有集合可能都是空的。
     *
     * 这里刻意用**不含换行/制表符**的长名字：收藏夹名称在解码时会被
     * `Collections.normalizeName` 压平（换行变空格、首尾去空白），那是**有意**的
     * —— 备份恢复出来的名称必须与 `collections.json` 里能存住的形状逐字相同。
     * 那一条单独在 [namesAreNormalizedLikeTheRealStore] 里钉。
     */
    @Test
    fun roundTripsUnicodeEmojiLongStringsAndEmpties() {
        val longName = "长".repeat(4000) + "·带中间点"
        val emoji = "🌇 落日 · 📷 Leica \"M6\" \\ 反斜杠"
        val original = BackupBundle(
            schema = Backup.CURRENT_SCHEMA,
            createdAt = t0,
            appVersion = "0.7.0-测试",
            settings = mapOf(
                // 键里出现引号与反斜杠：虽然今天不存在这样的键，但转义必须是对的
                "cfg_引用\\键\"" to "值里有 \"引号\" 与 \\ 反斜杠",
                SettingsKeys.General.startTab.id to "ALBUMS",
            ),
            looks = emptyList(),
            collections = listOf(
                PhotoCollection("c🌇", emoji, listOf(7L), t0, t0),
                PhotoCollection("long", longName, emptyList(), 0L, 0L),
            ),
            favorites = emptyList(),
            hidden = emptyList(),
            searchHistory = listOf("🌇 落日", "带\"引号\"的搜索"),
        )
        val back = Backup.decode(Backup.encode(original))
        assertEquals("中文 / emoji / 超长 / 空集合往返之后变了", original, back)
        // 自检：不许"两边都被编码器吞成空串"而误绿
        assertEquals(emoji, back!!.collections[0].name)
        assertEquals(longName, back.collections[1].name)
        assertEquals("转义过的设置值必须原样回来", original.settings, back.settings)
    }

    /**
     * 名称与搜索词的**归一**是有意的，不是往返失败：
     * 收藏夹名要压成单行（这份数据还会进工具条那一格，换行会撑高行高），
     * 搜索词去空、去重、截到 10 条（`SearchHistory.normalize` 同一套口径）。
     *
     * 这条测试的价值是：哪天有人把归一去掉，备份里就会出现"能存进来但存不回去"的形状。
     */
    @Test
    fun namesAreNormalizedLikeTheRealStore() {
        val text = """
            {"format":"suipai-backup","schema":1,
             "collections":[{"id":"a","name":"第一行\n第二行\t带制表符"}],
             "searchHistory":[" 日落 ","","日落"]}
        """.trimIndent()
        val got = Backup.decode(text)!!
        assertEquals("换行与制表符要压成空格", "第一行 第二行 带制表符", got.collections[0].name)
        assertEquals("搜索词要去空、去重、trim", listOf("日落"), got.searchHistory)
    }

    @Test
    fun emptyBundleRoundTrips() {
        val empty = BackupBundle(
            schema = Backup.CURRENT_SCHEMA,
            createdAt = 0L,
            appVersion = "",
            settings = emptyMap(),
            looks = emptyList(),
            collections = emptyList(),
            favorites = emptyList(),
            hidden = emptyList(),
            searchHistory = emptyList(),
        )
        val back = Backup.decode(Backup.encode(empty))
        assertEquals("空包往返之后变了", empty, back)
        assertTrue("空包的条数应当都是 0", back!!.counts().total == 0)
    }

    /** 同一个包编码两次必须是同一串字符（settings 按键名排序、字段顺序固定）。 */
    @Test
    fun encodingIsStable() {
        val b = bundle()
        assertEquals("同一份数据两次编码结果不同，diff 会看不懂", Backup.encode(b), Backup.encode(b))
        val reordered = b.copy(
            settings = b.settings.entries.reversed().associate { it.key to it.value },
        )
        assertEquals("settings 的顺序不该影响编码结果", Backup.encode(b), Backup.encode(reordered))
    }

    // ── schema ────────────────────────────────────────────────────────

    /** schema 比当前新 → **整包拒绝**，不是"按旧语义尽量解"。 */
    @Test
    fun newerSchemaIsRejectedWholesale() {
        val text = Backup.encode(bundle()).replace(
            "\"schema\": ${Backup.CURRENT_SCHEMA}",
            "\"schema\": ${Backup.CURRENT_SCHEMA + 1}",
        )
        assertTrue("替换没生效，这条判据在空转", "schema\": ${Backup.CURRENT_SCHEMA + 1}" in text)
        assertNull("更新的 schema 必须整包拒绝", Backup.decode(text))
        val read = Backup.read(text)
        assertTrue("应当报 TooNew 而不是 Broken：$read", read is BackupRead.TooNew)
        assertEquals(Backup.CURRENT_SCHEMA + 1L, (read as BackupRead.TooNew).schema)
    }

    /** schema 缺失 → 拒绝。不能猜成"当前版本"：猜错方向就是把数据解释成另一件东西。 */
    @Test
    fun missingSchemaIsRejected() {
        val text = Backup.encode(bundle()).replaceFirst("\"schema\": 1,\n", "")
        assertTrue("替换没生效（schema 那一行没被删掉）", "\"schema\"" !in text)
        assertNull("没有 schema 的文件必须拒绝", Backup.decode(text))
        assertTrue(Backup.read(text) is BackupRead.Broken)
    }

    /** schema 是 0 或负数 → 拒绝（历史上不存在 v0，它更可能是坏数据或别的文件）。 */
    @Test
    fun schemaZeroOrNegativeIsRejected() {
        for (bad in listOf("0", "-1", "1.5", "\"1\"")) {
            val text = Backup.encode(bundle()).replace("\"schema\": 1", "\"schema\": $bad")
            assertNull("<$bad> 当 schema 应当被拒绝", Backup.decode(text))
        }
    }

    /** 别的应用的 json（没有 `format` 标记）→ 拒绝，不许"字段刚好对得上"就往下解。 */
    @Test
    fun foreignJsonIsRejected() {
        val foreign = """{"schema":1,"createdAt":1,"settings":{"a":"b"}}"""
        assertNull("没有 format 标记的文件必须拒绝", Backup.decode(foreign))
        val wrongFormat = """{"format":"another-app","schema":1}"""
        assertNull("format 不对的文件必须拒绝", Backup.decode(wrongFormat))
    }

    // ── 字段缺失 / 类型不对 ────────────────────────────────────────────

    /**
     * 只有身份与 schema、其它字段一个都没有 → 解出来是一包空的，**不崩**。
     * 这是"未来的版本加了新字段、或者有人手工删了几段"的正常形态。
     */
    @Test
    fun missingFieldsFallBackToDefaults() {
        val minimal = """{"format":"suipai-backup","schema":1}"""
        val got = Backup.decode(minimal)
        assertEquals("缺字段应当解成默认值，解不出来说明容错被写成拒绝了：$got", bundle(
            settings = emptyMap(),
            looks = emptyList(),
            collections = emptyList(),
            favorites = emptyList(),
            hidden = emptyList(),
            searchHistory = emptyList(),
            createdAt = 0L,
            appVersion = "",
        ), got)
    }

    /** 类型不对的字段：跳过那一条，其它照旧。一个坏条目不许让整份文件消失。 */
    @Test
    fun wrongTypesOnlyDropThatEntry() {
        val goodLook = lookPayload("user_2002", "海边")
        val text = """
            {"format":"suipai-backup","schema":1,"createdAt":$t0,"appVersion":"0.7.0",
             "settings":{"cfg_theme_mode":"DARKROOM","cfg_confirm_delete":true},
             "looks":[42,"$goodLook",null],
             "collections":[{"id":"c1","name":"好的","photoIds":[1,2]},
                            {"id":"","name":"空 id"},
                            {"name":"没 id"},
                            {"id":"c2"},
                            7, "不是对象"],
             "favorites":[5,"6",-7,0,5],
             "hidden":"不是数组",
             "searchHistory":["日落",7,null,"日落"]}
        """.trimIndent()
        val got = Backup.decode(text)
        assertTrue("整份文件应当还能解出来：$got", got != null)
        val b = got!!
        assertEquals("设置里那条非字符串的值应当被丢掉，只留字符串那条",
            mapOf(SettingsKeys.Appearance.themeMode.id to "DARKROOM"), b.settings)
        assertEquals("只有字符串且能解出 id 的配方该活下来", 1, b.looks.size)
        assertEquals("只有带非空 id 的收藏夹该活下来", listOf("c1", "c2"), b.collections.map { it.id })
        assertEquals("缺名称回退兜底名", Collections.FALLBACK_NAME, b.collections[1].name)
        assertEquals("负数与 0 不是有效照片 id；重复要去重", listOf(5L, 6L), b.favorites)
        assertEquals("hidden 类型不对应当是空表", emptyList<Long>(), b.hidden)
        assertEquals("搜索历史去重且丢非字符串", listOf("日落"), b.searchHistory)
    }

    /** 空 id / 重复 id 的收藏夹只留第一条 —— 否则"删除"永远只删掉一条。 */
    @Test
    fun duplicateCollectionIdsKeepTheFirst() {
        val text = """
            {"format":"suipai-backup","schema":1,
             "collections":[{"id":"a","name":"第一","photoIds":[1],"createdAt":10,"updatedAt":20},
                            {"id":"a","name":"第二","photoIds":[2],"createdAt":30,"updatedAt":40}]}
        """.trimIndent()
        val got = Backup.decode(text)!!
        assertEquals(1, got.collections.size)
        assertEquals("第一", got.collections[0].name)
        assertEquals(20L, got.collections[0].updatedAt)
    }

    /** 搜索历史在**读**的时候就截到上限：手改过的文件能写 200 条，而界面只有一行。 */
    @Test
    fun searchHistoryIsClampedOnDecode() {
        val many = (1..(SearchHistory.MAX + 20)).joinToString(",") { "\"词$it\"" }
        val text = """{"format":"suipai-backup","schema":1,"searchHistory":[$many]}"""
        val got = Backup.decode(text)!!
        assertEquals(SearchHistory.MAX, got.searchHistory.size)
        assertEquals("截断要留前面那些", "词1", got.searchHistory.first())
    }

    /** 超上限的收藏夹条数在读的时候就截断（与 `Collections.decode` 同一条纪律）。 */
    @Test
    fun collectionsAreClampedOnDecode() {
        val items = (1..(Collections.MAX_COLLECTIONS + 5)).joinToString(",") { """{"id":"c$it"}""" }
        val text = """{"format":"suipai-backup","schema":1,"collections":[$items]}"""
        val got = Backup.decode(text)!!
        assertEquals(Collections.MAX_COLLECTIONS, got.collections.size)
    }

    // ── 截断 / 半截 JSON ─────────────────────────────────────────────

    @Test
    fun truncatedOrBrokenJsonReturnsNullWithoutThrowing() {
        val full = Backup.encode(bundle())
        val samples = listOf(
            "",
            "   ",
            "{",
            "}",
            "[",
            "null",
            "0",
            "\"字符串\"",
            "{\"format\":\"suipai-backup\"",
            "{\"format\":\"suipai-backup\",\"schema\":",
            full.substring(0, full.length / 2),            // 截一半
            full.substring(0, full.length - 3),            // 只少最后几个字符
            full.replace("\"favorites\"", "\"favorites\""),  // 完全相同，作为对照
            full + "后面还有垃圾",                          // 尾部多余内容
            "\u0000\u0001\u0002",
            "{\"format\":\"suipai-backup\",\"schema\":1,\"settings\":{\"a\":}}",
        )
        for (text in samples) {
            val got = try {
                Backup.decode(text)
            } catch (e: Exception) {
                throw AssertionError("<${text.take(24)}> 把 decode 抛了：$e", e)
            }
            if (text == samples[12]) {
                assertEquals("原样文本应当还能解出来（否则上面那些 null 是假绿）", bundle(), got)
            } else {
                assertNull("<${text.take(24)}…（共 ${text.length} 字符）> 应当返回 null", got)
            }
        }
    }

    /** [Backup.read] 对坏数据也绝不抛 —— 它是界面上"为什么不行"的唯一来源。 */
    @Test
    fun readNeverThrowsOnHostileInput() {
        val hostile = listOf("", "\u0000", "{\"format\":\"suipai-backup\",\"schema\":1e300}",
            "{\"format\":\"suipai-backup\",\"schema\":99999999999999999999}")
        for (text in hostile) {
            val r = try {
                Backup.read(text)
            } catch (e: Exception) {
                throw AssertionError("<${text.take(20)}> 把 read 抛了：$e", e)
            }
            assertTrue("<${text.take(20)}> 应当被判成不行：$r", r !is BackupRead.Ok)
        }
    }

    // ── 合并：REPLACE ────────────────────────────────────────────────

    @Test
    fun replaceTakesRemoteWholesale() {
        val local = bundle(favorites = listOf(1L, 2L), hidden = listOf(3L))
        val remote = bundle(
            settings = mapOf(SettingsKeys.Appearance.themeMode.id to "DAYLIGHT"),
            looks = listOf(lookPayload("user_9001", "交换的")),
            collections = emptyList(),
            favorites = listOf(99L),
            hidden = emptyList(),
            searchHistory = listOf("新词"),
            createdAt = t0 + 5_000,
        )
        val merged = Backup.merge(local, remote, MergeMode.REPLACE)
        assertEquals("REPLACE 的结果应当就是文件那一包（时间取较新）",
            remote.copy(schema = Backup.CURRENT_SCHEMA, createdAt = t0 + 5_000), merged)
        assertTrue("REPLACE 必须真的丢掉本机那些：${merged.favorites}", 1L !in merged.favorites)
    }

    // ── 合并：UNION ──────────────────────────────────────────────────

    /**
     * 核心判据：同一条配方两边都有 → **只留一条**，且留本机那条。
     *
     * 本机那条的 payload 里名字是「本机改过的」，文件里是「旧的」——结果里必须是前者，
     * 否则"合并"会把用户刚改的东西回退掉，而那在界面上看起来像恢复失败。
     */
    @Test
    fun unionDedupesLooksByIdAndKeepsLocal() {
        val sameId = "user_5001"
        val local = bundle(looks = listOf(lookPayload(sameId, "本机改过的")))
        val remote = bundle(
            looks = listOf(lookPayload(sameId, "旧的"), lookPayload("user_5002", "只有文件里有")),
        )
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals("同一条配方只许留一条，两边不同的补上：${merged.looks.size} 条", 2, merged.looks.size)
        assertEquals(listOf(sameId, "user_5002"), merged.looks.map { Backup.lookIdOf(it) })
        // 自检：留下的是**本机**那条（按 id 取不出名字，所以按 payload 是否与本机那条相同来判）
        assertEquals("同 id 时应当保留本机那条（用户在那边刚改过）", local.looks[0], merged.looks[0])
        assertNotEquals("结果不该是文件里那条", remote.looks[0], merged.looks[0])

        // 自检的反面：把去重去掉就应当是 3 条 —— 证明上面那条断言不是恒真
        val naive = local.looks + remote.looks
        assertEquals("朴素拼接应当是 3 条（去重真的起了作用）", 3, naive.size)
    }

    /**
     * 收藏夹 id 冲突的处理写清楚：
     * - 名称与 `createdAt` 取**本机**；
     * - 照片**并集**，本机顺序在前（`Collections.orderedAdd`：第一次出现的位置说了算）；
     * - `updatedAt` 取**较大**（"最近动过"这件事两边都算数）。
     */
    @Test
    fun unionCollectionsMergesPhotosAndKeepsLocalIdentity() {
        val local = bundle(
            collections = listOf(PhotoCollection("c1", "本机名", listOf(1L, 2L), t0, t0 + 10)),
        )
        val remote = bundle(
            collections = listOf(
                PhotoCollection("c1", "文件里的名字", listOf(2L, 3L, 4L), t0 + 999, t0 + 500),
                PhotoCollection("c2", "只有文件里有", listOf(9L), t0, t0),
            ),
        )
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals(2, merged.collections.size)
        val c1 = merged.collections.first { it.id == "c1" }
        assertEquals("名称取本机", "本机名", c1.name)
        assertEquals("createdAt 取本机", t0, c1.createdAt)
        assertEquals("updatedAt 取较大", t0 + 500, c1.updatedAt)
        assertEquals("照片并集且本机顺序在前", listOf(1L, 2L, 3L, 4L), c1.photoIds)
        assertTrue("文件独有的收藏夹要进来", merged.collections.any { it.id == "c2" })

        // 自检：顺序不是"文件拼前面"
        assertNotEquals(listOf(2L, 3L, 4L, 1L), c1.photoIds)
    }

    /** 收藏 / 隐藏的并集：去重、保序、本机在前；两份清单互不干扰。 */
    @Test
    fun unionIdListsDedupeAndKeepLocalOrder() {
        val local = bundle(favorites = listOf(5L, 1L), hidden = listOf(7L))
        val remote = bundle(favorites = listOf(1L, 9L, 5L), hidden = listOf(7L, 8L))
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals(listOf(5L, 1L, 9L), merged.favorites)
        assertEquals(listOf(7L, 8L), merged.hidden)
    }

    /** 同一个 id 同时在两份清单里是**允许的**：应用里这两份本来就是独立的。 */
    @Test
    fun unionAllowsAnIdToBeBothFavoriteAndHidden() {
        val local = bundle(favorites = listOf(4L), hidden = emptyList())
        val remote = bundle(favorites = emptyList(), hidden = listOf(4L))
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals(listOf(4L), merged.favorites)
        assertEquals(listOf(4L), merged.hidden)
    }

    /** 设置并集：同键本机赢，新键补上。 */
    @Test
    fun unionSettingsKeepLocalAndAddRemote() {
        val local = bundle(settings = mapOf(SettingsKeys.Appearance.themeMode.id to "DARKROOM"))
        val remote = bundle(
            settings = mapOf(
                SettingsKeys.Appearance.themeMode.id to "DAYLIGHT",
                SettingsKeys.Camera.jpegQuality.id to "99",
            ),
        )
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals("DARKROOM", merged.settings[SettingsKeys.Appearance.themeMode.id])
        assertEquals("99", merged.settings[SettingsKeys.Camera.jpegQuality.id])
    }

    /** 搜索历史并集：本机在前、去重不区分大小写、截到 10 条。 */
    @Test
    fun unionSearchHistoryDedupesCaseInsensitively() {
        val local = bundle(searchHistory = listOf("Shanghai", "北京"))
        val remote = bundle(searchHistory = listOf("shanghai", "上海"))
        val merged = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals("本机写法优先", listOf("Shanghai", "北京", "上海"), merged.searchHistory)

        val long = bundle(searchHistory = (1..8).map { "本机$it" })
        val more = bundle(searchHistory = (1..8).map { "文件$it" })
        val capped = Backup.merge(long, more, MergeMode.UNION)
        assertEquals("并集也要截到上限", SearchHistory.MAX, capped.searchHistory.size)
        assertEquals("截断留本机的那些", "本机1", capped.searchHistory.first())
    }

    /** 合并是纯函数：同样的输入调两次结果相同，且不改动入参。 */
    @Test
    fun mergeIsPureAndDoesNotMutateInputs() {
        val local = bundle()
        val remote = bundle(favorites = listOf(11L, 44L))
        val localBefore = local.copy()
        val remoteBefore = remote.copy()
        val a = Backup.merge(local, remote, MergeMode.UNION)
        val b = Backup.merge(local, remote, MergeMode.UNION)
        assertEquals("两次合并结果不同", a, b)
        assertEquals("合并改动了本机那份", localBefore, local)
        assertEquals("合并改动了文件那份", remoteBefore, remote)
    }

    // ── 设置的字符串 ↔ 类型 ──────────────────────────────────────────

    /** 导出的设置只含**真的落过盘**的键（`has`）：没落过 = 用默认值，文件里不该有它。 */
    @Test
    fun exportSettingsOnlyIncludesStoredKeys() {
        val snap = SettingsSnapshot(
            mapOf(
                SettingsKeys.Camera.jpegQuality.id to 99,
                SettingsKeys.General.confirmDelete.id to false,
                SettingsKeys.Appearance.themeMode.id to "DAYLIGHT",
                SettingsKeys.Self.searchHistory.id to setOf("落日", "海边"),
            ),
        )
        val out = Backup.exportSettings(snap)
        assertEquals(4, out.size)
        assertEquals("99", out[SettingsKeys.Camera.jpegQuality.id])
        assertEquals("false", out[SettingsKeys.General.confirmDelete.id])
        assertEquals("DAYLIGHT", out[SettingsKeys.Appearance.themeMode.id])
        assertEquals("集合要编码成 JSON 数组文本（逗号拼接不可逆）",
            "[\"海边\",\"落日\"]", out[SettingsKeys.Self.searchHistory.id])
        // 没存过的键一个都不该出现
        assertFalse("没落过盘的键不许进文件", SettingsKeys.General.startTab.id in out)
    }

    /** 字符串集合用 JSON 数组编码：搜索词里本来就有逗号时也不可逆地坏掉才是问题。 */
    @Test
    fun stringSetSurvivesCommasAndQuotes() {
        val tricky = setOf("2024, 夏天", "带\"引号\"", "a\\b")
        val snap = SettingsSnapshot(mapOf(SettingsKeys.Self.searchHistory.id to tricky))
        val out = Backup.exportSettings(snap)
        val writes = Backup.settingWrites(out)
        assertEquals(1, writes.size)
        val got = (writes[0] as SettingWrite.StringSet).value
        assertEquals("带逗号/引号/反斜杠的集合没有往返回来", tricky, got)
    }

    /** 值的类型解错就跳过：宁可少写一条，不许把非法值写进磁盘。 */
    @Test
    fun settingWritesSkipBadValues() {
        val writes = Backup.settingWrites(
            mapOf(
                // 不在表里的键：不许往 prefs 里塞野键
                "cfg_not_a_real_key" to "1",
                // 整数解不出 / 越界：越过 range 的值不写（不夹取）
                SettingsKeys.Camera.jpegQuality.id to "不是数字",
                SettingsKeys.Yishun.defaultIntensity.id to "900",
                SettingsKeys.Camera.shutterSound.id to "maybe",
                // 枚举 name 不在表里
                SettingsKeys.Appearance.themeMode.id to "NEON",
                // 字符串集合不是 JSON 数组
                SettingsKeys.Self.searchHistory.id to "落日,海边",
                // 四条合法的
                SettingsKeys.General.confirmDelete.id to "true",
                SettingsKeys.Appearance.gridColumns.id to "dense",
                SettingsKeys.Privacy.exportAt.id to "",
                SettingsKeys.Self.searchHistory.id + "\u0000" to "[]",
            ),
        )
        val ids = writes.map { w ->
            when (w) {
                is SettingWrite.BoolValue -> w.key.id
                is SettingWrite.IntValue -> w.key.id
                is SettingWrite.TextValue -> w.key.id
                is SettingWrite.ChoiceName -> w.key.id
                is SettingWrite.StringSet -> w.key.id
            }
        }
        assertEquals("只该有三条活下来：$ids", 3, writes.size)
        assertEquals(SettingsKeys.General.confirmDelete.id, ids[0])
        assertEquals(SettingsKeys.Appearance.gridColumns.id, ids[1])
        assertEquals(SettingsKeys.Privacy.exportAt.id, ids[2])
        // 自检：枚举的小写能救回来，但要归一成 options 里的那个 name
        assertEquals(
            "DENSE",
            (writes[1] as SettingWrite.ChoiceName).name,
        )
        // 自检：越界的整数没有被夹成 100
        assertTrue("越界整数不许被写进去", writes.none { it is SettingWrite.IntValue })
    }

    /** 合法的整数与布尔要真的解出来（否则上面那条"跳过"是假绿）。 */
    @Test
    fun settingWritesAcceptValidValues() {
        val writes = Backup.settingWrites(
            mapOf(
                SettingsKeys.Camera.jpegQuality.id to " 98 ",
                SettingsKeys.General.confirmDelete.id to "false",
                SettingsKeys.Appearance.themeMode.id to "daylight",
            ),
        )
        assertEquals(3, writes.size)
        assertEquals(98, (writes[0] as SettingWrite.IntValue).value)
        assertEquals(false, (writes[1] as SettingWrite.BoolValue).value)
        assertEquals("DAYLIGHT", (writes[2] as SettingWrite.ChoiceName).name)
    }

    // ── 配方 id 抽取 ────────────────────────────────────────────────

    /** `lookIdOf` 是去重的唯一依据：它认错一次就会把同一条配方留成两条（或删掉一条）。 */
    @Test
    fun lookIdIsReadOutOfThePayload() {
        val payload = lookPayload("user_7777", "海边")
        assertEquals("user_7777", Backup.lookIdOf(payload))
        // 后面跟着一句说明（剪贴板里最常见的形状）也要能解
        assertEquals("user_7777", Backup.lookIdOf("复制这段：$payload 用这个配方"))
        // 坏 payload 一律 null，不抛
        assertNull(Backup.lookIdOf(""))
        assertNull(Backup.lookIdOf("SPLOOK1:"))
        assertNull(Backup.lookIdOf("SPLOOK1:!!!!"))
        assertNull(Backup.lookIdOf("不是这个前缀 user_1"))
        // 自检：前缀对了但 payload 是别的 JSON（没有 id）也要 null
        assertNull(Backup.lookIdOf("SPLOOK1:eyJmb28iOjF9"))
    }

    /**
     * `decodeLook` 必须**保留 payload 里的 id**。
     *
     * 这一条守的是一个真踩过的坑：恢复最早走的是 `CustomLooks.importText`，
     * 而那个接口的硬保证是"一定重新发号"（剪贴板导入别人的配方时是对的）。
     * 用它来恢复同一个用户的备份 → 每恢复一次 id 就变一次，**同一条配方变成两条**，
     * 再恢复一次变三条。UNION 模式尤其明显（它按 id 判"同一条"）。
     */
    @Test
    fun decodedLookKeepsItsOwnId() {
        val payload = lookPayload("user_8888", "海边")
        val look = Backup.decodeLook(payload)
        assertTrue("应当解出一条配方：$look", look != null)
        assertEquals("恢复必须保住 id（重新发号会让每次恢复多出一条）", "user_8888", look!!.id)
        assertEquals("海边", look.name)
        // 自检：importText 那条路确实会改号 —— 这就是不能用它的原因
        val repo = CustomLooks(java.io.File(tmp.root, "imported.json"))
        assertNotEquals(
            "importText 的契约就是重新发号；哪天它改了，这里的注释和选择都要重看",
            "user_8888", repo.importText(payload)?.id,
        )
        // 不带 user_ 前缀的 id 不算我们的配方（`CustomLooks.all()` 读盘时就会过滤掉）
        assertNull("没有 user_ 前缀的配方不该被当成可恢复的", Backup.lookIdOf(lookPayload("other_1", "野的")))
    }

    // ── 条数 ────────────────────────────────────────────────────────

    @Test
    fun countsLineMentionsEveryCategory() {
        val c = BackupCounts(
            settings = 3, looks = 2, collections = 1,
            favorites = 10, hidden = 4, searchHistory = 5,
        )
        assertEquals(25, c.total)
        val line = c.line()
        for (n in listOf("设置 3 项", "配方 2 条", "收藏夹 1 个", "收藏 10 张", "隐藏 4 张", "搜索 5 条")) {
            assertTrue("条数那句话少了「$n」：$line", n in line)
        }
    }

    /** 结果那一句：成功与失败都必须有话说（界面上不许出现"点了没反应"）。 */
    @Test
    fun resultLineAlwaysSaysSomething() {
        val ok = BackupResult.Ok(BackupCounts(settings = 2), t0)
        assertTrue("成功没说话：${ok.line("导出")}", "导出" in ok.line("导出"))
        assertTrue("成功没报条数", "设置 2 项" in ok.line("导出"))
        val failed = BackupResult.Failed("打不开这个文件")
        assertEquals("打不开这个文件", failed.line("恢复"))
    }

    // ── 自检：坏例子必须变红 ────────────────────────────────────────

    /**
     * 把上面那些判据的**关键前提**逐条反过来喂一遍。
     *
     * 这一条守的是"测试自己有没有空转"：如果 `Backup.decode` 哪天变成"什么都返回空包"，
     * 上面那些"应当为 null"的断言会全部绿着，而实际功能已经死了。所以这里断言
     * **一份好的文件必须解得出东西**、**坏的文件必须解不出**，两侧都在。
     */
    @Test
    fun checkersActuallyCatchBadData() {
        // 好文件：必须解得出非空的包（否则"坏文件返回 null"那几条是假绿）
        val good = Backup.encode(bundle())
        val parsed = Backup.decode(good)
        assertTrue("正常的备份解不出东西 —— 上面所有 assertNull 都在空转", parsed != null)
        assertEquals("解出来的条数不对", 4, parsed!!.settings.size)
        assertTrue("收藏夹与配方也必须解出来", parsed.collections.isNotEmpty() && parsed.looks.isNotEmpty())

        // 坏文件：把 schema 换成字符串、把 format 删掉、截断 —— 三种都必须 null
        val mutations = listOf(
            good.replace("\"schema\": 1", "\"schema\": \"1\""),
            good.replace("\"suipai-backup\"", "\"other\""),
            good.substring(0, good.length / 3),
        )
        for (m in mutations) {
            assertNotEquals("这份被改过的文件不该等于原文（判据失效）", good, m)
            assertNull("被改坏的文件没被挡住：<${m.take(30)}…>", Backup.decode(m))
        }

        // 合并的自检：UNION 必须真的合并（不是返回本机、也不是返回文件）
        val local = bundle(favorites = listOf(1L))
        val remote = bundle(favorites = listOf(2L))
        val union = Backup.merge(local, remote, MergeMode.UNION)
        assertNotEquals("UNION 返回了本机那一份", local.favorites, union.favorites)
        assertNotEquals("UNION 返回了文件那一份", remote.favorites, union.favorites)
        assertEquals("UNION 应当两边都有", listOf(1L, 2L), union.favorites)
    }

    // ── 落盘路径：只测不碰 Context 的那一半 ──────────────────────────

    /**
     * `applyCollections` 写的是 `Collections.encode` 的形状 —— 所以那份文本必须能被
     * `Collections.decode` 原样读回来。这里把"写"那一步换成写临时文件（生产路径逐字相同），
     * 验证恢复之后收藏夹的 id 与顺序都在（走 `create` 会重新发号，那是另一个 bug）。
     */
    @Test
    fun collectionsRewriteKeepsIdsAndOrder() {
        val wanted = listOf(
            PhotoCollection("keep-me", "夏天", listOf(5L, 3L, 1L), t0, t0 + 1),
            PhotoCollection("also-keep", "冬天", emptyList(), 0L, 0L),
        )
        val file = java.io.File(tmp.root, Collections.FILE_NAME)
        file.writeText(Collections.encode(wanted))
        assertEquals("恢复之后收藏夹的 id 与顺序必须原样保留", wanted, Collections.decode(file.readText()))
        // 自检：走 create 那条路会重新发号（这就是为什么不复用它）
        assertNotEquals("create 会自己发号，这正是不能复用的原因",
            "keep-me", Collections.newId(t0, emptySet()))
    }
}
