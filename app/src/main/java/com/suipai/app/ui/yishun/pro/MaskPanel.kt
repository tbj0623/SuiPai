package com.suipai.app.ui.yishun.pro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.RadialMask

/**
 * 局部调整面板：最多 4 个蒙版，每个 线性/径向 + 四项基础调整 + 羽化/反向/删除。
 *
 * 版式：一行一个蒙版（**不用横向滚动**，`noHorizontalScrollOnActionRows` 那条守卫禁止），
 * 选中哪个就在下面展开它的滑杆。一屏永远只有 4 项调整 + 2 项形状，字级 2.0 也放得下。
 *
 * 坐标全部归一化（0..1 相对工作缓冲），所以预览上调的位置就是成片上的位置，
 * 这里不做任何分辨率换算 —— 这是 `LocalMask` 的设计前提，见其类注释。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProMaskSheet(
    masks: List<LocalMask>,
    onMasks: (List<LocalMask>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前展开编辑的蒙版下标。-1 = 没选中任何蒙版（只显示列表）。
    var selected by remember { mutableIntStateOf(if (masks.isEmpty()) -1 else 0) }
    // 删完之后下标可能越界，跟随列表长度收一次。
    val safeSelected = selected.coerceIn(-1, masks.lastIndex)

    AppSheet(title = "局部调整", tag = "yishun_sheet_mask", onDismiss = onDismiss) {
        AppSheetSection(
            title = "蒙版",
            note = MaskModel.summary(masks) + " · 最多 " + MaskModel.MAX_MASKS + " 个",
        )
        // 已有蒙版：一行一个，点开编辑。
        masks.forEachIndexed { index, mask ->
            MaskRow(
                number = index + 1,
                mask = mask,
                expanded = index == safeSelected,
                onOpen = { selected = if (safeSelected == index) -1 else index },
                onDelete = {
                    onMasks(MaskModel.remove(masks, index))
                    selected = -1
                },
            )
        }
        if (masks.isEmpty()) {
            Text(
                "还没有蒙版。下面新建一个，然后拖滑杆决定它改哪里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        AppSheetSection(
            title = "新建",
            note = if (masks.size >= MaskModel.MAX_MASKS) "已经到 4 个上限，先删一个" else "线性 = 一条渐变带，径向 = 一个椭圆",
        )
        AppChipFlow {
            MaskModel.Kind.entries.forEach { kind ->
                AppBarCell(
                    label = kind.label,
                    on = false,
                    // 满 4 个时按灰：引擎会静默截断第 5 个，用户拖完才发现没生效。
                    enabled = masks.size < MaskModel.MAX_MASKS,
                    modifier = Modifier.testTag("yishun_mask_add_" + kind.name.lowercase()),
                    onClick = {
                        val next = MaskModel.add(masks, kind)
                        onMasks(next)
                        selected = next.lastIndex
                    },
                )
            }
        }

        val current = masks.getOrNull(safeSelected)
        if (current != null) {
            when (val shape = current.shape) {
                is LinearMask -> LinearShapeControls(
                    maskNumber = safeSelected + 1,
                    shape = shape,
                    onChange = { next -> onMasks(MaskModel.replace(masks, safeSelected, MaskModel.withShape(current, next))) },
                )

                is RadialMask -> RadialShapeControls(
                    maskNumber = safeSelected + 1,
                    shape = shape,
                    onChange = { next -> onMasks(MaskModel.replace(masks, safeSelected, MaskModel.withShape(current, next))) },
                )
            }
            AdjustControls(
                maskNumber = safeSelected + 1,
                adjust = current.adjust,
                onChange = { next -> onMasks(MaskModel.replace(masks, safeSelected, MaskModel.withAdjust(current, next))) },
            )
        }
    }
}

/** 列表里的一行：序号 + 形状名 + （展开时）删掉。 */
@Composable
private fun MaskRow(
    number: Int,
    mask: LocalMask,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val kind = MaskModel.kindOf(mask)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppBarCell(
            label = "蒙版$number · ${kind.label}",
            on = expanded,
            modifier = Modifier.testTag("yishun_mask_row_" + (number - 1)),
            onClick = onOpen,
        )
        MaskPreview(mask)
        AppBarCell(
            label = "删除",
            on = false,
            modifier = Modifier.testTag("yishun_mask_del_" + (number - 1)),
            onClick = onDelete,
        )
    }
}

/**
 * 形状缩略图：一个 16:10 的小方块 + 一条渐变线 / 一个椭圆。
 *
 * 为什么值得画：4 个蒙版全是"线性 / 径向"两个词时，用户分不清哪个是哪个。
 * 这里只画**形状本身**，不画权重分布（那需要 1 张图的空间，抽屉里没有）。
 */
@Composable
private fun MaskPreview(mask: LocalMask) {
    val stroke = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    Canvas(
        Modifier
            .size(width = MASK_PREVIEW_WIDTH, height = MASK_PREVIEW_HEIGHT)
            .background(Color.Black.copy(alpha = 0.35f), MASK_PREVIEW_SHAPE)
            .border(1.dp, MaterialTheme.colorScheme.outline, MASK_PREVIEW_SHAPE),
    ) {
        val w = size.width
        val h = size.height
        when (val shape = mask.shape) {
            is LinearMask -> {
                // 画三条等距的平行线表示渐变带：方向与 angle 一致（0° = 从左到右）。
                val rad = shape.angle * (Math.PI / 180f).toFloat()
                val dx = kotlin.math.cos(rad)
                val dy = kotlin.math.sin(rad)
                for (k in 0..2) {
                    val t = 0.25f + k * 0.25f
                    val cx = w * 0.5f + (t - 0.5f) * w * dx
                    val cy = h * 0.5f + (t - 0.5f) * h * dy
                    val half = h * 0.5f
                    drawLine(
                        stroke.copy(alpha = if (k == 1) 0.9f else 0.45f),
                        Offset(cx - dy * half, cy + dx * half),
                        Offset(cx + dy * half, cy - dx * half),
                        strokeWidth = MASK_ROW_WIDTH.toPx(),
                    )
                }
            }

            is RadialMask -> {
                drawOval(
                    color = stroke,
                    topLeft = Offset(w * (shape.cx - shape.rx), h * (shape.cy - shape.ry)),
                    size = Size(w * shape.rx * 2f, h * shape.ry * 2f),
                    style = Stroke(width = MASK_ROW_WIDTH.toPx(), pathEffect = if (shape.inverted) dashed else null),
                )
                drawCircle(stroke, radius = 1.5f, center = Offset(w * shape.cx, h * shape.cy))
            }
        }
    }
}

/** 线性：角度 + 起止位置。 */
@Composable
private fun LinearShapeControls(maskNumber: Int, shape: LinearMask, onChange: (LinearMask) -> Unit) {
    AppSheetSection(title = "蒙版$maskNumber 形状", note = "角度 0° = 从左到右，90° = 从上到下")
    ShapeKnob(
        caption = "蒙版$maskNumber 角度 ${shape.angle.toInt()}°",
        value = shape.angle,
        range = 0f..360f,
        tag = "yishun_mask_angle",
    ) { v -> onChange(MaskModel.linear(v, shape.start, shape.end)) }
    ShapeKnob(
        caption = "蒙版$maskNumber 起点 ${pct(shape.start)}",
        value = shape.start,
        range = 0f..1f,
        tag = "yishun_mask_start",
    ) { v -> onChange(MaskModel.linear(shape.angle, v, shape.end)) }
    ShapeKnob(
        caption = "蒙版$maskNumber 终点 ${pct(shape.end)}",
        value = shape.end,
        range = 0f..1f,
        tag = "yishun_mask_end",
    ) { v -> onChange(MaskModel.linear(shape.angle, shape.start, v)) }
}

/** 径向：位置 + 半径 + 羽化 + 反向。 */
@Composable
private fun RadialShapeControls(maskNumber: Int, shape: RadialMask, onChange: (RadialMask) -> Unit) {
    AppSheetSection(title = "蒙版$maskNumber 形状", note = "位置与半径都是相对画面的比例")
    ShapeKnob("蒙版$maskNumber 横向位置 ${pct(shape.cx)}", shape.cx, 0f..1f, "yishun_mask_cx") {
        onChange(MaskModel.radial(it, shape.cy, shape.rx, shape.ry, shape.feather, shape.inverted))
    }
    ShapeKnob("蒙版$maskNumber 纵向位置 ${pct(shape.cy)}", shape.cy, 0f..1f, "yishun_mask_cy") {
        onChange(MaskModel.radial(shape.cx, it, shape.rx, shape.ry, shape.feather, shape.inverted))
    }
    ShapeKnob("蒙版$maskNumber 横向半径 ${pct(shape.rx)}", shape.rx, MaskModel.MIN_RADIUS..1f, "yishun_mask_rx") {
        onChange(MaskModel.radial(shape.cx, shape.cy, it, shape.ry, shape.feather, shape.inverted))
    }
    ShapeKnob("蒙版$maskNumber 纵向半径 ${pct(shape.ry)}", shape.ry, MaskModel.MIN_RADIUS..1f, "yishun_mask_ry") {
        onChange(MaskModel.radial(shape.cx, shape.cy, shape.rx, it, shape.feather, shape.inverted))
    }
    ShapeKnob("蒙版$maskNumber 羽化 ${pct(shape.feather)}", shape.feather, 0f..1f, "yishun_mask_feather") {
        onChange(MaskModel.radial(shape.cx, shape.cy, shape.rx, shape.ry, it, shape.inverted))
    }
    AppChipFlow {
        AppBarCell(
            label = if (shape.inverted) "反向开" else "反向",
            on = shape.inverted,
            modifier = Modifier.testTag("yishun_mask_invert"),
            onClick = {
                onChange(MaskModel.radial(shape.cx, shape.cy, shape.rx, shape.ry, shape.feather, !shape.inverted))
            },
        )
    }
}

/** 四项基础调整。 */
@Composable
private fun AdjustControls(maskNumber: Int, adjust: LocalAdjust, onChange: (LocalAdjust) -> Unit) {
    AppSheetSection(
        title = "蒙版$maskNumber 调整",
        note = if (adjust.isNeutral) MaskAdjustChannel.neutralHint() else "只在蒙版权重 > 0 的地方生效",
    )
    MaskAdjustChannel.entries.forEach { ch ->
        val caption = ch.caption(maskNumber, adjust)
        val v = ch.get(adjust)
        Text(
            caption + " " + pct(v),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Slider(
            value = v,
            onValueChange = { next ->
                onChange(ch.with(adjust, extraClamp(next)))
            },
            valueRange = -1f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = caption }
                .testTag("yishun_mask_" + ch.name.lowercase()),
        )
    }
}

/** 滑杆与引擎值域同宽，这里只挡 NaN —— 界面侧不再多一层夹取，免得与 `MaskModel.adjust` 分叉。 */
private fun extraClamp(v: Float): Float = if (v.isNaN()) 0f else v

/** 一根"形状参数"滑杆：把 caption/range/tag 收成一处，免得四段复制粘贴各写一份。 */
@Composable
private fun ShapeKnob(
    caption: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onChange: (Float) -> Unit,
) {
    Text(
        caption,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    Slider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = caption }
            .testTag(tag),
    )
}

/** 归一化值 → 百分数文案（与 `YishunScreen` 里 `(x * 100).roundToInt()` 同一口径）。 */
private fun pct(v: Float): String = "${(v * 100).toInt()}%"

/** 形状缩略图的尺寸与线宽：两个面板共用一份，免得排版对不齐。 */
private val MASK_PREVIEW_WIDTH: Dp = 96.dp
private val MASK_PREVIEW_HEIGHT: Dp = 64.dp
private val MASK_PREVIEW_SHAPE = RoundedCornerShape(2.dp)
private val MASK_ROW_WIDTH: Dp = 1.dp

/** 给空状态用的一行提示（列表为空时下面没有可展开的段落，保持排版不塌）。 */
@Composable
fun MaskEmptyHint(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
