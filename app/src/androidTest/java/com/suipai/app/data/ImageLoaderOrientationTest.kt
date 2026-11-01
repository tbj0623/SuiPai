package com.suipai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * EXIF orientation 1–8 的落盘→解码回归。
 *
 * 以前只处理了 3/6/9 三种旋转，2/4/5/7/8 这几种**带镜像**的直接当原图用（照片不歪、但左右反了，
 * 例：镜面自拍、部分扫描仪）。这里用一个「左上角一块黑、其余全白」的图案，因为
 * 8 种 orientation 对应的（横竖 + 黑块落在哪个角）组合是**互不相同**的，一次就能把 8 条分支钉死。
 *
 * 期望表由 EXIF 2.32 §4.6.4 的语义独立推出来，不是抄 ImageLoader 的实现：
 * 1 原样 / 2 左右翻 / 3 转 180 / 4 上下翻 / 5 沿主对角线翻 / 6 顺时针转 90 /
 * 7 沿副对角线翻 / 8 逆时针转 90。
 */
@RunWith(AndroidJUnit4::class)
class ImageLoaderOrientationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 黑块在解码结果里的象限：0=左上 1=右上 2=左下 3=右下。 */
    private val expectedQuadrant = intArrayOf(0, 1, 3, 2, 0, 1, 3, 2)

    private fun fixture(orientation: Int): File {
        val w = 64
        val h = 32
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, w / 2f, h / 2f, Paint().apply { color = Color.BLACK })
        }
        val file = File(context.cacheDir, "orient-$orientation.jpg")
        FileOutputStream(file).use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        src.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun darkQuadrant(bmp: Bitmap): Int {
        val counts = IntArray(4)
        for (q in 0 until 4) {
            val x0 = (q % 2) * bmp.width / 2
            val y0 = (q / 2) * bmp.height / 2
            for (y in y0 + 2 until y0 + bmp.height / 2 step 2) {
                for (x in x0 + 2 until x0 + bmp.width / 2 step 2) {
                    if (((bmp.getPixel(x, y) shr 16) and 0xFF) < 96) counts[q]++
                }
            }
        }
        val best = counts.indices.maxBy { counts[it] }
        val second = counts.filterIndexed { i, _ -> i != best }.maxOrNull() ?: 0
        // 黑块只占 1/4 面积；如果两个象限都"差不多黑"，说明解出来的图被整体弄歪了，
        // 那时候要报的是这条，而不是含糊地报象限错。
        assertTrue("黑块不集中：${counts.joinToString()}", counts[best] > second * 4)
        return best
    }

    @Test
    fun allEightExifOrientationsAreAppliedOnce() {
        runBlocking {
            for (orientation in 1..8) {
                val file = fixture(orientation)
                val (bmp, _) = ImageLoader.decode(context, Uri.fromFile(file), maxEdge = 128)
                val label = "orientation $orientation"
                // 5–8 会交换长短边（存进去是横图，显示是竖图）。
                assertEquals("$label 竖排", orientation >= 5, bmp.width < bmp.height)
                assertEquals("$label 黑块位置", expectedQuadrant[orientation - 1], darkQuadrant(bmp))
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** 没有 orientation 标签（截图/网络图常见）时不该被转走。 */
    @Test
    fun missingOrientationKeepsStoredPixels() {
        runBlocking {
            val file = File(context.cacheDir, "orient-none.jpg")
            val src = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            Canvas(src).apply {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, 32f, 16f, Paint().apply { color = Color.BLACK })
            }
            FileOutputStream(file).use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            src.recycle()

            val (bmp, _) = ImageLoader.decode(context, Uri.fromFile(file), maxEdge = 128)
            assertEquals(64, bmp.width)
            assertEquals(32, bmp.height)
            assertEquals(0, darkQuadrant(bmp))
            bmp.recycle()
        }
    }
}
