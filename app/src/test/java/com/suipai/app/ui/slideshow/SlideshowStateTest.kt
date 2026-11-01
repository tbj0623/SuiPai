package com.suipai.app.ui.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幻灯片的推进规则。
 *
 * 这一层全是"手感"问题，但它们都能化成状态断言：
 * - 顺序播完不循环时必须**停**，而不是绕回第一张（用户会以为它卡住了）；
 * - 随机必须**取尽一轮才重复**，"每次 reroll 直到不同"在三张以上根本做不到这一点；
 * - 0 张时不许崩、1 张时不许自己翻（没有"下一张"）。
 *
 * 每条都配自检：拿一版"错但常见"的实现放在同一个输入上，必须给出不同的结果。
 */
class SlideshowStateTest {

    companion object {

        /** 顺序推进 n 次，返回访问过的下标。 */
        fun walk(state: SlideshowState, steps: Int): Pair<SlideshowState, List<Int>> {
            var s = state
            val visited = ArrayList<Int>(steps)
            var seed = 0
            repeat(steps) {
                val (next, step) = s.advance(stepSeed = seed++)
                s = next
                when (step) {
                    is SlideshowStep.To -> visited += step.index
                    SlideshowStep.End, SlideshowStep.Idle -> Unit
                }
                if (step is SlideshowStep.End || step is SlideshowStep.Idle) return s to visited
            }
            return s to visited
        }

        /** 一版**错的**随机：每次 reroll 到"和当前不同"为止。用来看清它在"取尽"上做不到什么。 */
        fun rerollRandom(size: Int, count: Int, seed: Int = 7): List<Int> {
            val random = kotlin.random.Random(seed)
            val out = ArrayList<Int>(count)
            var cur = 0
            out += cur
            repeat(count - 1) {
                var pick = cur
                var guard = 0
                while (pick == cur && size > 1 && guard++ < 100) pick = random.nextInt(size)
                cur = pick
                out += pick
            }
            return out
        }
    }

    // ── 顺序 ──────────────────────────────────────────────────────────

    @Test
    fun sequentialPlayWalksForwardWithoutSkipping() {
        val start = SlideshowState.start(size = 5, startIndex = 0, loop = false, shuffle = false)
        val (end, visited) = walk(start, 10)
        assertEquals("顺序播的下标序列不对", listOf(1, 2, 3, 4), visited)
        assertTrue("播到片尾没有停（不循环时应当 End）", end.exhausted)
        // 停了之后继续 advance 不许再动
        val (again, step) = end.advance()
        assertEquals("停了之后又自己走了", SlideshowStep.End, step)
        assertEquals("停了之后下标变了", end.index, again.index)
    }

    @Test
    fun loopingWrapsBackToTheFirstFrame() {
        val start = SlideshowState.start(size = 3, startIndex = 0, loop = true, shuffle = false)
        val (_, visited) = walk(start, 4)
        assertEquals("循环没有回到第 0 张", listOf(1, 2, 0, 1), visited)
        // 自检：把 loop 换成 false，同一个序列必须停在第 3 张
        val noLoop = SlideshowState.start(size = 3, startIndex = 0, loop = false, shuffle = false)
        val (end, visited2) = walk(noLoop, 4)
        assertEquals(listOf(1, 2), visited2)
        assertTrue(end.exhausted)
    }

    @Test
    fun startIndexIsClampedInsteadOfCrashing() {
        val high = SlideshowState.start(size = 4, startIndex = 99)
        assertEquals("越界的起始下标没被夹住", 3, high.index)
        val low = SlideshowState.start(size = 4, startIndex = -5)
        assertEquals("负的起始下标没被夹住", 0, low.index)
        val (next, step) = high.advance()
        assertEquals("从最后一张出发，循环下应当回第 0 张", 0, (step as SlideshowStep.To).index)
        assertEquals(0, next.index)
    }

    // ── 边界：0 张 / 1 张 ────────────────────────────────────────────

    @Test
    fun zeroPhotosIsIdleNotACrash() {
        val empty = SlideshowState.start(size = 0, startIndex = 3)
        assertTrue("空片单没被认出来", empty.isEmpty)
        assertEquals("空片单的下标应当是 0", 0, empty.index)
        assertEquals("空片单的进度应当是 0（不是 NaN）", 0f, empty.progress, 0f)
        assertEquals("空片单推进应当 Idle", SlideshowStep.Idle, empty.advance().second)
        assertEquals("空片单回退应当 Idle", SlideshowStep.Idle, empty.back().second)
        assertEquals("空片单跳转应当 Idle", SlideshowStep.Idle, empty.jump(2).second)
        assertEquals("空片单开随机应当还是空袋", emptyList<Int>(), empty.withShuffle(true).bag)
        assertEquals("负数 size 也要当成空", 0, SlideshowState.start(size = -3).size)
    }

    @Test
    fun onePhotoNeverSelfAdvances() {
        val one = SlideshowState.start(size = 1, startIndex = 0, loop = true, shuffle = false)
        // 顺序 + 循环 + 只有一张：advance 会"回到 0"，但那不是一次翻页。
        // 界面靠"播放中且 size > 1 才起计时器"处理它；这里钉住状态本身不会越界。
        val (next, step) = one.advance()
        assertEquals("1 张时下标跑出了 0", 0, (step as SlideshowStep.To).index)
        assertEquals(0, next.index)
        // 随机 + 1 张：袋里最多只有它自己，取尽之后不许死循环
        val shuffled = SlideshowState.start(size = 1, startIndex = 0, loop = true, shuffle = true)
        val (after, step2) = shuffled.advance(stepSeed = 1)
        assertTrue("1 张随机推进给出了非法下标", step2 is SlideshowStep.To)
        assertEquals(0, after.index)
        assertEquals("1 张时进度应当是 1（唯一一张已经放了）", 1f, shuffled.progress, 0f)
    }

    // ── 随机：取尽之前不重复 ─────────────────────────────────────────

    @Test
    fun shuffleVisitsEveryPhotoExactlyOnceBeforeRepeating() {
        for (size in 2..9) {
            // seeds 多跑几个：一个种子恰好没触发边角，不代表规则对。
            for (seed in 0..12) {
                val start = SlideshowState.start(size = size, startIndex = 0, loop = true, shuffle = true, seed = seed)
                val (_, visited) = walk(start, size * 2)
                // 把"开始时屏幕上那张"也算进序列：一轮是 size 张，从当前这张起算。
                val seq = listOf(start.index) + visited
                assertTrue("size=$size seed=$seed 访问序列太短：$seq", seq.size >= size * 2)
                assertEquals(
                    "size=$size seed=$seed 第一轮没取尽：$seq",
                    (0 until size).toSet(),
                    seq.take(size).toSet(),
                )
                assertEquals(
                    "size=$size seed=$seed 第二轮不是完整排列：$seq",
                    (0 until size).toSet(),
                    seq.drop(size).take(size).toSet(),
                )
                // 相邻两张不许相同（取尽后的接缝也不许连播同一张）
                for (i in 1 until seq.size) {
                    assertNotEquals(
                        "size=$size seed=$seed 连播了同一张（位置 $i）：$seq",
                        seq[i - 1],
                        seq[i],
                    )
                }
            }
        }
    }

    @Test
    fun shuffleWithLoopOffStopsAfterOneFullRound() {
        val start = SlideshowState.start(size = 4, startIndex = 0, loop = false, shuffle = true, seed = 3)
        val (end, visited) = walk(start, 20)
        assertEquals("不循环的随机没在一轮后停：$visited", 3, visited.size)
        assertEquals("一轮之后没有取尽（漏了某张）", (0 until 4).toSet(), (visited + start.index).toSet())
        assertTrue("取尽之后没有置 exhausted", end.exhausted)
        assertEquals("取尽之后还在推进", SlideshowStep.End, end.advance().second)
        assertTrue("取尽之后进度应当是满的", end.progress >= 1f - 1e-4f)
    }

    @Test
    fun bagIsAlwaysAPermutationOfTheRemainingIndices() {
        val start = SlideshowState.start(size = 6, startIndex = 0, shuffle = true, seed = 11)
        var s = start
        repeat(10) {
            assertTrue("袋里出现了重复下标：${s.bag}", s.bag.distinct().size == s.bag.size)
            assertTrue("袋里出现了越界下标：${s.bag}", s.bag.all { it in 0 until s.size })
            assertFalse("袋里出现了当前正在放的这一张：bag=${s.bag} index=${s.index}", s.index in s.bag)
            s = s.advance(stepSeed = it).first
        }
    }

    /**
     * 自检：reroll 那一版**做不到**"取尽一轮"。
     *
     * 这不是"reroll 更差一点"，是它根本没有这个保证 —— 用 24 个种子里
     * 有多少个在 size 次之内漏了某张来量。真实现是 0 个。
     */
    @Test
    fun rerollRandomReallyFailsTheExhaustionGuarantee() {
        val size = 6
        val misses = (0..23).count { seed ->
            val seq = rerollRandom(size, size, seed)
            seq.toSet().size != size
        }
        assertTrue("reroll 居然一次都没漏（那这条自检没有区分能力）", misses > 0)
        // 真实现：同样 24 个种子，一次都不许漏
        val ours = (0..23).count { seed ->
            val (_, visited) = walk(
                SlideshowState.start(size = size, startIndex = 0, shuffle = true, seed = seed),
                size,
            )
            (visited + 0).toSet().size != size
        }
        assertEquals("洗牌袋漏了某张（那它和 reroll 没区别）", 0, ours)
    }

    // ── 手动操作：back / jump / 开关 ─────────────────────────────────

    @Test
    fun manualBackWrapsOnlyWhenLooping() {
        val looping = SlideshowState.start(size = 4, startIndex = 0, loop = true)
        assertEquals("循环时往回应当绕到最后一张", 3, (looping.back().second as SlideshowStep.To).index)
        val strict = SlideshowState.start(size = 4, startIndex = 0, loop = false)
        assertEquals("不循环时往回不该绕", SlideshowStep.End, strict.back().second)
        assertEquals("往回不该改变下标", 0, strict.back().first.index)
        val mid = SlideshowState.start(size = 4, startIndex = 2)
        assertEquals("往回一格不对", 1, (mid.back().second as SlideshowStep.To).index)
    }

    @Test
    fun jumpClampsAndSkipsTheTargetInTheBag() {
        val s = SlideshowState.start(size = 5, startIndex = 0, shuffle = true, seed = 5)
        val (jumped, step) = s.jump(3)
        assertEquals("跳转目标不对", 3, (step as SlideshowStep.To).index)
        assertFalse("跳过去之后那一张还留在袋里（会立刻再放一次）", 3 in jumped.bag)
        assertEquals("越界跳转没被夹住", 4, (s.jump(99).second as SlideshowStep.To).index)
        assertEquals("负跳转没被夹住", 0, (s.jump(-9).second as SlideshowStep.To).index)
    }

    @Test
    fun switchingShuffleKeepsTheCurrentFrame() {
        val playing = SlideshowState.start(size = 5, startIndex = 2, shuffle = false)
        val shuffled = playing.withShuffle(true, seed = 9)
        assertEquals("开随机时跳片了", 2, shuffled.index)
        assertTrue("开随机没生成袋子", shuffled.bag.isNotEmpty())
        assertFalse("开随机时把当前这张放进了袋里", 2 in shuffled.bag)
        val back = shuffled.withShuffle(false)
        assertEquals("关随机时跳片了", 2, back.index)
        assertEquals("关随机之后袋子没清空", emptyList<Int>(), back.bag)
        // 幂等：重复设同一个值不该重置进度之外的任何东西
        assertEquals(shuffled, shuffled.withShuffle(true, seed = 123))
    }

    @Test
    fun togglingLoopDoesNotMoveTheFrame() {
        val s = SlideshowState.start(size = 3, startIndex = 1, shuffle = false)
        val off = s.withLoop(false)
        assertEquals(1, off.index)
        assertFalse(off.loop)
        assertTrue("重新打开循环后位置变了", s.withLoop(true).index == 1)
    }

    // ── 间隔表 ───────────────────────────────────────────────────────

    @Test
    fun intervalTableMatchesTheSpecAndCyclesFully() {
        assertEquals("间隔档位必须是 2/3/5/10 秒", listOf(2000, 3000, 5000, 10000), SlideshowIntervals)
        // 循环一圈必须回到起点，且每一档都点得到
        val seen = LinkedHashSet<Int>()
        var cur = SlideshowIntervals.first()
        repeat(SlideshowIntervals.size) {
            seen += cur
            cur = nextInterval(cur)
        }
        assertEquals("循环格绕一圈没能点到全部档位", SlideshowIntervals.toSet(), seen)
        assertEquals("绕一圈没回到起点", SlideshowIntervals.first(), cur)
        // 表外的值不许把界面卡死
        assertEquals("表外值没有回到第一档", SlideshowIntervals.first(), nextInterval(1234))
        assertEquals("2 秒的写法不对", "2 秒", intervalLabel(2000))
        assertEquals("10 秒的写法不对", "10 秒", intervalLabel(10000))
    }

    @Test
    fun progressNeverExceedsOne() {
        var s = SlideshowState.start(size = 3, startIndex = 0, loop = true, shuffle = false)
        repeat(20) {
            assertTrue("进度越界：${s.progress}", s.progress in 0f..1f)
            s = s.advance().first
        }
    }
}
