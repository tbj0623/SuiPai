package com.suipai.app.ui.yishun

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.suipai.app.ui.ai.AiSheet
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppBarText
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.common.clipWrite
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.suipai.app.data.DeleteOutcome
import com.suipai.app.data.CustomLook
import com.suipai.app.data.CustomLooks
import com.suipai.app.data.ImageLoader
import com.suipai.app.data.MediaRepository
import com.suipai.app.data.RecipeExif
import com.suipai.app.data.asLook
import com.suipai.app.engine.ColorPipeline
import com.suipai.app.engine.Shift
import com.suipai.app.engine.Shifts
import com.suipai.app.engine.asShifts
import com.suipai.app.engine.recipeWithShifts
import com.suipai.app.engine.saveTo
import com.suipai.app.ui.yishun.pro.ImportRecipeSheet
import com.suipai.app.ui.yishun.pro.MyRecipesSection
import com.suipai.app.ui.yishun.pro.ProCurveSheet
import com.suipai.app.settings.settingsBool
import com.suipai.app.settings.settingsInt
import com.suipai.app.ui.yishun.pro.ProHubSheet
import com.suipai.app.ui.yishun.pro.ProHslSheet
import com.suipai.app.ui.yishun.pro.ProMaskSheet
import com.suipai.app.ui.yishun.pro.ProOps
import com.suipai.app.ui.yishun.pro.RenameRecipeSheet
import com.suipai.app.ui.yishun.pro.SaveRecipeSheet
import com.suipai.app.ui.yishun.pro.buildCustomLook
import com.suipai.app.ui.yishun.pro.exportRecipeText
import com.suipai.app.ui.yishun.pro.toApplied
import com.suipai.core.engine.Look
import com.suipai.core.engine.LookLibrary
import com.suipai.core.engine.YishunRecipe
import com.suipai.app.engine.SceneAnalyzer
import com.suipai.core.engine.Adaptation
import com.suipai.core.engine.SceneKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun YishunScreen(
    uri: Uri,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit,
    onDeleted: () -> Unit = {},
    onNext: (() -> Unit)? = null,
    onPrev: (() -> Unit)? = null,
    /**
     * 「配方卡」出口。参数是 (原图 uri, 配方 id, 强度) —— 三个值都必须由这一屏给出：
     * 强度是用户此刻在滑杆上看到的那一个，宿主重读 prefs 会拿到"上次保存的强度"。
     *
     * 为什么是回调而不是直接 `nav.push`：这一屏不认导航（同 `ui/play/RecipeCardScreen.kt`
     * 文件头那条分工），它只负责说"用户要这张卡的这几个数"，去哪一屏由 `AppNavHost` 决定。
     */
    onRecipeCard: ((uri: String, lookId: String, intensity: Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var selectedId by remember { mutableStateOf(LookLibrary.AUTO_ID) }
    var intensity by remember { mutableFloatStateOf(0.55f) }
    var reason by remember { mutableStateOf("正在分析") }
    var autoLook by remember { mutableStateOf<Look?>(null) }
    var autoIntensity by remember { mutableFloatStateOf(0.55f) }
    var showOriginal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 预设缩略图改成按需渲染：thumbSource 是 128px 底图，thumbs 是有界缓存。
    var thumbSource by remember(uri) { mutableStateOf<Bitmap?>(null) }
    val thumbs = remember(uri) { LookThumbCache() }
    var splitCompare by remember { mutableStateOf(false) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var lastSaved by remember { mutableStateOf<Uri?>(null) }
    var hist by remember { mutableStateOf<IntArray?>(null) }
    var rgbHist by remember { mutableStateOf<Array<IntArray>?>(null) }
    val prefs = remember { context.getSharedPreferences("suipai", android.content.Context.MODE_PRIVATE) }
    /** 设置中心那一层的读写口：成片画质这一项从这一版起读 `cfg_`，老键 `yishun_jpeg` 只做降级兜底。 */
    val settings = remember { com.suipai.app.settings.SettingsStore(prefs) }
    var flags by remember { mutableStateOf(YishunFlags.load(prefs)) }
    /** 0 = 都关着；1 = 筛选 sheet；2 = 更多 sheet；3 = 智能配方 sheet。顶栏因此不必横向滚动。 */
    var sheet by remember { mutableIntStateOf(0) }
    // 成片画质：cfg 存过就按 cfg，没存过才看老键 `yishun_jpeg`（升级迁移），区间仍是 95..100。
    var jpegQ by remember {
        mutableIntStateOf(
            prefs.settingsInt(
                com.suipai.app.settings.SettingsKeys.Yishun.exportQuality,
                prefs.getInt("yishun_jpeg", 95),
            ).coerceIn(95, 100),
        )
    }
    /**
     * 「默认强度」：一条配方**第一次**套上时用多少。
     *
     * null = 用户从没在设置里改过这一项，那就继续用每条配方自己设计好的 `defaultIntensity`。
     * 为什么不做成"永远取设置值"：那些 defaultIntensity 是配方作者定的（有的 0.35、有的 0.8），
     * 一律盖成 60% 等于把几百条配方的设计意图一次抹平 —— 用户没提要求时不该发生这种事。
     * 一旦他在设置里拨过一次，"第一次套上"就走他那个数，这才对得上副标题那句话。
     */
    val cfgFirstIntensity: Float? = remember(prefs) {
        if (prefs.contains(com.suipai.app.settings.SettingsKeys.Yishun.defaultIntensity.id)) {
            prefs.settingsInt(com.suipai.app.settings.SettingsKeys.Yishun.defaultIntensity, 60)
                .coerceIn(0, 100) / 100f
        } else {
            null
        }
    }

    /** 一条配方第一次套上时该给多少强度：设置里拨过就用它，否则用配方自己的。 */
    fun firstIntensity(look: Look): Float =
        (cfgFirstIntensity ?: look.defaultIntensity).coerceIn(0.12f, 0.92f)

    /**
     * 「按住看原图」。关掉之后，按住画面不再回到原片（那条手势是这一屏最容易误触的一处）；
     * 顶栏那颗「看原图」的明确按钮照旧可用 —— 它是一次点击，不是按住。
     */
    val cfgHoldCompare = prefs.settingsBool(
        com.suipai.app.settings.SettingsKeys.Yishun.holdToCompare,
        true,
    )

    /**
     * 「自动识别场景」。关掉之后，打开一张照片**不再跑** `SceneAnalyzer`（那是端侧逐像素分析，
     * 是这一屏最大的一次 CPU 开销），而是直接落在默认配方上。
     *
     * 语义要说清：关的是"替你挑一条相近配方"，不是"端侧分析全部下线" ——
     * 隐私那一页承诺的"不上传"与它无关，关掉只是少做一件本地的事。
     */
    val cfgAutoScene = prefs.settingsBool(
        com.suipai.app.settings.SettingsKeys.Yishun.autoScene,
        true,
    )
    var grainMul by remember { mutableFloatStateOf(prefs.getFloat("yishun_grain", 1f).coerceIn(0f, 1.8f)) }
    var vigMul by remember { mutableFloatStateOf(prefs.getFloat("yishun_vig", 1f).coerceIn(0f, 2f)) }
    var shifts by remember { mutableStateOf(Shifts.load(prefs)) }
    var lastLookId by remember { mutableStateOf(prefs.getString("yishun_last", "") ?: "") }
    var recentIds by remember {
        mutableStateOf(
            (prefs.getString("yishun_recent", "") ?: "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }
    var pinLooks by remember {
        mutableStateOf(prefs.getStringSet("yishun_pins", emptySet())?.toSet().orEmpty())
    }
    var toneFilter by remember { mutableIntStateOf(prefs.getInt("yishun_tone", 0).coerceIn(0, 2)) }
    var activeFilters by remember { mutableStateOf(LookFilter.enabledIn(prefs)) }
    var exportK by remember { mutableIntStateOf(prefs.getInt("yishun_k", 4).let { if (it == 2) 2 else 4 }) }
    var lookQuery by remember { mutableStateOf("") }
    var prevLookId by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    // ── 专业分级（曲线 / HSL 分色 / 局部蒙版）──────────────────────────────
    // 三组算子收在一个不可变值里：预览与导出、配方与"存成我的"都必须拿同一份，
    // 分开存就会出现"预览有曲线、存出去没曲线"（详见 `pro/ProOps.kt` 注释）。
    var pro by remember { mutableStateOf(ProOps()) }
    // 拖动专业面板的滑杆时**不**每个指针事件都重算全图：攒一个 tick 再跑（见下面的 LaunchedEffect）。
    var proTick by remember { mutableIntStateOf(0) }
    // 用户自建配方。读盘在 IO 上，见下方的 LaunchedEffect。
    var recipes by remember { mutableStateOf<List<CustomLook>>(emptyList()) }
    // 长按「我的配方」里某一条之后要改名的目标；null = 没有正在改的。
    var renaming by remember { mutableStateOf<CustomLook?>(null) }
    /**
     * 存配方 / 导入 / 改名 这三个抽屉"收起之后回到哪"。
     *
     * 0 = 顶栏直接进来的（回到主屏）；4 = 从专业抽屉进来的（回专业抽屉）。
     * 不记这个值就会出现：从「专业」里点存配方，存完人被扔回主屏，
     * 还得再点一次「专业」继续调 —— 而用户的心智是"我还在同一个面板里"。
     */
    var proReturn by remember { mutableIntStateOf(0) }
    var retryTick by remember { mutableIntStateOf(0) }
    var detectedRecipe by remember { mutableStateOf<YishunRecipe?>(null) }
    var recipeDismissed by remember { mutableStateOf(false) }
    // 从配方还原时记住它记的成片长边；换照片/手动改画质要清掉（见 exportK 的 onValueChange）。
    var recipeExportEdge by remember(uri) { mutableStateOf<Int?>(null) }
    /**
     * 成片长边。预览和成片必须用同一个值喂给颗粒（见 EngineParams.grainEdge），
     * 否则预览永远是 0.7× 细颗粒、成片永远是 1.0× 混合颗粒。
     * 从配方还原时用配方记的那一档，但不超过本机能力。
     */
    fun exportEdgeNow(): Int {
        val fromRecipe = recipeExportEdge
        return when {
            fromRecipe != null -> minOf(fromRecipe, ColorPipeline.exportEdge())
            exportK == 2 -> 2048
            else -> ColorPipeline.exportEdge()
        }
    }

    /**
     * 位图袋**不按 uri 重建**。按 uri 重建时，换照片那一刻 `DisposableEffect(uri)` 的
     * onDispose 会先跑，而 `original`/`preview` 这两个状态要到几百毫秒后才被新图覆盖 ——
     * 于是画面正在 draw 的那张位图被回收，直接 FATAL：
     * `Canvas: trying to use a recycled bitmap`（2026-09-21 在一瞬里连翻两张实测到）。
     * 现在：离开一瞬才整体回收；换照片时旧袋等这一轮的新图全部挂上状态之后再收。
     */
    var bag by remember { mutableStateOf(BitmapBag()) }
    DisposableEffect(Unit) {
        onDispose {
            bag.recycle()
            thumbs.dispose()
        }
    }

    LaunchedEffect(uri, retryTick) {
        lastSaved = null
        message = null
        loadError = null
        detectedRecipe = null
        recipeDismissed = false
        busy = true
        val stale = bag
        val fresh = BitmapBag()
        bag = fresh
        try {
        val (bitmap, exif) = ImageLoader.decode(context, uri, maxEdge = ColorPipeline.previewEdge())
        original = bitmap
        fresh.original = bitmap
        detectedRecipe = RecipeExif.readRecipe(context, uri, exif)
        // 关掉「自动识别场景」时这一步整个跳过：省下的正是这一屏最贵的那一次端侧分析。
        // `adaptation` 拿不到时的兜底是一个"看不出被推荐过"的中性结果 —— 见下面的用法：
        // 落到 `LookLibrary.daylight` + 默认强度，reason 也直接说"没有自动识别"。
        val adaptation = if (cfgAutoScene) {
            withContext(Dispatchers.Default) { SceneAnalyzer.analyze(bitmap, exif) }
        } else {
            // `scene` 这一格在关闭时没有"识别结果"可言，给 DAYLIGHT（中性那一档），
            // 不另编一个新场景名 —— 编一个会让别处的 `when (scene)` 多一个必须处理的分支。
            Adaptation(
                scene = SceneKind.DAYLIGHT,
                look = LookLibrary.daylight,
                intensity = 0.55f,
                reason = "已关掉自动识别场景",
            )
        }
        autoLook = adaptation.look
        autoIntensity = adaptation.intensity
        val reuse = when {
            flags.lockLook && selectedId != LookLibrary.AUTO_ID ->
                LookLibrary.byId(selectedId) ?: LookLibrary.byId(lastLookId)
            flags.preferLast -> LookLibrary.byId(lastLookId)
            else -> null
        }
        if (reuse != null) {
            selectedId = reuse.id
            if (!flags.lockInt) {
                // 这一条配方的键存过就用它（用户上次调过的值），否则走"第一次套上"的口径：
                // 设置里拨过默认强度就用设置值，没拨过就用配方自己的设计值。
                intensity = if (prefs.contains("yishun_int_${reuse.id}")) {
                    prefs.getFloat("yishun_int_${reuse.id}", reuse.defaultIntensity)
                } else {
                    firstIntensity(reuse)
                }
            }
            reason = "沿用上次 · ${reuse.name}"
        } else {
            selectedId = LookLibrary.AUTO_ID
            if (!flags.lockInt) intensity = adaptation.intensity
            reason = "智能适配 · ${adaptation.reason}"
        }
        val startLook = reuse ?: adaptation.look
        val startInt = intensity
        preview = ColorPipeline.preview(
            bitmap,
            startLook,
            startInt,
            grainEdge = exportEdgeNow(),
            grainMul = grainMul,
            vignetteMul = vigMul,
            shifts = shifts,
            curves = pro.curves,
            hsl = pro.bands,
            masks = pro.masks,
        )
        fresh.preview = preview
        // 只留一张 128px 底图；每张缩略图在 chip 组合时按需用它渲染，不再 5446 张全量预生成。
        val mini = withContext(Dispatchers.Default) { miniOf(bitmap, THUMB_EDGE) }
        fresh.mini = mini
        thumbSource = mini
        // 到这一行，状态里挂着的三张图全是新的，上一张才允许回收。
        stale.recycle()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 半途而废：这一袋的位图可能已经挂在状态上，回收就会崩；留着让下一轮接手。
            throw e
        } catch (t: Throwable) {
            Log.w("YishunScreen", "解码或分析失败", t)
            loadError = "无法读取这张照片"
            original = null
            preview = null
            thumbSource = null
            fresh.original = null
            fresh.preview = null
            stale.recycle()
        } finally {
            busy = false
        }
    }

    LaunchedEffect(preview, flags.histRgb) {
        val bmp = preview
        if (bmp == null || bmp.isRecycled) {
            hist = null
            rgbHist = null
            return@LaunchedEffect
        }
        if (flags.histRgb) {
            rgbHist = withContext(Dispatchers.Default) { ColorPipeline.rgbHistogram(bmp) }
            hist = null
        } else {
            hist = withContext(Dispatchers.Default) { ColorPipeline.lumaHistogram(bmp) }
            rgbHist = null
        }
    }

    fun toggleFilter(f: LookFilter) {
        val next = if (f in activeFilters) activeFilters - f else activeFilters + f
        activeFilters = next
        prefs.edit().putBoolean(f.prefKey, f in next).apply()
    }

    fun currentLook(): Look {
        if (selectedId == LookLibrary.AUTO_ID) return autoLook ?: LookLibrary.daylight
        return LookLibrary.byId(selectedId) ?: LookLibrary.daylight
    }

    /**
     * 强度的唯一落盘口。原来这里有三条不一致的路：滑杆会按配方存
     * （`yishun_int_<配方号>`），而 ±、轻/中/重 只写一个**全项目没人读**的裸键 `yishun_int`，
     * 「还原」干脆什么都不存。结果就是：按完「重」切走再切回来，强度悄悄回到默认；
     * 按完「还原」切走再回来，被抹掉的那个值又回来了。
     * 现在六条路都走这里——存进"这个配方"的键，切回来才拿得到自己刚调的那个值。
     */
    fun commitIntensity(value: Float) {
        intensity = value
        if (selectedId != LookLibrary.AUTO_ID) {
            prefs.edit().putFloat("yishun_int_$selectedId", value).apply()
        }
    }

    var recomputeGen by remember { mutableIntStateOf(0) }
    var sliderTick by remember { mutableIntStateOf(0) }
    fun recompute(look: Look, amount: Float, showBusy: Boolean = true, proOps: ProOps = pro) {
        val src = original ?: return
        val gen = recomputeGen + 1
        recomputeGen = gen
        if (showBusy) busy = true
        scope.launch {
            val next = ColorPipeline.preview(
                src,
                look,
                amount,
                grainEdge = exportEdgeNow(),
                grainMul = grainMul,
                vignetteMul = vigMul,
                shifts = shifts,
                curves = proOps.curves,
                hsl = proOps.bands,
                masks = proOps.masks,
            )
            if (gen == recomputeGen) {
                val old = preview
                preview = next
                bag.preview = next
                if (old != null && old !== next && old !== original && !old.isRecycled) old.recycle()
                busy = false
            }
        }
    }

    fun cycleLook(delta: Int) {
        val ids = LookLibrary.idOrder
        val i = ids.indexOf(selectedId).let { if (it < 0) 0 else it }
        val nextId = ids[(i + delta).mod(ids.size)]
        if (nextId != selectedId) prevLookId = selectedId
        selectedId = nextId
        val applied = if (nextId == LookLibrary.AUTO_ID) {
            autoLook ?: LookLibrary.daylight
        } else {
            LookLibrary.byId(nextId) ?: LookLibrary.daylight
        }
        intensity = if (nextId == LookLibrary.AUTO_ID) {
            autoIntensity
        } else if (prefs.contains("yishun_int_$nextId")) {
            prefs.getFloat("yishun_int_$nextId", applied.defaultIntensity)
        } else {
            firstIntensity(applied)
        }
        grainMul = 1f
        vigMul = 1f
        shifts = Shifts.None
        if (nextId != LookLibrary.AUTO_ID) {
            lastLookId = nextId
            recentIds = (listOf(nextId) + recentIds.filter { it != nextId }).take(6)
            prefs.edit()
                .putString("yishun_last", nextId)
                .putString("yishun_recent", recentIds.joinToString(","))
                .apply()
        }
        reason = if (nextId == LookLibrary.AUTO_ID) {
            "智能适配 · ${applied.name}"
        } else {
            applied.summary
        }
        recompute(applied, intensity)
    }

    fun applyRecipe(recipe: YishunRecipe) {
        // 弹层已经收了才走到这里，原来 `?: return` 之后什么都没留下：
        // 用户点了「用这个配方」，画面没变，也没人说为什么。
        val look = LookLibrary.byId(recipe.lookId) ?: run {
            recipeDismissed = true
            message = "这个配方指的色调不在库里，什么都没改"
            return
        }
        if (look.id != selectedId) prevLookId = selectedId
        selectedId = look.id
        lastLookId = look.id
        recentIds = (listOf(look.id) + recentIds.filter { it != look.id }).take(6)
        prefs.edit()
            .putString("yishun_last", look.id)
            .putString("yishun_recent", recentIds.joinToString(","))
            .apply()
        intensity = recipe.intensity
        grainMul = recipe.grainMul
        vigMul = recipe.vignetteMul
        shifts = recipe.asShifts()
        recipeExportEdge = recipe.exportEdge
        // v3 配方里的曲线/分色/蒙版一起还原；v2 配方这三项是 null，于是得到 ProOps.NONE ——
        // 这一步必须无条件做：上一张图残留的曲线不清掉，就会出现"配方说没曲线、画面还带着"。
        pro = ProOps.of(recipe)
        reason = look.summary
        recipeDismissed = true
        recompute(look, intensity)
    }

    /** 应用一条用户自建配方：全部参数（含专业三组）一起换掉。 */
    fun applyCustom(look: CustomLook) {
        val applied = look.toApplied() ?: run {
            message = "「${look.name}」的基础配方不在库里，什么都没改"
            return
        }
        val baseLook = LookLibrary.byId(applied.lookId) ?: return
        if (applied.lookId != selectedId) prevLookId = selectedId
        selectedId = applied.lookId
        lastLookId = applied.lookId
        recentIds = (listOf(applied.lookId) + recentIds.filter { it != applied.lookId }).take(6)
        prefs.edit()
            .putString("yishun_last", applied.lookId)
            .putString("yishun_recent", recentIds.joinToString(","))
            .apply()
        intensity = applied.intensity
        shifts = applied.shifts
        pro = applied.pro
        grainMul = 1f
        vigMul = 1f
        reason = "我的配方 · ${applied.name}"
        message = "已应用「${applied.name}」"
        recompute(baseLook, intensity)
    }

    LaunchedEffect(flags.blink, splitCompare, uri) {
        if (!flags.blink || splitCompare) {
            if (!flags.blink) showOriginal = false
            return@LaunchedEffect
        }
        while (true) {
            showOriginal = true
            delay(480)
            showOriginal = false
            delay(720)
        }
    }

    LaunchedEffect(sliderTick) {
        if (sliderTick == 0 || original == null) return@LaunchedEffect
        delay(160)
        recompute(currentLook(), intensity, showBusy = false)
    }

    var grainTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(grainTick) {
        if (grainTick == 0 || original == null) return@LaunchedEffect
        delay(160)
        recompute(currentLook(), intensity, showBusy = false)
    }

    /**
     * 专业面板的滑杆节流：**攒 120 ms 再重算一次**，不是每个指针事件都跑全图。
     *
     * 底座是实测/预算：预览 1600 长边 ≈ 12 万像素 → 单线程逐像素约 25 ms、8 线程行带约 4 ms
     * （`ColorCore.processBase` 的每条指令都是 IEEE single 乘加，没有 pow/atan2；
     * 预算口径见 `docs/engine/COLOR-PRO.md` §5）。但整条链路不止调色：`ColorPipeline.apply`
     * 还要 `getPixels`/`setPixels`（2 × 1.9 MB 拷贝）与 `Bitmap.createScaledBitmap`。
     * 一帧 16 ms 内做不完，所以节流到 ~7 Hz 已经比手指快。
     *
     * 为什么用「tick + delay」而不是 `snapshotFlow{}.debounce()`：这个文件里另外两条滑杆
     * （[sliderTick] / [grainTick]）已经用同一个形状，三处一致才不会有人只改一处。
     */
    LaunchedEffect(proTick) {
        if (proTick == 0 || original == null) return@LaunchedEffect
        delay(120)
        recompute(currentLook(), intensity, showBusy = false)
    }

    /** 自建配方列表：读盘一次（IO），保存/删除后重新读。`recipeTick` 是它的刷新键。 */
    var recipeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(recipeTick) {
        recipes = withContext(Dispatchers.IO) { CustomLooks(context).all() }
    }
    // 换照片时不重置专业算子：它们是**这一轮的调色意图**，跟着图走会让用户每张都重调。
    // 但换照片本身会触发一次重算（上面的 LaunchedEffect(uri)），那条会用当前 pro 值。

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppBarRow(Modifier.statusBarsPadding()) {
            AppBarCell("返回", on = false, onClick = onClose)
            AppBarText(
                "一瞬 · ${if (selectedId == LookLibrary.AUTO_ID) "智能" else currentLook().name}",
                layout = Modifier.weight(1f),
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
            )
            AppBarCell(
                label = "AI",
                on = sheet == 3,
                modifier = Modifier.testTag("yishun_ai"),
                onClick = { sheet = 3 },
            )
            AppBarCell(
                label = "微调",
                on = flags.showTune,
                modifier = Modifier.testTag("yishun_tune"),
                onClick = { flags = flags.toggle(YishunFlag.SHOW_TUNE).also { it.saveTo(prefs) } },
            )
            AppBarCell(
                label = "专业",
                // 存配方（8）有自己的一格，这里**不能**把它也算成"专业亮着"——
                // 两颗同时亮琥珀框时用户判断不出哪颗是当前（`FlagTableGuardTest` 记过同一类账）。
                on = sheet in 4..7 || sheet in 9..10,
                badge = pro.activeCount,
                modifier = Modifier.testTag("yishun_pro"),
                onClick = { proReturn = 0; sheet = 4 },
            )
            // 「存配方」独立一格而不是只藏在专业抽屉里：存配方是这一屏**产出**的动作，
            // 和「保存成片」同一量级。抽屉里那份（专业 → 存配方）保留给"刚调完顺手存"的路径。
            AppBarCell(
                label = "存配方",
                on = sheet == 8,
                modifier = Modifier.testTag("yishun_save_recipe"),
                onClick = { proReturn = 0; sheet = 8 },
            )
            AppBarCell(
                label = "筛选",
                on = activeFilters.isNotEmpty() || toneFilter != 0,
                badge = activeFilters.size + (if (toneFilter != 0) 1 else 0),
                onClick = { sheet = 1 },
            )
            AppBarCell(label = "更多", on = sheet == 2, onClick = { sheet = 2 })
            if (lastSaved != null) {
                AppBarPrimary(label = "完成", onClick = onClose, modifier = Modifier.testTag("yishun_save"))
            } else {
            AppBarPrimary(
                label = if (saving) "保存中" else "保存",
                enabled = !saving && preview != null,
                modifier = Modifier.testTag("yishun_save"),
                onClick = {
                    val look = currentLook()
                    // v2 的 14 个字段照旧由 recipeWithShifts 拼，专业三组再叠上去：
                    // 中性时 RecipePro.withPro 会把它们写成 null，配方仍是合法 v2 形状。
                    val recipe = pro.applyTo(
                        recipeWithShifts(
                            lookId = look.id,
                            intensity = intensity,
                            grainMul = grainMul,
                            vignetteMul = vigMul,
                            exportEdge = exportEdgeNow(),
                            shifts = shifts,
                        ),
                    )
                    saving = true
                    scope.launch {
                        try {
                        val out = withContext(Dispatchers.Default) {
                            val edge = exportEdgeNow()
                            val full = ImageLoader.decode(context, uri, maxEdge = edge).first
                            ColorPipeline.apply(
                                full,
                                look,
                                intensity,
                                maxEdge = edge,
                                grainEdge = edge,
                                grainMul = grainMul,
                                vignetteMul = vigMul,
                                shifts = shifts,
                                curves = pro.curves,
                                hsl = pro.bands,
                                masks = pro.masks,
                            )
                        }
                        val saved = MediaRepository(context).saveBitmap(
                            out,
                            "SuiPai_一瞬_${look.name}_${(intensity * 100).roundToInt()}_${System.currentTimeMillis()}.${if (flags.savePng) "png" else "jpg"}",
                            quality = jpegQ,
                            sourceUri = uri,
                            png = flags.savePng,
                        )
                        if (out !== original) out.recycle()
                        saving = false
                        if (saved != null) {
                            RecipeExif.writeRecipe(context, saved, recipe)
                            var compareSaved = false
                            if (flags.alsoCompare) {
                                val srcBmp = original
                                val done = preview
                                if (srcBmp != null && done != null) {
                                    val coll = withContext(Dispatchers.Default) {
                                        compareCollage(srcBmp, done, flags.splitVert, look.name)
                                    }
                                    // 这张的结果以前是丢掉的，消息却照说"两片都存了"
                                    compareSaved = MediaRepository(context).saveBitmap(
                                        coll,
                                        "SuiPai_对比_${look.name}_${System.currentTimeMillis()}.${if (flags.savePng) "png" else "jpg"}",
                                        quality = jpegQ,
                                        sourceUri = uri,
                                        png = flags.savePng,
                                    ) != null
                                    if (coll !== srcBmp && coll !== done && !coll.isRecycled) coll.recycle()
                                }
                            }
                            onSaved(saved)
                            if (flags.saveNext && onNext != null) {
                                onNext()
                            } else {
                                lastSaved = saved
                                message = savedNote(flags.alsoCompare, compareSaved)
                                if (flags.shareAfter) {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = if (flags.savePng) "image/png" else "image/jpeg"
                                        putExtra(Intent.EXTRA_STREAM, saved)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    sendOrTell(context, Intent.createChooser(send, "分享"), "分享") {
                                        message = it
                                    }
                                }
                            }
                        } else {
                            message = "保存失败"
                        }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w("YishunScreen", "导出失败", t)
                            message = "保存失败"
                        } finally {
                            saving = false
                        }
                    }
                },
            )
            }
        }


        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (splitCompare || flags.blink) Modifier else Modifier.pointerInput(preview, original, cfgHoldCompare) {
                        detectTapGestures(
                            onPress = {
                                // 「按住看原图」关掉之后这一下什么都不做，但**手势本身还在**：
                                // 同一个 pointerInput 里还挂着双击（把强度顶到 100%），
                                // 整块摘掉会连带把双击也废掉 —— 那就不是"关一项设置"了。
                                if (cfgHoldCompare) {
                                    showOriginal = true
                                    tryAwaitRelease()
                                    showOriginal = false
                                }
                            },
                            onDoubleTap = {
                                intensity = if (intensity > 0.92f) {
                                    if (selectedId == LookLibrary.AUTO_ID) autoIntensity
                                    else currentLook().defaultIntensity
                                } else {
                                    1f
                                }
                                recompute(currentLook(), intensity)
                            },
                        )
                    },
                )
                .then(
                    if (splitCompare) Modifier else Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, drag ->
                            intensity = (intensity - drag / 900f).coerceIn(0.12f, 0.92f)
                            sliderTick++
                        }
                    },
                )
                .then(
                    if (splitCompare) Modifier else Modifier.pointerInput(selectedId, autoLook) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                when {
                                    total < -88f -> cycleLook(1)
                                    total > 88f -> cycleLook(-1)
                                }
                            },
                            onHorizontalDrag = { change, drag ->
                                change.consume()
                                total += drag
                            },
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val processed = preview
            val source = original
            if (splitCompare && processed != null && source != null) {
                SplitCompare(
                    original = source,
                    processed = processed,
                    flip = flags.splitFlip,
                    split = split,
                    onSplitChange = { split = it },
                    vertical = flags.splitVert,
                    lineRed = flags.splitRed,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            } else {
                val shown = if (showOriginal) original else preview
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = if (flags.previewFill) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            }
            if (busy) CircularProgressIndicator()
            val rec = detectedRecipe
            if (rec != null && !recipeDismissed && loadError == null) {
                val recLook = LookLibrary.byId(rec.lookId)
                // 这条横幅原来是一颗 `Row`：字级 2.0 时那句"检测到一瞬配方 · …"把两颗格子
                // 挤成 53 px 宽（「忽略」只剩一个字），换成 `FlowRow` 装不下就换行。
                FlowRow(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AppBarText(
                        "检测到一瞬配方 · ${recLook?.name ?: "未知方案"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AppBarCell(
                        label = "还原配方",
                        on = false,
                        enabled = recLook != null,
                        onClick = { applyRecipe(rec) },
                    )
                    AppBarCell(
                        label = "忽略",
                        on = false,
                        onClick = { recipeDismissed = true },
                    )
                }
            }
            val err = loadError
            if (err != null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "⚠",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(err, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppBarPrimary(label = "重试", onClick = { retryTick++ })
                        AppBarCell(
                            label = "关闭",
                            on = false,
                            onClick = onClose,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            val bars = hist
            val rgbBars = rgbHist
            if (flags.showHist && (bars != null || rgbBars != null)) {
                LumaHist(
                    values = bars ?: IntArray(0),
                    rgb = rgbBars,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable {
                            flags = flags.copy(showHist = false).also { it.saveTo(prefs) }
                            prefs.edit().putBoolean("yishun_hist", false).apply()
                        },
                )
                AppBarCell(
                    label = if (flags.histRgb) "RGB" else "亮度",
                    on = flags.histRgb,
                    onClick = {
                        flags = flags.toggle(YishunFlag.HIST_RGB).also { it.saveTo(prefs) }
                    },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            } else if (!flags.showHist) {
                AppBarCell(
                    label = "直方图",
                    on = false,
                    onClick = {
                        flags = flags.copy(showHist = true).also { it.saveTo(prefs) }
                        prefs.edit().putBoolean("yishun_hist", true).apply()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        Text(
            // 结论落在这一行：预览下面那行小字是这一页**永远看得见**的位置。
            // 原来 `message` 挂在整页 Column 的最末尾 —— 微调面板一开就被挤出屏幕，
            // 装机实测两片都落库了而屏上一个字都没有，连「保存失败」也是看不见的。
            message ?: when {
                splitCompare -> "$reason  · 拖分割线对比 · ${if (flags.splitVert) "上下" else "左右"}"
                flags.blink -> "$reason  · 闪比原片/成片"
                showOriginal -> "原图"
                else -> "$reason  · 按住对比 · 双击满强度 · 左右滑换配方 · 上下滑强度"
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            color = if (message == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )

        val lastLook = LookLibrary.byId(lastLookId)   // 一次索引查表，芯片那一列也要用它
        // 这一串**必须 remember**：它是对 5446 条配方的一次全表过滤，再加一次搜索过滤与排序，
        // 而它所在的这一层作用域在**拖动强度滑杆时每个指针事件都要重组**（`intensity`、`preview`、
        // `showOriginal`、闪比那两条循环都在同一层读）。
        // 桌面 JVM warm 实测：基线 0.85 ms；开着 23 条筛选 4.2~5.4 ms；再叠排序 +1.8 ms、搜索 +4.3 ms
        // —— 最坏 ~12 ms，而 ART 上按 2~5 倍算就是 25~40 ms：一帧预算被滑杆的**每一个事件**吃掉。
        // 下面这些键全是 `remember { mutableStateOf(...) }` 里的稳定引用，不会自己失效。
        val chips = remember(activeFilters, toneFilter, flags.onlyPins, pinLooks,
            flags.onlyRecent, recentIds, lastLookId, lookQuery, flags.chipSort, flags.chipRev,
            recipes
        ) {
            buildList {
                add(Look(LookLibrary.AUTO_ID, "智能", "按这张照片选配方并微调"))
                if (lastLook != null) {
                    add(Look("last:${lastLook.id}", "上次", lastLook.summary))
                }
                recentIds.mapNotNull { LookLibrary.byId(it) }
                    .filter { it.id != lastLookId }
                    .take(5)
                    .forEach { add(Look("near:${it.id}", "近${it.name}", it.summary)) }
                // 用户自建配方排在内置库**前面**：它们是"我自己的那几条"，
                // 埋进 5446 条里等于没有（`asLook()` 会把名字与「我的配方 · 基于…」摘要带过来）。
                addAll(recipes.map { it.asLook() })
                addAll(
                    LookLibrary.all.filter { look ->
                        val pinOk = !flags.onlyPins || pinLooks.isEmpty() || look.id in pinLooks
                        val recentOk = !flags.onlyRecent || recentIds.isEmpty() || look.id in recentIds
                        val toneOk = when (toneFilter) {
                            1 -> look.temperature > 0.02f
                            2 -> look.temperature < -0.02f
                            else -> true
                        }
                        look.matchesFilters(activeFilters) && toneOk
                    },
                )
            }.let { list ->
                val q = lookQuery.trim()
                val filtered = if (q.isEmpty()) list
                else list.filter {
                    it.id == LookLibrary.AUTO_ID ||
                        it.id.startsWith("last:") ||
                        it.id.startsWith(CustomLook.ID_PREFIX) ||
                        it.name.contains(q, ignoreCase = true) ||
                        it.summary.contains(q, ignoreCase = true)
                }
                if (!flags.chipSort) filtered
                else {
                    val head = filtered.filter { it.id == LookLibrary.AUTO_ID || it.id.startsWith("last:") }
                    val rest = filtered.filter { it.id != LookLibrary.AUTO_ID && !it.id.startsWith("last:") }
                        .let { body -> if (flags.chipRev) body.sortedByDescending { it.name } else body.sortedBy { it.name } }
                    head + rest
                }
            }
        }

        OutlinedTextField(
            value = lookQuery,
            onValueChange = { lookQuery = it },
            modifier = Modifier
                .testTag("yishun_search")
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            singleLine = true,
            placeholder = {
                    // 单行输入框的提示语也必须单行：placeholder 不受 `singleLine` 管，
                    // 字级 2.0 + 窄屏时它自己折行，把输入框撑高、把网格顶出屏幕
                    // （装机量到 720×1600 / 字级 2.0：搜索框 528 px 高，时间线一颗不剩）。
                    Text("搜配方名", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            trailingIcon = {
                if (lookQuery.isNotBlank()) {
                    AppBarCell(
                        label = "清除",
                        on = false,
                        onClick = { lookQuery = "" },
                    )
                }
            },
        )
        LazyRow(
            modifier = Modifier.testTag("yishun_look_row"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chips, key = { it.id }) { look ->
                val selected = selectedId == look.id
                Column(
                    Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(2.dp),
                        )
                        .combinedClickable(
                            onClickLabel = "用这个配方",
                            onLongClickLabel = if (look.id.removePrefix("last:")
                                .removePrefix("near:") in pinLooks)
                                "取消钉住这个配方"
                            else "把这个配方钉住",
                            onClick = {
                            val realId = look.id.removePrefix("last:").removePrefix("near:")
                            // 自建配方：点它等于把整条参数（含专业三组）一次性套上，
                            // 不能只换 baseLook —— 那样用户会看到"选了我的配方却只变了预设"。
                            if (realId.startsWith(CustomLook.ID_PREFIX)) {
                                val custom = recipes.firstOrNull { it.id == realId }
                                if (custom != null) {
                                    applyCustom(custom)
                                    return@combinedClickable
                                }
                            }
                            if (realId != selectedId) prevLookId = selectedId
                            selectedId = realId
                            val applied = when {
                                realId == LookLibrary.AUTO_ID -> autoLook ?: LookLibrary.daylight
                                look.id.startsWith("last:") || look.id.startsWith("near:") ->
                                    LookLibrary.byId(realId) ?: LookLibrary.daylight
                                else -> look
                            }
                            if (realId != LookLibrary.AUTO_ID) {
                                lastLookId = realId
                                recentIds = (listOf(realId) + recentIds.filter { it != realId }).take(6)
                                prefs.edit()
                                    .putString("yishun_last", realId)
                                    .putString("yishun_recent", recentIds.joinToString(","))
                                    .apply()
                            }
                            intensity = if (realId == LookLibrary.AUTO_ID) {
                                autoIntensity
                            } else {
                                prefs.getFloat("yishun_int_$realId", applied.defaultIntensity)
                            }
                            grainMul = 1f
                            vigMul = 1f
                            shifts = Shifts.None
                            reason = if (realId == LookLibrary.AUTO_ID) {
                                "智能适配 · ${applied.name}"
                            } else if (look.id.startsWith("last:")) {
                                "上次 · ${applied.name}"
                            } else if (look.id.startsWith("near:")) {
                                "最近 · ${applied.name}"
                            } else look.summary
                            recompute(applied, intensity)
                            },
                            onLongClick = {
                                val realId = look.id.removePrefix("last:").removePrefix("near:")
                                if (realId == LookLibrary.AUTO_ID) return@combinedClickable
                                // 自建配方不参与「钉住」：它们本来就在列表最前面，
                                // 钉住反而会被内置筛选（只看钉住/只看最近）之外的逻辑重复算一遍。
                                if (realId.startsWith(CustomLook.ID_PREFIX)) return@combinedClickable
                                val next = pinLooks.toMutableSet()
                                if (realId in next) next.remove(realId) else next.add(realId)
                                pinLooks = next
                                prefs.edit().putStringSet("yishun_pins", next).apply()
                            },
                        )
                        .padding(8.dp),
                ) {
                    val thumbLook = when {
                        look.id == LookLibrary.AUTO_ID -> autoLook
                        look.id.startsWith("last:") -> lastLook
                        look.id.startsWith("near:") ->
                            LookLibrary.byId(look.id.removePrefix("near:"))
                        else -> look
                    }
                    var thumbState by remember(look.id, thumbLook, thumbSource) {
                        mutableStateOf<Bitmap?>(null)
                    }
                    LaunchedEffect(look.id, thumbLook, thumbSource) {
                        thumbState = thumbs.render(thumbLook, thumbSource)
                    }
                    val thumb = thumbState
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = look.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                    if (flags.chipNames) {
                    Text(
                        if (look.id != LookLibrary.AUTO_ID &&
                            !look.id.startsWith("last:") &&
                            look.id in pinLooks
                        ) {
                            "钉 ${look.name}"
                        } else {
                            look.name
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    }
                }
            }
        }

        // 标题与滑杆的读屏名同出一处：分两处各算一遍迟早分叉 —— 格子标签上的尺寸就这么分过叉。
        // 十根滑杆在 TalkBack 里原来都是"无名滑动条"，看得见的那行字不会跟着焦点念。
        val intensityCaption = "强度 ${(intensity * 100).roundToInt()}%"
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                intensityCaption,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            AppBarCell(
                label = "-",
                on = false,
                onClick = {
                    commitIntensity((intensity - 0.05f).coerceIn(0.12f, 0.92f))
                    recompute(currentLook(), intensity)
                },
            )
            AppBarCell(
                label = "+",
                on = false,
                onClick = {
                    commitIntensity((intensity + 0.05f).coerceIn(0.12f, 0.92f))
                    recompute(currentLook(), intensity)
                },
            )
            AppBarCell(
                label = "还原",
                on = false,
                modifier = Modifier.testTag("yishun_reset"),
                onClick = {
                    commitIntensity(
                        if (selectedId == LookLibrary.AUTO_ID) autoIntensity
                        else currentLook().defaultIntensity
                    )
                    grainMul = 1f
                    vigMul = 1f
                    shifts = Shifts.None
                    recompute(currentLook(), intensity)
                },
            )
            AppBarCell(
                label = "随机",
                on = false,
                onClick = {
                    // 复用上面那一列已经筛好的 `chips`：这里以前**再跑一遍 5446 条的全表过滤**，
                    // 而筛选条件与那一列一字不差 —— 点一次"随机"就白算一次（点击延迟的一部分）。
                    val pool = chips
                        .filter { look ->
                            look.id != LookLibrary.AUTO_ID &&
                                !look.id.startsWith("last:") && !look.id.startsWith("near:")
                        }
                        .ifEmpty { LookLibrary.all }
                    val pick = pool.random()
                    if (pick.id != selectedId) prevLookId = selectedId
                    selectedId = pick.id
                    lastLookId = pick.id
                    recentIds = (listOf(pick.id) + recentIds.filter { it != pick.id }).take(6)
                    prefs.edit()
                        .putString("yishun_last", pick.id)
                        .putString("yishun_recent", recentIds.joinToString(","))
                        .apply()
                    intensity = if (prefs.contains("yishun_int_${pick.id}")) {
                        prefs.getFloat("yishun_int_${pick.id}", pick.defaultIntensity)
                    } else {
                        firstIntensity(pick)
                    }
                    grainMul = 1f
                    vigMul = 1f
                    shifts = Shifts.None
                    reason = "随机 · ${pick.name}"
                    recompute(pick, intensity)
                },
            )
        }
        Slider(
            value = intensity,
            onValueChange = {
                intensity = it
                sliderTick++
            },
            onValueChangeFinished = {
                commitIntensity(intensity)
                recompute(currentLook(), intensity)
            },
            // caller modifier 与 sliderSemantics 落在同一个节点（material3 1.3.1 Slider.kt:686-695），
            // 所以这一句能落进语义树；不给，焦点到那里只报"滑动条"。
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = intensityCaption },
        )
        if (flags.showTune) {
        val grainCaption = "颗粒 ${(grainMul * 100).roundToInt()}%"
        Text(
            grainCaption,
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = grainMul,
            onValueChange = {
                grainMul = it
                grainTick++
            },
            valueRange = 0f..1.8f,
            onValueChangeFinished = {
                prefs.edit().putFloat("yishun_grain", grainMul).apply()
                recompute(currentLook(), intensity)
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = grainCaption },
        )
        val vigCaption = "暗角 ${(vigMul * 100).roundToInt()}%"
        Text(
            vigCaption,
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = vigMul,
            onValueChange = {
                vigMul = it
                grainTick++
            },
            valueRange = 0f..1.8f,
            onValueChangeFinished = {
                prefs.edit().putFloat("yishun_vig", vigMul).apply()
                recompute(currentLook(), intensity)
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = vigCaption },
        )
        // 7 条滑杆完全同构：整段就是下面这个 forEach。以后加一项微调只改 YishunShifts.kt。
        Shift.entries.forEach { s ->
            val shiftCaption = s.caption(s.get(shifts))
            Text(
                shiftCaption,
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = s.get(shifts),
                onValueChange = {
                    shifts = s.with(shifts, it)
                    grainTick++
                },
                valueRange = s.range,
                onValueChangeFinished = {
                    shifts.saveTo(prefs)
                    recompute(currentLook(), intensity)
                },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = shiftCaption }
                    .testTag("yishun_shift_" + s.name.lowercase()),
            )
        }
        }

        if (sheet == 1) {
            AppSheet(title = "筛选配方", tag = "yishun_sheet_filter", onDismiss = { sheet = 0 }) {
                AppSheetSection("冷暖")
                AppChipFlow {
                    AppBarCell(
                        label = when (toneFilter) {
                            1 -> "暖调"
                            2 -> "冷调"
                            else -> "冷暖"
                            },
                        on = toneFilter != 0,
                        onClick = {
                            toneFilter = (toneFilter + 1) % 3
                            prefs.edit().putInt("yishun_tone", toneFilter).apply()
                        },
                    )
                }
                AppSheetSection("预设池", "钉住与最近决定候选范围")
                YishunFlagChips(YishunFlagGroup.FILTER, flags) { f ->
                    flags = flags.toggle(f).also { it.saveTo(prefs) }
                }
                AppSheetSection("参数特征", "共 ${LookFilter.entries.size} 项，同时成立才留下")
                AppChipFlow {
                    LookFilter.entries.forEach { f ->
                        val on = f in activeFilters
                        AppBarCell(
                            label = if (on) f.labelOn else f.labelOff,
                            on = on,
                            onClick = { toggleFilter(f) },
                        )
                    }
                }
                AppChipFlow {
                    if (selectedId != LookLibrary.AUTO_ID) {
                        AppBarCell(
                            label = if (selectedId in pinLooks) "已钉住" else "钉住",
                            on = selectedId in pinLooks,
                            onClick = {
                                val next = pinLooks.toMutableSet()
                                if (selectedId in next) next.remove(selectedId) else next.add(selectedId)
                                pinLooks = next
                                prefs.edit().putStringSet("yishun_pins", next).apply()
                            },
                        )
                    }

                    if (pinLooks.isNotEmpty()) {
                        AppBarCell(
                            label = "清钉",
                            on = false,
                            onClick = {
                                pinLooks = emptySet()
                                flags = flags.copy(onlyPins = false).also { it.saveTo(prefs) }
                                prefs.edit()
                                    .putStringSet("yishun_pins", emptySet())
                                    .putBoolean("yishun_only_pins", false)
                                    .apply()
                            },
                        )
                    }

                    if (recentIds.isNotEmpty()) {
                        AppBarCell(
                            label = "清最近",
                            on = false,
                            onClick = {
                                recentIds = emptyList()
                                prefs.edit().putString("yishun_recent", "").apply()
                            },
                        )
                    }

                }
                AppBarCell(
                    label = "清除全部筛选",
                    on = false,
                    enabled = activeFilters.isNotEmpty() || toneFilter != 0,
                    onClick = {
                        activeFilters = emptySet()
                        toneFilter = 0
                        // 一个 Editor 一次 apply：原来 23 个筛选各开一个 Editor、各 apply 一次，
                        // 就是 23 次磁盘写调度挤在同一次点击里。
                        prefs.edit().apply {
                            LookFilter.entries.forEach { f -> putBoolean(f.prefKey, false) }
                        }.apply()
                        prefs.edit().putInt("yishun_tone", 0).apply()
                    },
                    modifier = Modifier.padding(top = 14.dp),
                )
                // 「我的配方」放在筛选抽屉末尾：它和筛选是同一件事的两端 ——
                // 筛选是从 5446 条内置里找，这里是把自己调好的那几条留在手边。
                MyRecipesSection(
                    items = recipes,
                    onApply = { look ->
                        applyCustom(look)
                        sheet = 0
                    },
                    onRename = { look ->
                        renaming = look
                        // 从筛选抽屉进来的，收起后回筛选抽屉（1），不是回主屏。
                        proReturn = 1
                        sheet = 10
                    },
                    onDelete = { look ->
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { CustomLooks(context).delete(look.id) }
                            recipes = saved
                            message = "已删除「${look.name}」"
                        }
                    },
                    onExport = { look ->
                        scope.launch { message = exportRecipeText(context, look) }
                    },
                    onImport = {
                        proReturn = 1
                        sheet = 9
                    },
                )
            }
        }
        if (sheet == 2) {
            AppSheet(title = "更多", tag = "yishun_sheet_more", onDismiss = { sheet = 0 }) {
                AppSheetSection("配方行")
                YishunFlagChips(YishunFlagGroup.LOOK, flags) { f ->
                    flags = flags.toggle(f).also { it.saveTo(prefs) }
                }
                AppSheetSection("显示")
                YishunFlagChips(YishunFlagGroup.VIEW, flags) { f ->
                    flags = flags.toggle(f).also { it.saveTo(prefs) }
                    // 关掉闪比时顺手退出「看原图」：这两个是同一个动作的两半，
                    // 原来另开一格「闪比」把副作用挂在外面，抽屉里就出现了两个同名格。
                    if (f == YishunFlag.BLINK && !flags.blink) showOriginal = false
                }
                AppSheetSection("对比原图")
                AppChipFlow {
                    AppBarCell(
                        label = if (splitCompare) "分屏开" else "分屏",
                        on = splitCompare,
                        onClick = { splitCompare = !splitCompare },
                    )
                    if (splitCompare) {
                        AppBarCell(
                            label = if (flags.splitVert) "竖分" else "横分",
                            on = flags.splitVert,
                            onClick = {
                                flags = flags.toggle(YishunFlag.SPLIT_VERT).also { it.saveTo(prefs) }
                            },
                        )
                        AppBarCell(
                            label = if (flags.splitFlip) "成片在左" else "原图在左",
                            on = flags.splitFlip,
                            onClick = {
                                flags = flags.toggle(YishunFlag.SPLIT_FLIP).also { it.saveTo(prefs) }
                            },
                        )
                        AppBarCell(
                            label = "居中",
                            on = false,
                            onClick = { split = 0.5f },
                        )
                        AppBarCell(
                            label = "左三分",
                            on = false,
                            onClick = { split = 0.33f },
                        )
                        AppBarCell(
                            label = "右三分",
                            on = false,
                            onClick = { split = 0.67f },
                        )
                        AppBarCell(
                            label = if (flags.splitRed) "红线" else "白线",
                            on = flags.splitRed,
                            onClick = {
                                flags = flags.toggle(YishunFlag.SPLIT_RED).also { it.saveTo(prefs) }
                            },
                        )
                    }
                    if (!splitCompare) {
                        AppBarCell(
                            label = if (showOriginal) "看成片" else "看原图",
                            on = showOriginal,
                            onClick = { showOriginal = !showOriginal },
                        )
                        // 「适应/铺满」不在这儿重复摆：它属于上面的「显示」段，
                        // 同一个抽屉里出现两个同名格等于让用户猜哪个是真的。
                    }
                }
                AppSheetSection("导出")
                YishunFlagChips(YishunFlagGroup.EXPORT, flags) { f ->
                    flags = flags.toggle(f).also { it.saveTo(prefs) }
                }
                AppChipFlow {
                    AppBarCell(
                        label = "存对比",
                        on = false,
                        enabled = !saving && preview != null && original != null,
                        onClick = {
                            val look = currentLook()
                            val srcBmp = original ?: return@AppBarCell
                            val done = preview ?: return@AppBarCell
                            saving = true
                            scope.launch {
                                try {
                                    val coll = withContext(Dispatchers.Default) {
                                        compareCollage(srcBmp, done, flags.splitVert, look.name)
                                    }
                                    val saved = MediaRepository(context).saveBitmap(
                                        coll,
                                        "SuiPai_对比_${look.name}_${System.currentTimeMillis()}.${if (flags.savePng) "png" else "jpg"}",
                                        quality = jpegQ,
                                        sourceUri = uri,
                                        png = flags.savePng,
                                    )
                                    if (coll !== srcBmp && coll !== done && !coll.isRecycled) coll.recycle()
                                    saving = false
                                    if (saved != null) {
                                        lastSaved = saved
                                        message = "已保存对比图"
                                        onSaved(saved)
                                        if (flags.shareAfter) {
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = if (flags.savePng) "image/png" else "image/jpeg"
                                                putExtra(Intent.EXTRA_STREAM, saved)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            sendOrTell(context, Intent.createChooser(send, "分享"), "分享") {
                                                message = it
                                            }
                                        }
                                    } else {
                                        message = "保存失败"
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    Log.w("YishunScreen", "保存对比图失败", t)
                                    message = "保存失败"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
                AppChipFlow {
                    // 配方卡放在抽屉里、不放在顶栏：顶栏已有 7 颗格 + 1 主按钮，
                    // `docs/arch/DEVICE-RUN-20261018-1536.md` 的 D-3 已实测页名被挤成「一…」，
                    // 再塞一颗只会加重那个已知缺陷。
                    //
                    // `onRecipeCard == null` 也进 `enabled`：没有出口时这颗点了什么都不会发生，
                    // 灰着比"亮着但失灵"诚实（守卫 #19 同一条口径）。
                    AppBarCell(
                        label = "配方卡",
                        on = false,
                        enabled = onRecipeCard != null && preview != null && !busy,
                        modifier = Modifier.testTag("yishun_recipe_card"),
                        onClick = {
                            // 强度取滑杆上的当前值，不重读 prefs —— 重读会拿回"上次保存的强度"，
                            // 画出来的卡和用户正在看的这张不是同一张。
                            val look = currentLook()
                            onRecipeCard?.invoke(uri.toString(), look.id, intensity)
                        },
                    )
                    AppBarCell(
                        label = "画质 $jpegQ",
                        on = false,
                        onClick = {
                            jpegQ = if (jpegQ >= 100) 95 else 100
                            prefs.edit().putInt("yishun_jpeg", jpegQ).apply()
                            // 设置页那一项也要跟着变：两边写的都是"成片画质"这一件事。
                            settings.syncFromLegacyInt(
                                com.suipai.app.settings.LegacyKeys.YISHUN_JPEG, jpegQ,
                            )
                        },
                    )

                    AppBarCell(
                        label = if (flags.savePng) "PNG" else "JPEG",
                        on = flags.savePng,
                        onClick = {
                            flags = flags.toggle(YishunFlag.SAVE_PNG).also { it.saveTo(prefs) }
                        },
                    )

                    AppBarCell(
                        label = if (exportK == 2) "导出 2K" else "导出 4K",
                        on = exportK == 2,
                        onClick = {
                            exportK = if (exportK == 2) 4 else 2
                            prefs.edit().putInt("yishun_k", exportK).apply()
                            // 手动改画质就覆盖掉配方记的长边，否则 exportEdgeNow() 仍然听配方的，按钮看着没反应。
                            recipeExportEdge = null
                        },
                    )

                    AppBarCell(
                        label = if (grainMul < 0.05f) "无颗粒" else "有颗粒",
                        on = grainMul < 0.05f,
                        onClick = {
                            grainMul = if (grainMul < 0.05f) 1f else 0f
                            prefs.edit().putFloat("yishun_grain", grainMul).apply()
                            recompute(currentLook(), intensity)
                        },
                    )

                    AppBarCell(
                        label = if (vigMul < 0.05f) "无暗角" else "有暗角",
                        on = vigMul >= 0.05f,
                        onClick = {
                            vigMul = if (vigMul < 0.05f) 1f else 0f
                            prefs.edit().putFloat("yishun_vig", vigMul).apply()
                            recompute(currentLook(), intensity)
                        },
                    )

                    AppBarCell(
                        label = "轻",
                        on = false,
                        onClick = {
                            commitIntensity(0.32f)
                            recompute(currentLook(), intensity)
                        },
                    )

                    AppBarCell(
                        label = "中",
                        on = false,
                        onClick = {
                            commitIntensity(currentLook().defaultIntensity)
                            recompute(currentLook(), intensity)
                        },
                    )

                    AppBarCell(
                        label = "重",
                        on = false,
                        onClick = {
                            commitIntensity(0.78f)
                            recompute(currentLook(), intensity)
                        },
                    )

                }
                AppSheetSection("翻片与撤销")
                if (lastSaved == null) {
                    // 「分享 / 撤销」这两颗在存出东西之前是无事可做的。以前它们照旧亮着，
                    // 点下去 `?: return@AppBarCell` 一声不响 —— 抽屉盖着页眉那句 message，
                    // 用户只会觉得"这按钮坏了"。现在先把话说在抽屉里，再把格子按灰。
                    Text(
                        "先按「保存」存出一张，分享与撤销才有东西可接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    )
                }
                AppChipFlow {
                    if (onPrev != null) {
                        AppBarCell(label = "上一张", on = false, onClick = onPrev)
                    }
                    if (onNext != null) {
                        AppBarCell(label = "下一张", on = false, onClick = onNext)
                    }
                    if (prevLookId.isNotBlank()) {
                        AppBarCell(
                            label = "上个配方",
                            on = false,
                            onClick = {
                                val back = prevLookId
                                prevLookId = selectedId
                                selectedId = back
                                val applied = if (back == LookLibrary.AUTO_ID) {
                                    autoLook ?: LookLibrary.daylight
                                } else {
                                    LookLibrary.byId(back) ?: LookLibrary.daylight
                                }
                                intensity = if (back == LookLibrary.AUTO_ID) {
                                    autoIntensity
                                } else {
                                    prefs.getFloat("yishun_int_$back", applied.defaultIntensity)
                                }
                                reason = if (back == LookLibrary.AUTO_ID) {
                                    "智能适配 · ${applied.name}"
                                } else {
                                    applied.summary
                                }
                                recompute(applied, intensity)
                            },
                        )
                    }
                    AppBarCell(
                        label = "分享",
                        on = false,
                        enabled = lastSaved != null,
                        onClick = {
                            val target = lastSaved ?: return@AppBarCell
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, target)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            sendOrTell(context, Intent.createChooser(send, "分享"), "分享") { message = it }
                        },
                    )
                    AppBarCell(
                        label = "撤销",
                        on = false,
                        enabled = lastSaved != null,
                        onClick = {
                            val target = lastSaved ?: return@AppBarCell
                            scope.launch {
                                // 撤销删的是**本应用刚存出去**的文件，直接删就得授权的情况极少；
                                // 真遇上了也别谎称"已撤销"，说清楚是要授权。
                                when (MediaRepository(context).deleteOne(target)) {
                                    DeleteOutcome.DELETED -> {
                                        lastSaved = null
                                        message = "已撤销"
                                        onDeleted()
                                    }
                                    DeleteOutcome.NEEDS_CONSENT -> message = "撤销要系统授权"
                                    DeleteOutcome.FAILED -> message = "撤销失败"
                                }
                            }
                        },
                    )
                }
                AppSheetSection("复制参数")
                AppChipFlow {
                    AppBarCell(
                        label = "复制名",
                        on = false,
                        onClick = {
                            val name = if (selectedId == LookLibrary.AUTO_ID) {
                                "智能 · ${(autoLook ?: LookLibrary.daylight).name}"
                            } else {
                                currentLook().name
                            }
                            val clip = android.content.ClipData.newPlainText("随拍", name)
                            val wrote = clipWrite(context, "随拍", clip)
                            message = if (wrote) "已复制配方名" else "拷贝失败"
                        },
                    )
                    AppBarCell(
                        label = "复制编号",
                        on = false,
                        onClick = {
                            val id = if (selectedId == LookLibrary.AUTO_ID) LookLibrary.AUTO_ID else currentLook().id
                            val clip = android.content.ClipData.newPlainText("随拍", id)
                            val wrote = clipWrite(context, "随拍", clip)
                            message = if (wrote) "已复制编号" else "拷贝失败"
                        },
                    )
                    AppBarCell(
                        label = "复制摘要",
                        on = false,
                        onClick = {
                            val look = if (selectedId == LookLibrary.AUTO_ID) {
                                autoLook ?: LookLibrary.daylight
                            } else {
                                currentLook()
                            }
                            val text = "${look.name}\n${look.summary}"
                            val clip = android.content.ClipData.newPlainText("随拍", text)
                            val wrote = clipWrite(context, "随拍", clip)
                            message = if (wrote) "已复制摘要" else "拷贝失败"
                        },
                    )
                    AppBarCell(
                        label = "复制强度",
                        on = false,
                        onClick = {
                            val text = "强度 ${(intensity * 100).roundToInt()}%"
                            val clip = android.content.ClipData.newPlainText("随拍", text)
                            val wrote = clipWrite(context, "随拍", clip)
                            message = if (wrote) "已复制强度" else "拷贝失败"
                        },
                    )
                    AppBarCell(
                        label = "复制微调",
                        on = false,
                        onClick = {
                            val look = currentLook()
                            val text = buildString {
                                append(look.name)
                                append("  ")
                                append("${(intensity * 100).roundToInt()}%")
                                append('\n')
                                append("颗粒 ${"%.2f".format(grainMul)}")
                                append("  暗角 ${"%.2f".format(vigMul)}")
                                append('\n')
                                Shift.entries.forEachIndexed { i, s ->
                                    if (i == 3) append('\n') else if (i > 0) append("  ")
                                    append("${s.label} ${"%.2f".format(s.get(shifts))}")
                                }
                            }
                            val clip = android.content.ClipData.newPlainText("随拍", text)
                            val wrote = clipWrite(context, "随拍", clip)
                            message = if (wrote) "已复制微调" else "拷贝失败"
                        },
                    )
                }
            }
        }
        if (sheet == 3) {
            AiSheet(
                bitmap = original,
                exportEdge = exportEdgeNow(),
                onApply = { recipe ->
                    // 应用完就收抽屉：用户要点的是预览上的效果，不是抽屉里的第二行字。
                    applyRecipe(recipe)
                    sheet = 0
                },
                onDismiss = { sheet = 0 },
            )
        }
        // ── 专业分级：4 = 入口，5 = 曲线，6 = 分色，7 = 蒙版，8 = 存配方，9 = 导入，10 = 改名 ──
        // 继续用同一个 sheet 整数，不新建整屏：一瞬是一屏一事，多一套导航状态就多一条
        // "按返回键回到哪"的路径（那正是 0.6.0 里 BackHandler 那串 bug 的来源）。
        if (sheet == 4) {
            ProHubSheet(
                pro = pro,
                recipeCount = recipes.size,
                onOpenCurve = { sheet = 5 },
                onOpenHsl = { sheet = 6 },
                onOpenMask = { sheet = 7 },
                // 这三个抽屉收起后回哪，取决于"从哪个入口进来的"（见 proReturn 的注释）。
                onSaveRecipe = { proReturn = 4; sheet = 8 },
                onImportRecipe = { proReturn = 4; sheet = 9 },
                onResetPro = {
                    pro = ProOps()
                    proTick++
                },
                onDismiss = { sheet = 0 },
            )
        }
        if (sheet == 5) {
            ProCurveSheet(
                curves = pro.curves,
                onCurves = { next ->
                    pro = pro.withCurves(next)
                    proTick++
                },
                onDismiss = { sheet = 4 },
            )
        }
        if (sheet == 6) {
            ProHslSheet(
                bands = pro.bands,
                onBands = { next ->
                    pro = pro.withBands(next)
                    proTick++
                },
                onDismiss = { sheet = 4 },
            )
        }
        if (sheet == 7) {
            ProMaskSheet(
                masks = pro.masks,
                onMasks = { next ->
                    pro = pro.withMasks(next)
                    proTick++
                },
                onDismiss = { sheet = 4 },
            )
        }
        if (sheet == 8) {
            val look = currentLook()
            val baseId = if (selectedId == LookLibrary.AUTO_ID) look.id else selectedId
            SaveRecipeSheet(
                baseLookId = baseId,
                baseLookName = look.name,
                intensity = intensity,
                shifts = shifts,
                pro = pro,
                existingNames = recipes.map { it.name },
                onSave = { name ->
                    val custom = buildCustomLook(
                        name = name,
                        baseLookId = baseId,
                        intensity = intensity,
                        shifts = shifts,
                        pro = pro,
                    )
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { CustomLooks(context).save(custom) }
                        recipes = saved
                        message = "已存成「${custom.name}」"
                        sheet = proReturn
                    }
                },
                onDismiss = { sheet = proReturn },
            )
        }
        if (sheet == 9) {
            ImportRecipeSheet(
                onImport = { imported ->
                    scope.launch {
                        // 导入只是解析；落盘走仓库（同 id 会覆盖，而 importText 每次都换新 id）。
                        val saved = withContext(Dispatchers.IO) { CustomLooks(context).save(imported) }
                        recipes = saved
                        message = "已导入「${imported.name}」"
                        sheet = proReturn
                    }
                },
                onDismiss = { sheet = proReturn },
            )
        }
        if (sheet == 10) {
            val target = renaming
            if (target == null) {
                // 目标没了（并发删除 / 进程恢复）。收抽屉比留一个白板诚实。
                LaunchedEffect(Unit) { sheet = proReturn }
            } else {
                RenameRecipeSheet(
                    look = target,
                    onRename = { name ->
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { CustomLooks(context).rename(target.id, name) }
                            recipes = saved
                            message = "已改名"
                            renaming = null
                            sheet = proReturn
                        }
                    },
                    onDismiss = {
                        renaming = null
                        sheet = proReturn
                    },
                )
            }
        }
    }
}

/** 直方图三色：提到文件级，别在 Canvas 的每一帧里重新装箱三个 Color。 */
private val HistRgb = listOf(Color(0xE8E24B4B), Color(0xE84BD94F), Color(0xE84B7DE2))

@Composable
private fun LumaHist(
    values: IntArray,
    modifier: Modifier = Modifier,
    rgb: Array<IntArray>? = null,
) {
    Canvas(modifier) {
        if (rgb != null && rgb.size >= 3) {
            val peak = maxOf(
                rgb[0].maxOrNull() ?: 1,
                rgb[1].maxOrNull() ?: 1,
                rgb[2].maxOrNull() ?: 1,
            ).coerceAtLeast(1)
            val n = rgb[0].size.coerceAtLeast(1)
            val barW = size.width / n
            rgb.forEachIndexed { c, arr ->
                arr.forEachIndexed { i, v ->
                    val h = size.height * v / peak
                    drawRect(
                        color = HistRgb[c],
                        topLeft = Offset(i * barW, size.height - h),
                        size = Size(barW * 0.86f, h),
                    )
                }
            }
        } else {
            val peak = values.maxOrNull()?.coerceAtLeast(1) ?: 1
            val barW = size.width / values.size.coerceAtLeast(1)
            values.forEachIndexed { i, v ->
                val h = size.height * v / peak
                drawRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(i * barW, size.height - h),
                    size = Size(barW * 0.86f, h),
                )
            }
        }
    }
}

@Composable
private fun SplitCompare(
    original: Bitmap,
    processed: Bitmap,
    split: Float,
    onSplitChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    flip: Boolean = false,
    lineRed: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val splitState = rememberUpdatedState(split)
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = (if (flip) original else processed).asImageBitmap(),
                contentDescription = if (flip) "原图" else "成片",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                bitmap = (if (flip) processed else original).asImageBitmap(),
                contentDescription = if (flip) "成片" else "原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val t = split.coerceIn(0.08f, 0.92f)
                        if (vertical) {
                            val cut = size.height * t
                            clipRect(0f, 0f, size.width, cut) {
                                this@drawWithContent.drawContent()
                            }
                        } else {
                            val cut = size.width * t
                            clipRect(0f, 0f, cut, size.height) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    },
            )
            Text(
                if (flip) "成片" else "原图",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(if (vertical) Alignment.TopStart else Alignment.TopStart)
                    .padding(12.dp),
            )
            Text(
                if (flip) "原图" else "成片",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(if (vertical) Alignment.BottomStart else Alignment.TopEnd)
                    .padding(12.dp),
            )
            if (vertical) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(0, (split.coerceIn(0.08f, 0.92f) * heightPx).roundToInt() - 12)
                        }
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(heightPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onSplitChange(
                                    (splitState.value + drag.y / heightPx).coerceIn(0.08f, 0.92f),
                                )
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (lineRed) Color(0xFFD94F3D) else Color.White),
                    )
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset((split.coerceIn(0.08f, 0.92f) * widthPx).roundToInt() - 12, 0)
                        }
                        .fillMaxHeight()
                        .width(24.dp)
                        .pointerInput(widthPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onSplitChange(
                                    (splitState.value + drag.x / widthPx).coerceIn(0.08f, 0.92f),
                                )
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .width(2.dp)
                            .background(if (lineRed) Color(0xFFD94F3D) else Color.White),
                    )
                }
            }
        }
    }
}

private fun drawCollageLabel(canvas: android.graphics.Canvas, text: String, x: Float, y: Float, maxW: Int) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = (maxW * 0.045f).coerceIn(22f, 56f)
        isAntiAlias = true
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
    }
    canvas.drawText(text, x, y, paint)
}

private fun compareCollage(
    original: Bitmap,
    processed: Bitmap,
    vertical: Boolean,
    lookName: String = "",
): Bitmap {
    if (vertical) {
        val w = minOf(original.width, processed.width).coerceAtLeast(1)
        fun fit(src: Bitmap): Bitmap {
            val h = (src.height * w.toFloat() / src.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            return if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        }
        val top = fit(original)
        val bot = fit(processed)
        val out = Bitmap.createBitmap(w, top.height + bot.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawBitmap(top, 0f, 0f, null)
            drawBitmap(bot, 0f, top.height.toFloat(), null)
            drawCollageLabel(this, "原图", 16f, top.height * 0.08f, w)
            drawCollageLabel(
                this,
                if (lookName.isBlank()) "成片" else "成片 · $lookName",
                16f,
                top.height + bot.height * 0.08f,
                w,
            )
        }
        if (top !== original && !top.isRecycled) top.recycle()
        if (bot !== processed && !bot.isRecycled) bot.recycle()
        return out
    }
    val h = minOf(original.height, processed.height).coerceAtLeast(1)
    fun fit(src: Bitmap): Bitmap {
        val w = (src.width * h.toFloat() / src.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        return if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
    }
    val left = fit(original)
    val right = fit(processed)
    val out = Bitmap.createBitmap(left.width + right.width, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).apply {
        drawBitmap(left, 0f, 0f, null)
        drawBitmap(right, left.width.toFloat(), 0f, null)
        drawCollageLabel(this, "原图", 16f, h * 0.08f, left.width)
        drawCollageLabel(
            this,
            if (lookName.isBlank()) "成片" else "成片 · $lookName",
            left.width + 16f,
            h * 0.08f,
            right.width,
        )
    }
    if (left !== original && !left.isRecycled) left.recycle()
    if (right !== processed && !right.isRecycled) right.recycle()
    return out
}

private const val THUMB_EDGE = 128

private fun thumbBudgetBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8L).coerceIn(2L shl 20, 8L shl 20).toInt()

// ColorPipeline.apply 内部已经用 runBlocking + 最多 8 个 Dispatchers.Default 任务把一张图
// 拆行带并行掉了，所以外层必须串行：实测 3 路并发缩略图会把主线程饿到 Skipped 228 frames，
// 1 路时同样的快滚只剩 1 次抖动。调度仍放 IO，避免占住 Default 的像素线程。
@OptIn(ExperimentalCoroutinesApi::class)
private val thumbRenderDispatcher = Dispatchers.IO.limitedParallelism(1)

private fun miniOf(bitmap: Bitmap, edge: Int): Bitmap {
    val scale = edge.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(
        bitmap,
        maxOf(1, (bitmap.width * scale).toInt()),
        maxOf(1, (bitmap.height * scale).toInt()),
        true,
    )
}

/**
 * 一瞬预设缩略图的有界缓存：只为真正组合出来的 chip 渲染，超出预算丢最久没用的。
 * 淘汰时不 recycle——同一个 id 可能正被另一个 chip 显示着；只在界面销毁后统一回收。
 */
private class LookThumbCache : LruCache<String, Bitmap>(thumbBudgetBytes()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    private var closed = false

    suspend fun render(look: Look?, source: Bitmap?): Bitmap? {
        if (closed || look == null || source == null || source.isRecycled) return null
        get(look.id)?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = withContext(thumbRenderDispatcher) {
            runCatching {
                ColorPipeline.apply(source, look, look.defaultIntensity, maxEdge = THUMB_EDGE)
            }.getOrNull()
        } ?: return null
        // 界面已经销毁（dispose 把这张也 recycle 了）就别再进缓存，直接回收。
        if (closed) {
            if (!bmp.isRecycled) bmp.recycle()
            return null
        }
        put(look.id, bmp)
        return bmp
    }

    fun dispose() {
        closed = true
        // 顺序不能反：evictAll 会对每个被淘汰项再调一次 sizeOf()，而 recycle 之后的
        // getByteCount() 是未定义行为（实测返回 0），size 就减不到 0，
        // trimToSize 抛 "sizeOf() is reporting inconsistent results" 直接崩在主线程。
        val live = snapshot()
        evictAll()
        live.values.forEach { if (!it.isRecycled) it.recycle() }
    }
}

private class BitmapBag {
    var original: Bitmap? = null
    var preview: Bitmap? = null
    var mini: Bitmap? = null

    fun recycle() {
        mini?.let { if (it !== original && it !== preview && !it.isRecycled) it.recycle() }
        preview?.let { if (it !== original && !it.isRecycled) it.recycle() }
        original?.let { if (!it.isRecycled) it.recycle() }
        original = null
        preview = null
        mini = null
    }
}

/**
 * 存片那一句结论。
 *
 * 原来 `alsoCompare` 一开就说「已保存成片和对比图」，而对比图那张的 `saveBitmap`
 * 结果根本没接 —— 拼图没落库（或者两张位图那时已经回收、拼图压根没做）也说成两片都存了。
 * 现在 `compareSaved` 是从 MediaStore 拿回来的那个 uri 算的。
 */
internal fun savedNote(compareAsked: Boolean, compareSaved: Boolean): String = when {
    !compareAsked -> "已写入系统相册"
    compareSaved -> "已保存成片和对比图"
    else -> "成片已存，对比图没存上"
}
