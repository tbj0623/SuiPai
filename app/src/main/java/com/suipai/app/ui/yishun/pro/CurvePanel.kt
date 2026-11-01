package com.suipai.app.ui.yishun.pro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves

/**
 * 曲线面板：一块 0..1 的正方形画布 + 通道切换 + 重置。
 *
 * 交互（与 `docs/product/PRO-EDITING.md` 一致）：
 * - **点空白**：加一个控制点（最多 [CurvePoints.MAX_POINTS] 个，满了不响应且下面那行小字会说）；
 * - **拖控制点**：移动（端点锁 x 轴，见 `CurvePoints.move`）；
 * - **长按控制点**：删掉它（至少留 2 个，端点不可删）。
 *
 * 为什么用手势而不是一排数值输入框：曲线是**空间**概念，用两个数字输入 x/y 编辑
 * 一条 S 曲线，用户得先在脑子里把它画出来。但反过来，手势不能是唯一的信息通道 ——
 * 图下面那行小字会实时播报"主通道 3 个控制点"，读屏用户至少知道当前形状。
 *
 * 状态全部由调用方持有（`curves` + `onCurves`），本文件不 remember 任何像素数据——
 * 与 `PerfSourceGuardTest.noWriteOnlyPreferenceKeys` 那条纪律同源：
 * 界面只负责画，真值只有一份。
 */
@Composable
fun ProCurveSheet(
    curves: ToneCurves,
    onCurves: (ToneCurves) -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前编辑的通道：只影响"这一块画布在编辑谁"，四条曲线本身各存各的。
    var channel by remember { mutableIntStateOf(CurveChannels.MASTER) }
    // 拖动中的点下标。-1 = 没在拖。它是**会话状态**，不落盘。
    var dragging by remember { mutableIntStateOf(-1) }
    val points = CurveChannels.points(curves, channel)

    fun commit(next: List<CurvePoint>) {
        onCurves(CurveChannels.withPoints(curves, channel, next))
    }

    AppSheet(title = "曲线", tag = "yishun_sheet_curve", onDismiss = onDismiss) {
        AppSheetSection(
            title = "通道",
            note = "主通道先动，再用 R/G/B 单独修色偏；四条各存各的",
        )
        AppChipFlow {
            for (ch in 0 until CurveChannels.count) {
                AppBarCell(
                    label = CurveChannels.label(ch),
                    on = ch == channel,
                    modifier = Modifier.testTag("yishun_curve_ch_" + CurveChannels.label(ch)),
                    onClick = { channel = ch },
                )
            }
        }

        AppSheetSection(
            title = "控制点 · " + channelName(channel),
            note = "共 ${points.size}/" + CurvePoints.MAX_POINTS + " 点 · 点空白加点 · 拖动改形状 · 长按删点",
        )
        CurveCanvas(
            points = points,
            color = Color(CurveChannels.colorOf(channel)),
            onAdd = { x, y ->
                val (next, idx) = CurvePoints.add(points, x, y)
                if (idx >= 0) commit(next)
            },
            onMove = { index, x, y -> commit(CurvePoints.move(points, index, x, y)) },
            onRemoveAt = { index -> commit(CurvePoints.remove(points, index)) },
            onDragIndex = { dragging = it },
            dragging = dragging,
        )

        AppSheetSection(title = "重置")
        AppChipFlow {
            AppBarCell(
                label = "清这条",
                on = false,
                // 这条通道本来就是直线时按灰：按下去什么都不变，看着像按钮坏了。
                enabled = CurveChannels.curve(curves, channel)?.isIdentity == false,
                modifier = Modifier.testTag("yishun_curve_clear"),
                onClick = { onCurves(CurveChannels.clearChannel(curves, channel)) },
            )
            AppBarCell(
                label = "清全部",
                on = false,
                enabled = !curves.isNeutral,
                onClick = { onCurves(CurveChannels.clearAll()) },
            )
        }
        Text(
            curveHint(curves),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 通道中文名，给上面那行小字与读屏共用一份（两处各写一遍迟早分叉）。 */
private fun channelName(channel: Int): String = when (channel) {
    CurveChannels.MASTER -> "主通道"
    CurveChannels.R -> "红通道"
    CurveChannels.G -> "绿通道"
    else -> "蓝通道"
}

/** 四条通道各自被改过没有，一句话说清（用户从别的面板回来时唯一的"我动过没有"线索）。 */
private fun curveHint(curves: ToneCurves): String {
    val touched = (0 until CurveChannels.count).filter {
        val c = CurveChannels.curve(curves, it)
        c != null && !c.isIdentity
    }
    if (touched.isEmpty()) return "四条通道都是直线：画面不会有任何变化"
    return "已改动：" + touched.joinToString("、") { CurveChannels.label(it) } + "（应用后立即生效）"
}

/**
 * 画布本体。
 *
 * 坐标约定：画布是正方形，归一化 (0,0) 在**左下**（暗部在左、亮部在上，与直方图同向），
 * (1,1) 在右上。所以屏幕 y 要翻转：`sy = (1 - y) * h`。
 *
 * 手势分成两个 `pointerInput`：tap 负责加点/删点，drag 负责移动。
 * drag 的起点必须落在某个控制点上才生效，否则"随手一划"会把最近的点拽走。
 */
@Composable
private fun CurveCanvas(
    points: List<CurvePoint>,
    color: Color,
    onAdd: (Float, Float) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onDragIndex: (Int) -> Unit,
    dragging: Int,
) {
    val grid = Color.White.copy(alpha = 0.16f)
    val frame = MaterialTheme.colorScheme.outline
    // 每次重组重算一条曲线：16 个控制点 × 64 个采样 = 1024 次求值，可忽略。
    val curve: ToneCurve? = remember(points) { CurvePoints.toCurve(points) }
    val spoken = "曲线画布，${points.size} 个控制点。" +
        if (dragging >= 0) "正在拖动第 ${dragging + 1} 个。" else ""
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .aspectRatio(1f)
            .border(1.dp, frame, RoundedCornerShape(2.dp))
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            .pointerInput(points) {
                detectTapGestures(
                    onTap = { offset ->
                        val p = norm(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        if (CurvePoints.hitTest(points, p.first, p.second) < 0) onAdd(p.first, p.second)
                    },
                    onLongPress = { offset ->
                        val p = norm(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        val hit = CurvePoints.hitTest(points, p.first, p.second)
                        if (hit >= 0) onRemoveAt(hit)
                    },
                )
            }
            .pointerInput(points) {
                var active = -1
                detectDragGestures(
                    onDragStart = { offset ->
                        val p = norm(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        active = CurvePoints.hitTest(points, p.first, p.second, radiusGap = 0.09f)
                        onDragIndex(active)
                    },
                    onDragEnd = {
                        active = -1
                        onDragIndex(-1)
                    },
                    onDragCancel = {
                        active = -1
                        onDragIndex(-1)
                    },
                    onDrag = { change, _ ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        val p = norm(
                            change.position.x,
                            change.position.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                        )
                        onMove(active, p.first, p.second)
                    },
                )
            }
            .semantics { contentDescription = spoken }
            .testTag("yishun_curve_canvas"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawGrid(grid)
            drawIdentity(Color.White.copy(alpha = 0.10f))
            drawCurve(curve, color)
            drawHandlePoints(points, color, dragging)
        }
    }
}

/** 屏幕坐标 → 归一化曲线坐标（y 翻转）。 */
private fun norm(x: Float, y: Float, w: Float, h: Float): Pair<Float, Float> {
    val nx = if (w <= 0f) 0f else (x / w).coerceIn(0f, 1f)
    val ny = if (h <= 0f) 0f else (1f - y / h).coerceIn(0f, 1f)
    return nx to ny
}

/** 四等分网格：0.25 / 0.5 / 0.75。曲线编辑器没有网格就看不出"点拖到哪了"。 */
private fun DrawScope.drawGrid(color: Color) {
    for (i in 1..3) {
        val t = i / 4f
        drawLine(color, Offset(t * size.width, 0f), Offset(t * size.width, size.height))
        drawLine(color, Offset(0f, t * size.height), Offset(size.width, t * size.height))
    }
}

private fun DrawScope.drawIdentity(color: Color) {
    drawLine(color, Offset(0f, size.height), Offset(size.width, 0f))
}

private fun DrawScope.drawCurve(curve: ToneCurve?, color: Color) {
    val n = 64
    var prev = Offset(0f, size.height)
    for (i in 1..n) {
        val x = i / n.toFloat()
        val y = curve?.apply(x) ?: x
        val cur = Offset(x * size.width, (1f - y) * size.height)
        drawLine(color, prev, cur, strokeWidth = 2f)
        prev = cur
    }
}

private fun DrawScope.drawHandlePoints(points: List<CurvePoint>, color: Color, dragging: Int) {
    points.forEachIndexed { i, p ->
        val c = Offset(p.x * size.width, (1f - p.y) * size.height)
        val r = if (i == dragging) 9f else 6f
        drawCircle(Color.Black.copy(alpha = 0.65f), radius = r + 2f, center = c)
        drawCircle(color, radius = r, center = c)
        if (i == dragging) drawCircle(Color.White, radius = r + 4f, center = c, style = Stroke(width = 2f))
    }
}
