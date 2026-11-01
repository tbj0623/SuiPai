package com.suipai.app.ui.about

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suipai.app.engine.AiAdvisor
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 「关于随拍」抽屉：版本、权限用途、数据去向、隐私政策、开源许可。
 *
 * 存在的理由不是装饰。上架硬门槛里有两条都落在这里：
 * - 读取相册 + 相机的应用，Google Play 要求**可直达**的隐私政策；此前 app 内一个字都没有，
 *   审核只能靠商店列表里那条 URL，而列表 URL 还没备案；
 * - 权限用途说明（permission rationale）在 Data Safety 与被拒申诉时都要逐条对上。
 * 所以这里的每条陈述都对着代码写：依赖表里没有统计/广告/崩溃回传 SDK，
 * 全项目出网的代码只有一处（`data/ai/UrlAiHttp`，局域网互通除外，见 AiSourceGuardTest 的源码级断言），
 * 而「智能配方」默认关闭。改这段文案里的任何数字或范围时，同步改 `store/privacy-policy.html`
 * 与 `store/listing-zh.md`，并跑一次那两个文件与代码的对照测试。
 */
private data class AboutBlock(val title: String, val note: String?, val lines: List<String>)

/** 版本号与设备行在运行时取，其余是政策正文。 */
private fun staticBlocks(): List<AboutBlock> = listOf(
    AboutBlock(
        "权限与用途",
        "每一条都只服务于一个功能，没有备用权限",
        listOf(
            "相机 · 只在进入拍摄界面时申请，用于取景与快门。",
            "读取照片 · Android 13 以上为 READ_MEDIA_IMAGES，可选「只选部分照片」；用于列相册、套配方、批量整理。",
            "写入照片 · 保存到 Pictures/SuiPai 走 MediaStore，Android 10 以上不需要存储权限；READ/WRITE_EXTERNAL_STORAGE 只发给 9 及以下的老系统。",
            "网络 · 只服务两件事：「互通」在同一局域网里发现附近的随拍设备并传图；" +
                "「智能配方」在你亲手按下「看这张图」时，把这张照片的缩略图发往**你自己填的那一家**官方接口。" +
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
                "这张照片长边 ${AiAdvisor.SEND_EDGE} 像素的缩略 JPEG，和一段固定提问文字。" +
                "原图、EXIF、拍摄位置、相册里的其它照片都不离开这台设备。",
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
            "「互通」功能传输的照片只经过两台设备之间的局域网连接，不经过第三方；传输需要用户在同一网络下手动确认接收。",
            "「智能配方」是可选功能，默认关闭：照片只发送给你自行选择、自行填入 Key 的那一家官方接口，" +
                "且只在你按下「看这张图」时发送。随拍不代你存管照片，也不经手你的 Key 到任何第三方。",
            "分享到其他 app 由系统选择器完成，随拍不保留副本、不记录接收方。",
            "本政策如有变更，会随版本一起更新并在这一屏显示。",
        ),
    ),
    AboutBlock(
        "开源许可",
        null,
        listOf(
            "Jetpack Compose · CameraX · Material 3 · ExifInterface · Lifecycle · Navigation —— Apache License 2.0",
            "Kotlin · kotlinx-coroutines —— Apache License 2.0",
            "Coil —— Apache License 2.0",
        ),
    ),
)

@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows = remember {
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = pkg?.versionName ?: "?"
        // longVersionCode 在 API 28 以下才不存在；minSdk 26，所以老接口也要走到。
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) {
            pkg?.longVersionCode?.toString() ?: "?"
        } else {
            pkg?.versionCode?.toString() ?: "?"
        }
        val head = AboutBlock(
            "版本",
            "构建号 · 机型 · 系统",
            listOf(
                "随拍 $version（$code）",
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                "照片保存位置：Pictures/SuiPai",
            ),
        )
        listOf(head) + staticBlocks()
    }
    AppSheet(title = "关于随拍", tag = "about_sheet", onDismiss = onDismiss) {
        rows.forEach { block ->
            AppSheetSection(block.title, block.note)
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                block.lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "拍完就忘的相机，不该顺手记住别的。",
            style = SuiPaiSheet.type.frameNo,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
