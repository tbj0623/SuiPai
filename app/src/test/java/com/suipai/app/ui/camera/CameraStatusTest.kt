package com.suipai.app.ui.camera

import com.suipai.app.ui.yishun.savedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机/一瞬那几句"做成了几件事"的话。
 *
 * 装机审计（ANDROID-VERIFY §32）抓到三处只说愿望不说实况：
 * 连拍只看最后一张的成败、间隔拍摄坏了也不吭声、
 * 一瞬的对比图结果没接却照说「已保存成片和对比图」。
 * 这三个纯函数就是那三句话的判据。
 */
class CameraStatusTest {

    @Test
    fun burstCountsSuccessesNotTheLastTry() {
        assertEquals("连拍完成 5 张", burstStatus(5, 5))
        assertEquals("连拍失败（5 张一张没存上）", burstStatus(0, 5))
        // 1 成 4 败、4 成 1 败：以前这两支分别说「连拍失败」和「连拍完成」
        assertEquals("连拍了 1/5 张，其余没存上", burstStatus(1, 5))
        assertEquals("连拍了 4/5 张，其余没存上", burstStatus(4, 5))
    }

    @Test
    fun intervalSaysHowManyItMissed() {
        assertEquals("间隔 3秒", intervalStatus(3, 0))
        val one = intervalStatus(3, 1)
        assertTrue("要数得出坏了第几张：" + one, one.contains("1 张没存上"))
        assertFalse("没有失败时不许提失败：" + intervalStatus(3, 0), intervalStatus(3, 0).contains("没存上"))
    }

    @Test
    fun savedNoteSplitsTheTwoFiles() {
        assertEquals("已写入系统相册", savedNote(false, false))
        assertEquals("已写入系统相册", savedNote(false, true))
        assertEquals("已保存成片和对比图", savedNote(true, true))
        val half = savedNote(true, false)
        assertTrue("对比图没存上要说出来：" + half, half.contains("对比图没存上"))
        assertTrue("成片存了这件事不许被否定：" + half, half.contains("成片已存"))
    }
}
