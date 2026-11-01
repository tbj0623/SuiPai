package com.suipai.app.ui.search

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.app.ui.design.Touch
import com.suipai.app.ui.design.pressable

/**
 * 搜索屏的输入框：**可复用**、能自己抢焦点、带清除按钮。
 *
 * 为什么不用 `OutlinedTextField`：
 *  1. 它的提示语不受 `singleLine` 管，会自己折行把输入框撑高 —— 这个坑本项目在
 *     `GalleryScreen` 上量到过（720×1600 + 字级 2.0 时搜索框 528 px，整片网格被顶出屏幕）。
 *     自己画就只用一行 `Text(maxLines = 1)`。
 *  2. 这个世界没有 Material 的胶囊输入框。`BasicTextField` + 1dp 描边 + 近直角，
 *     与 `AppInlineSearch`／设置页那颗搜索框是同一件东西。
 *
 * 三条硬约定（都是"看起来没问题、用起来才知道"的那种）：
 *  - `maxLines = 1` + `singleLine = true`：换行会把这一行撑成两行，而它上面只有一条工具条；
 *  - **不许横向滚动**：滚出去的输入内容既看不见也不在语义树里（源码守卫第 1 条）；
 *  - 输入区自己带 `contentDescription`：左边那颗「搜索」是**兄弟节点**，
 *    焦点进到编辑区时读屏不会顺带念它（`AppInlineSearch` 同一个理由）。
 *
 * @param autoFocus 进入时自动聚焦并弹键盘。由调用方决定（不是写死 true），
 *   因为"从结果里回到搜索页"不该再把键盘弹一遍。
 * @param label 左侧常驻的字段名。做成常驻而不是占位符：占位句在这个高度必然被截成半句话。
 */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "搜索",
    placeholder: String = "文件名、相册、相机、2024-09、今天",
    autoFocus: Boolean = true,
    onSubmit: () -> Unit = {},
) {
    val colors = SuiPaiColor.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }

    // 只在该进来的时候抢一次焦点：`LaunchedEffect(Unit)` 在重组之间不会重跑，
    // 所以用户点建议、从结果里返回时不会又被抢一次。
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    }

    val fieldText: TextStyle = remember(colors.ink) {
        SuiPaiType.bodyMedium.copy(color = colors.ink)
    }
    val cursor = remember(colors.grease) { SolidColor(colors.grease) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Touch.min)
            .border(Touch.hairline, colors.outline, SuiPaiShapes.frame)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            label,
            style = SuiPaiType.frameNo,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(Spacing.xs))
        Box(Modifier.weight(1f)) {
            // 提示语只在**真的空**的时候画，而且自己就是单行 —— 不交给任何输入框组件去折行。
            if (query.isEmpty()) {
                Text(
                    placeholder,
                    style = SuiPaiType.bodyMedium,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                maxLines = 1,
                textStyle = fieldText,
                cursorBrush = cursor,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focus.clearFocus()
                        onSubmit()
                    },
                    onDone = { focus.clearFocus() },
                ),
                // 输入区自己带名字与身份：读屏焦点进到编辑区时说得出这里是搜索框；
                // testTag 挂在这一颗上（不是外层 Row），设备端探针才抓得到输入框本身。
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .testTag("search_field")
                    .semantics { contentDescription = "搜索照片" },
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.xs))
            ClearButton(onClear = { onQuery("") })
        }
    }
}

/**
 * 清除按钮。做成"外层管命中、内层管画"的结构（与 `AppBarCell` 一致的 48dp 命中区）：
 * 一颗 12sp 的「清除」如果只有文字那么大的命中区，拇指点不中。
 */
@Composable
private fun ClearButton(onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = Touch.min)
            .pressable(onClickLabel = "清空搜索", onClick = onClear)
            .padding(horizontal = Spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "清除",
            style = SuiPaiType.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}
