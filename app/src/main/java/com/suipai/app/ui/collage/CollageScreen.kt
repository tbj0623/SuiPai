package com.suipai.app.ui.collage

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拼图排版屏。
 *
 * 参数是**数据 + 回调**：这一屏不认识 `MediaRepository`，也不知道导航。
 * 选中的照片从外面来，导出的 Bitmap 交给 `onExport` 去落盘 —— 这样
 * "从相册多选进来"和"从查看器进来"两条路共用同一套落盘口径（`MediaRepository.saveBitmap`）。
 *
 * 版面从上到下：
 * 1. 工具条：返回 / 张数与模板 / 长边 / 画幅 / **导出**
 * 2. 模板横条（`LazyRow`，不是 `horizontalScroll`：滚出视野的节点不在语义树里，
 *    读屏与自动化都够不着 —— 这是 `PerfSourceGuardTest.noHorizontalScrollOnActionRows` 钉住的那条）
 * 3. 预览区：长按某格起手，拖到另一格上交换
 * 4. 底部参数行：边框 / 边距 / 圆角 / 底色
 *
 * 预览与导出共用 `CollageRenderer.cellRects` 那一份几何，所以"预览长这样、导出就是长这样"
 * 不是靠肉眼对齐的。
 */
@Composable
fun CollageScreen(
    photos: List<PhotoItem>,
    onBack: () -> Unit,
    onExport: (Bitmap) -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    // 上限 9 张是模板表的边界：第 10 张没有格子放，宁可在这里截断也不让导出少一张还不说。
    val picked = remember(photos) { photos.take(9) }
    // 存的是**模板 id** 而不是 CollageLayout：后者不是 Parcelable，塞进 rememberSaveable
    // 会在"转屏/进程被杀后恢复"那一刻抛 IllegalArgumentException（存不进去）。
    var layoutId by rememberSaveable { mutableStateOf(CollageLayouts.defaultFor(picked.size).id) }
    val layout = remember(layoutId) { CollageLayouts.byId(layoutId) }
    var borderDp by rememberSaveable { mutableFloatStateOf(6f) }
    var marginDp by rememberSaveable { mutableFloatStateOf(12f) }
    var radiusDp by rememberSaveable { mutableFloatStateOf(0f) }
    var aspect by rememberSaveable { mutableFloatStateOf(1f) }
    var longEdge by rememberSaveable { mutableIntStateOf(CollageRenderer.DEFAULT_LONG_EDGE) }
    var backgroundIndex by rememberSaveable { mutableIntStateOf(0) }
    // 装裱状态。存的是**序号**不是枚举：`rememberSaveable` 只认 Bundle 存得下的类型，
    // 枚举要自己写 Saver；而"改表之后旧序号不能把界面卡死"由 `FrameStyle.byOrdinal` 兜。
    var frameOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var leakPct by rememberSaveable { mutableIntStateOf(0) }
    var leakCorner by rememberSaveable { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 格位 → photos 的下标。拖拽换位改的是这张表，不是 photos 本身。
    var order by remember(layout.id, picked.size) { mutableStateOf(fillOrder(picked.size, layout.count)) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf(Offset.Zero) }

    val style = remember(borderDp, marginDp, radiusDp, aspect, backgroundIndex, frameOrdinal, leakPct, leakCorner) {
        CollageStyle(
            borderDp = borderDp,
            marginDp = marginDp,
            radiusDp = radiusDp,
            backgroundArgb = CollageBg[backgroundIndex % CollageBg.size].argb,
            aspect = aspect,
            frame = FrameStyle.byOrdinal(frameOrdinal),
            leak = leakPct / 100f,
            leakCorner = leakCorner,
        ).coerced()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                "${picked.size} 张 · ${layout.name}",
                style = SuiPaiSheet.type.frameNo,
                color = colors.inkSecondary,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppBarCell(
                label = if (longEdge >= 4096) "长边 4K" else "长边 2K",
                on = longEdge >= 4096,
                onClick = { longEdge = if (longEdge >= 4096) 2048 else 4096 },
            )
            AppBarCell(
                label = if (aspect > 1.2f) "横幅" else if (aspect < 0.9f) "竖幅" else "方形",
                on = aspect != 1f,
                onClick = {
                    // 三档：方 → 竖 → 横 → 方。回合里的每一档都要能点到，所以是 3 的模。
                    aspect = when {
                        aspect >= 1.99f -> 1f
                        aspect <= 1.01f -> 0.8f
                        else -> 2f
                    }
                },
            )
            AppBarPrimary(
                label = if (exporting) "导出中" else "导出",
                enabled = !exporting && picked.size >= 2,
                modifier = Modifier.testTag("collage_export"),
                onClick = {
                    if (exporting) return@AppBarPrimary
                    exporting = true
                    notice = null
                    val snapshot = order
                    val target = layout
                    val st = style
                    val edge = longEdge
                    val ordered = snapshot.mapNotNull { i -> picked.getOrNull(i) }
                    scope.launch {
                        val bitmap = withContext(Dispatchers.Default) {
                            CollageRenderer.renderPhotos(
                                context = context,
                                photos = ordered,
                                layout = target,
                                style = st,
                                density = density,
                                longEdge = edge,
                            )
                        }
                        exporting = false
                        if (bitmap == null) {
                            notice = "这几张读不出来，换几张再试"
                        } else {
                            onExport(bitmap)
                        }
                    }
                },
            )
        }

        val hint = notice ?: note
        if (hint != null) {
            Text(
                hint,
                style = SuiPaiType.bodySmall,
                color = if (notice != null) colors.fixer else colors.inkSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (picked.size < 2) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "拼图至少要 2 张，回去多选几张再来",
                    color = colors.inkSecondary,
                    style = SuiPaiType.bodyMedium,
                )
            }
            AppBarRow(Modifier.padding(horizontal = 8.dp)) {
                AppBarCell(label = "返回相册", on = false, onClick = onBack)
            }
            return@Column
        }

        CollageTemplateStrip(
            current = layout,
            count = picked.size,
            onPick = { next ->
                layoutId = next.id
                order = fillOrder(picked.size, next.count)
            },
        )

        CollagePreview(
            photos = picked,
            layout = layout,
            style = style,
            aspect = aspect,
            order = order,
            dragging = dragging,
            dragTo = dragTo,
            onDragStart = { slot, at ->
                dragging = slot
                dragTo = at
            },
            onDrag = { at -> dragTo = at },
            onDragEnd = {
                val from = dragging
                dragging = null
                if (from != null) {
                    val to = layout.hit(dragTo.x, dragTo.y)
                    if (to >= 0 && to != from && from < order.size && to < order.size) {
                        val next = order.toMutableList()
                        val tmp = next[from]
                        next[from] = next[to]
                        next[to] = tmp
                        order = next
                    }
                }
            },
            onDragCancel = { dragging = null },
        )

        AppBarRow(Modifier.padding(horizontal = 8.dp)) {
            AppBarCell(
                label = "边框 ${borderDp.toInt()}",
                on = borderDp > 0f,
                onClick = { borderDp = nextStep(borderDp, BorderSteps) },
            )
            AppBarCell(
                label = "边距 ${marginDp.toInt()}",
                on = marginDp > 0f,
                onClick = { marginDp = nextStep(marginDp, MarginSteps) },
            )
            AppBarCell(
                label = "圆角 ${radiusDp.toInt()}",
                on = radiusDp > 0f,
                onClick = { radiusDp = nextStep(radiusDp, RadiusSteps) },
            )
            AppBarCell(
                label = CollageBg[backgroundIndex % CollageBg.size].name,
                on = backgroundIndex % CollageBg.size != 0,
                onClick = { backgroundIndex = (backgroundIndex + 1) % CollageBg.size },
            )
        }
        // 装裱那一行：边框样式 / 漏光强度 / 漏光角。
        // 单独一行而不是挤进上面：上面四格是"版面几何"，这两格是"装裱"——
        // 混在一行里会让用户以为边框和边距是同一类东西（一个是格子缝，一个是片子边）。
        AppBarRow(Modifier.padding(horizontal = 8.dp)) {
            val frameNow = FrameStyle.byOrdinal(frameOrdinal)
            AppBarCell(
                label = frameNow.label,
                on = frameNow != FrameStyle.NONE,
                modifier = Modifier.testTag("collage_frame"),
                onClick = {
                    frameOrdinal = frameNow.next().ordinal
                    // 换到任何一种真边框时把外边距收到 0：拍立得的白卡外面再留一条底色缝，
                    // 看起来像"卡纸贴在黑板上"，那不是拍立得。
                    if (frameNow.next() != FrameStyle.NONE && marginDp > 0f) marginDp = 0f
                },
            )
            AppBarCell(
                label = if (leakPct == 0) "无漏光" else "漏光 $leakPct",
                on = leakPct > 0,
                modifier = Modifier.testTag("collage_leak"),
                onClick = {
                    leakPct = nextStep(leakPct.toFloat(), LeakSteps).toInt()
                },
            )
            if (leakPct > 0) {
                AppBarCell(
                    label = LeakCornerNames[leakCorner.mod(4)],
                    on = false,
                    modifier = Modifier.testTag("collage_leak_corner"),
                    onClick = { leakCorner = (leakCorner + 1).mod(4) },
                )
            }
        }
        AppBarText(
            "长按一格拖到另一格上换位 · 导出长边 ${longEdge}px",
            style = SuiPaiType.marker,
            color = colors.inkTertiary,
            layout = Modifier.fillMaxWidth(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/** 模板横条：`LazyRow`。放不下的模板灰掉而不是藏起来 —— 灰着至少能听见"为什么"。 */
@Composable
private fun CollageTemplateStrip(
    current: CollageLayout,
    count: Int,
    onPick: (CollageLayout) -> Unit,
) {
    val colors = SuiPaiColor.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag("collage_templates"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(CollageLayouts.all, key = { it.id }) { item ->
            val on = item.id == current.id
            val holds = item.holds(count)
            Box(
                Modifier
                    .size(width = 84.dp, height = 56.dp)
                    .clickable(
                        enabled = holds,
                        role = Role.Button,
                        onClickLabel = "用「${item.name}」，${item.fitLabel(count)}",
                        onClick = { onPick(item) },
                    )
                    .clearAndSetSemantics {
                        contentDescription = "模板 ${item.name}，${item.count} 格" +
                            if (on) "，已选中" else ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .size(width = 76.dp, height = 50.dp)
                        .border(
                            1.dp,
                            if (on) colors.safelight else colors.outline,
                            SuiPaiShapes.frame,
                        )
                        .background(
                            if (on) colors.safelightWash else Color.Transparent,
                            SuiPaiShapes.frame,
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniLayout(item, on, holds)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.name.substringAfter('·'),
                        style = SuiPaiType.marker,
                        color = when {
                            !holds -> colors.inkTertiary
                            on -> colors.safelight
                            else -> colors.inkSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 微缩版式：拿同一份 `slots` 画，跟预览、跟导出同一个几何来源。 */
@Composable
private fun MiniLayout(layout: CollageLayout, on: Boolean, holds: Boolean) {
    val colors = SuiPaiColor.current
    Canvas(
        Modifier.size(width = 36.dp, height = 24.dp),
    ) {
        val gap = size.minDimension * 0.05f
        val tint = if (on) colors.safelight else if (holds) colors.inkSecondary else colors.inkTertiary
        for (slot in layout.slots) {
            drawRect(
                color = tint,
                topLeft = Offset(slot.x * size.width + gap, slot.y * size.height + gap),
                size = Size(
                    (slot.w * size.width - gap * 2f).coerceAtLeast(1f),
                    (slot.h * size.height - gap * 2f).coerceAtLeast(1f),
                ),
                style = Stroke(width = 1.2f),
            )
        }
    }
}

/**
 * 预览区。
 *
 * 拖拽用 `detectDragGesturesAfterLongPress`（`detectDragGestures` 那一族）：
 * **必须长按起手**，否则它会和"点一下选中某格"抢同一个手势。
 */
@Composable
private fun CollagePreview(
    photos: List<PhotoItem>,
    layout: CollageLayout,
    style: CollageStyle,
    aspect: Float,
    order: List<Int>,
    dragging: Int?,
    dragTo: Offset,
    onDragStart: (Int, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .aspectRatio(aspect.coerceIn(0.5f, 2f))
            .background(Color(style.backgroundArgb), SuiPaiShapes.frame)
            .border(1.dp, colors.hairline, SuiPaiShapes.frame)
            .pointerInput(layout.id, aspect, style, order) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        val nx = (at.x / size.width).coerceIn(0f, 0.9999f)
                        val ny = (at.y / size.height).coerceIn(0f, 0.9999f)
                        val slot = layout.hit(nx, ny)
                        if (slot >= 0) onDragStart(slot, Offset(nx, ny))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(
                            Offset(
                                (change.position.x / size.width).coerceIn(0f, 0.9999f),
                                (change.position.y / size.height).coerceIn(0f, 0.9999f),
                            ),
                        )
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .testTag("collage_preview"),
    ) {
        // 画布宽度这一刻才知道：模板是归一化的 0..1，乘上它就得到 dp。
        val canvasW: Dp = maxWidth
        val density = LocalDensity.current.density
        val cells = previewCells(layout, style, canvasW, density)
        for ((slotIndex, cell) in cells.withIndex()) {
            val photoIndex = order.getOrNull(slotIndex) ?: continue
            val photo = photos.getOrNull(photoIndex) ?: continue
            if (cell.isEmpty) continue
            val isDrag = dragging == slotIndex
            key(photo.id, slotIndex, layout.id) {
                val targetEdge = cell.pxWidth.coerceAtLeast(64)
                val request = remember(photo.id, targetEdge) {
                    ImageRequest.Builder(context)
                        .data(photo.uri)
                        // 按格子像素数取缩略图：预览只要看得清，别把 4000px 的原图拖进来。
                        .size(targetEdge)
                        .tag(FastThumb(targetEdge))
                        .crossfade(false)
                        .build()
                }
                val painter = rememberAsyncImagePainter(model = request)
                Box(
                    Modifier
                        .padding(start = cell.x, top = cell.y)
                        .size(width = cell.w, height = cell.h)
                        .background(Color(style.backgroundArgb))
                        .semantics {
                            contentDescription = buildString {
                                append("第 ")
                                append(slotIndex + 1)
                                append(" 格，")
                                append(photo.displayName)
                                if (isDrag) append("，正在拖动")
                            }
                        }
                        .then(
                            if (isDrag) Modifier.border(2.dp, colors.safelight, SuiPaiShapes.frame)
                            else Modifier,
                        ),
                ) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (painter.state is AsyncImagePainter.State.Error) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("无法显示", style = SuiPaiType.marker, color = colors.inkTertiary)
                        }
                    }
                }
            }
        }
        // 格线画在最上层：图片会盖住描边，而"这一格在哪"是拖拽唯一的线索。
        Canvas(Modifier.fillMaxSize()) {
            val margin = style.marginDp * density
            val border = style.borderDp * density
            for (rect in CollageRenderer.cellRects(
                layout,
                size.width.toInt(),
                size.height.toInt(),
                margin,
                border,
            )) {
                if (rect.isEmpty) continue
                drawRect(
                    color = colors.hairline,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 1f),
                )
            }
            if (dragging != null) {
                drawCircle(
                    color = colors.safelight,
                    radius = 14f,
                    center = Offset(dragTo.x * size.width, dragTo.y * size.height),
                    style = Stroke(width = 2.5f),
                )
            }
        }
        // 装裱预览：**几何来源与成片同一份** —— `CollagePixels.frameMarks` 按成片像素算，
        // 这里等比缩到预览尺寸再画。不这么做的话"预览好看、导出难看"只能靠肉眼对齐，
        // 而肉眼对齐的东西下一轮就会分叉（本项目已经栽过三次）。
        if (style.hasAdornments) {
            AdornmentOverlay(style)
        }
    }
}

/**
 * 把 [CollageStyle] 的边框与漏光按**成片几何**缩到预览尺寸画一遍。
 *
 * 单独一个 composable 而不是写进上面那个 `Canvas`：那个 Canvas 画的是"格子在哪"（拖拽线索），
 * 这个画的是"片子长什么样"（观感）。两者的重绘时机与语义都不同，混在一起会互相拖累。
 */
@Composable
private fun AdornmentOverlay(style: CollageStyle) {
    val (outW, outH) = remember(style) { CollageRenderer.outputSize(style) }
    val marks = remember(style, outW, outH) { CollagePixels.frameMarks(outW, outH, style) }
    val leak = style.leak.coerceIn(0f, 1f)
    val corner = style.leakCorner.mod(4)
    Canvas(Modifier.fillMaxSize()) {
        val pvW = size.width
        val pvH = size.height
        if (pvW <= 0f || pvH <= 0f) return@Canvas
        val sx = pvW / outW
        val sy = pvH / outH
        if (leak > 0f) {
            val cx = if (corner == 1 || corner == 3) pvW else 0f
            val cy = if (corner == 2 || corner == 3) pvH else 0f
            val radius = minOf(pvW, pvH) * CollagePixels.leakRadiusFactor
            val peak = leak * CollagePixels.leakMaxAlpha
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to LeakTint.copy(alpha = peak),
                        0.45f to LeakTint.copy(alpha = peak * 0.3f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = radius.coerceAtLeast(1f),
                ),
            )
        }
        for (mark in marks) {
            if (mark.isEmpty) continue
            drawRect(
                color = Color(mark.argb),
                topLeft = Offset(mark.left * sx, mark.top * sy),
                size = Size(
                    (mark.width * sx).coerceAtLeast(0.5f),
                    (mark.height * sy).coerceAtLeast(0.5f),
                ),
            )
        }
    }
}

/**
 * 预览里漏光的那支橙。
 *
 * **直接取像素层那一支**（`CollagePixels.LEAK_RGB`）：两处各写一个字面量迟早分叉，
 * 而"预览的橙和成片的橙不一样"是装机才看得出来、diff 里完全正常的那种缺陷。
 */
internal val LeakTint = Color(CollagePixels.LEAK_RGB)

/** 预览用的一格：dp 盒（相对画布左上角）+ 它该解码的像素宽。 */
private data class PreviewCell(
    val x: Dp,
    val y: Dp,
    val w: Dp,
    val h: Dp,
    val pxWidth: Int,
) {
    val isEmpty: Boolean get() = w.value <= 0.5f || h.value <= 0.5f
}

/**
 * 把归一化模板算成预览用的 dp 盒。
 *
 * 缝与边距按**画布宽度的比例**折算（预览画布就是屏幕宽，几 dp 的绝对误差看不出来），
 * 但格子取图时的像素数按真实 density 算 —— 那是真实的解码尺寸。
 */
private fun previewCells(
    layout: CollageLayout,
    style: CollageStyle,
    canvasWidth: Dp,
    density: Float,
): List<PreviewCell> {
    val w = canvasWidth.value
    if (w <= 0f) return emptyList()
    val margin = style.marginDp / w
    val half = (style.borderDp / w) / 2f
    val innerW = 1f - margin * 2f
    val innerH = 1f - margin * 2f
    return layout.slots.map { slot ->
        val l = margin + slot.x * innerW + half
        val t = margin + slot.y * innerH + half
        val r = margin + slot.right * innerW - half
        val b = margin + slot.bottom * innerH - half
        val cw = (r - l).coerceAtLeast(0f)
        val ch = (b - t).coerceAtLeast(0f)
        PreviewCell(
            x = (l * w).dp,
            y = (t * w).dp,
            w = (cw * w).dp,
            h = (ch * w).dp,
            pxWidth = (cw * w * density).toInt(),
        )
    }
}

/** 底色档位。第一个是"默认"（暗房底），所以 `on =` 的判据是"没停在第一个"。 */
private data class CollageBgColor(val name: String, val argb: Int)

private val CollageBg = listOf(
    CollageBgColor("黑底", 0xFF111111.toInt()),
    CollageBgColor("白底", 0xFFF5F3EE.toInt()),
    CollageBgColor("灰底", 0xFF8A8A8A.toInt()),
    CollageBgColor("棕底", 0xFF3A2B22.toInt()),
)

private val BorderSteps = floatArrayOf(0f, 3f, 6f, 12f, 24f)
private val MarginSteps = floatArrayOf(0f, 8f, 12f, 24f, 48f)
private val RadiusSteps = floatArrayOf(0f, 6f, 12f, 24f)
/** 漏光五档：0 = 关（逐像素不变），40 = 刚看得见，100 = 最重。 */
private val LeakSteps = floatArrayOf(0f, 20f, 40f, 70f, 100f)

/** 漏光角的名字。与 `CollagePixels.leakAlphaAt` 的 corner 编号同序。 */
private val LeakCornerNames = listOf("左上", "右上", "左下", "右下")

/** 循环格的下一档。不在表里的值归到第一档（模板表以后加档也不会把界面卡死）。 */
private fun nextStep(current: Float, steps: FloatArray): Float {
    val i = steps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return if (i < 0) steps.first() else steps[(i + 1) % steps.size]
}

/**
 * 格位表：第 i 格放第几张。
 *
 * 照片比格子多时只用前 `slotCount` 张（`holds` 已经在模板横条上把这种模板灰掉了，
 * 这里是第二道闸）；比格子少时**循环补**（第 k 格放 `k % photoCount` 张）——
 * 空着不画会被用户当成导出坏了。
 */
internal fun fillOrder(photoCount: Int, slotCount: Int): List<Int> {
    if (photoCount <= 0 || slotCount <= 0) return emptyList()
    return List(slotCount) { i -> if (i < photoCount) i else i % photoCount }
}
