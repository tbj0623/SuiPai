package com.suipai.app.ui.play

import com.suipai.core.engine.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配方卡的判据。**全部在 JVM 上跑**：卡被切成三层，前两层（模型 / 材料像素）不依赖任何
 * Android 类型，所以可以逐字段、逐像素断言。
 *
 * 这一篇钉住五件事：
 * 1. **同参数两次渲染完全一致**（模型相等 + 材料像素逐字节相等）—— 卡是要发出去的，
 *    "两次生成不一样"是缺陷；
 * 2. 卡上**不许出现时间、随机、设备信息**（那正是"两次不一样"的来路）；
 * 3. 强度会正确地出现在卡上，且越界会被夹取；
 * 4. 强调色由配方自己的色温决定（暖 → 琥珀、冷 → 青、单色 → 灰）；
 * 5. 每条判据都配"喂坏例子必须变红"的自检。
 */
class RecipeCardTest {

    companion object {
        fun look(
            id: String = "daylight",
            name: String = "日光",
            summary: String = "轻微提亮，暖调收在高光",
            monochrome: Boolean = false,
            temperature: Float = 0f,
            contrast: Float = 0.12f,
            saturation: Float = 0.04f,
            grain: Float = 0.08f,
            vignette: Float = 0.08f,
        ): Look = Look(
            id = id,
            name = name,
            summary = summary,
            monochrome = monochrome,
            contrast = contrast,
            saturation = saturation,
            temperature = temperature,
            grain = grain,
            vignette = vignette,
        )

        /** 用 1:1 档的小尺寸做确定性断言（不必真的吃 1080×1350 的内存）。 */
        fun smallLayout(): RecipeCardLayout = recipeCardLayout(180, 180)

        fun drawMaterials(layout: RecipeCardLayout, model: RecipeCardModel): IntArray {
            val px = IntArray(layout.width * layout.height)
            RecipeCardPixels.paint(px, layout, RecipeCardInk(), model.accentArgb)
            return px
        }

        fun changedCount(a: IntArray, b: IntArray): Int {
            var n = 0
            for (i in a.indices) if (a[i] != b[i]) n++
            return n
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 1. 确定性
    // ────────────────────────────────────────────────────────────────

    @Test
    fun sameInputsGiveTheSameCard() {
        val l = look()
        val a = recipeCardModel(l, 0.62f)
        val b = recipeCardModel(l, 0.62f)
        assertTrue("同参数两次生成的模型不相等：\n$a\n$b", a.isSameAs(b))
        assertEquals("模型不是 data class 的相等", a, b)

        val layout = smallLayout()
        val px1 = drawMaterials(layout, a)
        val px2 = drawMaterials(layout, b)
        assertEquals("同参数两次画出的材料像素不同", 0, changedCount(px1, px2))

        // 自检 1：**模型**必须对参数敏感（强度变了模型得变）。
        // 注意材料像素对强度是**不敏感**的 —— 纸底/框线/强调竖条是材料，
        // 强度只影响卡上的文字（Canvas 那一层画）。所以自检要分层打，
        // 不能要求"材料像素也跟着强度变"（那是把两层混成一层的错判）。
        val c = recipeCardModel(l, 0.63f)
        assertNotEquals("强度变了模型却没变", a, c)

        // 自检 2：**材料**必须对强调色敏感（换个配色，纸上的竖条要跟着变）。
        val otherAccent = a.copy(accentArgb = 0xFF00FF00.toInt())
        val px3 = drawMaterials(layout, otherAccent)
        assertNotEquals(
            "换了强调色材料像素却没变 —— 强调竖条没被画进去",
            px1.toList(),
            px3.toList(),
        )

        // 自检 3：**材料**必须对纸色敏感（换了 stock，整幅底色要跟着变）。
        val otherInk = RecipeCardInk(paperArgb = 0xFF203040.toInt())
        val px4 = IntArray(layout.width * layout.height).also {
            RecipeCardPixels.paint(it, layout, otherInk, a.accentArgb)
        }
        assertNotEquals("换了纸色材料像素却没变 —— 纸底没被铺上", px1.toList(), px4.toList())
    }

    @Test
    fun cardCarriesNoTimeOrDeviceInformation() {
        val model = recipeCardModel(look(), 0.5f)
        val everything = (
            listOf(model.kicker, model.title, model.summary, model.strengthLine, model.footer) +
                model.parameterLines
            ).joinToString("\n")
        // 年份 / 时间戳 / 设备 —— 任何一样进卡，"同一张卡发两次不一样"就再也修不掉。
        assertFalse("卡里出现了四位年份：$everything", Regex("""\b(19|20)\d{2}\b""").containsMatchIn(everything))
        assertFalse("卡里出现了冒号分隔的时间", Regex("""\d{1,2}:\d{2}""").containsMatchIn(everything))
        assertFalse("卡里出现了 build/device 字样", everything.contains("Android", ignoreCase = true))
        // 自检：这条正则确实能抓到 —— 往字符串里塞一个年份必须命中
        assertTrue(
            "年份正则失效了（塞进去也抓不到）",
            Regex("""\b(19|20)\d{2}\b""").containsMatchIn("2026 年拍的"),
        )
    }

    @Test
    fun strengthIsOnTheCardAndIsClamped() {
        assertEquals("强度 62% 没写对", "强度 62%", recipeCardModel(look(), 0.62f).strengthLine)
        assertEquals("强度 0 没写对", "强度 0%", recipeCardModel(look(), 0f).strengthLine)
        assertEquals("强度 100 没写对", "强度 100%", recipeCardModel(look(), 1f).strengthLine)
        // 越界必须夹取：界面那根滑杆理论上给不出越界值，但模型层不许依赖调用方的自觉
        assertEquals(">1 没夹住", "强度 100%", recipeCardModel(look(), 4f).strengthLine)
        assertEquals("<0 没夹住", "强度 0%", recipeCardModel(look(), -2f).strengthLine)
        // 自检：0.615 应该四舍五入到 62 而不是 61（用截断实现会红）
        assertEquals("四舍五入退化成截断了", "强度 62%", recipeCardModel(look(), 0.615f).strengthLine)
    }

    // ────────────────────────────────────────────────────────────────
    // 2. 强调色由配方自己决定
    // ────────────────────────────────────────────────────────────────

    @Test
    fun accentFollowsTheLookTemperature() {
        val amber = 0xFFE8A33D.toInt()
        val cool = 0xFF6F97A8.toInt()
        val gray = 0xFF9AA39E.toInt()
        val neutral = 0xFFC9C3B4.toInt()

        assertEquals("暖调没走琥珀", amber, accentFor(look(temperature = 0.12f)))
        assertEquals("冷调没走青蓝", cool, accentFor(look(temperature = -0.12f)))
        assertEquals("中性没走相纸灰", neutral, accentFor(look(temperature = 0.0f)))
        // 单色优先于色温：黑白配方刷暖色会和"黑白"打架
        assertEquals("单色没优先走灰", gray, accentFor(look(monochrome = true, temperature = 0.3f)))

        // 自检：边界值两侧必须分开（阈值写成 >= 会把 0.02 也算暖调）
        assertEquals("阈值边界判错了", neutral, accentFor(look(temperature = 0.02f)))
        assertEquals("阈值边界判错了", amber, accentFor(look(temperature = 0.021f)))
    }

    // ────────────────────────────────────────────────────────────────
    // 3. 材料像素（纸底 / 框线 / 强调竖条）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun materialsDrawPaperFrameRuleAndAccentBar() {
        val layout = smallLayout()
        val model = recipeCardModel(look(temperature = 0.2f), 0.5f)
        val ink = RecipeCardInk()
        val px = drawMaterials(layout, model)
        val w = layout.width

        // 纸底：整幅覆盖（先填满再画别的），所以画面中心的远处必须是纸色
        val farLeftTop = px[layout.margin * w + layout.margin]
        assertEquals("纸底没铺满", ink.paperArgb, farLeftTop)

        // 内框线：frameRect 的左边那一列必须是线色
        val frameY = (layout.frameRect.top + layout.frameRect.bottom) / 2
        assertEquals(
            "内框线没画在 frameRect 上",
            ink.lineArgb,
            px[frameY * w + layout.frameRect.left],
        )

        // 配方名下面那道横线
        val ruleY = (layout.ruleRect.top + layout.ruleRect.bottom) / 2
        val ruleX = layout.ruleRect.left + 3
        assertEquals("横线没画", ink.lineArgb, px[ruleY * w + ruleX])

        // 强调色竖条：用配方自己的色
        val accentY = (layout.accentRect.top + layout.accentRect.bottom) / 2
        val accentX = (layout.accentRect.left + layout.accentRect.right) / 2
        assertEquals("强调色竖条颜色不对", model.accentArgb, px[accentY * w + accentX])

        // 自检：把强调色换掉，那个像素必须跟着变（否则上面那条是假绿）
        val other = drawMaterials(layout, model.copy(accentArgb = 0xFF00FF00.toInt()))
        assertNotEquals(
            "换了强调色竖条却没变 —— 上面那条断言抓的是别的东西",
            px[accentY * w + accentX],
            other[accentY * w + accentX],
        )
    }

    @Test
    fun layoutNeverOverlapsOrEscapesTheCard() {
        // 三档尺寸 × 极端尺寸，所有矩形都必须在卡内、且竖排顺序不重叠。
        val sizes = listOf(
            RecipeCardSize.PORTRAIT_4x5,
            RecipeCardSize.SQUARE,
            RecipeCardSize.STORY_9x16,
        )
        for (size in sizes) {
            val layout = recipeCardLayout(size.width, size.height)
            assertEquals("宽度没按档位给", size.width, layout.width)
            assertEquals("高度没按档位给", size.height, layout.height)
            val rects = listOf(
                "kicker" to layout.kickerRect,
                "title" to layout.titleRect,
                "summary" to layout.summaryRect,
                "rule" to layout.ruleRect,
                "strength" to layout.strengthRect,
                "params" to layout.paramsRect,
                "accent" to layout.accentRect,
                "footer" to layout.footerRect,
            )
            for ((name, r) in rects) {
                assertTrue(
                    "${size.name} 的 $name 越界：$r",
                    r.left >= 0 && r.top >= 0 && r.right <= size.width && r.bottom <= size.height,
                )
                assertFalse("${size.name} 的 $name 是空的：$r", r.isEmpty)
            }
            // 竖排顺序：kicker < title < summary < rule < strength < params < footer
            assertTrue("kicker 没在 title 上面", layout.kickerRect.bottom <= layout.titleRect.top)
            assertTrue("title 没在 summary 上面", layout.titleRect.bottom <= layout.summaryRect.top)
            assertTrue("summary 没在 rule 上面", layout.summaryRect.bottom <= layout.ruleRect.top)
            assertTrue("rule 没在 strength 上面", layout.ruleRect.bottom <= layout.strengthRect.top)
            assertTrue("strength 没在 params 上面", layout.strengthRect.bottom <= layout.paramsRect.top)
            assertTrue("params 没在 footer 上面", layout.paramsRect.bottom <= layout.footerRect.top)
        }
        // 自检：退化尺寸不许崩（会走到 coerceAtLeast 的分支）
        val tiny = recipeCardLayout(8, 8)
        assertTrue("退化尺寸算出了空的 title", !tiny.titleRect.isEmpty)
    }

    // ────────────────────────────────────────────────────────────────
    // 4. 参数行与文件名
    // ────────────────────────────────────────────────────────────────

    @Test
    fun parameterLinesAreBoundedAndSigned() {
        val model = recipeCardModel(look(), 0.5f)
        assertTrue("参数行超过上限", model.parameterLines.size <= RecipeCardModel.MAX_PARAMS)
        assertTrue("参数行是空的", model.parameterLines.isNotEmpty())

        // 每一行必须是"名字 + 带符号的两位小数"，否则等宽下对不齐
        val signedValue = Regex("""^.+\s[+-]\d\.\d{2}$""")
        for (line in model.parameterLines) {
            assertTrue("参数行格式不对：$line", signedValue.matches(line))
        }
        // 自检：正则确实会拒掉一个格式不对的
        assertFalse("正则太宽（它接受了纯数字）", signedValue.matches("影调 0.12"))

        // 负值必须带减号，不是靠"值本身看起来像负的"
        val neg = recipeCardModel(look(contrast = -0.12f), 0.5f)
        assertTrue("负值没有减号", neg.parameterLines.first { it.startsWith("影调") }.contains("-0.12"))
        val pos = recipeCardModel(look(contrast = 0.12f), 0.5f)
        assertTrue("正值没有加号", pos.parameterLines.first { it.startsWith("影调") }.contains("+0.12"))
    }

    @Test
    fun fileNameIsSafeAndStable() {
        val name = recipeCardFileName("daylight", 1234L)
        assertTrue("文件名少了加工件前缀：$name", name.startsWith("SuiPai_配方卡_"))
        assertTrue("文件名没带时间戳", name.contains("1234"))
        assertTrue("后缀不是 png（卡要清晰）", name.endsWith(".png"))
        assertFalse("文件名里出现了非法字符", name.contains(":") || name.contains("/"))

        // 带冒号的 id 必须被清洗（`last:xxx` 这种前缀真的存在）
        val dirty = recipeCardFileName("last:daylight", 1L)
        assertFalse("冒号没被清洗：$dirty", dirty.contains(":"))
        // 自检：清洗函数对干净输入不该改动
        assertEquals("daylight", sanitizeForFileName("daylight"))
        assertEquals("_", sanitizeForFileName(":"))
        assertEquals("look", sanitizeForFileName(""))
        // 超长 id 必须被截断（文件名上限 255 字节，中文 id + 前缀容易超）
        assertTrue("超长 id 没截断", sanitizeForFileName("a".repeat(200)).length <= 40)
    }

    @Test
    fun cardSizeCycleCoversEveryEntry() {
        var s = RecipeCardSize.PORTRAIT_4x5
        val seen = mutableListOf(s)
        repeat(RecipeCardSize.entries.size - 1) {
            s = s.next()
            seen += s
        }
        assertEquals("循环少走了", RecipeCardSize.entries.size, seen.size)
        assertEquals("循环没覆盖全部", RecipeCardSize.entries.toSet(), seen.toSet())
        assertEquals("没回到起点", RecipeCardSize.PORTRAIT_4x5, s.next())
        // 越界序号归零
        assertEquals(RecipeCardSize.PORTRAIT_4x5, RecipeCardSize.byOrdinal(99))
        assertEquals(RecipeCardSize.PORTRAIT_4x5, RecipeCardSize.byOrdinal(-3))
        // 三档尺寸各不相同（抄错一档会让界面选什么都没变）
        assertEquals(3, RecipeCardSize.entries.map { it.width to it.height }.toSet().size)
    }

    @Test
    fun longSummaryIsTruncatedWithEllipsis() {
        val long = look(summary = "这是一个非常非常长的配方摘要".repeat(6))
        val model = recipeCardModel(long, 0.5f)
        assertTrue("摘要没被截断：长度 ${model.summary.length}", model.summary.length <= 27)
        assertTrue("截断了却没有省略号：${model.summary}", model.summary.endsWith("…"))
        // 自检：短摘要不该被动
        val short = recipeCardModel(look(summary = "短"), 0.5f)
        assertEquals("短摘要被改了", "短", short.summary)
        assertFalse("短摘要被加了省略号", short.summary.endsWith("…"))
    }

    @Test
    fun pixelsLayerRejectsDegenerateInputs() {
        // 数组比画布小：不许越界
        val layout = recipeCardLayout(64, 64)
        assertFalse(
            "小数组居然被接受了",
            RecipeCardPixels.paint(IntArray(10), layout, RecipeCardInk(), 0xFFFFFFFF.toInt()),
        )
        // 空矩形：不画、不崩
        assertFalse(RecipeCardPixels.fillRect(IntArray(64), 8, 8, RectI(0, 0, 0, 0), 0xFF000000.toInt()))
        assertFalse(RecipeCardPixels.rectOutline(IntArray(64), 8, 8, RectI(0, 0, 0, 0), 0xFF000000.toInt()))
        // 自检：正常矩形必须 true（否则"永远 false"也会绿）
        assertTrue(RecipeCardPixels.fillRect(IntArray(64), 8, 8, RectI(1, 1, 3, 3), 0xFF000000.toInt()))
        // 越界的矩形要被裁剪而不是写坏别的行
        val px = IntArray(64)
        assertTrue(RecipeCardPixels.fillRect(px, 8, 8, RectI(-5, -5, 3, 3), 0xFF00FF00.toInt()))
        assertEquals("写了画布外的位置", 0xFF00FF00.toInt(), px[0])
        assertEquals("越界写进了别的行", 0, px[8 * 4])
    }
}
