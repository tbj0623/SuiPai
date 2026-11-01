package com.suipai.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.theme.SuiPaiSheet

/**
 * 矮屏判定。手机横屏只有 411 dp 高，宽度反而很富余（914 dp），
 * 所以那里最该省的是纵向：竖屏 915 dp、平板横屏 800 dp 都不成立，
 * 阈值 600 卡在这两档实测值之间 → 竖屏那套已验证过的排版一行都不受影响。
 */
@Composable
fun rememberShortViewport(): Boolean = LocalConfiguration.current.screenHeightDp < 600

/**
 * 矮屏、并且横向宽到放得下一条内嵌搜索框。
 * 横屏分屏能同时满足 h<600 与窄宽度，那种情况下内嵌会把工具条挤得出屏，
 * 所以宽度不够时退回搜索框独占一行的原排版。
 */
@Composable
fun rememberWideShortViewport(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenHeightDp < 600 && cfg.screenWidthDp >= 560
}

/**
 * 挂在工具条那一行末尾的搜索框，吃掉整行剩下的宽度。
 *
 * 「搜索」做成常驻字段名而不是会消失的占位符：这一行只有 38 dp 高，
 * 长占位句（「文件名、相册、2024-09、今天」）在这里必然被截成半句话，
 * 而截断后的提示比没有提示更容易让人以为搜索坏了。
 */
@Composable
fun RowScope.AppInlineSearch(
    query: String,
    onQuery: (String) -> Unit,
    label: String = "搜索",
) {
    val sheetType = SuiPaiSheet.type
    val colors = SuiPaiSheet.colors
    val focus = LocalFocusManager.current
    Row(Modifier.weight(1f).height(AppBarHeight - 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline, SuiPaiSheet.frame)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = sheetType.frameNo, color = colors.number, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = sheetType.label.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.grease),
                // 键盘上的「完成」要能把键盘收掉：矮屏一共只有 411 dp 高，
                // 键盘挂着不走就占掉近一半屏幕，而搜索结果就在它后面。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                // 字段自己不带名字：左边那颗「搜索」是**兄弟节点**，焦点进到编辑区时不会顺带念它。
                // `BasicTextField` 的 modifier 与编辑节点落在同一个节点上（BasicSecureTextField.kt:164），
                // 所以这里给名字是有效的。
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label },
            )
        }
        if (query.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            AppBarCell(label = "清除", on = false, onClick = { onQuery("") })
        }
    }
}
