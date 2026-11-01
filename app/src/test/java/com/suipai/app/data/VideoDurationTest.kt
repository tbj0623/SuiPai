package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 视频这一层的**纯逻辑**判据。全部在 JVM 上跑，一个 `android.*` 的静态方法都不碰。
 *
 * `VideoItem` 握着 `android.net.Uri`，而单测里 `Uri.parse` 是返回 null 的桩 ——
 * 谁在测试里 `Uri.parse` 一下，整个类的初始化就崩在那儿，后面所有断言都不会跑。
 * 所以被判的东西全部是 `Long` / `String` / `Int` 进、`String` / `Boolean` 出。
 *
 * 每一族都配一条"喂坏例子必须变红"的自检：没见过自己变红的守卫不算守卫
 * （这是这个仓库的硬纪律，见 `docs/handoff.md` §8.2）。
 */
class VideoDurationTest {

    // ── 时长 ──────────────────────────────────────────────────────────

    @Test
    fun durationUnderOneHourDropsTheHourField() {
        assertEquals("0:07", formatDuration(7_000L))
        assertEquals("1:05", formatDuration(65_000L))
        // 59:59 还是两段
        assertEquals("59:59", formatDuration(3_599_000L))
    }

    @Test
    fun durationOverOneHourGetsTheHourField() {
        assertEquals("1:02:03", formatDuration(3_723_000L))
        assertEquals("10:00:00", formatDuration(36_000_000L))
    }

    /**
     * 向下取整，不是四舍五入。
     *
     * 63.9 秒如果显示成 `1:04`，而播放器**还在**第 63 秒，用户看到的那一秒和听到的那一秒
     * 差了一秒 —— 这类错在拖动进度条时最容易看出来（每次都跳一格）。
     */
    @Test
    fun durationTruncatesInsteadOfRounding() {
        assertEquals("1:03", formatDuration(63_900L))
        assertEquals("1:03", formatDuration(63_000L))
        assertEquals("1:04", formatDuration(64_000L))
        // 999ms 还没到 1 秒
        assertEquals("0:00", formatDuration(999L))
    }

    /**
     * 负数 = 时长未知。给的是破折号，不是 `0:00`。
     *
     * `MediaMetadataRetriever` 读不到时长时回 -1；把它格式化成 `0:00` 会被读成
     * "这段视频长度是零" —— 和"还没读到"是两件事。
     */
    @Test
    fun unknownDurationIsADashNotZero() {
        assertEquals("—", formatDuration(-1L))
        assertEquals("—", formatDuration(-999_999L))
        assertTrue("未知时长不许长得像 0:00", formatDuration(-1L) != formatDuration(0L))
        // 自检：把判据退回"一律当日志格式化"，这一格必须变红
        val naive = { ms: Long -> "${ms / 60_000}:${(ms / 1000) % 60}" }
        assertTrue("自检失效：老写法居然也把 -1 说成了破折号", naive(-1L) != "—")
    }

    @Test
    fun durationIsMonotonicAcrossTheSecondBoundary() {
        // 同一秒内的两个毫秒值必须给同一个字符串（否则进度条每帧都在改字）
        assertEquals(formatDuration(60_000L), formatDuration(60_999L))
        // 跨过一秒必须变
        assertTrue(formatDuration(60_999L) != formatDuration(61_000L))
    }

    // ── 大小 ──────────────────────────────────────────────────────────

    @Test
    fun sizeBelowOneKilobyteKeepsBytes() {
        assertEquals("0 B", formatSize(0L))
        assertEquals("999 B", formatSize(999L))
        // 1023 仍是 B 档：进 KB 的那一刀切在 1024
        assertEquals("1023 B", formatSize(1023L))
    }

    @Test
    fun sizeUnitsAdvanceAtEach1024() {
        assertEquals("1.0 KB", formatSize(1024L))
        assertEquals("1.5 KB", formatSize(1536L))
        assertEquals("1.0 MB", formatSize(1024L * 1024L))
        assertEquals("2.5 MB", formatSize((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.00 GB", formatSize(1024L * 1024L * 1024L))
    }

    /** 负数是"没读到"（MediaStore 的 SIZE 允许为 NULL → 0，但有些提供方给 -1）。 */
    @Test
    fun negativeSizeIsADash() {
        assertEquals("—", formatSize(-1L))
        // 自检：老写法 `"$bytes B"` 会把 -1 说成 "-1 B"，看起来像一个真实的负数大小
        val naive = { bytes: Long -> "$bytes B" }
        assertTrue("自检失效：老写法居然也没把 -1 说成大小", naive(-1L) != "—")
    }

    /**
     * 跨单位边界时**必须换单位**，而且边界两侧的字符串必须不同 ——
     * 那才是"某一档永远显示不出来"的真判据。
     *
     * 第一版这条写错了：把 `1024` 与 `1025` 也塞进"必须互不相同"的点集里。
     * 它们都在 KB 档内、都格式化到一位小数，于是都是 `1.0 KB`。那不是缺陷，
     * 是**显示精度决定了同一档内相邻字节必然重名**（KB 档一位小数只能分辨 102 字节）。
     * 把"跨档不重名"和"同档内可重名"混成一条断言，结果是测试红着而代码是对的 ——
     * 这类"断言写错"比"代码写错"更危险，因为它会诱使人去改正确的实现。
     *
     * 现在的判据拆成三条，每条都只钉它真的成立的东西：
     * 1. **跨边界**（1023→1024、1048575→1048576、1073741823→1073741824）必须换单位；
     * 2. 同一档内，数值随字节数**单调不减**；
     * 3. 每个边界点的输出都等于它所在档位的正确写法（硬编码期望，不自我循环）。
     */
    @Test
    fun unitBoundariesChangeUnitAndStayMonotonicInsideEachTier() {
        // 1. 跨边界必须换单位（判据是后缀，不是整个字符串）
        val boundaries = listOf(
            1023L to "B",
            1024L to "KB",
            1_048_575L to "KB",
            1_048_576L to "MB",
            1_073_741_823L to "MB",
            1_073_741_824L to "GB",
        )
        for ((bytes, want) in boundaries) {
            val unit = formatSize(bytes).substringAfter(' ')
            assertEquals("$bytes 的单位不是 $want：${formatSize(bytes)}", want, unit)
        }
        // 自检：边界两侧的字符串确实不同（同一档内允许重名，跨档不允许）
        assertTrue("1023 与 1024 说出了同一句话", formatSize(1023L) != formatSize(1024L))
        assertTrue("1 MB-1 与 1 MB 说出了同一句话", formatSize(1_048_575L) != formatSize(1_048_576L))
        // 自检：把"同一档内相邻字节可重名"这件事显式钉住 —— 它是有意的，不是漏网
        assertEquals("同档内相邻字节重名是显示精度的必然", formatSize(1024L), formatSize(1025L))

        // 2. 同一档内数值单调不减（解析回 double 再比，跨档不比）
        val kbTier = listOf(1024L, 2048L, 512L * 1024L, 1_048_575L)
        val kbValues = kbTier.map { formatSize(it).removeSuffix(" KB").toDouble() }
        assertEquals(
            "KB 档内没有单调不减：$kbValues",
            kbValues.sorted(),
            kbValues,
        )
        // 自检：把序列人为打乱必须不再单调（证明上面那句不是恒真）
        assertTrue("自检失效：打乱之后居然还是单调的", kbValues.reversed() != kbValues.sorted())
    }

    // ── 进度夹取 ──────────────────────────────────────────────────────

    @Test
    fun clampKeepsSeekInsideTheClip() {
        assertEquals(0L, clampSeekMs(0L, 60_000L))
        assertEquals(5_000L, clampSeekMs(5_000L, 60_000L))
        assertEquals(60_000L, clampSeekMs(60_000L, 60_000L))
        // 超出尾部：夹到尾部而不是让播放器自己去处理（VideoView 对越界 seek 的行为不统一）
        assertEquals(60_000L, clampSeekMs(999_999L, 60_000L))
    }

    @Test
    fun clampFallsBackToZeroWhenDurationIsUnknown() {
        // duration = -1 是 VideoView 在第一帧之前的真实取值。
        // 拿它当上限会把 seekTo(-1) 送进播放器。
        assertEquals(0L, clampSeekMs(5_000L, -1L))
        assertEquals(0L, clampSeekMs(5_000L, 0L))
        // 负的请求位置也回 0
        assertEquals(0L, clampSeekMs(-5_000L, 60_000L))
    }

    @Test
    fun clampSelfCheckCatchesUnclampedSeek() {
        val naive = { ms: Long -> ms }        // "直接用滑块值"的老写法
        assertTrue("自检失效：不夹取的写法居然也没越界", naive(999_999L) > 60_000L)
        assertTrue("夹取之后的写法必须落在区间内", clampSeekMs(999_999L, 60_000L) <= 60_000L)
    }

    // ── 权限名字 ──────────────────────────────────────────────────────

    /**
     * 13（API 33）是分界线：从这一版起视频有**自己**的读权限，上面那条不再覆盖它。
     * 这张表判错一格的代价是"视频页永远空着而界面说没有视频"。
     */
    @Test
    fun videoPermissionNameFollowsTheSdkLine() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", videoReadPermission(26))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", videoReadPermission(32))
        assertEquals("android.permission.READ_MEDIA_VIDEO", videoReadPermission(33))
        assertEquals("android.permission.READ_MEDIA_VIDEO", videoReadPermission(36))
        // 自检：整张表退回"一律用旧权限"，13 以上那一格必须变红
        val naive = { _: Int -> "android.permission.READ_EXTERNAL_STORAGE" }
        assertTrue("自检失效：老写法居然在 33 上也给了新权限名", naive(33) != videoReadPermission(33))
    }

    // ── 空态文案 ──────────────────────────────────────────────────────

    /**
     * 三件事必须分成三句话：**系统没放行** / **还没读完** / **库里确实没有**。
     * 把第一件说成第三件，就是这个仓库反复点名的"把没量到写成已量到"。
     */
    @Test
    fun emptyCopySeparatesMissingPermissionFromAnEmptyLibrary() {
        val missing = videoEmptyCopy(VideoAccess.MISSING, loaded = true)
        val loading = videoEmptyCopy(VideoAccess.GRANTED, loaded = false)
        val empty = videoEmptyCopy(VideoAccess.GRANTED, loaded = true)

        assertEquals(3, setOf(missing.title, loading.title, empty.title).size)
        // 「没权限」那句必须点名那条权限，否则用户/开发者无从知道要加什么
        assertTrue("没权限那句没提 READ_MEDIA_VIDEO：${missing.note}", missing.note?.contains("READ_MEDIA_VIDEO") == true)
        assertFalse("还没读完时不许说「没有视频」", loading.title.contains("没有视频"))
        assertFalse("库里确实没有时不许说「正在读」", empty.title.contains("正在读"))
    }

    @Test
    fun emptyCopySelfCheckCatchesTheSingleVerdict() {
        // 老写法：三种情况共用一句"还没有视频"
        val naive = { _: VideoAccess, _: Boolean -> "还没有视频" }
        assertTrue(
            "自检失效：一句到底的写法居然也分出了三句",
            naive(VideoAccess.MISSING, true) != videoEmptyCopy(VideoAccess.MISSING, true).title,
        )
    }

    // ── 命名与缓存键 ───────────────────────────────────────────────────

    /**
     * 提取帧的文件名必须**不算随拍原片**。
     *
     * `GalleryScreen.isSuiPaiOriginalName` 的判据是"`SuiPai_` 之后不许再有下划线"，
     * 所以 `SuiPai_视频帧_<时间戳>.jpg` 会正确地落进"加工件"，而不是混进「仅随拍」那一档
     * （那一档的空态写着「还没有随拍**原片**」）。
     */
    @Test
    fun frameFileNameIsNotAnOriginalShot() {
        val name = videoFrameFileName(1_790_023_855_356L)
        assertEquals("SuiPai_视频帧_1790023855356.jpg", name)
        assertFalse("提取帧不许被认成随拍原片", name.startsWith("SuiPai_") && !name.substring(7).contains("_"))
        // 自检：真正的原片形状必须过那条判据（否则这条断言是恒真的）
        assertTrue(
            "自检失效：原片形状也被同一条判据拒了",
            "SuiPai_1790023855356.jpg".startsWith("SuiPai_") &&
                !"SuiPai_1790023855356.jpg".substring(7).contains("_"),
        )
    }

    @Test
    fun frameFileNameIsStableForOneStampAndDiffersAcrossStamps() {
        assertEquals(videoFrameFileName(42L), videoFrameFileName(42L))
        assertTrue(videoFrameFileName(42L) != videoFrameFileName(43L))
    }

    /**
     * 封面缓存的键带 `sizeBytes`：同一个 id 被原地换掉内容（同步工具会这么干）时，
     * 键跟着变，于是不会一直显示上一段视频的封面。
     */
    @Test
    fun coverCacheKeyMovesWhenTheContentChanges() {
        val a = coverCacheName(7L, 320, 1_000L)
        val b = coverCacheName(7L, 320, 2_000L)
        assertTrue("同一 id、不同大小必须给不同的缓存键", a != b)
        assertEquals("同一 id、同一大小必须稳定", a, coverCacheName(7L, 320, 1_000L))
        // 不同边长也必须是不同的文件（换网格大小时不许串用一张糊图）
        assertTrue(coverCacheName(7L, 320, 1_000L) != coverCacheName(7L, 640, 1_000L))
        assertTrue(a.endsWith(".jpg"))
    }

    @Test
    fun thumbCacheTrimsOnlyAboveTheBudget() {
        assertFalse(shouldTrimThumbCache(0L, VideoThumbs.DISK_BUDGET_BYTES))
        assertFalse(shouldTrimThumbCache(VideoThumbs.DISK_BUDGET_BYTES, VideoThumbs.DISK_BUDGET_BYTES))
        assertTrue(shouldTrimThumbCache(VideoThumbs.DISK_BUDGET_BYTES + 1, VideoThumbs.DISK_BUDGET_BYTES))
        // 预算为 0（配置算错）时必须一个都不删，而不是把整目录删空
        assertFalse("预算 0 时不许删任何东西", shouldTrimThumbCache(1_000_000L, 0L))
    }

    // ── 时间戳 ────────────────────────────────────────────────────────

    @Test
    fun stampTextUsesTheInjectedZone() {
        val ms = 1_700_000_000_000L      // 2023-11-14T22:13:20Z
        assertEquals("2023.11.14 22:13", videoStampText(ms, ZoneId.of("UTC")))
        // 同一个瞬间在 UTC+8 是第二天早上
        assertEquals("2023.11.15 06:13", videoStampText(ms, ZoneId.of("Asia/Shanghai")))
        // 未知时间给破折号，不是 1970
        assertEquals("—", videoStampText(0L, ZoneId.of("UTC")))
        assertEquals("—", videoStampText(-1L, ZoneId.of("UTC")))
    }
}
