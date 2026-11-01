package com.suipai.app.ui.slideshow

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.suipai.app.data.FastThumb
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import kotlinx.coroutines.delay

/**
 * 全屏幻灯片。
 *
 * 全屏 = 黑底 + 状态栏/导航栏的 padding 都留出来（不 `WindowInsets` 强制隐藏：
 * 这一屏是从查看器/回忆进的，退出后还要回到有 chrome 的界面，来回切系统栏会闪）。
 *
 * 手势与按键：
 * - **左右滑**：手动翻页（`HorizontalPager`），滑完会同步回 [SlideshowState]；
 * - **下滑**：退出（`detectVerticalDragGestures`，阈值 140px，和查看器同一个数）；
 * - **点一下**：暂停 / 继续；
 * - **音量键**：上一张 / 下一张（`view.setOnKeyListener`，和查看器同一套写法 ——
 *   音频应用会抢这个键，所以只在拿到焦点时生效）。
 *
 * 定时器的依赖里同时有 `state.index`：手动滑一下，计时器会**重新计时**而不是马上跳。
 */
@Composable
fun SlideshowScreen(
    photos: List<PhotoItem>,
    startIndex: Int = 0,
    onExit: () -> Unit,
) {
    val colors = SuiPaiColor.current
    val view = LocalView.current

    var state by remember(photos.size) {
        mutableStateOf(SlideshowState.start(photos.size, startIndex, loop = true, shuffle = false, seed = 7))
    }
    var playing by remember { mutableStateOf(true) }
    var interval by remember { mutableIntStateOf(SlideshowIntervals[1]) }
    var chrome by remember { mutableStateOf(true) }
    var dismissY by remember { mutableFloatStateOf(0f) }

    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    // 片单变小（后台删了照片）时把下标夹回来，别让 pager 拿着越界的页号。
    LaunchedEffect(photos.size) {
        if (state.index > photos.lastIndex) state = state.jump(photos.lastIndex).first
    }

    val pager = rememberPagerState(initialPage = state.index.coerceIn(0, photos.lastIndex)) { photos.size }

    // 状态 → pager：定时/按键推进之后把画面滚过去。
    LaunchedEffect(state.index) {
        if (pager.currentPage != state.index) pager.animateScrollToPage(state.index)
    }
    // pager → 状态：用户手动滑。`jump` 会把目标从随机袋里摘掉，不会"刚看过又抽到"。
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != state.index) state = state.jump(pager.currentPage).first
    }

    // 计时器：暂停、间隔、位置、循环/随机开关中任何一个变了，都重新起算。
    LaunchedEffect(playing, interval, state.index, state.loop, state.shuffle, photos.size) {
        if (!playing) return@LaunchedEffect
        delay(interval.toLong())
        if (photos.size < 2) {
            playing = false
            return@LaunchedEffect
        }
        val (next, step) = state.advance(stepSeed = state.index + interval)
        state = next
        if (step is SlideshowStep.End) playing = false
    }

    // 音量键翻页。只在多张时接管：单张时把音量键还给系统。
    val volumePager = rememberUpdatedState(photos.size > 1)
    DisposableEffect(photos.size) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (!volumePager.value) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    val (next, step) = state.advance(stepSeed = state.index + 1)
                    state = next
                    if (step is SlideshowStep.End) playing = false
                    true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val (next, step) = state.back()
                    state = next
                    if (step is SlideshowStep.End) playing = false
                    true
                }

                else -> false
            }
        }
        onDispose { view.setOnKeyListener(null) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasSunken)
            .graphicsLayer {
                translationY = dismissY
                alpha = (1f - dismissY / 900f).coerceIn(0.35f, 1f)
            },
    ) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = dismissY < 8f,
            modifier = Modifier
                .fillMaxSize()
                .testTag("slideshow_pager"),
        ) { page ->
            SlidePhoto(photos[page])
        }

        // 点一下：暂停/继续（整页透明手势层，压在图画上面）。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            playing = !playing
                            chrome = true
                        },
                    )
                },
        )

        // 下滑退出：只在没翻页时接管，避免和 pager 的横向手势打架。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(photos.size) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dismissY > DismissThresholdPx) onExit() else dismissY = 0f
                        },
                        onDragCancel = { dismissY = 0f },
                        onVerticalDrag = { change, amount ->
                            // 横向为主的手势不归这里管：pager 要拿它翻页。
                            if (kotlin.math.abs(amount) < 2f) return@detectVerticalDragGestures
                            change.consume()
                            dismissY = (dismissY + amount).coerceAtLeast(0f)
                        },
                    )
                },
        )

        if (chrome) {
            AppBarRow(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
            ) {
                AppBarCell(label = "退出", on = false, onClick = onExit)
                AppBarText(
                    "${state.index + 1} / ${photos.size}",
                    style = SuiPaiType.frameNo,
                    color = colors.inkSecondary,
                    layout = Modifier.weight(1f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                AppBarCell(
                    label = if (playing) "暂停" else "继续",
                    on = !playing,
                    onClick = { playing = !playing },
                )
                AppBarCell(
                    label = intervalLabel(interval),
                    on = interval != SlideshowIntervals[1],
                    onClick = { interval = nextInterval(interval) },
                )
                AppBarCell(
                    label = "循环",
                    on = state.loop,
                    onClick = { state = state.withLoop(!state.loop) },
                )
                AppBarCell(
                    label = "随机",
                    on = state.shuffle,
                    onClick = {
                        state = state.withShuffle(!state.shuffle, seed = state.index + 31)
                        playing = true
                    },
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    photos[state.index.coerceIn(0, photos.lastIndex)].displayName,
                    style = SuiPaiType.marker,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 进度条：自己画，不用 Slider —— 这一条不是可调控件，不该被读屏当成滑杆。
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .padding(top = 6.dp)
                        .testTag("slideshow_progress"),
                ) {
                    drawRect(color = colors.hairline, size = Size(size.width, size.height))
                    drawRect(
                        color = colors.safelight,
                        size = Size(size.width * state.progress, size.height),
                    )
                }
                if (state.exhausted) {
                    Text(
                        "这一轮放完了，点画面从头再来",
                        style = SuiPaiType.bodySmall,
                        color = colors.safelight,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            // 结论要能被读屏播出来：这句话是用户唯一的反馈。
                            .clickable {
                                state = SlideshowState.start(
                                    photos.size,
                                    state.index,
                                    state.loop,
                                    state.shuffle,
                                    seed = state.index + 17,
                                )
                                playing = true
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlidePhoto(photo: PhotoItem) {
    val context = LocalContext.current
    val colors = SuiPaiColor.current
    val request = remember(photo.id) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .size(2048)
            .tag(FastThumb(2048))
            .crossfade(true)
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request)
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painter,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        when (painter.state) {
            is AsyncImagePainter.State.Error -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("无法显示", color = colors.inkSecondary, style = SuiPaiType.bodyMedium)
            }

            else -> Unit
        }
    }
}

/** 下滑退出那条拒绝横向拖动时的比较用的是绝对值；这里只留一句说明。 */
private const val DismissThresholdPx = 140f
