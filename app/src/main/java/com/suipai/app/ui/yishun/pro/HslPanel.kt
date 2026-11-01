package com.suipai.app.ui.yishun.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.core.engine.HslBands

/**
 * HSL 分色面板：8 色带 × (色相 / 饱和 / 明度)。
 *
 * 版式取舍（都是踩过的）：
 * - **不用横向滚动**：`PerfSourceGuardTest.noHorizontalScrollOnActionRows` 明令禁止
 *   `horizontalScroll(`，理由是滚出视野的节点不在语义树里（读屏与自动化都够不到）。
 *   这里用「先选色带、再上下排三根滑杆」：一屏永远只有 3 根滑杆，字级 2.0 也放得下；
 * - **色带选择器用 `AppChipFlow`（内部是 FlowRow）**：8 格自动换行，比 8 列横排更抗大字号；
 * - **每根滑杆自己带 `contentDescription`**：material3 的滑杆组件只有写在**调用方
 *   modifier** 上的语义才会落到 `sliderSemantics` 同一个节点（Slider.kt:686-695），
 *   不写的话读屏只报「滑动条」。守卫 `A11ySourceGuardTest.everySliderHasItsOwnName`
 *   是按字面量扫调用点括号区间的（注释不参与裁剪，所以这里不写那个调用形状）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProHslSheet(
    bands: HslBands,
    onBands: (HslBands) -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前选中的色带。只影响"下面显示哪三根滑杆"，8 带数值本身各存各的。
    var selected by remember { mutableIntStateOf(HslBandKind.RED.index) }
    val kind = HslBandKind.at(selected)
    val band = HslModel.band(bands, kind)

    AppSheet(title = "分色 HSL", tag = "yishun_sheet_hsl", onDismiss = onDismiss) {
        AppSheetSection(
            title = "色带",
            note = HslModel.summary(bands),
        )
        AppChipFlow {
            HslBandKind.entries.forEach { k ->
                AppBarCell(
                    label = k.label,
                    on = k.index == selected,
                    modifier = Modifier.testTag("yishun_hsl_band_" + k.index),
                    onClick = { selected = k.index },
                )
            }
        }

        AppSheetSection(
            title = "${kind.label} 带",
            note = "只动这一族的色相；带与带之间是余弦交叠，渐变处不会出断层",
        )
        HslChannel.entries.forEach { ch ->
            val caption = ch.caption(kind, band)
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = ch.get(band),
                onValueChange = { v -> onBands(HslModel.withValue(bands, kind, ch, v)) },
                valueRange = ch.range,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = caption }
                    .testTag("yishun_hsl_" + kind.name.lowercase() + "_" + ch.name.lowercase()),
            )
        }

        AppSheetSection(title = "重置")
        AppChipFlow {
            AppBarCell(
                label = "清带",
                on = false,
                enabled = band.hasAny,
                modifier = Modifier.testTag("yishun_hsl_clear_band"),
                onClick = { onBands(HslModel.clearBand(bands, kind)) },
            )
            AppBarCell(
                label = "清全部",
                on = false,
                enabled = !bands.isNeutral,
                onClick = { onBands(HslModel.clearAll()) },
            )
        }
        BandLegend(kind)
        Text(
            if (bands.isNeutral) "8 个色带都是 0：画面不会有任何变化"
            else "已改 ${HslModel.touchedBands(bands)} 个带（应用后立即生效）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 一行色带图例：8 个色块 + 中心角。
 *
 * 用 `FlowRow` 而不是 `Row`：大字级时"品红 320°"那一格会被挤出屏幕
 * （`AppBarRow` 那套"宁可换行也不许消失"的教训）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandLegend(selected: HslBandKind) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HslBandKind.entries.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Color(k.color), CircleShape)
                        .border(
                            if (k == selected) 1.dp else 0.dp,
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape,
                        ),
                )
                Text(
                    k.label + " " + HUES_DEG[k.index] + "°",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (k == selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 带中心色相角，**只用于显示**。真值在 `HslBands.HUES`；
 * `ProPanelsTest` 逐项对账，写错一个数字会红。
 */
private val HUES_DEG = intArrayOf(0, 30, 60, 120, 180, 240, 280, 320)
