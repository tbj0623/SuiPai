package com.suipai.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 把 intent 交给别的 App，**没人接手时要说出来**。
 *
 * 原来八个出口都是 `runCatching { context.startActivity(...) }`，一个 catch 也不写：
 * 没有可接手的应用（干净系统、没装地图、分享目标被卸载）时，
 * 用户点了「分享 / 打开 / 地图 / 去设置」什么也不会发生，界面也不说一个字 ——
 * 和剪贴板那一类（`getSystemService(...)?.setPrimaryClip`）是同一个形状：
 * 把"没做成"吞成"像做成了"。
 *
 * 返回值给调用点决定要不要继续（例如"存完就分享"里分享失败不必把已存的说成没存）。
 */
internal fun sendOrTell(context: Context, intent: Intent, label: String, tell: (String) -> Unit): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Launch", "没有能「$label」的应用", e)
        tell("没有能「$label」的应用")
        false
    } catch (e: Exception) {
        Log.w("Launch", "「$label」没发出去", e)
        tell("「$label」没打开：${e.message ?: "系统不让"}")
        false
    }

/** 本应用（系统设置里的「打开该应用」）那一页。 */
private fun appSettingsIntent(context: Context): Intent =
    android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 权限被「永久拒绝」之后系统不再弹框，只能把用户送到本应用的设置页。
 *
 * 原来这段抄在两处（相机页与授权门），而授权门那两处是裸 `context.startActivity` ——
 * 没有 `resolveActivity` 检查也没有 catch：万一这台设备没有设置页可跳，
 * 用户点「去设置」就是**什么都没发生**。授权门是用户看到的第一屏，最不该静默。
 */
internal fun openAppSettings(context: android.content.Context, tell: (String) -> Unit) {
    sendOrTell(context, appSettingsIntent(context), "打开本应用的设置页", tell)
}

/**
 * Android 14 的「系统照片选择器 → 选择和管理照片」那一页。
 *
 * 常量在 SDK 35/36 的桩里核对过存在（`android.provider.MediaStore` 上直接挂着
 * `ACTION_PICK_IMAGES_SETTINGS = "android.provider.action.PICK_IMAGES_SETTINGS"`），
 * 但**这台机器上没有真点开过**：桩里有不等于设备接得住，更不等于它落到哪一页。
 * 所以这一条只当"首选出口"，调用点必须带兜底（[openPhotoAccessSettings]），
 * 而且发不出去的那一句不许咽下去。
 */
internal fun photoPickerSettingsIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= 34) {
        Intent(android.provider.MediaStore.ACTION_PICK_IMAGES_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        null
    }

/**
 * 「只授权了部分照片」之后回到系统里改授权。
 *
 * 两条出口的先后与失败后的说法都在这里：先试 [photoPickerSettingsIntent]（语义未验的那条），
 * 解析不到就退回 [openAppSettings]（授权门一直在用、装机走过的那条）。
 * 首选那条**发出去了但没人接**时不就地放弃：接着试兜底，兜底也倒了才说，
 * 而且两句都说 —— 只报兜底那一句会把「其实试过头那条」这件事藏掉。
 */
internal fun openPhotoAccessSettings(context: Context, tell: (String) -> Unit) {
    val pick = photoPickerSettingsIntent()
    if (pick == null || pick.resolveActivity(context.packageManager) == null) {
        openAppSettings(context, tell)
        return
    }
    var why: String? = null
    if (sendOrTell(context, pick, "打开系统的「选择照片」设置页", { why = it })) return
    openAppSettings(context) { fallback ->
        tell("$fallback；「选择照片」那一页也没打开：${why ?: "没答话"}")
    }
}

/**
 * 这台机器上到底有没有能接住「改照片授权」的应用 —— 两条出口都探一遍。
 *
 * 判据给「补授权」那颗格子的 `enabled`：守卫 #19 那条老规矩，做不了的时候它自己就是灰的，
 * 不许"点下去一声不响"。两个都解析不到（干净系统、Settings 被禁）时灰掉，
 * 同时那句提示改口说"要到系统设置里手动改"（`partialPhotoNotice`）。
 */
internal fun photoAccessSettingsReachable(context: Context): Boolean {
    val pm = context.packageManager
    if (photoPickerSettingsIntent()?.resolveActivity(pm) != null) return true
    return appSettingsIntent(context).resolveActivity(pm) != null
}
