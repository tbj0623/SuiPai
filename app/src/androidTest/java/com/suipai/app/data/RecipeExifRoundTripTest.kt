package com.suipai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suipai.core.engine.RecipeCodec
import com.suipai.core.engine.YishunRecipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 配方落盘的端到端守卫，跑在设备上：字符码那 8 个字节以前只靠人记，
 * 一旦哪天改成直接写 encode()（少了字符码），Android 这边照样读得回来、
 * iOS 和相册那边就静默失效，所以断言的是**磁盘上的字节**而不是内存里的字符串。
 */
@RunWith(AndroidJUnit4::class)
class RecipeExifRoundTripTest {

    private val recipe = YishunRecipe(
        lookId = "daylight",
        intensity = 0.62f,
        tempShift = -0.11f,
        contrastShift = 0.08f,
        satShift = 0.05f,
        grainMul = 1.25f,
        vignetteMul = 0.9f,
        exportEdge = 2560,
    )

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun probeJpeg(): File {
        val context = context()
        val file = File(context.cacheDir, "recipe-exif-probe.jpg")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return file
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (k in needle.indices) {
                if (haystack[start + k] != needle[k]) continue@outer
            }
            return start
        }
        return -1
    }

    @Test
    fun recipeSurvivesRealExifWriteThenRead() {
        runBlocking {
            val context = context()
            val file = probeJpeg()
            val uri = Uri.fromFile(file)

            RecipeExif.writeRecipe(context, uri, recipe)
            val back = RecipeExif.readRecipe(context, uri)

            assertNotNull("androidx ExifInterface 没能把配方读回来", back)
            assertEquals(recipe, back)
        }
    }

    @Test
    fun userCommentOnDiskCarriesAsciiCharsetCode() {
        runBlocking {
            val file = probeJpeg()
            RecipeExif.writeRecipe(context(), Uri.fromFile(file), recipe)

            val bytes = file.readBytes()
            val markerAt = indexOf(bytes, RecipeCodec.MARKER.encodeToByteArray())
            assertTrue("写出的 JPEG 里找不到配方标记", markerAt >= 0)
            val prefix = bytes.copyOfRange(markerAt - 8, markerAt).map { it.toInt() and 0xff }
            // 'A' 'S' 'C' 'I' 'I' 0 0 0 —— EXIF 2.32 4.6.4 要求的 ASCII 字符码。
            assertEquals(listOf(65, 83, 67, 73, 73, 0, 0, 0), prefix)
        }
    }

    /** 老照片/别的写入方可能不带字符码，这条保证读侧的宽容不会被改回去。 */
    @Test
    fun recipeWithoutCharsetCodeStillReads() {
        runBlocking {
            val context = context()
            val file = probeJpeg()
            val uri = Uri.fromFile(file)
            androidx.exifinterface.media.ExifInterface(file.absolutePath).apply {
                setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT,
                    RecipeCodec.encode(recipe),
                )
                saveAttributes()
            }

            assertEquals(recipe, RecipeExif.readRecipe(context, uri))
        }
    }
}
