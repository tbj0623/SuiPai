package com.suipai.app.ui.yishun.pro

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.suipai.app.data.CustomLook
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection

/**
 * 「专业」入口抽屉：三块面板 + 存配方 / 导入 + 全部清零。
 *
 * 为什么不把三块面板直接平铺在顶栏：`AppBarRow` 是 `FlowRow`，能换行，
 * 但顶栏每多一格就多一份大字号下被挤扁的机会（字级 2.0 实测过格子只剩 15px，
 * 见 `AppBar.kt` 的 `AppBarRow` 注释）。所以顶栏只加**一格**「专业」，
 * 三块面板与两个配方动作都收进这里。
 *
 * 这里同时是"当前到底动过什么"的唯一说明书：每一格都带角标，
 * 用户从任何一个子面板返回都能看到自己改了哪几项。
 */
@Composable
fun ProHubSheet(
    pro: ProOps,
    recipeCount: Int,
    onOpenCurve: () -> Unit,
    onOpenHsl: () -> Unit,
    onOpenMask: () -> Unit,
    onSaveRecipe: () -> Unit,
    onImportRecipe: () -> Unit,
    onResetPro: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(title = "专业分级", tag = "yishun_sheet_pro", onDismiss = onDismiss) {
        AppSheetSection(
            title = "三块面板",
            note = if (pro.isNeutral) "都还没动：画面与只用量预设时逐像素相同" else "已动 ${pro.activeCount} 项",
        )
        AppChipFlow {
            AppBarCell(
                label = "曲线",
                on = !pro.curves.isNeutral,
                badge = if (pro.curves.isNeutral) 0 else 1,
                modifier = Modifier.testTag("yishun_pro_curve"),
                onClick = onOpenCurve,
            )
            AppBarCell(
                label = "分色",
                on = !pro.bands.isNeutral,
                badge = if (pro.bands.isNeutral) 0 else 1,
                modifier = Modifier.testTag("yishun_pro_hsl"),
                onClick = onOpenHsl,
            )
            AppBarCell(
                label = "蒙版",
                on = pro.masks.isNotEmpty(),
                badge = pro.masks.size,
                modifier = Modifier.testTag("yishun_pro_mask"),
                onClick = onOpenMask,
            )
        }

        AppSheetSection(
            title = "我的配方",
            note = if (recipeCount == 0) "还没有自建配方" else "本机存了 $recipeCount 条",
        )
        AppChipFlow {
            AppBarCell(
                label = "存配方",
                on = false,
                modifier = Modifier.testTag("yishun_pro_save_recipe"),
                onClick = onSaveRecipe,
            )
            AppBarCell(
                label = "导入",
                on = false,
                modifier = Modifier.testTag("yishun_pro_import_recipe"),
                onClick = onImportRecipe,
            )
        }

        AppSheetSection(title = "重置")
        AppChipFlow {
            AppBarCell(
                label = "全部清零",
                on = false,
                // 三项全中性时按灰：这一格点下去本来就不会有任何变化。
                enabled = !pro.isNeutral,
                modifier = Modifier.testTag("yishun_pro_reset"),
                onClick = onResetPro,
            )
        }
        Text(
            PRO_DOC_LINE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 一句说明，指向真正解释数学的地方（别在 UI 里抄第二份定义）。 */
private const val PRO_DOC_LINE =
    "三块面板都是逐像素点运算：预览按长边降采样，成片按导出长边重算；" +
        "存成配方后曲线/分色/蒙版一起进 EXIF，换设备也还原得出来。"

/**
 * 改名抽屉。
 *
 * 只改名、不动内容 —— 但要走 `CustomLooks.rename`（它负责刷新 `modified`），
 * 所以回调返回的是"磁盘上改完的整份列表"，调用方直接替换内存里的那份。
 */
@Composable
fun RenameRecipeSheet(
    look: CustomLook,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // key(look.id)：换一条目标时重置输入框，而不是把上一条的名字带过来。
    var name by remember(look.id) { mutableStateOf(look.name) }
    val trimmed = name.trim()
    AppSheet(title = "配方改名", tag = "yishun_sheet_rename_recipe", onDismiss = onDismiss) {
        AppSheetSection(title = "当前名字", note = look.name)
        AppSheetSection(title = "新名字", note = "留空不改；最长 24 字")
        RecipeTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            placeholder = "配方名字",
            description = "新配方名字",
            tag = "yishun_recipe_rename_text",
        )
        AppChipFlow {
            AppBarCell(
                label = "改名",
                on = false,
                // 名字没变或为空时按灰：按下去磁盘不会有任何变化。
                enabled = trimmed.isNotEmpty() && trimmed != look.name,
                modifier = Modifier.testTag("yishun_recipe_rename_go"),
                onClick = { onRename(trimmed) },
            )
            AppBarCell(label = "取消", on = false, onClick = onDismiss)
        }
    }
}
