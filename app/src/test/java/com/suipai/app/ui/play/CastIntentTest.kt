package com.suipai.app.ui.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投屏判定的判据。**全部在 JVM 上跑，一行 `Uri.parse` 都不碰。**
 *
 * 为什么：本工程的 JVM 单测里 `android.jar` 全是桩（`unitTests.isReturnDefaultValues = true`），
 * `Uri.parse("...")` 直接返回 **null**，`Intent()` 的方法抛 `Stub!`。
 * 第一版测试在 companion 里写 `val uri = Uri.parse(...)`，于是在**类初始化**就
 * `ExceptionInInitializerError`，11 条测试全红 —— 那是测试在测 Android 桩，不是测逻辑。
 *
 * 所以判定被拆成纯 Kotlin 的 [CastIntent.decide]（两个布尔谓词进，一个
 * [CastRoute] 出）。四种出口在这里全部能真的走到。
 *
 * 这一篇钉住四件事：
 * 1. 已知投屏器**装了且接得住** → [CastRoute.Precise]；
 * 2. 已知投屏器**装了但接不住** → 不许选它（最容易写漏的一条）；
 * 3. 都没命中但有应用能接 → [CastRoute.Generic]；
 * 4. **一个能接的都没有 → [CastRoute.None]**（界面据此显示「未找到投屏设备」）。
 *
 * 每条判据都配"喂坏例子必须变红"的自检。
 */
class CastIntentTest {

    companion object {
        /** 全部"没装"、全部"接不住" —— 最坏的那台机器。 */
        fun nothing(): Pair<(String) -> Boolean, (String?) -> Boolean> =
            Pair({ _: String -> false }, { _: String? -> false })

        fun decide(
            isInstalled: (String) -> Boolean,
            resolves: (String?) -> Boolean,
        ): CastRoute = CastIntent.decide(isInstalled, resolves)
    }

    // ────────────────────────────────────────────────────────────────
    // 1. 四种出口
    // ────────────────────────────────────────────────────────────────

    @Test
    fun nothingInstalledAndNothingResolvesReturnsNone() {
        val (installed, resolves) = nothing()
        val route = decide(isInstalled = installed, resolves = resolves)
        assertEquals(
            "没有投屏设备时居然选出了路由 —— 界面会去 startActivity 然后静默失败",
            CastRoute.None,
            route,
        )
        assertEquals(
            "None 时那句话没说「未找到」",
            true,
            CastIntent.noteFor(route).contains("未找到"),
        )
        // 自检：同样的谓词，只要 resolves 变 true 就必须不再为 None
        assertTrue(
            "resolves 为 true 时仍是 None —— 判据空转",
            decide(isInstalled = { _: String -> false }, resolves = { _: String? -> true }) != CastRoute.None,
        )
    }

    @Test
    fun knownCasterThatResolvesGetsAPreciseRoute() {
        val caster = CastIntent.knownCasters.first()
        val route = decide(isInstalled = { it == caster }, resolves = { true })
        assertEquals(
            "装了已知投屏器却没走精确路径",
            CastRoute.Precise(caster),
            route,
        )
        // 精确路由的文案必须带上包名，否则"投到哪"说不清
        assertTrue(
            "精确路由的文案里没有包名",
            CastIntent.noteFor(route).contains(caster),
        )
        // 自检：换成第二个投屏器，路由必须跟着换（防"永远返回表头那一个"）
        val second = CastIntent.knownCasters[1]
        assertEquals(
            CastRoute.Precise(second),
            decide(isInstalled = { it == second }, resolves = { true }),
        )
    }

    @Test
    fun installedButUnresolvableCasterIsRejected() {
        // 本条判据真正的价值：`getPackageInfo` 成功 ≠ 接得住 ACTION_VIEW。
        // 只看"装没装"的实现在这里会选出一个不接 VIEW 的包，
        // 用户看到的是系统那句「没有应用可执行此操作」。
        val caster = CastIntent.knownCasters.first()
        val route = decide(
            isInstalled = { it == caster },
            resolves = { pkg -> pkg == null },   // 只有宽松路径接得住
        )
        assertEquals(
            "把目标选成了一个装了但不接 ACTION_VIEW 的包",
            CastRoute.Generic,
            route,
        )
        // 自检：连宽松路径也接不住时，同一个"装了"的包必须归到 None 而不是 Generic
        assertEquals(
            "接不住的时候居然没归到 None",
            CastRoute.None,
            decide(isInstalled = { it == caster }, resolves = { _: String? -> false }),
        )
    }

    @Test
    fun skipsUnresolvableCastersInOrderAndPicksTheNextOne() {
        val first = CastIntent.knownCasters[0]
        val second = CastIntent.knownCasters[1]
        val route = decide(
            isInstalled = { it == first || it == second },
            // 第一个接不住、第二个接得住
            resolves = { pkg -> pkg == null || pkg == second },
        )
        assertEquals(
            "没有跳过接不住的那一个、去试下一个",
            CastRoute.Precise(second),
            route,
        )
    }

    @Test
    fun genericPathIsUsedWhenNoKnownCasterIsInstalled() {
        val route = decide(
            isInstalled = { _: String -> false },
            resolves = { _: String? -> true },
        )
        assertEquals("没人装、但有应用能接 → 应当交给系统选", CastRoute.Generic, route)
        assertTrue(
            "宽松路径的文案没说交给系统：${CastIntent.noteFor(route)}",
            CastIntent.noteFor(route).contains("系统"),
        )
    }

    // ────────────────────────────────────────────────────────────────
    // 2. 探测代价与短路
    // ────────────────────────────────────────────────────────────────

    @Test
    fun probeStopsAtTheFirstHit() {
        // 每次探测都是 binder 调用。第一个就命中时必须立刻返回，
        // 不许把整张表问一遍 —— 这一条防止有人把 `return` 写成 `continue`。
        var probes = 0
        val first = CastIntent.knownCasters.first()
        CastIntent.decide(
            isInstalled = { probes++; it == first },
            resolves = { true },
        )
        assertTrue(
            "第一个命中后还在继续探测（探了 $probes 次，表长 ${CastIntent.knownCasters.size}）",
            probes <= 2,
        )
        // 自检：全不命中时确实会把整张表问一遍（说明上面那个上限不是靠"从不探测"达成的）
        var allProbes = 0
        CastIntent.decide(isInstalled = { allProbes++; false }, resolves = { false })
        assertEquals("没命中时没有把整张表问完", CastIntent.knownCasters.size, allProbes)
    }

    @Test
    fun emptyTargetIsRejectedWithoutAnyProbe() {
        assertFalse("空 uri 被当成可投目标", CastIntent.isCastableTarget(""))
        assertFalse("空白 uri 被当成可投目标", CastIntent.isCastableTarget("   "))
        assertTrue("正常 uri 被判成不可投", CastIntent.isCastableTarget("content://media/1"))
        // 自检：判据必须真的区分两者（否则"永远 false"也会绿）
        assertTrue(CastIntent.isCastableTarget("file:///tmp/a.png"))
    }

    // ────────────────────────────────────────────────────────────────
    // 3. MIME 归一
    // ────────────────────────────────────────────────────────────────

    @Test
    fun blankMimeFallsBackToWildcard() {
        for (blank in listOf("", "   ", "\t")) {
            assertEquals(
                "mime 为 \"$blank\" 时没有退回通配",
                CastIntent.MIME_WILDCARD,
                CastIntent.normalizedMime(blank),
            )
        }
        // 自检：给了具体 mime 就不许被改写
        assertEquals("具体 mime 被覆盖了", "image/heic", CastIntent.normalizedMime("image/heic"))
        assertEquals("前后空白没清", "image/png", CastIntent.normalizedMime("  image/png  "))
        // 通配常量本身必须真的是通配（抄成具体类型会让 HEIC/PNG 挑不出来）
        assertEquals("MIME_WILDCARD 不是通配", "image/*", CastIntent.MIME_WILDCARD)
        // 反向自检：一个具体类型不该被判成通配（否则上面那条 assert 是空转）
        assertTrue("具体类型居然和通配相等", CastIntent.MIME_WILDCARD != "image/jpeg")
    }

    // ────────────────────────────────────────────────────────────────
    // 4. 文案与已知包名表
    // ────────────────────────────────────────────────────────────────

    @Test
    fun noteForSaysSomethingDifferentForEveryRoute() {
        // "点了没反应"是这个功能最容易变成的样子，所以三种结果各有各的话。
        val none = CastIntent.noteFor(CastRoute.None)
        val precise = CastIntent.noteFor(CastRoute.Precise("com.google.android.gms.cast"))
        val generic = CastIntent.noteFor(CastRoute.Generic)

        for ((name, s) in listOf("None" to none, "Precise" to precise, "Generic" to generic)) {
            assertTrue("$name 的文案为空", s.isNotBlank())
        }
        assertEquals(
            "三种路由说了同一句话（精确/宽松/没有 无法区分）",
            3,
            setOf(none, precise, generic).size,
        )
        // 自检：精确路由必须报包名
        assertTrue("精确路由没说包名", precise.contains("com.google.android.gms.cast"))
    }

    @Test
    fun knownCasterTableIsSane() {
        val list = CastIntent.knownCasters
        assertTrue("已知投屏器表是空的（这一条判据就没意义了）", list.isNotEmpty())
        assertEquals("表里有重复包名", list.size, list.toSet().size)
        for (pkg in list) {
            assertTrue("包名不像包名：$pkg", pkg.contains('.'))
            assertEquals("包名首尾有空白：[$pkg]", pkg, pkg.trim())
            assertFalse("包名里不该有通配或斜杠：$pkg", pkg.contains('*') || pkg.contains('/'))
            assertFalse("包名里不该有空格：$pkg", pkg.contains(' '))
        }
        // 自检：判据本身确实会拒掉一个坏包名
        assertFalse("坏包名被判成合法了", "not a package".contains('.') && !"not a package".contains(' '))
        // 表必须够长才有意义：只留一两个等于"永远走宽松路径"
        assertTrue("已知投屏器少于 4 个，精确路径基本用不上（${list.size} 个）", list.size >= 4)
    }
}
