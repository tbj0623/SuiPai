package com.suipai.app.widget

/**
 * 桌面小组件那一层**不碰 android 的**部分。
 *
 * 为什么要把它们从 [RecentWidgetProvider] 里拆出来：这个仓库的纪律是"能纯函数就纯函数"
 * （见 `state/NavPolicy.kt`、`settings/OnboardingGate.kt` 都是这么做的）。小组件尤其需要 ——
 * 它的正确性全在真机上才看得见（桌面是个我们测不了的宿主），
 * 留在 JVM 上测得到的就只剩"选哪几张 / 说什么话 / 用哪个布局 / 什么时候重试"。
 *
 * 本文件**一个 `android.*` 符号都不许出现**：JVM 单测里 android.jar 全是返回默认值的桩，
 * 一旦这里沾上 `Uri` / `TextUtils`，`WidgetLogicTest` 就会变成"永远绿但什么都没验"。
 */

/**
 * 小组件要画的**一张缩略图**。
 *
 * `uri` 是 `content://media/external/images/media/<id>` 这一串**字符串**，
 * 不是 `android.net.Uri` —— 见文件头那条：这一层不许碰 android 类型。
 * 组装成真 Uri 是 [RecentWidgetProvider] 的事。
 */
data class WidgetPhoto(
    /** MediaStore 行号。同一张照片只该出现一次，[selectRecent] 按它去重。 */
    val id: Long,
    /** `content://` 全串（点缩略图要拿它当查看器的目标）。 */
    val uri: String,
    /** 拍摄时刻（毫秒）。`DATE_TAKEN` 缺失时由 `DATE_ADDED * 1000` 兜底。 */
    val dateTaken: Long,
)

/** 小组件可选的两种尺寸。 */
enum class WidgetSize {
    /** 2×2：一格大图打头（最近一张），下面一条说明。 */
    Small2x2,

    /** 4×2：四张并排缩略图 + 一条说明。 */
    Wide4x2,
    ;

    /**
     * 对应的 `RemoteViews` 布局名。与 `res/layout/` 里的文件名逐字一致。
     *
     * 注意它**不能**拿去 `getIdentifier` 查 —— 那是运行时反射、会被 R8 改名的那一类做法。
     * 真取布局时用的是 `R.layout.xxx`（见 [RecentWidgetProvider.draw]），
     * 这个名字只给文档与 `WidgetLogicTest`（它去磁盘上核对布局文件确实存在）用。
     */
    val layoutName: String
        get() = when (this) {
            Small2x2 -> "widget_recent_2x2"
            Wide4x2 -> "widget_recent_4x2"
        }

    companion object {
        /**
         * 由宿主给的宽度（dp）挑一套布局。
         *
         * 门槛取 [WIDE_MIN_WIDTH_DP]：2×2 的格子通常 110–180dp，4×2 是 250–320dp。
         * 门槛写成 200 会在某些 ROM 的 3×2 格子上跳到 4×2，而 3 格宽塞四张图会把最后一张裁掉一半。
         *
         * 拿不到宽（0 / 负数 / 宿主没给）时一律退回 [Small2x2]：
         * 猜大了是"图被裁"，猜小了只是"少显示几张"，后者不影响理解。
         */
        fun forWidthDp(widthDp: Int): WidgetSize =
            if (widthDp >= WIDE_MIN_WIDTH_DP) Wide4x2 else Small2x2

        /** 从 `AppWidgetManager.OPTION_APPWIDGET_MIN/MAX_WIDTH` 那两个值里挑尺寸。 */
        fun forOptions(minWidthDp: Int, maxWidthDp: Int): WidgetSize =
            forWidthDp(if (minWidthDp > 0) minWidthDp else maxWidthDp)

        /** 250dp 是四张并排缩略图不被裁切的实测下限（见 docs/product/WIDGET.md）。 */
        const val WIDE_MIN_WIDTH_DP = 250
    }
}

/**
 * 一次刷新的结果。**三态**，不是两态。
 *
 * `Empty` 与 `Unavailable` 分开，理由和 `MediaRepository.LibraryRead` 里
 * "`null` = 没读到 / 空表 = 库里确实没有"完全一样：
 * 混成一种的话，媒体提供方抖一下（撤权限、partial access、进程被杀）就会让桌面写
 * 「随拍 · 0 张」，用户看到的是"照片没了"。没读到的时候必须留着上一次的画面。
 */
sealed interface WidgetState {

    /**
     * 读到了。[photos] 至多 [WidgetShape.MAX_THUMBS] 张（从新到旧），
     * [total] 是库里**一共**多少张 —— 那一行字报的是总数，不是这四张。
     */
    data class Loaded(val photos: List<WidgetPhoto>, val total: Int) : WidgetState

    /** 读到了，库里确实一张都没有（全新安装 / 照片全删了）。 */
    data object Empty : WidgetState

    /**
     * **没读到**（查询抛了、游标是 null、媒体库没答话）。
     * [reason] 只进 logcat 与测试，不上桌面 —— 桌面那句是固定文案。
     */
    data class Unavailable(val reason: String) : WidgetState

    /** 桌面上那一行说明。 */
    val caption: String
        get() = when (this) {
            is Loaded -> "随拍 · $total 张"
            Empty -> "随拍 · 还没有照片"
            is Unavailable -> "随拍 · 没读到照片"
        }
}

/**
 * 小组件的形状常量。布局 XML 里的 `visibility` 由 [visibleSlots] 决定，
 * 两处只认这一个真值来源。
 */
object WidgetShape {

    /** 桌面上最多摆几张缩略图（4×2 的格数；2×2 只用其中的第 0 格）。 */
    const val MAX_THUMBS = 4

    /** 宿主刷新最多重试几次（含第一次）。见 [WidgetRetry]。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 4×2 单张缩略图的边长上限（px）。
     *
     * 由 Binder 预算反推：4 张 × edge² × 4 字节（ARGB_8888）必须明显小于
     * 一次 `RemoteViews` 事务的一半预算（512 KB）——
     * `4 × 144² × 4 = 331 KB`，剩下 180 KB 给布局、文本与其它字段。
     *
     * 这个常量存在的意义是不让"上限"散落在 [thumbEdgePx] 的 `coerceIn` 里：
     * 单测要检查它，而写在调用点里的字面量检查不到。
     */
    const val WIDE_EDGE_MAX = 144

    /**
     * 这套布局要显示前几格。
     *
     * 2×2 只显示 1 张：那个格子最宽也就 180dp，塞四张会变成四条 40dp 的缝。
     * 4×2 显示 [MAX_THUMBS] 张。
     */
    fun visibleSlots(size: WidgetSize, loaded: Int): Int = when (size) {
        WidgetSize.Small2x2 -> if (loaded > 0) 1 else 0
        WidgetSize.Wide4x2 -> loaded.coerceIn(0, MAX_THUMBS)
    }
}

/**
 * 「取最近 N 张」的选取规则 —— 纯函数，`WidgetLogicTest` 直接喂例子。
 *
 * 四件事一起做，顺序是承重的：
 *  1. **滤掉取不到缩略图的行**：`id <= 0` 或 uri 是空串的行画出来是个空框，
 *     不如把位置让给下一张。
 *  2. **去重**：MediaStore 在 partial access（Android 14 的"选中的照片"）下会把同一行
 *     报两遍；不去重的话同一张照片会占掉四格里的两格。按 `id` 判重，
 *     **保留第一次出现**（调用方已经按时间倒好序）。
 *  3. **截到 [limit]**。
 *
 * `limit <= 0` 一律返回空表（而不是"全给"）：调用点写错一个 0 不该让桌面塞进五千张图。
 */
fun selectRecent(raw: List<WidgetPhoto>, limit: Int = WidgetShape.MAX_THUMBS): List<WidgetPhoto> {
    if (limit <= 0) return emptyList()
    val seen = LinkedHashSet<Long>(raw.size)
    val out = ArrayList<WidgetPhoto>(minOf(limit, raw.size))
    for (p in raw) {
        if (p.id <= 0L) continue
        if (p.uri.isEmpty()) continue
        if (!seen.add(p.id)) continue
        out += p
        if (out.size >= limit) break
    }
    return out
}

/**
 * 小组件的**刷新重试状态机**。
 *
 * 为什么需要它：`goAsync()` 给的预算只有 ~10 秒，而查库/取缩略图在冷启动那一下
 * （媒体提供方还没起来、外置存储刚挂载）经常抛 `IOException` / 返回 null。
 * 一次失败就写「没读到照片」太急 —— 那时相册里明明有一万张。
 * 但重试也不能无限：广播接收器的 ANR 时限是 10 秒，在 `goAsync()` 里死循环会把
 * 桌面的 launcher 一起拖住。
 *
 * 所以规则做成纯的、可测的：
 *  - 只有**没读到**才加一次尝试（"确实没有照片"重试一百次也还是空的）；
 *  - 每次失败后等 [backoffMillis] 再来（50 → 150 → 400ms，加起来 < 1s，装得下预算）；
 *  - [WidgetShape.MAX_ATTEMPTS] 次之后给结论（[shouldGiveUp]），不再重试。
 *
 * 这是一个不可变的值对象：`next()` 返回新的一份。少了这一点，
 * "上一个还没跑完、宿主又发了一次"就会互相把计数改乱。
 */
data class WidgetRetry(
    /** 这是第几次尝试（从 1 开始）。 */
    val attempt: Int = 1,
    /** 上一次失败的原因；还没失败过时是 null。 */
    val lastFailure: String? = null,
) {

    /** 还能再试：还没到上限。 */
    val canRetry: Boolean get() = attempt < WidgetShape.MAX_ATTEMPTS

    /** 该放弃了：这一次的失败要落成「没读到照片」。 */
    val shouldGiveUp: Boolean get() = !canRetry

    /**
     * 记一次失败，返回下一次尝试。
     *
     * `attempt` 到上限之后**不再自增**：让它停在 [WidgetShape.MAX_ATTEMPTS]，
     * 否则日志里会看到第 4、第 5 次尝试，而实际只发起了 3 次。
     */
    fun next(failure: String): WidgetRetry = WidgetRetry(
        attempt = if (attempt < WidgetShape.MAX_ATTEMPTS) attempt + 1 else attempt,
        lastFailure = failure,
    )

    /** 这一次失败之后等多久再试（毫秒）。第 1 次 50ms，第 2 次 150ms，之后 400ms。 */
    val backoffMillis: Long
        get() = when (attempt) {
            1 -> 50L
            2 -> 150L
            else -> 400L
        }
}

/**
 * 把「查到的行 + 总数」收成 [WidgetState]。
 *
 * 与 `MediaRepository` 里那条口径逐字一致：**`null` 是没读到，空表是确实没有**。
 * 这个函数存在的意义就是不让调用点自己写 `if (rows.isNullOrEmpty())` ——
 * 那样写出来的代码在"媒体库没答话"时会把桌面刷成 0 张。
 */
fun stateOf(rows: List<WidgetPhoto>?, total: Int): WidgetState = when {
    rows == null -> WidgetState.Unavailable("媒体库没答话")
    rows.isEmpty() -> WidgetState.Empty
    else -> {
        val picked = selectRecent(rows)
        // 读到了行、却一张都留不下（全是坏 id / 空 uri）：按"没读到"处理，
        // 不许把上一次的画面擦成空。这是本条判据的边界，测试里专门喂了这一例。
        if (picked.isEmpty()) {
            WidgetState.Unavailable("这一批行全都取不到缩略图")
        } else {
            WidgetState.Loaded(picked, total.coerceAtLeast(picked.size))
        }
    }
}

/** 位图相关的常量与换算。放在对象里是为了让"这些数字为什么是这个数"能写在一处。 */
object WidgetLogic {

    /**
     * 取缩略图时长边的像素。
     *
     * ## 为什么必须小
     * `RemoteViews` 带的位图是**走 Binder** 交给 launcher 的，一次事务有 ~1MB 的硬上限，
     * 超了不是"图糊一点"，是 `TransactionTooLargeException` —— 整个 update 失败，
     * 桌面上停在旧画面且没有任何提示（这条坑见 docs/product/WIDGET.md「已知限制」）。
     * 所以 4×2 的四张必须小：4 × 144² × 4B ≈ 331KB，连同一份布局还装得下。
     *
     * ## 数字怎么来的
     * - 2×2 是一张满格大图（~110–180dp 宽），按 2 倍屏取 180dp 再夹到 384px；
     * - 4×2 每格约 60–72dp 宽，按 2 倍屏取 90dp，**上限由 Binder 预算反推**：
     *   4 张 × edge² × 4B 必须 < 512KB → edge ≤ 181。取 [WIDE_EDGE_MAX] = 144
     *   （4 × 144² × 4 = 331KB，与上面那个数字对得上），留出布局与其它字段的余量。
     *
     * 之前这里写的是 192：那在 3 倍屏上会算出 4 张 192² 的图 = 576KB，
     * 已经吃掉 Binder 事务预算的一半以上，再加布局与字符串就贴着
     * `TransactionTooLargeException` 的边。192 这个值当初是照"每格 72dp × 2.67 倍"
     * 拍的，没和上面那条 331KB 的结论对账 —— `WidgetLogicTest.thumbEdgeStaysInsideTheBinderBudget`
     * 把它抓出来了。
     *
     * 密度传 0 / 负数 / NaN（某些单测与极端宿主上会出现）时按 1 倍算，不许返回 0 ——
     * `loadThumbnail` 收到 0 会抛 `IllegalArgumentException`。
     */
    fun thumbEdgePx(size: WidgetSize, density: Float): Int {
        val d = if (density.isFinite() && density > 0f) density else 1f
        return when (size) {
            WidgetSize.Small2x2 -> (180f * d).toInt().coerceIn(96, 384)
            // 4×2 四张并排：单张的宽只有整块的 1/4，取 90dp；上限见上面的预算推导。
            WidgetSize.Wide4x2 -> (90f * d).toInt().coerceIn(72, WidgetShape.WIDE_EDGE_MAX)
        }
    }
}
