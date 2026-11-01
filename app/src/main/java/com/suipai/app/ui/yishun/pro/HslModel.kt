package com.suipai.app.ui.yishun.pro

import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands

/**
 * 8 色带的**表**：界面那一列/网格全部由它生成，滑杆的读屏名也从这里来。
 *
 * 数值幅度（±30° / ±100% / ±25%）是引擎定义，不在这里重复一遍 ——
 * 见 `HslBands.HUE_AMPLITUDE_DEG` 等常量与 `docs/engine/COLOR-PRO.md` §2。
 * 这里只有"人看得懂"的东西：中文名、代表色、以及把 [-1,1] 显示成百分数。
 */
enum class HslBandKind(
    val index: Int,
    val label: String,
    /** 带中心色，只用来画那颗色块；与引擎的 HUES 一一对应（测试钉住顺序）。 */
    val color: Long,
) {
    RED(0, "红", 0xFFE05A45),
    ORANGE(1, "橙", 0xFFE08A3C),
    YELLOW(2, "黄", 0xFFDFC93C),
    GREEN(3, "绿", 0xFF4FB06A),
    CYAN(4, "青", 0xFF3FB5B8),
    BLUE(5, "蓝", 0xFF4A7FD8),
    PURPLE(6, "紫", 0xFF8A63CE),
    MAGENTA(7, "品红", 0xFFC95B9E),
    ;

    companion object {
        /** 按引擎的 [HslBands.HUES] 顺序取；越界夹到端点，与 `HslBands.band()` 的语义一致。 */
        fun at(index: Int): HslBandKind = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * 一个色带的三个滑杆。
 *
 * 三根滑杆的**值域与夹取**收在一处：界面只读 [SliderSpec]，判定只读 [withValue]。
 * 分开写就会出现「滑杆拖到 0.3、落盘是 0.25」那类两两对不上的账（`Shifts` 当年就分过家）。
 */
enum class HslChannel(
    val label: String,
    /** 滑杆显示范围；引擎侧 [HslBand] 会把越界夹回 [-1,1]，但滑杆不该让人拖到那里。 */
    val range: ClosedFloatingPointRange<Float>,
    val lowWord: String,
    val midWord: String,
    val highWord: String,
    val get: (HslBand) -> Float,
    val with: (HslBand, Float) -> HslBand,
) {
    HUE("色相", -1f..1f, "偏左", "原色", "偏右", { it.hueC }, { b, v -> b.copy(hue = v) }),
    SAT("饱和", -1f..1f, "更淡", "原饱和", "更浓", { it.satC }, { b, v -> b.copy(sat = v) }),
    LUM("明度", -1f..1f, "更暗", "原明度", "更亮", { it.lumC }, { b, v -> b.copy(lum = v) }),
    ;

    /** 读屏与界面共用这一句：`绿 色相 偏右`。 */
    fun caption(kind: HslBandKind, band: HslBand): String {
        val v = get(band)
        val word = when {
            v < -WORD_STEP -> lowWord
            v > WORD_STEP -> highWord
            else -> midWord
        }
        return "${kind.label}$label $word"
    }

    companion object {
        /** 与 `Shift.wordStep` 同量级：小到能看见变化，大到不会在两档之间抖。 */
        const val WORD_STEP = 0.04f
    }
}

/**
 * HSL 面板的编辑模型：8 带 × 3 通道 = 24 个滑杆。
 *
 * **只做读改写，不持有状态**：状态在调用方的 `remember` 里，
 * 这样"滑杆拖动 → 节流重算"那条链路只有一处（`YishunScreen` 的 tick）。
 */
object HslModel {

    fun band(bands: HslBands, kind: HslBandKind): HslBand = bands.band(kind.index)

    /**
     * 改一个值。先夹进滑杆范围，再夹进引擎值域 —— 两道都有用：
     * 前者防"滑杆拖出去"，后者防"从配方读进来的脏值"被原样写回。
     */
    fun withValue(bands: HslBands, kind: HslBandKind, channel: HslChannel, value: Float): HslBands {
        val v = if (value.isNaN()) 0f else value.coerceIn(channel.range)
        val next = channel.with(band(bands, kind), v)
        return bands.withBand(kind.index, next)
    }

    /** 单带清零。 */
    fun clearBand(bands: HslBands, kind: HslBandKind): HslBands =
        bands.withBand(kind.index, HslBand())

    /** 24 个值全零。 */
    fun clearAll(): HslBands = HslBands.NONE

    /** 顶栏/抽屉角标：有几个带被动过。 */
    fun touchedBands(bands: HslBands): Int = bands.bands().count { it.hasAny }

    /** 一条"哪些带改了"的人话摘要，给抽屉标题下方那行小字。 */
    fun summary(bands: HslBands): String {
        val touched = HslBandKind.entries.filter { band(bands, it).hasAny }
        return if (touched.isEmpty()) "8 个色带都未改动" else "已改动：" + touched.joinToString("、") { it.label }
    }
}
