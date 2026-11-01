package com.suipai.app.ui

import com.suipai.app.ui.camera.CamRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画幅这张表的数据不变量：**格子上写的名字，必须等于照片真裁出来的比例**。
 *
 * 起因是两个量出来的缺陷：
 *  - `R169("16:9")` 压根没有 `cropAspect`（那一档不裁，改由相机开一条 16:9 的流），
 *    而后处理那条级联是八个布尔 + `else -> 9f/16f`，16:9 没有自己的布尔——**永远轮不到它**；
 *  - 反过来，给 4:3 也写上比例是**做过又撤回**的：竖拍按 EXIF 摆正之后画面是 1440×1920，
 *    再"裁成 4:3"只剩 1440×1080，2.76 MP 变 1.56 MP（装机量的）。
 *    所以规则是"每一档都得自己声明，唯一的例外是铺满传感器那一帧的 4:3"。
 */
class CamRatioGuardTest {

    companion object {
        /** 标签 → 期望比例。枚举里 "16:9" 与 "2.39" 两种写法混着用，都得认。 */
        fun declaredAspect(label: String): Float {
            val parts = label.split(":")
            return if (parts.size == 2) parts[0].toFloat() / parts[1].toFloat() else label.toFloat()
        }

        /**
         * 返回"名字与裁切目标不符"的档位。
         * `cropAspect == 0` 只允许给铺满传感器那一帧的用（`nativeLabel`），别的一律要自己声明。
         */
        fun mismatched(rows: List<Pair<String, Float>>, nativeLabel: String): List<String> =
            rows.filter { (label, crop) ->
                if (crop <= 0f) label != nativeLabel
                else kotlin.math.abs(crop - declaredAspect(label)) > 0.005f
            }.map { it.first }
    }

    @Test
    fun everyRatioCropsToWhatItsLabelPromises() {
        val rows = CamRatio.values().map { it.label to it.cropAspect }
        val bad = mismatched(rows, "4:3")
        assertTrue("这些画幅格子上写的比例与实际裁切目标不符：$bad", bad.isEmpty())
        val uncut = rows.filter { it.second <= 0f }.map { it.first }
        assertEquals("只该有 4:3 一档不裁（铺满传感器那一帧）", listOf("4:3"), uncut)
        assertTrue("画幅该有十档，现在是 ${rows.size} 档", rows.size >= 10)
        assertTrue("有两档同名", rows.map { it.first }.distinct().size == rows.size)
    }

    @Test
    fun onlyTheNativeFrameRatioIsUncut() {
        // 「4:3 也写上 4/3」不是名字不符（值是对的），它的代价是**白丢像素**：
        // 竖拍那一帧按 EXIF 摆正之后是 1440×1920，再"裁成 4:3"只剩 1440×1080，
        // 2.76 MP 变 1.56 MP（装机量的）。这条断言守的就是这一件事，别把它当"格式检查"删掉。
        assertTrue("4:3 这一档必须不裁：写上 4/3 会把默认档砍掉 44 % 像素",
            CamRatio.R43.cropAspect == 0f)
        val uncut = CamRatio.values().filter { it.cropAspect <= 0f }.map { it.label }
        assertEquals("只该有 4:3 一档不裁（铺满传感器那一帧）", listOf("4:3"), uncut)
    }

    @Test
    fun guardActuallyCatchesABadTable() {
        // 「16:9 不裁」就是修之前的真形状：它必须被抓到，否则上面那条断言是摆设。
        assertEquals(
            "探针抓不到「16:9 不裁」这个真翻车过的形状",
            listOf("16:9"),
            mismatched(listOf("4:3" to 0f, "16:9" to 0f, "1:1" to 1f), "4:3"),
        )
        // 数写错也要抓到；4:3 留 0 是合法的（见 onlyTheNativeFrameRatioIsUncut）
        assertEquals(listOf("1:1"), mismatched(listOf("4:3" to 0f, "1:1" to 1.6f), "4:3"))
    }
}
