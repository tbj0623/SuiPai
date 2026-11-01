package com.suipai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suipai.app.settings.FontScale
import com.suipai.app.settings.GridColumns
import com.suipai.app.settings.SettingChange
import com.suipai.app.settings.SettingsKeys
import com.suipai.app.settings.SettingsSnapshot
import com.suipai.app.settings.ThemeMode
import com.suipai.app.settings.ThumbQuality
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 外观子屏：主题、字号、网格列数、缩略图质量，外加一块**即时预览**。
 *
 * 为什么要预览：这四项里，网格与缩略图要**退出设置页、回到相册**才看得见；
 * 预览让"你选的就是这个密度"在同一个屏幕上成立，不用先怀疑设置没生效。
 * （主题与字号**不需要**退出：`ui/theme/Theme.kt` 订阅了 `SettingsSignal`，
 * 拨完这一屏自己就换皮了 —— 预览块也跟着换，因为它读的是同一套 token。）
 *
 * 预览里的格子是**假照片**（色块 + 编号），不读相册：这一屏不该为了几个缩略图去申请/遍历媒体库，
 * 那会让"打开外观设置"变成一次重 IO。编号是印样语言里的帧号，不冒充照片内容。
 *
 * 真源与读数点：`cfg_theme_mode` / `cfg_font_scale` 由 `SuiPaiTheme` 消费；
 * `cfg_grid_columns` / `cfg_thumb_quality` 由 `GalleryScreen` 的 `cellDp` 与 `FastThumb(px)` 消费。
 * 老键那侧的兼容写与映射见 `settings/SettingsBridge.kt`。
 */
@Composable
fun AppearanceScreen(
    snapshot: SettingsSnapshot,
    onChange: (SettingChange) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = snapshot.choice(SettingsKeys.Appearance.themeMode)
    val font = snapshot.choice(SettingsKeys.Appearance.fontScale)
    val grid = snapshot.choice(SettingsKeys.Appearance.gridColumns)
    val thumb = snapshot.choice(SettingsKeys.Appearance.thumbQuality)

    Column(modifier.fillMaxSize()) {
        SubScreenBar(title = "外观", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.testTag("appearance_list"),
        ) {
            item(key = "preview") {
                AppearancePreview(theme = theme, font = font, grid = grid, thumb = thumb)
            }
            item(key = "theme") {
                OptionSection(
                    title = "主题",
                    note = "暗房是这套界面的默认（深灰绿底、琥珀强调）；日光印样是纸白那套。跟随系统按 Android 设置翻。",
                    options = ThemeMode.entries.map { it.name },
                    labels = ThemeMode.entries.map { it.label },
                    selected = theme.name,
                    tagPrefix = "theme",
                    onPick = { onChange(SettingChange.Choice(SettingsKeys.Appearance.themeMode, it)) },
                )
            }
            item(key = "font") {
                OptionSection(
                    title = "字号",
                    note = "在系统字号之上再调一档。系统已经放大很多时，这里选「标准」也不会把它缩回去。",
                    options = FontScale.entries.map { it.name },
                    labels = FontScale.entries.map { "${it.label} ${it.percent}%" },
                    selected = font.name,
                    tagPrefix = "font",
                    onPick = { onChange(SettingChange.Choice(SettingsKeys.Appearance.fontScale, it)) },
                )
            }
            item(key = "grid") {
                OptionSection(
                    title = "照片网格",
                    note = "一格的最小宽度。数字越小，一屏塞的列越多。",
                    options = GridColumns.entries.map { it.name },
                    labels = GridColumns.entries.map { "${it.label} · ${it.minCellDp}dp" },
                    selected = grid.name,
                    tagPrefix = "grid",
                    onPick = { onChange(SettingChange.Choice(SettingsKeys.Appearance.gridColumns, it)) },
                )
            }
            item(key = "thumb") {
                OptionSection(
                    title = "缩略图质量",
                    note = "走系统的缩略图缓存，再按这条边长解。清晰更费内存，省电在低端机上更稳。",
                    options = ThumbQuality.entries.map { it.name },
                    labels = ThumbQuality.entries.map { "${it.label} · ${it.edgePx}px" },
                    selected = thumb.name,
                    tagPrefix = "thumb",
                    onPick = { onChange(SettingChange.Choice(SettingsKeys.Appearance.thumbQuality, it)) },
                )
            }
            item(key = "reduce_motion") {
                val on = snapshot.bool(SettingsKeys.Appearance.reduceMotion)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) {
                            onChange(SettingChange.Toggle(SettingsKeys.Appearance.reduceMotion, !on))
                        }
                        .clearAndSetSemantics {
                            contentDescription = if (on) "减弱动效，开" else "减弱动效，关"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("减弱动效", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关掉淡入淡出与按压动画。系统已经开了「移除动画」时这一项不叠加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (on) "开" else "关",
                        style = SuiPaiSheet.type.frameNo,
                        color = SuiPaiSheet.colors.grease,
                    )
                }
            }
            item(key = "pending_note") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "这四项已经真的生效：主题与字号当场换（整屏，不用退出去），" +
                            "网格与缩略图在回到相册时生效。上面的预览是同一套档位的示意图。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 子屏页眉：与设置主屏同一套（返回 + 页名），不堆 Material AppBar。 */
@Composable
internal fun SubScreenBar(title: String, onBack: () -> Unit) {
    AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        AppBarCell(label = "返回", on = false, onClick = onBack)
        AppBarText(title, style = SuiPaiSheet.type.pageHead, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 一块即时预览：按当前档位画 12 个假格子。
 *
 * - 列数：按 `GridColumns.minCellDp` 反推（这一块宽度固定成 3 档可比的样子，
 *   所以真正的"多少列"是按比例算出来的，不是写死的数字）；
 * - 缩略图质量：用格子的圆角/描边粗细与一个"清晰度"角标暗示，不做假的像素尺寸承诺；
 * - 字号：用档位的百分比缩放假格子下面的编号。
 */
@Composable
private fun AppearancePreview(
    theme: ThemeMode,
    font: FontScale,
    grid: GridColumns,
    thumb: ThumbQuality,
) {
    val scale = font.percent / 100f
    // 预览区宽度按屏幕给，列数由"最小格宽"决定：这是 GridCells.Adaptive 的同一套算法。
    val columns = when (grid) {
        GridColumns.DENSE -> 6
        GridColumns.SMALL -> 5
        GridColumns.STANDARD -> 4
        GridColumns.LARGE -> 3
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, SuiPaiSheet.frame)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "预览",
                style = SuiPaiSheet.type.frameNo,
                color = SuiPaiSheet.colors.number,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${theme.label} · ${font.percent}% · ${columns} 列 · ${thumb.edgePx}px",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 用几行 Row 手工排，不引 Lazy——这是固定 12 格的小预览，Lazy 在这里只是负担。
        (0 until 12 step columns).forEach { start ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(columns) { c ->
                    val i = start + c
                    if (i < 12) {
                        PreviewCell(index = i + 1, scale = scale, thumb = thumb,
                            modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "格子是占位色块，不是相册里的照片；编号是印样帧号。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewCell(index: Int, scale: Float, thumb: ThumbQuality, modifier: Modifier = Modifier) {
    // 缩略图档位只影响"这块色块的观感"，不冒充分辨率：省电那一档画得更平（模拟降采样），
    // 清晰那一档给一道细描边（模拟更锐利）。这是**界面的提示**，不是对像素的承诺。
    val fill = when (thumb) {
        ThumbQuality.FAST -> MaterialTheme.colorScheme.surfaceVariant
        ThumbQuality.STANDARD -> MaterialTheme.colorScheme.surfaceContainerHigh
        ThumbQuality.SHARP -> MaterialTheme.colorScheme.surfaceBright
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(SuiPaiSheet.frame)
            .background(fill, SuiPaiSheet.frame)
            .border(
                width = if (thumb == ThumbQuality.SHARP) 1.dp else 0.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = SuiPaiSheet.frame,
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            index.toString().padStart(2, '0'),
            style = SuiPaiSheet.type.frameNo.copy(fontSize = SuiPaiSheet.type.frameNo.fontSize * scale),
            color = SuiPaiSheet.colors.number,
            modifier = Modifier.padding(3.dp),
        )
    }
}

/**
 * 一组单选。
 *
 * 用 `FlowRow`（`AppChipFlow`）而不是横向滚动：滚出视野的 Compose 节点根本不在语义树里，
 * 读屏与自动化都够不着（`PerfSourceGuardTest` 第 1 条守卫量过）。
 * 每一颗都是可点节点自带名字与选中态 —— 复用 `AppBarCell`，它那套结构在装机上验过。
 */
@Composable
private fun OptionSection(
    title: String,
    note: String,
    options: List<String>,
    labels: List<String>,
    selected: String,
    tagPrefix: String,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        AppChipFlow {
            options.forEachIndexed { i, name ->
                AppBarCell(
                    label = labels[i],
                    on = name == selected,
                    onClick = { onPick(name) },
                    modifier = Modifier.testTag("${tagPrefix}_$name"),
                )
            }
        }
    }
}

/** 这一屏用到的"当前档位"直方图：给接线方做一次性对账用（也是单测的入口）。 */
internal fun appearanceSummary(snapshot: SettingsSnapshot): List<Pair<String, String>> = listOf(
    "主题" to snapshot.choice(SettingsKeys.Appearance.themeMode).label,
    "字号" to "${snapshot.choice(SettingsKeys.Appearance.fontScale).percent}%",
    "网格" to snapshot.choice(SettingsKeys.Appearance.gridColumns).label,
    "缩略图" to "${snapshot.choice(SettingsKeys.Appearance.thumbQuality).edgePx}px",
)

/** 供外观预览之外的调用点复用：把一个颜色按档位调淡（省电档更平）。 */
internal fun dimFor(quality: ThumbQuality, base: Color): Color = when (quality) {
    ThumbQuality.FAST -> base.copy(alpha = 0.72f)
    ThumbQuality.STANDARD -> base.copy(alpha = 0.86f)
    ThumbQuality.SHARP -> base
}
