package com.suipai.app.ui.video

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.suipai.app.data.VideoItem
import com.suipai.app.data.VideoRepository
import com.suipai.app.data.clampSeekMs
import com.suipai.app.data.formatDuration
import com.suipai.app.data.formatSize
import com.suipai.app.data.videoFrameFileName
import com.suipai.app.data.videoStampText
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.design.PrimaryButton
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.theme.SuiPaiSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个视频的播放器。
 *
 * ## 只用系统能力，不引 Media3
 * 三个动作（播 / 分享 / 提取一帧）全是 `VideoView` + `MediaMetadataRetriever` 就够的事，
 * 引 Media3 要给包体加 1.5–3 MB、给 Data safety 申报加一条依赖，换回来的是
 * "能播 HLS"这种相册根本不需要的能力。理由与取舍写在 `docs/product/VIDEO.md` §2。
 *
 * ## 这一屏只吃数据、只回结论
 * 与 `DuplicatesScreen` / `TrashScreen` / [VideoGrid] 同一套分工：不 import `state` 包、
 * 不导航、不认识路由。视频从哪来由宿主决定；存成功之后只回报一句人话（`onSavedFrame`），
 * 宿主拿它去刷列表或换一句页眉。
 *
 * ## native 句柄
 * `MediaMetadataRetriever` 在这个文件里**一次都不直接 new** —— 取帧走
 * [VideoRepository.extractFrame]，那一处是 `try { … } finally { retriever.release() }`，
 * 且整个 UI 只有这一条取帧路径。多建一条路，就多一处"某天有人忘了 release"的机会。
 * 播放器自己的 `MediaPlayer` 由 `VideoView.stopPlayback()` 在 `onDispose` 里收掉。
 *
 * @param video 要播的那一段（`uri` 是 MediaStore 给的内容 uri）
 * @param onBack 页首「返回」
 * @param onSavedFrame 存帧成功之后回报一句人话（"存好了：SuiPai_视频帧_…jpg"）
 * @param note 宿主的一句话结论（"这一段是从微信分享进来的" "刚才那次没存上"）
 */
@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    onBack: () -> Unit,
    onSavedFrame: (String) -> Unit,
    note: String? = null,
) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 仓库只建一次：它没有可变状态，但下一行那些调用一律从这里走，不各自 new 一个。
    val repo = remember(context) { VideoRepository(context) }

    // 播放器实例按 video.id 建：换一段视频就换一个 VideoView（旧的由 onDispose 收掉）。
    val player = remember(video.id) { VideoView(context) }

    var prepared by remember(video.id) { mutableStateOf(false) }
    var playing by remember(video.id) { mutableStateOf(false) }
    var positionMs by remember(video.id) { mutableLongStateOf(0L) }
    var playerDurationMs by remember(video.id) { mutableLongStateOf(0L) }
    // 拖动进度条的时候，轮询不许把位置改回去（否则滑块会自己跳）。
    var scrubbing by remember(video.id) { mutableStateOf(false) }
    var scrubMs by remember(video.id) { mutableFloatStateOf(0f) }
    var saving by remember(video.id) { mutableStateOf(false) }
    // 这一屏自己的那句话（"正在提取这一帧…" / "这张帧读不出来"）。宿主的 note 在它上面。
    var status by remember(video.id) { mutableStateOf<String?>(null) }

    // `playerDuration` 在第一帧之前是 0；MediaStore 那一行的 DURATION 也可能是 0（录像不一定写）。
    // 两处都读不到就给 -1 —— `formatDuration(-1)` 是破折号，**不是 `0:00`**
    // （后者会被读成"这段视频长度是零"）。
    val totalMs = playerDurationMs.takeIf { it > 0L }
        ?: video.durationMs.takeIf { it > 0L }
        ?: -1L

    // 进度轮询：VideoView 不提供回调，只能按拍子问。
    // 250ms 一拍：滑块看起来是连续走的，而每拍只写一个 Long —— 整屏 5 次/秒的重组，
    // 比"每帧问一次"省得多（这台机器上没有硬件 GPU，见 handoff §1b）。
    // 句点里的 `scrubbing` 是快照读：拖动期间跳过写入，松手后立刻恢复。
    LaunchedEffect(player, prepared, playing) {
        while (playing && prepared) {
            if (!scrubbing) {
                positionMs = player.currentPosition.toLong().coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    fun togglePlay() {
        if (!prepared) {
            // 还没准备好：如实说，不做"看起来按了"的动画。
            status = "还在准备这一段，稍等一下"
            return
        }
        if (player.isPlaying) {
            player.pause()
            playing = false
            positionMs = player.currentPosition.toLong().coerceAtLeast(0L)
        } else {
            // 播完了再按播放 = 从头来一遍（VideoView 自己不会回到 0）。
            if (totalMs > 0L && positionMs >= totalMs - 60L) {
                player.seekTo(0)
                positionMs = 0L
            }
            player.start()
            playing = true
        }
    }

    fun shareVideo() {
        // 与查看器/相册同一个出口：没人接手时 `sendOrTell` 会说一句，不许静默。
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        sendOrTell(context, Intent.createChooser(send, "分享"), "分享这段视频") { status = it }
    }

    fun saveCurrentFrame() {
        if (saving) return
        // 拖动中按「存帧」= 存滑块停的那一格，而不是松手前的老位置。
        val atMs = (if (scrubbing) scrubMs.toLong() else positionMs).coerceAtLeast(0L)
        val stamp = System.currentTimeMillis()
        saving = true
        status = "正在提取这一帧…"
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    saveFrameToLibrary(repo, video, atMs, stamp)
                }
                status = when (outcome) {
                    FrameOutcome.SAVED -> {
                        // 名字与落盘用的是同一个函数（`videoFrameFileName`），
                        // 所以屏幕上说的和相册里躺的不会分家。
                        val said = "存好了：${videoFrameFileName(stamp)}（在相册的「随拍」里）"
                        onSavedFrame(said)
                        said
                    }
                    // 取不到帧的原因很多（关键帧坏了、文件被移走、解码器不支持），
                    // 而用户只关心"存没存上"——所以这里只说人话，不甩 what/extra。
                    FrameOutcome.UNREADABLE -> "这张帧读不出来"
                    FrameOutcome.WRITE_FAILED -> "这一帧没存上（系统不许往相册里写）"
                }
            } catch (e: CancellationException) {
                // 页面被关掉：协程正常取消，不写任何"失败"的话。
                throw e
            } finally {
                saving = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                video.displayName,
                style = SuiPaiSheet.type.frameNo,
                color = colors.ink,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(horizontal = Spacing.xxs),
            )
            AppBarCell(label = "分享", on = false, onClick = { shareVideo() })
            AppBarCell(
                label = if (saving) "存帧中" else "存帧",
                on = saving,
                onClick = { saveCurrentFrame() },
                enabled = prepared && !saving,
            )
        }

        val line = status ?: note
        if (line != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panelOverlay)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            ) {
                Text(line, style = SuiPaiType.bodySmall, color = colors.inkSecondary)
            }
        }

        // ── 画面 ──────────────────────────────────────────────────────
        // 监听器与 setVideoURI 在**同一个 factory** 里、且监听器先装：
        // 先 setVideoURI 再装 onPreparedListener 会留下"准备好得比监听器早"的窗口，
        // 那一窗口里 prepared 永远不翻，界面上就是一条点了没反应的播放键。
        // `key(video.id)` 是必须的：宿主换一段视频时这一层的组合位置不变，
        // 没有它 `AndroidView` 的 factory 不会再跑，画面会停在上一条。
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.canvasSunken),
            contentAlignment = Alignment.Center,
        ) {
            key(video.id) {
                AndroidView(
                    factory = {
                        player.apply {
                            setOnPreparedListener { mp ->
                                prepared = true
                                playerDurationMs = (mp?.duration ?: 0).toLong().coerceAtLeast(0L)
                                positionMs = 0L
                                start()
                                playing = true
                            }
                            setOnCompletionListener {
                                playing = false
                                positionMs = duration.toLong().coerceAtLeast(0L)
                            }
                            setOnErrorListener { _, what, extra ->
                                // 一台设备播不了某段编码是常事（HEVC / 10bit / 容器不认识）。
                                // 这里必须说一句话，返回 true 表示已经处理过 —— 返回 false 会让系统
                                // 弹一个"无法播放此视频"的英文框，那句话既不是我们的口气也说不出原因。
                                playing = false
                                status = "这段视频播不了（解码器回报 $what/$extra）"
                                true
                            }
                            setVideoURI(video.uri)
                        }
                    },
                    // `stopPlayback` 内部会 release 掉 MediaPlayer；之后再调 release 是多余的。
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── 进度 ──────────────────────────────────────────────────────
        val shownPosition = if (scrubbing) scrubMs.toLong() else positionMs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDuration(shownPosition),
                style = SuiPaiType.frameNo,
                color = colors.inkSecondary,
                maxLines = 1,
            )
            Slider(
                value = shownPosition.toFloat().coerceIn(0f, totalMs.coerceAtLeast(1L).toFloat()),
                onValueChange = {
                    scrubbing = true
                    scrubMs = it
                },
                onValueChangeFinished = {
                    // 夹取用数据层那条判据：`duration <= 0` 一律回 0，
                    // 否则滑块值会被当成 seekTo 的实参送进播放器（越界行为各解码器不一致）。
                    val target = clampSeekMs(scrubMs.toLong(), totalMs)
                    player.seekTo(target.toInt())
                    positionMs = target
                    scrubbing = false
                },
                // 名字必须写在这个 `Slider(` 自己的括号里（A11ySourceGuardTest 的判据）：
                // material3 1.3.1 只有调用方 modifier 上的语义会落到与 sliderSemantics
                // 同一个节点，不写的话 TalkBack 到那里只报「滑动条」。
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.xs)
                    .semantics { contentDescription = "播放进度" },
                enabled = prepared && totalMs > 0L,
                valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
            )
            Text(
                formatDuration(totalMs),
                style = SuiPaiType.frameNo,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }

        // ── 动作 ──────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            PrimaryButton(
                label = if (playing) "暂停" else "播放",
                onClick = { togglePlay() },
                modifier = Modifier.widthIn(min = 96.dp),
            )
            Spacer(Modifier.width(Spacing.xxs))
            Column(Modifier.weight(1f)) {
                Text(
                    metaLine(video),
                    style = SuiPaiType.parameter,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「存帧」这个词不解释一下没人知道它会存成什么。
                Text(
                    "「存帧」把画面存成一张照片，进相册的「随拍」",
                    style = SuiPaiType.labelSmall,
                    color = colors.inkTertiary,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 这一段的一行参数：`5.2 MB · 1920×1080 · 2026.10.18 20:54`。
 *
 * 三段都用数据层已有的格式化函数（[formatSize] / [videoStampText]），
 * 界面不再抄一份字节换算 —— 抄一份就有一份会先漂。
 */
private fun metaLine(item: VideoItem): String = buildList {
    if (item.sizeBytes > 0L) add(formatSize(item.sizeBytes))
    if (item.width > 0 && item.height > 0) add("${item.width}×${item.height}")
    if (item.dateTaken > 0L) add(videoStampText(item.dateTaken))
}.joinToString(" · ").ifEmpty { "—" }

/** 存一帧的三种结局。分开说，因为"读不出来"和"写不进去"对用户是两件事。 */
private enum class FrameOutcome { SAVED, UNREADABLE, WRITE_FAILED }

/**
 * 取帧 → 落盘，全在 IO 上。
 *
 * 落盘走 [VideoRepository.saveFrame] → `MediaRepository.saveBitmap`，
 * 与相机 / 一瞬 / 副本 / 拼图**同一条口径**（`Pictures/SuiPai`、`IS_PENDING` 两段式、
 * 文件名 `SuiPai_视频帧_<时间戳>.jpg` 让它不算"随拍原片"）。
 * 这一屏不自己拼 `ContentValues`：多一条自写的 MediaStore 通道，就多一处
 * "扩展名和内容不符""半截文件留在库里"的机会。
 *
 * 位图**不手动 recycle**：它已经被 `saveBitmap` 压进 byte array 了，
 * 交给 GC 才安全 —— `recycle` 与"别处还捏着引用"撞上就是
 * `Canvas: trying to use a recycled bitmap`（一瞬那个位图袋实测崩过 12 次）。
 */
private suspend fun saveFrameToLibrary(
    repo: VideoRepository,
    video: VideoItem,
    atMs: Long,
    stampMs: Long,
): FrameOutcome = withContext(Dispatchers.IO) {
    // extractFrame 内部已经把 MediaMetadataRetriever 关在 try/finally 里 release 掉了。
    val frame = repo.extractFrame(video.uri, atMs) ?: return@withContext FrameOutcome.UNREADABLE
    val saved: Uri? = try {
        repo.saveFrame(frame, stampMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 权限/空间/提供方都可能在这儿抛，而抛出去就是"点一下崩一次"。
        android.util.Log.w("VideoPlayerScreen", "这一帧没存上：${video.id} @ $atMs ms", e)
        null
    }
    if (saved != null) FrameOutcome.SAVED else FrameOutcome.WRITE_FAILED
}
