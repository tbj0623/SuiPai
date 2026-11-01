package com.suipai.app.ui.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.navigationBarsPadding
import com.suipai.app.data.ImageLoader
import com.suipai.app.ui.common.ShutterFab
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.style.TextOverflow
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.common.AppBarHeight
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection
import com.suipai.app.ui.common.sendOrTell
import com.suipai.app.ui.theme.CameraOverlay
import com.suipai.app.ui.theme.SuiPaiSheet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// ---------------------------------------------------------------------------
// Camera semantic colors.
// The immersive camera UI is a dark surface, so these are the values that used
// to be hard-coded inline, collected here so the palette is defined once.
// CamIvory / CamRecordRed intentionally echo the app brand palette in
// ui/theme/Theme.kt (Ivory = 0xFFF2EDE6, Shutter = 0xFFD94F3D): the red shutter
// accent is the same brand red, reused as the capture accent.
// ---------------------------------------------------------------------------
private val CamInk = Color.Black            // immersive background / dim overlay base
private val CamIvory = Color.White          // foreground text, lines and controls on dark
private val CamRecordRed = Color(0xFFD94F3D) // shutter button + capture accent (brand red)
private val CamLockGreen = Color(0xFF7DFF9A) // focus / exposure lock + level-ok indicator
private val CamGold = Color(0xFFE8C15A)      // golden-grid lines + tilt warning
private val CamWarmFill = Color(0xFFFFE4C2)  // warm front fill-light flash
private val CamChromeScrim = Color.Black.copy(alpha = 0.42f)  // 顶部固定条的压暗底

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraScreen(
    onCaptured: (Uri, Boolean) -> Unit,
    onReview: (Uri) -> Unit = {},
    onSystemCamera: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { cameraGranted = it }
    LaunchedEffect(Unit) {
        if (!cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA)
    }
    // 这个分支提前 return，读不到下面的 `status`，所以「去设置」打不开了要有一格自己的话。
    var settingsHint by remember { mutableStateOf<String?>(null) }
    if (!cameraGranted) {
        Column(
            Modifier
                .fillMaxSize()
                .background(CamInk)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 相机是这App的主入口，被拒之后不该只剩一行字：给三条明确的出路。
            CameraOverlay {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("随拍需要相机权限", style = SuiPaiSheet.type.pageHead, color = CamIvory)
                    Text(
                        "不授权也能看已经拍好的片子。授权之后回到这里，快门还在原来那颗位置。",
                        style = SuiPaiSheet.type.label,
                        color = CamIvory.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    )
                    AppChipFlow {
                        AppBarPrimary(
                            label = "授权相机",
                            onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.testTag("cam_ask_perm"),
                        )
                        AppBarCell(
                            label = "去设置",
                            on = false,
                            onClick = {
                                settingsHint = null
                                openAppSettings(context) { settingsHint = it }
                            },
                            modifier = Modifier.testTag("cam_app_settings"),
                        )
                        AppBarCell(label = "关闭", on = false, onClick = onClose)
                    }
                    settingsHint?.let {
                        Text(
                            it,
                            style = SuiPaiSheet.type.label,
                            color = CamIvory.copy(alpha = 0.86f),
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
        }
        return
    }
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("suipai", Context.MODE_PRIVATE) }
    val cam = remember(prefs) { CameraUiState(context, prefs) }
    // 专业模式的全部状态收在这一个 remember 里：CameraScreen 已经很长，而每多一个
    // `by remember` 委托就离 ART 校验器那道悬崖更近一步（见 CameraUiState.kt 开头那段）。
    val pro = remember(prefs) { CamProState(prefs) }
    /** 哪一档参数的抽屉开着。null = 关着。只有一层，与相机其它抽屉互斥（camSheet 会被抢先关掉）。 */
    var proSlot by remember { mutableStateOf<ProSlot?>(null) }
    // 直方图开关：只在 PRO 开着且设备给得出画面时才有意义，所以它不进 CameraFlag 表
    // （那张表的键是持久化的用户偏好，而直方图是"这一趟取景想看什么"）。
    var showHist by remember { mutableStateOf(true) }
    // 帧源：PRO 直方图用。null = 跑不起来（下面会写一句人话）。
    var frameSource by remember { mutableStateOf<androidx.camera.core.ImageAnalysis?>(null) }
    // 拍完回读那句话（P1-6）。**必须单独存一份**，不能直接写 `status`：
    // `captureOnce` 返回之后，`fireShutter` 立刻会把状态行改成「已保存」，
    // 写在里面的话会被同一个调用栈的下一行覆盖掉——那正是 P0-2 的那类"界面在说谎"。
    var exposureNote by remember { mutableStateOf("") }
    with(cam) {
    val tiltLimit = when (levelTol) {
        0 -> 1.2f
        2 -> 5f
        else -> 2.2f
    }
    val shutterClick = remember { MediaActionSound() }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    /**
     * 直方图那条分析流的执行器。**单线程**：直方图只需要 1/64 的像素，
     * 一个线程足够，而 `Dispatchers.Default` 早被 `ColorPipeline` 的 8 路分块占满
     * （见 `ColorPipeline.kt:56`）——再塞进去就是与"出片"抢 CPU。
     * `onDispose` 里必须 shutdown：这个线程池不属于任何 Composable 的生命周期。
     */
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = if (prefs.getBoolean("camera_fill", true)) {
                PreviewView.ScaleType.FILL_CENTER
            } else {
                PreviewView.ScaleType.FIT_CENTER
            }
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }
    /**
     * 把 PRO 的**当前状态**算成计划并立刻下发给 session。**所有改 PRO 状态的入口都必须走它。**
     *
     * 为什么必须是一个共用函数（P0-1）：这个动作原来有四份调用点（绑好相机、顶栏 PRO 格、
     * 抽屉里的 PRO 格、选完一档），而其中两处只调了 `pro.toggle()` —— 状态翻了、界面翻了，
     * `CameraProBindings.apply` 一次都没被调用。于是用户选过 ISO 1600 之后点「PRO 关」，
     * `SENSOR_SENSITIVITY` 仍留在 session 上，下一张还按 1600 曝光。
     * `CameraProStateTest.disabledModeAlwaysPlansAReset_notANoOp` 钉的是"计划该是 Reset"，
     * 钉不住"有没有人真去下发"——那正是这个 bug 能在 31 条全绿下活下来的原因。
     * 现在再加一条源码守卫（`PerfSourceGuardTest.everyProToggleAppliesItsPlan`）兜住调用点。
     *
     * 失败时**只降内存**（P0-3）：一次 `ILLEGAL_ARGUMENT` 或 `readCapsOrNull` 偶发失败
     * 不代表用户存盘的 ISO 3200 是错的，那不该变成磁盘上的墓碑（见 `CamProState.degradeInMemory`）。
     *
     * @param failNote 下发失败时要写在状态行上的那句话（绑相机与选档位的措辞不同）
     * @return true = 已下发给 CameraX
     */
    fun applyProNow(failNote: String = "专业参数没能下发，已回到自动"): Boolean {
        // 计划要变了，上一张照片那句「请求 vs 实际」已经过期——留着就是一句不再成立的话。
        exposureNote = ""
        val ok = CameraProBindings.apply(
            camera?.cameraControl,
            planCaptureOptions(pro.mode, pro.caps),
        )
        if (ok) return true
        if (pro.mode.anyManual) {
            pro.degradeInMemory()
            status = failNote
        }
        return false
    }

    /**
     * 对焦 + 测光，锁不锁曝光都走这一个动作。
     *
     * 原来只有预览上的手势能触发（单击对焦、长按锁曝光），读屏用户与外接键盘够不到这条路，
     * 抽屉里那两颗格子就是把**同一个**动作再给一条入口，所以两侧共用这个函数而不是各写一遍。
     *
     * 结果那句话写在**这里**，不写在下面那条收对焦框的 `LaunchedEffect` 里，也不跟着它一起擦掉：
     * 抽屉是一颗独立弹窗，它开着的时候底下那层根本不在无障碍树里（实测 `uiautomator dump`
     * 只给当前获焦窗口），所以读屏用户的真实顺序只能是 开抽屉 → 点格子 → 收抽屉 → 摸状态行。
     * 走到那一步时任何"只活几秒"的话都已经过去了 —— 话要在动作那一刻写下，并且留着。
     */
    fun focusAe(x: Float, y: Float, lock: Boolean) {
        val c = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val builder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(3, TimeUnit.SECONDS)
        c.cameraControl.startFocusAndMetering(builder.build())
        aeLocked = lock
        focusAt = Offset(x, y)
        val where = focusRegion(x, y, previewView.width, previewView.height)
        status = focusWords(if (lock) FocusResult.LOCKED else FocusResult.FOCUSED, where)
    }

    fun releaseFocusAe() {
        camera?.cameraControl?.cancelFocusAndMetering()
        aeLocked = false
        focusAt = null
        // 原来这里直接回到 `modeLabel`：于是"解除了"这件事只剩"绿字不见了"，
        // 而那既不在活区上，也不是一句听得懂的话。
        status = focusWords(FocusResult.RELEASED, "")
    }

    /** 预览还没量出尺寸时按哪都对不了焦，那就要说一句人话，不能静悄悄什么都不发生。 */
    fun centerOfPreview(): Offset? =
        if (previewView.width > 0 && previewView.height > 0) {
            Offset(previewView.width / 2f, previewView.height / 2f)
        } else {
            status = focusWords(FocusResult.NOT_READY, "")
            null
        }

    LaunchedEffect(previewFill) {
        previewView.scaleType = if (previewFill) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
    }

    suspend fun captureOnce(): Uri? {
        val capture = imageCapture ?: return null
        shutterFlash = true
        if (shutterSound) shutterClick.play(MediaActionSound.SHUTTER_CLICK)
        if (shutterHaptic) buzz(context)
        capture.targetRotation = displayRotation(context)
        val window = (context as? Activity)?.window
        val prevBright = window?.attributes?.screenBrightness ?: -1f
        val fillLight = lensFacing == CameraSelector.LENS_FACING_FRONT &&
            flashMode != ImageCapture.FLASH_MODE_OFF
        if (fillLight && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        try {
            if (fillLight) delay(220)
            if (antiShake) delay(320)
            // P1-6：先记住**这一张请求了什么**。PRO 关着时计划是 Reset，两项都是 null，
            // 于是这一趟不会拿自动档的数字去和 null 比（见 exposureMismatchNote 的纪律）。
            val wanted = planCaptureOptions(pro.mode, pro.caps) as? CaptureOptionPlan.Options
            val uri = takeAndSave(context, capture) ?: return null
            savedSize = ""
            todayN += 1
            prefs.edit().putString("shot_day", shotDay).putInt("shot_n", todayN).apply()
            val (note, saved) = withContext(Dispatchers.IO) {
                // **在后处理之前**读 EXIF：下面 postProcessCapture 有画幅/水印/镜像时会用
                // Bitmap.compress 重写同一个 uri，而 compress 不写 EXIF —— 读晚了就只剩空。
                // 这也正是这一条只能在本仓库自己的 IO 段做、不能放进后处理里的原因。
                val actual = readSavedExposure(context, uri)
                val diff = exposureMismatchNote(
                    requestedIso = wanted?.sensitivity,
                    requestedShutterNs = wanted?.exposureNs,
                    actualIso = actual.first,
                    actualShutterNs = actual.second,
                )
                diff to postProcessCapture(
                    context,
                    uri,
                    cropAspect,
                    mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorFront,
                    stamp = dateStamp,
                    stampCorner = stampCorner,
                    stampStyle = stampStyle,
                    stampScale = stampScale,
                    stampAlpha = stampAlpha,
                    onSize = { w, h -> savedSize = "$w×$h" },
                )
            }
            // 只写进 `exposureNote`，**不写 status**：`fireShutter` 紧接着会把状态行改成
            // 「已保存」，写在里面会被下一行覆盖（与 P0-2 同一类"界面在说谎"）。
            exposureNote = note
            return saved
        } finally {
            if (fillLight && window != null) {
                window.attributes = window.attributes.apply { screenBrightness = prevBright }
            }
            if (torchOff && torchOn) {
                camera?.cameraControl?.enableTorch(false)
                torchOn = false
            }
        }
    }

    fun fireShutter() {
        if (capturing) return
        capturing = true
        status = "正在拍摄"
        scope.launch {
            runCatching { captureOnce() }
                .onSuccess { uri ->
                    capturing = false
                    if (uri != null) {
                        lastShot = uri
                        status = "已保存"
                        if (reviewOn && !autoYishun) reviewUri = uri
                        onCaptured(uri, autoYishun)
                    } else {
                        status = "保存失败"
                    }
                }
                .onFailure {
                    capturing = false
                    status = it.message ?: "拍摄失败"
                }
        }
    }

    fun burst() {
        if (capturing || countdown > 0) return
        if (levelShutter && kotlin.math.abs(rollDeg) > tiltLimit) {
            status = "先水平"
            return
        }
        capturing = true
        scope.launch {
            // 原来只留"最后一张"的结果：第 1 张成、后面 4 张全 null 会说「连拍失败」，
            // 反过来 4 成 1 败（败在最后）会说「连拍完成」——两头都不合实况。
            var ok = 0
            var last: Uri? = null
            try {
                repeat(burstN) { i ->
                    status = "连拍 ${i + 1}/$burstN"
                    val uri = captureOnce()
                    if (uri != null) {
                        ok++
                        last = uri
                        lastShot = uri
                    }
                    delay(burstGap.toLong())
                }
                status = burstStatus(ok, burstN)
                if (shutterHaptic && last != null) buzz(context)
                last?.let { onCaptured(it, false) }
            } catch (e: Exception) {
                status = if (ok > 0) "连拍中断：$ok/$burstN 张已存（${e.message ?: "出错"}）"
                else e.message ?: "连拍失败"
            } finally {
                capturing = false
            }
        }
    }

    fun shoot() {
        if (intervalRunning) {
            intervalJob?.cancel()
            intervalJob = null
            intervalRunning = false
            capturing = false
            status = "已停间隔"
            return
        }
        if (capturing) return
        if (countdown > 0) {
            timerJob?.cancel()
            timerJob = null
            countdown = 0
            status = "已取消延时"
            return
        }
        if (intervalSec > 0) {
            intervalRunning = true
            intervalJob = scope.launch {
                capturing = true
                // 原来每一张都套着 `runCatching{}.getOrNull()`：拍坏了既不吭声也不停，
                // 屏幕上永远写着「间隔 N秒」，而实际上可能一张都没存下来。
                var misses = 0
                try {
                    while (true) {
                        status = intervalStatus(intervalSec, misses)
                        val got = runCatching { captureOnce() }
                        val e = got.exceptionOrNull()
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        val uri = got.getOrNull()
                        if (uri != null) {
                            misses = 0
                            lastShot = uri
                            onCaptured(uri, false)
                        } else {
                            misses++
                            status = intervalStatus(intervalSec, misses) +
                                (e?.message?.let { "：$it" } ?: "")
                            if (misses >= 3) {
                                status = "间隔已停：连着 $misses 张没存上"
                                break
                            }
                        }
                        delay(intervalSec * 1000L)
                    }
                } finally {
                    capturing = false
                    intervalRunning = false
                }
            }
            return
        }
        if (levelShutter && kotlin.math.abs(rollDeg) > tiltLimit) {
            status = "先水平"
            return
        }
        if (timerSec > 0) {
            countdown = timerSec
            timerJob = scope.launch {
                var left = timerSec
                while (left > 0) {
                    countdown = left
                    if (shutterSound) {
                        runCatching { shutterClick.play(MediaActionSound.FOCUS_COMPLETE) }
                    }
                    if (shutterHaptic) buzz(context)
                    delay(1000)
                    left--
                }
                countdown = 0
                timerJob = null
                fireShutter()
            }
            return
        }
        fireShutter()
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                rollDeg = Math.toDegrees(kotlin.math.atan2(x.toDouble(), y.toDouble())).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accel != null) {
            sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sensors.unregisterListener(listener)
            shutterClick.release()
            intervalJob?.cancel()
            providerHolder.value?.unbindAll()
            // 分析流与它的线程池都归这一次取景所有：不关掉会在相机页退出后继续跑，
            // 而 ImageAnalysis 若还挂着 analyzer，CameraX 会在下一次打开时抱怨"流被占用"。
            runCatching { frameSource?.clearAnalyzer() }
            analysisExecutor.shutdown()
        }
    }
    val view = LocalView.current
    DisposableEffect(imageCapture, capturing, volZoom) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            ) {
                if (volZoom) {
                    val cam = camera ?: return@setOnKeyListener true
                    val state = cam.cameraInfo.zoomState.value ?: return@setOnKeyListener true
                    val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.16f else -0.16f
                    val next = (state.linearZoom + step).coerceIn(0f, 1f)
                    cam.cameraControl.setLinearZoom(next)
                    linearZoom = next
                    val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    zoomRatio = ratio
                    prefs.edit().putFloat("zoom_ratio", ratio).apply()
                    zoomLabel = if (ratio <= 1.05f) "" else "${"%.1f".format(ratio)}×"
                } else {
                    shoot()
                }
                true
            } else {
                false
            }
        }
        onDispose { view.setOnKeyListener(null) }
    }

    LaunchedEffect(lensFacing, selfieTimer) {
        if (selfieTimer && lensFacing == CameraSelector.LENS_FACING_FRONT && timerSec == 0) {
            timerSec = 3
            prefs.edit().putInt("timer_sec", 3).apply()
        }
    }

    // 画幅不参与 key：现在所有比例共用同一条 4:3 流，换比例只是换后处理的裁切框，
    // 把它们留在 key 里会让每次点「比例」都重绑一次相机（预览闪一下）。
    LaunchedEffect(lensFacing, jpegQ) {
        status = "打开相机"
        capturing = false
        if (!aeHold) aeLocked = false
        runCatching {
            val provider = ProcessCameraProvider.getInstance(context).await()
            providerHolder.value = provider
            val extensions = runCatching {
                ExtensionsManager.getInstanceAsync(context, provider).await()
            }.getOrNull()
            val facing = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            var selector = facing
            var label = "标准"
            if (extensions != null) {
                val modes = listOf(ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.AUTO)
                for (mode in modes) {
                    if (extensions.isExtensionAvailable(facing, mode)) {
                        selector = extensions.getExtensionEnabledCameraSelector(facing, mode)
                        label = when (mode) {
                            ExtensionMode.HDR -> "HDR"
                            ExtensionMode.NIGHT -> "夜景"
                            else -> "自动增强"
                        }
                        break
                    }
                }
            }
            val resolution = ResolutionSelector.Builder()
                .setAllowedResolutionMode(
                    ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE,
                )
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                // 一律按 4:3 那一路要，其余画幅由 postProcessCapture 裁——不再按画幅分支开流。
                //
                // 之前的两分支（`if (ratio43) 4:3 else 16:9`）实测过代价：在这台 AVD 上
                // 走 16:9 那一路的档位全部落到 720×405 那条流上（0.29 MP），
                // 1:1 出 405×405（0.16 MP）、2.39 出 405×169（0.07 MP），比 4:3 的 1856×1392 小 9~37 倍。
                // 改完之后同一台机器上：16:9 出 1920×1080、1:1 出 960×960、9:16 出 768×1366，
                // 比例各自名副其实（量法与逐档表见 ANDROID-VERIFY.md §13）。
                // 另一个好处：画幅不参与 LaunchedEffect 的 key，换比例不再重绑相机。
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(resolution)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            // 这两个事实由 `bind` 写出、由绑好相机之后那一段读（P0-2）：
            // 直方图的那句话必须能分清"造不出分析流"与"造得出但三路排不下"——
            // 混成一句的话，换个设备/换 CameraX 版本之后没人分得清该怪哪一层。
            // 放在 `bind` 外面（而不是函数内局部变量）是因为读取点在 bind 返回之后。
            var analysisBuilt = false
            var threeStreamBound = false
            fun bind(sel: CameraSelector, quality: Int): Camera {
                val rotation = displayRotation(context)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(quality)
                    .setJpegQuality(jpegQ)
                    .setFlashMode(flashMode)
                    .setTargetRotation(rotation)
                    .setResolutionSelector(resolution)
                    .build()
                preview.targetRotation = rotation
                // 直方图那第三条流：**单独一套低分辨率选择器**。借用上面那条 HIGHEST_AVAILABLE
                // 会把分析流也顶到满分辨率，等于每帧多拷一份 4:3 大图回来——为了一个 64 桶的
                // 直方图不值，而且会跟拍照抢带宽。640×480 + KEEP_ONLY_LATEST + 每 8 像素采样一次，
                // 单帧成本几十微秒（见 docs/product/CAMERA-PRO.md §5）。
                val analysis = runCatching {
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    // 全限定名写 `android.util.Size`：本文件已经 import 了
                                    // `androidx.compose.ui.geometry.Size`（六道辅助线 Canvas 全靠它），
                                    // 再 import 一个同名类会直接 "imported name 'Size' is ambiguous"。
                                    ResolutionStrategy(
                                        android.util.Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                }.getOrNull()
                analysis?.setAnalyzer(analysisExecutor) { image ->
                    // 只读 Y 平面，采样后立刻 close()。**任何异常都不许冒到分析线程上**：
                    // ImageAnalysis 的默认行为是异常直接让 CameraX 结束这一路流。
                    runCatching {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val hist = lumaHistogram(
                            y = bytes,
                            width = image.width,
                            height = image.height,
                            rowStride = plane.rowStride,
                            pixelStride = plane.pixelStride,
                            bins = HIST_BINS,
                            step = HIST_STEP,
                        )
                        val normalized = normalizeHistogram(hist)
                        scope.launch { pro.histogram = normalized }
                    }
                    image.close()
                }
                provider.unbindAll()
                // 三路一起绑；排不下就退回两路（**这一条是直方图的降级路径**，
                // 也是"宁可少一个功能也不要崩相机"的落点）。三路成功的概率在低端机上并不高：
                // Preview + ImageCapture(满分辨率) + ImageAnalysis 已经贴着保证组合的上限。
                //
                // `threeStreamBound` 是这一轮**唯一**的降级事实来源（P0-2）。以前降级时在
                // `recoverCatching` 里写一句 histogramNote，随后又被下面按 `frameSource == null`
                // 重算的那段覆盖成空串 —— 降级时 `analysis` 对象仍然非空，于是 when 落到 else。
                // 结果是"直方图 开"亮着、屏幕上什么都没有、也没有一句解释。
                threeStreamBound = false
                analysisBuilt = analysis != null
                val cam = runCatching {
                    provider.bindToLifecycle(lifecycle, sel, preview, capture, analysis)
                        // 只有三路一起绑**真的返回了**才算成功（写在 runCatching 内、成功后）。
                        .also { threeStreamBound = true }
                }.recoverCatching {
                    analysis?.clearAnalyzer()
                    provider.bindToLifecycle(lifecycle, sel, preview, capture)
                }.getOrThrow()
                // 降级时帧源必须是 null：analyzer 已经 clear 了，留着一个跑不动的引用只会让
                // 下游继续按"有帧源"推断（旧 bug 的根因）。
                frameSource = if (!analysisBuilt || !threeStreamBound) null else analysis
                imageCapture = capture
                return cam
            }
            var usedLabel = "标准"
            camera = runCatching {
                val cam = bind(selector, ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                usedLabel = label
                cam
            }.recoverCatching {
                usedLabel = "标准"
                bind(facing, ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            }.recoverCatching {
                usedLabel = "标准"
                bind(facing, ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            }.getOrThrow()
            status = usedLabel
            modeLabel = usedLabel
            val savedZ = prefs.getFloat("zoom_ratio", 1f).coerceAtLeast(1f)
            zoomRatio = savedZ
            zoomLabel = if (savedZ <= 1.05f) "" else "${savedZ.toInt()}×"
            camera?.cameraControl?.setZoomRatio(savedZ)
            linearZoom = camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f
            evLabel = evText(camera)
            val res = imageCapture?.resolutionInfo?.resolution
            captureSize = if (res != null && res.width > 0) "${res.width}×${res.height}" else ""
            sensorAspect = if (res != null && res.height > 0) res.width.toFloat() / res.height else 0f
            val cam = camera
            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                torchOn = false
            } else if (torchOn) {
                cam.cameraControl.enableTorch(true)
            }
            // ── 专业模式：读能力 + 把上次落盘的参数重新套上 ──────────────
            // 顺序很要紧：先 updateCaps（它会把上次存的参数按这台设备重钳一遍），
            // 再把钳制后的计划下发。反过来的话，一个越界的 ISO 会先被送进 session。
            runCatching {
                // `readCapsOrNull` 返回 null 表示**这一次没读到**，与"读到了但一项手动能力都没有"
                // 是两件事（P0-3）。把一次读失败当成"这台设备什么都不支持"喂给 `updateCaps`，
                // 它会钳成全自动并**写盘**，擦掉用户的 ISO/快门/WB/对焦。
                // 所以：读到了才 updateCaps（含钳制写盘），读不到只降内存。
                val read = CameraProBindings.readCapsOrNull(cam)
                if (read != null) {
                    pro.updateCaps(read)
                } else {
                    pro.degradeInMemory()
                }
                // `cam` 是 `camera` 的快照；下发失败时 applyProNow 会就地降级，
                // 且**只降内存不写盘**（见那个函数的注释）。
                applyProNow()
                pro.histogramNote = histogramNoteFor(
                    executorShutdown = analysisExecutor.isShutdown,
                    analysisBuilt = analysisBuilt,
                    threeStreamBound = threeStreamBound,
                )
            }
        }.onFailure {
            imageCapture = null
            camera = null
            status = "相机打开失败，可用系统相机"
        }
    }

    // 预览上的一切浮层都按暗房配色画，不跟随 App 的日光主题（见 Theme.kt::CameraOverlay）。
    CameraOverlay {
    Box(
        Modifier
            .fillMaxSize()
            .background(CamInk),
    ) {
        AndroidView(
            factory = {
                val scaleDetector = ScaleGestureDetector(
                    context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val cam = camera ?: return false
                            val current = cam.cameraInfo.zoomState.value?.linearZoom ?: 0f
                            val next = (current + (detector.scaleFactor - 1f) * 1.4f).coerceIn(0f, 1f)
                            cam.cameraControl.setLinearZoom(next)
                            linearZoom = next
                            val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            zoomRatio = ratio
                            zoomLabel = if (ratio <= 1.05f) "" else "${"%.1f".format(ratio)}×"
                            return true
                        }
                    },
                )
                val tapDetector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean = true
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (camera == null) return false
                            focusAe(e.x, e.y, lock = false)
                            previewView.performClick()
                            return true
                        }
                        override fun onLongPress(e: MotionEvent) {
                            if (camera == null) return
                            focusAe(e.x, e.y, lock = true)
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val cam = camera ?: return false
                            val state = cam.cameraInfo.zoomState.value ?: return false
                            val target = if (state.zoomRatio < 1.55f) {
                                minOf(2f, state.maxZoomRatio)
                            } else {
                                1f
                            }
                            cam.cameraControl.setZoomRatio(target)
                            zoomRatio = target
                            prefs.edit().putFloat("zoom_ratio", target).apply()
                            zoomLabel = if (target <= 1.05f) "" else "${"%.1f".format(target)}×"
                            status = if (target <= 1.05f) "1×" else zoomLabel
                            return true
                        }
                    },
                )
                previewView.setOnTouchListener { view, event ->
                    scaleDetector.onTouchEvent(event)
                    if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
                    // 触摸被我们自己吃掉做对焦/变焦了，无障碍服务就收不到点击事件；
                    // 抬起时补一次 performClick，让 TalkBack 等还能触发。
                    if (event.action == android.view.MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                previewView
            },
            update = {
                it.scaleX = if (lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorFront) -1f else 1f
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 这一条现在只管**收对焦框**：预览上那个 72dp 的框闪 2.8 秒就该不见（它视觉上是"点到了这里"
        // 的指示，一直挂着反而像还锁着）。结果那句话由 `focusAe` 在动作那一刻写进状态行，
        // 这里**一个字都不擦** —— 原来 `delay(2800)` 之后要把状态行清回去，于是那句话只活 2.8 秒，
        // 而读屏的人得先收掉抽屉才摸得到这一行（见 `focusAe` 上面那段）。没有新增定时器：
        // 这一段里的 `delay` 还是原来那一个，它只负责收框。
        LaunchedEffect(focusAt, aeLocked) {
            if (focusAt == null) return@LaunchedEffect
            // 锁着的时候那个框就是"还锁着"的记号，不收；锁定这一支的话也由 focusAe 说过了。
            if (aeLocked) return@LaunchedEffect
            delay(2800)
            if (!aeLocked) focusAt = null
        }
        focusAt?.let { pt ->
            Box(
                Modifier
                    .offset { IntOffset((pt.x - 36.dp.toPx()).toInt(), (pt.y - 36.dp.toPx()).toInt()) }
                    .size(72.dp)
                    .border(
                        1.5.dp,
                        if (aeLocked) CamLockGreen else CamIvory.copy(alpha = 0.9f),
                    ),
            )
        }
        if (aeLocked) {
            Text(
                "曝光锁定",
                color = CamLockGreen,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            )
        }
        LaunchedEffect(shutterFlash) {
            if (!shutterFlash) return@LaunchedEffect
            val hold = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 260L else 70L
            delay(hold)
            shutterFlash = false
        }
        if (shutterFlash) {
            val fill = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CamWarmFill.copy(alpha = 0.92f)
            } else {
                CamIvory.copy(alpha = 0.82f)
            }
            Box(Modifier.fillMaxSize().background(fill))
        }
        val reviewed = reviewUri
        LaunchedEffect(reviewed) {
            if (reviewed == null) return@LaunchedEffect
            delay(reviewMs.toLong())
            if (reviewUri == reviewed) reviewUri = null
        }
        if (reviewed != null) {
            AsyncImage(
                model = reviewed,
                contentDescription = "刚拍的照片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(CamInk.copy(alpha = 0.88f))
                    .clickable(role = Role.Button, onClickLabel = "关闭回看", onClick = { reviewUri = null }),
            )
            Text(
                "回看",
                color = CamIvory,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp),
            )
        }
        if (showLevel) Canvas(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val leveled = kotlin.math.abs(rollDeg) < 1.6f
            val color = if (leveled) CamLockGreen else CamIvory.copy(alpha = 0.9f)
            val midY = size.height / 2f
            drawLine(
                CamIvory.copy(alpha = 0.35f),
                Offset(size.width * 0.18f, midY),
                Offset(size.width * 0.82f, midY),
                2f,
            )
            rotate(rollDeg.coerceIn(-40f, 40f)) {
                drawLine(
                    color,
                    Offset(size.width * 0.28f, midY),
                    Offset(size.width * 0.72f, midY),
                    4f,
                )
            }
        }
        if (showSafe) Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * 0.06f
            drawRect(
                CamIvory.copy(alpha = 0.55f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f),
            )
        }
        if (showPhiFrame) Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * 0.191f
            drawRect(
                CamIvory.copy(alpha = 0.62f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f),
            )
        }
        if (showCenter) Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val paint = CamIvory.copy(alpha = 0.78f)
            drawLine(paint, Offset(cx - 22f, cy), Offset(cx + 22f, cy), 2.4f)
            drawLine(paint, Offset(cx, cy - 22f), Offset(cx, cy + 22f), 2.4f)
            drawCircle(CamIvory.copy(alpha = 0.35f), 6f, Offset(cx, cy))
        }
        // 上下两道渐隐的高度提前算：它们既是那两层遮罩的尺寸，也就是「看得见的取景带」的边界。
        // 辅助线里的「四角」要用它——贴着屏幕角画，角标就压在遮罩与快门条底下（见 CORNER 那段）。
        val availH = LocalConfiguration.current.screenHeightDp.dp
        val topScrim = minOf(150.dp, availH * 0.26f)
        val bottomScrim = minOf(260.dp, availH * 0.44f)
        if (gridMode > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val paint = (if (gridGold) CamGold else CamIvory)
                    .copy(alpha = if (gridBold) 0.52f else 0.18f)
                val stroke = if (gridBold) 2f else 1.2f
                // 24 条预设 = 6 个单基元 + 15 种两两 + 3 种三重，画线只写一遍（原来是 24 段几乎一样的 drawLine）。
                // 每一条都画一套别人没有的线——表由 CamGridGuardTest 钉，屏幕由 cam_grid3.py 钉。
                CamGrid.at(gridMode).parts.forEach {
                    gridLines(it, paint, stroke, topScrim, bottomScrim)
                }
            }
        }
        val cropTarget = cropAspect
        if (cropsNow) {
            // 压暗框只画「相对传感器那一帧真要裁掉的部分」：4:3 对着 4:3 的帧就不画，
            // 默认档的取景画面和以前一字不差。
            Canvas(Modifier.fillMaxSize()) {
                val view = size.width / size.height.coerceAtLeast(1f)
                val dim = CamInk.copy(alpha = 0.48f)
                if (view > cropTarget) {
                    val w = size.height * cropTarget
                    val left = (size.width - w) / 2f
                    drawRect(dim, Offset(0f, 0f), Size(left, size.height))
                    drawRect(dim, Offset(left + w, 0f), Size(size.width - left - w, size.height))
                } else {
                    val h = size.width / cropTarget
                    val top = (size.height - h) / 2f
                    drawRect(dim, Offset(0f, 0f), Size(size.width, top))
                    drawRect(dim, Offset(0f, top + h), Size(size.width, size.height - top - h))
                }
            }
        }
        // ── 取景上下渐隐 ───────────────────────────────────────────────
        // 浮层上的字是相纸白（CameraOverlay 固定用暗房配色）。对着天空、白墙、
        // 雪景时预览本身就接近纯白，白字白框直接消失——实测（虚拟场景上半屏是白的）
        // 第一版 0.26~0.42 的压暗量在 2×/3× 上只有 1.27:1，等于读不出来。
        // 白色场景上要让 235 亮度的字达到 3:1，压暗层在该处必须 ≥ 0.69 alpha，
        // 所以下面两条渐隐的峰值都定在 0.8~0.9，而不是"意思一下"。
        if (!uiLocked) {
            // 两道渐隐改成「占可用高度的比例，并以竖屏那组绝对值为上限」。
            // 竖屏 914dp 高时 0.26/0.44 算出 238/402dp，min 之后仍是 150dp 与 260dp——竖屏观感一字不变；
            // 但横屏只有 411dp 高，写死的 150+260=410dp 等于整块取景（实测 2400x1080 时两道合计
            // 1076px / 屏高 1080px = 100%），取景画面里再无一块未被压暗的区域。
            // （尺寸那两个 val 已经提到辅助线那一段之前——「四角」也按同一道边界画。）
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topScrim)
                    .background(
                        Brush.verticalGradient(
                            0f to CamInk.copy(alpha = 0.90f),
                            0.55f to CamInk.copy(alpha = 0.80f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomScrim)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.28f to CamInk.copy(alpha = 0.62f),
                            0.5f to CamInk.copy(alpha = 0.80f),
                            1f to CamInk.copy(alpha = 0.88f),
                        ),
                    ),
            )
        }
        // ── 顶部固定工具条 ─────────────────────────────────────────────
        // 这里原来是左右两列共 25 个 TextButton 直接叠在预览上，而且那两列不滚动：
        // 一屏装不下，下半截（水印四格、延时、EV）在真机上根本按不到——
        // 不是难看，是功能不可达。现在：一格 52dp 的固定条 + 一个抽屉，
        // 常用四格在取景时伸手可及，其余全部在语义树里、滑得动、找得到。
        if (!uiLocked) {
            AppBarRow(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding(),
                // 不再叠一层 42% 的方块底：那道渐隐已经负责压出对比，
                // 再加一块边界分明的灰板会在天空上留出一条脏边。
            ) {
                AppBarCell(
                    label = "关闭",
                    on = false,
                    onClick = onClose,
                    modifier = Modifier.testTag("cam_close"),
                )
                AppBarCell(
                    label = ratioLabel,
                    // 「这一档相对传感器帧真要裁点什么」才亮。十档现在都声明了目标比例，
                    // 用 `cropAspect > 0` 判断的话默认那档 4:3 会一直顶着琥珀框。
                    on = cropsNow,
                    enabled = !capturing,
                    onClick = { cycleRatio() },
                    modifier = Modifier.testTag("cam_ratio"),
                )
                AppBarCell(
                    label = flashLabel,
                    on = flashMode != ImageCapture.FLASH_MODE_OFF,
                    enabled = !capturing,
                    onClick = { cycleFlash() },
                    modifier = Modifier.testTag("cam_flash"),
                )
                if (!simpleUi) AppBarCell(
                    label = gridLabel,
                    on = gridMode > 0,
                    onClick = { cycleGrid() },
                    modifier = Modifier.testTag("cam_grid"),
                )
                // PRO 只用一颗格子进顶栏：本机若一项手动都不支持，它自己是灰的，
                // 但仍画出来——消失的控件在用户眼里与"坏了"没有区别（见 CameraProPanel 顶部）。
                // `toggle()` 之后**必须** applyProNow()：只翻状态不下发，session 上残留的
                // SENSOR_SENSITIVITY 会让下一张仍按上次的 ISO 曝光（P0-1）。
                if (!simpleUi) CamProToggleCell(
                    pro = pro.mode,
                    availability = pro.avail,
                    onToggle = {
                        pro.toggle()
                        applyProNow("专业参数没能下发，已回到自动")
                    },
                )
                Spacer(Modifier.weight(1f))
                AppBarCell(
                    label = "设置",
                    on = camSheet == 1,
                    onClick = { camSheet = 1 },
                    modifier = Modifier.testTag("cam_more"),
                )
            }
            if (!simpleUi) {
                // 状态行用等宽小字：它就是印样页边上那行编号（状态 · 输出 · 今日 · 变焦）。
                // remember：这一层现在还会被变焦/网格/抽屉等状态带着重组，四个字符串模板 + 一次 join
                // 没必要每帧重来。`exposureNote` 也进键——它是拍完才出现的那半句。
                val meta = remember(status, captureSize, savedSize, todayN, zoomLabel, exposureNote) {
                    buildList {
                        add(status)
                        // 拍完那一句「请求 ISO 400 · 实际 800」跟在状态后面：它是同一件事的
                        // 实测值，不该另起一行去和「已保存」抢读者的注意力。
                        if (exposureNote.isNotBlank()) add(exposureNote)
                        // 「已保存」后面跟的必须是落盘那一张；没重写过才退回相机的输出档
                        val out = savedSize.ifBlank { captureSize }
                        if (out.isNotBlank()) add(out)
                        if (todayN > 0) add("今日 ${todayN}张")
                        if (zoomLabel.isNotBlank()) add(zoomLabel)
                    }.joinToString("  ·  ")
                }
                Text(
                    meta,
                    style = SuiPaiSheet.type.frameNo,
                    color = CamIvory.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = AppBarHeight + 4.dp, start = 12.dp, end = 12.dp)
                        // 这一行是取景时唯一会自己变的字（对焦结果 / 已保存 / 连拍 3/8）。
                        // 普通 Text 原地改文字只发 WINDOW_CONTENT_CHANGED，读屏不主动念；
                        // 标成 liveRegion 之后才听得见状态，否则这套反馈只剩预览上一闪而过的框。
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else {
            // 锁定界面只留出口，不留死角：一颗关闭、一颗解锁。
            AppBarRow(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .background(CamChromeScrim),
            ) {
                AppBarCell(label = "关闭", on = false, onClick = onClose)
                Spacer(Modifier.weight(1f))
                AppBarCell(
                    label = "界面已锁定",
                    on = true,
                    onClick = { uiLocked = false },
                    modifier = Modifier.testTag("cam_unlock"),
                )
            }
        }
        if (camSheet == 1) {
            AppSheet(title = "拍摄设置", tag = "cam_sheet", onDismiss = { camSheet = 0 }) {
                AppSheetSection("这一趟取景", "不写进设置的临时动作")
                AppChipFlow {
                    AppBarCell(label = "系统相机", on = false, onClick = onSystemCamera)
                    // 对焦与曝光锁原来只有预览上的手势这一条路：读屏用户点得到格子，
                    // 但触发不了「单击对焦 / 长按锁定」。这里把同一个动作补成两颗格子。
                    AppBarCell(
                        label = "对焦中央",
                        on = false,
                        enabled = camera != null,
                        onClick = {
                            centerOfPreview()?.let { focusAe(it.x, it.y, lock = false) }
                        },
                        modifier = Modifier.testTag("cam_focus_center"),
                    )
                    AppBarCell(
                        label = if (aeLocked) "解除锁定" else "锁定对焦",
                        on = aeLocked,
                        enabled = camera != null,
                        onClick = {
                            if (aeLocked) {
                                releaseFocusAe()
                            } else {
                                centerOfPreview()?.let { focusAe(it.x, it.y, lock = true) }
                            }
                        },
                        modifier = Modifier.testTag("cam_ae_lock"),
                    )
                    AppBarCell(
                        label = "锁定界面",
                        on = false,
                        onClick = {
                            uiLocked = true
                            camSheet = 0
                        },
                    )
                    AppBarCell(
                        label = if (torchOn) "手电 开" else "手电 关",
                        on = torchOn,
                        enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                        onClick = {
                            val next = !torchOn
                            torchOn = next
                            camera?.cameraControl?.enableTorch(next)
                        },
                    )
                }
                // ── 专业模式 ─────────────────────────────────────────────
                // 说明行由 `describe()` 一处生成（见 CameraProState.kt）：它同时是读屏念的那句，
                // 于是"屏幕上写的"与"语义树里说的"不可能分叉。
                AppSheetSection("专业模式", pro.line())
                AppChipFlow {
                    AppBarCell(
                        label = if (pro.mode.enabled) "PRO 开" else "PRO 关",
                        on = pro.mode.enabled,
                        enabled = pro.avail.any,
                        // 与顶栏那颗共用 applyProNow()（P0-1）。这里是第二个漏掉的入口：
                        // 只翻状态的话，用户选过 ISO 1600 再点「PRO 关」，session 上那条
                        // SENSOR_SENSITIVITY 不会被清掉，下一张仍按 1600 曝光。
                        onClick = {
                            pro.toggle()
                            applyProNow("专业参数没能下发，已回到自动")
                        },
                        modifier = Modifier.testTag("cam_pro_sheet"),
                    )
                    if (pro.mode.enabled) {
                        ProSlot.entries.forEach { slot ->
                            val enabled = when (slot) {
                                ProSlot.ISO -> pro.avail.iso
                                ProSlot.SHUTTER -> pro.avail.shutter
                                ProSlot.EV -> pro.avail.ev
                                ProSlot.WB -> pro.avail.wb
                                ProSlot.FOCUS -> pro.avail.focus
                            }
                            AppBarCell(
                                label = when (slot) {
                                    ProSlot.ISO -> pro.mode.iso?.let { ProTables.isoLabel(it) } ?: "ISO 自动"
                                    ProSlot.SHUTTER -> pro.mode.shutterNs
                                        ?.let { ProTables.shutterLabel(it) } ?: "快门 自动"
                                    ProSlot.EV -> ProTables.evLabel(pro.mode.ev)
                                    ProSlot.WB -> pro.mode.wbKelvin?.let { "${it}K" } ?: "白平衡 自动"
                                    ProSlot.FOCUS -> pro.mode.focusDiopters
                                        ?.let { "MF ${ProTables.focusLabel(it)}" } ?: "对焦 自动"
                                },
                                on = when (slot) {
                                    ProSlot.ISO -> pro.mode.iso != null
                                    ProSlot.SHUTTER -> pro.mode.shutterNs != null
                                    ProSlot.EV -> pro.mode.ev != 0
                                    ProSlot.WB -> pro.mode.wbKelvin != null
                                    ProSlot.FOCUS -> pro.mode.focusDiopters != null
                                },
                                enabled = enabled,
                                onClick = {
                                    camSheet = 0
                                    proSlot = slot
                                },
                                // 这五格的**标签会随值变**（`ISO 自动` → `ISO 400`），所以自动化
                                // 没法按文字定位；给一个稳定的 tag，AVD / tools/verify.ps1 才能取证。
                                // 底部那排 chips 已有 `cam_pro_chip_*`，那是另一套节点，不动。
                                modifier = Modifier.testTag("cam_pro_slot_${slot.name.lowercase()}"),
                            )
                        }
                    }
                    // 直方图的开关**只在 PRO 开着时出现**：它不是一条拍摄偏好，
                    // 而是"这一趟取景要看波形"的临时动作，与上面「这一趟取景」同性质。
                    if (pro.mode.enabled) {
                        AppBarCell(
                            label = if (showHist) "直方图 开" else "直方图 关",
                            on = showHist,
                            enabled = pro.histogramNote.isBlank(),
                            onClick = { showHist = !showHist },
                            modifier = Modifier.testTag("cam_hist"),
                        )
                    }
                }
                if (pro.mode.enabled && pro.histogramNote.isNotBlank() && showHist) {
                    Text(
                        pro.histogramNote,
                        style = SuiPaiSheet.type.label,
                        color = CamIvory.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                CameraFlagGroup.values().forEach { group ->
                    val flags = CameraFlag.inGroup(group).filter { it.shown(cam) }
                    AppSheetSection(group.title, group.hint)
                    AppChipFlow {
                        when (group) {
                            CameraFlagGroup.VIEW -> {
                                AppBarCell(
                                    label = gridLabel,
                                    on = gridMode > 0,
                                    onClick = { cycleGrid() },
                                )
                                AppBarCell(
                                    label = levelTolLabel,
                                    on = levelTol != 1,
                                    onClick = {
                                        levelTol = (levelTol + 1) % 3
                                        setInt("level_tol", levelTol)
                                    },
                                )
                            }
                            CameraFlagGroup.EXPOSURE -> {
                                AppBarCell(
                                    label = "EV −",
                                    on = false,
                                    onClick = {
                                        nudgeEv(camera, -1)
                                        evLabel = evText(camera)
                                    },
                                )
                                AppBarCell(
                                    label = evLabel,
                                    on = evLabel != "EV 0",
                                    onClick = {
                                        resetEv(camera)
                                        evLabel = evText(camera)
                                    },
                                )
                                AppBarCell(
                                    label = "EV +",
                                    on = false,
                                    onClick = {
                                        nudgeEv(camera, 1)
                                        evLabel = evText(camera)
                                    },
                                )
                            }
                            CameraFlagGroup.RHYTHM -> {
                                AppBarCell(
                                    label = intervalLabel,
                                    on = intervalSec != 0,
                                    enabled = !capturing,
                                    onClick = { cycleInterval() },
                                )
                                AppBarCell(
                                    label = "连拍 ${burstN}张",
                                    on = burstN != 3,
                                    enabled = !capturing && countdown == 0,
                                    onClick = { cycleBurstN() },
                                )
                                AppBarCell(
                                    label = burstGapLabel,
                                    on = burstGap == 400,
                                    onClick = {
                                        burstGap = if (burstGap == 240) 400 else 240
                                        setInt("burst_gap", burstGap)
                                    },
                                )
                                AppBarCell(
                                    label = timerLabel,
                                    on = timerSec != 0,
                                    enabled = !capturing,
                                    onClick = { cycleTimer() },
                                )
                                if (reviewOn) AppBarCell(
                                    label = reviewMsLabel,
                                    on = reviewMs == 2800,
                                    onClick = {
                                        reviewMs = if (reviewMs == 1600) 2800 else 1600
                                        setInt("review_ms", reviewMs)
                                    },
                                )
                                AppBarCell(
                                    label = "画质 $jpegQ",
                                    on = jpegQ >= 100,
                                    onClick = {
                                        jpegQ = if (jpegQ >= 100) 95 else 100
                                        setInt("camera_jpeg", jpegQ)
                                    },
                                )
                            }
                            CameraFlagGroup.OUTPUT -> {
                                if (dateStamp) {
                                    AppBarCell(
                                        label = stampCornerLabel,
                                        on = stampCorner != 0,
                                        onClick = {
                                            stampCorner = (stampCorner + 1) % 4
                                            setInt("stamp_corner", stampCorner)
                                        },
                                    )
                                    AppBarCell(
                                        label = stampStyleLabel,
                                        on = stampStyle != 0,
                                        onClick = {
                                            stampStyle = (stampStyle + 1) % 3
                                            setInt("stamp_style", stampStyle)
                                        },
                                    )
                                    AppBarCell(
                                        label = stampScaleLabel,
                                        on = stampScale != 1,
                                        onClick = {
                                            stampScale = (stampScale + 1) % 3
                                            setInt("stamp_scale", stampScale)
                                        },
                                    )
                                }
                            }
                        }
                        flags.forEach { f ->
                            AppBarCell(
                                label = cam.labelOf(f),
                                on = f.get(cam),
                                onClick = {
                                    // 自拍延时不是纯开关：勾上还要顺手给 3 秒，见 cycleSelfieTimer。
                                    if (f == CameraFlag.SELFIE_TIMER) cam.cycleSelfieTimer() else cam.toggle(f)
                                },
                            )
                        }
                    }
                }
            }
        }
        // 档位抽屉：与拍摄设置抽屉**互斥**（上面每一处打开 camSheet 的地方都写 camSheet = 1，
        // 而这里只在 proSlot != null 时组合）。选完一档立刻把计划下发，失败就地降级成
        // "本机不支持"——绝不留一颗亮着却没生效的格子（见 CameraProBindings.apply 的注释）。
        proSlot?.let { slot ->
            CamProSheet(
                slot = slot,
                pro = pro.mode,
                caps = pro.caps,
                availability = pro.avail,
                onDismiss = { proSlot = null },
                onPick = { next ->
                    pro.update(next)
                    proSlot = null
                    // 第三个入口，同样走 applyProNow()（P0-1 的共用出口）。
                    // 失败措辞与绑相机那次不同：用户刚刚点的这一档没吃下去。
                    applyProNow("这一档没能下发，已回到自动")
                },
            )
        }
        if (intervalRunning) {
            Text(
                // 这一行自己会出现/消失，而 Compose 1.7.6 对普通 Text 只发
                // WINDOW_CONTENT_CHANGED：不标活区，TalkBack 用户不知道相机正在连拍。
                "间隔拍摄中  · 再按快门停止",
                color = CamIvory,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 96.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (countdown > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(6.dp, CamRecordRed.copy(alpha = 0.9f)),
            )
            Text(
                countdown.toString(),
                color = CamIvory,
                fontSize = 72.sp,
                // 倒计时每少一秒换一个数字，是这一屏里最需要"没人碰也会播"的信息。
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        if (!simpleUi && !uiLocked && maxZoom > 1.15f) {
            Slider(
                value = linearZoom,
                onValueChange = { z ->
                    linearZoom = z
                    camera?.cameraControl?.setLinearZoom(z)
                    val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    zoomRatio = ratio
                    setFloat("zoom_ratio", ratio)
                    zoomLabel = if (ratio <= 1.05f) "" else "${"%.1f".format(ratio)}×"
                },
                // 调用方的 modifier 与 sliderSemantics 落在同一个节点上（material3 1.3.1
                // Slider.kt:686-695），所以这里给名字有效；不给，TalkBack 只报一根没名字的
                // "滑动条"，值还是百分数而不是熟悉的那几档。
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 56.dp)
                    .padding(bottom = 148.dp)
                    .semantics { contentDescription = if (zoomLabel.isEmpty()) "变焦 1×" else "变焦 $zoomLabel" },
            )
        }
        if (!simpleUi && !uiLocked) {
            // 变焦档位：选中哪一档就描琥珀框，不再靠字变红——这套界面只用一种方式说「当前」。
            val minZ = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
            val maxZ = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            val stops = buildList {
                if (minZ < 0.92f) add(minZ)
                add(1f)
                if (maxZ >= 1.8f) add(2f)
                if (maxZ >= 2.8f) add(3f)
                if (maxZ >= 3.8f) add(4f)
                if (maxZ >= 4.8f) add(5f)
            }
            AppBarRow(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp),
            ) {
                stops.forEach { z ->
                    if (z <= maxZ + 0.05f) {
                        val label = if (z < 0.95f) "${"%.1f".format(z)}×" else "${z.toInt()}×"
                        AppBarCell(
                            label = label,
                            on = kotlin.math.abs(zoomRatio - z) < 0.12f,
                            onClick = {
                                camera?.cameraControl?.setZoomRatio(z)
                                zoomRatio = z
                                linearZoom = camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f
                                setFloat("zoom_ratio", z)
                                zoomLabel = if (z <= 1.05f) "" else label
                            },
                            modifier = Modifier.testTag("cam_zoom_$label"),
                        )
                    }
                }
            }
        }
        // ── 专业模式的当前值那一排 ─────────────────────────────────────
        // 位置在变焦档之上（变焦档 bottom=100、变焦滑杆 bottom=148），压在 196：
        // 三排不重叠，而且都在拇指区。仅在 PRO 开着时出现——关着时它一排空标签只会占地方。
        if (pro.mode.enabled && !uiLocked) {
            CamProChips(
                pro = pro.mode,
                availability = pro.avail,
                onOpen = { camSheet = 0; proSlot = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 196.dp),
            )
        }
        // ── 直方图（右下角）────────────────────────────────────────────
        // 条件：PRO 开着 + 开关开着 + 至少拿到过一帧。`pro.histogram` 是 FloatArray(0) 时
        // 什么都不画（不是画一个空框——空框会被当成"直方图坏了"）。
        if (pro.mode.enabled && showHist && pro.histogram.isNotEmpty() && !uiLocked) {
            Canvas(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 268.dp)
                    .size(width = 96.dp, height = 40.dp)
                    .background(CamInk.copy(alpha = 0.45f))
                    .border(1.dp, CamIvory.copy(alpha = 0.35f))
                    // 它是**图**，不是控件：读屏要一句话说清它是什么，否则一个无名 Canvas
                    // 在语义树里是个不可读的空白节点。
                    .semantics { contentDescription = "亮度直方图" },
            ) {
                val bars = pro.histogram
                val barW = size.width / bars.size.coerceAtLeast(1)
                bars.forEachIndexed { i, v ->
                    val h = size.height * v
                    if (h <= 0f) return@forEachIndexed
                    drawRect(
                        color = CamIvory.copy(alpha = 0.82f),
                        topLeft = Offset(i * barW, size.height - h),
                        size = Size(barW * 0.8f, h),
                    )
                }
            }
        }
        // 「未水平」那一行单独成一个叶子 composable：`rollDeg` 是传感器回调写的
        // （SENSOR_DELAY_UI ≈ 每 16~60 ms 一次）。读它的那一层是**整个相机浮层**——
        // 预览、六道 Canvas、顶部工具条、变焦档、快门条全在里面，
        // 就地读一次等于让手机端着不动时也以 20~60 Hz 重组整屏。
        // （水平仪那根针本来就是对的：它读在 Canvas 的 draw lambda 里，不触发重组。）
        LevelWarning(cam, tiltLimit)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shot = lastShot
            if (shot != null) {
                AsyncImage(
                    model = shot,
                    contentDescription = "刚拍的照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(SuiPaiSheet.frame)
                        .border(1.dp, SuiPaiSheet.colors.hairline, SuiPaiSheet.frame)
                        .clickable(role = Role.Button, onClickLabel = "回看刚拍的那张", onClick = { onReview(shot) }),
                )
            } else {
                // 没得回看也占住这一格：快门的位置永远不动，摸黑也按得准。
                Box(Modifier.size(60.dp))
            }
            ShutterFab(
                onClick = { shoot() },
                onClickLabel = "拍照",
                onLongClick = { burst() },
                onLongClickLabel = "长按连拍",
                enabled = (!capturing && imageCapture != null) || countdown > 0,
                modifier = Modifier.testTag("cam_shutter"),
            )
            AppBarCell(
                label = "翻转",
                on = lensFacing == CameraSelector.LENS_FACING_FRONT,
                enabled = !capturing,
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    setInt("lens_facing", lensFacing)
                },
                modifier = Modifier.testTag("cam_flip"),
            )
        }
    }  // Box
    }  // CameraOverlay
    }  // with (cam)
}

/** 权限被「永久拒绝」之后系统不再弹框，只能把用户送到应用设置页。 */
private fun openAppSettings(context: Context, tell: (String) -> Unit) {
    val intent = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    sendOrTell(context, intent, "打开本应用的设置页", tell)
}

private fun buzz(context: Context) {
    runCatching {
        // minSdk 26，所以原来那个 SDK_INT >= 26 分支永远为真、else 是死代码。
        // VIBRATOR_SERVICE 在 31 起废弃，但 26–30 没有别的入口，那一支的 @Suppress 是必要的。
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(36, 90))
    }
}

private fun displayRotation(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= 30) {
        context.display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context as? Activity)?.windowManager?.defaultDisplay?.rotation
            ?: android.view.Surface.ROTATION_0
    }
}

private fun resetEv(camera: Camera?) {
    val cam = camera ?: return
    val state = cam.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    cam.cameraControl.setExposureCompensationIndex(0)
}

private fun nudgeEv(camera: Camera?, delta: Int) {
    val cam = camera ?: return
    val state = cam.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val next = (state.exposureCompensationIndex + delta)
        .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
    cam.cameraControl.setExposureCompensationIndex(next)
}

private fun evText(camera: Camera?): String {
    val cam = camera ?: return "EV 0"
    val state = cam.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return "EV —"
    val step = state.exposureCompensationStep
    val den = step.denominator.coerceAtLeast(1)
    val ev = state.exposureCompensationIndex * step.numerator.toFloat() / den
    return if (kotlin.math.abs(ev) < 0.05f) "EV 0" else "EV ${"%+.1f".format(ev)}"
}

private suspend fun takeAndSave(context: Context, capture: ImageCapture): Uri? {
    val name = "SuiPai_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/SuiPai",
            )
        }
    }
    val options = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values,
    ).build()
    return suspendCoroutine { cont ->
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    cont.resume(outputFileResults.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }
}

/**
 * 回读刚存那张 JPEG 里**实际**用的 ISO 与快门（纳秒）。读不到就是 `null`，不猜。
 *
 * 为什么这一条值得做（P1-6）：这是 AVD 上唯一能判定"PRO 的计划到底有没有真下发"的证据来源。
 * 界面上「PRO 开 / ISO 400」两句话都可以是我们自己状态袋里的事实，而 session 上真正生效的
 * 是什么只有照片的 EXIF 说得清——P0-1 那个附件 bug 正是"界面说关了、照片按 1600 曝"。
 *
 * 三条纪律：
 * 1. **必须跑在 IO 线程**，并且在后处理重写之前读：`Bitmap.compress` 不写 EXIF。
 * 2. 走 [retryOnIo]：CameraX 刚写完的那一行 MediaStore 偶尔还不许重开（实测约 1/10）。
 * 3. 三个候选标签按 EXIF 版本的先后顺序试：2.3 的 `PhotographicSensitivity`、
 *    2.2 的 `ISOSpeedRatings`、以及少数 HAL 只写的 `ISOSpeed`。全读不到 → null。
 */
private suspend fun readSavedExposure(context: Context, uri: Uri): Pair<Int?, Long?> = retryOnIo {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val exif = androidx.exifinterface.media.ExifInterface(stream)
        val iso = listOf(
            androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS,
            androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED,
        ).firstNotNullOfOrNull { tag ->
            exif.getAttributeInt(tag, 0).takeIf { it > 0 }
        }
        // EXIF 的 ExposureTime 是秒（"1/125" 这种分数也由库换算成小数），换成纳秒与
        // SENSOR_EXPOSURE_TIME 同口径。<= 0 当没写。
        val ns = exif
            .getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, -1.0)
            .takeIf { it > 0.0 }
            ?.let { Math.round(it * 1_000_000_000.0) }
            ?.takeIf { it > 0L }
        iso to ns
    }
} ?: (null to null)

private suspend fun postProcessCapture(
    context: Context,
    uri: Uri,
    cropAspect: Float,
    mirror: Boolean,
    stamp: Boolean = false,
    stampCorner: Int = 0,
    stampStyle: Int = 0,
    stampScale: Int = 1,
    stampAlpha: Boolean = false,
    onSize: suspend (Int, Int) -> Unit = { _, _ -> },
): Uri {
    if (cropAspect <= 0f && !mirror && !stamp) return uri
    // **先按 EXIF 摆正，再裁**。裸 `decodeStream` 不吃 EXIF：竖着拍的那张，像素是躺着的
    // （1856×1392 + orientation=6），在它上面按列裁、写回去时又不再带 EXIF——
    // 于是凡是走过重写的照片（16:9 / 9:16 / 4:5 / 水印 / 镜像…）都横躺 90°。
    // 装机量的证据：同一场景，未重写的 4:3 亮部在上（251/下 160），重写过的亮部跑到了左边
    // （左 223/上 161）；而 9:16 落盘 783×1392 恰好等于把**未旋转**那一帧按列裁出来的尺寸。
    // 这里复用 `ImageLoader.decode`——它本来就在做 EXIF 摆正，2/4/5/7 那几种镜像也认。
    //
    // 另外：CameraX 刚写完的那一行 MediaStore 偶尔不许我们立刻重开（实测 20 次里 2 次），
    // 老写法 `?: return uri` 会把**没裁过**的原帧静默交给用户，格子还亮着。
    // 所以重试，再失败就把话说到状态行上，不许静默撒谎。
    val src = retryOnIo { ImageLoader.decode(context, uri, POST_PROCESS_MAX_EDGE).first }
        ?: error("照片已存，但画幅/水印没能加上（读不到刚存的那一张）")
    var working = src
    if (cropAspect > 0f) {
        // 一个数说了算。以前这里是八个布尔加一条 when 级联，而 `else -> 9f / 16f` 那支
        // 永远轮不到「16:9」这一档——它没有自己的布尔，于是那一档根本不裁，
        // 全靠相机去开一条 16:9 的流（那条流在实测里只有 720×405）。
        val cropped = cropToAspect(working, cropAspect)
        if (cropped !== working) {
            if (working !== src) working.recycle()
            working = cropped
        }
        if (working !== src && !src.isRecycled) src.recycle()
    }
    if (mirror) {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
        if (flipped !== working) working.recycle()
        working = flipped
    }
    if (stamp) {
        val marked = stampDate(working, stampCorner, stampStyle, stampScale, stampAlpha)
        if (marked !== working && working !== src) working.recycle()
        working = marked
    }
    // 先把要写的 JPEG 压到内存里，压成了才动那个 uri。
    // 原来的写法是 `out.use { working.compress(...) }`：`openOutputStream(uri, "wt")`
    // 一开就把文件截成 0 字节，而 `compress` 返回的是 Boolean —— 结果被丢掉，
    // 于是内存不够 / 盘满 / 写坏时那一行 MediaStore 就是一张坏图，界面还说「已保存」。
    val bytes = java.io.ByteArrayOutputStream().use { buf ->
        if (!working.compress(Bitmap.CompressFormat.JPEG, 95, buf)) {
            val keep = if (working !== src) src else null
            if (keep != null && !keep.isRecycled) keep.recycle()
            if (!working.isRecycled) working.recycle()
            error("这张压不回 JPEG（原图已经不在，别当它存好了）")
        }
        buf.toByteArray()
    }
    val out = retryOnIo {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.openOutputStream(uri, "wt")
            } else {
                context.contentResolver.openOutputStream(uri)
            }
        }.getOrNull()
    } ?: error("照片已存成原图，但画幅/水印写不回去（存储区不让重写刚存的那张）")
    out.use { it.write(bytes) }
    val outW = working.width
    val outH = working.height
    if (!working.isRecycled) working.recycle()
    // 状态行要说"落盘那一张"的尺寸，而不是相机那一档的输出：有画幅/水印时两者不是一回事
    withContext(Dispatchers.Main) { onSize(outW, outH) }
    return uri
}

/**
 * 重试几次去做一件"刚写完的文件可能还没放行"的 IO。
 * 跑在 IO 线程上（调用点在 `withContext(Dispatchers.IO)` 里），失败返回 null 就再试。
 */
private suspend fun <T : Any> retryOnIo(tries: Int = 4, waitMs: Long = 220, block: suspend () -> T?): T? {
    var left = tries
    while (left > 0) {
        val got = runCatching { block() }.getOrNull()
        if (got != null) return got
        left--
        if (left > 0) delay(waitMs)
    }
    return null
}

/**
 * 后处理解码的长边上限。`ImageLoader.decode` 超过它就会降采样——
 * 以前这里是不带任何 options 的 `decodeStream`，一台 48MP 的手机能给它 196 MB 的位图，
 * 那一发就是 OOM；现在是"最大 4096 长边"，既保住了 EXIF 方向也保住了内存。
 */
private const val POST_PROCESS_MAX_EDGE = 4096

/** 直方图桶数。64 桶在 96dp 宽的画布上每桶 1.5dp，够了；再多就只是噪声。 */
private const val HIST_BINS = 64

/**
 * 直方图采样步长（横竖各跳这么多像素）。
 *
 * 8 是**量出来选的**，不是拍的：640×480 的 Y 平面有 307200 个点，步长 8 只读 4800 个；
 * 步长 4 读到 19200 个、步长 16 只剩 1200 个（直方图开始抖动，暗部只剩几个桶有值）。
 * 8 在"形状稳定"与"每帧几十微秒"之间的拐点上，见 `docs/product/CAMERA-PRO.md` §5。
 */
private const val HIST_STEP = 8

private fun stampDate(
    src: Bitmap,
    corner: Int = 0,
    style: Int = 0,
    scale: Int = 1,
    alpha: Boolean = false,
): Bitmap {
    // 复制不出来就是画不上字。原来 `?: return src` 把这一步的失败变成"水印没了但没说"，
    // 而调用点已经在按 error 说话（同一条链上读不到 / 写不回都是这个形状）。
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
        ?: error("照片已存成原图，但水印画不上去（复制这一张时内存不够）")
    val canvas = android.graphics.Canvas(out)
    val mul = when (scale) {
        0 -> 0.72f
        2 -> 1.38f
        else -> 1f
    }
    val paint = android.graphics.Paint().apply {
        color = if (alpha) android.graphics.Color.argb(168, 255, 255, 255) else android.graphics.Color.WHITE
        textSize = (out.width.coerceAtLeast(out.height) * 0.032f * mul).coerceIn(22f, 96f)
        isAntiAlias = true
        setShadowLayer(5f, 0f, 1.5f, android.graphics.Color.BLACK)
    }
    val pattern = when (style) {
        1 -> "yyyy.MM.dd"
        2 -> "HH:mm"
        else -> "yyyy.MM.dd  HH:mm"
    }
    val text = java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA)
        .format(java.util.Date())
    val tw = paint.measureText(text)
    val pad = out.width * 0.05f
    val x = if (corner == 1 || corner == 2) out.width - pad - tw else pad
    val y = if (corner == 2 || corner == 3) paint.textSize + pad else out.height * 0.94f
    canvas.drawText(text, x, y, paint)
    return out
}


/**
 * 「未水平」那一行。**单独成一个叶子**：`cam.rollDeg` 由传感器回调写（SENSOR_DELAY_UI），
 * 读它的作用域才会跟着重组——放在浮层那一层就是每 16~60 ms 重组整屏相机界面。
 */
@Composable
private fun BoxScope.LevelWarning(cam: CameraUiState, limit: Float) {
    if (!cam.showLevel) return
    if (kotlin.math.abs(cam.rollDeg) <= limit) return
    val deg = cam.rollDeg
    Text(
        "未水平  ${if (deg >= 0f) "+" else ""}${"%.0f".format(deg)}°",
        style = SuiPaiSheet.type.frameNo,
        color = CamGold,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 176.dp),
    )
}

private fun DrawScope.gridLines(
    part: GridPart,
    paint: Color,
    stroke: Float,
    chromeTop: androidx.compose.ui.unit.Dp = 0.dp,
    chromeBottom: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val w = size.width
    val h = size.height
    when (part) {
        GridPart.THIRD -> {
            drawLine(paint, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
            drawLine(paint, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
            drawLine(paint, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
            drawLine(paint, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
        }
        GridPart.PHI -> {
            drawLine(paint, Offset(w * 0.382f, 0f), Offset(w * 0.382f, h), stroke)
            drawLine(paint, Offset(w * 0.618f, 0f), Offset(w * 0.618f, h), stroke)
            drawLine(paint, Offset(0f, h * 0.382f), Offset(w, h * 0.382f), stroke)
            drawLine(paint, Offset(0f, h * 0.618f), Offset(w, h * 0.618f), stroke)
        }
        GridPart.DIAG -> {
            drawLine(paint, Offset(0f, 0f), Offset(w, h), stroke)
            drawLine(paint, Offset(w, 0f), Offset(0f, h), stroke)
        }
        GridPart.TRI -> {
            drawLine(paint, Offset(0f, 0f), Offset(w, h), stroke)
            drawLine(paint, Offset(w, 0f), Offset(w * 0.382f, h * 0.382f), stroke)
            drawLine(paint, Offset(0f, h), Offset(w * 0.618f, h * 0.618f), stroke)
        }
        GridPart.CROSS -> {
            drawLine(paint, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
            drawLine(paint, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
        }
        GridPart.CORNER -> {
            // 角标画在「看得见的取景带」四角，不是屏幕四角。
            // 贴着屏幕角画的那一版实测等于没画（`cam_grid3.py` 的单图结构分：「四角」比「网格 关」
            // 只多 5 个亮线像素，而同一档连拍六张自己的抖动是 11）——因为顶边压在顶部渐隐
            // （0.90 alpha）底下、底边压在底部渐隐（0.88）与快门条底下。
            // 含这一条基元的预设一共 8 条，等于用户点其中 8 次都是"名字换了、画面没动"。
            val top = chromeTop.toPx()
            val bot = (h - chromeBottom.toPx()).coerceAtLeast(top + 1f)
            val side = w * 0.05f
            val arm = minOf(w * 0.10f, (bot - top) * 0.10f)
            listOf(
                Offset(side, top) to Offset(side + arm, top),
                Offset(side, top) to Offset(side, top + arm),
                Offset(w - side, top) to Offset(w - side - arm, top),
                Offset(w - side, top) to Offset(w - side, top + arm),
                Offset(side, bot) to Offset(side + arm, bot),
                Offset(side, bot) to Offset(side, bot - arm),
                Offset(w - side, bot) to Offset(w - side - arm, bot),
                Offset(w - side, bot) to Offset(w - side, bot - arm),
            ).forEach { (a, b) -> drawLine(paint, a, b, stroke) }
        }
    }
}

private fun cropToAspect(src: Bitmap, aspect: Float): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return src
    val srcAspect = w.toFloat() / h
    return if (srcAspect > aspect) {
        val nw = (h * aspect).toInt().coerceAtLeast(1).coerceAtMost(w)
        val x = (w - nw) / 2
        Bitmap.createBitmap(src, x, 0, nw, h)
    } else {
        val nh = (w / aspect).toInt().coerceAtLeast(1).coerceAtMost(h)
        val y = (h - nh) / 2
        Bitmap.createBitmap(src, 0, y, w, nh)
    }
}

/**
 * 连拍那一句话：说成功几张，不说尝试到第几张。
 * 原来只看"最后一张成没成"：1 成 4 败说「连拍失败」，4 成 1 败（败在最后）说「连拍完成」。
 */
internal fun burstStatus(ok: Int, total: Int): String = when {
    ok == 0 -> "连拍失败（$total 张一张没存上）"
    ok == total -> "连拍完成 $ok 张"
    else -> "连拍了 $ok/$total 张，其余没存上"
}

/** 间隔拍摄的状态行：坏了几张要数得出来，不能永远写着「间隔 N秒」。 */
internal fun intervalStatus(sec: Int, misses: Int): String =
    if (misses == 0) "间隔 ${sec}秒" else "间隔 ${sec}秒（$misses 张没存上）"

/**
 * 一次取景动作的四种结局。`focusAe` / `releaseFocusAe` / `centerOfPreview` 三条路都收到这张表上，
 * 于是"实际做了什么"与"说了什么"之间只剩 `focusWords` 一个映射 —— 那一处就能穷举测真假。
 */
internal enum class FocusResult { FOCUSED, LOCKED, RELEASED, NOT_READY }

/**
 * 九宫格：`focusRegion` 返回值的那九种说法。
 * 顺序 = 上中下三行、每行左中右——与水印角标那四个词（左下/右下/右上/左上）是同一套方位词。
 */
private val FocusCells = listOf(
    "左上", "上中", "右上",
    "左中", "中央", "右中",
    "左下", "下中", "右下",
)

/**
 * 焦点落在取景画面的哪一块。
 *
 * 看得见的人在预览上看那个框，听的人只有这一句 —— 对焦的**位置**本来就是这一次动作里
 * 唯一有信息量的部分（"对焦中央"那颗格子点的也是这里）。
 * 预览还没量出尺寸（`w`/`h` <= 0）时返回空串，`focusWords` 会把那句缩成不带位置。
 */
internal fun focusRegion(x: Float, y: Float, w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return ""
    val col = when {
        x < w / 3f -> 0
        x < w * 2f / 3f -> 1
        else -> 2
    }
    val row = when {
        y < h / 3f -> 0
        y < h * 2f / 3f -> 1
        else -> 2
    }
    return FocusCells[row * 3 + col]
}

/**
 * 对焦/锁定的结果怎么说。全项目只有这一处写这句文案，所以"话对不对得起实况"能在 JVM 里逐条穷举
 * （`CameraFocusFeedbackTest`），而界面里那三处只许调用它。
 *
 * 三条真值，都是装机量到的：
 *  · 没锁的那一次绝不许出现"锁定"二字。旧文案写「对焦锁定」，可那一支走的是
 *    `setAutoCancelDuration(3, TimeUnit.SECONDS)` —— 三秒后 CameraX 自己就取消了，
 *    这句话在它挂出来的当下就开始假；而读屏用户摸到状态行时它其实早被擦掉了（错两回）。
 *  · 锁着的那一次必须说"锁定"：这是唯一一件会一直生效下去的事，不说人不知道自己松不了手。
 *  · 位置要说，`解除` 要说：这两件都是原来只剩"绿字出现/绿字消失"、听不到的状态变化。
 */
internal fun focusWords(result: FocusResult, region: String): String {
    val where = if (region.isEmpty()) "" else " $region"
    return when (result) {
        FocusResult.FOCUSED -> "已对焦$where"
        FocusResult.LOCKED -> "已锁定对焦$where"
        FocusResult.RELEASED -> "已解除对焦锁定"
        FocusResult.NOT_READY -> "预览还没出来，对不上焦"
    }
}
