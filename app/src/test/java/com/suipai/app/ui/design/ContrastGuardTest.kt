package com.suipai.app.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 对比度守卫：两套主题里**每一对「字 / 底」**都必须真的读得清。
 *
 * 为什么这一条必须是测试而不是设计时的一句话：
 * 这个项目里"某个字突然读不清"的机会非常多 —— 暗房底与相纸白相邻、
 * 琥珀在两种主题下是两个完全不同的色值、危险红有两种用途（装饰红 vs 可读红）。
 * 眼睛在显示器上看不出 4.2 与 4.6 的差别，而差值正好跨过合规线的那一档
 * 就是"上架时被打回"的那一档。
 *
 * 判据用 WCAG 2.1 的相对亮度公式（sRGB → 线性化 → 加权求和），
 * 阈值 4.5:1（正文尺寸）。这条是本仓**唯一**允许的对比度算法，
 * 别处若要算对比度，从这里调 [Wcag.contrastOf]，不要另写一份。
 *
 * 每条判定都配一条"喂坏样本必须变红"的自检 —— 没见过自己变红的守卫不算守卫
 * （这个形状照抄 `PerfSourceGuardTest.checkersActuallyCatchBadCode`）。
 */
class ContrastGuardTest {

    /**
     * 每一对必须成立的「字色 token → 底 token」。
     *
     * 这张表是本测试的**全部内容**：加一支颜色、加一片底，必须同时想清楚
     * 它上面会画什么字。表里没有的组合等于"没有承诺"，所以宁可多列。
     */
    private val requiredPairs: List<Pair<String, String>> = listOf(
        // 主文字：三档，出现在每一个面上
        "ink" to "canvas",
        "ink" to "canvasSunken",
        "ink" to "panel",
        "ink" to "panelRaised",
        "ink" to "panelOverlay",
        "ink" to "selectionSurface",
        "ink" to "safelightWash",
        "ink" to "fixerWash",
        // 次级文字
        "inkSecondary" to "canvas",
        "inkSecondary" to "panel",
        "inkSecondary" to "panelRaised",
        "inkSecondary" to "selectionSurface",
        // 三级文字（编号 / 元数据）
        "inkTertiary" to "canvas",
        "inkTertiary" to "canvasSunken",
        "inkTertiary" to "panel",
        "inkTertiary" to "panelRaised",
        "inkTertiary" to "panelOverlay",
        // 琥珀作为文字（强调标签、选中芯片字、文字按钮）
        "safelight" to "canvas",
        "safelight" to "panel",
        "safelight" to "panelRaised",
        "safelight" to "selectionSurface",
        // 危险色作为**文字**（删除确认、错误提示）—— 注意用的是 fixerInk 不是 fixer
        "fixerInk" to "canvas",
        "fixerInk" to "panel",
        "fixerInk" to "panelRaised",
        "fixerInk" to "fixerWash",
        // 洗色容器上的字
        "inkOnSafelightWash" to "safelightWash",
        "inkOnFixerWash" to "fixerWash",
        // 实心填充上的字
        "inkOnSafelight" to "safelight",
        "inkOnDangerFill" to "dangerFill",
        // 反相面
        "inkInverse" to "ink",
    )

    /** 4.5:1 —— 正文尺寸（WCAG 1.4.3 AA）。 */
    private val bodyThreshold = 4.5

    /** 3:1 —— 大字号（≥18pt / 14pt bold）与"非文字"控件边界（WCAG 1.4.11）。 */
    private val largeThreshold = 3.0

    /** 两套主题都要过。 */
    private fun bothThemes(): List<Pair<String, Map<String, ColorToken>>> = listOf(
        "暗房（深色）" to DarkRoomTokens.all.associateBy { it.name },
        "日光印样（浅色）" to DaylightTokens.all.associateBy { it.name },
    )

    @Test
    fun everyTextPairMeetsWcagBodyContrast() {
        val failures = ArrayList<String>()
        var checked = 0
        for ((themeName, tokens) in bothThemes()) {
            for ((fgName, bgName) in requiredPairs) {
                val fg = tokens[fgName]
                val bg = tokens[bgName]
                assertTrue("$themeName 缺 token：$fgName", fg != null)
                assertTrue("$themeName 缺 token：$bgName", bg != null)
                val ratio = Wcag.contrastOf(fg!!.argb, bg!!.argb)
                checked++
                if (ratio < bodyThreshold) {
                    failures += "$themeName $fgName(${fg.hex}) on $bgName(${bg.hex}) = " +
                        "%.2f:1 < $bodyThreshold:1".format(ratio)
                }
            }
        }
        assertTrue("这是空跑：一对都没核对到", checked >= requiredPairs.size * 2)
        assertTrue("这些字在它的底上读不清（WCAG AA 4.5:1）：\n" + failures.joinToString("\n"), failures.isEmpty())

        // ── 自检：判定真的会因为"读不清"变红 ──
        // 1. 暗房底上写与自己几乎一样的字（真实踩过的形状：LocalContentColor 回退成黑色）
        assertTrue(
            "黑字压在暗房底上居然判过了",
            Wcag.contrastOf(0xFF101413, 0xFF101413) < bodyThreshold,
        )
        // 2. 琥珀原色压在纸白上 —— 浅色主题**必须**压深，原色只能到 1.9:1
        assertTrue(
            "浅色主题用原色琥珀 #E8A33D 判过了（它只有约 1.9:1）",
            Wcag.contrastOf(0xFFE8A33D, 0xFFF2EFE8) < bodyThreshold,
        )
        // 3. 定影红本体当文字用（装饰色误当可读色）也要红
        assertTrue(
            "定影红 #C4453B 直接当文字压在暗房底上判过了",
            Wcag.contrastOf(0xFFC4453B, 0xFF101413) < bodyThreshold,
        )
        // 4. 反方向：真正合规的两对不许误判
        assertTrue(
            "相纸白在暗房底上被误判了",
            Wcag.contrastOf(0xFFEDEBE4, 0xFF101413) >= bodyThreshold,
        )
        assertTrue(
            "墨字在日光纸白上被误判了",
            Wcag.contrastOf(0xFF1A1E1C, 0xFFF2EFE8) >= bodyThreshold,
        )
    }

    @Test
    fun everyInteractiveBoundaryMeetsWcagNonTextContrast() {
        // 交互描边（格子的框、输入框的框）承担"这里能点"，属于 1.4.11 非文字对比，≥3:1。
        // 装饰用的 hairline **不在此列**（它不指示可点性），这一点在文档里写明了。
        val failures = ArrayList<String>()
        var checked = 0
        for ((themeName, tokens) in bothThemes()) {
            val outline = tokens.getValue("outline")
            for (surface in listOf("canvas", "panel", "panelRaised")) {
                val bg = tokens.getValue(surface)
                val ratio = Wcag.contrastOf(outline.argb, bg.argb)
                checked++
                if (ratio < largeThreshold) {
                    failures += "$themeName outline(${outline.hex}) on $surface(${bg.hex}) = " +
                        "%.2f:1 < $largeThreshold:1".format(ratio)
                }
            }
        }
        assertTrue("这一条在空跑", checked >= 6)
        assertTrue("交互描边在它所在的面上太淡，看不出这是一颗可点控件：\n" + failures.joinToString("\n"), failures.isEmpty())

        // 自检：装饰线（hairline）确实"不合格"——这正是它不能当交互描边用的原因
        assertTrue(
            "装饰发丝线的对比居然达标了，那 outline 与 hairline 就没必要分开",
            Wcag.contrastOf(0xFF2A312E, 0xFF161B1A) < largeThreshold,
        )
        assertTrue(
            "交互描边 #6B7570 在面板上被误判了",
            Wcag.contrastOf(0xFF6B7570, 0xFF161B1A) >= largeThreshold,
        )
    }

    @Test
    fun disabledAndDecorativePairsAreDeclaredNotAccidental() {
        // 禁用态刻意低于 4.5:1：它本来就"读不清"，但**必须**仍然看得见轮廓（≥2:1），
        // 否则用户会以为那颗按钮消失了。这一条把"低对比"变成一个有下限的刻意选择。
        val darkPanel = DarkRoomTokens.panel.argb
        val darkInk = DarkRoomTokens.ink.argb
        val disabledInk = Wcag.blendOver(darkInk, darkPanel, alpha = 0.38f)
        val disabledRatio = Wcag.contrastOf(disabledInk, darkPanel)
        assertTrue(
            "禁用态文字低于 2:1，用户会以为控件消失了（实测 %.2f）".format(disabledRatio),
            disabledRatio >= 2.0,
        )
        assertTrue(
            "禁用态文字不该达到正文对比度（那样它看起来仍然可用）",
            disabledRatio < bodyThreshold,
        )

        // 自检：把 alpha 拉到 0（等于没画）必须跌破下限
        val invisible = Wcag.blendOver(darkInk, darkPanel, alpha = 0f)
        assertTrue(
            "alpha=0 的禁用态居然 >=2:1，混合公式写错了",
            Wcag.contrastOf(invisible, darkPanel) < 2.0,
        )
    }

    @Test
    fun greaseEqualsSafelightInBothThemes() {
        // 视觉契约：油笔圈选就是琥珀，**不是第二套强调色**。
        // 这条很便宜但很重要：一旦有人给 grease 换一个色，整个世界就有了两支笔。
        assertEquals(
            "深色主题的 grease 与 safelight 不是同一个色",
            DarkRoomTokens.safelight.argb,
            DarkRoomTokens.grease.argb,
        )
        assertEquals(
            "浅色主题的 grease 与 safelight 不是同一个色",
            DaylightTokens.safelight.argb,
            DaylightTokens.grease.argb,
        )
        // 自检：换一支笔必须红
        assertTrue(
            "换成定影红之后 grease 仍然被当成合格，这条判据没在核对什么",
            DarkRoomTokens.fixer.argb != DarkRoomTokens.grease.argb,
        )
    }
}

/**
 * WCAG 2.1 相对亮度与对比度。**纯函数、无依赖**，所以能在 JVM 单测里直接跑。
 *
 * 公式（WCAG 2.1 §1.4.3 定义）：
 * ```
 * 每个通道 c/255 → 若 ≤0.03928 则 c/12.92，否则 ((c+0.055)/1.055)^2.4
 * L = 0.2126R + 0.7152G + 0.0722B
 * 对比度 = (L亮 + 0.05) / (L暗 + 0.05)
 * ```
 *
 * 注意：这是 **2.1** 的正则。WCAG 2.2 只改了 3:1 那条的适用条件，
 * 没有改这个算法 —— 文档里标了版本，免得下一个人按 2.2 重算一遍。
 */
object Wcag {

    /** 通道线性化。 */
    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** 相对亮度 L（0 = 纯黑，1 = 纯白）。忽略 alpha。 */
    fun relativeLuminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    /** 两支颜色之间的对比度（1.0 到 21.0）。顺序无关。 */
    fun contrastOf(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 把 `fg` 以 `alpha` 压在 `bg` 上，返回实际的 ARGB。
     *
     * 用来算"禁用态文字"这类半透明叠加的真实对比度 ——
     * 只按 alpha 估算是错的（暗底上压白 38% 与白底上压黑 38% 差很多）。
     */
    fun blendOver(fg: Long, bg: Long, alpha: Float): Long {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int): Long {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return Math.round(f * a + b * (1f - a)).toLong()
        }
        val alphaByte = ((bg shr 24) and 0xFF)
        return (alphaByte shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
