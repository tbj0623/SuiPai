package com.suipai.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 删除这条链的真实边界：**谁存的这张照片**。
 *
 * 立这条测试的起因：`delete` 原来是 `runCatching { resolver.delete(uri) }.getOrDefault(false)`，
 * 而 Android 11 起删别人存的东西会抛 `RecoverableSecurityException` ——
 * 于是一台设备上 72 张里有 23 张（adb push 进来的、owner 为 NULL 的）永远删不掉，
 * 界面却照样把进度条跑到 N/N，一句错都不报。
 *
 * 这里不去点界面（系统授权框要用户亲手点，自动化点它等于自欺欺人），
 * 只量三件可判定的事：
 *   1. 本应用自己存的照片 —— 直接删得掉（`DELETED`），这条改完不能退化；
 *   2. 外来照片 —— 必须是 `NEEDS_CONSENT`（不是 `FAILED`，也不是 `DELETED`）；
 *   3. 系统那条"让用户点允许"的入口在这台机器上确实拿得到（`deleteRequestFor != null`）。
 */
@RunWith(AndroidJUnit4::class)
class DeleteOwnershipTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 没有 READ_MEDIA_IMAGES 的话，本应用查 MediaStore 得到的是**空游标**（不报错），
     * 于是"找一张外来照片"永远找不到、这一整条守卫会绿得毫无意义。
     * connectedDebugAndroidTest 每次重装都会抹掉运行时权限，所以在这里补一次。
     */
    @Before
    fun grantMediaRead() {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${ctx.packageName} android.permission.READ_MEDIA_IMAGES")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun insertOwnJpeg(tag: String): Uri {
        val png = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 92, this)
        }.toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SuiPai_deltest_$tag.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("插不进去，这台设备测不了", uri)
        ctx.contentResolver.openOutputStream(uri!!)?.use { it.write(png) }
        return uri
    }

    private fun idOf(uri: Uri): Long = ContentUris.parseId(uri)

    @Test
    fun ownPhotosDeleteDirectly() {
        val repo = MediaRepository(ctx)
        val uri = insertOwnJpeg("own")
        val outcome = kotlinx.coroutines.runBlocking { repo.deleteOne(uri) }
        assertEquals("本应用自己存的照片应当能直接删掉", DeleteOutcome.DELETED, outcome)
        val after = kotlinx.coroutines.runBlocking { repo.stillPresent(listOf(idOf(uri))) }
        assertNotNull("查询没探到结果 —— 这条不能算\"删掉了\"（空集与探不到是两件事）", after)
        assertEquals("删完不该还在库里", emptySet<Long>(), after)
    }

    /**
     * 外来照片这一侧。
     *
     * `OWNER_PACKAGE_NAME` 这一列本应用**读不到**（不是自己的行就给你 null / 干脆整条查询失败），
     * 所以这里不靠它认外来货，改用命名事实：本应用存出去的文件一律叫
     * `SuiPai_...`（相机原片是 `SuiPai_<时间戳>.jpg`），而 `seed_*` / `IMG_*` / `DSC_*`
     * 是 adb push 进来的 —— 名字就是所有权证据。
     *
     * 断言两件事，都不需要真删掉别人的照片：结果**不许是 DELETED**（那是越权），
     * 而且必须存在"去问用户要授权"这条路（API 30+ 的 IntentSender，Android 10 没这个入口）。
     */
    @Test
    fun foreignPhotosNeedConsentAndTheConsentEntryExists() {
        val rows = ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val i = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val n = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val out = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) out += c.getLong(i) to c.getString(n)
            out
        } ?: emptyList()
        val foreign = rows.filter { !it.second.startsWith("SuiPai_") }
        if (foreign.isEmpty()) {
            assertTrue("这台设备上没有非 SuiPai_ 命名的照片（行数 ${rows.size}），" +
                "NEEDS_CONSENT 这条路没被验证过 —— 换台有外来照片的设备再量", false)
        }
        val (id, name) = foreign.first()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val repo = MediaRepository(ctx)
        val outcome = kotlinx.coroutines.runBlocking { repo.deleteOne(uri) }
        assertTrue(
            "外来照片 $name(id=$id) 的删除结果成了 $outcome：DELETED 意味着本应用能绕过用户授权删别人的东西",
            outcome != DeleteOutcome.DELETED,
        )
        // 30 不是 29：`createDeleteRequest` 是 Android 11 才有的（原本按 29 写，
        // `:app:lintRelease` 的 NewApi 把那一行抓出来了）。
        if (Build.VERSION.SDK_INT >= 30) {
            val direct = runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, listOf(uri)) }
            assertTrue(
                "createDeleteRequest 对外来照片 $name 抛了：" + (direct.exceptionOrNull()?.toString() ?: "OK") +
                    "，outcome=" + outcome,
                direct.isSuccess && repo.deleteRequestFor(listOf(uri)) != null,
            )
        }
        // 没授权就不该真的少一张（这条同时钉住"我们没偷偷删掉它"）
        val still = kotlinx.coroutines.runBlocking { repo.stillPresent(listOf(id)) }
        assertNotNull("没探到结果，不能顺着当成\"还在库里\"", still)
        assertTrue("没走用户授权，$name 却从库里没了", still!!.contains(id))
    }

    @Test
    fun stillPresentTellsRealFromGone() {
        val repo = MediaRepository(ctx)
        val uri = insertOwnJpeg("probe")
        val id = idOf(uri)
        val bogus = 9_999_999_999_999L
        val found = kotlinx.coroutines.runBlocking { repo.stillPresent(listOf(id, bogus)) }
        assertNotNull("查询根本没探到结果", found)
        assertTrue("刚存的这张该在库里", id in found!!)
        assertTrue("不存在的 id 不该被报成还在", bogus !in found)
        kotlinx.coroutines.runBlocking { repo.deleteOne(uri) }
    }

    /** 老照片解码通路别被这次改动带坏（顺手钉一下）。 */
    @Test
    fun insertedPhotoIsReadable() {
        val uri = insertOwnJpeg("read")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertNotNull(bytes)
        assertTrue("存进去的 JPEG 解不出来", BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size) != null)
        kotlinx.coroutines.runBlocking { MediaRepository(ctx).deleteOne(uri) }
    }
}
