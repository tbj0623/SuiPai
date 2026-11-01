package com.suipai.app.ui.slideshow

import androidx.compose.runtime.Immutable

/**
 * 幻灯片播放状态。**纯数据 + 纯函数**，不认识 Compose、不认识 `PhotoItem`：
 * 下标在 0..size-1 之间推进，推进规则由 [advance] 决定，
 * 所以顺序 / 循环 / 随机不重复这三件事在 JVM 上一条断言就能钉死。
 *
 * 随机不重复的口径：**洗牌袋（shuffle bag）**，不是"每次 reroll 直到不同"。
 * 后者没有"取尽一遍"的保证（3 张以上时相邻不重复是渐近的，不是每轮一次），
 * 而需求要的是"随机不重复**直到取尽**"—— 那只能靠袋。
 */
@Immutable
data class SlideshowState(
    /** 照片总数。0 = 空片单。 */
    val size: Int,
    /** 当前这张的下标。片单为空时固定 0。 */
    val index: Int = 0,
    /** 循环：放到最后一张之后回到第 0 张（否则 [advance] 返回 [SlideshowStep.End]）。 */
    val loop: Boolean = true,
    /** 随机：按洗牌袋出片，一轮取尽之前不重复。 */
    val shuffle: Boolean = false,
    /** 洗牌袋里**还没出过**的下标（顺序即出片顺序）。非随机时不用。 */
    val bag: List<Int> = emptyList(),
    /** 本轮已经出了几张（进度条用；每轮洗牌开始时归 1 —— 当前这张已经算出了）。 */
    val played: Int = 0,
    /** 本轮已经取尽了吗。取尽之后 [advance] 不再自己动。 */
    val exhausted: Boolean = false,
) {
    val isEmpty: Boolean get() = size <= 0

    /** 进度 0..1。片单空时是 0，不是 NaN —— 进度条不许因为除以 0 画出满格。 */
    val progress: Float
        get() = if (size <= 0) 0f else (played.toFloat() / size).coerceIn(0f, 1f)

    companion object {

        /** 一份新的播放状态。片单为空时所有推进都是空操作。 */
        fun start(
            size: Int,
            startIndex: Int = 0,
            loop: Boolean = true,
            shuffle: Boolean = false,
            seed: Int = 0,
        ): SlideshowState {
            if (size <= 0) return SlideshowState(size = 0, index = 0, loop = loop, shuffle = shuffle)
            val first = startIndex.coerceIn(0, size - 1)
            // 袋子 = 本轮"还没出过"的下标。当前这张已经算出了，所以从袋里拿掉 ——
            // 留着它会让"一轮"变成 size+1 张，进度条永远差一格。
            val bag = if (shuffle) {
                refill(size, avoidHead = first, seed = seed).filterNot { it == first }
            } else {
                emptyList()
            }
            return SlideshowState(
                size = size,
                index = first,
                loop = loop,
                shuffle = shuffle,
                bag = bag,
                played = 1,
                exhausted = false,
            )
        }

        /**
         * 一轮洗牌袋：`0 until size` 全部下标，洗一遍。
         *
         * `avoidHead` 是"上一张的下标"：新一轮的第一个**不接在它后面**，
         * 否则"取尽一轮 → 重新开始"的接缝会连播同一张（看着像卡住了）。
         * 只有 1 张时无处可避，原样返回。
         *
         * 洗牌用显式种子的 Fisher-Yates（`kotlin.random.Random(seed)`）：
         * 同一个种子得到同一份片单，测试才能断言"取尽之前不重复"，而不是碰运气。
         */
        fun refill(size: Int, avoidHead: Int = -1, seed: Int = 0): List<Int> {
            if (size <= 0) return emptyList()
            val order = MutableList(size) { it }
            val random = kotlin.random.Random(seed)
            for (i in size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
            }
            if (size > 1 && order.first() == avoidHead) {
                // 把撞上的开头换到后面随便一个位置。换完仍是完整排列。
                val swapAt = 1 + random.nextInt(size - 1)
                val tmp = order[0]
                order[0] = order[swapAt]
                order[swapAt] = tmp
            }
            return order
        }
    }
}

/** 推进一次的结果。界面按它决定"去下一张 / 停下 / 什么都不做"。 */
sealed interface SlideshowStep {
    /** 去 [index] 那张。 */
    data class To(val index: Int) : SlideshowStep

    /** 到片尾了且不循环：停下，但**不**退出（用户可能还要往回滑）。 */
    data object End : SlideshowStep

    /** 片单为空：不动。 */
    data object Idle : SlideshowStep
}

/**
 * 推进一步。这是整个幻灯片里**唯一一处**"下一张是谁"的判断，界面只负责照着它滚。
 *
 * 三种情形：
 * - **随机**：从袋里取下一个；袋空 = 本轮取尽 → 循环就重洗一轮（并避开刚才那张），
 *   不循环就 [SlideshowStep.End]。
 * - **顺序**：`index + 1`；越过末尾时循环回 0，否则 End。
 * - **片单为空**：[SlideshowStep.Idle]。
 */
fun SlideshowState.advance(stepSeed: Int = 0): Pair<SlideshowState, SlideshowStep> {
    if (size <= 0) return this to SlideshowStep.Idle
    if (exhausted) return this to SlideshowStep.End
    if (shuffle) {
        val nextIndex = bag.firstOrNull()
        if (nextIndex != null) {
            val next = copy(
                index = nextIndex.coerceIn(0, size - 1),
                bag = bag.drop(1),
                played = (played + 1).coerceAtMost(size),
            )
            return next to SlideshowStep.To(next.index)
        }
        // 袋空：这一轮取尽
        if (!loop) return copy(exhausted = true) to SlideshowStep.End
        val refilled = SlideshowState.refill(size, avoidHead = index, seed = stepSeed)
        val head = refilled.firstOrNull() ?: return this to SlideshowStep.End
        val next = copy(index = head, bag = refilled.drop(1), played = 1, exhausted = false)
        return next to SlideshowStep.To(next.index)
    }
    val nextIndex = index + 1
    if (nextIndex < size) {
        val next = copy(index = nextIndex, played = (played + 1).coerceAtMost(size))
        return next to SlideshowStep.To(nextIndex)
    }
    if (!loop) return copy(exhausted = true, played = size) to SlideshowStep.End
    val next = copy(index = 0, played = 1, exhausted = false)
    return next to SlideshowStep.To(0)
}

/**
 * 往回一步（手动左滑 / 音量减）。随机模式下**不动洗牌袋**：
 * 用户来回滑不该消耗掉"还没出过"的那几张。
 */
fun SlideshowState.back(): Pair<SlideshowState, SlideshowStep> {
    if (size <= 0) return this to SlideshowStep.Idle
    val nextIndex = when {
        index > 0 -> index - 1
        loop -> size - 1
        else -> return this to SlideshowStep.End
    }
    return copy(index = nextIndex, exhausted = false) to SlideshowStep.To(nextIndex)
}

/**
 * 手动跳到任意一张（用户直接点了进度/网格）。夹到合法区间。
 * 随机模式下把目标从袋里摘掉，避免"刚看过又抽到它"。
 */
fun SlideshowState.jump(target: Int): Pair<SlideshowState, SlideshowStep> {
    if (size <= 0) return this to SlideshowStep.Idle
    val nextIndex = target.coerceIn(0, size - 1)
    val nextBag = if (shuffle) bag.filterNot { it == nextIndex } else bag
    return copy(index = nextIndex, bag = nextBag, exhausted = false) to SlideshowStep.To(nextIndex)
}

/** 关掉随机时把袋子清掉；打开时重洗一份。两种切换都从当前位置接上，不跳片。 */
fun SlideshowState.withShuffle(on: Boolean, seed: Int = 0): SlideshowState {
    if (on == shuffle && !exhausted) return this
    if (!on) return copy(shuffle = false, bag = emptyList(), exhausted = false)
    if (size <= 0) return copy(shuffle = true, bag = emptyList(), exhausted = false)
    return copy(
        shuffle = true,
        // 当前这张不进袋：随机打开的第一张不该是屏幕上正在放的那张。
        bag = SlideshowState.refill(size, avoidHead = index, seed = seed).filterNot { it == index },
        played = 1,
        exhausted = false,
    )
}

/** 改循环开关。不改变当前位置，只影响"到片尾往哪走"。 */
fun SlideshowState.withLoop(on: Boolean): SlideshowState = copy(loop = on, exhausted = false)

/** 间隔档位（毫秒）。界面与文档共用这一份真值来源。 */
val SlideshowIntervals: List<Int> = listOf(2000, 3000, 5000, 10000)

/** 下一档间隔。不在表里的值回到第一档。 */
fun nextInterval(current: Int): Int {
    val i = SlideshowIntervals.indexOf(current)
    return if (i < 0) SlideshowIntervals.first() else SlideshowIntervals[(i + 1) % SlideshowIntervals.size]
}

/** 间隔的界面写法：`2 秒` / `10 秒`。 */
fun intervalLabel(ms: Int): String = "${ms / 1000} 秒"
