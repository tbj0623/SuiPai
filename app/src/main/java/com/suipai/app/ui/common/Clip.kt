package com.suipai.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 往剪贴板写一次，并老实回答**写成了没有**。
 *
 * 收这个函数之前，全项目有 20 处剪贴板写入，每一处都是同一个形状：
 *
 * ```
 * runCatching {
 *     val clip = ClipData.newPlainText("随拍", 值)
 *     context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
 *     copyHint = "已复制画幅"          // ← 无条件
 * }
 * ```
 *
 * 那个 `?.` 把两件事变成成功的空操作：服务没拿到（剪贴板被系统回收、
 * 或某些 ROM 上 `getSystemService` 返回 null）时一张字节都没写，
 * 格子却亮成"已复制画幅"。其中"复制全部信息"那一格连 `onFailure` 都没有 ——
 * 抛异常时连一句错都不说。20 份复制的判据，就是 20 个可以说谎的地方，
 * 所以判据只留这一处，调用方一律按返回值说话。
 */
fun clipWrite(context: Context, label: String, data: ClipData): Boolean =
    try {
        val cm = context.getSystemService(ClipboardManager::class.java)
        if (cm == null) {
            android.util.Log.w("Clip", "拿不到 ClipboardManager，$label 没写进去")
            false
        } else {
            cm.setPrimaryClip(data)
            true
        }
    } catch (e: Exception) {
        // 不静默：这一格到底成没成，界面上要说得出原因。
        android.util.Log.w("Clip", "剪贴板写入失败：$label", e)
        false
    }
