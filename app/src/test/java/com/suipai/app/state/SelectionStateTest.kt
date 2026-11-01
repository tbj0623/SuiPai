package com.suipai.app.state

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SelectionState` 的 Saver：存得进 Bundle、读得回来、坏数据不炸、超限整体不存。
 *
 * ## 为什么用匿名 `SaverScope` 而不是真起一个 Compose
 * JVM 单测起不了 Compose（没有 Android 的 CompositionLocal / recomposer），
 * 而 `Saver<S, T>` 的 `save` 是一个纯函数 `(SaverScope, S) -> T?` —— 只要给一个
 * `canBeSaved = true` 的 scope 就能直接调。这个写法是仓库里现成的范式
 * （`BackStackTest.saverRoundTripsThroughAListOfStrings`）。
 *
 * ## 每一条都配反向自检
 * 判据写成恒真式（比如断言一个空数组等于一个空数组）是这类测试最常见的自欺方式。
 * 所以每条断言后面都跟着"这个值确实不是那个值"的检查，见文件末尾那条汇总自检。
 */
class SelectionStateTest {

    /** 仓库根：单测的工作目录是 `app/`。 */
    private fun src(relative: String): File = File(relative)

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun save(state: SelectionState): LongArray? = with(SelectionState.Saver) { scope.save(state) }

    // ── 往返 ──────────────────────────────────────────────────────────

    /** 空选择也要能安全往返（新建的 `SelectionState()` 是绝大多数启动的情况）。 */
    @Test
    fun saverRoundTripsEmptyAndNonEmpty() {
        val empty = SelectionState()
        val savedEmpty = save(empty)
        assertTrue("空选择不该存成 null（null 在 rememberSaveable 里等于\"以后别问我\"）", savedEmpty != null)
        assertEquals(0, savedEmpty!!.size)
        assertEquals(emptySet<Long>(), SelectionState.Saver.restore(savedEmpty)?.ids)

        val picked = SelectionState(setOf(3L, 9L, 41L))
        val saved = save(picked)
        assertEquals("三个 id 必须都在", 3, saved!!.size)
        assertEquals("往返之后集合必须一模一样", picked.ids, SelectionState.Saver.restore(saved)?.ids)

        // 自检：如果保存的是"空数组"（比如实现里写死了 LongArray(0)），
        // 上面第二条相等断言会红 —— 这里直接把两者摆出来。
        assertNotEquals("自检失败：非空选择被存成了空数组", 0, saved.size)
    }
    // ── 坏数据 ────────────────────────────────────────────────────────

    /**
     * 恢复时喂什么都不能抛。
     *
     * `restore` 的入参来自 Bundle —— 进程被杀、降级安装、别的东西往同一个键里写过，
     * 都可能给出匪夷所思的值。抛异常在 `rememberSaveable` 里就是"启动即崩"。
     * `LongArray` 本身只有数字，所以这里能"怪"的只有长度与重复：
     *  - 重复 id 必须去重（`Set` 语义），否则同一个 id 出现两次会让"已选 N 张"多算一张；
     *  - 空数组 / 超长数组都不许炸。
     */
    @Test
    fun restoreFromGarbageIsEmptyNotCrash() {
        // 空数组
        assertEquals(emptySet<Long>(), SelectionState.Saver.restore(LongArray(0))?.ids)
        // 重复值：Set 语义要吃掉重复
        assertEquals(setOf(5L), SelectionState.Saver.restore(longArrayOf(5L, 5L, 5L))?.ids)
        assertEquals(1, SelectionState.Saver.restore(longArrayOf(5L, 5L, 5L))!!.ids.size)
        // 负 id / 0 / Long.MIN：MediaStore 的行号不会是这些，但"脏数据不许炸"
        // 是这一层的职责，不该在恢复路径上做业务校验。
        val weird = SelectionState.Saver.restore(longArrayOf(0L, -1L, Long.MIN_VALUE))
        assertEquals(setOf(0L, -1L, Long.MIN_VALUE), weird?.ids)

        // 自检：上面"重复被吃掉"的判据如果实现改成 List 语义，这条会红。
        assertNotEquals(
            "自检失败：重复 id 没被去重",
            longArrayOf(5L, 5L, 5L).toList(),
            SelectionState.Saver.restore(longArrayOf(5L, 5L, 5L))?.ids?.toList(),
        )
    }

    // ── 上限 ──────────────────────────────────────────────────────────

    /**
     * 超过 [SelectionState.MAX_SAVED] 就**整体不存**，不是截断。
     *
     * 截断的后果不是"少了几个"，而是"已选 N 张"变成一个**错误的** N'：
     * 用户选了 6000 张，恢复之后界面说"已选 5000 张"，而批量删除/收藏真的会作用到
     * 那 5000 张。整体不存则是"这次没保住"，行为与改前一致。
     */
    @Test
    fun oversizedSelectionIsNotSaved() {
        val justRight = SelectionState((1L..SelectionState.MAX_SAVED).toSet())
        assertEquals(
            "正好等于上限时必须存",
            SelectionState.MAX_SAVED,
            save(justRight)!!.size,
        )

        val tooMany = SelectionState((1L..(SelectionState.MAX_SAVED + 1L)).toSet())
        val saved = save(tooMany)
        assertEquals("超限必须整体不存（空数组），不许截断", 0, saved!!.size)
        assertEquals(
            "超限恢复回来应该是空选择，而不是一个被截断的假集合",
            emptySet<Long>(),
            SelectionState.Saver.restore(saved)?.ids,
        )

        // 自检：如果实现改成"截断到 MAX_SAVED"，saved.size 会是 5000 而不是 0 —— 这条会红。
        assertNotEquals(
            "自检失败：超限被截断了（那是更糟的行为）",
            SelectionState.MAX_SAVED,
            saved.size,
        )
    }

    // ── 确定性 ────────────────────────────────────────────────────────

    /**
     * 同一组 id 不管以什么顺序选进来，存出去的数组必须一样。
     *
     * 防的 bug：`Set` 的迭代顺序取决于插入顺序（`LinkedHashSet`），
     * 于是"点选 1、2、3"与"框选 3、2、1"存出来的 `LongArray` 不同 ——
     * 任何"重建前后 Bundle 是否一致"的断言、以及 `Saver` 的相等判断都会飘。
     */
    @Test
    fun savedArrayIsDeterministic() {
        val a = SelectionState(linkedSetOf(9L, 3L, 41L))
        val b = SelectionState(linkedSetOf(41L, 9L, 3L))
        val c = SelectionState(sortedSetOf(41L, 3L, 9L))
        assertArrayEquals("同一组 id 的保存结果必须与选择顺序无关", save(a), save(b))
        assertArrayEquals(save(a), save(c))
        assertArrayEquals(
            "必须升序（这样 diff 与人眼看都稳定）",
            longArrayOf(3L, 9L, 41L),
            save(a),
        )

        // 自检：两个不同的集合必须存出不同的数组，否则上面那条可能是"都存了同一个空数组"。
        assertFalse(
            "自检失败：不同集合存出了同一个数组",
            save(a)!!.contentEquals(save(SelectionState(setOf(1L)))!!),
        )
    }

    /** 写入路径（`ids = ...`）要真的换掉集合，并且读回来是新值。 */
    @Test
    fun writingIdsIsVisibleToTheNextSave() {
        val s = SelectionState(setOf(1L))
        assertEquals(setOf(1L), s.ids)
        s.ids = setOf(1L, 2L)
        assertEquals(setOf(1L, 2L), s.ids)
        assertArrayEquals(longArrayOf(1L, 2L), save(s))
        s.ids = emptySet()
        assertArrayEquals(longArrayOf(), save(s))

        // 自检：空与不空不能存成一样的东西（上面最后一条如果恒真就没意义了）。
        assertNotEquals(0, save(SelectionState(setOf(7L)))!!.size)
    }

    // ── 源码守卫：MainActivity 真的用上了它 ────────────────────────────

    /**
     * 取 `name(` 到配对的 `)` 之间的参数文本。
     *
     * 用括号配平而不是"取到下一个换行"：具名参数一行一个，靠换行判断会把
     * 第一个参数之后的全部截掉（第一版就写错过，导致"删掉 selection = 也不红"）。
     * 这里只处理括号，因为 `AppState(...)` 的实参里没有字符串字面量与 lambda。
     */
    private fun callArgs(text: String, name: String): String? {
        val at = text.indexOf("$name(")
        if (at < 0) return null
        val open = at + name.length
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /**
     * 守卫：`MainActivity` 必须 `rememberSaveable(saver = SelectionState.Saver)` 建一个，
     * 并且 `AppState(...)` 的构造**带着 `selection =`**。
     *
     * 为什么扫源码：这两件事都在 `@Composable` 里，JVM 单测起不了 Compose；
     * 而漏掉的后果是静默的 —— 选择照常工作、`SelectionStateTest` 全绿，
     * 只有真机分屏/进程重建之后选择没了。这条是这一层唯一能自动化的验证手段
     * （与仓库里 `RouteBranchGuardTest` / `PerfSourceGuardTest` 同一条路子）。
     */
    @Test
    fun mainActivityWiresTheSelectionSaver() {
        fun violations(text: String): List<String> {
            val out = mutableListOf<String>()
            if (!text.contains("rememberSaveable(saver = SelectionState.Saver)")) {
                out += "缺少 rememberSaveable(saver = SelectionState.Saver)"
            }
            val args = callArgs(text, "AppState")
            when {
                args == null -> out += "找不到 AppState( 调用"
                !args.contains("selection =") -> out += "AppState( 没传 selection ="
            }
            return out
        }

        val f = src("src/main/java/com/suipai/app/MainActivity.kt")
        assertTrue("找不到 MainActivity.kt：${f.absolutePath}", f.isFile)
        // 统一成 LF 再扫：工作区里这个文件是 CRLF（`core.autocrlf=true`），
        // 而下面的坏样本是用 `\n` 拼的 —— 不归一化会"替换没生效"。
        val text = f.readText().replace("\r\n", "\n")
        val bad = violations(text)
        assertTrue("MainActivity 没有把 SelectionState 接进 AppState：$bad", bad.isEmpty())

        // 自检 0：`callArgs` 本身要真的取到那一段参数（取不到就是恒绿）。
        val goodArgs = callArgs(text, "AppState")
        assertTrue("自检失败：callArgs 没取到构造参数：$goodArgs", goodArgs != null && goodArgs.contains("nav = nav"))

        // ── 自检：喂坏例子必须变红 ──
        val withoutSaver = text.replace(
            "rememberSaveable(saver = SelectionState.Saver) { SelectionState() }",
            "remember { SelectionState() }",
        )
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, withoutSaver)
        assertTrue("自检失败：去掉 rememberSaveable 之后守卫仍然绿", violations(withoutSaver).isNotEmpty())

        val withoutSelection = text.replace("selection = selection,", "")
        assertNotEquals("自检失败：坏样本与原文件一样，替换没生效", text, withoutSelection)
        assertTrue("自检失败：去掉 selection = 之后守卫仍然绿", violations(withoutSelection).isNotEmpty())

        // 自检 2：把 `selection` 挪到调用外面（参数没了但变量还在）也必须红 ——
        // 这一支专门防"只 grep 了 SelectionState 这个词"的假绿。
        val movedOut = text.replace("selection = selection,", "").replace(
            "val selection = rememberSaveable",
            "val selectionIgnored = rememberSaveable",
        )
        assertTrue("自检失败：参数与变量一起改名之后守卫仍然绿", violations(movedOut).isNotEmpty())
    }
}
