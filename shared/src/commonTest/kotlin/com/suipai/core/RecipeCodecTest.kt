package com.suipai.core

import com.suipai.core.engine.RecipeCodec
import com.suipai.core.engine.YishunRecipe
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeCodecTest {
    private val full = YishunRecipe(
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

    private val nul = String(Character.toChars(0))
    private val tab = String(Character.toChars(9))

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64url(text: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(text.encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    private fun jsonOf(encoded: String): String = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .decode(encoded.removePrefix(RecipeCodec.MARKER))
        .decodeToString()

    @Test
    fun roundTripKeepsAllFields() {
        val text = RecipeCodec.encode(full)
        assertTrue(text.startsWith(RecipeCodec.MARKER))
        assertEquals(full, RecipeCodec.decode(text))
    }

    @Test
    fun everyTunableKnobSurvivesRoundTrip() {
        val d = RecipeCodec.decode(RecipeCodec.encode(full))!!
        assertEquals(full, d)
        assertEquals(2048, d.exportEdge)
    }

    @Test
    fun versionIsAlwaysWritten() {
        // encodeDefaults = false 会把等于默认值的字段省掉。v 一旦被省掉，
        // 读方就只能猜这份 payload 属于哪一版语义，所以它必须显式写出去。
        assertTrue("\"v\":2" in jsonOf(RecipeCodec.encode(full)))
    }

    @Test
    fun decodeRejectsNewerUnknownVersion() {
        // 拿 v2 的语义去解 v4 不会报错，只会静默错色，所以整条拒收。
        // v3 现在是当前版本（曲线/分色/蒙版），必须读得进；v4 及以后一律拒绝。
        val json = "{\"v\":4,\"lookId\":\"daylight\",\"intensity\":0.5,\"brandNewKnob\":1}"
        assertNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(json)))
    }

    @Test
    fun decodeAcceptsLegacyPayloadWithoutVersion() {
        // 已经发出去的成片里就是没有 v 的，必须继续读得回来；
        // 顺带证明老 payload 里的 seed 键被安全忽略（它现在是死字段）。
        val json = "{\"lookId\":\"daylight\",\"intensity\":0.5,\"seed\":987654}"
        val recipe = assertNotNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url(json)))
        assertEquals(YishunRecipe.CURRENT_VERSION, recipe.v)
        assertEquals("daylight", recipe.lookId)
    }

    @Test
    fun decodeStopsAtNulTerminator() {
        // 老实现的终止符集只有「空白 + 引号」，而 NUL 不算空白：
        // 任何在 payload 后补零的 EXIF 写入方都会把垃圾拼进 base64，整条配方解不出来。
        val encoded = RecipeCodec.encode(full)
        assertEquals(full, RecipeCodec.decode(encoded + nul + nul))
    }

    @Test
    fun decodeIgnoresTrailingNonBase64Junk() {
        val encoded = RecipeCodec.encode(full)
        assertEquals(full, RecipeCodec.decode(encoded + tab + "尾部备注"))
        assertEquals(full, RecipeCodec.decode(encoded + "|not-base64"))
    }

    @Test
    fun decodeAcceptsPaddedInput() {
        val encoded = RecipeCodec.encode(full).removePrefix(RecipeCodec.MARKER)
        // 部分 EXIF 写入方会补齐 base64 padding，解码需兼容合法长度的 '='
        val pad = (4 - encoded.length % 4) % 4
        assertEquals(full, RecipeCodec.decode(RecipeCodec.MARKER + encoded + "=".repeat(pad)))
    }

    @Test
    fun decodeFindsMarkerInsideExifUserComment() {
        val prefix = "charset=" + String(Character.toChars(34)) + "9" + String(Character.toChars(34))
        val text = prefix + " 一些相机备注信息 " + RecipeCodec.encode(full) + " 尾部"
        assertEquals(full, RecipeCodec.decode(text))
    }

    @Test
    fun exifValueCarriesAsciiCharsetCode() {
        // EXIF 2.32 §4.6.4：UserComment 必须以 8 字节字符码开头。androidx 不补这个码，
        // 而它自己读的时候又从 offset 0 宽容解析，所以漏了码只有我们读得回来 ——
        // iOS ImageIO / exiftool / 别的相册会把前 8 字节当字符码吃掉，配方跨端失效。
        val forExif = RecipeCodec.encodeForExif(full)
        val code = forExif.take(8).map { it.code }
        assertEquals(listOf(65, 83, 67, 73, 73, 0, 0, 0), code, "前 8 字节必须是 ASCII 字符码")
        assertTrue(forExif.substring(8).startsWith(RecipeCodec.MARKER))
    }

    @Test
    fun decodeGarbageReturnsNull() {
        assertNull(RecipeCodec.decode(""))
        assertNull(RecipeCodec.decode("no marker here"))
        assertNull(RecipeCodec.decode(RecipeCodec.MARKER))
        assertNull(RecipeCodec.decode(RecipeCodec.MARKER + "!!!not-base64!!!"))
        assertNull(RecipeCodec.decode(RecipeCodec.MARKER + b64url("{}")))
    }

    @Test
    fun encodeOutputIsExifSafe() {
        val text = RecipeCodec.encode(full).removePrefix(RecipeCodec.MARKER)
        // Base64Url 无 padding：不含 + / = 与空白，可安全放入 EXIF UserComment
        assertTrue(text.none { it == '+' || it == '/' || it == '=' || it.isWhitespace() })
    }
}
