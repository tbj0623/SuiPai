package com.suipai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suipai.app.engine.AiAdvisor
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 「关于随拍」正式页面 —— 把 `ui/about/AboutSheet.kt` 的内容升级成整页。
 *
 * **原 sheet 保留不删**：`AboutSheet` 今天挂在 `MainActivity` 的页眉里，
 * 那条路径由 writer-arch 的导航重构负责改；这一页只是给未来的设置中心一个挂点。
 * 两处的文案口径必须一致（同源到 `store/privacy-policy.html`），
 * 所以这一页的正文**照抄** AboutSheet 的四块，只把"抽屉"换成"页"。
 *
 * 相比 sheet 多出来的两件事：
 * 1. **开源许可可选中复制**（`SelectionContainer`）—— 上架申诉时要能粘出去；
 * 2. **引导状态**：新用户第一次能在这里看到"首启引导走完了没有"，也是"重看引导"的落点。
 *
 * @param versionText "随拍 0.6.0（6）"
 * @param deviceText  "Xiaomi xx · Android 16（API 36）"
 * @param savePath    照片保存位置
 * @param onboardingStatus 引导状态（`OnboardingGate.statusText`）
 * @param onReplayOnboarding 重看引导
 */
@Composable
fun AboutScreen(
    versionText: String,
    deviceText: String,
    savePath: String,
    onboardingStatus: String,
    onReplayOnboarding: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SubScreenBar(title = "关于随拍", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("about_list"),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
            item(key = "version") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        versionText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    )
                    Text(
                        deviceText,
                        style = SuiPaiSheet.type.frameNo,
                        color = SuiPaiSheet.colors.number,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "照片保存位置：$savePath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            item(key = "onboarding") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("首启引导", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            onboardingStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppBarCell(
                        label = "重看",
                        on = false,
                        onClick = onReplayOnboarding,
                        modifier = Modifier.testTag("about_replay_onboarding"),
                    )
                }
            }

            aboutBlocks().forEach { block ->
                item(key = "head_${block.title}") {
                    BlockHead(block.title, block.note)
                }
                items(block.lines, key = { "line_${block.title}_${it.hashCode()}" }) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item(key = "gap_${block.title}") { Spacer(Modifier.height(6.dp)) }
            }

            item(key = "licenses") {
                BlockHead("开源许可", null)
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        listOf(
                            "Jetpack Compose · CameraX · Material 3 · ExifInterface · Lifecycle · Navigation —— Apache License 2.0",
                            "Kotlin · kotlinx-coroutines —— Apache License 2.0",
                            "Coil —— Apache License 2.0",
                            "这些组件不引入任何数据收集行为。",
                        ).forEach { line ->
                            Text(
                                line,
                                style = SuiPaiSheet.type.frameNo,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            item(key = "tail") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text(
                        "拍完就忘的相机，不该顺手记住别的。",
                        style = SuiPaiSheet.type.frameNo.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/** 一块文案：标题 + 一句副记 + 若干行。与 `AboutSheet` 的私有 `AboutBlock` 同形。 */
data class AboutBlock(val title: String, val note: String?, val lines: List<String>)

/**
 * 四块正文。**与 `AboutSheet.staticBlocks()` 同源**：改这里必须同步改 sheet 与
 * `store/privacy-policy.html`（`AiGuardTest.privacyTextsQuoteTheThumbnailSizeTheCodeActuallySends`
 * 会盯缩略图那个数字，但盯不到其它句子——所以这条注释是提醒，不是自动判据）。
 *
 * 缩略图长边与质量引 `AiAdvisor` 常量：全项目只有一个真值来源，
 * 政策文案里抄死数字会被 `AiGuardTest` 判红。
 */
fun aboutBlocks(): List<AboutBlock> = listOf(
    AboutBlock(
        "权限与用途",
        "每一条都只服务于一个功能，没有备用权限",
        listOf(
            "相机 · 只在进入拍摄界面时申请，用于取景与快门。",
            "读取照片 · Android 13 以上为 READ_MEDIA_IMAGES，可选「只选部分照片」；用于列相册、套配方、批量整理。",
            "读取视频 · Android 13 以上为 READ_MEDIA_VIDEO，只在视频页点「授权视频 / 补选视频」时申请；仅播放本机视频和提取一帧。",
            "存储 · Android 12L 及以下读取相册用 READ_EXTERNAL_STORAGE；" +
                "Android 9 及以下保存图片用 WRITE_EXTERNAL_STORAGE，Android 10+ 保存走 MediaStore。",
            "网络 · 只服务两件事：「互通」在同一局域网里发现附近的随拍设备并传图；" +
                "「智能配方」在你亲手按下「看这张图」时，把这张照片的缩略图发往你自己填的那一家官方接口。" +
                "除这两处之外，随拍不连接任何服务器。",
            "设为壁纸、震动 · 分别对应「设为壁纸」动作与快门触感，系统不弹窗询问。",
        ),
    ),
    AboutBlock(
        "数据去哪了",
        "默认哪儿也不去；只有你打开智能配方并亲手按那一个按钮，才会出网",
        listOf(
            "照片、配方选择、筛选条件、互通用的设备名，全部存在这台设备的本机存储里。",
            "没有账号，没有登录，没有服务器端：随拍不收集你的内容，也无法远程删除或恢复任何内容。",
            "「智能配方」的总开关默认是关的。开着时，一次「看这张图」出网的只有两样东西：" +
                "这张照片长边 ${AiAdvisor.SEND_EDGE} 像素的缩略 JPEG（质量 ${AiAdvisor.SEND_QUALITY}），" +
                "和一段固定提问文字。原图、EXIF、拍摄位置、相册里的其它照片都不离开这台设备。",
            "你填入的 API Key 先用 Android 密钥库里那把不可导出的主密钥加密，再写盘；" +
                "明文不落盘，存进去之后界面上也不会再显示出来。",
            "系统备份与换机直传只带偏好设置一个文件（suipai.xml）：不含照片，" +
                "也不含 Key 所在的 suipai.ai——就算带上，换机之后主密钥也没了，那段密文解不开。",
            "没有统计 SDK、没有广告 SDK、没有崩溃回传。依赖只有 androidx、JetBrains Kotlin、Coil；" +
                "出网用的是 Android 自带的 HTTPS 连接，没有第三个网络库。",
            "想清干净：在系统「应用 → 随拍 → 存储 → 清除数据」，或直接卸载；" +
                "只想去掉 Key，在「智能配方」里按「清除」。已保存的照片是你相册里的普通文件，自行删除即可。",
        ),
    ),
    AboutBlock(
        "隐私政策",
        "本机可读的完整政策；商店列表页需要公网 URL 时，把这一段原样发布即可",
        listOf(
            "随拍不收集、不使用、不共享、不转让任何个人数据。",
            "不请求位置、通讯录、通话记录、麦克风、日历权限，也不在后台运行任何采集任务。",
            "「互通」功能传输的照片只经过两台设备之间的局域网连接，不经过第三方；" +
                "传输需要用户在同一网络下手动确认接收。",
            "「智能配方」是可选功能，默认关闭：照片只发送给你自行选择、自行填入 Key 的那一家官方接口，" +
                "且只在你按下「看这张图」时发送。随拍不代你存管照片，也不经手你的 Key 到任何第三方。",
            "分享到其他 app 由系统选择器完成，随拍不保留副本、不记录接收方。",
            "本政策如有变更，会随版本一起更新并在这一屏显示。",
        ),
    ),
)

@Composable
private fun BlockHead(title: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.padding(end = 8.dp).size(width = 3.dp, height = 14.dp).background(MaterialTheme.colorScheme.primary))
            Text(
                title,
                style = SuiPaiSheet.type.label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 11.dp, top = 2.dp),
            )
        }
    }
}
