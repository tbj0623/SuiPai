package com.suipai.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 「设置刚刚被改过」这一个事实的广播口。
 *
 * ── 为什么需要它 ──
 *
 * 主题与字号是在 `MainActivity` 最外层、**导航宿主之上**读的（`ui/theme/Theme.kt` 的
 * `SuiPaiTheme`）。设置中心改一条偏好时，重组只发生在设置页那一棵子树里 ——
 * 外面那层根本不知道自己该重来一次，于是"选了日光、整屏还是暗房"，要等下次冷启动。
 * 那正是这一轮要消灭的那类"拨了像没拨"。
 *
 * 所以写路径统一在这里 +1，读路径在组合里读一下 [revision]。它是个普通的 Compose 状态：
 * 值一变，读过它的组合就失效 —— 与 `mutableStateOf` 的语义完全一样，只是**跨子树**。
 *
 * ── 为什么不放进 `state/` ──
 *
 * 这一层只跟"设置被写了"有关，跟照片、导航、选择集都无关；放进 `state/` 会让设置数据层
 * 反过来认识应用状态。而放在设置包里的代价只是：`SettingsStore` 多了一个 compose-runtime
 * 的 import（JVM 单测里它照样能用，`mutableIntStateOf` 不依赖 Android）。
 */
object SettingsSignal {

    /**
     * 累计写入次数。**只增不清** —— 读者要的是"变过了"，不是"现在第几次"，
     * 所以归零没有任何价值，反而会让两次不同的写入在同一个值上撞车。
     */
    var revision by mutableIntStateOf(0)
        private set

    /** 写路径在落盘之后调一次。**不抛异常、不做 IO**，它只是让读者知道该重读。 */
    fun bump() {
        revision += 1
    }
}
