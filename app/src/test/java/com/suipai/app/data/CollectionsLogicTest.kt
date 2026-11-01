package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Collections` 的纯逻辑：去重保序、名称规整、id 生成、账单往返与上限。
 *
 * 与 `TrashLogicTest` 同一条纪律：`Collections` 的 CRUD 落盘要 `Context`，
 * JVM 上是返回默认值的桩，测不出真行为。所以真值规则全部下沉成
 * `normalizeName` / `orderedAdd` / `newId` / `decode` / `encode` 这几个
 * 不碰 Context 的函数，测试只钉它们 —— 界面那条路径只是把这些函数接起来。
 */
class CollectionsLogicTest {

    private val t0 = 1_700_000_000_000L

    private fun collection(
        id: String = "c1",
        name: String = "2024 夏天",
        ids: List<Long> = listOf(1L, 2L, 3L),
        created: Long = t0,
        updated: Long = t0,
    ) = PhotoCollection(id, name, ids, created, updated)

    // ── 名称 ──────────────────────────────────────────────────────────

    /**
     * 名称规整：空白回退「未命名收藏」，首尾空白去掉，换行压成空格。
     *
     * 为什么换行也要压：名称是单行输入框来的，但 json 是手也能改的，
     * 而且一个带 `\n` 的名称在工具条那一格里会把行高撑开（FlowRow 按最高那颗定行高）。
     */
    @Test
    fun normalizeNameFallsBackAndFlattens() {
        val cases = listOf(
            "" to Collections.FALLBACK_NAME,
            "   " to Collections.FALLBACK_NAME,
            "\n\t " to Collections.FALLBACK_NAME,
            "  2024 夏天  " to "2024 夏天",
            "第一行\n第二行" to "第一行 第二行",
            "a\r\nb" to "a b",
            "未命名收藏" to "未命名收藏",
            "0" to "0",                       // 不算空
        )
        for ((input, want) in cases) {
            assertEquals("<$input> 规整错了", want, Collections.normalizeName(input))
        }
        // 自检：不能把所有输入都映射成兜底名（那样上面"2024 夏天"那条也会过，但这条会红）
        assertNotEquals(Collections.normalizeName("x"), Collections.normalizeName("y"))
    }

    // ── 去重 + 保序 ───────────────────────────────────────────────────

    /**
     * 这三条是"收藏夹里照片的顺序"的全部契约：
     * 去重、**第一次出现**的位置说了算、老的不许被新来的挤到后面。
     */
    @Test
    fun orderedAddDedupesAndKeepsFirstPosition() {
        assertEquals(listOf(1L, 2L, 3L), Collections.orderedAdd(listOf(1L, 2L), listOf(3L)))
        // 已经在里面的不许挪位置
        assertEquals(listOf(1L, 2L, 3L), Collections.orderedAdd(listOf(1L, 2L, 3L), listOf(2L)))
        // 新来的内部也要去重，且按传入顺序
        assertEquals(listOf(1L, 5L, 4L), Collections.orderedAdd(listOf(1L), listOf(5L, 5L, 4L, 5L)))
        // 空输入不动
        assertEquals(listOf(9L), Collections.orderedAdd(listOf(9L), emptyList()))
        assertEquals(emptyList<Long>(), Collections.orderedAdd(emptyList(), emptyList()))
    }

    // ── id ────────────────────────────────────────────────────────────

    /** 同一毫秒连点两次「新建」不许撞 id —— 撞了第二次 `byId` 会拿到第一个。 */
    @Test
    fun newIdAvoidsCollisionsWithinTheSameMillisecond() {
        val first = Collections.newId(t0, emptySet())
        val second = Collections.newId(t0, setOf(first))
        val third = Collections.newId(t0, setOf(first, second))
        assertEquals("三个 id 必须互不相同", 3, setOf(first, second, third).size)
        assertTrue("id 不许是空的", first.isNotEmpty())
        // 换一个时刻就该是一个全新的 id，不带着上一刻的后缀
        assertNotEquals(first, Collections.newId(t0 + 1, setOf(first)))
        // 负数时间戳（时钟没对上）不许产出 "-xxx"
        assertFalse(Collections.newId(-1L, emptySet()).startsWith("-"))
    }

    // ── 账单 ──────────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsEverything() {
        val items = listOf(
            collection(id = "a", name = "2024 夏天", ids = listOf(1L, 2L, 3L)),
            collection(id = "b", name = "待修", ids = emptyList(), created = t0 + 5, updated = t0 + 9),
        )
        assertEquals(items, Collections.decode(Collections.encode(items)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<PhotoCollection>(), Collections.decode(Collections.encode(emptyList())))
    }

    /** 坏 json 一律空表，绝不抛（与 `Trash.decode` 同一套纪律）。 */
    @Test
    fun brokenJsonDegradesToEmptyTable() {
        val broken = listOf("", "   ", "{", "[", "null", "{\"items\":", "{\"items\":7}", "[1,2,3]", "\u0000")
        for (text in broken) {
            val got = try {
                Collections.decode(text)
            } catch (e: Exception) {
                throw AssertionError("坏样本把 decode 抛了：<${text.take(16)}> → $e", e)
            }
            assertTrue("坏样本 <${text.take(16)}> 应退回空表，却解出了 $got", got.isEmpty())
        }
        assertEquals(1, Collections.decode(Collections.encode(listOf(collection()))).size)
    }

    /**
     * 单条坏记录只丢它自己；同一个 id 出现两次只留第一条
     * （否则「删除」永远只删掉一条，另一条会一直在列表里）。
     */
    @Test
    fun oneBadRecordOnlyDropsItself() {
        val json = """
            {"items":[
              {"id":"a","name":"好的","photoIds":[1,2],"createdAt":$t0,"updatedAt":$t0},
              {"name":"没 id"},
              {"id":"","name":"空 id"},
              {"id":"b","name":"  ","photoIds":[3,"x",4,-1,3]},
              {"id":"a","name":"重复的 a"}
            ]}
        """.trimIndent()
        val got = Collections.decode(json)
        assertEquals("该活下来的 id 不对：$got", listOf("a", "b"), got.map { it.id })
        assertEquals("重复 id 要留第一条", "好的", got[0].name)
        assertEquals("缺名称回退兜底名", Collections.FALLBACK_NAME, got[1].name)
        assertEquals("非数字/负数的 photoId 要跳过，重复的要去重", listOf(3L, 4L), got[1].photoIds)
    }

    /** 文件里超过上限的条目在**读**的时候就截断，不然一次坏文件能把上限绕过去。 */
    @Test
    fun decodeClampsPhotoCountToTheCap() {
        val ids = (1..(Collections.MAX_PHOTOS + 10)).joinToString(",")
        val json = """{"items":[{"id":"a","name":"大","photoIds":[$ids],"createdAt":$t0}]}"""
        val got = Collections.decode(json)
        assertEquals(1, got.size)
        assertEquals(Collections.MAX_PHOTOS, got[0].photoIds.size)
        assertEquals("截断要留前面那些", 1L, got[0].photoIds.first())
    }

    /** `updatedAt` 缺失时退成 `createdAt`，不许是 0（排序会把它踢到最后）。 */
    @Test
    fun missingUpdatedAtFallsBackToCreatedAt() {
        val json = """{"items":[{"id":"a","photoIds":[],"createdAt":$t0}]}"""
        assertEquals(t0, Collections.decode(json).single().updatedAt)
    }

    // ── 上限常量 ──────────────────────────────────────────────────────

    /** 上限本身也是契约：界面文案与 `create` 的闸门都读它。改这里要一起想清楚。 */
    @Test
    fun capsAreWhatTheDocsPromise() {
        assertEquals(200, Collections.MAX_COLLECTIONS)
        assertEquals(5000, Collections.MAX_PHOTOS)
        assertEquals("未命名收藏", Collections.FALLBACK_NAME)
    }
}
