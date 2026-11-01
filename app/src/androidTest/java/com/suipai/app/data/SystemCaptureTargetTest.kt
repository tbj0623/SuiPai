package com.suipai.app.data

import android.content.Context
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统相机那条路的落点必须**当场推得出来**，不能靠一个组合状态把它带回来。
 *
 * 起因：`systemCaptureUri` 原来是普通 `remember`，而 Manifest 只声明了
 * `orientation|screenSize|keyboardHidden` —— 跳外部相机那几分钟里任何一次配置变化
 * （分屏、字级、深色）都会重建 MainActivity 并把它清空，于是回调里
 * `if (ok && uri != null)` 不成立：拍完那张静静躺在 `cacheDir/camera/system.jpg`，
 * 屏幕一句都没有，也不崩，所以验证套件只会记成"没问题"。
 *
 * 现在的改法是在回调里现推 uri。这条测试钉的就是"现推"这一步真的成立：
 * 路径必须在 `res/xml/file_paths.xml` 里声明过（否则 `getUriForFile` 直接抛
 * `IllegalArgumentException`，那会把静默 bug 换成崩溃），而且推出来的 uri 真的打得开。
 */
@RunWith(AndroidJUnit4::class)
class SystemCaptureTargetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theRecomputedUriResolvesAndOpens() {
        val cache = java.io.File(context.cacheDir, "camera")
        cache.mkdirs()
        val file = java.io.File(cache, "system.jpg")
        // 真写一张进去：openInputStream 走的是 ContentProvider，光比对 uri 字符串证明不了它能用。
        file.writeBytes(
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(-16744192) }
                .let { b -> java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() },
        )
        val uri = com.suipai.app.systemCaptureTarget(context)
        assertNotNull("现推 uri 返回了 null —— FileProvider 的 cache-path 没声明或对不上", uri)
        context.contentResolver.openInputStream(uri!!).use { input ->
            assertNotNull("uri 推出来了但打不开", input)
            assertTrue("相机落点是空的", input!!.readBytes().isNotEmpty())
        }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertNotNull("查询 uri 元数据失败", name)
        file.delete()
    }
}
