package com.suipai.app.data.ai

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Key 的存放必须在真机/模拟器上测：AndroidKeyStore 在 JVM 单测里根本不存在，
 * 而它偏偏有一堆「编译期全绿、运行期直接崩」的规矩。
 *
 * 第一条测试就是这种规矩的回归守卫：2026-09-21 的初版用调用方自己随机的 IV 去 init 加密，
 * 平台拒绝——`InvalidAlgorithmParameterException: Caller-provided IV not permitted`，
 * 用户点「存入本机」整个 app 当场死掉。JVM 侧任何断言都看不见这件事。
 */
@RunWith(AndroidJUnit4::class)
class AiKeyStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store(): AiKeyStore = AiKeyStore(context).apply { clear() }

    private fun sealed(): String? = context
        .getSharedPreferences("suipai.ai", Context.MODE_PRIVATE)
        .getString("ai_key_sealed", null)

    @Test
    fun savedKeyRoundTripsThroughTheKeystore() {
        val s = store()
        val key = "fixture-key-not-a-real-credential-012345"
        assertTrue("存入被拒绝：这台设备的密钥库不可用", s.save(key))
        assertTrue(s.hasKey())
        assertEquals(key, s.read())
        s.clear()
    }

    @Test
    fun blankKeyIsNeverStored() {
        val s = store()
        assertFalse(s.save("   "))
        assertFalse(s.hasKey())
        assertNull(s.read())
    }

    @Test
    fun clearingRemovesBothTheBlobAndTheAbilityToRead() {
        val s = store()
        assertTrue(s.save("fixture-key-material-abcdef"))
        s.clear()
        assertFalse(s.hasKey())
        assertNull(s.read())
    }

    /**
     * 改一个字节就必须解不开，而不是解出一串乱码当 key 用；并且解不开要顺手清空。
     * 这条守的是 GCM 的认证标签真的在起作用：哪天有人把 transform 换成 AES/CBC/NoPadding，
     * 篡改会安静地返回垃圾，用户看到的是「Key 不对」而不是「存储被动过」。
     *
     * **一定要改「解码之后」的字节，而不是 base64 文本的最后一个字符。**
     * 旧写法改的是文本末尾：那串的长度带 `==` padding，改完直接成了非法 base64，
     * 于是走的是「decode 失败」那条 `clear()` —— 2026-09-21 把 `read()` 里的自愈 `clear()`
     * 删掉重跑，6 项全绿，证明当时这条测的是 base64 校验而不是 GCM 认证。
     * 现在改的是解出来的最后一个标签字节，base64 仍然合法，能红下来才说明自愈真的在被测。
     */
    @Test
    fun tamperedBlobFailsAuthenticationAndSelfHeals() {
        val s = store()
        assertTrue(s.save("fixture-key-material-abcdef"))
        val packed = sealed()
        assertTrue("密文没落在预期的键上", packed != null && packed.length > 20)
        val raw = Base64.decode(packed!!, Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw.last().toInt() xor 0x11).toByte()
        context.getSharedPreferences("suipai.ai", Context.MODE_PRIVATE)
            .edit()
            .putString("ai_key_sealed", Base64.encodeToString(raw, Base64.NO_WRAP))
            .apply()

        assertNull("被改过的密文竟然还解出了东西", s.read())
        assertFalse("解密失败后没有自我清空，下次进来还会重试", s.hasKey())
    }

    /**
     * 落盘的东西不能一眼看出是明文——**解码之后也不能**。
     *
     * 只查 base64 文本里有没有那串 key 是不够的：「懒得加密、直接 Base64 一下」这种改法
     * 恰好能骗过它，而那是这条隐私声明最现实的死法。所以把存下来的 blob 解码再查一遍。
     */
    @Test
    fun storedFormIsNotThePlaintext() {
        val s = store()
        val key = "super-secret-key-material-0123456789ab"
        assertTrue(s.save(key))
        val raw = sealed().orEmpty()
        assertTrue("存储是空的，那这条断言什么都没测", raw.isNotEmpty())
        assertTrue("明文出现在了存储里", key !in raw)
        val decoded = String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        assertTrue("只是 base64 了一下，没有真的加密", key !in decoded)
        s.clear()
    }

    /**
     * 实测过的失败：自动化往输入框里打 49 个字符时内容不对，应用照单收下，
     * 之后每次「看这张图」都是 401，而界面当时只会说「Key 不对或已失效」——把我们的错报成用户的错。
     *
     * 这里只承诺**形状**层面的拒绝：空、短到不可能是 Key、混进空白、混进全角。
     * 「49 个字符但内容错」这种本地判不出来，要判就得猜各家的格式，
     * 而猜格式的下场是给所有非该格式的用户加一道墙——所以那条路不走，
     * 改由失败文案把最可能的原因讲清楚（见 `AiSheet.explain` 的 AUTH 分支）。
     *
     * 下面用的是**假 Key**（与真 Key 同形状同长度），仓库里永远不放真凭据。
     */
    @Test
    fun mangledKeyIsRefusedBeforeItReachesTheKeystore() {
        val s = store()
        assertFalse("中间混了空格的 Key 不该被收下", s.save("0123456789abcdef 0123456789abcdef.ABCDEF012345"))
        assertFalse("短到不可能是 Key 的输入不该被收下", s.save("0123456789ab"))
        assertFalse("全角字符的 Key 不该被收下", s.save("０１２３４５６７８９ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏ"))
        assertFalse("拒绝了却没有清空，说明 clear 没生效", s.hasKey())
        assertTrue("同形状的正常 Key 被误拒了", s.save("0123456789abcdef0123456789abcdef.ABCDEF0123456789"))
        s.clear()
    }
}
