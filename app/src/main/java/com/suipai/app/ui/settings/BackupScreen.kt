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
import com.suipai.app.data.BackupCounts
import com.suipai.app.data.MergeMode
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.theme.SuiPaiSheet
import java.time.Instant
import java.time.ZoneId

/**
 * 「导出与恢复」子屏：把账号之外的第一件事 —— 用户自己的数据 —— 变成可带走的文件。
 *
 * ## 这一屏存在的理由
 * 设置、自定义配方、收藏夹、收藏/隐藏清单、搜索历史以前**只在这台设备上**，
 * 换手机就全丢。系统备份只带 `suipai.xml` 一层偏好，且随拍把清单挪进了
 * 不参与备份的 `suipai.private.xml`（那是隐私）。所以这里给一条显式的出口：
 * 用户自己挑一个位置写文件，自己在新设备上选回来。
 *
 * ## 三条不能含糊的规矩
 * 1. **文件里没有照片。** 随拍不存照片，照片在系统媒体库。备份里只有"哪几张被收藏了"
 *    这样的 id，而换机之后照片行号会变。这一屏必须在按钮**之前**说清这件事，
 *    否则用户会以为换机只要恢复一个文件 —— 到时候收藏夹指向一堆不存在的行，
 *    界面上看起来就是"恢复失败了"。
 * 2. **恢复要有二次确认，且必须自己选模式。** 恢复会覆盖本机现在的数据，
 *    而 REPLACE 与 UNION 的后果完全不同（一个会删、一个只加）。所以默认**一个都不选**，
 *    用户读了两段后果、点了一种，还要再点一次确认，才开始选文件。
 * 3. **这个文件是敏感文件。** 里面有收藏与隐藏清单 —— 也就是"我把哪些照片藏起来了"。
 *    导出之后它躺在用户自己选的位置（可能是网盘、可能是聊天记录），要提醒一句。
 *
 * 这一屏**不持有仓库、不导航、不读 Activity**：数据由接线方算好传进来，
 * 动作由接线方执行（放 `ActivityResultContracts.CreateDocument` / `OpenDocument`），
 * 结果由接线方写进 [note]。所以它能被 `SettingsUiGuardTest` 那一族读源码的守卫扫到，
 * 也不需要在 JVM 上起一个假的 `Context`。
 *
 * @param localCounts 本机现在有多少东西（让用户在恢复前能对比）
 * @param lastExportAt 上次导出成功的时刻（毫秒），`0` = 这台设备上还没导出过
 * @param lastExportCounts 上次导出的那一包有多大，`null` = 不知道（例如刚装完机）
 * @param note 上一次动作的结论（成功与失败都写在这里），`null` = 还没动作过
 * @param busy 正在读写，按钮全部灰掉 —— 导出与恢复都是几秒到几十秒的 IO
 * @param onExport 接线方用它调 `CreateDocument` 并把 uri 交给 `Backup.exportTo`
 * @param onImport 接线方用它调 `OpenDocument` 并把 uri 交给 `Backup.importFrom(mode)`
 */
@Composable
fun BackupScreen(
    localCounts: BackupCounts,
    lastExportAt: Long,
    lastExportCounts: BackupCounts?,
    note: String?,
    busy: Boolean,
    onExport: () -> Unit,
    onImport: (MergeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 两个状态就够了：恢复区的"已展开"与"选了哪种模式"。
    // 不再加第三个（"要不要再来一次确认"）：模式未选 = 确认按钮不可点，本身就是一道闸。
    var restoring by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<MergeMode?>(null) }

    Column(modifier.fillMaxSize()) {
        SubScreenBar(title = "导出与恢复", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("backup_list"),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
            // ── 这个文件里有什么、没有什么 ────────────────────────────
            item(key = "scope") {
                SectionLine("备份里有什么")
                Text(
                    "一份文件，装下随拍在这台设备上写下的所有**非照片**数据：" +
                        "设置中心的偏好、你自建的一瞬配方、收藏夹、收藏与隐藏的清单、相册的搜索历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                NoPhotosBox()
            }

            // ── 本机现状 / 上次导出 ───────────────────────────────────
            item(key = "status") {
                SectionLine("本机与上次导出")
                CountRow("本机现在", localCounts.line(), "backup_local_counts")
                Spacer(Modifier.height(6.dp))
                CountRow(
                    label = "上次导出",
                    value = if (lastExportAt <= 0L) {
                        "这台设备上还没导出过"
                    } else {
                        "${backupStamp(lastExportAt)} · ${lastExportCounts?.line() ?: "条数没记下来"}"
                    },
                    tag = "backup_last_export",
                )
            }

            // ── 导出 ──────────────────────────────────────────────────
            item(key = "export") {
                SectionLine("导出到文件")
                Text(
                    "会打开系统的文件选择器，让你挑一个位置（本机下载目录、U 盘、网盘都行）。" +
                        "随拍不申请存储权限 —— 写入授权跟着你选的那一个文件走，用完就没了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    AppBarPrimary(
                        label = if (busy) "正在写入…" else "导出到文件",
                        onClick = onExport,
                        enabled = !busy,
                        modifier = Modifier.testTag("backup_export"),
                    )
                }
                Text(
                    "文件名默认带日期，扩展名是 .json。它是一份纯文本，可以自己打开看，" +
                        "也可以放网盘同步 —— 但请先读下面那段隐私提醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // ── 敏感文件提醒 ──────────────────────────────────────────
            item(key = "privacy") {
                SensitiveBox()
            }

            // ── 从文件恢复 ────────────────────────────────────────────
            item(key = "restore") {
                SectionLine("从文件恢复")
                Text(
                    "挑一份之前导出的 .json，把里面的东西写回这台设备。会先校验：不是随拍的备份、" +
                        "或者来自更新版本的随拍，都会被整份拒绝，本机数据一个字都不动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                RestoreBlock(
                    restoring = restoring,
                    mode = mode,
                    busy = busy,
                    onToggle = {
                        restoring = !restoring
                        if (!restoring) mode = null
                    },
                    onPickMode = { mode = it },
                    onConfirm = { picked -> if (picked != null) onImport(picked) },
                )
            }

            // ── 结论 ──────────────────────────────────────────────────
            if (note != null) {
                item(key = "note") {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("backup_note"),
                    )
                }
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 「照片不在里面」那块重点。
 *
 * 单独一块、带框、写在导出按钮**之前**：这是用户最容易误解的一条，而误解的代价是
 * 换机之后以为"恢复完了"，实际上收藏夹指向一堆不存在的行。
 */
@Composable
private fun NoPhotosBox() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, SuiPaiSheet.frame)
            .padding(12.dp),
    ) {
        Text(
            "照片本身不在备份里",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "随拍不存照片，照片是系统相册里的文件。备份里只有「哪几张被收藏、哪几张被藏起来」" +
                "这样的编号，而换一台设备之后这些编号指向的是新机器上的另一批照片。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "所以换机的正确顺序是：先用系统相册 / 数据线 / 网盘把照片搬过去，再恢复这份备份。" +
                "即使顺序反了也不会坏事 —— 只是收藏夹与收藏清单里的照片会对不上，需要重新收藏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 「这是个敏感文件」那块提醒。文案口径与 `docs/product/BACKUP.md` 的隐私一节一致。 */
@Composable
private fun SensitiveBox() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.error, SuiPaiSheet.frame)
            .padding(12.dp),
    ) {
        Text(
            "导出的文件是敏感文件",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "里面有你的收藏清单与隐藏清单 —— 也就是「我把哪些照片藏起来了」。它不含照片，" +
                "但足以说明你收藏过什么、藏过什么。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "请放在只有你能看到的位置：自己的网盘、自己的电脑。不要发到群里、不要贴进公开的 Issue。" +
                "导出之后想删，在系统文件管理器里删掉那个 .json 就行，随拍不保留副本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 恢复区的两步闸。
 *
 * 为什么把"选模式"做成一次可点、把"确认"做成另一颗按钮，而不是一个弹框里的单选：
 * 弹框会把两段后果挤成小字，而这两段话正是用户唯一能据此决定"要不要删掉本机配方"的东西。
 * 铺在页面上、整段可读、按钮就在下面，才有机会被真的读到。
 *
 * 模式**默认不选**（`mode == null`）：任何默认值都会变成"没读就下一步"的快捷方式，
 * 而 REPLACE 的默认值会直接删掉用户本机的东西。
 */
@Composable
private fun RestoreBlock(
    restoring: Boolean,
    mode: MergeMode?,
    busy: Boolean,
    onToggle: () -> Unit,
    onPickMode: (MergeMode) -> Unit,
    onConfirm: (MergeMode?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            AppBarCell(
                label = if (restoring) "收起恢复" else "从文件恢复",
                on = restoring,
                onClick = onToggle,
                enabled = !busy,
                modifier = Modifier.testTag("backup_restore_arm"),
            )
        }

        if (!restoring) return@Column

        Text(
            "第一步：选怎么合。两种做法的后果不一样，请读完再点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        AppChipFlow {
            AppBarCell(
                label = "以文件为准",
                on = mode == MergeMode.REPLACE,
                onClick = { onPickMode(MergeMode.REPLACE) },
                enabled = !busy,
                modifier = Modifier.testTag("backup_mode_replace"),
            )
            AppBarCell(
                label = "两边合并",
                on = mode == MergeMode.UNION,
                onClick = { onPickMode(MergeMode.UNION) },
                enabled = !busy,
                modifier = Modifier.testTag("backup_mode_union"),
            )
        }

        when (mode) {
            MergeMode.REPLACE -> ConsequenceText(
                title = "「以文件为准」会删掉本机多出来的东西",
                body = "恢复之后，本机变成文件里那一份的样子：本机有、文件里没有的配方会被删掉，" +
                    "收藏夹整份换成文件里的，收藏与隐藏清单只剩下文件里那些编号，搜索历史也只剩文件里的词。" +
                    "设置只覆盖文件里有的键，本机其它的设置项不动。",
                danger = true,
            )

            MergeMode.UNION -> ConsequenceText(
                title = "「两边合并」只加不删",
                body = "文件里的东西并进本机：本机已有的保留，文件里多出来的补上。" +
                    "同一条配方、同一个收藏夹以本机为准（名字与创建时间不动），" +
                    "同一个收藏夹里两边都有的照片只留一份。本机现在的配方、收藏夹、清单一条都不会少。",
                danger = false,
            )

            null -> Text(
                "上面两种都还没选。没选之前，下面这颗按钮点不动。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(Modifier.padding(top = 12.dp)) {
            AppBarPrimary(
                label = when (mode) {
                    MergeMode.REPLACE -> if (busy) "正在恢复…" else "确认：以文件为准"
                    MergeMode.UNION -> if (busy) "正在恢复…" else "确认：两边合并"
                    null -> "先选一种做法"
                },
                onClick = { onConfirm(mode) },
                enabled = !busy && mode != null,
                modifier = Modifier.testTag("backup_restore_confirm"),
            )
        }
        Text(
            "第二步：上面这颗按钮会打开文件选择器，挑好文件之后才会真的开始。" +
                "在系统选择器里按取消，就什么都没发生。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 一种模式的后果。REPLACE 用定影红，UNION 用主文字色 —— 危险程度必须一眼分得开。 */
@Composable
private fun ConsequenceText(title: String, body: String, danger: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 一条「标签 —— 数值」行。数值用等宽（印样语言里的编号列），与存储页同一套。 */
@Composable
private fun CountRow(label: String, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
            )
            Text(
                value,
                style = SuiPaiSheet.type.frameNo,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(tag),
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
}

/** 子屏里的小节线：与存储页、关于页同一套（3dp 琥珀竖条 + 标签字）。 */
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

/**
 * 毫秒时间戳 → 「2026-10-18 13:20」。
 *
 * 为什么不用 `DateTimeFormatter.ofPattern`：那个模式串每次调用都要重新解析，
 * 而且 `PerfSourceGuardTest.formattersAreDeclaredOnce` 钉着它只许出现在声明行上。
 * 这里要的只是六个数字，手工补零比引一个 formatter 更短。
 */
internal fun backupStamp(atMillis: Long): String {
    if (atMillis <= 0L) return "还没导出过"
    val t = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())
    fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
    return "${t.year}-${two(t.monthValue)}-${two(t.dayOfMonth)} " +
        "${two(t.hour)}:${two(t.minute)}"
}
