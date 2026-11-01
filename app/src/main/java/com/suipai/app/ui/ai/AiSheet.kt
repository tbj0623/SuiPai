package com.suipai.app.ui.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.suipai.app.data.ai.AiKeyStore
import com.suipai.app.data.ai.AiSettings
import com.suipai.app.data.ai.UrlAiHttp
import com.suipai.app.engine.AiAdvisor
import com.suipai.app.engine.AiOutcome
import com.suipai.app.engine.Shifts
import com.suipai.app.engine.asShifts
import com.suipai.app.engine.recipeWithShifts
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.core.ai.AiEndpoint
import com.suipai.core.ai.AiFailure
import com.suipai.core.ai.AiKeyShape
import com.suipai.core.ai.AiSuggestion
import com.suipai.core.engine.YishunRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「看这张图」抽屉。
 *
 * 单独成文件而不是塞进 YishunScreen：那个 composable 长到 1674 行时被 ART 校验器整类拒过
 * （VerifyError，编译期全绿），少加委托就离悬崖远一点。这里所有状态都关在本文件里，
 * YishunScreen 只多一个 `sheet == 3` 的分支和一个回调。
 */
@Composable
fun AiSheet(
    bitmap: Bitmap?,
    exportEdge: Int,
    onApply: (YishunRecipe) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val settings = remember { AiSettings(context) }
    val keyStore = remember { AiKeyStore(context) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var endpointId by remember { mutableStateOf(settings.endpointId) }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<AiSuggestion?>(null) }
    var telemetry by remember { mutableStateOf("") }

    AppSheet(title = "智能配方", tag = "ai_sheet", onDismiss = onDismiss) {
        AppSheetSection("总开关", "关掉之后随拍不再发出任何网络请求。默认是关的。")
        AppChipFlow {
            AppBarCell(
                label = if (enabled) "已开启" else "已关闭",
                on = enabled,
                modifier = Modifier.testTag("ai_switch"),
                onClick = {
                    enabled = !enabled
                    settings.enabled = enabled
                    if (!enabled) {
                        suggestion = null
                        note = "已关闭：不会再出网"
                    }
                },
            )
        }

        AppSheetSection("端点", "只列官方对开发者开放的兼容接口；照片会发给你选的这一家。")
        AppChipFlow {
            // 不显示不支持视觉的档位：这个功能没图就没意义，摆一颗永远点不动的格子
            // 只会让用户以为是自己哪里没设置对（无障碍与自动化也一样：它出现在树里却永远灰着）。
            AiEndpoint.ALL.filter { it.vision }.forEach { e ->
                AppBarCell(
                    label = e.label,
                    on = endpointId == e.id,
                    modifier = Modifier.testTag("ai_endpoint_${e.id}"),
                    onClick = {
                        endpointId = e.id
                        settings.endpointId = e.id
                    },
                )
            }
        }
        // 选完就把这一家的取舍写在眼前：这个功能唯一的真取舍是「照片多转一手」换「推荐更贴画面」，
        // 而它是用户自己的决定，不该由默认档位替他做完。数字来自 20 张真照片的 A/B（同一配置重跑
        // 会差一张，所以是区间），措辞由 commonMain 的 [AiEndpoint.pickConflictsNote] 出，便于测。
        // 没量过的档位就明说没量过，不借别的档的数字。
        val picked = AiEndpoint.byId(endpointId)
        Text(
            (if (picked.aggregator) "照片会经它再转给一家你说不出名字的模型方。"
            else "模型厂商就是接口厂商，照片不多转一手。") +
                (picked.pickConflictsNote ?: "这一档我们还没量过。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        AppSheetSection("你的 API Key", "只存在本机：Android 密钥库包裹后写入，明文不落盘，也不显示回屏上。")
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("ai_key_field"),
            label = {
                // 掩码框里看不见自己贴了什么，所以把字符数报出来：
                // 设备实测过一次「自动化粘贴漏了字符 → 存下错的 Key → 一路 401」，
                // 长度是本地唯一能免费拿到的自检信号（智谱 49、Agnes 51）。
                Text(
                    (if (hasKey) "粘贴新的 Key（覆盖已存的）" else "粘贴你的 Key") +
                        if (keyDraft.isEmpty()) "" else " · 已输入 ${keyDraft.length} 字符",
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = TextStyle(fontWeight = FontWeight.Normal),
        )
        AppChipFlow {
            AppBarPrimary(
                label = "存入本机",
                enabled = keyDraft.isNotBlank(),
                modifier = Modifier.testTag("ai_key_save"),
                onClick = {
                    val draft = keyDraft
                    // 先在本地判形状：存进一个注定 401 的 Key，用户看到的是一句像在怪他的「Key 不对」，
                    // 而且要等一次网络往返才知道。少发一次请求，也少一次误导。
                    val shape = AiKeyShape.problem(draft)
                    if (shape != null) {
                        note = shape
                    } else {
                        // 密钥库第一次用要生成主密钥（跨进程 + 可能的用户锁屏检查），
                        // 留在主线程上就是一次可感知的卡顿，实测过同类调用能把动画抽停。
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { keyStore.save(draft) }
                            if (saved) keyDraft = ""
                            hasKey = keyStore.hasKey()
                            note = when {
                                saved -> "Key 已存入密钥库"
                                else -> "存入失败：这台设备的密钥库不可用"
                            }
                        }
                    }
                },
            )
            // 只在用户点这颗格子时才读剪贴板：进界面就读会被系统弹「已读取剪贴板」提示，
            // 也和随拍「不在后台采集任何东西」的说法打架。
            AppBarCell(
                label = "从剪贴板粘贴",
                on = false,
                modifier = Modifier.testTag("ai_key_paste"),
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty().trim()
                    if (clip.isEmpty()) {
                        note = "剪贴板是空的"
                    } else {
                        keyDraft = clip
                        note = "已取入 ${clip.length} 个字符，确认无误后按「存入本机」"
                    }
                },
            )
            AppBarCell(
                label = "清除",
                on = false,
                enabled = hasKey,
                modifier = Modifier.testTag("ai_key_clear"),
                onClick = {
                    keyStore.clear()
                    hasKey = false
                    note = "已清除 Key"
                },
            )
            Text(
                if (hasKey) "已存 Key" else "还没有 Key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        AppSheetSection("跑一次", "上传的只有：长边 ${AiAdvisor.SEND_EDGE} 像素的缩略 JPEG 与一段提问文字。原图、EXIF、位置都不出这台设备。")
        AppChipFlow {
            AppBarPrimary(
                label = if (busy) "正在看" else "看这张图",
                enabled = !busy && enabled && hasKey && bitmap != null,
                modifier = Modifier.testTag("ai_run"),
                onClick = {
                    val bmp = bitmap
                    when {
                        bmp == null -> note = "照片还没加载好"
                        !enabled -> note = "先打开总开关"
                        !hasKey -> note = "先粘贴你的 Key"
                        else -> {
                            busy = true
                            note = null
                            suggestion = null
                            scope.launch {
                                // 整段搬到 IO：这里要解一次密钥库（跨进程 + GCM）、缩放并 JPEG 编码一次，
                                // 第一次点还会现算 5446 条配方的候选表。留在主线程就是一次可感知的卡顿。
                                val outcome = withContext(Dispatchers.IO) {
                                    AiAdvisor.suggest(
                                        bitmap = bmp,
                                        endpoint = settings.visionEndpoint(),
                                        apiKey = keyStore.read().orEmpty(),
                                        http = UrlAiHttp,
                                    )
                                }
                                busy = false
                                when (outcome) {
                                    is AiOutcome.Suggested -> {
                                        suggestion = outcome.suggestion
                                        note = null
                                        telemetry = telemetryLine(
                                            outcome.millis, outcome.promptTokens, outcome.completionTokens,
                                        )
                                    }

                                    is AiOutcome.Failed -> {
                                        note = explain(outcome.kind, outcome.detail)
                                        telemetry = ""
                                    }
                                }
                            }
                        }
                    }
                },
            )
            if (busy) {
                Text(
                    "在读图…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val s = suggestion
        if (s != null) {
            // 实测有模型干脆不给 seen/why 的时候（尤其是回得很短的那几次），
            // 摆一个「它看到的：（没写）」比不摆更糟——用户会以为是自己操作错了。
            if (s.seen.isNotBlank()) {
                AppSheetSection("它看到的", s.seen)
            }
            Text(
                "推荐 ${s.look.name}" + if (s.why.isNotBlank()) " · ${s.why}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).testTag("ai_result"),
            )
            Text(
                s.look.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val dropped = s.shiftsDropped
            if (dropped != null) {
                Text(
                    dropped,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AppChipFlow {
                AppBarPrimary(
                    label = "用这条",
                    modifier = Modifier.testTag("ai_apply"),
                    onClick = {
                        onApply(
                            recipeWithShifts(
                                lookId = s.look.id,
                                intensity = s.look.defaultIntensity,
                                grainMul = 1f,
                                vignetteMul = 1f,
                                exportEdge = exportEdge,
                                shifts = s.shifts.asShifts(),
                            ),
                        )
                    },
                )
                s.alternates.forEach { alt ->
                    AppBarCell(
                        label = alt.name,
                        on = false,
                        modifier = Modifier.testTag("ai_alt_${alt.id}"),
                        onClick = {
                            onApply(
                                recipeWithShifts(
                                    lookId = alt.id,
                                    intensity = alt.defaultIntensity,
                                    grainMul = 1f,
                                    vignetteMul = 1f,
                                    exportEdge = exportEdge,
                                    shifts = Shifts.None,
                                ),
                            )
                        },
                    )
                }
            }
            if (telemetry.isNotEmpty()) {
                Text(
                    telemetry,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        note?.let {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    // 每一句错误都在这里落地（读不到 JSON、这条不像配方、出网失败），
                    // 而它是这一列的**最后一个孩子**：抽屉滚到底才看得见。
                    // 不标活区，点了「让它挑」之后读屏用户什么也听不到。
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * 失败种类 → 用户能照做的下一步。
 *
 * 统一一句「网络错误」是没用的：NO_KEY 要去贴 Key，RATE_LIMIT 要等，AUTH 要去换 Key，
 * OFFLINE 则说明手机根本没网——而随拍在没网时必须一切照常。
 */
private fun explain(kind: AiFailure, detail: String): String {
    val head = when (kind) {
        AiFailure.DISABLED -> "总开关没开"
        AiFailure.NO_KEY -> "还没有 Key"
        AiFailure.OFFLINE -> "连不上端点：检查网络。其余功能不受影响"
        AiFailure.TIMEOUT ->
            "端点没在 ${AiAdvisor.TIMEOUT_MS / 1000} 秒内回话。可能是这一家此刻太慢，也可能是设备现在没有可用网络：" +
                "先确认网络再点一次；一直这样就换一家端点"
        AiFailure.AUTH ->
            "这家端点不认这个 Key。最常见的原因是粘贴时漏了字符或带了空格——重新完整贴一次；" +
                "其次才是 Key 本身过期或换了一家平台（各家的 Key 不通用）"
        AiFailure.RATE_LIMIT -> "这家在限流（免费档常见），稍后再试"
        AiFailure.SERVER -> "端点侧故障，稍后再试"
        AiFailure.REJECTED -> "这个档位不收图片，换带「视觉」的那档"
        AiFailure.BAD_JSON -> "模型没按格式回话，再点一次"
        AiFailure.EMPTY -> "模型没给出内容，再点一次"
    }
    return if (detail.isBlank()) head else "$head · ${detail.take(120)}"
}

/**
 * 用量那一行。
 *
 * `usage` 缺失是很多中转与自建端点的常态，原来 `?: 0` 把它写成「输入 0 输出 0 token」——
 * 那一格是"这一趟到底花了多少"的凭据，编一个 0 比空着更容易骗人。
 * null = 没报，就说没报。
 */
internal fun telemetryLine(millis: Long, prompt: Int?, completion: Int?): String {
    val secs = "%d.%ds".format(millis / 1000, (millis % 1000) / 100)
    val tokens = when {
        prompt == null && completion == null -> "这家用人没报 token"
        prompt == null || completion == null ->
            "token 只报了一半（输入 ${prompt ?: "—"} 输出 ${completion ?: "—"}）"
        else -> "输入 $prompt 输出 $completion token"
    }
    return "$secs · $tokens"
}
