package com.suipai.core

import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.RadialMask
import com.suipai.core.engine.RecipeCodec
import com.suipai.core.engine.RecipePro
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves
import com.suipai.core.engine.YishunRecipe
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 配方 v3 的兼容矩阵。
 *
 * 三条底线：
 * 1. **v2 完全读得进**，且 14 个字段的语义一个都没动（老成片里那份配方还能还原）；
 * 2. **v3 往返无损**，包括曲线/分色/蒙版；
 * 3. **v4 与 v0 整条拒绝** —— 拿旧语义去解新 payload 只会静默错色。
 */
class RecipeV3Test {

    private val v2 = YishunRecipe(
        v = 2,
        lookId = "ri_guang",
        intensity = 0.62f,
        tempShift = 0.1f,
        contrastShift = -0.05f,
        satShift = 0.2f,
        shadowShift = 0.03f,
        fadeShift = 0.07f,
        highlightShift = 0.04f,
        softShift = 0.02f,
        grainMul = 1.3f,
        vignetteMul = 0.9f,
        exportEdge = 2048,
    )

    private val curves = ToneCurves(
        master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.35f, 0.28f), CurvePoint(1f, 1f))),
        b = ToneCurve(listOf(CurvePoint(0f, 0.02f), CurvePoint(1f, 0.96f))),
    )

    private val hsl = HslBands(
        orange = HslBand(hue = 0.2f, sat = -0.1f, lum = 0.15f),
        blue = HslBand(sat = 0.3f),
    )

    private val masks = listOf(
        LocalMask(
            RadialMask(cx = 0.4f, cy = 0.55f, rx = 0.25f, ry = 0.3f, feather = 0.6f),
            LocalAdjust(exposure = 0.35f, saturation = -0.2f),
        ),
        LocalMask(
            LinearMask(angle = 25f, start = 0.1f, end = 0.85f),
            LocalAdjust(temperature = 0.4f),
        ),
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64url(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(json.encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    private fun jsonOf(encoded: String): String = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .decode(encoded.removePrefix(RecipeCodec.MARKER))
        .decodeToString()

    // ---- v2 兼容 --------------------------------------------------------------

    @Test
    fun v2StillDecodesWithAllFourteenFields() {
        val decoded = assertNotNull(RecipeCodec.decode(RecipeCodec.encode(v2)))
        // 解码不改写版本号：v2 payload 解出来仍是 v2（写回去也还是 v2），
        // 这样「老配方原样转存」不会在别人升版本时被动变成 v3。
        assertEquals(v2, decoded, "v2 的 13 个字段必须原样带回来")
        // 逐个字段点名，防止某天 copy 的默认值把某一项悄悄吞掉
        assertEquals("ri_guang", decoded.lookId)
        assertEquals(0.62f, decoded.intensity)
        assertEquals(0.1f, decoded.tempShift)
        assertEquals(-0.05f, decoded.contrastShift)
        assertEquals(0.2f, decoded.satShift)
        assertEquals(0.03f, decoded.shadowShift)
        assertEquals(0.07f, decoded.fadeShift)
        assertEquals(0.04f, decoded.highlightShift)
        assertEquals(0.02f, decoded.softShift)
        assertEquals(1.3f, decoded.grainMul)
        assertEquals(0.9f, decoded.vignetteMul)
        assertEquals(2048, decoded.exportEdge)
        // v2 里没有的三个新字段必须是中性，否则老配方会被凭空加上调整
        assertNull(decoded.curve)
        assertNull(decoded.hsl)
        assertNull(decoded.masks)

        // 自检：把 v2 payload 的 v 改成 4，解码必须变红
        val v4Json = "{\"v\":4,\"lookId\":\"ri_guang\",\"intensity\":0.62}"
        assertNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(v4Json)), "自检失败：v4 没被拒绝")
    }

    @Test
    fun v2PayloadWithoutNewKeysProducesIdenticalKnobs() {
        // 老 App 写的 payload 里根本没有 curve/hsl/masks 三个键。
        // ignoreUnknownKeys + encodeDefaults=false 保证它读进来就是三个 null。
        val json = "{\"v\":2,\"lookId\":\"daylight\",\"intensity\":0.5,\"exportEdge\":2048}"
        val decoded = assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(json)))
        assertEquals("daylight", decoded.lookId)
        assertEquals(0.5f, decoded.intensity)
        assertEquals(2048, decoded.exportEdge)
        assertNull(decoded.curve)
        assertNull(decoded.hsl)
        assertNull(decoded.masks)
        // 自检：真正带 curve 的 v3 必须解出非 null
        val withCurve = assertNotNull(RecipeCodec.decode(RecipeCodec.encode(RecipePro.withPro(v2, curves))))
        assertNotNull(withCurve.curve, "自检失败：带曲线的配方解出来是 null")
    }

    // ---- v3 往返 --------------------------------------------------------------

    @Test
    fun v3RoundTripKeepsCurvesHslAndMasks() {
        val v3recipe = RecipePro.withPro(v2, curves, hsl, masks)
        assertEquals(YishunRecipe.CURRENT_VERSION, v3recipe.v)
        val text = RecipeCodec.encode(v3recipe)
        val decoded = assertNotNull(RecipeCodec.decode(text))
        assertEquals(v3recipe, decoded)

        val backCurves = RecipePro.curvesOf(decoded)
        assertEquals(3, backCurves.master?.points?.size)
        assertEquals(0.28f, backCurves.master!!.points[1].y, 1e-6f)
        assertNotNull(backCurves.b)
        assertNull(backCurves.r)

        val backHsl = RecipePro.hslOf(decoded)
        assertEquals(0.2f, backHsl.orange.hue, 1e-6f)
        assertEquals(-0.1f, backHsl.orange.sat, 1e-6f)
        assertEquals(0.3f, backHsl.blue.sat, 1e-6f)
        assertEquals(0f, backHsl.red.sat, 1e-6f)

        val backMasks = RecipePro.masksOf(decoded)
        assertEquals(2, backMasks.size)
        val radial = backMasks[0].shape as RadialMask
        assertEquals(0.4f, radial.cx, 1e-6f)
        assertEquals(0.6f, radial.feather, 1e-6f)
        assertEquals(0.35f, backMasks[0].adjust.exposure, 1e-6f)
        val linear = backMasks[1].shape as LinearMask
        assertEquals(25f, linear.angle, 1e-6f)

        // 自检：同一份 payload 解两次必须相等（不能靠可变单例）
        assertEquals(decoded, RecipeCodec.decode(text))
    }

    @Test
    fun neutralProKeepsRecipeAtV2Shape() {
        // 没有专业算子的配方不该多出键：v2 的形状是 EXIF 长度预算的一部分。
        val plain = RecipePro.withPro(v2)
        assertNull(plain.curve)
        assertNull(plain.hsl)
        assertNull(plain.masks)
        val json = jsonOf(RecipeCodec.encode(plain))
        assertTrue("\"curve\"" !in json, "中性配方多写了 curve 键：$json")
        assertTrue("\"hsl\"" !in json)
        assertTrue("\"masks\"" !in json)
        // 自检：带算子的配方必须写出这三个键
        val rich = jsonOf(RecipeCodec.encode(RecipePro.withPro(v2, curves, hsl, masks)))
        assertTrue("\"curve\"" in rich, "自检失败：带曲线的配方没写 curve 键")
        assertTrue("\"masks\"" in rich)
    }

    @Test
    fun versionIsWrittenAsThree() {
        assertTrue("\"v\":3" in jsonOf(RecipeCodec.encode(v2.copy(v = 3))), "v 必须是当前版本号")
        // 自检：CURRENT_VERSION 被改回 2 时这条会红
        assertEquals(3, YishunRecipe.CURRENT_VERSION)
    }

    // ---- 拒绝 -----------------------------------------------------------------

    @Test
    fun futureAndZeroVersionsAreRejectedWholesale() {
        for (bad in intArrayOf(0, -1, 4, 99)) {
            val json = "{\"v\":$bad,\"lookId\":\"daylight\",\"intensity\":0.5}"
            assertNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(json)), "v=$bad 没被拒绝")
        }
        // 自检：v=1/2/3 必须都能过，否则上面的拒绝是因为整条解析坏了
        for (ok in intArrayOf(1, 2, 3)) {
            val json = "{\"v\":$ok,\"lookId\":\"daylight\",\"intensity\":0.5}"
            assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(json)), "v=$ok 被误拒")
        }
    }

    @Test
    fun corruptedProFieldsDegradeInsteadOfCrashing() {
        // 曲线扁平数组长度是奇数 / 太短：不能抛，只是那一条曲线作废。
        val badCurve = "{\"v\":3,\"lookId\":\"daylight\",\"intensity\":0.5," +
            "\"curve\":{\"master\":[0,0,0.5]}}"
        val r1 = assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(badCurve)))
        assertTrue(RecipePro.curvesOf(r1).isNeutral, "坏曲线应该退化成中性")

        // 未知蒙版 kind：整条丢弃，不猜。
        val badMask = "{\"v\":3,\"lookId\":\"daylight\",\"intensity\":0.5," +
            "\"masks\":[{\"kind\":\"spiral\",\"p\":[0,0,0]},{\"kind\":\"radial\",\"p\":[0.5,0.5,0.2,0.2,0.5]," +
            "\"exposure\":0.3}]}"
        val r2 = assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(badMask)))
        assertEquals(1, RecipePro.masksOf(r2).size, "未知 kind 没被丢弃")

        // HSL 数组太短：退化成全 0（不是越界崩溃）
        val shortHsl = "{\"v\":3,\"lookId\":\"daylight\",\"intensity\":0.5,\"hsl\":{\"bands\":[0.1,0.2]}}"
        val r3 = assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(shortHsl)))
        assertTrue(RecipePro.hslOf(r3).isNeutral, "坏 HSL 应该退化成中性")

        // 自检：这些坏 payload 如果都不返回 null，说明解码层容忍度是对的（拒绝整条反而错）
        assertEquals(3, listOf(r1, r2, r3).count { it.v == 3 })
    }

    @Test
    fun masksAreCappedAtFourOnRead() {
        // 配方是外部输入：写进 10 个蒙版也不能让渲染层照着做 10 遍。
        val many = (0 until 10).map {
            LocalMask(RadialMask(cx = 0.1f * it, cy = 0.5f), LocalAdjust(exposure = 0.1f))
        }
        val recipe = RecipePro.withPro(v2, ToneCurves(), HslBands(), many)
        assertEquals(4, recipe.masks!!.size)
        assertEquals(4, RecipePro.masksOf(recipe).size)
        // 自检：4 个以内不许被削
        assertEquals(2, RecipePro.withPro(v2, ToneCurves(), HslBands(), many.take(2)).masks!!.size)
    }

    @Test
    fun noSeedFieldIsEverWritten() {
        // SYNC.md 的铁律：配方里没有 seed。它一旦被写回来，两端就会各自回读并长出两套颗粒。
        val json = jsonOf(RecipeCodec.encode(RecipePro.withPro(v2, curves, hsl, masks)))
        assertTrue("\"seed\"" !in json, "配方里出现了 seed 键：$json")
        // exportEdge 必须继续记：颗粒按成片长边调校，不记它换台手机就对不上
        assertTrue("\"exportEdge\"" in json, "exportEdge 丢了")
    }
}
