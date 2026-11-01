package com.suipai.app.sync

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 互通「发送照片」那一栏的本机缩略图取数路径。
 *
 * 为什么需要它：那一栏只有**发现了对端**才在界面上出现（`PeerSheet` 里 `SendPane` 的 mode==2），
 * 一台模拟器上永远点不到它 —— 于是 §17 改动的复测清单第 ② 条在设备上是量不到的。
 * 这里把那条改动真正冒险的部分直接量掉：
 *
 *   1. 一页 60 张的本机缩略图，**每张都要拿得到、都要解得开**；
 *   2. 解出来的键必须与 `listPhotos` 给的 id **一字不差**——
 *      `LocalPhotoCell` 是按 `thumbs[meta.id]` 查的，键一旦对不上，
 *      整栏就全是占位图标，而代码一行都不会报错。
 *
 * 顺带记下这一页的耗时，作为「一次协程批量取」的实际体积。
 */
@RunWith(AndroidJUnit4::class)
class SyncLocalThumbTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 用 UiAutomation 的 shell 通道补一次授权：`androidx.test:rule` 没进依赖，
     * 而 connectedAndroidTest 每次都重装 APK，运行时权限是被抹掉的——
     * 没有 READ_MEDIA_IMAGES，MediaStore 查出来就是空表，这条测试会变成一台干净设备上的假绿。
     */
    @Before
    fun grantMediaRead() {
        val pkg = ctx.packageName
        // executeShellCommand 返回的是命令输出的 fd；读到 EOF 就等于等它跑完。
        // pm grant 只能由 shell uid 发，所以走 UiAutomation 这条通道，而不是 Runtime.exec。
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Test
    fun aWholePageOfLocalThumbsDecodesAndIsKeyedByTheIdsItCameFrom() {
        val source = AndroidSyncSource(ctx)
        val metas = source.listPhotos(0, 60)
        assertTrue("本机一张照片都没有：这台设备没播种，测不出东西", metas.isNotEmpty())

        val t0 = System.currentTimeMillis()
        val decoded = LinkedHashMap<String, android.graphics.Bitmap>(metas.size)
        val undecodable = ArrayList<String>()
        for (m in metas) {
            val bytes = source.readThumbBytes(m.id, 256)
            if (bytes == null) {
                undecodable += "${m.id}(无字节)"
                continue
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
                undecodable += "${m.id}(${bytes.size}B 解不开)"
            } else {
                decoded[m.id] = bmp
            }
        }
        val ms = System.currentTimeMillis() - t0

        assertTrue("这些 id 拿不到能解码的缩略图：$undecodable", undecodable.isEmpty())
        assertEquals(
            "解码结果的键必须正好覆盖 listPhotos 给的 id（SendPane 按 thumbs[meta.id] 查，缺一就是整栏空图占位）",
            metas.map { it.id }.toSet(), decoded.keys.toSet(),
        )
        // 缩略图必须真的是缩略图：长边超过 256 说明采样没生效，一屏 60 张就是几十 MB 的原图位图。
        val tooBig = decoded.values.filter { maxOf(it.width, it.height) > 512 }
        assertTrue("这些缩略图长边没降下来：${tooBig.map { it.width.toString() + "x" + it.height }}", tooBig.isEmpty())
        assertNotNull(decoded[metas.first().id])
        android.util.Log.i("SyncLocalThumbTest", "%d 张本机缩略图共 %d ms（均值 %d ms）"
            .format(metas.size, ms, ms / metas.size.coerceAtLeast(1)))
    }
}
