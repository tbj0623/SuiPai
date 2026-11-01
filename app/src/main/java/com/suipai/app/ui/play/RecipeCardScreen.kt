package com.suipai.app.ui.play

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.core.content.FileProvider
import com.suipai.app.data.MediaRepository
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.design.Spacing
import com.suipai.app.ui.design.SuiPaiColor
import com.suipai.app.ui.design.SuiPaiShapes
import com.suipai.app.ui.design.SuiPaiType
import com.suipai.core.engine.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 配方卡屏：预览 + 两个动作（存到相册 / 分享）。
 *
 * ## 参数只有三个
 *
 * `look` / `intensity` / `onBack` —— **不 import `state` 包、不认导航**。
 * 这一屏是纯展示 + 两个出口，接进哪儿由外面决定。这样做的直接好处：
 * `state` 包正在被并行 lane 改，这一屏不跟着它抖动；单测与预览也不必搭一套 app 状态。
 *
 * ## 两条出口的口径
 *
 * - **存到相册**：`MediaRepository.saveBitmap` —— 与拼图、一瞬、导出**同一条口径**
 *   （`Pictures/SuiPai` + `IS_PENDING` 二段式）。这是一条已验证的落盘路径，不另造。
 * - **分享**：优先分享刚存好的 **MediaStore uri**（`content://media/...`，天然可读，
 *   不需要 FileProvider）。只有落盘失败时才退到 `cacheDir/camera/` + FileProvider ——
 *   那条路径在 `res/xml/file_paths.xml` 里**已经声明**，所以不需要改 manifest。
 *
 * ## 卡本身不碰照片
 *
 * 这一屏**不读任何照片**：卡上只有配方与参数（见 `RecipeCard.kt` 文件头）。
 * 所以它既不需要相册读权限，也不会把照片带进分享。
 */
@Composable
fun RecipeCardScreen(
    look: Look,
    intensity: Float,
    onBack: () -> Unit,
    /** 落盘。返回新 uri；null = 没存上。抽成参数是为了让调用方决定用哪一个仓库实例。 */
    onSave: suspend (Bitmap, String) -> Uri? = { bmp, name -> null },
) {
    val colors = SuiPaiColor.current
    val context = LocalContext.current
    val repo = remember(context) { MediaRepository(context) }
    val scope = rememberCoroutineScope()

    var sizeOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val cardSize = RecipeCardSize.byOrdinal(sizeOrdinal)
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    // 卡的渲染是 CPU 活（1080×1350 的 Canvas + 字体排版）。放到 Default 上算，
    // 且按 (look, intensity, 尺寸) 缓存 —— 参数没变就不重画。
    // 这就是 PerfSourceGuardTest 里"大对象不许随重组重建"那条规矩的同源要求。
    val bitmap by produceState<Bitmap?>(initialValue = null, look, intensity, cardSize) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                renderRecipeCard(look, intensity, IntSize(cardSize.width, cardSize.height))
            }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        AppBarRow(Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)) {
            AppBarCell(label = "返回", on = false, onClick = onBack)
            AppBarText(
                look.name,
                style = SuiPaiType.frameNo,
                color = colors.inkSecondary,
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(horizontal = Spacing.xxs),
            )
            // 卡的比例三档循环。不是装饰性设置：4:5 发朋友圈、1:1 发聊天、9:16 当壁纸。
            AppBarCell(
                label = cardSize.label,
                on = sizeOrdinal != 0,
                modifier = Modifier.testTag("recipe_card_size"),
                onClick = { sizeOrdinal = cardSize.next().ordinal },
            )
        }

        val hint = note
        if (hint != null) {
            Text(
                hint,
                style = SuiPaiType.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                Text("正在印卡…", style = SuiPaiType.bodyMedium, color = colors.inkTertiary)
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "配方卡预览：${look.name}，强度 ${(intensity * 100).toInt()}%",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(cardSize.width.toFloat() / cardSize.height)
                        .background(colors.panel, SuiPaiShapes.frame)
                        .testTag("recipe_card_preview"),
                )
            }
        }

        // 两个出口。**存与分享都要有结论**：存完说"已存"，分享失败说"没有能接住的应用"。
        AppBarRow(Modifier.padding(horizontal = Spacing.xs)) {
            AppBarCell(
                label = if (savedUri != null) "已存到相册" else "存到相册",
                on = savedUri != null,
                enabled = bitmap != null && !busy,
                modifier = Modifier.testTag("recipe_card_save"),
                onClick = {
                    val bmp = bitmap ?: return@AppBarCell
                    if (busy) return@AppBarCell
                    busy = true
                    scope.launch {
                        val name = recipeCardFileName(look.id, System.currentTimeMillis())
                        val uri = withContext(Dispatchers.IO) {
                            onSave(bmp, name) ?: repo.saveBitmap(bmp, name, png = true)
                        }
                        busy = false
                        savedUri = uri
                        note = if (uri != null) "配方卡已存到相册" else "配方卡没存上（相册不接收这张图）"
                    }
                },
            )
            AppBarPrimary(
                label = if (busy) "处理中" else "分享",
                enabled = bitmap != null && !busy,
                modifier = Modifier.testTag("recipe_card_share"),
                onClick = {
                    val bmp = bitmap ?: return@AppBarPrimary
                    if (busy) return@AppBarPrimary
                    busy = true
                    scope.launch {
                        val name = recipeCardFileName(look.id, System.currentTimeMillis())
                        val uri = savedUri ?: withContext(Dispatchers.IO) {
                            onSave(bmp, name) ?: repo.saveBitmap(bmp, name, png = true)
                        }
                        busy = false
                        if (uri != null) {
                            savedUri = uri
                            note = shareCard(context, uri)
                        } else {
                            // 落盘失败时的兜底：写进**已声明**的 cacheDir/camera/，
                            // 用 FileProvider 分享。两条都失败才说"分享不了"。
                            val fallback = withContext(Dispatchers.IO) { cacheCardFile(context, bmp, name) }
                            note = if (fallback != null) {
                                shareCard(context, fallback)
                            } else {
                                "配方卡没能写到临时目录，分享不了"
                            }
                        }
                    }
                },
            )
        }
        AppBarText(
            "卡上只有配方，没有照片 · 原图未上传",
            style = SuiPaiType.marker,
            color = colors.inkTertiary,
            layout = Modifier.fillMaxWidth(),
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

/**
 * 分享一张卡，返回**要说给用户的那句话**。
 *
 * 三种结果各有各的话：发出去 / 没有能接住的 App / 抛了异常。
 * 与 `ui/common/Launch.kt` 的 `sendOrTell` 同一条纪律 —— 失败不许静默。
 */
internal fun shareCard(context: Context, uri: Uri): String {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    var why: String? = null
    val ok = sendOrTell(context, Intent.createChooser(send, "分享配方卡"), "分享配方卡") { why = it }
    return if (ok) {
        "已打开分享"
    } else {
        // `sendOrTell` 已经把原因写进 why；带上它，别只说"失败了"。
        why ?: "分享没发出去"
    }
}

/**
 * 兜底：把卡写进 `cacheDir/camera/` 并换成 FileProvider uri。
 *
 * 为什么是 `camera/` 而不是新建 `recipe-cards/`：`res/xml/file_paths.xml` 里
 * **只声明了 `cache-path camera/`**，而 manifest / res 不在本 lane 的写作用域里。
 * 复用一个已声明的路径，比"改别人的 manifest"安全得多 ——
 * 文件名带 `SuiPai_配方卡_` 前缀，不会和系统相机那张 `system.jpg` 撞名。
 */
internal fun cacheCardFile(context: Context, bitmap: Bitmap, fileName: String): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, fileName)
    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/**
 * 这台机器上有没有能接住"分享图片"的活动。
 *
 * 与 `CastIntent` 同一套判据纪律：**做不了的时候自己就是灰的**，
 * 不许"点下去一声不响"。界面可以在进屏时探一次，把这个值传给按钮的 `enabled`。
 */
fun canShareCard(context: Context): Boolean = runCatching {
    val send = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
    send.resolveActivity(context.packageManager) != null
}.getOrDefault(false)
