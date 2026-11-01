package com.suipai.app.ui.common

import androidx.compose.animation.core.Easing
import com.suipai.app.ui.design.MotionScale

/**
 * 旧的动作刻度文件：数值与缓动**已迁到** `ui/design/Motion.kt`（设计系统 v1）。
 *
 * 这个文件只为"别处还写着 `Motion.Enter`"而存在 —— 名字保留、行为逐毫秒不变、
 * 每个成员都标了 @Deprecated 并指向新家。新代码请直接用 `MotionScale`。
 *
 * 迁移路径（代码里按 IDE 的 Replace 提示一路点下去即可）：
 *
 * ```
 * Motion.Press    → MotionScale.Press
 * Motion.Enter    → MotionScale.Enter
 * Motion.Exit     → MotionScale.Exit
 * Motion.State    → MotionScale.State
 * Motion.Replace  → MotionScale.Replace
 * Motion.Screen   → MotionScale.Screen
 * Motion.Standard → MotionScale.Standard
 * Motion.Accelerate → MotionScale.Accelerate
 * rememberMotionScale() → com.suipai.app.ui.design.rememberMotionScale()
 * ```
 *
 * 为什么留这个壳而不是直接删：这个仓库同一时刻有多条 lane 在改不同的界面文件，
 * 删掉类名会让别人正在编辑的文件编译不过，而那不是他们的错。
 * 等 ui 包里的引用清零之后，这个文件整个删掉即可（`MotionScaleGuardTest` 把"只剩别名"钉住了）。
 * <!-- 注意：上面那句原来写的是 `ui/**`，而 `**/` 会**提前闭合 Kotlin 块注释**，
 *      编译器在文件末尾报 `Syntax error: Unclosed comment`，且 `object Motion` 整个被吃掉，
 *      下游 `GalleryScreen.kt` 报一串 "Motion 未解析"。谁都不许在这里写带 `*` 的路径字面量。 -->
 */
@Deprecated(
    "动作刻度已迁到 ui/design/Motion.kt 的 MotionScale。见 ui/common/Motion.kt 顶部的迁移表。",
    ReplaceWith("MotionScale", "com.suipai.app.ui.design.MotionScale"),
)
object Motion {
    /** @see MotionScale.Press */
    @Deprecated("改用 MotionScale.Press", ReplaceWith("MotionScale.Press", "com.suipai.app.ui.design.MotionScale"))
    const val Press = MotionScale.Press

    /** @see MotionScale.Enter */
    @Deprecated("改用 MotionScale.Enter", ReplaceWith("MotionScale.Enter", "com.suipai.app.ui.design.MotionScale"))
    const val Enter = MotionScale.Enter

    /** @see MotionScale.Exit */
    @Deprecated("改用 MotionScale.Exit", ReplaceWith("MotionScale.Exit", "com.suipai.app.ui.design.MotionScale"))
    const val Exit = MotionScale.Exit

    /** @see MotionScale.State */
    @Deprecated("改用 MotionScale.State", ReplaceWith("MotionScale.State", "com.suipai.app.ui.design.MotionScale"))
    const val State = MotionScale.State

    /** @see MotionScale.Replace */
    @Deprecated("改用 MotionScale.Replace", ReplaceWith("MotionScale.Replace", "com.suipai.app.ui.design.MotionScale"))
    const val Replace = MotionScale.Replace

    /** @see MotionScale.Screen */
    @Deprecated("改用 MotionScale.Screen", ReplaceWith("MotionScale.Screen", "com.suipai.app.ui.design.MotionScale"))
    const val Screen = MotionScale.Screen

    /** @see MotionScale.Standard */
    @Deprecated("改用 MotionScale.Standard", ReplaceWith("MotionScale.Standard", "com.suipai.app.ui.design.MotionScale"))
    val Standard: Easing = MotionScale.Standard

    /** @see MotionScale.Accelerate */
    @Deprecated("改用 MotionScale.Accelerate", ReplaceWith("MotionScale.Accelerate", "com.suipai.app.ui.design.MotionScale"))
    val Accelerate: Easing = MotionScale.Accelerate
}

/**
 * @see com.suipai.app.ui.design.rememberMotionScale
 */
@Deprecated(
    "改用 ui/design/Motion.kt 的 rememberMotionScale()",
    ReplaceWith("rememberMotionScale()", "com.suipai.app.ui.design.rememberMotionScale"),
)
@androidx.compose.runtime.Composable
fun rememberMotionScale(): Float = com.suipai.app.ui.design.rememberMotionScale()
