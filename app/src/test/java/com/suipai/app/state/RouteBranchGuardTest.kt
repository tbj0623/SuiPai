package com.suipai.app.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 路由 `when` 分支的**类型匹配守卫**。
 *
 * ## 这条守卫挡的是什么
 *
 * `AppRoute` 里有两族实现：
 *  - `data object`（`Settings` / `Trash` / `Videos` …）—— 单例，`when` 里写
 *    `AppRoute.Trash ->` 就是在比这个单例，正确；
 *  - `data class`（`Search` / `Collage` / `Viewer` / `Yishun` …）—— 每次构造都是新实例，
 *    `when` 里**必须**写 `is AppRoute.Search ->`。
 *
 * 写成 `AppRoute.Search ->` 的后果不是编译错，而是**静默失效**：
 * `Search` 带 `companion object`，所以 `AppRoute.Search` 指的是 `Search.Companion`，
 * `Search(null) == Search.Companion` 恒为 false，那一支永远进不去，落到 `else`。
 *
 * 这不是假设 —— 0.7.0 装机验证时真的踩到了：抽屉里 11 颗入口，
 * **只有「搜索」点不动**（其余 10 颗是 `data object` 或写对了 `is`），
 * 点一下只关抽屉、停在时间线，一句解释都没有。
 * 编译绿、727 条单测绿、lint 0 error —— 因为 `AppRouteTest` 测的是
 * "路由此刻编码/解码对不对"，测不到"这个 when 分支会不会被命中"。
 *
 * ## 判据
 *
 * 扫 `nav/AppNavHost.kt` 的 `when (route)` 区段，把每个分支抓出来，
 * 凡是**带参数的 `data class`** 而分支没写 `is` 的，全部判红。
 * 反向自检（喂一段故意写坏的假源码，必须被抓出来）在文件末尾。
 *
 * 为什么扫源码而不是靠反射跑一遍：`when` 的命中是在 composition 里发生的，
 * JVM 单测起不了 Compose。源码扫描是这一层**唯一**能自动化验证的手段 ——
 * 这与本仓库另外十几个守卫（`PerfSourceGuardTest` 等）同一条路子。
 */
class RouteBranchGuardTest {

    /** 仓库根：单测的工作目录是 `app/`，源码在 `src/main/…`。 */
    private fun src(relative: String): File = File(relative)

    /**
     * 所有**带参数构造**的 `AppRoute` 子类型名。
     *
     * 从 `AppRoute.kt` 里现场抽，而不是在这里抄一份常量 ——
     * 抄一份的下场是"有人加了新路由但忘了同步这张表"，守卫照样绿。
     */
    private fun dataClassRouteNames(): Set<String> {
        val f = src("src/main/java/com/suipai/app/state/AppRoute.kt")
        assertTrue("找不到 AppRoute.kt：${f.absolutePath}", f.isFile)
        val text = f.readText()
        // `data class Search(val query: String? = null) : AppRoute {`
        val re = Regex("""data\s+class\s+(\w+)\s*\([^)]*\)\s*:\s*AppRoute""")
        val names = re.findAll(text).map { it.groupValues[1] }.toSet()
        return names
    }

    /**
     * `when (route)` 区段里的分支名集合（只取**没写 `is`** 的那些）。
     *
     * 抓的是形如 `        AppRoute.Foo ->` 的行；`        is AppRoute.Foo ->` 不算。
     */
    private fun branchesWithoutIs(text: String): Set<String> {
        // `AppRoute.X ->` 前面不是 `is `（允许若干空白）
        val re = Regex("""(?<!is )\bAppRoute\.(\w+)\s*->""")
        return re.findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyDataClassRouteBranchUsesIs() {
        val shapes = dataClassRouteNames()
        assertTrue("一个 data class 路由都没抽到，正则失效了", shapes.size >= 8)

        val f = src("src/main/java/com/suipai/app/nav/AppNavHost.kt")
        assertTrue("找不到 AppNavHost.kt：${f.absolutePath}", f.isFile)
        val text = f.readText()

        val bare = branchesWithoutIs(text)
        // 只关心"既在 AppRoute 里是 data class、又在分支里没写 is"的交集。
        val bad = bare intersect shapes
        assertTrue(
            "这些分支把 data class 写成了 `AppRoute.X ->`（会命中伴生对象、永远不匹配实例）：$bad\n" +
                "改成 `is AppRoute.X ->`。",
            bad.isEmpty(),
        )
    }

    /**
     * 自检：喂一段**故意写坏**的假源码，同一个判据必须变红。
     *
     * 少了这条，上面那个 `bad.isEmpty()` 可能只是因为正则压根没抓到东西（恒绿）。
     */
    @Test
    fun guardCatchesTheBadShapeWhenFedOne() {
        val shapes = dataClassRouteNames()
        // `Search` 确实是带 companion 的 data class —— 这一支如果写错就是我踩到的那个坑。
        assertTrue("Search 不在 data class 路由里，这条自检的前提没了：$shapes", "Search" in shapes)

        val broken = """
            when (route) {
                AppRoute.Settings -> A()
                AppRoute.Search -> B()
                is AppRoute.Collage -> C()
                else -> return false
            }
        """.trimIndent()
        val bad = branchesWithoutIs(broken) intersect shapes
        assertTrue("自检失败：写坏的 Search 分支没被抓出来", "Search" in bad)
        assertTrue("自检失败：Settings 是 object，不该被抓", "Settings" !in bad)
        assertTrue("自检失败：写了 is 的 Collage 不该被抓", "Collage" !in bad)

        // 反向自检：把 Search 改成 `is` 之后必须干净。
        val fixed = broken.replace("AppRoute.Search ->", "is AppRoute.Search ->")
        assertTrue(
            "自检失败：改成 is 之后仍然被抓",
            (branchesWithoutIs(fixed) intersect shapes).isEmpty(),
        )
    }

    /**
     * 第二个自检：确认 `AppRoute.Search` 这个写法**真的**指向伴生对象。
     *
     * 这是整条守卫的立论基础。如果哪一天 `Search` 去掉了 `companion object`，
     * `AppRoute.Search` 就变不成一个值，写错的分支会变成编译错误（那反而更好）——
     * 这条断言会第一时间告诉我们"守卫的前提变了"。
     */
    @Test
    fun theMistakeIsReallyAboutTheCompanion() {
        val withCompanion = dataClassRouteNames().filter { name ->
            val text = src("src/main/java/com/suipai/app/state/AppRoute.kt").readText()
            val body = text.substringAfter("data class $name", "")
            body.substringBefore("\n    data ").contains("companion object")
        }
        assertTrue(
            "没有任何一个带 companion 的 data class 路由了 —— 守卫的前提要重写",
            withCompanion.isNotEmpty(),
        )
        // `Search` 必须在里面：它就是 0.7.0 踩到的那一例。
        assertTrue("Search 丢了 companion，这一节要按实际重写：$withCompanion", "Search" in withCompanion)
    }
}
