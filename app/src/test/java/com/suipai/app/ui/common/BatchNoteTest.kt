package com.suipai.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量动作结论的判据。
 *
 * 装机复测（ANDROID-VERIFY §30）点到「副本」：媒体库确实多了一行，界面上一个字都没有 ——
 * 因为 `batchCopy` 只在 `failed > 0` 时写 batchNote。这一套把"每个分支都有话说"
 * 变成纯函数上的穷举：任何一组输入都得给出一句非空、且带着张数的话。
 */
class BatchNoteTest {

    @Test
    fun copySpeaksEvenWhenEverythingWorked() {
        assertEquals("存了 1 张", copyNote(1, 0, 1))
        assertEquals("存了 3 张，2 张没存上", copyNote(3, 2, 5))
        assertEquals("5 张都没存上（源文件可能已经不在）", copyNote(0, 5, 5))
    }

    @Test
    fun deleteSpeaksEvenWhenEverythingWorked() {
        assertEquals("删了 3 张", deleteNote(3, 0, 3))
        assertEquals("删了 2 张，1 张删不掉", deleteNote(2, 1, 3))
        assertEquals("2 张都没删掉（可能是权限或文件已不在）", deleteNote(0, 2, 2))
    }

    @Test
    fun hideAndFavoriteSayWhichDirection() {
        assertEquals("藏了 4 张", hideNote(false, 4, true))
        assertEquals("放出来 4 张", hideNote(true, 4, true))
        assertEquals("收藏了 2 张", favoriteNote(false, 2, true))
        assertEquals("取消藏了 2 张", favoriteNote(true, 2, true))
    }

    @Test
    fun aWriteThatDidNotLandSaysSo() {
        // `Hidden` 原来是 `apply()`：没落盘也没人知道，而"藏起来"是隐私
        val hide = hideNote(false, 3, false)
        assertTrue("要说没写进设置：" + hide, hide.contains("没写进设置"))
        assertTrue("张数不许丢：" + hide, hide.contains("藏了 3 张"))
        val rel = hideNote(true, 3, false)
        assertTrue("放出来那一支要说下次还在：" + rel, rel.contains("放出来 3 张") && rel.contains("没写进设置"))
        val fav = favoriteNote(false, 1, false)
        assertTrue(fav.contains("收藏了 1 张") && fav.contains("没写进设置"))
        assertTrue(persistWarning("收藏这一步").contains("收藏这一步没写进设置"))
        // 写成了就不许带这句尾巴
        assertTrue(!hideNote(false, 1, true).contains("没写进设置"))
    }

    @Test
    fun cancelledYishunRefusesToInventCounts() {
        // 进度回调报的是尝试次数（index+1），中途停下时手上没有可信的"出了几张"
        val s = yishunNote(0, 0, true)
        assertFalse("停了的结论里不许出现数字：$s", s.any { it.isDigit() })
        assertEquals("出了 4 张", yishunNote(4, 0, false))
        assertEquals("完成 4 张，失败 1 张", yishunNote(4, 1, false))
        assertEquals("3 张都没出（源文件可能已经不在）", yishunNote(0, 3, false))
    }

    @Test
    fun consentKeepsGoneHeldAndAlreadyApart() {
        assertEquals("没允许，3 张没删", consentNote(0, 3, 0))
        assertEquals("没允许，3 张没删（另删了 2 张）", consentNote(0, 3, 2))
        assertEquals("删了 3 张，1 张你没允许", consentNote(2, 3, 1))
        assertEquals("删了 5 张", consentNote(3, 3, 2))
        assertEquals("删了 2 张", consentNote(2, 2, 0))
    }

    @Test
    fun noInputCombinationIsSilent() {
        val samples = ArrayList<String>()
        for (a in 0..5) {
            for (b in 0..5) {
                samples += copyNote(a, b, 5)
                samples += deleteNote(a, b, 5)
                samples += yishunNote(a, b, false)
                samples += consentNote(a, 5, b)
            }
        }
        for (c in booleanArrayOf(true, false)) {
            for (n in 0..5) {
                samples += hideNote(c, n, true)
                samples += favoriteNote(c, n, true)
                samples += hideNote(c, n, false)
                samples += favoriteNote(c, n, false)
            }
        }
        samples += yishunNote(0, 0, true)
        val blank = samples.filter { it.isBlank() }
        assertTrue("有 ${blank.size} 句是空的", blank.isEmpty())
        // 每句都得留下"几张"这件事（"没查到"那一类由调用点的字面量负责，不在这套函数里）
        val noCount = samples.filter { !it.contains("张") && !it.contains("没算出") }
        assertTrue("有 ${noCount.size} 句没说张数：${noCount.take(3)}", noCount.isEmpty())
    }
}
