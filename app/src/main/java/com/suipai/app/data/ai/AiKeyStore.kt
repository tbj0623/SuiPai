package com.suipai.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.suipai.core.ai.AiKeyShape
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用户自带的 API Key 的存放处。
 *
 * 三条约束决定了这个类的形状：
 * 1) **不能进 SharedPreferences 明文**——那等于把 key 交给任何一台做了 adb backup 的机器；
 * 2) **不能引 androidx.security:security-crypto**——它已经停止维护，而且会拖进 Tink 一整条
 *    依赖链，直接推翻随拍「依赖只有 androidx / Kotlin / Coil」的数据安全申报；
 * 3) 平台自带的 AndroidKeyStore 就够：主密钥**不可导出**，明文永远不落盘。
 *
 * 做法：KeyStore 里一把 AES/GCM 主密钥（256 位、不可导出、无需锁屏认证），
 * 每次写入都用新随机 IV 加密，落盘的是 `base64(iv ‖ 密文 ‖ tag)`。
 * 换机/恢复出厂会让主密钥消失，这时解密必然失败——直接把存的那条清掉，
 * 让用户重贴一次，而不是弹一个看不懂的密码学错误。
 */
class AiKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val master: SecretKey? get() = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ks.getKey(ALIAS, null) as? SecretKey
    }.getOrNull()

    /** 有没有存过 key。**不**尝试解密，所以可以在关着总开关时安全地问。 */
    fun hasKey(): Boolean = prefs.contains(KEY_FIELD)

    /**
     * 加密并落盘；返回 false 表示没存成（密钥库不可用、空白 key 等），界面要能说出来。
     *
     * **IV 必须由平台生成**：AndroidKeyStore 的 GCM 不接受调用方给的 IV，
     * 用 `GCMParameterSpec(128, 自己随机的 iv)` 去 init 加密会直接抛
     * `InvalidAlgorithmParameterException: Caller-provided IV not permitted`——
     * 2026-09-21 在 suipai_r4 上装 release 包点「存入本机」时实测崩在 main 线程。
     * 正确做法是加密后从 [Cipher.iv] 取回平台用的 IV，与密文一起存；只有解密时才把 IV 传回去。
     */
    fun save(plain: String): Boolean {
        val trimmed = plain.trim()
        // 界面上已经先判过一次，这里是最后一道：别的入口（将来的设置页、恢复流程）
        // 也不该能把一个明显不完整的串写进密钥库，然后让用户去猜为什么一直 401。
        if (AiKeyShape.problem(trimmed) != null) {
            clear()
            return false
        }
        val key = master ?: generate() ?: return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = cipher.iv
            // 平台哪天换了 IV 长度，read() 里按固定偏移切分就会切错：宁可拒绝写入，
            // 也不能存一条永远解不开、又每次都静默清空的记录。
            if (iv.size != IV_BYTES) return@runCatching false
            val sealed = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_FIELD, Base64.encodeToString(iv + sealed, Base64.NO_WRAP))
                .apply()
            true
        }.getOrDefault(false)
    }

    /**
     * 取出 key。返回 null 的三种情况：没存过、主密钥不在了（换机/重置）、密文被改过。
     * 后两种会顺手清掉存条，避免每次进界面都重试一遍注定失败的解密。
     */
    fun read(): String? {
        val packed = prefs.getString(KEY_FIELD, null) ?: return null
        val decoded = runCatching { Base64.decode(packed, Base64.NO_WRAP) }.getOrNull()
        if (decoded == null || decoded.size <= IV_BYTES + TAG_BITS / 8) {
            clear()
            return null
        }
        val key = master ?: run { clear(); return null }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, decoded, 0, IV_BYTES),
                )
            }
            String(
                cipher.doFinal(decoded, IV_BYTES, decoded.size - IV_BYTES),
                Charsets.UTF_8,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_FIELD).apply()
    }

    private fun generate(): SecretKey? = runCatching {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                // 不做「必须锁屏解锁后才能解」：那会让用户在车上拍一张、开一次屏就调不出配方。
                .build(),
        )
        generator.generateKey()
    }.getOrNull()

    private companion object {
        const val PREFS = "suipai.ai"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "com.suipai.app.ai.key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_FIELD = "ai_key_sealed"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
