package com.suipai.app.ui.play

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntSize
import com.suipai.core.engine.Look
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 配方卡的**数据层**：一张卡上该出现的每一句话、每一个数、每一块颜色。
 *
 * ## 为什么先有这一层，再谈画
 *
 * "同一组参数渲染出同一张卡"是这一屏唯一的正确性要求 —— 用户会把卡发给别人，
 * 两次生成不一样（比如混进了时间戳、随机数、或者遍历 `HashMap` 的顺序）就是缺陷。
 * 而"两次一样"在 JVM 单测里没法对着 `Bitmap` 断言：本工程
 * `unitTests.isReturnDefaultValues = true`，`Bitmap.createBitmap` 直接回 null。
 *
 * 所以卡被切成两层：**这一层是纯 Kotlin 数据**（可以逐字段断言"两次相等"、
 * 也可以断言"不含时间/随机"），下一层 [RecipeCardPixels] 是纯像素数组，
 * 最外层 [renderRecipeCard] 才碰 `android.graphics`。三层里前两层都可测，
 * 第三层是一段没有分支的 `Canvas` 执行。
 *
 * ## 卡上说什么
 *
 * 配方卡是「把一条配方抄在印样卡上送人」，所以它承载三件事：
 * 1. **这是哪条配方**（名字 + 摘要里那句特征）；
 * 2. **强度多少**（等宽数字，这是配方唯一必须被精确传达的参数）；
 * 3. **它是什么做的**（影调/饱和度/色温那一组关键参数，等宽）。
 *
 * 落款位只写一句「随拍 · 原图未上传」：这是产品原则里最该被看见的那一条，
 * 也确实**没有上传任何东西** —— 本卡片是纯函数，不碰网络、不读照片（见文件头）。
 */
@Immutable
data class RecipeCardModel(
    /** 卡顶那行小字：等宽、全大写、带字符间距。 */
    val kicker: String,
    /** 配方名（可能是任意中文，由 `Canvas` 排版）。 */
    val title: String,
    /** 配方摘要里那句特征（已是成品文案，可能为空）。 */
    val summary: String,
    /** 强度那一行：`强度 62%`。 */
    val strengthLine: String,
    /** 关键参数，等宽数字。每行一个"名 值"对，卡上最多显示 [MAX_PARAMS] 行。 */
    val parameterLines: List<String>,
    /** 底部署名。 */
    val footer: String,
    /** 强调色（ARGB）。取配方自己的温度：暖调偏琥珀、冷调偏青，单色配方走灰。 */
    val accentArgb: Int,
) {
    /** 两次生成必须相等 —— `RecipeCardTest.sameInputsGiveTheSameCard` 钉住。 */
    fun isSameAs(other: RecipeCardModel): Boolean = this == other

    companion object {
        /** 卡上最多几行参数。再多就不是"抄一条配方"而是一张参数表了。 */
        const val MAX_PARAMS: Int = 4

        const val KICKER: String = "SUIPAI · RECIPE CARD"
        const val FOOTER: String = "随拍 · 原图未上传"
    }
}

/**
 * 从配方与强度生成卡片模型。**纯函数**：同输入必同输出。
 *
 * 这里刻意**不接收**任何时间、随机种子、设备信息 —— 那些一旦进卡，
 * "同一张卡发两次不一样"就再也修不掉了。
 */
fun recipeCardModel(look: Look, intensity: Float): RecipeCardModel {
    val pct = (intensity.coerceIn(0f, 1f) * 100f).roundToInt()
    val summary = look.summary.trim().let { if (it.length > 26) it.take(26) + "…" else it }
    return RecipeCardModel(
        kicker = RecipeCardModel.KICKER,
        title = look.name,
        summary = summary,
        strengthLine = "强度 $pct%",
        parameterLines = buildList {
            add("影调 ${signed(look.contrast)}")
            add("饱和 ${signed(look.saturation)}")
            add("色温 ${signed(look.temperature)}")
            if (look.grain > 0.001f) add("颗粒 ${pctOf(look.grain)}")
            if (look.vignette > 0.001f) add("暗角 ${pctOf(look.vignette)}")
        }.take(RecipeCardModel.MAX_PARAMS),
        footer = RecipeCardModel.FOOTER,
        accentArgb = accentFor(look),
    )
}

/** 带符号的两位小数：`+0.12` / `-0.04`。整数位永远有一位，等宽下才对得齐。 */
private fun signed(v: Float): String {
    val a = abs(v)
    val s = "%.2f".format(a)
    return if (v < 0f) "-$s" else "+$s"
}

/** 百分比（0..1 → "0.18"这种两组小数的口径与参数行一致，不另造一套格式）。 */
private fun pctOf(v: Float): String = signed(v)

/**
 * 强调色：**由配方自身的色温决定**，不是跟界面主题走。
 *
 * 三条分支对应三种配方的性格：暖调（色温 > 0.02）走琥珀、冷调（< -0.02）走青蓝、
 * 其余（中性 / 单色）走相纸灰。这样"卡的颜色"本身就在说这条配方是什么脾气。
 * 单色配方一律走灰：给它刷一个暖色会和"黑白"打架。
 */
fun accentFor(look: Look): Int = when {
    look.monochrome -> 0xFF9AA39E.toInt()
    look.temperature > 0.02f -> 0xFFE8A33D.toInt()
    look.temperature < -0.02f -> 0xFF6F97A8.toInt()
    else -> 0xFFC9C3B4.toInt()
}

/**
 * 卡的**尺寸约定**。三个固定档，不做任意尺寸：卡是分享物，
 * 尺寸飘了在聊天窗口里会被压成不同比例的缩略图。
 *
 * 长边一律 1080 —— 这是"发出去不糊、又不至于几 MB"的那一档。
 */
enum class RecipeCardSize(val width: Int, val height: Int, val label: String) {
    /** 1080×1350（4:5）—— 社交平台竖图最稳的一档。 */
    PORTRAIT_4x5(1080, 1350, "4:5 竖卡"),

    /** 1080×1080（1:1）—— 聊天窗口里不裁切。 */
    SQUARE(1080, 1080, "1:1 方卡"),

    /** 1080×1920（9:16）—— 完整一屏。 */
    STORY_9x16(1080, 1920, "9:16 长卡"),
    ;

    /** 下一档（界面上那颗格子点一下就是它）。 */
    fun next(): RecipeCardSize = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 越界序号归零：偏好里存的是序号，改表之后旧值不能把界面卡死。 */
        fun byOrdinal(i: Int): RecipeCardSize = entries[i.mod(entries.size)]
    }
}

/** 卡的**几何**：所有矩形都在卡坐标系里，纯数据、可断言。 */
@Immutable
data class RecipeCardLayout(
    val width: Int,
    val height: Int,
    /** 卡的外边距（四周留白）。 */
    val margin: Int,
    /** 顶部 kicker 的基线区域。 */
    val kickerRect: RectI,
    /** 配方名区域。 */
    val titleRect: RectI,
    /** 摘要区域。 */
    val summaryRect: RectI,
    /** 一条横向发丝线（配方名与参数之间）。 */
    val ruleRect: RectI,
    /** 强度那一行：卡上最重的一处文字。 */
    val strengthRect: RectI,
    /** 参数块（等宽、左对齐、行距固定）。 */
    val paramsRect: RectI,
    /** 参数块右侧那枚强调色竖条。 */
    val accentRect: RectI,
    /** 落款区域。 */
    val footerRect: RectI,
    /** 卡内框线（1px，四周各一道）。 */
    val frameRect: RectI,
) {
    /** 参数行的行距：由参数块高度均分。 */
    fun paramLineHeight(): Float =
        if (paramsRect.height <= 0) 0f else paramsRect.height.toFloat() / RecipeCardModel.MAX_PARAMS
}

/** 纯 Kotlin 的整数矩形（不想让几何层依赖 `android.graphics.Rect`）。 */
@Immutable
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * 由尺寸算几何。**纯函数**：宽高定下来，所有矩形就唯一确定。
 *
 * 竖排顺序是承重的：kicker（谁做的）→ 名字（是哪条）→ 摘要（什么脾气）→
 * 一道线（换气）→ 强度（多少）→ 参数（怎么做的）→ 落款（谁给的）。
 * 强度排在参数之前，因为它是用户唯一会去调的那一个数。
 */
fun recipeCardLayout(width: Int, height: Int): RecipeCardLayout {
    val w = width.coerceAtLeast(64)
    val h = height.coerceAtLeast(64)
    val unit = minOf(w, h)
    val margin = (unit * 0.085f).roundToInt().coerceAtLeast(4)

    val innerLeft = margin
    val innerRight = w - margin
    val innerW = (innerRight - innerLeft).coerceAtLeast(1)

    val kickerH = (unit * 0.030f).roundToInt().coerceAtLeast(8)
    val titleH = (unit * 0.105f).roundToInt().coerceAtLeast(14)
    val summaryH = (unit * 0.042f).roundToInt().coerceAtLeast(10)
    val ruleH = 2
    val strengthH = (unit * 0.070f).roundToInt().coerceAtLeast(12)
    val footerH = (unit * 0.034f).roundToInt().coerceAtLeast(8)
    val gap = (unit * 0.022f).roundToInt().coerceAtLeast(2)

    var y = margin + gap
    val kicker = RectI(innerLeft, y, innerRight, y + kickerH)
    y += kickerH + gap
    val title = RectI(innerLeft, y, innerRight, y + titleH)
    y += titleH + (gap / 2)
    val summary = RectI(innerLeft, y, innerRight, y + summaryH)
    y += summaryH + gap
    val rule = RectI(innerLeft, y, innerRight, y + ruleH)
    y += ruleH + gap * 2
    val strength = RectI(innerLeft, y, innerRight, y + strengthH)
    y += strengthH + gap

    // 参数块：从当前 y 一直铺到落款上方；强调色竖条在它右侧
    val footerTop = h - margin - gap - footerH
    val paramsBottom = (footerTop - gap).coerceAtLeast(y + 1)
    val params = RectI(innerLeft, y, innerRight, paramsBottom)
    val accentW = (unit * 0.008f).roundToInt().coerceAtLeast(2)
    val accent = RectI(innerRight - accentW, params.top, innerRight, params.bottom)
    val footer = RectI(innerLeft, footerTop, innerRight, footerTop + footerH)

    val frameInset = (margin * 0.42f).roundToInt().coerceAtLeast(1)
    val frame = RectI(frameInset, frameInset, w - frameInset, h - frameInset)

    return RecipeCardLayout(
        width = w,
        height = h,
        margin = margin,
        kickerRect = kicker,
        titleRect = title,
        summaryRect = summary,
        ruleRect = rule,
        strengthRect = strength,
        paramsRect = params,
        accentRect = accent,
        footerRect = footer,
        frameRect = frame,
    )
}

/**
 * 卡的三支颜色（ARGB）。与主题无关：卡是**要送出去的东西**，
 * 它必须在自己被转发到的那个聊天窗口里也读得清，所以固定用暗房纸感的一套。
 */
data class RecipeCardInk(
    /** 纸底：比纯黑暖一档的深灰绿。 */
    val paperArgb: Int = 0xFF141917.toInt(),
    /** 主文字：相纸白。 */
    val inkArgb: Int = 0xFFEDEBE4.toInt(),
    /** 次级文字。 */
    val dimArgb: Int = 0xFF9AA39E.toInt(),
    /** 发丝线与内框。 */
    val lineArgb: Int = 0xFF2F3733.toInt(),
)

/**
 * 卡的**像素层**：只画"材料"，不画字。
 *
 * 纸底、内框线、配方名下面那道横线、右侧强调色竖条 —— 这四件都是纯几何，
 * 用纯像素数组画出来就可以在 JVM 上逐像素断言（"底色对"、"线在哪个 y"、
 * "强调色竖条确实用了配方自己的色"）。文字交给 [renderRecipeCard] 里的 `Canvas`，
 * 因为中文要字体，而字体在 JVM 单测里没有。
 *
 * 这样切的好处是：**卡的像素里最大的一片（纸底 + 框线 + 竖条）是被测过的**，
 * 而不是"整张卡都靠装机看"。
 */
object RecipeCardPixels {

    /**
     * 把材料画进 `px`（行优先，宽 [RecipeCardLayout.width]）。
     *
     * @return 是否改动过像素（布局退化时可能一处都画不上）。
     */
    fun paint(px: IntArray, layout: RecipeCardLayout, ink: RecipeCardInk, accentArgb: Int): Boolean {
        val w = layout.width
        val h = layout.height
        if (w <= 0 || h <= 0 || px.size < w * h) return false
        var touched = false
        // 1. 纸底：整幅铺满。这是卡"是一张纸"的唯一来源。
        java.util.Arrays.fill(px, ink.paperArgb)
        touched = true
        // 2. 内框线：四周各 1px 的细线（印样的框）。
        touched = rectOutline(px, w, h, layout.frameRect, ink.lineArgb) || touched
        // 3. 配方名下面那道横线：从左边距到右边距，1..2px。
        touched = fillRect(px, w, h, layout.ruleRect, ink.lineArgb) || touched
        // 4. 右侧强调色竖条：配方自己的颜色。
        touched = fillRect(px, w, h, layout.accentRect, accentArgb) || touched
        return touched
    }

    /** 实心矩形（自动与画布求交）。 */
    fun fillRect(px: IntArray, w: Int, h: Int, r: RectI, argb: Int): Boolean {
        if (r.isEmpty || w <= 0 || h <= 0) return false
        val l = r.left.coerceIn(0, w)
        val t = r.top.coerceIn(0, h)
        val rr = r.right.coerceIn(0, w)
        val b = r.bottom.coerceIn(0, h)
        if (rr <= l || b <= t) return false
        for (y in t until b) {
            val base = y * w
            for (x in l until rr) px[base + x] = argb
        }
        return true
    }

    /** 空心矩形（四条 1px 边）。 */
    fun rectOutline(px: IntArray, w: Int, h: Int, r: RectI, argb: Int): Boolean {
        if (r.isEmpty || w <= 0 || h <= 0) return false
        val l = r.left.coerceIn(0, w)
        val rr = (r.right - 1).coerceIn(0, w - 1)
        val t = r.top.coerceIn(0, h)
        val b = (r.bottom - 1).coerceIn(0, h - 1)
        if (rr < l || b < t) return false
        for (x in l..rr) {
            px[t * w + x] = argb
            px[b * w + x] = argb
        }
        for (y in t..b) {
            px[y * w + l] = argb
            px[y * w + rr] = argb
        }
        return true
    }
}

/**
 * 渲染一张配方卡。
 *
 * **纯函数**：不读时钟、不取随机、不碰网络、不读照片 —— 输入就是配方 + 强度 + 尺寸。
 * 同一组输入两次调用得到的 `Bitmap` 逐字节相同（`RecipeCardTest` 对像素数组钉住；
 * 只有 `Canvas` 里的中文排版依赖设备字体，同一台机器上它也是确定的）。
 *
 * @param size 卡的像素尺寸，来自 [RecipeCardSize.width] × [RecipeCardSize.height]。
 */
fun renderRecipeCard(
    look: Look,
    intensity: Float,
    size: IntSize,
    ink: RecipeCardInk = RecipeCardInk(),
): Bitmap {
    val w = size.width.coerceAtLeast(64)
    val h = size.height.coerceAtLeast(64)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val model = recipeCardModel(look, intensity)
    val layout = recipeCardLayout(w, h)

    // 材料层走纯像素（与测试同一份实现），再贴回 Bitmap。
    // 这一步看着绕，但它是"卡的底色/框线/竖条被单测覆盖过"的唯一来路。
    val px = IntArray(w * h)
    RecipeCardPixels.paint(px, layout, ink, model.accentArgb)
    out.setPixels(px, 0, w, 0, 0, w, h)

    val canvas = Canvas(out)
    val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    val rect = RectF()

    fun text(s: String, r: RectI, sizePx: Float, color: Int, typeface: Typeface, align: Paint.Align) {
        if (s.isEmpty() || r.isEmpty) return
        paint.typeface = typeface
        paint.textSize = sizePx
        paint.color = color
        paint.textAlign = align
        // 基线 = 矩形垂直居中（ascent/descent 由字体给，不猜）
        val fm = paint.fontMetrics
        val baseline = r.top + (r.height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(s, r.left.toFloat(), baseline, paint)
    }

    val unit = minOf(w, h)
    text(model.kicker, layout.kickerRect, unit * 0.026f, ink.dimArgb, mono, Paint.Align.LEFT)
    text(model.title, layout.titleRect, unit * 0.098f, ink.inkArgb, sans, Paint.Align.LEFT)
    text(model.summary, layout.summaryRect, unit * 0.036f, ink.dimArgb, mono, Paint.Align.LEFT)
    text(model.strengthLine, layout.strengthRect, unit * 0.070f, model.accentArgb, mono, Paint.Align.LEFT)

    // 参数块：等宽、左对齐、固定行距。行距由几何给，不按字体量 —— 这样 4 行永远在
    // paramsRect 里，不会因为字体行高不同而最后一行掉出去。
    val lineH = layout.paramLineHeight()
    model.parameterLines.forEachIndexed { i, line ->
        if (i >= RecipeCardModel.MAX_PARAMS) return@forEachIndexed
        val top = layout.paramsRect.top + (lineH * i).roundToInt()
        val rectI = RectI(
            layout.paramsRect.left,
            top,
            layout.paramsRect.right - layout.accentRect.width - unit / 40,
            top + lineH.roundToInt(),
        )
        text(line, rectI, unit * 0.040f, ink.inkArgb, mono, Paint.Align.LEFT)
    }

    text(model.footer, layout.footerRect, unit * 0.028f, ink.dimArgb, mono, Paint.Align.LEFT)

    // 一道极轻的漏光压在左上角：卡是"印样"，印样有光。像素层不做它（那是渐变，
    // 不透明底上叠一层半透明就够了），所以放在 Canvas 这一层。
    // 颜色由**配方自己的强调色**推出来（16/255 的 alpha），不是第二个字面量：
    // 卡上的光必须跟卡上的强调色是同一支色，否则暖调配方会配上一道冷光。
    val wash = (model.accentArgb and 0x00FFFFFF) or (0x16 shl 24)
    val fade = model.accentArgb and 0x00FFFFFF
    rect.set(0f, 0f, w.toFloat(), h * 0.42f)
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, w * 0.7f, h * 0.42f,
        intArrayOf(wash, fade),
        floatArrayOf(0f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(rect, paint)
    paint.shader = null

    return out
}

/** 卡的文件名。与加工件 `SuiPai_<动作>_<时间戳>` 同族（两个下划线 ⇒ 不算原片）。 */
fun recipeCardFileName(lookId: String, timestamp: Long): String =
    "SuiPai_配方卡_${sanitizeForFileName(lookId)}_$timestamp.png"

/** 配方 id 进文件名之前要清一遍：它可能带冒号（例如 `last:` 这种前缀），而冒号在某些文件系统上非法。 */
internal fun sanitizeForFileName(id: String): String =
    id.map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }
        .joinToString("")
        .take(40)
        .ifBlank { "look" }
