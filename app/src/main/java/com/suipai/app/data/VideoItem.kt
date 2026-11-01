package com.suipai.app.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 视频这一层的数据结构与**纯逻辑**判据。
 *
 * 为什么把格式化和判据放在数据层而不是界面里：
 * `VideoItem` 握着 `android.net.Uri`，JVM 单测造不出来（`Uri.parse` 在 android.jar 里是桩，
 * 返回 null 之后连累整个类初始化）。所以凡是"能脱离 Android 跑"的东西 ——
 * 时长怎么写、大小怎么说、进度该夹到哪、空态该说哪句话 —— 全部收成本文件里的
 * 顶层纯函数，界面只负责画。`VideoDurationTest` 逐个钉住它们。
 */

/**
 * 一行 MediaStore 视频记录的快照。
 *
 * 标 @Immutable 与 `PhotoItem` 同一条理由：Compose 认不出平台类 `Uri`，默认把整个 data class
 * 判成 unstable，于是一屏十几格的封面会跟着任何一次选择态变化全部重组。
 * 这个类**只读**：视频这一版不写 MediaStore（不删、不转码、只提取一帧另存成照片）。
 */
@Immutable
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    val bucketId: String,
    val bucketName: String,
)

/**
 * 这一屏能不能读到视频。
 *
 * 四格而不是一个布尔：`PARTIAL` = 仅授权部分视频，`UNKNOWN` = 未确认。
 * 把「未确认 / 未授权 / 部分可见」都当整库，会让用户误以为视频丢失。
 */
enum class VideoAccess { GRANTED, PARTIAL, MISSING, UNKNOWN }

/** 空态那一对文案。看得见的与念出来的必须同源，所以由同一处给。 */
internal data class VideoEmpty(val title: String, val note: String?)

/**
 * 视频列表空着的原因分开说：未授权 / 部分授权 / 状态未知 / 正在读 / 读取失败 / 确实为空。
 * Android 13 起照片授权不覆盖视频，MediaStore 缺权时还可能静默回空游标。
 * 读取失败也不能误报成「还没有视频」；只有已授权且查库成功的空表才可以这样说。
 */
internal fun videoEmptyCopy(access: VideoAccess, loaded: Boolean, readFailed: Boolean = false): VideoEmpty = when {
    access == VideoAccess.MISSING -> VideoEmpty(
        title = "还未授权读取视频",
        note = "视频需要单独的 READ_MEDIA_VIDEO 权限。点「授权视频」后，才会向系统申请；照片不受影响。",
    )
    access == VideoAccess.UNKNOWN -> VideoEmpty(
        title = "暂时无法确认视频权限",
        note = "请稍后返回再进入视频页，或检查系统中的应用授权。",
    )
    !loaded -> VideoEmpty(title = "正在读视频", note = "第一次读整库会慢一点，读完就记在缓存里")
    readFailed -> VideoEmpty(
        title = "读取视频失败",
        note = "系统媒体库本次没有返回可用结果。请返回再试，照片并未被删除。",
    )
    access == VideoAccess.PARTIAL -> VideoEmpty(
        title = "已授权的视频中没有内容",
        note = "当前只允许访问部分照片和视频，未选中的视频不会出现在这里。可以点「补选视频」扩大范围。",
    )
    else -> VideoEmpty(title = "还没有视频", note = "相机或别的应用录的视频会自己出现在这里")
}

/**
 * 时长：`0:07` / `1:05` / `1:02:03`。
 *
 * 向下取整（不四舍五入）：播放器停在同一秒时，显示的那一秒必须已经播完了，
 * 否则用户看到 `1:05` 而实际还在第 64.9 秒。负数 = 时长未知，给的是破折号而不是
 * `0:00` —— 后者会被读成"这段视频长度是零"。
 */
internal fun formatDuration(ms: Long): String {
    if (ms < 0L) return "—"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/**
 * 文件大小：`0 B` / `999 B` / `1.0 KB` / `2.5 MB` / `1.00 GB`。
 *
 * 与 `ui/settings/StorageScreen.kt` 的 `formatBytes` 同一条口径（小于 1 KB 保留到 B、
 * 小数位随量级收窄），但**不复用**它：那个函数在 `ui.settings` 包里，是设置页的现成文案，
 * 而数据层不该反过来依赖界面层。两边都从"字节数"这一个输入出发，判据由各自的单测钉住。
 */
internal fun formatSize(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
}

/**
 * 把要跳到的位置夹回 [0, durationMs]。
 *
 * `durationMs <= 0` 一律回 0：`VideoView.duration` 在第一帧之前是 -1，把它当上限去夹
 * 会把 `seekTo(-1)` 送进播放器 —— 那在某些解码器上不是"回到开头"，是一次失败回调。
 */
internal fun clampSeekMs(ms: Long, durationMs: Long): Long {
    if (durationMs <= 0L || ms <= 0L) return 0L
    return if (ms > durationMs) durationMs else ms
}

/** 提取出来的那一帧叫什么。与相机原片同族，但第二个下划线让它**不算原片**（见 `isSuiPaiOriginalName`）。 */
internal fun videoFrameFileName(stampMs: Long): String = "SuiPai_视频帧_$stampMs.jpg"

/**
 * 封面缓存的文件名：`<id>_<边长>_<字节数>.jpg`。
 *
 * 为什么把 `sizeBytes` 也写进名字：MediaStore 的 id 是"行"的 id，就地替换/重写同一行的
 * 内容（某些同步工具会这么干）时 id 不变而画面变了 —— 只按 id 命名就会一直显示上一段视频的封面。
 * 带上字节数之后，内容一变名字就变，旧文件交给容量上限去淘汰。
 */
internal fun coverCacheName(id: Long, edgePx: Int, sizeBytes: Long): String = "${id}_${edgePx}_${sizeBytes}.jpg"

/**
 * 视频读权限的名字。
 *
 * 取 `Int` 而不是内部读 `Build.VERSION.SDK_INT`：这样这张表能在 JVM 单测里逐格核对，
 * 而"这台机器上是几"由调用方给一次。**不引用 `Manifest.permission.READ_MEDIA_VIDEO`**：
 * 那是个 API 33 才有的字段，写成字面量既躲开 lint 的 InlinedApi，也不会在 26 上
 * 变成一次 NoSuchFieldError。
 */
internal fun videoReadPermission(sdk: Int): String =
    if (sdk >= 33) "android.permission.READ_MEDIA_VIDEO" else "android.permission.READ_EXTERNAL_STORAGE"

/** 视频是可选功能：仅在用户点视频页的「授权 / 补选」时才请求，不能跟照片首启授权绑定。 */
internal fun videoPermissionRequest(sdk: Int): Array<String> = when {
    sdk >= 34 -> arrayOf(
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    )
    sdk >= 33 -> arrayOf("android.permission.READ_MEDIA_VIDEO")
    else -> emptyArray() // Android 12 及以下由照片权限门统一请求 READ_EXTERNAL_STORAGE。
}

/** Android 14 选中授权优先保守判成部分；即使全量和选中标记都在，也不声称看到了整库。 */
internal fun videoAccessFor(sdk: Int, fullGranted: Boolean, selectedGranted: Boolean): VideoAccess = when {
    sdk >= 34 && selectedGranted -> VideoAccess.PARTIAL
    fullGranted -> VideoAccess.GRANTED
    else -> VideoAccess.MISSING
}

/**
 * 拍摄时间：`2026.10.18 20:54`。0 = 未知，给破折号。
 *
 * `zone` 可注入：单测要能钉住输出，而不是跟着跑测试那台机器的时区漂。
 */
private val StampPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.ROOT)

internal fun videoStampText(takenMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (takenMs <= 0L) return "—"
    return Instant.ofEpochMilli(takenMs).atZone(zone).format(StampPattern)
}

/**
 * 网格里一格视频在**读屏里说的那一句**。
 *
 * 与 `photoCellLabel` 同一套纪律：看得见的（封面角标 + 两行小字）和念出来的必须同一个来源，
 * 否则两边迟早分叉，而分叉的那一半永远是读屏用户吃亏。
 */
internal fun videoCellLabel(item: VideoItem): String = buildString {
    append(item.displayName)
    append("，时长 ")
    append(if (item.durationMs > 0L) formatDuration(item.durationMs) else "未知")
    if (item.sizeBytes > 0L) {
        append("，")
        append(formatSize(item.sizeBytes))
    }
    if (item.width > 0 && item.height > 0) {
        append("，")
        append("${item.width}×${item.height}")
    }
    if (item.dateTaken > 0L) {
        append("，")
        append(videoStampText(item.dateTaken))
    }
    append("。点它是播放")
}

/** 封面缓存该不该现在清一遍：超过预算才动手，免得每次写封面都扫一遍目录。 */
internal fun shouldTrimThumbCache(totalBytes: Long, budgetBytes: Long): Boolean =
    budgetBytes > 0L && totalBytes > budgetBytes
