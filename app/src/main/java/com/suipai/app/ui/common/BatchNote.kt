package com.suipai.app.ui.common

/**
 * 批量动作的那一句结论。
 *
 * 原来五路 `batch*` 各自收尾，而且**只在出问题时说话**：全成功时选中一清空、
 * 选择条一消失，屏幕上一个字都没有 —— 装机复测点「副本」就是这么抓到的
 * （ANDROID-VERIFY §30：库里确实多了一行，界面上零反馈）。
 * 所以这几个函数每个分支都必须有话说，调用点不许再加 `if (失败 > 0)` 的门。
 *
 * 数字一律用"真的做了几张"，不用尝试次数：见 PerfSourceGuardTest 的 attemptCountProgress。
 */
internal fun copyNote(done: Int, failed: Int, total: Int): String = when {
    done == 0 -> "$total 张都没存上（源文件可能已经不在）"
    failed > 0 -> "存了 $done 张，$failed 张没存上"
    else -> "存了 $done 张"
}

internal fun deleteNote(deleted: Int, refused: Int, total: Int): String = when {
    deleted == 0 -> "$total 张都没删掉（可能是权限或文件已不在）"
    refused > 0 -> "删了 $deleted 张，$refused 张删不掉"
    else -> "删了 $deleted 张"
}

/**
 * 系统授权框回来之后的结论。`already` 是弹框之前就直删掉的那几张 ——
 * 不带上它，"混着删"那一趟会把已删的几张报丢。
 * 原来这段只分"全没允许 / 其他"两支，允许 2 张、扣下 3 张也报一句"删了 2 张"。
 */
internal fun consentNote(gone: Int, asked: Int, already: Int): String {
    val held = asked - gone
    return when {
        held == 0 -> if (already > 0) "删了 ${gone + already} 张" else "删了 $gone 张"
        gone == 0 -> if (already > 0) "没允许，${asked} 张没删（另删了 $already 张）"
        else "没允许，${asked} 张没删"
        else -> "删了 ${gone + already} 张，$held 张你没允许"
    }
}

internal fun hideNote(released: Boolean, count: Int, stored: Boolean): String =
    (if (released) "放出来 $count 张" else "藏了 $count 张") + notStoredTail(stored)

internal fun favoriteNote(released: Boolean, count: Int, stored: Boolean): String =
    (if (released) "取消藏了 $count 张" else "收藏了 $count 张") + notStoredTail(stored)

/**
 * 这一步没落盘时必须说出来。`Hidden` / `Favorites` 原来一律 `apply()`，
 * 写失败没有任何地方能观察到 —— 而"藏起来"是隐私：下次打开那些照片就又在时间线里了，
 * 当时屏幕上说的却是「藏了 N 张」。
 */
internal fun notStoredTail(stored: Boolean): String =
    if (stored) "" else "，但这一步没写进设置（下次打开会回到原样）"

/** 单张收藏/隐藏没有结论位，借这一句。注意 `${what}` 的花括号是承重的：
 *  Kotlin 里汉字可以进标识符，`$what什` 会被读成一个变量名。 */
internal fun persistWarning(what: String): String = "${what}没写进设置（下次打开会回到原样）"

/**
 * 一瞬批量的结论。`cancelled` 时**不许报张数**：
 * `BatchYishun.runDetailed` 的进度回调是 `onProgress(index + 1, total)`，
 * 那是尝试次数不是成功数，中途停下时手上没有可信的"出了几张"。
 * （同一个坑见 PerfSourceGuardTest 的 attemptCountProgress。）
 */
internal fun yishunNote(succeeded: Int, failed: Int, cancelled: Boolean): String = when {
    cancelled -> "一瞬停了，没算出出了几张"
    succeeded == 0 && failed > 0 -> "$failed 张都没出（源文件可能已经不在）"
    failed > 0 -> "完成 $succeeded 张，失败 $failed 张"
    else -> "出了 $succeeded 张"
}
