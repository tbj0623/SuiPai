package com.suipai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suipai.app.settings.SettingAction
import com.suipai.app.settings.SettingsKeys
import com.suipai.app.settings.SettingsSnapshot
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 存储子屏：本机数据占用、缓存清理、导出/清除。
 *
 * 这一屏有三条不能含糊的规矩：
 *
 * 1. **"占用"必须说清是什么的占用**。随拍自己不存照片（照片在系统媒体库），
 *    所以这一屏最大的那块数不是"随拍占了多少"，而是"相册里的照片占了多少"。
 *    两个数分开写，绝不相加 —— 把媒体库的体积算成"应用占用"是商店里最常见的一种夸大。
 * 2. **清缓存只删可再生文件**，删完照片、收藏、设置都还在。按钮文案就写这一句，
 *    别写"清理垃圾"（用户会以为照片要没了）。
 * 3. **清除数据要点两次**。第一次点只是把危险按钮换成"真的要清？"，第二次才执行；
 *    执行完把结果写在原地，不弹一个会自己消失的 toast。
 *
 * @param usedText 本机数据占用（随拍自己写的文件：偏好 + 缓存 + 导出件）
 * @param cacheText 图片缓存占用
 * @param libraryText 相册里的照片占用（媒体库，不归随拍管）
 * @param lastCleanAt 上次清理时间（ISO-8601 截断到分钟，空串 = 从没清过）
 * @param onAction CLEAR_CACHE / EXPORT_LOCAL_DATA / CLEAR_LOCAL_DATA
 * @param onRefreshNumbers 重新量一次占用。**与 [onAction] 分开**：见下面「刷新占用」那颗的注释。
 * @param hint 动作结果那句话
 */
@Composable
fun StorageScreen(
    usedText: String,
    cacheText: String,
    libraryText: String,
    lastCleanAt: String,
    hint: String?,
    onAction: (SettingAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshNumbers: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        SubScreenBar(title = "存储", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("storage_list"),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item(key = "usage") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            cacheText,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "图片缓存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                        )
                    }
                    UsageBar(label = "随拍写入", value = usedText)
                    UsageBar(label = "相册照片（系统媒体库，不归随拍管）", value = libraryText)
                    Text(
                        "上面两个数分开算，不相加：随拍自己不存照片，照片在系统相册里，" +
                            "删随拍不会删照片。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item(key = "cache_actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppBarPrimary(
                        label = "清除缓存",
                        onClick = { onAction(SettingAction.CLEAR_CACHE) },
                        modifier = Modifier.testTag("storage_clear_cache"),
                    )
                    AppBarCell(
                        label = "刷新占用",
                        on = false,
                        // 这一颗原来写的是 `onAction(SettingAction.CLEAR_CACHE)` —— 也就是说
                        // 「刷新占用」按下去会**删缓存**。用户点一个只该读一眼的动作，
                        // 结果可再生的临时文件被清掉了：这是"点了做错事"，比没反应更糟。
                        // 重新量一次数字不需要任何 SettingAction（那个枚举是给"改设置"的），
                        // 所以单独走一个回调。
                        onClick = onRefreshNumbers,
                        modifier = Modifier.testTag("storage_refresh"),
                    )
                }
                Text(
                    "只删可再生的临时文件（缩放后的预览、导出中转、相机临时片）。" +
                        "照片、收藏、隐藏、设置都不会被动。" +
                        if (lastCleanAt.isBlank()) "这台设备上还没清过。" else "上次清理：$lastCleanAt。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item(key = "export") {
                SectionLine("导出")
                Text(
                    "「本机数据说明」是一份 JSON：列出随拍在这台设备上存了哪些偏好、值是什么，" +
                        "以及一句固定的「数据去哪了」摘要。可以直接粘给任何人看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp)) {
                    AppBarCell(
                        label = "复制本机数据说明",
                        on = false,
                        onClick = { onAction(SettingAction.EXPORT_LOCAL_DATA) },
                        modifier = Modifier.testTag("storage_export"),
                    )
                    AppBarCell(
                        label = "清除设置",
                        on = false,
                        onClick = { onAction(SettingAction.CLEAR_LOCAL_DATA) },
                        modifier = Modifier.testTag("storage_clear_here"),
                    )
                }
            }

            item(key = "clear") {
                SectionLine("清除数据")
                ClearDataBlock(onAction = onAction)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * 「清除设置」的两步确认。
 *
 * 为什么不用 `AlertDialog`：这一屏原本就有一堆要说的上下文（哪些会被清、哪些不会），
 * 弹框会把它挪走；而且弹框里的"确定"第二轮又要点一个不可逆的动作，
 * 在读屏上是三跳。这里的做法是**按钮自己变**：点一次变红成"确认清除"，
 * 同时多出一句"照片和收藏不会丢"；再点才执行，执行完变回原来的样子并写结论。
 */
@Composable
private fun ClearDataBlock(onAction: (SettingAction) -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            "清除的是设置中心这一层（cfg_ 开头的偏好）。相机、一瞬、相册的老设置，" +
                "照片，收藏与隐藏清单都不动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (armed) {
            Text(
                "再点一次就会清掉这些设置，清完不可撤销。照片与收藏不会丢。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppBarCell(
                label = if (armed) "确认清除" else "清除设置",
                on = armed,
                onClick = {
                    if (armed) {
                        onAction(SettingAction.CLEAR_LOCAL_DATA)
                        armed = false
                    } else {
                        armed = true
                    }
                },
                modifier = Modifier.testTag("storage_clear_data"),
            )
            if (armed) {
                AppBarCell(
                    label = "取消",
                    on = false,
                    onClick = { armed = false },
                    modifier = Modifier.testTag("storage_clear_cancel"),
                )
            }
        }
        Text(
            "想把随拍在这台机器上的一切都清掉：系统「应用 → 随拍 → 存储 → 清除数据」，或直接卸载。" +
                "系统设置那一页有两条出口（相册权限、应用信息），在隐私子屏里。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 一条"标签 —— 数值"的占用行；数值是等宽数字（印样语言里的编号列）。 */
@Composable
private fun UsageBar(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = SuiPaiSheet.type.frameNo,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 子屏里的小节线：比主屏的分组标题轻一档。 */
@Composable
private fun SectionLine(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(MaterialTheme.colorScheme.primary))
        Text(
            title,
            style = SuiPaiSheet.type.label,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 交给接线方的一段现成文案：设置项里「本机数据」那一行说的事。 */
internal fun storageUsedNote(snapshot: SettingsSnapshot): String {
    val exportAt = snapshot.text(SettingsKeys.Privacy.exportAt)
    return if (exportAt.isBlank()) "设置 + 缓存；还没导出过本机数据说明" else "设置 + 缓存；上次导出 $exportAt"
}

/**
 * 把字节数说成人话。**不四舍五入到 0**：小于 1 KB 的东西说成"1 KB"会让人以为清不干净，
 * 说成"0 B"又像没量到，所以小值保留到 B。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
}

/** 把时间戳说成人话：空串 → "还没"；ISO 字串截到分钟并换掉 T。 */
internal fun formatStamp(iso: String): String {
    if (iso.isBlank()) return "还没"
    return iso.replace('T', ' ').take(16)
}

/** 这面钟只在写"上次清理"时用：本地时区的 ISO-8601，秒以下不要。 */
internal fun nowStamp(): String =
    java.time.LocalDateTime.now().withNano(0).toString()

/** 让 `external` 之外的调用点也能拿到"缓存清理后该写什么"。 */
internal fun cleanStamp(): String = nowStamp()
