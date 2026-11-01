package com.suipai.app.ui.viewer

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.clipWrite
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.design.PrimaryButton
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.design.TextButtonTonal
import com.suipai.app.ui.design.Touch
import com.suipai.app.ui.design.pressable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.suipai.app.data.MediaRepository
import com.suipai.app.data.PhotoItem
import com.suipai.app.ui.play.CastIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 同一个格式化器全页用一个就够了，别按照片现场造。 */
private val TakenFull: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

/**
 * 文件名尾缀 → 扩展名（不带点）。抽成函数是因为「格式」那颗格子和投屏那条路都要它，
 * 写两遍的下场是以后只改了一处，两处说的格式不一样。
 */
private fun extensionOf(displayName: String): String =
    displayName.substringAfterLast('.', missingDelimiterValue = "").ifBlank { "jpg" }

/**
 * 扩展名 → MIME。
 *
 * 为什么不能一律给 `image/jpeg`：接收端会按 MIME 挑解码器，HEIC / PNG 被贴成 jpeg
 * 时对方的"图库 / 投屏器"要么不解码、要么弹出错误 —— 这条与 `CastIntent.MIME_WILDCARD`
 * 的注释是同一条理由。认不出的尾缀才退回 jpeg（系统相册里绝大多数就是它）。
 */
private fun mimeOf(displayName: String): String = when (extensionOf(displayName).lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    else -> "image/jpeg"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    onYishun: (PhotoItem) -> Unit,
    onDeleted: (PhotoItem) -> Unit = {},
    favoriteIds: Set<Long> = emptySet(),
    onToggleFavorite: (PhotoItem) -> Unit = {},
    hiddenIds: Set<Long> = emptySet(),
    onToggleHidden: (PhotoItem) -> Unit = {},
    statusNote: String? = null,
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val restoredIndex = rememberSaveable { mutableIntStateOf(initialIndex) }
    val start = restoredIndex.intValue.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = start) { photos.size }
    LaunchedEffect(pagerState.currentPage) { restoredIndex.intValue = pagerState.currentPage }
    var pagerLocked by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showChrome by remember { mutableStateOf(true) }
    var dismissY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(photos.size) {
        if (pagerState.currentPage > photos.lastIndex) {
            pagerState.scrollToPage(photos.lastIndex)
        }
    }
    val photo = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyHint by remember { mutableStateOf<String?>(null) }
    /**
     * 投屏的结论。查看器没有可写的结论行（`statusNote` 是只读入参），
     * 而这些话又非说不可 —— 「点了没反应」正是投屏最容易变成的样子。
     * 所以单独一份状态，喂给「外部打开」那一节的 note。
     */
    var castNote by remember { mutableStateOf<String?>(null) }
    var wallHint by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("suipai", android.content.Context.MODE_PRIVATE) }
    var showInfo by remember { mutableStateOf(prefs.getBoolean("viewer_info", false)) }
    var wallMode by remember { mutableIntStateOf(prefs.getInt("wall_mode", android.app.WallpaperManager.FLAG_SYSTEM)) }
    var playing by remember { mutableStateOf(false) }
    var rotation by remember { mutableIntStateOf(0) }
    var mirrored by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var playMs by remember { mutableIntStateOf(prefs.getInt("slideshow_ms", 2800).coerceIn(1500, 8000)) }
    var shufflePlay by remember { mutableStateOf(prefs.getBoolean("slideshow_shuffle", false)) }
    var fillFrame by remember { mutableStateOf(prefs.getBoolean("viewer_fill", false)) }
    var playLoop by remember { mutableStateOf(prefs.getBoolean("slideshow_loop", true)) }
    var overlayOn by remember { mutableStateOf(prefs.getBoolean("viewer_overlay", false)) }
    var showGrid by remember { mutableStateOf(prefs.getBoolean("viewer_grid", false)) }
    var showDiag by remember { mutableStateOf(prefs.getBoolean("viewer_diag", false)) }
    var showPhi by remember { mutableStateOf(prefs.getBoolean("viewer_phi", false)) }
    var showCross by remember { mutableStateOf(prefs.getBoolean("viewer_cross", false)) }
    var showSafe by remember { mutableStateOf(prefs.getBoolean("viewer_safe", false)) }
    var volPager by remember { mutableStateOf(prefs.getBoolean("viewer_vol", true)) }
    val view = LocalView.current
    DisposableEffect(volPager, photos.size) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (!volPager || photos.size < 2) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                return@setOnKeyListener false
            }
            val next = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                (pagerState.currentPage + 1).coerceAtMost(photos.lastIndex)
            } else {
                (pagerState.currentPage - 1).coerceAtLeast(0)
            }
            if (next != pagerState.currentPage) {
                scope.launch { pagerState.animateScrollToPage(next) }
            }
            true
        }
        onDispose { view.setOnKeyListener(null) }
    }
    LaunchedEffect(pagerState.currentPage) {
        confirmDelete = false
        dismissY = 0f
        copyHint = null
        // 换了照片，上一张的投屏结论就不成立了（"正在投屏到 X" 说的不是这一张）。
        castNote = null
        wallHint = null
        rotation = 0
        mirrored = false
        flipV = false
    }
    LaunchedEffect(playing, playMs, shufflePlay, playLoop, photos.size) {
        if (!playing || photos.size < 2) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(playMs.toLong())
            val next = if (shufflePlay && photos.size > 2) {
                var pick: Int
                do {
                    pick = (0 until photos.size).random()
                } while (pick == pagerState.currentPage)
                pick
            } else {
                pagerState.currentPage + 1
            }
            if (!shufflePlay && next >= photos.size) {
                if (playLoop) {
                    pagerState.animateScrollToPage(0)
                } else {
                    playing = false
                    break
                }
            } else {
                pagerState.animateScrollToPage(if (shufflePlay) next else next)
            }
        }
    }
    val taken = remember(photo.dateTaken) {
        Instant.ofEpochMilli(photo.dateTaken.coerceAtLeast(0L))
            .atZone(ZoneId.systemDefault())
            .format(TakenFull)
    }
    var exifBits by remember(photo.uri) { mutableStateOf<ExifBits?>(null) }
    LaunchedEffect(showInfo, photo.uri) {
        if (!showInfo) return@LaunchedEffect
        exifBits = withContext(Dispatchers.IO) { readExifBits(context, photo.uri) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !pagerLocked && dismissY < 8f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissY
                    alpha = (1f - dismissY / 700f).coerceIn(0.35f, 1f)
                },
        ) { page ->
            ZoomablePhoto(
                uri = photos[page].uri,
                contentDescription = photos[page].displayName,
                fillFrame = fillFrame,
                rotation = if (page == pagerState.currentPage) rotation else 0,
                mirrored = page == pagerState.currentPage && mirrored,
                flipV = page == pagerState.currentPage && flipV,
                onScaleChange = { pagerLocked = it > 1.02f },
                onTap = {
                    if (playing) playing = false else showChrome = !showChrome
                },
                dismissEnabled = !pagerLocked,
                onDismissDelta = { delta ->
                    dismissY = (dismissY + delta).coerceAtLeast(0f)
                },
                onDismissEnd = {
                    if (dismissY > 140f) onClose() else dismissY = 0f
                },
            )
        }
        if (showGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = Color.White.copy(alpha = 0.28f)
                val w = size.width
                val h = size.height
                drawLine(paint, Offset(w / 3f, 0f), Offset(w / 3f, h), 1.4f)
                drawLine(paint, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), 1.4f)
                drawLine(paint, Offset(0f, h / 3f), Offset(w, h / 3f), 1.4f)
                drawLine(paint, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), 1.4f)
            }
        }
        if (showDiag) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = Color.White.copy(alpha = 0.28f)
                val w = size.width
                val h = size.height
                drawLine(paint, Offset(0f, 0f), Offset(w, h), 1.4f)
                drawLine(paint, Offset(w, 0f), Offset(0f, h), 1.4f)
            }
        }
        if (showPhi) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = Color.White.copy(alpha = 0.28f)
                val w = size.width
                val h = size.height
                drawLine(paint, Offset(w * 0.382f, 0f), Offset(w * 0.382f, h), 1.4f)
                drawLine(paint, Offset(w * 0.618f, 0f), Offset(w * 0.618f, h), 1.4f)
                drawLine(paint, Offset(0f, h * 0.382f), Offset(w, h * 0.382f), 1.4f)
                drawLine(paint, Offset(0f, h * 0.618f), Offset(w, h * 0.618f), 1.4f)
            }
        }
        if (showCross) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = Color.White.copy(alpha = 0.28f)
                val w = size.width
                val h = size.height
                drawLine(paint, Offset(w / 2f, 0f), Offset(w / 2f, h), 1.4f)
                drawLine(paint, Offset(0f, h / 2f), Offset(w, h / 2f), 1.4f)
            }
        }
        if (showSafe) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = Color.White.copy(alpha = 0.28f)
                val insetX = size.width * 0.05f
                val insetY = size.height * 0.05f
                drawRect(
                    color = paint,
                    topLeft = Offset(insetX, insetY),
                    size = Size(size.width - insetX * 2f, size.height - insetY * 2f),
                    style = Stroke(width = 1.4f),
                )
            }
        }
        if (overlayOn) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (showChrome) 96.dp else 24.dp),
            ) {
                Text(
                    photo.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photoInfoLine(photo),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    taken,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        var vSheet by remember { mutableIntStateOf(0) }
        if (!showChrome) return@Box
        // 上下各一道渐隐：填满模式下图会铺到状态栏底下，白图配白框、黑图配黑框都会读不出来。
        // 这里用 surface 而不是写死黑色——日光（相纸白）主题下黑渐隐会把墨色字压没。
        val chromeFade = MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        0f to chromeFade.copy(alpha = 0.90f),
                        0.55f to chromeFade.copy(alpha = 0.78f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to chromeFade.copy(alpha = 0.62f),
                        1f to chromeFade.copy(alpha = 0.90f),
                    ),
                ),
        )
        AppBarRow(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding(),
        ) {
            AppBarCell(label = "返回", on = false, onClick = onClose)
            AppBarText(
                // 删除的结论要能在**当前这一层**说出口：从查看器里删一张时画廊在它后面，
                // 画廊页眉那格用户根本看不见。
                statusNote ?: "${pagerState.currentPage + 1} / ${photos.size}  ·  ${taken}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = com.suipai.app.ui.theme.SuiPaiSheet.type.frameNo,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            AppBarCell(
                label = if (fillFrame) "填满" else "适应",
                on = fillFrame,
                onClick = {
                    fillFrame = !fillFrame
                    prefs.edit().putBoolean("viewer_fill", fillFrame).apply()
                },
            )
            AppBarCell(label = "旋转", on = rotation != 0, onClick = { rotation = (rotation + 90) % 360 })
            // 「更多」只在底栏留一把钥匙：顶栏那颗和它控的是同一个 vSheet，
            // 两把钥匙会在抽屉打开时同时亮起琥珀框，用户无从判断哪颗是"当前"。
            AppBarCell(
                label = "分享",
                on = false,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, photo.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    sendOrTell(context, Intent.createChooser(send, "分享"), "分享") { copyHint = it }
                },
            )
        }

        if (showInfo) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                    .combinedClickable(
                        // 这一层吃掉点击是为了不让它穿到底下的图上；它没有点击动作，
                        // 那就显式写 null —— 留空会在读屏的"操作"里挂一个无名动作。
                        onClickLabel = null,
                        onLongClickLabel = "复制这张的全部信息",
                        onClick = {},
                        onLongClick = {
                            val text = listOfNotNull(
                                photo.displayName,
                                photoInfoLine(photo),
                                exifBits?.line,
                            ).joinToString("\n")
                            val clip = android.content.ClipData.newPlainText("随拍", text)
                            val wrote = clipWrite(context, "随拍", clip)
                            copyHint = if (wrote) "已复制内容" else "拷贝失败"
                        },
                    ),
            ) {
                Text(
                    photo.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photoInfoLine(photo),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                yishunLookName(photo.displayName)?.let { look ->
                    Text(
                        "一瞬 · $look",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                exifBits?.line?.takeIf { it.isNotBlank() }?.let { line ->
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val lat = exifBits?.lat
                val lng = exifBits?.lng
                if (lat != null && lng != null) {
                    AppBarCell(
                        label = "地图",
                        on = false,
                        onClick = {
                            val geo = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                            sendOrTell(context, Intent(Intent.ACTION_VIEW, geo), "打开地图") {
                                copyHint = it
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制坐标") "已复制坐标" else "复制坐标",
                        on = copyHint == "已复制坐标",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText(
                                    "随拍",
                                    "${"%.6f".format(lat)}, ${"%.6f".format(lng)}",
                                )
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制坐标" else "拷贝失败"
                            }
                        },
                    )
                }
                AppBarCell(
                    label = when (wallMode) {
                        android.app.WallpaperManager.FLAG_LOCK -> "锁屏"
                        android.app.WallpaperManager.FLAG_SYSTEM -> "桌面"
                        else -> "桌面+锁屏"
                        },
                    on = wallMode != 0,
                    onClick = {
                        wallMode = when (wallMode) {
                            android.app.WallpaperManager.FLAG_SYSTEM -> android.app.WallpaperManager.FLAG_LOCK
                            android.app.WallpaperManager.FLAG_LOCK ->
                                android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                            else -> android.app.WallpaperManager.FLAG_SYSTEM
                        }
                        prefs.edit().putInt("wall_mode", wallMode).apply()
                        wallHint = null
                    },
                )
                AppBarCell(
                    label = wallHint ?: "设为壁纸",
                    on = false,
                    onClick = {
                        scope.launch {
                            val flags = wallMode
                            wallHint = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bmp = com.suipai.app.data.ImageLoader.decode(context, photo.uri, maxEdge = 4096).first
                                    android.app.WallpaperManager.getInstance(context)
                                        .setBitmap(bmp, null, true, flags)
                                    if (!bmp.isRecycled) bmp.recycle()
                                    "已设为壁纸"
                                }.getOrElse { "壁纸失败" }
                            }
                        }
                    },
                )
            }
        }
        AppBarRow(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 6.dp),
        ) {
            // ── 底栏分主次 ──
            //
            // 改之前这五颗是**完全同构的方框**：收藏 / 隐藏 / 删除 / 更多 / 一瞬 全是
            // `AppBarCell` 的 1dp 描边格子，只有「一瞬」是实心。后果是
            // **「删除」和「收藏」长得一模一样**，而它们一个是不可逆的危险操作。
            //
            // 现在按语义角色分三层（设计系统 v1 §1）：
            //   · 只有「一瞬」是实心主操作（一屏至多一个实心块）；
            //   · 收藏 / 隐藏 / 更多 是次操作：无底色文字按钮；
            //   · 删除 是危险操作：**琥珀不再是它的颜色**，用定影红。
            //     确认态（`confirmDelete`）升级成实心 `dangerFill` + 白字，
            //     因为那一下真的会把照片删掉。
            //
            // 二次确认的既有行为**一个字都没改**：第一次点只是把 `confirmDelete`
            // 置真（按钮变成「确认删除」），第二次点才 `onDeleted(photo)`。
            TextButtonTonal(
                label = if (photo.id in favoriteIds) "已收藏" else "收藏",
                tonal = photo.id !in favoriteIds,
                onClick = { onToggleFavorite(photo) },
            )
            TextButtonTonal(
                label = if (photo.id in hiddenIds) "取消隐藏" else "隐藏",
                tonal = photo.id !in hiddenIds,
                onClick = { onToggleHidden(photo) },
            )
            if (confirmDelete) {
                // 确认态：实心危险块。`danger = true` 用的是 dangerFill + 纸白字（4.94:1），
                // 不是 `fixer` 本体（3.77:1，小字读不清）。
                PrimaryButton(
                    label = "确认删除",
                    danger = true,
                    onClick = {
                        confirmDelete = false
                        onDeleted(photo)
                    },
                )
                TextButtonTonal(
                    label = "取消",
                    tonal = true,
                    onClick = { confirmDelete = false },
                )
            } else {
                // 危险操作的**非确认态**：定影红字。
                //
                // 为什么不用 `TextButtonTonal`：它只分"琥珀"与"次级"两档，
                // 没有危险档。把「删除」写成琥珀，它会变成一颗普通的强调按钮 ——
                // 而这正是改前最严重的问题（删除和收藏长得一样）。
                // 所以这里自建一颗：形态与 `TextButtonTonal` 同族
                // （无底色 + 一道淡下划线），只有颜色是危险语义。
                //
                // ⚠ `AppBarRow` 是 `FlowRow`，裸 `Text` 会被顶到行的上沿
                // （`PerfSourceGuardTest.toolbarTextsMustUseAppBarText` 钉着这一条）。
                // 所以这里**不用 `Text`**，改用 `AppBarText` —— 它自带 48dp 高度与垂直居中，
                // 与同排格子对齐；颜色/字级都从调用方给。
                AppBarText(
                    text = "删除",
                    style = SuiPaiType.labelLarge,
                    color = SuiPaiColor.current.fixerInk,
                    modifier = Modifier
                        .heightIn(min = Touch.min)
                        .pressable(
                            onClickLabel = "删除这张照片（要再点一次确认）",
                            onClick = { confirmDelete = true },
                        )
                        .clearAndSetSemantics { contentDescription = "删除" }
                        .padding(horizontal = Spacing.sm),
                )
            }
            AppBarCell(label = "更多", on = vSheet == 1, onClick = { vSheet = 1 })
            AppBarPrimary(
                label = "一瞬",
                onClick = { onYishun(photo) },
                modifier = Modifier.testTag("viewer_yishun"),
            )
        }

        if (vSheet == 1) {
            AppSheet(title = "这张照片", tag = "viewer_sheet_more", onDismiss = { vSheet = 0 }) {
                AppSheetSection("翻片")
                AppChipFlow {
                    if (photos.size > 1) {
                        AppBarCell(
                            label = "上一张",
                            on = false,
                            enabled = pagerState.currentPage > 0,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            },
                        )
                        AppBarCell(
                            label = "下一张",
                            on = false,
                            enabled = pagerState.currentPage < photos.lastIndex,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                        )
                    }
                }
                AppSheetSection("画面与构图辅助")
                AppChipFlow {
                    AppBarCell(
                        label = if (mirrored) "已镜像" else "镜像",
                        on = mirrored,
                        onClick = { mirrored = !mirrored },
                    )
                    AppBarCell(
                        label = if (flipV) "已上下翻" else "上下翻",
                        on = flipV,
                        onClick = { flipV = !flipV },
                    )
                    if (rotation != 0 || mirrored || flipV) {
                        AppBarCell(
                            label = "保存画面",
                            on = false,
                            onClick = {
                                val deg = rotation
                                val flip = mirrored
                                val vflip = flipV
                                scope.launch {
                                    copyHint = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val bmp = com.suipai.app.data.ImageLoader.decode(context, photo.uri, maxEdge = 4096).first
                                            val matrix = android.graphics.Matrix().apply {
                                                if (flip || vflip) postScale(if (flip) -1f else 1f, if (vflip) -1f else 1f)
                                                if (deg != 0) postRotate(deg.toFloat())
                                            }
                                            val turned = android.graphics.Bitmap.createBitmap(
                                                bmp, 0, 0, bmp.width, bmp.height, matrix, true,
                                            )
                                            val tag = when {
                                                vflip && flip -> "翻转"
                                                vflip -> "上下翻"
                                                flip && deg != 0 -> "转向"
                                                flip -> "镜像"
                                                else -> "旋转"
                                            }
                                            val saved = MediaRepository(context).saveBitmap(
                                                turned,
                                                "SuiPai_${tag}_${System.currentTimeMillis()}.jpg",
                                                sourceUri = photo.uri,
                                            )
                                            if (turned !== bmp && !turned.isRecycled) turned.recycle()
                                            if (!bmp.isRecycled) bmp.recycle()
                                            if (saved != null) "已保存$tag" else "保存失败"
                                        }.getOrElse { "保存失败" }
                                    }
                                }
                            },
                        )
                    }
                    if (photos.size > 1) {
                        AppBarCell(
                            label = if (playing) "停止" else "播放",
                            on = playing,
                            onClick = { playing = !playing },
                        )
                        AppBarCell(
                            label = "${"%.1f".format(playMs / 1000f)}秒",
                            on = false,
                            onClick = {
                                playMs = when (playMs) {
                                    1500 -> 2800
                                    2800 -> 5000
                                    else -> 1500
                                }
                                prefs.edit().putInt("slideshow_ms", playMs).apply()
                            },
                        )
                        AppBarCell(
                            label = if (shufflePlay) "随机" else "顺序",
                            on = shufflePlay,
                            onClick = {
                                shufflePlay = !shufflePlay
                                prefs.edit().putBoolean("slideshow_shuffle", shufflePlay).apply()
                            },
                        )
                        AppBarCell(
                            label = if (playLoop) "循环" else "一遍",
                            on = playLoop,
                            onClick = {
                                playLoop = !playLoop
                                prefs.edit().putBoolean("slideshow_loop", playLoop).apply()
                            },
                        )
                    }
                    AppBarCell(
                        label = if (showInfo) "收起" else "详情",
                        on = showInfo,
                        onClick = {
                        showInfo = !showInfo
                        prefs.edit().putBoolean("viewer_info", showInfo).apply()
                    },
                    )
                    AppBarCell(
                        label = if (overlayOn) "叠信息开" else "叠信息",
                        on = overlayOn,
                        onClick = {
                            overlayOn = !overlayOn
                            prefs.edit().putBoolean("viewer_overlay", overlayOn).apply()
                        },
                    )
                    AppBarCell(
                        label = if (showGrid) "九宫开" else "九宫",
                        on = showGrid,
                        onClick = {
                            showGrid = !showGrid
                            prefs.edit().putBoolean("viewer_grid", showGrid).apply()
                        },
                    )
                    AppBarCell(
                        label = if (showDiag) "对角开" else "对角",
                        on = showDiag,
                        onClick = {
                            showDiag = !showDiag
                            prefs.edit().putBoolean("viewer_diag", showDiag).apply()
                        },
                    )
                    AppBarCell(
                        label = if (showPhi) "黄金开" else "黄金线",
                        on = showPhi,
                        onClick = {
                            showPhi = !showPhi
                            prefs.edit().putBoolean("viewer_phi", showPhi).apply()
                        },
                    )
                    AppBarCell(
                        label = if (showCross) "十字开" else "十字",
                        on = showCross,
                        onClick = {
                            showCross = !showCross
                            prefs.edit().putBoolean("viewer_cross", showCross).apply()
                        },
                    )
                    AppBarCell(
                        label = if (showSafe) "安全框开" else "安全框",
                        on = showSafe,
                        onClick = {
                            showSafe = !showSafe
                            prefs.edit().putBoolean("viewer_safe", showSafe).apply()
                        },
                    )
                    if (photos.size > 1) {
                        AppBarCell(
                            label = if (volPager) "音量翻页" else "音量系统",
                            on = volPager,
                            onClick = {
                                volPager = !volPager
                                prefs.edit().putBoolean("viewer_vol", volPager).apply()
                            },
                        )
                    }
                }
                AppSheetSection("外部打开", note = castNote)
                AppChipFlow {
                    AppBarCell(
                        label = "打开",
                        on = false,
                        onClick = {
                            val view = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(photo.uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            sendOrTell(context, Intent.createChooser(view, "打开"), "用别的 App 打开") {
                                copyHint = it
                            }
                        },
                    )
                    AppBarCell(
                        label = "投屏",
                        on = false,
                        modifier = Modifier.testTag("viewer_cast"),
                        onClick = {
                            // 先 decide 再 intentFor，而不是直接 `CastIntent.castIntent(...)`：
                            // castIntent 内部把路由吞掉了，只回一个 null / Intent，
                            // 于是 `noteFor(route)` 的三句话（精确 / 交给系统 / 未找到）一句也说不出来，
                            // 用户看到的永远是同一句「没找到」——那等于把三种结局糊成一种。
                            val mime = mimeOf(photo.displayName)
                            val pm = context.packageManager
                            val route = CastIntent.decide(
                                isInstalled = { pkg ->
                                    runCatching {
                                        @Suppress("DEPRECATION")
                                        pm.getPackageInfo(pkg, 0)
                                    }.isSuccess
                                },
                                resolves = { pkg ->
                                    // 与 `CastIntent.castIntent` 内部同形：`queryIntentActivities` 为主
                                    // （API 30+ 的包可见性下 `resolveActivity` 看不见别人的声明），
                                    // 两条都 runCatching —— 抛了就是"接不住"，不许漏到调用点。
                                    runCatching {
                                        val probe = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(photo.uri, CastIntent.normalizedMime(mime))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            if (pkg != null) setPackage(pkg)
                                        }
                                        pm.queryIntentActivities(probe, 0).isNotEmpty() ||
                                            probe.resolveActivity(pm) != null
                                    }.getOrDefault(false)
                                },
                            )
                            val intent = CastIntent.intentFor(route, photo.uri, mime)
                            if (intent == null) {
                                // 没有可投的目标：把"为什么"说出来，不要静默。
                                castNote = CastIntent.noteFor(route)
                            } else {
                                // 先把"投到哪个包 / 交给系统挑"说出来 —— 三种路由各有各的话；
                                // 万一系统又拒了这一发，`sendOrTell` 会把这句换成真正的原因。
                                castNote = CastIntent.noteFor(route)
                                sendOrTell(context, intent, "投屏") { castNote = it }
                            }
                        },
                    )
                }
                AppSheetSection("变换后另存", "原图不动，另存一份")
                AppChipFlow {
                    AppBarCell(
                        label = if (copyHint == "已存副本") "已存副本" else if (copyHint == "副本失败") "副本失败" else "副本",
                        on = copyHint == "已存副本",
                        onClick = {
                            scope.launch {
                                val saved = MediaRepository(context).copyToSuiPai(photo.uri)
                                copyHint = if (saved != null) "已存副本" else "副本失败"
                            }
                        },
                    )
                    SaveTransform.entries.forEach { k ->
                        AppBarCell(
                            label = k.label,
                            on = copyHint == k.doneHint,
                            onClick = { scope.launch { copyHint = exportTransformed(context, photo.uri, k) } },
                        )
                    }
                }
                AppSheetSection("导出尺寸", "${ExportSize.entries.size} 档，全部另存不改原图")
                AppChipFlow {
                    ExportSize.entries.forEach { s ->
                        AppBarCell(
                            label = s.label,
                            on = copyHint == s.doneHint,
                            onClick = { scope.launch { copyHint = exportScaled(context, photo.uri, s) } },
                        )
                    }
                }
                AppSheetSection("裁切", "${CropRatio.entries.size} 种比例，居中裁")
                AppChipFlow {
                    CropRatio.entries.forEach { r ->
                        AppBarCell(
                            label = r.label,
                            on = copyHint == r.doneHint,
                            onClick = { scope.launch { copyHint = exportCropped(context, photo.uri, r) } },
                        )
                    }
                }
                AppSheetSection("复制")
                AppChipFlow {
                    AppBarCell(
                        label = if (copyHint == "已拷贝") "已拷贝" else "拷贝",
                        on = copyHint == "已拷贝",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver,
                                    "image",
                                    photo.uri,
                                )
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已拷贝" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制文件名") "已复制文件名" else "文件名",
                        on = copyHint == "已复制文件名",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", photo.displayName)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制文件名" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制时间") "已复制时间" else "时间",
                        on = copyHint == "已复制时间",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", taken)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制时间" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制地址") "已复制地址" else "地址",
                        on = copyHint == "已复制地址",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", photo.uri.toString())
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制地址" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制参数") "已复制参数" else "参数",
                        on = copyHint == "已复制参数",
                        onClick = {
                            runCatching {
                                val text = listOfNotNull(
                                    photo.displayName,
                                    photoInfoLine(photo),
                                    taken,
                                ).joinToString("\n")
                                val clip = android.content.ClipData.newPlainText("随拍", text)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制参数" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制相册名") "已复制相册名" else "相册名",
                        on = copyHint == "已复制相册名",
                        onClick = {
                            runCatching {
                                val name = photo.bucketName.ifBlank { photo.displayName }
                                val clip = android.content.ClipData.newPlainText("随拍", name)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制相册名" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制分辨率") "已复制分辨率" else "分辨率",
                        on = copyHint == "已复制分辨率",
                        onClick = {
                            runCatching {
                                val text = if (photo.width > 0 && photo.height > 0) {
                                    "${photo.width}×${photo.height}"
                                } else {
                                    photo.displayName
                                }
                                val clip = android.content.ClipData.newPlainText("随拍", text)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制分辨率" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制体积") "已复制体积" else "体积",
                        on = copyHint == "已复制体积",
                        onClick = {
                            runCatching {
                                val size = when {
                                    photo.sizeBytes >= 1_000_000L -> "${"%.1f".format(photo.sizeBytes / 1_000_000.0)} MB"
                                    photo.sizeBytes >= 1_000L -> "${"%.0f".format(photo.sizeBytes / 1_000.0)} KB"
                                    else -> "${photo.sizeBytes} B"
                                }
                                val clip = android.content.ClipData.newPlainText("随拍", size)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制体积" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制编号") "已复制编号" else "编号",
                        on = copyHint == "已复制编号",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", photo.id.toString())
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制编号" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制时间戳") "已复制时间戳" else "时间戳",
                        on = copyHint == "已复制时间戳",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", photo.dateTaken.toString())
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制时间戳" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制相册编号") "已复制相册编号" else "相册编号",
                        on = copyHint == "已复制相册编号",
                        onClick = {
                            runCatching {
                                val clip = android.content.ClipData.newPlainText("随拍", photo.bucketId)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制相册编号" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制格式") "已复制格式" else "格式",
                        on = copyHint == "已复制格式",
                        onClick = {
                            runCatching {
                                val ext = extensionOf(photo.displayName)
                                val clip = android.content.ClipData.newPlainText("随拍", ext)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制格式" else "拷贝失败"
                            }
                        },
                    )
                    AppBarCell(
                        label = if (copyHint == "已复制画幅") "已复制画幅" else "画幅",
                        on = copyHint == "已复制画幅",
                        onClick = {
                            runCatching {
                                val a = if (photo.height > 0) photo.width.toFloat() / photo.height else 0f
                                val label = when {
                                    kotlin.math.abs(a - 16f / 9f) < 0.08f || kotlin.math.abs(a - 9f / 16f) < 0.08f -> "16:9"
                                    kotlin.math.abs(a - 4f / 3f) < 0.08f || kotlin.math.abs(a - 3f / 4f) < 0.08f -> "4:3"
                                    kotlin.math.abs(a - 3f / 2f) < 0.08f || kotlin.math.abs(a - 2f / 3f) < 0.08f -> "3:2"
                                    kotlin.math.abs(a - 1f) < 0.08f -> "1:1"
                                    a > 0f -> "%.2f".format(a)
                                    else -> ""
                                }
                                val clip = android.content.ClipData.newPlainText("随拍", label)
                                val wrote = clipWrite(context, "随拍", clip)
                                copyHint = if (wrote) "已复制画幅" else "拷贝失败"
                            }
                        },
                    )
                }
            }
        }

    }
}

@Composable
private fun ZoomablePhoto(
    uri: Uri,
    contentDescription: String?,
    fillFrame: Boolean = false,
    rotation: Int = 0,
    mirrored: Boolean = false,
    flipV: Boolean = false,
    onScaleChange: (Float) -> Unit,
    onTap: () -> Unit = {},
    dismissEnabled: Boolean = false,
    onDismissDelta: (Float) -> Unit = {},
    onDismissEnd: () -> Unit = {},
) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val canDismiss = rememberUpdatedState(dismissEnabled && scale <= 1.02f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = if (mirrored) -scale else scale
                scaleY = if (flipV) -scale else scale
                translationX = offset.x
                translationY = offset.y
                rotationZ = rotation.toFloat()
            }
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 4.5f)
                    scale = next
                    offset = if (next <= 1.01f) Offset.Zero else offset + pan
                    onScaleChange(next)
                }
            }
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                            onScaleChange(1f)
                        } else {
                            scale = 2.4f
                            onScaleChange(2.4f)
                        }
                    },
                )
            }
            .pointerInput(uri) {
                detectVerticalDragGestures(
                    onDragEnd = { if (canDismiss.value) onDismissEnd() },
                    onVerticalDrag = { change, amount ->
                        if (!canDismiss.value) return@detectVerticalDragGestures
                        change.consume()
                        onDismissDelta(amount)
                    },
                )
            },
    ) {
        key(retryToken) {
            val painter = rememberAsyncImagePainter(model = uri)
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = if (fillFrame) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            when (painter.state) {
                is AsyncImagePainter.State.Success -> Unit
                is AsyncImagePainter.State.Error -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(PhotoLoadPlaceholder),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("无法显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "重试",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable { retryToken += 1 },
                            )
                        }
                    }
                }
                else -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(PhotoLoadPlaceholder),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val PhotoLoadPlaceholder = Color(0xFFE0E0E0)

private fun yishunLookName(name: String): String? {
    if (!name.startsWith("SuiPai_一瞬_")) return null
    val look = name.removePrefix("SuiPai_一瞬_").substringBefore('_')
    return look.takeIf { it.isNotBlank() && it.any { ch -> ch.isLetter() } }
}

private fun photoInfoLine(photo: PhotoItem): String {
    val size = when {
        photo.sizeBytes >= 1_000_000L -> "${"%.1f".format(photo.sizeBytes / 1_000_000.0)} MB"
        photo.sizeBytes >= 1_000L -> "${"%.0f".format(photo.sizeBytes / 1_000.0)} KB"
        photo.sizeBytes > 0L -> "${photo.sizeBytes} B"
        else -> ""
    }
    val pixels = if (photo.width > 0 && photo.height > 0) {
        "${photo.width}×${photo.height}"
    } else {
        ""
    }
    return listOfNotNull(
        pixels.ifBlank { null },
        size.ifBlank { null },
        photo.bucketName.ifBlank { null },
    ).joinToString("  ·  ")
}

private data class ExifBits(
    val line: String,
    val lat: Double? = null,
    val lng: Double? = null,
)

private fun readExifBits(context: android.content.Context, uri: Uri): ExifBits {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }?.let { exif ->
            val model = listOfNotNull(
                exif.getAttribute(ExifInterface.TAG_MAKE),
                exif.getAttribute(ExifInterface.TAG_MODEL),
            ).joinToString(" ").trim()
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                .takeIf { it > 0 }?.let { "ISO $it" }
            val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "快门 $it s" }
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            val focal = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                .takeIf { it > 0 }?.let { "${it}mm" }
            val ll = exif.latLong
            val gps = if (ll != null && ll.size >= 2) "位置 ${"%.5f".format(ll[0])}, ${"%.5f".format(ll[1])}" else null
            ExifBits(
                line = listOfNotNull(model.ifBlank { null }, iso, exp, aperture, focal, gps).joinToString("  ·  "),
                lat = ll?.getOrNull(0),
                lng = ll?.getOrNull(1),
            )
        }
    }.getOrNull() ?: ExifBits("")
}
