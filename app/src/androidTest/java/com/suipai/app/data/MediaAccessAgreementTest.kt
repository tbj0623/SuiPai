package com.suipai.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suipai.app.ui.hasMediaAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 授权状态与"读得到读不到"必须对得上。
 *
 * 起因：装机想注入一次"媒体库读不到"来验 §33/§34 那条可空改动，结果两种注入都没打到那条路 ——
 * `appops … ignore` 之后进程里再读还是 85 行（appop 不参与已经在跑的进程），
 * 而 `pm revoke READ_MEDIA_IMAGES` 之后顶栏变成 73 行：这台 AVD 还留着
 * `READ_MEDIA_VISUAL_USER_SELECTED`，于是 `hasMediaAccess` 依旧为真、画廊照常渲染。
 * 这本身没错（系统就是这么答话的），但它说明界面上"你的照片就这些"这句话的可信度
 * 全押在 **`hasMediaAccess` 与真实可读性一致** 上。这条测试就把这两件事钉在一起：
 *
 *   1. 两个权限都撤掉之后，`hasMediaAccess` 必须是 false —— 否则用户会穿过授权门
 *      看到一个"空相册"，那就是撒谎；
 *   2. 无论有没有权限，`readLibrary()` 都不许把异常抛到调用点（没读到要还 null），
 *      并且不许抛出去 —— 原来这条路径会一路顶到 `LaunchedEffect`；
 *   3. 空集合不等于失败：`loadByIds(emptySet())` 要回空表而不是 null。
 *
 * 测完必须把权限原样还回去，否则下一支探针就在没授权的设备上跑（这条债本轮已经还过一次）。
 */
@RunWith(AndroidJUnit4::class)
class MediaAccessAgreementTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        val text = stream.bufferedReader().readText()
        stream.close()
        pfd.close()
        return text.trim()
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun allMediaPerms(): List<String> = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    @Test
    fun revokedMeansNoAccessAndNoThrow() {
        val had = allMediaPerms().filter { granted(it) }
        val pkg = ctx.packageName
        try {
            for (p in allMediaPerms()) shell("pm revoke $pkg $p")
            assertTrue(
                "两个媒体权限都撤了，hasMediaAccess 还说有（用户会穿过授权门看到一个空相册）",
                !hasMediaAccess(ctx),
            )
            // 没权限时这一趟要么读到东西、要么还 null，就是不许抛
            val read: Result<MediaRepository.LibraryRead> = try {
                Result.success(runBlocking { MediaRepository(ctx).readLibrary() })
            } catch (e: Throwable) {
                Result.failure(e)
            }
            assertTrue("readLibrary 把异常抛到调用点了：${read.exceptionOrNull()}", read.isSuccess)
            val lib = read.getOrThrow()
            // 在这台 AVD 上 MediaProvider 对无权限的答话是"空"，两种结果都合法；
            // 不合法的是"读到 null 却没人说"——那由 §34 的界面判据管。
            assertNotNull("readLibrary 返回的库对象本身不许为空", lib)
            val byIds = runBlocking { MediaRepository(ctx).loadByIds(emptySet()) }
            assertEquals("空 id 集合是真的空，不是没读到", emptyList<Any>(), byIds)
        } finally {
            for (p in had) shell("pm grant $pkg $p")
        }
        // 还原要真的生效：下一支探针/用户都靠这个状态
        assertEquals(
            "撤销过的权限没全数还回去：" + had.joinToString(),
            had.count { granted(it) },
            had.size,
        )
    }

    /** 只为让编译期确认 API 名没写错：撤销/授予都走 `pm`，这里断言权限确实申请过。 */
    @Test
    fun mediaPermissionsAreDeclared() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val asked = (info.requestedPermissions?.toList() ?: emptyList())
        assertTrue("清单里没声明 READ_MEDIA_IMAGES：" + asked,
            asked.contains(Manifest.permission.READ_MEDIA_IMAGES))
    }
}
