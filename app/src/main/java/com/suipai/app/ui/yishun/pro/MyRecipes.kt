package com.suipai.app.ui.yishun.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suipai.app.data.CustomLook
import com.suipai.app.data.CustomLooks
import com.suipai.app.engine.Shift
import com.suipai.app.engine.Shifts
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.clipWrite
import com.suipai.core.engine.LookLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「我的配方」：把当前这组参数存下来、导出/导入、重命名、删除。
 *
 * 仓库是 [CustomLooks]（`filesDir/custom_looks.json`，不进 SharedPreferences，
 * 也不进系统备份 —— 理由见那个类的注释）。本文件只做三件事：
 * 1. 把界面上的散装状态（基础配方 + 强度 + 7 偏移 + 专业三组）拼成一条 [CustomLook]；
 * 2. 反过来把一条 [CustomLook] 拆回那些状态；
 * 3. 导入/导出走剪贴板，**一律经 `clipWrite`**（`PerfSourceGuardTest.clipboardHasExactlyOneWriter`
 *    只允许 `Clip.kt` 里那一处 `setPrimaryClip`）。
 */

/** 从界面状态拼一条配方。`id` 为空 / 非 `user_` 前缀时由仓库补一个新 id（覆盖用同 id）。 */
fun buildCustomLook(
    name: String,
    baseLookId: String,
    intensity: Float,
    shifts: Shifts,
    pro: ProOps,
    id: String? = null,
    created: Long = 0L,
): CustomLook = CustomLook.from(
    name = name.trim().ifBlank { defaultRecipeName(baseLookId) },
    baseLookId = baseLookId,
    intensity = intensity,
    shifts = shifts,
    curves = pro.curves,
    hsl = pro.bands,
    masks = pro.masks,
    id = id,
    created = created,
)

/** 名字留空时的兜底：用基础配方名 + 「我的」，比空行或「未命名」都好认。 */
fun defaultRecipeName(baseLookId: String): String =
    "我的" + (LookLibrary.byId(baseLookId)?.name ?: "配方")

/** 一条配方拆回界面参数。 */
data class AppliedCustom(
    val lookId: String,
    val intensity: Float,
    val shifts: Shifts,
    val pro: ProOps,
    val name: String,
)

/**
 * @return null 表示它的基础配方已经不在库里（老版本存过一个后来被删掉的 id）。
 *         这种情况**不能静默用 daylight 顶替**：用户会看到颜色全变，而名字还是"我的 xx"。
 */
fun CustomLook.toApplied(): AppliedCustom? {
    val base = LookLibrary.byId(baseLookId) ?: return null
    return AppliedCustom(
        lookId = base.id,
        intensity = intensity.coerceIn(0f, 1f),
        shifts = shifts(),
        pro = ProOps(curves = curves, bands = bands, masks = localMasks),
        name = name,
    )
}

/**
 * 「我的配方」列表段：直接塞进现有「筛选」抽屉。
 *
 * 交互：**点一下应用**、**长按展开三个动作**（改名 / 导出 / 删除）。
 * 长按那三个动作是第三种"一格两动作"的形状，所以 `onClickLabel` 与 `onLongClickLabel`
 * 都写全（`PerfSourceGuardTest.everyPressActionIsNamed` 会扫 `combinedClickable`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyRecipesSection(
    items: List<CustomLook>,
    onApply: (CustomLook) -> Unit,
    onRename: (CustomLook) -> Unit,
    onDelete: (CustomLook) -> Unit,
    onExport: (CustomLook) -> Unit,
    onImport: () -> Unit,
) {
    AppSheetSection(
        title = "我的配方",
        note = if (items.isEmpty()) "还没有，先去专业面板里「存配方」" else "点一下应用，长按改名/导出/删除",
    )
    if (items.isNotEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            items.forEach { look ->
                RecipeRow(
                    look = look,
                    onApply = { onApply(look) },
                    onRename = { onRename(look) },
                    onExport = { onExport(look) },
                    onDelete = { onDelete(look) },
                )
            }
        }
    }
    AppChipFlow {
        AppBarCell(
            label = "导入配方",
            on = false,
            modifier = Modifier.testTag("yishun_recipe_import_from_filter"),
            onClick = onImport,
        )
    }
}

/**
 * 一行配方。点击应用、长按展开管理动作。
 *
 * 没有用 `AppBarCell`：那个组件内部是 `clearAndSetSemantics`，会把 `combinedClickable`
 * 的 click 动作一起清掉（同样的坑记在 `AppBar.kt` 的 `AppBarPrimary` 注释里）。
 * 这里自己搭一颗，两颗动作都留着，语义只补一个 `contentDescription`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeRow(
    look: CustomLook,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(look.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .combinedClickable(
                    onClickLabel = "应用「${look.name}」",
                    onLongClickLabel = "管理「${look.name}」",
                    onClick = onApply,
                    onLongClick = { menu = !menu },
                )
                .semantics { contentDescription = "我的配方：${look.name}" }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                look.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                recipeBadge(look),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (menu) {
            AppChipFlow {
                AppBarCell(label = "改名", on = false, onClick = { menu = false; onRename() })
                AppBarCell(label = "导出", on = false, onClick = { menu = false; onExport() })
                AppBarCell(label = "删除", on = false, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** 一行角标：`曲线+分色+2蒙版`，让用户不用点开就知道这条配方有多重。 */
internal fun recipeBadge(look: CustomLook): String {
    val parts = buildList {
        if (look.curve != null) add("曲线")
        if (look.hsl != null) add("分色")
        val masks = look.masks?.size ?: 0
        if (masks > 0) add("${masks}蒙版")
    }
    return if (parts.isEmpty()) "仅预设" else parts.joinToString("+")
}

/**
 * 参数摘要：一行说清"存下去的是什么"，避免用户存完才发现少了东西。
 *
 * 微调项按「与 0 不同」数，而不是数全部 7 项：7 项全 0 时说"微调 7 项"是假话。
 */
internal fun summaryLine(baseLookName: String, intensity: Float, shifts: Shifts, pro: ProOps): String {
    val parts = buildList {
        add("基础 $baseLookName")
        add("强度 ${(intensity * 100).toInt()}%")
        val shifted = Shift.entries.count { it.get(shifts) != 0f }
        if (shifted > 0) add("微调 $shifted 项")
        if (!pro.curves.isNeutral) add("曲线")
        if (!pro.bands.isNeutral) add("分色")
        if (pro.masks.isNotEmpty()) add("${pro.masks.size} 蒙版")
    }
    return parts.joinToString(" · ")
}

/**
 * 「存配方」抽屉：一个名字输入框 + 当前参数摘要 + 保存。
 *
 * 名字输入用 `BasicTextField` 而不是 `OutlinedTextField`：抽屉里已经有一个搜索框，
 * 再来一个带 outline 与 label 的输入框，看起来像两张并列的表单。
 * 单行 + `imeAction = Done`；末端的「保存」格子不依赖回车（软键盘收起时也要能按）。
 */
@Composable
fun SaveRecipeSheet(
    baseLookId: String,
    baseLookName: String,
    intensity: Float,
    shifts: Shifts,
    pro: ProOps,
    existingNames: List<String>,
    onSave: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultRecipeName(baseLookId)) }
    val trimmed = name.trim()
    // 重名不拦：用户可能是故意的（同一套参数的两档强度）。但要说出来，别让他以为覆盖了旧的那条。
    val duplicated = trimmed.isNotEmpty() && existingNames.any { it == trimmed }
    AppSheet(title = "存成我的配方", tag = "yishun_sheet_save_recipe", onDismiss = onDismiss) {
        AppSheetSection(title = "名字", note = "留空会自动叫「${defaultRecipeName(baseLookId)}」")
        RecipeTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            placeholder = "配方名字",
            description = "配方名字",
            tag = "yishun_recipe_name",
        )
        if (duplicated) {
            Text(
                "已经有一条同名配方；保存会新增一条，不会覆盖旧的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AppSheetSection(title = "这一条会存下什么")
        Text(
            summaryLine(baseLookName, intensity, shifts, pro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppChipFlow {
            AppBarCell(
                label = "保存",
                on = false,
                modifier = Modifier.testTag("yishun_recipe_confirm"),
                onClick = { onSave(trimmed) },
            )
            AppBarCell(label = "取消", on = false, onClick = onDismiss)
        }
    }
}

/**
 * 导入抽屉：粘贴一段 `SPLOOK1:…`，解析成功就交给调用方落盘。
 *
 * 失败一定是**说人话**（`CustomLooks.importText` 返回 null 就说明这段文本不是配方），
 * 不做"猜它想表达什么"——剪贴板里的东西可能是任何东西。
 */
@Composable
fun ImportRecipeSheet(
    onImport: (CustomLook) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val repo = remember { CustomLooks(context) }

    fun submit() {
        val raw = text.trim()
        if (raw.isEmpty()) {
            error = "先粘贴一段配方文本（以 ${CustomLooks.MARKER} 开头）"
            return
        }
        val parsed = repo.importText(raw)
        if (parsed == null) {
            error = "这段文本里没有可识别的配方（应该是 ${CustomLooks.MARKER} 开头的一串字符）"
            return
        }
        error = null
        onImport(parsed)
    }

    AppSheet(title = "导入配方", tag = "yishun_sheet_import_recipe", onDismiss = onDismiss) {
        AppSheetSection(
            title = "粘贴配方文本",
            note = "朋友发给你的那段以 ${CustomLooks.MARKER} 开头的文字，整段粘进来即可",
        )
        RecipeTextField(
            value = text,
            // 单行输入但允许粘贴整段：换行替换成空格，避免输入框长出多行高度把抽屉顶穿。
            onValueChange = { text = singleLineOf(it); error = null },
            placeholder = "SPLOOK1:…",
            description = "配方文本",
            tag = "yishun_recipe_import_text",
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        AppChipFlow {
            AppBarCell(
                label = "从剪贴板读",
                on = false,
                modifier = Modifier.testTag("yishun_recipe_paste"),
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    // `ClipData.Item.text` 的静态类型是 CharSequence?，而这里要的是 String
                    // 的 `replace(Char, Char)` 重载（CharSequence 上只有 Regex 那条）——
                    // 先落到 String 再改，别在智能转换上赌编译器。
                    val raw: String? = cm?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                    if (raw.isNullOrBlank()) {
                        error = "剪贴板里没有文字"
                    } else {
                        text = singleLineOf(raw)
                        error = null
                    }
                },
            )
            AppBarCell(
                label = "导入",
                on = false,
                modifier = Modifier.testTag("yishun_recipe_import_go"),
                onClick = { submit() },
            )
            AppBarCell(label = "取消", on = false, onClick = onDismiss)
        }
    }
}

/**
 * 单行输入框的唯一实现（名字框与导入框共用）。
 *
 * 不用 `OutlinedTextField` 的理由见 [SaveRecipeSheet]；不用「裸 `BasicTextField`
 * 直接写两遍」的理由是占位符、单行、字色、`maxLines` 这四条一处写错就露馅 ——
 * 而 `PerfSourceGuardTest.singleLineFieldsMustNotWrapTheirPlaceholder` 只管
 * `OutlinedTextField` 的 placeholder，管不到这里，所以更要收成一处。
 */
@Composable
internal fun RecipeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    tag: String,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = description }
            .testTag(tag),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    inner()
                }
            }
        },
    )
}

/**
 * 剪贴板 / 输入框里的整段文字压成一行。
 *
 * 为什么不直接用 `replace('\n', ' ')`：`String.replace(Char, Char)` 只在 String 接收者上
 * 有那个重载，而 `ClipData.Item.text` 的静态类型是 `CharSequence?`——过了 `isNullOrBlank()`
 * 的智能转换之后仍然可能走 CharSequence 那条（只剩 Regex 重载），编译直接红。
 * 用 `\r\n|\r|\n` 一次替换，比链式两次更省一个中间字符串。
 */
private fun singleLineOf(raw: String): String =
    raw.replace(Regex("[\r\n]+"), " ").trim().take(2000)

/**
 * 导出：把一条配方写进剪贴板，**按 `clipWrite` 的返回值说话**。
 *
 * 读盘在 IO 上（仓库是同步文件 IO），返回值是一句人话，调用方直接挂到页眉那行 `message`。
 */
suspend fun exportRecipeText(context: Context, look: CustomLook): String {
    val text = withContext(Dispatchers.IO) { CustomLooks(context).exportText(look) }
    val wrote = clipWrite(context, "配方「${look.name}」", ClipData.newPlainText("随拍配方", text))
    return if (wrote) "已复制配方「${look.name}」" else "复制失败：剪贴板不可用"
}
