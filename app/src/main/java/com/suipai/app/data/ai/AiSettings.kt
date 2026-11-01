package com.suipai.app.data.ai

import android.content.Context
import com.suipai.core.ai.AiEndpoint

/**
 * AI 的两项设置：总开关与档位。
 *
 * [enabled] 默认 **关**。这不是保守，是这条功能的定义本身：随拍的卖点之一是「照片不出这台设备」，
 * 一旦某个版本把默认值改成开，升级用户什么都没做就开始上传——那才是真的隐私事故。
 * 开关关掉之后 app 不得再产生任何网络请求，这条要在设备上证明（见 ANDROID-VERIFY.md 的 netstats 步骤）。
 */
class AiSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(FIELD_ON, false)
        set(value) {
            prefs.edit().putBoolean(FIELD_ON, value).apply()
        }

    var endpointId: String
        get() = prefs.getString(FIELD_ENDPOINT, AiEndpoint.ALL.first().id) ?: AiEndpoint.ALL.first().id
        set(value) {
            prefs.edit().putString(FIELD_ENDPOINT, value).apply()
        }

    fun endpoint(): AiEndpoint = AiEndpoint.byId(endpointId)

    /** 换档位时把不支持视觉的档位挡掉：照片推荐没图可看就没有意义。 */
    fun visionEndpoint(): AiEndpoint = endpoint().let {
        if (it.vision) it else AiEndpoint.ALL.first { e -> e.vision }
    }

    private companion object {
        /** 与 [AiKeyStore] 同一个文件：都是「AI 这件事的配置」，不要散成两个 xml。 */
        const val PREFS = "suipai.ai"
        const val FIELD_ON = "ai_on"
        const val FIELD_ENDPOINT = "ai_endpoint"
    }
}
