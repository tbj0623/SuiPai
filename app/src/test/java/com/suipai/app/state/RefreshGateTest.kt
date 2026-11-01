package com.suipai.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `RefreshGate` 的决策纯函数。
 *
 * ## 为什么这块值得单测
 * 它的全部风险都在**边界**上，而且错了都表现为"界面卡在旧数据"或"每趟都白读一遍"——
 * 两种都不会崩、不会报错，只有装机走一条长路径才看得见：
 *  - 首次（`lastGen == null`）跳过了 → 冷启动相册永远空着；
 *  - `gen == null`（老系统 / 提供方不答话）跳过了 → 26~28 上永远不刷新；
 *  - 读失败也记了 `lastGen` → 一次失败被缓存成"库没变"，整整一个 TTL 拿不到新数据；
 *  - TTL 边界写反（`>` vs `>=`）→ 刚过期的那一秒还在用旧数据。
 *
 * 每一条判据都配一个"喂相反输入必须变红"的自检，最后还有一条汇总自检。
 */
class RefreshGateTest {

    private val ttl = 60_000L

    private fun needs(
        force: Boolean = false,
        lastGen: String? = "g1",
        gen: String? = "g1",
        ageMs: Long = 0L,
        ttlMs: Long = ttl,
    ) = RefreshGate.needsLibraryRead(force, lastGen, gen, ageMs, ttlMs)

    // ── 必读的五种 ────────────────────────────────────────────────────

    /** 首次（还没成功读过）必须读：否则冷启动的相册永远是空的。 */
    @Test
    fun firstEverReadIsAlwaysNeeded() {
        assertTrue("lastGen == null 必须读", needs(lastGen = null, gen = "g1", ageMs = 0L))
        // 连"代数相同"都不成立时当然也读（这里 gen 也是 null，两个理由各自都够）
        assertTrue(needs(lastGen = null, gen = null, ageMs = 0L))
        // 自检：一旦成功读过一次（lastGen 有值）且代数没变、TTL 内，就该跳过 ——
        // 若实现写成"永远必读"，这一条会红。
        assertFalse("自检失败：已经读成功过、代数也没变，却还在读", needs(lastGen = "g1", gen = "g1", ageMs = 0L))
    }

    /** 代数变了必须读：这是整个门控唯一的"库真的变了"信号。 */
    @Test
    fun generationChangeAlwaysForcesARead() {
        assertTrue("g1 → g2 必须读", needs(lastGen = "g1", gen = "g2", ageMs = 1L))
        assertTrue(needs(lastGen = "g1", gen = "g1 ", ageMs = 1L))   // 空白也算不同（字符串比较，不 trim）
        // 自检：同一个代数不许被当成"变了"（那样门控等于不存在，每趟都读）。
        assertFalse("自检失败：代数相同时也说变", needs(lastGen = "g2", gen = "g2", ageMs = 1L))
    }

    /**
     * 拿不到代数（`gen == null`）必须读。
     *
     * 26~28 上 `MediaStore.getGeneration` / `VOLUME_EXTERNAL` 都不存在，我们返回 null。
     * null 的语义是"**不知道变了没有**"，不是"没变" —— 读成后者就等于老系统永不刷新。
     */
    @Test
    fun missingGenerationMeansWeCannotKnowSoWeRead() {
        assertTrue("gen == null 必须读", needs(lastGen = "g1", gen = null, ageMs = 0L))
        // 自检：只要代数给得出来并且一致，同一时刻就该跳过 —— 证明"必读"是 null 造成的，
        // 不是 ageMs 造成的。
        assertFalse(needs(lastGen = "g1", gen = "g1", ageMs = 0L))
    }

    /** `force` 必须读：调用方明确知道库刚被改过（保存 / 导入 / 恢复 / 下拉）。 */
    @Test
    fun forceAlwaysReads() {
        assertTrue(
            "force 且代数没变、TTL 未到也要读",
            needs(force = true, lastGen = "g1", gen = "g1", ageMs = 0L),
        )
        // 自检：同一组参数去掉 force 就必须跳过。
        assertFalse(needs(force = false, lastGen = "g1", gen = "g1", ageMs = 0L))
    }

    /** TTL 到期必须读：这是"库变了但 generation 没反映出来"的兜底。 */
    @Test
    fun ttlExpiryForcesARead() {
        assertFalse("TTL 内跳过", needs(ageMs = ttl - 1))
        assertTrue("恰好到 TTL 就要读（`>=`，不是 `>`）", needs(ageMs = ttl))
        assertTrue(needs(ageMs = ttl + 1))
        assertTrue(needs(ageMs = Long.MAX_VALUE))
        // 自检：把 TTL 调大之后同一个 ageMs 要变成"不用读" —— 证明这条判据真的读 ttlMs。
        assertFalse("自检失败：ttlMs 没被用上", needs(ageMs = ttl - 1, ttlMs = ttl * 10))
    }

    /**
     * 负的 `ageMs` 按"到期"处理。
     *
     * 它只可能来自时钟被调过。拿它当"刚读过"等于让门控在那段时间里永久跳过 ——
     * 用户看到的是"库里变了、相册一直不动"。
     */
    @Test
    fun negativeAgeIsTreatedAsStale() {
        assertTrue(needs(ageMs = -1L))
        assertTrue(needs(ageMs = Long.MIN_VALUE))
        // 自检：0 与 -1 必须区别对待（0 = 刚刚读过，跳过；-1 = 不可信，读）。
        assertFalse(needs(ageMs = 0L))
    }

    // ── 按 id 读的那两份 ──────────────────────────────────────────────

    /** id 集合相同就不重读（这就是 `loadByIds` 能被跳过的那一半收益）。 */
    @Test
    fun idsReadIsSkippedWhenTheSetIsUnchanged() {
        val same = setOf(1L, 2L, 3L)
        assertFalse(RefreshGate.needsIdsRead(same, setOf(3L, 1L, 2L)))
        assertFalse("空集与空集也算没变", RefreshGate.needsIdsRead(emptySet(), emptySet()))
        // 自检：不同集合必须读，否则这一支恒 false。
        assertTrue(RefreshGate.needsIdsRead(same, same + 4L))
        assertNotEquals(same, same + 4L)
    }

    /** id 集合变了就必须重读（用户刚收藏/取消收藏/删掉了一张）。 */
    @Test
    fun idsReadIsNeededWhenTheSetDiffers() {
        assertTrue(RefreshGate.needsIdsRead(setOf(1L), setOf(2L)))     // 换了一张
        assertTrue(RefreshGate.needsIdsRead(setOf(1L, 2L), setOf(1L))) // 少了一张
        assertTrue(RefreshGate.needsIdsRead(emptySet(), setOf(1L)))    // 从空到有
        assertTrue(RefreshGate.needsIdsRead(setOf(1L), emptySet()))    // 从有到空
        // 自检：同一对参数反过来问必须是 false —— 证明上面不是恒 true。
        assertFalse(RefreshGate.needsIdsRead(setOf(1L), setOf(1L)))
    }

    // ── 汇总自检 ──────────────────────────────────────────────────────

    /**
     * 把"错实现的输出"直接摆出来，证明上面的判据真的有区分度。
     *
     * 这一段自己必须通过，意思是：这些错的值确实**不满足**我们的期望 ——
     * 如果哪天有人把 `needsLibraryRead` 改成这样的行为，上面的断言会红。
     */
    @Test
    fun theAssertionsWouldCatchAWrongImplementation() {
        // 错法 1：不看 force（"每趟都按代数算"）。同一组参数去掉 force 之后，
        // 判据必须从"读"变成"跳过" —— 不变就说明 force 根本没被读。
        assertTrue(needs(force = true, lastGen = "g1", gen = "g1", ageMs = 0L))
        assertFalse(
            "自检失败：force 对判据没影响",
            needs(force = false, lastGen = "g1", gen = "g1", ageMs = 0L),
        )

        // 错法 2：把 gen == null 读成"没变"（老系统永不刷新）。
        // 两个只差 gen 的输入必须给出不同结论。
        assertNotEquals(
            "自检失败：gen 有没有值对判据没影响",
            needs(lastGen = "g1", gen = null, ageMs = 0L),
            needs(lastGen = "g1", gen = "g1", ageMs = 0L),
        )

        // 错法 3：TTL 判据写成 `>`（恰好到期那一下漏掉）。
        // 差 1 ms 的两个输入必须给出不同结论。
        assertNotEquals(
            "自检失败：TTL 边界写成 > 也测不出来",
            needs(ageMs = ttl - 1),
            needs(ageMs = ttl),
        )

        // 错法 4：按 id 读那支恒 false。
        assertNotEquals(
            "自检失败：id 集合变化对判据没影响",
            RefreshGate.needsIdsRead(setOf(1L), setOf(1L)),
            RefreshGate.needsIdsRead(setOf(1L), setOf(2L)),
        )
    }

    // ── 源码守卫：AppState 真的用上了它 ────────────────────────────────

    /**
     * 守卫：`AppState.refresh` 必须真的走 `RefreshGate`，并且**只有读成功才推进代数**。
     *
     * 为什么扫源码：这两件事都在协程里、要 `MediaStore`，JVM 上跑不到；
     * 漏掉的后果是静默的 —— `RefreshGateTest` 全绿，而线上仍然是每趟全量读，
     * 或者更糟：失败也记了 `lastGen`，一次失败被缓存成"库没变"。
     *
     * 判据五条（都只在这一个文件的一个函数里，范围足够小）：
     *  1. `refresh(` 的签名带 `force`；
     *  2. 出现 `RefreshGate.needsLibraryRead(`；
     *  3. 出现"读全了"那个判据 `val libraryOk =` 与 `if (libraryOk) {`；
     *  4. `lastGen = gen` **紧跟在 `if (libraryOk) {` 之后**（窗口 200 字符内）；
     *  5. `libraryDirty` 出现在 `readLibrary =` 那一行里 —— 否则"我们自己刚删了照片"
     *     会被 generation 门控跳过（`DeleteFlow` / `BatchActions` 调的是无参 `refresh()`）。
     *
     * 第 4 条为什么必须写成"紧跟"，而不是"前面 400 字符里有 libraryOk"：
     * 后者在第一版里被一个坏样本打败了 —— 把赋值挪到 `if` 之前时，
     * `val libraryOk = …` 那一行仍然在它前面，判据照样绿。
     * 窗口法只认"`if` 里面第一件事就是推进"，挪出去就必然红。
     *
     * 另外必须匹配 `lastGen = gen` 而不是 `lastGen =`：后者会先命中
     * `RefreshGate.needsLibraryRead(... lastGen = lastGen, ...)` 那个具名实参。
     */
    @Test
    fun appStateRefreshActuallyGoesThroughTheGate() {
        val f = File("src/main/java/com/suipai/app/state/AppState.kt")
        assertTrue("找不到 AppState.kt：${f.absolutePath}", f.isFile)
        // 统一成 LF 再扫：这台机器的 `.gitattributes`/`core.autocrlf` 会让工作区里的文件
        // 是 CRLF（仓库里 60 个 .kt 有 16 个如此），而下面几个坏样本是用 `\n` 拼的 ——
        // 不归一化就会"替换没生效"，于是 assertNotEquals 报一个与真问题无关的红。
        val text = f.readText().replace("\r\n", "\n")

        fun violations(src: String): List<String> {
            val out = mutableListOf<String>()
            if (!src.contains("fun refresh(force: Boolean = false)")) {
                out += "refresh 的签名没有 force 参数"
            }
            if (!src.contains("RefreshGate.needsLibraryRead(")) {
                out += "refresh 没有走 RefreshGate.needsLibraryRead"
            }
            if (!src.contains("val libraryOk =")) out += "没有\"读全了\"的判据 val libraryOk"
            val ifAt = src.indexOf("if (libraryOk) {")
            when {
                ifAt < 0 -> out += "没有 if (libraryOk) { 这一支"
                !src.substring(ifAt, minOf(src.length, ifAt + 200)).contains("lastGen = gen") ->
                    out += "推进 lastGen = gen 不在 if (libraryOk) 里面（失败会被缓存成库没变）"
            }
            val declAt = src.indexOf("val readLibrary =")
            if (declAt < 0) {
                out += "没有 val readLibrary = 这一行"
            } else {
                val line = src.substring(declAt, src.indexOf('\n', declAt).takeIf { it > 0 } ?: src.length)
                if (!line.contains("libraryDirty")) {
                    out += "readLibrary 没把 libraryDirty 算进去（自己刚删的照片会被门控跳过）"
                }
            }
            return out
        }

        val bad = violations(text)
        assertTrue("AppState.refresh 没有正确接上 RefreshGate：$bad", bad.isEmpty())

        // 自检 1：去掉 force 参数。
        val noForce = text.replace("fun refresh(force: Boolean = false)", "fun refresh()")
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, noForce)
        assertTrue("自检失败：去掉 force 参数没被抓到", violations(noForce).isNotEmpty())

        // 自检 2：绕过 RefreshGate（改成直接调私有函数）。
        val bypass = text.replace("RefreshGate.needsLibraryRead(", "needsLibraryRead(")
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, bypass)
        assertTrue("自检失败：绕过 RefreshGate 没被抓到", violations(bypass).isNotEmpty())

        // 自检 3：把"读成功了才推进"改成"无条件推进"（挪到 if 之前 = 失败也会写缓存）。
        val movedAssignment = text.replace(
            "if (libraryOk) {\n                lastGen = gen",
            "lastGen = gen\n            if (libraryOk) {",
        )
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, movedAssignment)
        assertTrue(
            "自检失败：把 lastGen 推进挪出 libraryOk 判断没被抓到",
            violations(movedAssignment).isNotEmpty(),
        )

        // 自检 4：把 if 里面的赋值整段删掉（改成永远不推进 = 门控等于不存在）。
        val dropped = text.replace(
            "\n            if (libraryOk) {\n                lastGen = gen\n                lastFullReadAt = now\n                // 只有读成功才清脏；失败那一次留着，下一趟仍然必读。\n                libraryDirty = false\n            }",
            "",
        )
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, dropped)
        assertTrue("自检失败：整段删掉推进之后没被抓到", violations(dropped).isNotEmpty())

        // 自检 5：把 libraryDirty 从 readLibrary 里摘掉。
        val withoutDirty = text.replace("libraryDirty || jumpUri != null || RefreshGate", "jumpUri != null || RefreshGate")
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, withoutDirty)
        assertTrue("自检失败：摘掉 libraryDirty 之后没被抓到", violations(withoutDirty).isNotEmpty())
    }
}
