package com.suipai.app.ui.yishun.pro

import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.LocalMaskField
import com.suipai.core.engine.RecipePro
import com.suipai.core.engine.ToneCurves
import com.suipai.core.engine.YishunRecipe

/**
 * 一瞬「专业分级」的三组算子，收成**一个不可变值**。
 *
 * 为什么要有这个类型，而不是在 `YishunScreen` 里散着三个 `mutableStateOf`：
 * 1. 它们必须**同时**进 [com.suipai.app.engine.ColorPipeline]（预览 / 导出）、
 *    同时进配方（`RecipePro.withPro`）——分开放就会出现「预览有曲线、存出去没曲线」
 *    这类只看代码看不出来的错法（`Shifts` 当年就分过家，见 `YishunShifts.kt` 注释）；
 * 2. 三组算子都是「中性 = 逐位不变」的（`ToneCurves.isNeutral` / `HslBands.isNeutral` /
 *    空蒙版表），所以一个值就能回答「这一帧要不要走专业路径」；
 * 3. 配方 v2 → v3 的升降级只有一个入口（[of] / [applyTo]），测试可以整条往返。
 *
 * 全部字段都来自 `shared`，本文件不产生任何新的数学——引擎侧定义见
 * `docs/engine/COLOR-PRO.md`，那里是唯一权威。
 */
data class ProOps(
    val curves: ToneCurves = ToneCurves(),
    val bands: HslBands = HslBands.NONE,
    val masks: List<LocalMask> = emptyList(),
) {

    /** 三组全中性：管线会整段跳过，输出与 v2 逐位相同。 */
    val isNeutral: Boolean
        get() = curves.isNeutral && bands.isNeutral && masks.isEmpty()

    /** 顶栏/抽屉里显示「专业 · 3」那个数：只数真的会改变画面的东西。 */
    val activeCount: Int
        get() = (if (curves.isNeutral) 0 else 1) +
            (if (bands.isNeutral) 0 else 1) +
            masks.size

    fun withCurves(next: ToneCurves): ProOps = copy(curves = next)

    fun withBands(next: HslBands): ProOps = copy(bands = next)

    /** 蒙版上限由引擎定（[LocalMaskField.MAX_MASKS] = 4），超出的部分截断而不抛。 */
    fun withMasks(next: List<LocalMask>): ProOps =
        copy(masks = next.take(LocalMaskField.MAX_MASKS))

    /**
     * 套进一条配方。中性时 [RecipePro.withPro] 会把三个字段写成 null，
     * 于是「没动专业面板」的配方仍是合法的 v2 形状（`encodeDefaults = false` 不多出键）。
     */
    fun applyTo(base: YishunRecipe): YishunRecipe =
        RecipePro.withPro(base, curves, bands, masks)

    companion object {
        val NONE = ProOps()

        /**
         * 从配方还原。**v2 配方（三个字段都是 null）会得到 [NONE]** ——
         * 这是刻意的：应用一条老配方必须把上一张图残留的曲线/蒙版清掉，
         * 否则「配方说它没曲线，画面却还带着上一条的曲线」。
         */
        fun of(recipe: YishunRecipe): ProOps = ProOps(
            curves = RecipePro.curvesOf(recipe),
            bands = RecipePro.hslOf(recipe),
            masks = RecipePro.masksOf(recipe),
        )
    }
}
