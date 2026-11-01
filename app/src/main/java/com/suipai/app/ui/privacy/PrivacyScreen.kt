package com.suipai.app.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suipai.app.engine.AiAdvisor
import com.suipai.app.settings.SettingAction
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 隐私与数据中心：数据去哪、权限逐项用途、清除本机数据、导出「本机数据说明」。
 *
 * **这一屏的每一个数字与每一句能力描述都必须对着代码。** 口径来源只有三处，
 * 三处互为镜像，改一处就要改另外两处（`AiGuardTest` 会核对其中两处）：
 * - `ui/about/AboutSheet.kt`（app 内的既有口径）
 * - `store/privacy-policy.html`（可直接发布的完整政策）
 * - `store/listing-zh.md`（商店列表里对用户的承诺）
 *
 * 所以这里：
 * - 缩略图长边与质量**引用常量** `AiAdvisor.SEND_EDGE` / `SEND_QUALITY`，不重抄数字；
 * - 权限表与 `AndroidManifest.xml` 逐条对应，每条都写"只服务哪一件事"；
 * - 备份范围与 `res/xml/backup_rules.xml` / `data_extraction_rules.xml` 的实际 include 一致；
 * - **不新增任何未验证的能力描述**（不写"端到端加密""匿名化"这类代码里不存在的东西）。
 *
 * @param readonly 运行时真值（AI 是否已开启、备份带什么、占用多少）
 * @param onAction 清除数据 / 导出说明 / 跳系统设置
 * @param exportText 生成好的「本机数据说明」JSON 文本；界面只负责显示与复制
 * @param exportHint 上一次复制的结果（成功或"没有剪贴板"），由调用方给
 */
@Composable
fun PrivacyScreen(
    readonly: Map<com.suipai.app.settings.ReadOnlySource, String>,
    exportText: String,
    exportHint: String?,
    onAction: (SettingAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "隐私与数据",
                style = SuiPaiSheet.type.pageHead,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("privacy_list"),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "headline") { PrivacyHeadline() }

            item(key = "data_goes") {
                SectionHead("数据去哪了", "默认哪儿也不去；只有你亲手发起的那两件事才会离开这台手机")
                Paragraphs(
                    listOf(
                        "照片、配方选择、筛选条件、互通用的设备名，全部存在这台设备的本机存储里。",
                        "没有账号，没有登录，没有服务器端：随拍不收集你的内容，也无法远程删除或恢复任何内容。",
                        "「智能配方」的总开关默认是关的。开着时，一次「看这张图」出网的只有两样东西：" +
                            "这张照片长边 ${AiAdvisor.SEND_EDGE} 像素的缩略 JPEG（质量 ${AiAdvisor.SEND_QUALITY}），" +
                            "和一段固定提问文字。原图、EXIF、拍摄位置、相册里的其它照片都不离开这台设备。",
                        "你填入的 API Key 先用 Android 密钥库里那把不可导出的主密钥加密，再写盘；" +
                            "明文不落盘，存进去之后界面上也不会再显示出来。",
                        "「互通」只在你自己打开那一屏、且两端都确认之后，才在同一局域网的设备之间直传；" +
                            "不经过任何服务器。关掉 WiFi 就什么都传不了。",
                        "没有统计 SDK、没有广告 SDK、没有崩溃回传。依赖只有 androidx、JetBrains Kotlin、Coil；" +
                            "出网用的是 Android 自带的 HTTPS 连接，没有第三个网络库。",
                    ),
                )
            }

            item(key = "permissions_head") {
                SectionHead("权限逐项用途", "每一条都只服务于一个功能，没有备用权限")
            }
            items(permissionRows(), key = { it.name }) { row ->
                PermissionRow(row)
            }

            item(key = "backup") {
                SectionHead("系统备份带什么", "只带偏好设置，不带照片，也不带 Key")
                Paragraphs(
                    listOf(
                        "云备份与换机直传都只 include 一个文件：suipai.xml（界面、相机、一瞬、相册与设置中心的偏好）。",
                        "收藏与隐藏的 id 清单住在 suipai.private.xml，两处规则都显式 exclude。" +
                            "它们描述的是「我藏起了哪些照片」，换机后重新收藏一次，比把它送到另一台设备/云上安全。",
                        "照片本身在系统媒体库（MediaStore）里，不归备份管。",
                        "存放 API Key 的 suipai.ai 也不在备份里；就算被带走，换机之后主密钥也没了，那段密文解不开。",
                    ),
                )
            }

            item(key = "export") {
                SectionHead("导出与清除", "两个动作都只影响本机，都不动你的照片")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppBarCell(
                        label = "复制本机数据说明",
                        on = false,
                        onClick = { onAction(SettingAction.EXPORT_LOCAL_DATA) },
                        modifier = Modifier.testTag("privacy_export"),
                    )
                    AppBarCell(
                        label = "清除设置",
                        on = false,
                        onClick = { onAction(SettingAction.CLEAR_LOCAL_DATA) },
                        modifier = Modifier.testTag("privacy_clear"),
                    )
                }
                if (exportHint != null) {
                    Text(
                        exportHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "「清除设置」只清设置中心这一层（cfg_ 开头的偏好），不碰相机、一瞬、相册的老设置，" +
                        "更不碰照片与收藏/隐藏清单。要把随拍的所有东西清干净：" +
                        "系统「应用 → 随拍 → 存储 → 清除数据」，或直接卸载。已保存的照片是相册里的普通文件，请自行删除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            item(key = "export_body_head") {
                SectionHead("本机数据说明（JSON）", "就是上面那个按钮复制下去的内容；可粘给任何人看")
            }
            item(key = "export_body") {
                Text(
                    exportText,
                    style = SuiPaiSheet.type.frameNo,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, SuiPaiSheet.frame)
                        .padding(10.dp),
                )
            }

            item(key = "system") {
                SectionHead("系统里的两个出口", "有些事只能在系统设置里改")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppBarCell(
                        label = "相册权限",
                        on = false,
                        onClick = { onAction(SettingAction.OPEN_PHOTO_PERMISSION) },
                        modifier = Modifier.testTag("privacy_permission"),
                    )
                    AppBarCell(
                        label = "应用信息页",
                        on = false,
                        onClick = { onAction(SettingAction.OPEN_SYSTEM_APP_SETTINGS) },
                        modifier = Modifier.testTag("privacy_app_settings"),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 页首那句结论。放在最上面，因为它是这一屏唯一重要的一句话。 */
@Composable
private fun PrivacyHeadline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, SuiPaiSheet.frame)
            .padding(14.dp),
    ) {
        Column {
            Text(
                "随拍不收集、不使用、不共享、不转让任何个人数据。",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "没有账号体系，没有服务器端，不含任何统计、广告或崩溃回传代码。安装后不联网也能用全部功能。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionHead(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                title,
                style = SuiPaiSheet.type.label,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 11.dp, top = 2.dp),
        )
    }
}

@Composable
private fun Paragraphs(lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        lines.forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    "${i + 1}",
                    style = SuiPaiSheet.type.frameNo,
                    color = SuiPaiSheet.colors.number,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 一行权限。
 *
 * 名字、系统常量、用途三列——**系统常量照抄 `AndroidManifest.xml`**，
 * 因为这一屏的用途是"Data Safety 与被拒申诉时逐条对得上"（见 `AboutSheet` 的注释），
 * 只写一句人话对不上任何东西。
 *
 * 这个类与 [permissionRows] 放进 `ui/privacy` 而不是 `settings/`：
 * 权限是隐私中心的核心事实，而 `PrivacyScreen` 是它唯一的消费点。
 * `PrivacyPermissionTest` 会对着 `AndroidManifest.xml` 逐条核对（少一条、多一条都红）。
 */
data class PermissionRow(
    val name: String,
    val manifest: String,
    /** 什么时候申请（或"系统直接授予，不弹窗"）。 */
    val whenAsked: String,
    val purpose: String,
)

/**
 * 权限表。与 `AndroidManifest.xml` 逐条对应；改动两侧必须同步。
 *
 * 顺序按"用户最可能问到的"排：相机 → 照片 → 存储（老系统）→ 网络 → 壁纸/震动。
 */
fun permissionRows(): List<PermissionRow> = listOf(
    PermissionRow(
        name = "相机",
        manifest = "CAMERA",
        whenAsked = "只在进入拍摄界面时申请",
        purpose = "取景与快门。不在相册、一瞬或后台打开相机。",
    ),
    PermissionRow(
        name = "读取照片",
        manifest = "READ_MEDIA_IMAGES / READ_MEDIA_VISUAL_USER_SELECTED（Android 13+）",
        whenAsked = "首次进入相册时",
        purpose = "列出相册、套配方、批量整理。支持系统的「只允许访问所选照片」——" +
            "只授权部分时，随拍只看得到你选中的那些。",
    ),
    PermissionRow(
        name = "旧系统存储权限",
        manifest = "READ_EXTERNAL_STORAGE（≤ Android 12L）",
        whenAsked = "首次进入相册时（仅老系统）",
        purpose = "在 Android 13 之前完成同样的相册读取。",
    ),
    PermissionRow(
        name = "旧系统写入权限",
        manifest = "WRITE_EXTERNAL_STORAGE（≤ Android 9）",
        whenAsked = "保存成片时（仅老系统）",
        purpose = "把成片写进 Pictures/SuiPai。Android 10 起走 MediaStore，不需要这条。",
    ),
    PermissionRow(
        name = "网络",
        manifest = "INTERNET / ACCESS_NETWORK_STATE",
        whenAsked = "系统直接授予，不弹窗",
        purpose = "只服务两件事：「互通」在同一局域网发现附近设备并传图；" +
            "可选的「智能配方」在你亲手按下按钮时把这一张的缩略图发往你自己选的接口。" +
            "随拍不连接任何自有服务器。",
    ),
    PermissionRow(
        name = "设为壁纸",
        manifest = "SET_WALLPAPER",
        whenAsked = "系统直接授予，不弹窗",
        purpose = "只对应查看器里的「设为壁纸」这一个动作。",
    ),
    PermissionRow(
        name = "震动",
        manifest = "VIBRATE",
        whenAsked = "系统直接授予，不弹窗",
        purpose = "快门触感反馈。关掉「震动」开关之后不再触发。",
    ),
)

@Composable
private fun PermissionRow(row: PermissionRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                row.whenAsked,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            row.manifest,
            style = SuiPaiSheet.type.frameNo,
            color = SuiPaiSheet.colors.number,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            row.purpose,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 生成「本机数据说明」的 JSON 文本。
 *
 * 为什么是手写 JSON 而不是引序列化库：这个项目**不新增依赖**，
 * 而这里要序列化的是一张 `String → String` 的平表（`SettingsSnapshot.forExport()`），
 * 手写转义只有三行，比拉一个 300 KB 的库划算得多。
 *
 * 内容是**只读说明书**：它报告这台设备上随拍存了哪些偏好、值是什么，
 * 以及一张固定的"数据去哪了"摘要。不包含照片、文件名、位置、Key。
 */
object PrivacyExport {

    /**
     * @param snapshot 当前设置快照
     * @param facts 运行时真值（版本、机型、张数、AI 状态…）：键是给人看的字段名
     */
    fun buildJson(snapshot: com.suipai.app.settings.SettingsSnapshot, facts: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": \"随拍 / SuiPai\",\n")
        facts.forEach { (k, v) ->
            sb.append("  \"").append(escape(k)).append("\": \"").append(escape(v)).append("\",\n")
        }
        sb.append("  \"settingsFile\": \"suipai.xml（参与系统备份）\",\n")
        sb.append("  \"privateFile\": \"suipai.private.xml（收藏与隐藏清单，不参与备份）\",\n")
        sb.append("  \"aiKeyFile\": \"suipai.ai（密钥库加密，不参与备份）\",\n")
        sb.append("  \"photosUploaded\": 0,\n")
        sb.append("  \"networkCalls\": \"仅「互通」（局域网直传）与用户亲手发起的「智能配方」\",\n")
        sb.append("  \"settings\": {\n")
        val rows = snapshot.forExport().toList()
        rows.forEachIndexed { i, (k, v) ->
            sb.append("    \"").append(escape(k)).append("\": \"").append(escape(v)).append('"')
            if (i != rows.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** JSON 字符串转义：引号、反斜杠、换行、回车、制表。够这张平表用。 */
    fun escape(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
    }
}
