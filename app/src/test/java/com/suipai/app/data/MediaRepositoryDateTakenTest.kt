package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「收到/复制进来的一张照片，时间线要按**拍摄时间**排」。
 *
 * ## 这条测试存在的理由
 *
 * 互通收到对端照片时，`SyncPhotoSink.saveIncoming(bytes, name, dateTaken)` 的签名
 * **一直带着** `dateTaken` —— 服务端从对端的 `listPhotos` 元信息里拿到的原始拍摄时间。
 * 而 `AndroidSyncSink` 的实现在整条链的最后一米把它丢掉了：`repo.saveJpeg(bytes, name)`
 * 没有时间参数，`saveBytes` 里写死 `DATE_TAKEN = System.currentTimeMillis()`。
 *
 * 后果不是"少了一个字段"，是**相册的主排序被打乱**：时间线按 `DATE_TAKEN DESC` 排，
 * 收得越多越乱，而乱法取决于当时的网络快慢。一次收十张，十张落在同一个毫秒上。
 * 代码里原来甚至有一段注释承认了这个缺口（"已知缺口：`dateTaken` 被丢掉了"），
 * 只是没人接 —— 这正是这一轮要找的那类"写了没人读"。
 *
 * ## 为什么钉子有一半是读源码
 *
 * `saveBytes` 里那一行要 `ContentResolver`，JVM 单测里是返回默认值的桩 ——
 * 真行为测不到。所以分两层：
 *  1. 纯函数 [dateTakenForSave] 的判据（能直接跑）；
 *  2. 「参数真的从 `saveIncoming` 一路传到了 `ContentValues`」这条**接线**，
 *     只能读源码验。少传一层就是一次静默的降级，编译器不会报（有默认参数）。
 */
class MediaRepositoryDateTakenTest {

    companion object {
        /**
         * 在 [text] 里找 `saveJpeg(` 的实参表里有没有把 `dateTaken` 传下去。
         *
         * 判据刻意只认**三个实参**的形状：`saveJpeg(bytes, name, dateTaken)`。
         * 两个实参（`saveJpeg(bytes, name)`）就是丢参数的那一版 —— 默认参数会把
         * "忘了传"变成"编译通过、行为错了"。
         */
        fun saveJpegCallsMissingDateTaken(text: String): List<String> {
            val out = ArrayList<String>()
            val re = Regex("""saveJpeg\(\s*([^()]*(?:\([^()]*\))?[^()]*)\)""")
            for (m in re.findAll(text)) {
                val args = m.groupValues[1]
                // 实参个数按顶层逗号数：这里最多三个简单实参，够用。
                val count = args.split(',').count { it.isNotBlank() }
                if (count < 3) out += m.value.replace(Regex("""\s+"""), " ")
            }
            return out
        }
    }

    // ── 纯函数：落盘前最后一道夹 ─────────────────────────────────────

    @Test
    fun aRealDateTakenIsWrittenAsIs() {
        val shotAt = 1_690_000_000_000L   // 2023-07 的某个时刻
        assertEquals(shotAt, dateTakenForSave(shotAt, now = 1_700_000_000_000L))
    }

    /**
     * `<= 0` = "不知道什么时候拍的"（老对端没填、元信息坏了）。
     *
     * 这时写"现在"是唯一正确的选择：写 0 会让那行落到 1970 年附近、被时间线排到最后，
     * 而"不写"会让它退回 `DATE_ADDED`（也就是收到的那一刻）—— 与写"现在"等价但更绕。
     */
    @Test
    fun unknownDateFallsBackToNowInsteadOfEpoch() {
        val now = 1_700_000_000_000L
        assertEquals(now, dateTakenForSave(0L, now))
        assertEquals(now, dateTakenForSave(-1L, now))
        assertEquals(now, dateTakenForSave(Long.MIN_VALUE, now))
        // 自检：绝不能把 0 / 负数原样放出去（那正是"落在 1970 年"的写法）
        assertNotEquals(0L, dateTakenForSave(0L, now))
    }

    // ── 接线：参数真的传到底了吗 ─────────────────────────────────────

    @Test
    fun saveJpegForwardsItsDateTakenIntoContentValues() {
        val f = File("src/main/java/com/suipai/app/data/MediaRepository.kt")
        assertTrue("找不到 MediaRepository.kt：${f.absolutePath}", f.isFile)
        val text = f.readText()

        assertTrue(
            "saveJpeg 的签名里没有 dateTaken —— 收到对端照片时又只能写「现在」了",
            Regex("""suspend fun saveJpeg\([\s\S]{0,200}?dateTaken""").containsMatchIn(text),
        )
        // saveJpeg → saveBytes 这一跳必须把时间带上（默认参数会让漏传编译通过）
        val body = text.substringAfter("suspend fun saveJpeg(").substringBefore("suspend fun saveBytes(")
        assertTrue(
            "saveJpeg 没有把 dateTaken 传给 saveBytes（三处实参缺一）。实际：" +
                body.lines().filter { "saveBytes(" in it }.joinToString(" | "),
            "dateTaken" in body.substringAfter("saveBytes(").substringBefore(")"),
        )
        // saveBytes → ContentValues 这一跳
        assertTrue(
            "saveBytes 没有把 dateTaken 落到 DATE_TAKEN 上：收到/复制的照片仍会写成「现在」",
            Regex("""DATE_TAKEN[^\n]*dateTakenForSave\(dateTaken\)""").containsMatchIn(text),
        )
    }

    @Test
    fun theSyncSinkPassesThePeersDateTakenThrough() {
        val f = File("src/main/java/com/suipai/app/sync/SyncSupport.kt")
        assertTrue("找不到 SyncSupport.kt：${f.absolutePath}", f.isFile)
        val text = f.readText()
        val missing = saveJpegCallsMissingDateTaken(text)
        assertTrue(
            "这些 saveJpeg 调用没把拍摄时间传下去（对端照片会按收到先后排，时间线直接乱）：" +
                missing.joinToString(" | "),
            missing.isEmpty(),
        )
    }

    /**
     * 自检：把"漏传第三个实参"的形状喂进同一个判据，必须变红。
     *
     * 少了这条，上面那条 `missing.isEmpty()` 可能只是因为正则压根没抓到东西（恒绿）。
     */
    @Test
    fun theGuardCatchesTheDroppedArgument() {
        val bad = """
            override fun saveIncoming(bytes: ByteArray, suggestedName: String, dateTaken: Long) =
                runBlocking { repo.saveJpeg(bytes, suggestedName) != null }
        """.trimIndent()
        assertEquals(
            "自检失败：丢参数的那一版没被抓出来",
            listOf("saveJpeg(bytes, suggestedName)"),
            saveJpegCallsMissingDateTaken(bad),
        )

        val good = """
            override fun saveIncoming(bytes: ByteArray, suggestedName: String, dateTaken: Long) =
                runBlocking { repo.saveJpeg(bytes, suggestedName, dateTaken) != null }
        """.trimIndent()
        assertTrue(
            "自检失败：传了三个实参的正常写法被误判：${saveJpegCallsMissingDateTaken(good)}",
            saveJpegCallsMissingDateTaken(good).isEmpty(),
        )
    }
}
