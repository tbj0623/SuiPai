package com.suipai.app.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 根外壳（页眉 / 底栏 / 关于抽屉）的状态。
 *
 * 为什么这部分不放进 [AppState]：它是**界面偏好**，不是照片数据。
 * 分开放的另一个好处是它只依赖 `SharedPreferences`（不需要权限、不需要仓库），
 * 于是 MainActivity 那一段"读 prefs、跟着 dark 改状态栏图标明暗"可以原样留着。
 */
class ChromeState(private val prefs: SharedPreferences) {

    /**
     * 暗房 / 日光。系统栏已经全透明，图标明暗只能自己跟着这一格：
     * 不跟着设的话，日光（相纸白）页面上会是一排白色状态栏图标，直接看不见。
     */
    var darkUi by mutableStateOf(prefs.getBoolean("dark_ui", true))
        private set

    /** 「关于随拍」抽屉开着。 */
    var aboutOpen by mutableStateOf(false)

    /** 页眉那颗日光/暗房切换。落盘一次（与改前一致：`.apply()`，不阻塞主线程）。 */
    fun toggleDark() {
        darkUi = !darkUi
        prefs.edit().putBoolean("dark_ui", darkUi).apply()
    }

    fun openAbout() {
        aboutOpen = true
    }

    fun closeAbout() {
        aboutOpen = false
    }
}
