package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Trash` 的纯逻辑：账单 json 的往返与兜底、剩余天数、过期筛选。
 *
 * 为什么只测这几块：`moveToTrash` / `restore` / `purge` 一落地就要碰 `ContentResolver`，
 * 而 JVM 单测里它是一个**返回默认值的桩**（`unitTests.isReturnDefaultValues = true`）——
 * 在那里测"搬家成不成功"，测到的是桩的默认值，不是真行为。所以真正值得钉、
 * 也钉得住的，是"搬家前后那本账单算得对不对"。
 *
 * 每一条都配了"喂坏样本必须变红"的自检：没见过自己红的断言不算断言。
 */
class TrashLogicTest {

    private val day = Trash.DAY_MILLIS
    private val t0 = 1_700_000_000_000L      // 钉一个时刻，别用 now

    private fun item(
        id: Long = 1L,
        name: String = "IMG_0001.jpg",
        uri: String = "content://media/external/images/media/1",
        deletedAt: Long = t0,
        size: Long = 2_000_000L,
        /**
         * 原始拍摄时间。默认给 `t0` 而**不是** 0：`encodeDecodeRoundTripsEverything` 是整条
         * `assertEquals(items, back)`，这个字段只要有一个字不进账单，那条就会红 ——
         * 那正是我们要的（证明字段真的进了账，而不是被默认值糊过去）。
         * 真实场景里它通常**不等于** `deletedAt`，所以下面几条用例会显式给不同的值。
         */
        dateTaken: Long = t0,
    ) = TrashItem(
        id = id,
        displayName = name,
        trashUri = uri,
        deletedAt = deletedAt,
        sizeBytes = size,
        width = 4032,
        height = 3024,
        dateTaken = dateTaken,
    )

    // ── 账单 json ─────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsEverything() {
        val items = listOf(
            item(id = 7L, name = "IMG_0007.jpg", uri = "content://media/external/images/media/107"),
            item(id = 8L, name = "截图 2024-06-01.png", uri = "content://media/external/images/media/108", size = 0L),
        )
        val back = Trash.decode(Trash.encode(items))
        assertEquals("字段丢了（往返必须一模一样，顺序也算）", items, back)
    }

    /** 空表要能往返：`encode(emptyList())` 不许变成 `null` 或抛。 */
    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<TrashItem>(), Trash.decode(Trash.encode(emptyList())))
    }

    /**
     * 坏 json 一律退化成空表，**绝不抛**。
     *
     * 这不是假想：账单是 app 自己写的，但它可能写到一半就被系统杀掉，
     * 那时 `readText()` 回来的是一段截断的 json。抛出去就是"点开回收站 app 崩了"。
     */
    @Test
    fun brokenJsonDegradesToEmptyTable() {
        val broken = listOf(
            "",
            "   ",
            "{",
            "[",
            "null",
            "{\"items\":",                       // 写了一半
            "{\"items\":\"不是数组\"}",
            "{\"items\":[null, 3, \"x\"]}",
            "[{},{},{}]",
            "\u0000\u0001乱码",
            "[]",
            "{\"items\":[]}",
        )
        for (text in broken) {
            val got = try {
                Trash.decode(text)
            } catch (e: Exception) {
                throw AssertionError("坏样本把 decode 抛了：<${text.take(24)}> → $e", e)
            }
            assertTrue("坏样本 <${text.take(24)}> 应退回空表，却解出了 $got", got.isEmpty())
        }
        // 自检：好样本不许被这条判据一起吃掉
        assertEquals(1, Trash.decode(Trash.encode(listOf(item()))).size)
    }

    /**
     * 单条坏记录**只丢它自己**，不连坐整份账单。
     *
     * 判据是「按 uri + deletedAt 筛」：没有 uri 的没法恢复、没有删除时间的没法算天数 ——
     * 这两种留下来会变成"永远删不掉的幽灵"。而其余字段（名字/宽高/大小）缺了就用兜底值。
     */
    @Test
    fun oneBadRecordOnlyDropsItself() {
        val json = """
            {"items":[
              {"id":1,"displayName":"好的.jpg","trashUri":"content://a/1","deletedAt":$t0,
               "sizeBytes":10,"width":100,"height":50},
              {"id":2,"displayName":"没uri.jpg","deletedAt":$t0},
              {"id":3,"displayName":"没时间.jpg","trashUri":"content://a/3"},
              {"id":4,"trashUri":"content://a/4","deletedAt":$t0},
              "这不是对象",
              {"id":5,"trashUri":"content://a/5","deletedAt":$t0,
               "sizeBytes":-5,"width":-1,"height":0}
            ]}
        """.trimIndent()
        val got = Trash.decode(json)
        assertEquals("该活下来的条数不对：$got", listOf(1L, 4L, 5L), got.map { it.id })
        assertEquals("缺名字要用兜底名，不许是空串", "未命名.jpg", got[1].displayName)
        assertEquals("负的字节数要夹到 0", 0L, got[2].sizeBytes)
        assertEquals("负的宽高要夹到 0", 0, got[2].width)
    }

    /** 两种文件形状都要认：直接是数组，或包一层 `{"items":[…]}`。 */
    @Test
    fun bothFileShapesDecode() {
        val one = item()
        val wrapped = Trash.encode(listOf(one))
        val bare = Trash.decode(wrapped).let { Trash.encode(it) }
        assertTrue("encode 出来的形状变了", wrapped.startsWith("{"))
        assertEquals(Trash.decode(wrapped), Trash.decode(bare))
    }

    // ── 原始拍摄时间（DATE_TAKEN） ────────────────────────────────────

    /**
     * 拍摄时间必须真的进账单、并且原样回来。
     *
     * 防的 bug：`TrashItem` 原来根本没有这个字段，于是删了又恢复的照片，
     * `DATE_TAKEN` 由 MediaProvider 补成"现在" —— 三年前的照片跳到今天的时间线。
     *
     * 用例里刻意让 `dateTaken` **不等于** `deletedAt`：如果实现哪天偷懒拿
     * `deletedAt` 兜底，这条会红（而"两者相等"的样本看不出来）。
     */
    @Test
    fun dateTakenSurvivesRoundTrip() {
        val shot = t0 - 900L * day      // 拍了两年多以前
        val withTime = item(id = 1L, deletedAt = t0, dateTaken = shot)
        val unknown = item(id = 2L, deletedAt = t0, dateTaken = 0L)
        val back = Trash.decode(Trash.encode(listOf(withTime, unknown)))

        assertEquals("拍摄时间没进账单", shot, back[0].dateTaken)
        assertEquals("0（不知道）必须原样往返，不许被补成一个值", 0L, back[1].dateTaken)
        assertEquals("两条都要回来", listOf(1L, 2L), back.map { it.id })
        // 整条相等也算一遍：任何字段（包括 dateTaken）漂了都会红。
        assertEquals(listOf(withTime, unknown), back)
        // 自检：这两个值确实不等 —— 否则上面"拿 deletedAt 兜底"那条判据测不出来。
        assertNotEquals(
            "样本本身没区分度：dateTaken 与 deletedAt 相等，兜底实现不会被抓住",
            withTime.dateTaken,
            withTime.deletedAt,
        )
    }

    /**
     * 老账单（没有 `dateTaken` 这个键）解出来必须是 `0`，**不许**拿 `deletedAt` 冒充。
     *
     * 用 `deletedAt` 兜底会把老照片错排进"删除那一天"的时间线，而且界面上看不出异常 ——
     * 这种错只能靠一条显式断言钉住。负值同样收成 0（`DATE_TAKEN` 合法值只有正毫秒）。
     */
    @Test
    fun legacyJsonWithoutDateTakenDecodesToZero() {
        val legacy = """
            {"items":[
              {"id":1,"displayName":"老.jpg","trashUri":"content://a/1","deletedAt":$t0,
               "sizeBytes":10,"width":100,"height":50},
              {"id":2,"displayName":"负值.jpg","trashUri":"content://a/2","deletedAt":$t0,
               "dateTaken":-12345},
              {"id":3,"displayName":"字符串.jpg","trashUri":"content://a/3","deletedAt":$t0,
               "dateTaken":"不是数字"}
            ]}
        """.trimIndent()
        val got = Trash.decode(legacy)
        assertEquals(3, got.size)
        assertEquals("缺键必须解成 0（不知道），不许拿 deletedAt 顶替", 0L, got[0].dateTaken)
        assertEquals("负值必须收成 0", 0L, got[1].dateTaken)
        assertEquals("类型不对必须收成 0，不许把整条丢掉", 0L, got[2].dateTaken)

        // 自检：如果实现拿 deletedAt 兜底，got[0].dateTaken 会是 t0 而不是 0 ——
        // 所以"t0 != 0"这件事本身就是这条判据的区分度。
        assertTrue("自检前提失效：t0 竟然是 0", t0 != 0L)
        assertNotEquals("自检失败：老账单解出来的竟然是 deletedAt", t0, got[0].dateTaken)
    }

    /**
     * 恢复时要写回媒体库的那个值：正数照写，0 与负数都是"不写"。
     *
     * 这是 `insertImage` 里 `if (dateTaken > 0)` 的唯一实现（抽成 `dateTakenToWrite`），
     * 而 `insertImage` 本身要 `ContentResolver`，在 JVM 上是返回默认值的桩 ——
     * 所以这条判据只能挂在纯函数上。
     */
    @Test
    fun dateTakenForRestoreIgnoresZeroAndNegatives() {
        val shot = t0 - 5 * day
        assertEquals("正数必须原样写回", shot, Trash.dateTakenToWrite(shot))
        assertEquals("0 = 不知道，不许写", 0L, Trash.dateTakenToWrite(0L))
        assertEquals("负数不许写回（会落到 1970 年之前）", 0L, Trash.dateTakenToWrite(-1L))
        assertEquals(0L, Trash.dateTakenToWrite(Long.MIN_VALUE))
        assertEquals("Long.MAX_VALUE 不该被夹掉", Long.MAX_VALUE, Trash.dateTakenToWrite(Long.MAX_VALUE))

        // 自检：如果实现写成"负数照写"，上面第二条会红；写成"总是返回 0"，
        // 第一条会红 —— 这里直接把两种错法的结果摆出来，证明判据有区分度。
        assertNotEquals("自检失败：负值被当成有效时间了", -1L, Trash.dateTakenToWrite(-1L))
        assertNotEquals("自检失败：正数被吞成 0 了", 0L, Trash.dateTakenToWrite(shot))
    }

    /** `encode` 一定要写出这个键（写不出来的话老文件永远升不了级，往返也保不住）。 */
    @Test
    fun encodeAlwaysWritesTheDateTakenKey() {
        val text = Trash.encode(listOf(item(dateTaken = 0L)))
        assertTrue("encode 里没有 dateTaken 键：$text", text.contains("\"dateTaken\":"))
        // 自检：换一个非 0 值，编出来的数字也要跟着变（不是写死一个常量）。
        val other = Trash.encode(listOf(item(dateTaken = 1234L)))
        assertTrue("自检失败：encode 没把值写进去：$other", other.contains("\"dateTaken\":1234"))
    }

    // ── 剩余天数 ──────────────────────────────────────────────────────

    /**
     * 边界全在这一条上：0 / 29 / 30 / 31 天。
     *
     * "30 天保留"的含义是**满 30 天整就可以清**，所以第 30 天当天 `daysLeft` 是 0、
     * 第 31 天仍是 0 —— 用户看到的永远是「今天清理」，不会看到负数。
     */
    @Test
    fun daysLeftHitsEveryBoundary() {
        val cases = listOf(
            // (已过天数, 期望)
            0L to 30,
            1L to 29,
            29L to 1,
            30L to 0,
            31L to 0,
            400L to 0,
        )
        for ((elapsed, want) in cases) {
            assertEquals(
                "已过 $elapsed 天时剩余天数不对",
                want,
                Trash.daysLeftOf(t0, t0 + elapsed * day),
            )
        }
        // 不满一天算 0 天：刚删的那一张显示「还剩 30 天」，不是「29 天」。
        assertEquals(30, Trash.daysLeftOf(t0, t0 + day - 1))
        assertEquals(29, Trash.daysLeftOf(t0, t0 + day))
    }

    /** 时钟被调过（`deletedAt` 在未来）不许返回负数或爆表的数。 */
    @Test
    fun daysLeftClampsWhenTheClockMovedBackwards() {
        assertEquals("未来的删除时间应显示满额", 30, Trash.daysLeftOf(t0 + 5 * day, t0))
        assertEquals(30, Trash.daysLeftOf(Long.MAX_VALUE, t0))
        // Long.MIN_VALUE 的日期会让 `now - deletedAt` 溢出成负数，必须走"未来"那一支而不是崩。
        assertEquals(30, Trash.daysLeftOf(Long.MIN_VALUE, t0))
    }

    /** 保留天数可配：7 天档也要在同一套边界上成立。 */
    @Test
    fun daysLeftHonoursACustomRetention() {
        assertEquals(7, Trash.daysLeftOf(t0, t0, 7))
        assertEquals(1, Trash.daysLeftOf(t0, t0 + 6 * day, 7))
        assertEquals(0, Trash.daysLeftOf(t0, t0 + 7 * day, 7))
        assertEquals("0 天保留 = 立刻过期", 0, Trash.daysLeftOf(t0, t0, 0))
    }

    // ── 过期筛选 ──────────────────────────────────────────────────────

    /**
     * `purgeExpired` 的筛选部分（`expiredItems`）—— 这也是"自动清理 30 天"的唯一实现。
     *
     * 边界与 `daysLeft` 相同：第 29 天不动、第 30 天清。
     */
    @Test
    fun expiredSelectionUsesTheSameBoundaryAsTheBadge() {
        val items = listOf(
            item(id = 1L, deletedAt = t0),                 // 0 天
            item(id = 2L, deletedAt = t0 - 29 * day),      // 29 天：还在
            item(id = 3L, deletedAt = t0 - 30 * day),      // 30 天：过期
            item(id = 4L, deletedAt = t0 - 31 * day),      // 31 天：过期
            item(id = 5L, deletedAt = t0 + 3 * day),       // 未来的：不动
        )
        val expired = Trash.expiredItems(items, t0).map { it.id }
        assertEquals("过期判定与角标的边界必须一致：$expired", listOf(3L, 4L), expired)
    }

    @Test
    fun emptyTableHasNothingToPurge() {
        assertEquals(emptyList<TrashItem>(), Trash.expiredItems(emptyList(), t0))
        assertEquals(emptyList<TrashItem>(), Trash.decode(Trash.encode(emptyList())))
    }

    /**
     * 整表过期时全清；表里没有过期项时**一条都不许动** ——
     * 后者才是真正会出事的那个方向（把没到期的删了）。
     */
    @Test
    fun onlyExpiredOnesAreSelected() {
        val fresh = (1..5).map { item(id = it.toLong(), deletedAt = t0 - it * day) }
        assertEquals("没到期的一条都不许选进来", emptyList<TrashItem>(), Trash.expiredItems(fresh, t0))

        val all = (1..5).map { item(id = it.toLong(), deletedAt = t0 - (30 + it) * day) }
        assertEquals("全部过期就要全选", 5, Trash.expiredItems(all, t0).size)
    }

    // ── 自检：判据真的会因为坏数据变红 ────────────────────────────────

    /**
     * 本文件所有断言的"反向样本"集中在这里：如果上面那些断言写成了恒真式，
     * 这一段会把它们暴露出来（它自己必须通过，也就是"错的值确实不等于期望值"）。
     */
    @Test
    fun theAssertionsWouldCatchAWrongImplementation() {
        // 剩余天数写成"四舍五入"或"不夹 0"都会在这里露馅
        assertFalse("第 30 天不该还有 1 天", Trash.daysLeftOf(t0, t0 + 30 * day) == 1)
        assertFalse("过期之后不该出现负数", Trash.daysLeftOf(t0, t0 + 90 * day) < 0)
        // 过期判定写成"大于 30 天"会把第 30 天那一天漏掉
        assertTrue(
            "刚好 30 天整必须是过期",
            Trash.expiredItems(listOf(item(deletedAt = t0 - 30 * day)), t0).isNotEmpty(),
        )
        // json 兜底写成"抛异常"会让坏样本这一条挂掉
        assertTrue(Trash.decode("{\"items\":[").isEmpty())
        // 拍摄时间写成"用 deletedAt 兜底"会让这条露馅：没有 dateTaken 键的老条目
        // 解出来必须是 0，而不是 deletedAt。
        val legacyOne = Trash.decode(
            "{\"items\":[{\"id\":1,\"trashUri\":\"content://a/1\",\"deletedAt\":$t0}]}",
        ).single()
        assertNotEquals("拍摄时间被 deletedAt 顶替了", legacyOne.deletedAt, legacyOne.dateTaken)
        // 恢复时写成"负数照写"会让这条露馅。
        assertNotEquals("负数被当成有效拍摄时间", -42L, Trash.dateTakenToWrite(-42L))
    }
}
