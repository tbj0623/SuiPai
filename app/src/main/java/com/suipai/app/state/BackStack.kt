package com.suipai.app.state

/**
 * 纯 Kotlin 的返回栈。**不依赖 Compose、不依赖 Android**，所以那几条最容易出错的规则
 * （同路由去重、Tab 切换清栈、空栈兜底、保存恢复）全都能在 JVM 上跑。
 *
 * 结构：**一个底座 Tab + 一串上层路由**。
 *
 * - 底座永远是一个 [AppRoute.Tab]，栈里不会出现第二个 Tab（[replaceTo] 换底座时清上层）；
 * - 上层是"压在上面"的页：查看器 / 一瞬 / 相机 / 相册内页，以及将来的设置中心、回收站…
 * - `Tab` 不在 [layers] 里，所以"上层有几层"与"当前在哪个 tab"是两个独立问题，
 *   旧代码里那 7 个互斥导航状态的关系被一个列表说完了。
 *
 * 不可变：每个方法返回新栈。这样 `rememberSaveable` 的 Saver 只需要存一个 `List<String>`
 * （底座下标 + 上层编码串），恢复时一次构造出来，不存在"恢复到一半"的状态。
 */
data class BackStack private constructor(
    /** 底座那一页。永远是 [AppRoute.Tab]。 */
    val base: AppRoute.Tab,
    /** 压在底座上的路由，最后一个就是当前屏。 */
    val layers: List<AppRoute> = emptyList(),
) {

    /** 当前该显示哪个路由：上层最顶那张，没有就回底座。 */
    val current: AppRoute get() = layers.lastOrNull() ?: base

    /** 上层有几层（不含底座）。返回键的优先级靠它判"还有没有可退的"。 */
    val depth: Int get() = layers.size

    /** 栈里有没有这个路由（按 [AppRoute.family] 比，不比页号/uri）。 */
    fun contains(route: AppRoute): Boolean = containsFamily(route.family)

    /** 栈里有没有这一族目的地（含底座）。 */
    fun containsFamily(family: String): Boolean =
        base.family == family || layers.any { it.family == family }

    /**
     * 压一层。
     *
     * **同路由去重策略**：栈顶那一层与要压的**同 family** 时，替换栈顶而不是再压一层。
     * 两个例子（都是旧代码里真实存在的行为）：
     *  - 查看器里翻到另一张又点"一瞬"：`yishunUri` 是一个单值状态，换 uri 不会多一层；
     *  - 一瞬里按"下一张"：同上，返回键一次就回到查看器。
     * family 不同的照常入栈（从大图进一瞬 = 两层）。
     *
     * `Tab` 不许入栈：要换 tab 用 [replaceTo]，误用 [push] 时会被忽略并回同一个栈 ——
     * 与其静默长出一个"压着 tab 的 tab"，不如让不变量在这里挡住（单测钉住）。
     */
    fun push(route: AppRoute): BackStack = when {
        route is AppRoute.Tab -> this
        layers.lastOrNull()?.family == route.family -> copy(layers = layers.dropLast(1) + route)
        else -> copy(layers = layers + route)
    }

    /**
     * 插到栈顶**下面一层**（夹在倒数第一与倒数第二之间）。
     *
     * 只有一处用得到：桌面快捷方式「拍照」落在已经开着的一瞬上。旧代码里 `showCamera`
     * 是个布尔，而渲染顺序把一瞬排在最前 —— 于是相机在**一瞬下面**：屏幕仍然是一瞬，
     * 关掉一瞬才看到相机。用 `push` 会把相机盖到一瞬上面，那是行为变化。
     */
    fun insertBelowTop(route: AppRoute): BackStack = when {
        route is AppRoute.Tab -> this
        layers.isEmpty() -> push(route)
        else -> copy(layers = layers.dropLast(1) + route + layers.last())
    }

    /**
     * 退一层。空栈时**兜底**返回自己（不是抛异常、也不是 null）：
     * 返回键在底座上要交给系统（[NavPolicy.resolveBack] 会先判 `EXIT`），
     * 但一个被误调的 `pop()` 不许把应用弄崩。
     */
    fun pop(): BackStack = if (layers.isEmpty()) this else copy(layers = layers.dropLast(1))

    /** 一直退到指定 family 那一层（它就在栈里时）；不在就原样返回。 */
    fun popTo(family: String): BackStack {
        val at = layers.indexOfLast { it.family == family }
        return if (at < 0) this else copy(layers = layers.take(at + 1))
    }

    /** 清空上层，回到底座那一屏。 */
    fun clearLayers(): BackStack = if (layers.isEmpty()) this else copy(layers = emptyList())

    /** 从栈里摘掉这几族（相机 / 查看器这种"被外面一动作就关掉"的层）。 */
    fun removeFamilies(families: Set<String>): BackStack {
        val kept = layers.filterNot { it.family in families }
        return if (kept.size == layers.size) this else copy(layers = kept)
    }

    /**
     * 换底座并清空上层：点底栏 tab 用这一条。
     * 旧代码里切 tab 时上层必然已经不可见（底栏只在上层为空时才画出来），
     * 所以"清栈"是与旧行为一致的显式化，不是新规则。
     */
    fun replaceTo(tab: AppRoute.Tab): BackStack =
        if (base == tab && layers.isEmpty()) this else BackStack(tab, emptyList())

    /**
     * 只换底座、**保留**上层。
     *
     * 桌面快捷方式「互通」用这一条：旧代码写的是 `tab = 2`，而渲染顺序里
     * 一瞬 / 相机 / 查看器 / 相册内页都在 tab 之上 —— 所以屏幕**不变**，
     * 返回键退掉上层之后才落到「互通」页。用 [replaceTo] 会把上层直接关掉，那是行为变化。
     */
    fun rebaseTo(tab: AppRoute.Tab): BackStack = if (base == tab) this else copy(base = tab)

    /** 整个换成新栈（分享导入 / 快捷方式这类"从外面进来"的跳转用）。 */
    fun reset(tab: AppRoute.Tab, layers: List<AppRoute> = emptyList()): BackStack =
        BackStack(tab, layers.filterNot { it is AppRoute.Tab })

    /**
     * 编码成 `List<String>` 进 Bundle：
     * 第 0 项是底座下标，其余是上层路由的编码（顺序即层级）。
     *
     * 为什么底座单独放在第 0 位：恢复时**必须先有底座**才能拒绝脏数据
     * （一个只剩上层、底座坏掉的列表没法修复），所以先把底座写死最稳。
     */
    fun encode(): List<String> = buildList {
        add("base:" + base.index)
        layers.forEach { add(it.encode()) }
    }

    companion object {

        /** 一个只有底座的空栈。 */
        fun of(tab: AppRoute.Tab): BackStack = BackStack(tab, emptyList())

        /**
         * 从 [encode] 的结果恢复。**任何脏数据都不许让它抛异常**：
         * 进程被杀、版本回退、Bundle 被人改过，都可能喂进半截列表 ——
         * 恢复失败最多是"回到照片页"，不许是"启动即崩"。
         *
         * 规则：
         *  - 空的 / 第一项不是 `base:<n>` → 回 [AppRoute.Gallery] 空栈；
         *  - 底座下标越界 → 回 [AppRoute.Gallery]（[AppRoute.tab] 自己会兜底，这里只是写死）；
         *  - 上层里解不出来的项**丢掉**，剩下的照原顺序保留；
         *  - 上层里混进 Tab（脏数据）→ 丢掉，底座不因此改变。
         */
        fun restore(encoded: List<String>): BackStack {
            val head = encoded.firstOrNull() ?: return of(AppRoute.Gallery)
            if (!head.startsWith("base:")) return of(AppRoute.Gallery)
            val idx = head.removePrefix("base:").toIntOrNull() ?: return of(AppRoute.Gallery)
            val tab = AppRoute.tab(idx)
            val layers = encoded.drop(1)
                .mapNotNull { AppRoute.decode(it) }
                .filterNot { it is AppRoute.Tab }
            return BackStack(tab, layers)
        }
    }
}
