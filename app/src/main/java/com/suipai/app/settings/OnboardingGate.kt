package com.suipai.app.settings

/**
 * 首启引导的"该不该出现"。
 *
 * 纯 Kotlin：判定只看一份 [SettingsSnapshot]，不碰 `Context`、不碰 Compose，
 * 所以"连读三次是不是同一个答案"能直接写成 JVM 单测（`OnboardingTest`）。
 *
 * 判定是**两个条件的与**，不是一个布尔：
 * - [SettingsKeys.General.onboarded]：用户真的走完过一次；
 * - [SettingsKeys.General.onboardingVersion]：走完的是不是当前这一版内容。
 *
 * 为什么留版本号：引导改了文案（例如以后加一屏"局域网互通"），老用户不该被闷在旧内容里，
 * 但也不该在每次升级时重看一遍。两个条件分开之后，"改内容"这个动作只需要 +1，
 * 不必写迁移代码去清一个布尔。
 */
object OnboardingGate {

    /** 当前引导内容的版本。**改了引导内容就 +1**，判定会自己把老用户再放进来一次。 */
    const val CONTENT_VERSION = 1

    /** 该不该弹引导。没走完、或者走完的是旧版本内容 → true。 */
    fun shouldShow(snapshot: SettingsSnapshot): Boolean {
        if (!snapshot.bool(SettingsKeys.General.onboarded)) return true
        return snapshot.int(SettingsKeys.General.onboardingVersion) < CONTENT_VERSION
    }

    /**
     * 标记走完。**幂等**：连写两次与写一次之后读三次，结果完全一样
     * （两条 `put` 都写的是常量，重复写不产生新值）。
     */
    fun complete(store: SettingsStore) {
        store.put(SettingsKeys.General.onboarded, true)
        store.put(SettingsKeys.General.onboardingVersion, CONTENT_VERSION)
    }

    /**
     * 从设置里"重看引导"：只把版本号退回去，不动 [SettingsKeys.General.onboarded]。
     *
     * 为什么不直接清 onboarded：重看是用户主动的，看完要能回到"已完成"这条路上；
     * 清掉布尔之后，如果他中途退出，下次冷启动又会被弹一次 —— 那是把他自己点的东西
     * 变成了一个关不掉的开关。退回版本号等价于"这次没看完当前内容"，
     * 且他随时可以在设置里再点一次。
     */
    fun replay(store: SettingsStore) {
        store.put(SettingsKeys.General.onboardingVersion, 0)
    }

    /** 设置页上那句状态话。 */
    fun statusText(snapshot: SettingsSnapshot): String = when {
        !snapshot.bool(SettingsKeys.General.onboarded) -> "还没走完"
        snapshot.int(SettingsKeys.General.onboardingVersion) < CONTENT_VERSION -> "看过旧版（v${snapshot.int(SettingsKeys.General.onboardingVersion)}）"
        else -> "已走完 v$CONTENT_VERSION"
    }
}
