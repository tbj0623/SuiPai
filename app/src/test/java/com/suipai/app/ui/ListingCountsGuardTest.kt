package com.suipai.app.ui

import com.suipai.app.engine.Shift
import com.suipai.app.ui.camera.CamGrid
import com.suipai.app.ui.camera.CamRatio
import com.suipai.app.ui.viewer.CropRatio
import com.suipai.app.ui.viewer.ExportSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商店文案里**写出来的每一个数字**都必须是真的（`store/listing-zh.md` 第一节）。
 *
 * 起因：`CamGrid` 那张表名字挂着 23 条、实际只有 19 套线（四条重画），
 * 而文案里写的是「24 种构图辅助线预设」——两头都不对，见 `ANDROID-VERIFY.md` §14。
 * 界面里的条数是从枚举算的（`PhotoViewerScreen.kt:749/759` 直接写 `${ExportSize.entries.size} 档`），
 * 所以**只有文案会漂**；这一支测试就是让"改了表忘了改文案"在编译期之后立刻变红。
 *
 * 顺带把同一类缺陷的形状钉在四张表上：**不许两条名字画/裁/导出同一件事**。
 */
class ListingCountsGuardTest {

    companion object {
        /** 值相同的条目（键是"这件事本身"，不是名字）。 */
        fun duplicated(rows: List<Pair<String, Any>>): List<String> =
            rows.groupBy { it.second }.filterValues { it.size > 1 }.values.flatten().map { it.first }

        /**
         * Play 手机截图那一栏的硬门槛：**2–8 张**，每张比例在 9:16 与 16:9 之间、
         * 两条边都在 320–3840 px。返回不合格的那些（连读不出尺寸的也算）。
         *
         * 上限留 1.78 的余地：1080×1920 的商正好是 1.7777…，贴着 16/9 判浮点不安全。
         */
        fun unfitShots(rows: List<Pair<String, Pair<Int, Int>?>>, maxCount: Int = 8): List<String> {
            if (rows.size < 2) return listOf("<一共 ${rows.size} 张：Play 每一类要 2–$maxCount 张>")
            val over = if (rows.size > maxCount) listOf("<一共 ${rows.size} 张：超过 $maxCount 张>") else emptyList()
            return over + rows.filter { (_, wh) ->
                wh == null || wh.first < 320 || wh.first > 3840 || wh.second < 320 || wh.second > 3840 ||
                    wh.second.toFloat() / wh.first > 1.78f || wh.first.toFloat() / wh.second > 1.78f
            }.map { (n, wh) -> "$n=${wh?.first}×${wh?.second}" }
        }

        /**
         * PNG 的宽高就写在 IHDR 里（第 16..24 字节，大端），不需要图像库。
         * 读不出来（不是 PNG / 文件太短）就回 null —— 调用点把它算成违规，不静默跳过。
         */
        fun pngSize(f: java.io.File): Pair<Int, Int>? {
            if (f.length() < 26) return null
            val h = ByteArray(26)
            java.io.FileInputStream(f).use { it.read(h) }
            if (!(h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() &&
                    h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte())
            ) return null
            return intFrom(h, 16) to intFrom(h, 20)
        }

        private fun intFrom(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    @Test
    fun advertisedCountsMatchTheTables() {
        assertEquals("文案：24 种构图辅助线预设", 24, CamGrid.values().count { it.parts.isNotEmpty() })
        assertEquals("文案：10 档画幅一循环", 10, CamRatio.values().size)
        assertEquals("文案：裁切 16 种比例", 16, CropRatio.entries.size)
        assertEquals("文案：导出 15 档尺寸", 15, ExportSize.entries.size)
        assertEquals("文案：7 条微调滑杆", 7, Shift.values().size)
    }

    @Test
    fun noTwoEntriesDoTheSameThing() {
        val crops = CropRatio.entries.map { it.name to it.target }
        assertTrue("裁切表里有两条比例一样：%s", duplicated(crops).isEmpty())
        val exports = ExportSize.entries.map { it.name to it.edge }
        assertTrue("导出表里有两条尺寸一样：%s", duplicated(exports).isEmpty())
        assertTrue("导出 token 撞了（文件名会互相覆盖）：%s",
            duplicated(ExportSize.entries.map { it.name to it.token }).isEmpty())
        assertTrue("裁切 token 撞了：%s",
            duplicated(CropRatio.entries.map { it.name to it.token }).isEmpty())
        // 导出尺寸必须一档比一档小，否则界面上那一列就是乱序的
        val edges = ExportSize.entries.map { it.edge }
        assertTrue("导出尺寸没从大到小排：$edges", edges == edges.sortedDescending())
    }

    @Test
    fun guardActuallyCatchesADuplicatedEntry() {
        // 脏样本就是 §14 里那个真发生过的形状：两个名字、一套线（顺序不同也算同一套）。
        val dirty = listOf(
            "井字中点" to listOf("THIRD", "CROSS").sorted().joinToString("+"),
            "井字十字" to listOf("CROSS", "THIRD").sorted().joinToString("+"),
        )
        assertEquals(listOf("井字中点", "井字十字"), duplicated(dirty))
        // 干净样本必须不红，否则上面那条断言毫无意义。
        assertTrue(duplicated(CropRatio.entries.map { it.name to it.target }).isEmpty())
    }

    /**
     * PNG 的宽高就写在 IHDR 里（第 16..24 字节，大端），不需要图像库。
     * 读不出来（不是 PNG / 文件短）就回 null，让调用点把它算成违规而不是跳过。
     */
    internal fun pngSize(f: java.io.File): Pair<Int, Int>? {
        val h = ByteArray(26)
        if (f.length() < 26) return null
        java.io.FileInputStream(f).use { it.read(h) }
        if (!(h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() &&
                h[3] == 'G'.code.toByte())
        ) return null
        return intFrom(h, 16) to intFrom(h, 20)
    }

    private fun intFrom(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /**
     * 商店截图本身也是**会被产品改动带坏**的素材：界面一改，旧图就是在宣传一个不存在的界面；
     * 而张数与比例是 Play 的硬门槛，超了不是"难看"，是**上传直接被拒**。
     *
     * 装机量的现实：`store/screens/phone/` 那批图全部是 1080×2400（1:2.22）共 9 张 ——
     * 比例与张数两处都不合格，而这只有走到上传那一步才会发现。见 ANDROID-VERIFY.md §44。
     */
    @Test
    fun phoneScreenshotsFitPlaysFrame() {
        val dir = java.io.File("../store/screens/phone")
        assertTrue("找不到商店截图目录：${dir.absolutePath}", dir.isDirectory)
        val shots = dir.listFiles { f -> f.isFile && f.extension == "png" }.orEmpty().toList()
        assertTrue("一张截图都没有（读法失效）：${dir.absolutePath}", shots.isNotEmpty())
        val bad = unfitShots(shots.map { it.name to pngSize(it) })
        assertTrue("这些手机截图 Play 会拒收：" + bad.joinToString(" | "), bad.isEmpty())
        // 每张都得是 1080×1920：混合尺寸在 Play 的九宫格预览里会露出参差不齐的边。
        val odd = shots.map { it.name to pngSize(it) }.filter { it.second != (1080 to 1920) }
        assertTrue("这些不是 1080×1920：" + odd.map { "${it.first}=${it.second}" }, odd.isEmpty())

        // 自检：装机那批真实形状必须整批被判出来，重拍后的那一版不许误杀。
        val asShot = (1..9).map { "s$it.png" to (1080 to 2400) }
        val caught = unfitShots(asShot)
        assertTrue("1:2.22 的 9 张必须全判：" + caught, caught.size == 10)  // 9 张 + 一条超数
        assertTrue("超张数那条要在：" + caught, caught.any { it.contains("超过 8 张") })
        val reshoot = (1..8).map { "s$it.png" to (1080 to 1920) }
        assertTrue("重拍那一版被误杀：" + unfitShots(reshoot), unfitShots(reshoot).isEmpty())
        assertTrue("只剩一张也不合格：" + unfitShots(reshoot.take(1)), unfitShots(reshoot.take(1)).isNotEmpty())
        assertEquals("非 PNG 不能静默跳过", null, pngSize(java.io.File("build.gradle.kts")))
        assertTrue(
            "读不出尺寸的要算违规：" + unfitShots(listOf("a.png" to null, "b.png" to (1080 to 1920))),
            unfitShots(listOf("a.png" to null, "b.png" to (1080 to 1920))).contains("a.png=null×null"),
        )
    }
}
