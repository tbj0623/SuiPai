package com.suipai.app.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 可复用的质感 Modifier：本世界所有"手感"都收在这里。
 *
 * 收进来的理由和 `clipWrite` 那次一样：同一个效果如果允许各处现写，
 * 三处就会写成三个样子，而"到处都差一点点"正是这个 app 之前最大的问题
 * （每一项都是 2dp 方框，于是层级全平）。
 *
 * 使用顺序有讲究，逐个写在函数的 KDoc 里。
 *
 * ⚠ 这些函数的默认参数**不能**写成 `LocalSuiPaiColors.current.xxx`：
 * 默认参数在非 @Composable 的参数位置求值，读 CompositionLocal 会编译不过。
 * 所以颜色参数一律用 `Color.Unspecified` 当哨兵，真正取值在 `composed {}` 里做。
 */

/** 当前主题的整套 token。`composed {}` 与 @Composable 体内都从这里取。 */
private val sheetColors: SuiPaiColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSuiPaiColors.current

/** 圆角 → Shape 的便捷取用（避免各处 `RoundedCornerShape(2.dp)`）。 */
internal val InnerFrame: Shape get() = RoundedCornerShape(Radius.frame)

/** 直角 Shape（印样网格里"照片本身就是方的"）。 */
internal val BareCorners: Shape get() = RectangleShape

/**
 * 相纸边缘高光：照片是一张压在印样页上的相纸，它的**上沿与左沿**会吃光。
 *
 * 画法不是阴影（投影属于"浮起的卡片"，这个世界没有卡片），而是：
 * 一条 1dp 的浅色细线压在内侧上沿，一条更淡的压在左沿。
 * 强度跟主题走 —— 暗房里那道光很弱（相纸本身比底亮），日光下更弱（纸不会发光）。
 *
 * 用法（`paperEdge` 自己会 clip，所以直接用；不要叠第二个 clip）：
 * ```
 * Box(
 *     Modifier
 *         .paperEdge()
 *         .background(color, SuiPaiShapes.frame)
 * )
 * ```
 *
 * @param alpha 高光强度。0 等于不画。
 * @param shape 相纸的形状，默认近直角（本世界的相纸永远近直角）。
 */
fun Modifier.paperEdge(
    alpha: Float = 0.55f,
    shape: Shape = SuiPaiShapes.frame,
): Modifier = composed {
    val colors = sheetColors
    // 高光色取"墨与底之间"的一档：暗房里它是相纸白，日光下它是更深的灰。
    val highlight = lerp(colors.ink, colors.canvas, 0.45f)
    // drawWithContent 而不是 drawBehind：那道光是**压在相纸上面**的，
    // 画在背后会被照片自己盖住（照片是子节点，永远画在身后那一层之上）。
    clip(shape).drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val line = Touch.hairline.toPx()
        // 上沿：从左到右渐渐淡出（光从左上打进来，右侧没有光）
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(highlight.copy(alpha = alpha), Color.Transparent),
                startX = 0f,
                endX = size.width,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, line),
        )
        // 左沿：从上到下淡出，比上沿更弱
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(highlight.copy(alpha = alpha * 0.6f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
            topLeft = Offset.Zero,
            size = Size(line, size.height),
        )
    }
}

/**
 * 油笔圈选：选中态的唯一语言。
 *
 * 印样上圈照片不是"加个蓝底"——是拿油笔在**外面**画一个略微歪的直角框。
 * 这里用一层 2dp 的琥珀描边 + 一点点偏移的"重描"来做出笔的厚薄：
 * 主框正，副框偏移一格、alpha 更低，看起来像笔尖走了一圈。
 *
 * 用法（**不要**再另外加 border）：
 * ```
 * Modifier
 *     .greaseSelection(ticked = picked.contains(photo.uri))
 *     .pressable(onClickLabel = "选中这张", onClick = { toggle(photo.uri) })
 * ```
 *
 * @param ticked 是否被圈中。false 时什么都不画（零开销）。
 * @param width 圈选的线宽，默认 2dp（1dp 太细，3dp 会吃掉照片）。
 */
fun Modifier.greaseSelection(
    ticked: Boolean,
    width: Dp = Touch.selection,
): Modifier = if (!ticked) this else composed {
    val pen = sheetColors.grease
    drawBehind {
        val w = width.toPx()
        // 主框：贴边、正
        drawRect(
            color = pen,
            topLeft = Offset.Zero,
            size = size,
            style = Stroke(width = w),
        )
        // 重描：向内偏一格，淡 35% —— 笔不是一次画准的
        val inset = Touch.hairline.toPx()
        val innerW = (size.width - inset * 2f).coerceAtLeast(0f)
        val innerH = (size.height - inset * 3f).coerceAtLeast(0f)
        if (innerW > 0f && innerH > 0f) {
            drawRect(
                color = pen.copy(alpha = 0.35f),
                topLeft = Offset(inset, inset * 2f),
                size = Size(innerW, innerH),
                style = Stroke(width = w * 0.5f),
            )
        }
    }
}

/**
 * 按压态：按下时缩小一点点 + 压暗一点点。
 *
 * 参数是从"手指按在相纸上"推的：相纸几乎不缩（1.5%），但会**暗**一点
 * （手指挡住光）。所以位移要几乎没有，亮度变化要看得出来。
 * 缩放放在这里而不是调用点，是因为三处各写一个 0.97/0.96/0.95 就是"到处都差一点点"。
 *
 * 用法（**替代** `Modifier.clickable`，不要叠用两个 clickable —— 语义树会长出两颗可点节点）：
 * ```
 * Modifier.pressable(
 *     onClickLabel = "打开这张照片",
 *     onClick = { open(uri) },
 * )
 * ```
 *
 * @param enabled 关闭时既不响应点击、也不记按压。
 * @param pressMs 动画时长；默认取 `MotionScale.Press`。
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    pressMs: Int = MotionScale.Press,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = tween(durationMillis = pressMs, easing = MotionScale.PressEasing),
        label = "pressable-scale",
    )
    val dim by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.12f else 0f,
        animationSpec = tween(durationMillis = pressMs, easing = MotionScale.PressEasing),
        label = "pressable-dim",
    )
    this
        .scale(scale)
        .drawBehind {
            if (dim > 0f) {
                drawRect(color = Color.Black.copy(alpha = dim * 0.5f))
            }
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * 发丝线：一条 1dp 的分隔线。
 *
 * 为什么不做成组件而做成 Modifier：它几乎总是要跟着父容器的内边距走
 * （"从文字左边缘到屏幕右边缘"），做成 `Box` 就得在每个调用点再算一次 padding。
 *
 * 用法（只画横线；竖线用 [hairlineVertical]）：
 * ```
 * Column {
 *     Row(...) { ... }
 *     Spacer(Modifier.fillMaxWidth().height(1.dp).hairline(inset = 16.dp))
 *     Row(...) { ... }
 * }
 * ```
 *
 * @param color 默认取主题 `hairline`（**装饰用**，不承担可点性指示）。
 * @param inset 左右各缩进多少 —— 让线从文字边缘起，而不是从屏幕边起。
 */
fun Modifier.hairline(
    color: Color = Color.Unspecified,
    inset: Dp = 0.dp,
): Modifier = composed {
    val line = if (color == Color.Unspecified) sheetColors.hairline else color
    drawBehind {
        val startX = inset.toPx()
        val endX = size.width - inset.toPx()
        if (endX <= startX) return@drawBehind
        val y = size.height - Touch.hairline.toPx() / 2f
        drawLine(
            color = line,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = Touch.hairline.toPx(),
        )
    }
}

/** 竖版发丝线：给工具条里的分组用。 */
fun Modifier.hairlineVertical(
    color: Color = Color.Unspecified,
    inset: Dp = 0.dp,
): Modifier = composed {
    val line = if (color == Color.Unspecified) sheetColors.hairline else color
    drawBehind {
        val startY = inset.toPx()
        val endY = size.height - inset.toPx()
        if (endY <= startY) return@drawBehind
        val x = Touch.hairline.toPx() / 2f
        drawLine(
            color = line,
            start = Offset(x, startY),
            end = Offset(x, endY),
            strokeWidth = Touch.hairline.toPx(),
        )
    }
}

/**
 * 胶片颗粒（filmGrain）：在**空的地方**撒一层极淡的噪点，让暗房底不像一块纯色塑料。
 *
 * 做法是画一圈极淡的方点（不是逐像素噪声 —— 那要一张 bitmap，还得随尺寸重建，
 * 而且 Bitmap 一进组合就是每帧新建）。强度默认 0.02，肉眼几乎看不见，
 * 但整块底色会"活"起来。
 *
 * **不许**盖在照片上：照片本身已经有颗粒（一瞬的颗粒是管线的一部分），
 * 再叠一层就是双重纹理，而且会让"原图对比"失真。
 *
 * @param alpha 整体强度。0 关闭。
 * @param step 点的间距（dp）。越小越密、越贵。
 */
fun Modifier.filmGrain(
    alpha: Float = 0.02f,
    step: Dp = 7.dp,
): Modifier = composed {
    val grain = sheetColors.ink
    drawBehind {
        if (alpha <= 0f) return@drawBehind
        val gap = step.toPx()
        if (gap <= 0f) return@drawBehind
        val dot = Touch.hairline.toPx()
        var y = gap
        var row = 0
        // 行列上限：极端尺寸（超长列表项 / 异常测量）时也不许画出几万个点
        while (y < size.height && row < 400) {
            // 隔行错位：规则网格看起来像屏幕摩尔纹，错开半格才像颗粒
            var x = if (row % 2 == 0) gap else gap * 1.5f
            var col = 0
            while (x < size.width && col < 400) {
                drawRect(
                    color = grain.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(dot, dot),
                )
                x += gap
                col++
            }
            y += gap
            row++
        }
    }
}

/**
 * 相纸的**内**边缘暗角：四边各压一道极淡的暗线，让照片与底之间有"纸有厚度"的暗示。
 *
 * 与 [paperEdge] 是一对：paperEdge 是上沿的光，paperInset 是四边的厚度。
 * 两者可以叠用，顺序是 `paperEdge` → `paperInset` → `background`。
 */
fun Modifier.paperInset(
    color: Color = Color.Black.copy(alpha = 0.20f),
    width: Dp = Touch.hairline,
): Modifier = drawBehind {
    drawRect(
        color = color,
        topLeft = Offset.Zero,
        size = size,
        style = Stroke(width = width.toPx()),
    )
}
