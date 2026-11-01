package com.suipai.app.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量那一行的判据：没报就是没报。
 *
 * 原来 `intOf(...) ?: 0` 把「这家用人没回 usage」写成「输入 0 输出 0 token」——
 * 那一格是"这一趟花了多少"的凭据，编一个 0 比空着更容易骗人
 * （而且 BYO 的中转端点十家有八家不回 usage，那等于常态在骗）。
 */
class TelemetryLineTest {

    @Test
    fun reportsBothNumbersWhenGiven() {
        assertEquals("1.2s · 输入 64 输出 19 token", telemetryLine(1234, 64, 19))
    }

    @Test
    fun saysSoWhenTheProviderStayedSilent() {
        val s = telemetryLine(900, null, null)
        assertTrue("要明说没报：" + s, s.contains("没报"))
        assertFalse("不许出现被编出来的 0：" + s, Regex("""\d+\s*token|0\s*输出""").containsMatchIn(s))
    }

    @Test
    fun halfReportIsNotRoundedUpToZero() {
        val s = telemetryLine(100, 5, null)
        assertTrue("只报一半也要说出来：" + s, s.contains("只报了一半") && s.contains("输入 5"))
        assertFalse("缺的那半不许写成 0：" + s, s.contains("输出 0"))
    }
}
