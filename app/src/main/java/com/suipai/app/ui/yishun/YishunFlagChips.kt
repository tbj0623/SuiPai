package com.suipai.app.ui.yishun

import androidx.compose.runtime.Composable
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow

/** 一瞬偏好开关的一组格子，整组由 [YishunFlag] 表生成；onlyWhen 不成立的不出现。 */
@Composable
fun YishunFlagChips(
    group: YishunFlagGroup,
    flags: YishunFlags,
    onToggle: (YishunFlag) -> Unit,
) {
    val shown = YishunFlag.inGroup(group).filter { it.visibleIn(flags) }
    if (shown.isEmpty()) return
    AppChipFlow {
        shown.forEach { f ->
            AppBarCell(label = f.label(flags), on = f.get(flags), onClick = { onToggle(f) })
        }
    }
}
