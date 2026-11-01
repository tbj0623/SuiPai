package com.suipai.app.widget

/**
 * 快捷方式的**账本**：id 常量、数量预算、深链白名单。
 *
 * 与 [WidgetLogic] 同一条纪律：这一层不许出现 `android.*`。数量上限与 id 唯一性都是
 * 能被"喂个坏例子"测红的纯逻辑，没有任何理由把它们埋在 `ShortcutManagerCompat` 的调用里。
 */

/**
 * 全项目快捷方式 id 的**唯一真值来源**。
 *
 * 静态那三个的 id 是 `res/xml/shortcuts.xml` 里的 `android:shortcutId`，两边必须逐字相同 ——
 * 不同不会报错，只会让同一个动作在长按菜单里出现两次（静态一份、动态一份），
 * 而用户点哪一个都是"能用"，所以这类重复可以活很久都没人发现。
 * `WidgetLogicTest` 会把这份清单与 `shortcuts.xml` 原文逐条对一遍。
 */
object ShortcutIds {

    // ── 静态（res/xml/shortcuts.xml） ──
    const val CAMERA = "camera"
    const val SYNC = "sync"
    const val YISHUN = "yishun"

    // ── 动态（本文件发布的） ──
    const val RECENT = "dyn_recent"
    const val TRASH = "dyn_trash"

    /** 静态那三个，顺序与 xml 里的声明顺序一致。 */
    val STATIC: List<String> = listOf(CAMERA, SYNC, YISHUN)

    /**
     * 动态那几条的 id。**顺序就是菜单里的顺序**（rank 另外还写着，但系统对同 rank 的
     * 排序没有承诺，所以按列表顺序发）。
     */
    val DYNAMIC: List<String> = listOf(RECENT, TRASH)
}

/**
 * 数量预算。
 *
 * 工程预算最多安排 5 个（3 条静态 + 至多 2 条动态），实际设备容量由
 * `ShortcutManagerCompat.getMaxShortcutCountPerActivity` 单独查询，可能小于 5。
 * [budgetedDynamicShortcuts] 同时遵守工程预算和当前启动器的剩余名额。
 */
object ShortcutBudget {
    const val SYSTEM_MAX = 5
    const val STATIC_COUNT = 3
    const val MAX_DYNAMIC = SYSTEM_MAX - STATIC_COUNT
}

/**
 * 「这台设备能不能发动态快捷方式」的**纯判定**。
 *
 * 输入是 Android API 版本与 launcher 报告的静态+动态快捷方式总容量。
 * `isRequestPinShortcutSupported` 只代表"能否钉到桌面"，不能用来拦截动态快捷方式；
 * 真正的容量来自 `ShortcutManagerCompat.getMaxShortcutCountPerActivity`。
 * 先预留三条 manifest 静态入口；仅有剩余名额时才发动态入口。
 * 纯函数使 API 低、容量不足、刚好一个名额和充足名额都能在 JVM 回归中验证。
 *
 * API 25 是动态快捷方式的下限（`ShortcutManager` 是 25 才有的类），这一条不能省：
 * 更低版本上 core 的兼容层行为没有承诺。
 */
object ShortcutSupport {

    /** 动态快捷方式需要的最低 API。 */
    const val MIN_SDK = 25

    fun shouldPublish(sdkInt: Int, launcherMax: Int): Boolean =
        sdkInt >= MIN_SDK && launcherMax > ShortcutBudget.STATIC_COUNT
}

/**
 * 一条要发出去的快捷方式。
 *
 * [deepLink] 为 null 表示**普通启动 intent**（只带 `targetClass`，不带 data）。
 * 这不是偷懒：见 [SupportedDeepLinks]。
 */
data class ShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val deepLink: String?,
    /** 越小越靠前。 */
    val rank: Int,
)

/**
 * `MainActivity` 已实现的 `suipai://` 深链白名单 —— 与 Activity 的 host 校验同步。
 *
 * 五条 host：camera / sync / yishun 为静态快捷方式；recent / trash 为动态快捷方式。
 * recent 在照片与隐藏清单加载后直达最近一张的查看器；trash 直接打开回收站。
 * viewer?index=N 仍不支持：那需要一份已加载的照片列表，不可只凭不稳定的列表下标猜。
 * `WidgetLogicTest` 从 MainActivity 源码提取白名单，比对这份 HOSTS，防止入口静默失效。
 */
internal object SupportedDeepLinks {

    /** 与 `MainActivity.collectHop` 的 `takeIf` 里那份清单逐字一致。 */
    val HOSTS: Set<String> = setOf("camera", "sync", "yishun", "recent", "trash")

    /** `suipai://yishun` → `true`；`suipai://viewer?index=0` / 空串 / 非 suipai scheme → `false`。 */
    fun isSupported(deepLink: String): Boolean = hostOf(deepLink) in HOSTS

    /**
     * 取 host。**故意写得很笨**：不用 `java.net.URI`（它对你随手造的串抛得比答得多），
     * 也不引 `android.net.Uri`（这一层不许碰 android）。
     * 只认 `scheme://host[/path][?query]` 这一种形状，认不出来就是 null。
     */
    fun hostOf(deepLink: String): String? {
        val sep = deepLink.indexOf("://")
        if (sep <= 0) return null
        if (!deepLink.substring(0, sep).equals("suipai", ignoreCase = true)) return null
        val rest = deepLink.substring(sep + 3)
        if (rest.isEmpty()) return null
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.takeIf { it.isNotEmpty() }
    }
}

/**
 * 要发布的动态快捷方式。**纯函数**，`hasPhotos` 从外面问出来（这里不查库）。
 *
 * 两条的取舍：
 * - **最近一张**：库里一张都没有时**不发**。发了它只会把用户送到"还没有照片"的空态，
 *   那比菜单里少一条更让人困惑。
 * - **回收站**：永远发。它是"删错了想捞回来"的唯一入口，而那种时刻用户不会记得
 *   它在页眉的「更多」里。
 *
 * 两条都携带受 [SupportedDeepLinks] 核对的真实深链：recent / trash。
 */
internal fun dynamicShortcutSpecs(hasPhotos: Boolean): List<ShortcutSpec> = buildList {
    if (hasPhotos) {
        add(
            ShortcutSpec(
                id = ShortcutIds.RECENT,
                shortLabel = "最近一张",
                longLabel = "打开随拍看最近一张",
                deepLink = "suipai://recent",
                rank = 0,
            ),
        )
    }
    add(
        ShortcutSpec(
            id = ShortcutIds.TRASH,
            shortLabel = "回收站",
            longLabel = "30 天内删掉的照片",
            deepLink = "suipai://trash",
            rank = 1,
        ),
    )
}

/**
 * 收紧一次再交给系统。
 *
 * 两道闸，都不许省：
 *  1. **裁到 [ShortcutBudget.MAX_DYNAMIC]**：系统的 5 个上限是"总数"，超了它自己丢，
 *     丢哪条不归我们管。
 *  2. **只留 id 不重复的**（保留第一次出现）：`ShortcutManagerCompat` 对同一个 id
 *     发两次的行为在旧版本上是覆盖、在新版本上是 `IllegalArgumentException`，
 *     不能把版本差异留给运行时。
 *
 * 第三道闸 —— "深链必须是 MainActivity 认得的" —— 故意**不在这里**做成静默过滤：
 * 那样一个写错的 `suipai://viewer` 会被悄悄吞掉，只表现为快捷方式少一条。
 * 发出去之前由 [ShortcutSpec] 的构造点和 [SupportedDeepLinks] 保证，
 * `WidgetLogicTest` 逐条核对（喂一条 `suipai://viewer` 必须变红）。
 */
internal fun budgetedDynamicShortcuts(
    specs: List<ShortcutSpec>,
    availableSlots: Int = ShortcutBudget.MAX_DYNAMIC,
): List<ShortcutSpec> {
    val limit = availableSlots.coerceIn(0, ShortcutBudget.MAX_DYNAMIC)
    if (limit == 0) return emptyList()
    val seen = LinkedHashSet<String>(specs.size)
    val out = ArrayList<ShortcutSpec>(minOf(specs.size, limit))
    for (s in specs) {
        if (!seen.add(s.id)) continue
        out += s
        if (out.size >= limit) break
    }
    return out
}
