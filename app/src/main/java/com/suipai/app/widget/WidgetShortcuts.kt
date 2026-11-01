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
 * 系统的硬上限是**每个 launcher 5 个**（静态 + 动态 + 被固定的，全算在内）。
 * 静态已经占了 3 个，所以动态最多只能给 [MAX_DYNAMIC] 个。
 * 超了不会崩，`setDynamicShortcuts` 会**静默丢掉**多出来的那几条 —— 这正是这条
 * 为什么要写成常量：丢的是哪几条由系统决定，不是我们。
 */
object ShortcutBudget {
    const val SYSTEM_MAX = 5
    const val STATIC_COUNT = 3
    const val MAX_DYNAMIC = SYSTEM_MAX - STATIC_COUNT
}

/**
 * 「这台设备能不能发动态快捷方式」的**纯判定**。
 *
 * 为什么抽出来：这条判据的两个输入都来自 android 静态调用
 * （`Build.VERSION.SDK_INT` 与 `ShortcutManagerCompat.isRequestPinShortcutSupported`），
 * 而在 JVM 单测里前者是 0、后者是桩返回 false —— 直接写成 `if (...)` 的话，
 * 这条分支永远测不到，等于没写。把两个数字传进来，四个象限就能逐个钉住。
 *
 * ## 判据为什么是"两条都要"
 * 按约定用 `isRequestPinShortcutSupported`：它答的是"这个 launcher 支不支持把快捷方式钉到桌面"。
 * 严格说它比"能不能发快捷方式"更严 —— 钉不住的 launcher 上，长按菜单里的动态快捷方式
 * 其实照样能显示。但那台机器上用户拿不到"拖到桌面"这个动作，
 * 功能已经残了一半；而这里的错误代价不对等：
 * **多发**的后果是一个偶尔点了没到地方的入口，**不发**的后果只是少两条快捷键。
 * 所以判据取严，宁可静默跳过。
 *
 * API 25 是动态快捷方式的下限（`ShortcutManager` 是 25 才有的类），这一条不能省：
 * 更低版本上 core 的兼容层行为没有承诺。
 */
object ShortcutSupport {

    /** 动态快捷方式需要的最低 API。 */
    const val MIN_SDK = 25

    fun shouldPublish(sdkInt: Int, pinShortcutsSupported: Boolean): Boolean =
        sdkInt >= MIN_SDK && pinShortcutsSupported
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
 * `MainActivity` 目前认得哪些 `suipai://` 深链 —— **这是抄过去的一份**。
 *
 * ## 为什么要有这一份，而且必须抄
 * `MainActivity.collectHop` 只做一件事：`intent.data?.host?.takeIf { it == "camera" || it == "sync" || it == "yishun" }`。
 * 换句话说，除了这三个 host，**任何** `suipai://xxx` 都会被静默忽略：
 * `launchHop` 保持 null，用户看到的是"点了快捷方式，app 打开了，但没到那个地方"。
 * 它不崩、不报错、logcat 里一个字都没有 —— 是那种能活到上架的形状。
 *
 * 本轮不允许改 `MainActivity`，所以唯一的出路是**只发它认得的**。
 * `WidgetLogicTest` 会读 `MainActivity.kt` 的原文把这张表校一遍：
 * 哪天那边加/删了 host 而这里没跟上，测试当场红。
 *
 * ## 目前**不支持**的两条，因此不许发
 * - `suipai://viewer?index=N` —— 查看器要靠 `AppState.viewerPhotos` 那份重数据，
 *   而 `collectHop` 只解析 host，query 根本不读。
 * - `suipai://trash` —— `AppRoute.Trash` 存在，但没有任何 intent 入口。
 *
 * 要真正直达这两处，需要 `MainActivity` 里加两个分支（本轮被禁止，交给 lead 决定）。
 */
internal object SupportedDeepLinks {

    /** 与 `MainActivity.collectHop` 的 `takeIf` 里那份清单逐字一致。 */
    val HOSTS: Set<String> = setOf("camera", "sync", "yishun")

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
 * 两条的 `deepLink` 都是 null：见 [SupportedDeepLinks] 那段说明。
 */
internal fun dynamicShortcutSpecs(hasPhotos: Boolean): List<ShortcutSpec> = buildList {
    if (hasPhotos) {
        add(
            ShortcutSpec(
                id = ShortcutIds.RECENT,
                shortLabel = "最近一张",
                longLabel = "打开随拍看最近一张",
                deepLink = null,
                rank = 0,
            ),
        )
    }
    add(
        ShortcutSpec(
            id = ShortcutIds.TRASH,
            shortLabel = "回收站",
            longLabel = "30 天内删掉的照片",
            deepLink = null,
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
 * 发出去之前由 [ShortcutSpec] 的构造点保证（现在全是 null），
 * 由 `WidgetLogicTest` 逐条核对（喂一条 `suipai://viewer` 必须变红）。
 */
internal fun budgetedDynamicShortcuts(specs: List<ShortcutSpec>): List<ShortcutSpec> {
    val seen = LinkedHashSet<String>(specs.size)
    val out = ArrayList<ShortcutSpec>(minOf(specs.size, ShortcutBudget.MAX_DYNAMIC))
    for (s in specs) {
        if (!seen.add(s.id)) continue
        out += s
        if (out.size >= ShortcutBudget.MAX_DYNAMIC) break
    }
    return out
}
