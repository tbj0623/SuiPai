package com.suipai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suipai.app.settings.ReadOnlySource
import com.suipai.app.settings.SettingChange
import com.suipai.app.settings.SettingItem
import com.suipai.app.settings.SettingLive
import com.suipai.app.settings.SettingsGroup
import com.suipai.app.settings.SettingsModel
import com.suipai.app.settings.SettingsSnapshot
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 设置中心主屏：分组列表 + 顶部搜索。
 *
 * 三条设计约束都来自这个项目已经量过的伤：
 *
 * 1. **搜索框自己带名字、提示语不会自己折行**。这一屏没有工具条动作，搜索框独占一行；
 *    这里用 `BasicTextField` 自己画（不是 `OutlinedTextField` + `placeholder`）——
 *    后者的提示语是一颗普通 `Text`，装不下会折行把搜索框撑高（`PerfSourceGuardTest` 第 22 条
 *    量到 528 px、网格被顶光）。也不在 `horizontalScroll` 里放任何东西（第 1 条守卫）。
 * 2. **每一行自己带名字与状态**。用与 `AppBarCell` 同一套被装机验证过的结构：
 *    先 `clickable(role = …)`、再 `clearAndSetSemantics { contentDescription; stateDescription }`。
 *    读屏落在可点那颗节点上时听到的是"JPEG 画质，95"，而不是"按钮"。
 * 3. **没接线的开关必须是灰的**。`SettingLive.PENDING` 的那几项点不动，副标题里明说"等接线"。
 *    这比"好看"重要：装机复测里"拨了没反应"会被当成设置中心坏了。
 *    反过来也成立：**能拨的就必须真的生效**。`LIVE` 的每一项在
 *    `SettingsBridgeTest.everyLiveWritableItemReallyHasAReader` 里都挂了"谁读它"的证据，
 *    不靠人工记。这一版只剩三项 PENDING（年份索引 / 桌面快捷方式 / 缓存过大提醒）。
 *
 * 这一屏**只接参数 + 回调**，不碰导航（`MainActivity` / `nav/` 由 writer-arch 正在重构）。
 * 改动通过 [SettingChange] 描述出来交给接线方，界面自己不知道 prefs 长什么样。
 *
 * @param snapshot 当前设置快照（`SettingsStore.load()`）
 * @param readonly 只读值：`ReadOnlySource → 运行时真值`（版本号、占用、张数…）
 * @param onChange 用户改了一条设置
 * @param onOpenBackup 进「导出与恢复」子屏（存储组最后一行，**不是**设置项模型里的一条 ——
 *   见下面 `SettingsRow` 调用点旁那段说明）
 * @param onBack 返回上一屏
 */
@Composable
fun SettingsScreen(
    snapshot: SettingsSnapshot,
    readonly: Map<ReadOnlySource, String>,
    onChange: (SettingChange) -> Unit,
    onOpenBackup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val grouped = remember(query) { SettingsModel.searchGrouped(query) }
    // 「导出与恢复」这一行**也参与搜索**。少了这一条，搜「备份」会得到一个
    // "没有匹配"的空屏 —— 而那一行明明就挂在存储组末尾（用户搜"备份"正是要它）。
    val backupHit = remember(query) { settingsBackupRowMatches(query) }
    val storageGroupShown = grouped.any { it.first == SettingsGroup.STORAGE }

    Column(modifier.fillMaxSize()) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "设置",
                style = SuiPaiSheet.type.pageHead,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            AppBarText(
                "${SettingsModel.items().size} 项",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
            )
        }
        SettingsSearchField(query = query, onQuery = { query = it })
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            grouped.forEach { (group, groupItems) ->
                item(key = "head_${group.name}") { SettingsGroupHeader(group) }
                items(groupItems, key = { it.key }) { item ->
                    SettingsRow(
                        item = item,
                        snapshot = snapshot,
                        readonly = readonly,
                        onChange = onChange,
                    )
                }
                // 存储组末尾补一行「导出与恢复」。
                //
                // 为什么是**手写一行**而不是往 `SettingsModel` 里加一条 `SettingItem.Action`：
                // 那个目录是"拨一下改一个偏好"的表，它的每条 Action 都由 `SettingAction`
                // 枚举表达（纯 Kotlin、无参数），而这里要的是一个**带路由的子屏入口**
                // （`AppRoute.Backup`）—— 往枚举里塞一个"打开某条路由"等于让设置模型
                // 反过来认识导航，那正是这一轮在拆的耦合。设置项计数（页眉那个「N 项」）
                // 也因此不会被这一行改掉。
                if (group == SettingsGroup.STORAGE) {
                    item(key = "storage_backup_entry") { SettingsBackupRow(onOpenBackup) }
                }
            }
            // 搜索命中这一行、但存储组里一条设置都没命中时（例如搜「备份」）：
            // 上面那个 forEach 根本不会走到 STORAGE —— 单独把整个组补出来，
            // 否则用户搜"备份"看到的是一屏"没有匹配"。
            if (backupHit && !storageGroupShown) {
                item(key = "head_${SettingsGroup.STORAGE.name}") {
                    SettingsGroupHeader(SettingsGroup.STORAGE)
                }
                item(key = "storage_backup_entry") { SettingsBackupRow(onOpenBackup) }
            }
            if (grouped.isEmpty() && !backupHit) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有匹配「$query」的设置。试试「缓存」「画质」「隐私」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「导出与恢复」那一行。与其它设置行长得一样（同一个 `SettingsRowShell`），
 * 但它的点击不产生 [SettingChange]，而是进一条路由 —— 所以它是设置页自己的一行，
 * 不是 `SettingsModel` 里的一条（理由见上面 `LazyColumn` 里那段）。
 *
 * 名字直接落在可点节点上（`SettingsRowShell` 里 `clickable` 紧跟 `clearAndSetSemantics`），
 * 读屏听到的是「导出与恢复」，不是「按钮，双击激活」。
 */
@Composable
private fun SettingsBackupRow(onOpen: () -> Unit) {
    SettingsRowShell(
        title = "导出与恢复",
        note = "把设置、配方、收藏夹与清单打成一个文件带走",
        // Action 型没有"当前值"，但这一格要和上下几行对齐，所以留一个空串（不是 null）。
        value = "",
        enabled = true,
        role = Role.Button,
        spoken = "导出与恢复",
        state = null,
        tag = "setting_storage_backup",
        onClick = onOpen,
    )
}

/**
 * 搜索词是否命中「导出与恢复」这一行。
 *
 * 判据与 `SettingsModel.matches` **同一套**（标题/副标题/关键词，`contains(ignoreCase)`）：
 * 它是设置页里唯一一行不在 `SettingsModel` 里的东西，所以自己带一份判据 ——
 * 但口径必须逐字对齐，否则用户搜同一个词，别的行出现、这一行不出现，看起来像"搜索有毛病"。
 *
 * 纯函数、不碰 Compose，所以能在 JVM 上直接钉住（含"空词不误命中"）。
 */
internal fun settingsBackupRowMatches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    val title = "导出与恢复"
    val note = "把设置、配方、收藏夹与清单打成一个文件带走"
    val keywords = listOf("备份", "导出", "恢复", "还原", "换机", "文件", "json", "导入")
    return title.contains(q, ignoreCase = true) ||
        note.contains(q, ignoreCase = true) ||
        keywords.any { it.contains(q, ignoreCase = true) }
}

/** 分组标题：印样页里的 section 名 + 一句人话。 */
@Composable
private fun SettingsGroupHeader(group: SettingsGroup) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                group.title,
                style = SuiPaiSheet.type.label,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            group.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 11.dp, top = 2.dp),
        )
    }
}

/**
 * 一行设置。五个类型各有各的取值与点击语义，但**都落在同一颗可点节点上**，
 * 且都带 `stateDescription`：
 * - Toggle：点一下翻转；状态「开 / 关」；
 * - Choice：点一下循环到下一档；状态是当前档位名；
 * - Number：点一下 +step，到头回区间起点；状态是"当前值 + 单位"；
 * - Action：点一下执行；没有状态（名字里已经说清是"进外观""清缓存"）；
 * - ReadOnly：不可点，报"名字，值"。
 *
 * 取值与"点一下会变成什么"全部来自 `SettingsModel`（纯 Kotlin，可单测）；
 * 这里只负责把 [SettingChange] 交出去。
 */
@Composable
private fun SettingsRow(
    item: SettingItem,
    snapshot: SettingsSnapshot,
    readonly: Map<ReadOnlySource, String>,
    onChange: (SettingChange) -> Unit,
) {
    val enabled = item.live != SettingLive.PENDING
    when (item) {
        is SettingItem.Info -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge)
                item.paragraphs.forEach { p ->
                    Text(
                        p,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        is SettingItem.ReadOnly -> {
            val value = readonly[item.source] ?: "—"
            SettingsRowShell(
                title = item.title,
                note = item.note,
                value = value,
                enabled = false,
                role = null,
                spoken = item.title,
                state = value,
                tag = "setting_${item.key}",
                onClick = null,
            )
        }
        else -> {
            val value = SettingsModel.currentValue(item, snapshot).orEmpty()
            val change = if (enabled) SettingsModel.changeFor(item, snapshot) else null
            SettingsRowShell(
                title = item.title,
                note = item.note,
                value = value,
                enabled = enabled,
                role = if (change == null) null else Role.Button,
                spoken = item.title,
                state = value.takeIf { item is SettingItem.Toggle || item is SettingItem.Choice<*> || item is SettingItem.Number },
                tag = "setting_${item.key}",
                onClick = { if (change != null) onChange(change) },
            )
        }
    }
}

/**
 * 一行的外壳：**先** `clickable`（带 role）**再** `clearAndSetSemantics`。
 *
 * 顺序是承重的：反过来会把 click 动作一起清掉（`AppBarPrimary` 上量到过 `click=false`，
 * 见 `AppBar.kt` 那段注释）。名字与状态都写在**可点那一颗**节点上，
 * 外层清空子树之后里面的 `Text` 不再各自出声，读屏听到的是一句完整的话。
 */
@Composable
private fun SettingsRowShell(
    title: String,
    note: String,
    value: String,
    enabled: Boolean,
    role: Role?,
    spoken: String,
    state: String?,
    tag: String,
    onClick: (() -> Unit)?,
) {
    val colors = SuiPaiSheet.colors
    val alpha = if (enabled) 1f else 0.45f
    // 可点的那颗自己带名字：`clickable` 紧接着 `clearAndSetSemantics`，中间不隔别的修饰符。
    // 顺序不能反（反了会把 click 动作一起清掉），也不能把两件事拆到不同节点上
    // （话落在子节点时，读屏到可点那颗只会念「按钮，双击激活」—— 装机量到过）。
    val rowModifier = if (onClick != null && role != null) {
        Modifier
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (state != null) "$spoken，$state" else spoken
                if (state != null) stateDescription = state
            }
    } else {
        Modifier.clearAndSetSemantics {
            contentDescription = if (state != null) "$spoken，$state" else spoken
            if (state != null) stateDescription = state
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(rowModifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = SuiPaiSheet.type.frameNo,
                color = if (enabled) colors.grease else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            if (enabled) note else "$note（等接线）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * 顶部搜索框：`BasicTextField` 自己画，字段名常驻在左边。
 *
 * 「搜索」做成常驻字段名而不是会消失的占位符：这一行不高，长占位句必然被截成半句话，
 * 而截断后的提示比没有提示更容易让人以为搜索坏了（同 `AppInlineSearch` 的取舍）。
 */
@Composable
private fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 44.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, SuiPaiSheet.frame)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "搜索",
            style = SuiPaiSheet.type.frameNo,
            color = SuiPaiSheet.colors.number,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "缓存 · 画质 · 隐私 · 引导",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(SuiPaiSheet.colors.grease),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                // 输入区自己带名字：左边那颗「搜索」是兄弟节点，焦点进编辑区时不会顺带念它。
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "搜索设置" },
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "清空搜索", onClick = { onQuery("") })
                    // 可点那颗自己报名字与动作：读屏听到的是「清空搜索」，
                    // 而不是「清除」两个字（那两个字在这个屏幕上还可以指别的）。
                    .clearAndSetSemantics { contentDescription = "清空搜索" }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
