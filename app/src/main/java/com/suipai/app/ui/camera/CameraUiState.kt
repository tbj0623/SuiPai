package com.suipai.app.ui.camera

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.suipai.app.settings.settingsBool
import com.suipai.app.settings.settingsInt
import kotlinx.coroutines.Job

/**
 * CameraScreen 的全部可变状态：从那个 1600 行的 @Composable 里搬出来的 67 个委托属性。
 *
 * 为什么要搬：一整个函数体里堆 68 个 `by remember`，方法的字段表/常量池会大到 ART
 * 在类加载阶段直接 VerifyError——编译期与 lint 都不报，只在真机上打不开相机。
 * 一瞬（58→40）和相册（50→23）两处就是这么治的，这里一次收干净。
 *
 * 语义与原来逐字对应：每个属性仍是各自独立的 mutableStateOf（细粒度订阅不变），
 * 落盘 key 与默认值一字未改；rememberSaveable 换成普通属性没有损失——这些值本来
 * 就在改动当下写回 prefs，而相机页切走时整个 composable 也会离开组合。
 *
 * 调用点不做改写：CameraScreen 用内联的 `with(cam) { ... }` 把本类当隐式接收者，
 * 所以 `square = ratioSquare` 这类具名实参与普通读写全部保持原样。
 */
class CameraUiState(val context: Context, val prefs: SharedPreferences) {
    /**
     * 设置中心那一层的读写口。相机这五项（画质 / 辅助线 / 快门声 / 前置镜像 / 极简取景）
     * 从这一版起**读 `cfg_`**，老键只在"从没写过 cfg"时兜底（升级迁移），
     * 而每一次改动都两边一起写 —— 降级安装要认。理由见 `settings/SettingsBridge.kt` 的文件头。
     */
    private val store = com.suipai.app.settings.SettingsStore(prefs)
    val shotDay = java.time.LocalDate.now().toString()
    var lensFacing by mutableIntStateOf(prefs.getInt("lens_facing", CameraSelector.LENS_FACING_BACK))
    var status by mutableStateOf("准备相机")
    var imageCapture by mutableStateOf<ImageCapture?>(null)
    var camera by mutableStateOf<Camera?>(null)
    var capturing by mutableStateOf(false)
    var flashMode by mutableIntStateOf(prefs.getInt("flash_mode", ImageCapture.FLASH_MODE_OFF))
    var torchOn by mutableStateOf(false)
    var zoomLabel by mutableStateOf("")
    // `grid_mode` 是一串 20 款网格样式的编号，「构图辅助线」只管开/关，所以：
    // 关 → 0；开 → 沿用相机自己记着的那一款（存的不是正数才退到 1 = 九宫格）。
    // 读法：cfg 存过就按 cfg 的开/关，没存过就看老键 `grid_mode > 0` —— 老用户挑的那一款不丢。
    var gridMode by mutableIntStateOf(
        com.suipai.app.settings.SettingsBridge.gridModeInit(
            prefs.settingsBool(
                com.suipai.app.settings.SettingsKeys.Camera.showGrid,
                com.suipai.app.settings.SettingsBridge.showGridOf(prefs.getInt("grid_mode", 1)),
            ),
            prefs.getInt("grid_mode", 1),
        ),
    )
    var gridBold by mutableStateOf(prefs.getBoolean("grid_bold", false))
    var gridGold by mutableStateOf(prefs.getBoolean("grid_gold", false))
    var evLabel by mutableStateOf("EV 0")
    var autoYishun by mutableStateOf(prefs.getBoolean("auto_yishun", true))
    var timerSec by mutableIntStateOf(prefs.getInt("timer_sec", 0))
    var burstN by mutableIntStateOf(
            prefs.getInt("burst_n", 3).let { if (it == 5 || it == 7 || it == 9) it else 3 },
        )
    var burstGap by mutableIntStateOf(prefs.getInt("burst_gap", 240).let { if (it == 400) 400 else 240 })
    var ratioIndex by mutableIntStateOf(CamRatio.fromPrefs(prefs).ordinal)
    val ratio: CamRatio get() = CamRatio.values()[ratioIndex]
    val ratioLabel get() = "比例 ${ratio.label}"
    /** 预览里压暗框的目标比例；0 = 这一档不裁切，不画框。 */
    val cropAspect get() = ratio.cropAspect
    /**
     * 这一档相对**传感器实际给的那一帧**到底裁不裁。压暗框与格子高亮共用这一个判断，
     * 免得两处各自解释「4:3 = 不裁」——实测这台相机会给 1656×1392（1.19:1）的帧，
     * 那时 4:3 那一档是真要裁掉一点的，格子就该亮。
     */
    val cropsNow
        get() = cropAspect > 0f &&
            (sensorAspect <= 0f || kotlin.math.abs(cropAspect - sensorAspect) > 0.02f)
    var mirrorFront by mutableStateOf(
        prefs.settingsBool(com.suipai.app.settings.SettingsKeys.Camera.mirrorFront, prefs.getBoolean("mirror_front", true)),
    )
    var simpleUi by mutableStateOf(
        prefs.settingsBool(com.suipai.app.settings.SettingsKeys.Camera.simpleUi, prefs.getBoolean("camera_simple", false)),
    )
    var jpegQ by mutableIntStateOf(
        prefs.settingsInt(com.suipai.app.settings.SettingsKeys.Camera.jpegQuality, prefs.getInt("camera_jpeg", 95))
            .coerceIn(95, 100),
    )
    var showLevel by mutableStateOf(prefs.getBoolean("show_level", true))
    var shutterSound by mutableStateOf(
        prefs.settingsBool(com.suipai.app.settings.SettingsKeys.Camera.shutterSound, prefs.getBoolean("shutter_sound", true)),
    )
    var shutterHaptic by mutableStateOf(prefs.getBoolean("shutter_haptic", true))
    var aeLocked by mutableStateOf(false)
    var aeHold by mutableStateOf(prefs.getBoolean("ae_hold", false))
    var volZoom by mutableStateOf(prefs.getBoolean("vol_zoom", false))
    var reviewOn by mutableStateOf(prefs.getBoolean("shot_review", false))
    var reviewMs by mutableIntStateOf(prefs.getInt("review_ms", 1600).let { if (it == 2800) 2800 else 1600 })
    var selfieTimer by mutableStateOf(prefs.getBoolean("selfie_timer", false))
    var intervalSec by mutableIntStateOf(prefs.getInt("interval_sec", 0).let { if (it == 2 || it == 5) it else 0 })
    var intervalJob by mutableStateOf<Job?>(null)
    var intervalRunning by mutableStateOf(false)
    var showCenter by mutableStateOf(prefs.getBoolean("show_center", false))
    var showSafe by mutableStateOf(prefs.getBoolean("show_safe", false))
    var showPhiFrame by mutableStateOf(prefs.getBoolean("show_phi_frame", false))
    var levelShutter by mutableStateOf(prefs.getBoolean("level_shutter", false))
    var levelTol by mutableIntStateOf(prefs.getInt("level_tol", 1).coerceIn(0, 2))
    var dateStamp by mutableStateOf(prefs.getBoolean("shot_stamp", false))
    var stampCorner by mutableIntStateOf(prefs.getInt("stamp_corner", 0).coerceIn(0, 3))
    var stampStyle by mutableIntStateOf(prefs.getInt("stamp_style", 0).coerceIn(0, 2))
    var stampScale by mutableIntStateOf(prefs.getInt("stamp_scale", 1).coerceIn(0, 2))
    var stampAlpha by mutableStateOf(prefs.getBoolean("stamp_alpha", false))
    var torchOff by mutableStateOf(prefs.getBoolean("torch_off", false))
    var antiShake by mutableStateOf(prefs.getBoolean("anti_shake", false))
    var linearZoom by mutableFloatStateOf(0f)
    var captureSize by mutableStateOf("")
    /**
     * 上一张**真的落到存储区里**的尺寸（裁过、加过水印之后的）。
     * 装机量到的：要 1.85 那一档拍完，状态行写「已保存 · 1920×1440」，
     * 而媒体库里那一行是 1440×778 —— `captureSize` 是相机那一档的输出，
     * 有画幅/水印时它和被写回去的那张根本不是一回事，所以另存一个数。空 = 这张没重写。
     */
    var savedSize by mutableStateOf("")
    /** 绑定之后传感器那一帧的真实宽高比；0 = 还没量到。预览压暗框用它决定要不要画。 */
    var sensorAspect by mutableFloatStateOf(0f)
    var uiLocked by mutableStateOf(false)
    var reviewUri by mutableStateOf<Uri?>(null)
    var todayN by mutableIntStateOf(
            if (prefs.getString("shot_day", "") == shotDay) prefs.getInt("shot_n", 0) else 0,
        )
    var modeLabel by mutableStateOf("标准")
    var countdown by mutableIntStateOf(0)
    /** 0 = 没抽屉；1 = 拍摄设置抽屉开着。和相册/一瞬一样只有一层弹层。 */
    var camSheet by mutableIntStateOf(0)
    var timerJob by mutableStateOf<Job?>(null)
    var lastShot by mutableStateOf<Uri?>(null)
    var focusAt by mutableStateOf<Offset?>(null)
    var zoomRatio by mutableFloatStateOf(prefs.getFloat("zoom_ratio", 1f))
    var rollDeg by mutableFloatStateOf(0f)
    var shutterFlash by mutableStateOf(false)
    var previewFill by mutableStateOf(prefs.getBoolean("camera_fill", true))

    /** 点一次走一格。九个老布尔一起写回，老版本降级后仍读得懂这个设置。 */
    fun cycleRatio(): CamRatio {
        val all = CamRatio.values()
        val next = all[(ratioIndex + 1) % all.size]
        ratioIndex = next.ordinal
        prefs.edit()
            .putInt("ratio_idx", next.ordinal)
            .putBoolean("ratio_43", next.sensor43)
            .putBoolean("ratio_square", next.square)
            .putBoolean("ratio_32", next.r32)
            .putBoolean("ratio_916", next.r916)
            .putBoolean("ratio_45", next.r45)
            .putBoolean("ratio_239", next.r239)
            .putBoolean("ratio_185", next.r185)
            .putBoolean("ratio_53", next.r53)
            .putBoolean("ratio_85", next.r85)
            .apply()
        return next
    }

    /** 前摄把「闪光灯」说成「补光」：屏幕上就是屏闪，叫闪光灯会让人去找氙气灯。 */
    val flashLabel: String
        get() {
            val selfie = lensFacing == CameraSelector.LENS_FACING_FRONT
            val what = if (selfie) "补光" else "闪光灯"
            return when {
                flashMode == ImageCapture.FLASH_MODE_ON -> "$what 开"
                flashMode == ImageCapture.FLASH_MODE_AUTO -> "$what 自动"
                else -> "$what 关"
            }
        }

    val intervalLabel: String
        get() = when {
            intervalRunning -> "停止间隔"
            intervalSec == 2 -> "间隔 2秒"
            intervalSec == 5 -> "间隔 5秒"
            intervalSec == 10 -> "间隔 10秒"
            else -> "间隔 关"
        }

    val timerLabel: String
        get() = when (timerSec) {
            2 -> "延时 2秒"
            3 -> "延时 3秒"
            5 -> "延时 5秒"
            10 -> "延时 10秒"
            else -> "延时 关"
        }

    val burstGapLabel: String get() = if (burstGap == 400) "连拍慢" else "连拍快"
    val reviewMsLabel: String get() = if (reviewMs == 2800) "回看 长" else "回看 短"
    val levelTolLabel: String
        get() = when (levelTol) {
            0 -> "水平 严"
            2 -> "水平 松"
            else -> "水平 中"
        }
    val stampCornerLabel: String
        get() = when (stampCorner) {
            1 -> "水印 右下"
            2 -> "水印 右上"
            3 -> "水印 左上"
            else -> "水印 左下"
        }
    val stampStyleLabel: String
        get() = when (stampStyle) {
            1 -> "水印 日期"
            2 -> "水印 时间"
            else -> "水印 日期时间"
        }
    val stampScaleLabel: String
        get() = when (stampScale) {
            0 -> "水印 小字"
            2 -> "水印 大字"
            else -> "水印 中字"
        }
    val gridLabel: String get() = CamGrid.at(gridMode).label

    /**
     * 落盘一个布尔开关。
     *
     * 多了一句 `syncFromLegacyBool`：相机抽屉里**同样**有「快门声 / 前置镜像 / 极简取景」
     * 这四颗开关（`CameraFlag` 表的 SHUTTER_SOUND / MIRROR_FRONT / SIMPLE_UI，以及
     * `cycleGrid` 那一颗）。用户在相机里拨了它们之后，设置页那一项必须跟着变 ——
     * 否则同一条设置会长出两个互相不认的值，而两个界面各自都"看起来正常"。
     * 没有对应设置项的老键（那 20 款网格样式等）由 `SettingsBridge` 返回 null，什么也不发生。
     */
    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        store.syncFromLegacyBool(key, value)
    }

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        // 画质（`camera_jpeg`）与网格编号（`grid_mode`）在设置页都有对应项。
        store.syncFromLegacyInt(key, value)
    }

    fun setFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    /** 开关格的统一动作：翻状态 + 落盘，界面里不再手写 21 段一样的代码。 */
    fun toggle(f: CameraFlag) {
        val v = !f.get(this)
        f.set(this, v)
        setBool(f.key, v)
    }

    fun labelOf(f: CameraFlag) = if (f.get(this)) f.labelOn else f.labelOff

    fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        setInt("flash_mode", flashMode)
        imageCapture?.flashMode = flashMode
    }

    fun cycleGrid() {
        val all = CamGrid.values()
        gridMode = (gridMode + 1) % all.size
        setInt("grid_mode", gridMode)
        setBool("show_grid", gridMode > 0)
    }

    fun cycleInterval() {
        intervalJob?.cancel()
        intervalJob = null
        intervalRunning = false
        intervalSec = when (intervalSec) {
            0 -> 2
            2 -> 5
            5 -> 10
            else -> 0
        }
        setInt("interval_sec", intervalSec)
        if (intervalSec == 0) status = modeLabel
    }

    fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 2
            2 -> 3
            3 -> 5
            5 -> 10
            else -> 0
        }
        setInt("timer_sec", timerSec)
        timerJob?.cancel()
        countdown = 0
    }

    fun cycleBurstN() {
        burstN = when (burstN) {
            3 -> 5
            5 -> 7
            7 -> 9
            else -> 3
        }
        setInt("burst_n", burstN)
    }

    /** 打开自拍延时就把延时先给 3 秒：勾了这个开关还拍出 0 秒的片子，只会让人觉得开关坏了。 */
    fun cycleSelfieTimer() {
        selfieTimer = !selfieTimer
        setBool("selfie_timer", selfieTimer)
        if (selfieTimer && lensFacing == CameraSelector.LENS_FACING_FRONT && timerSec == 0) {
            timerSec = 3
            setInt("timer_sec", 3)
        }
    }
}

/**
 * 画幅：一循环 10 格。原来是 9 个布尔互相踩，加上一段 120 行的手写级联
 * （每一格都要把其它 8 个布尔逐个赋值），改一次顺序就得重排一遍。
 * 现在只有 ratioIndex 一个真值，顺序就是下面的声明顺序。
 *
 * cropAspect = 预览里压暗框的目标比例，也是后处理真正裁的目标（0 = 不裁，铺满传感器画幅）；
 * sensor43 = 老版本写进 SharedPreferences 的 `ratio_43` 的来源，只为降级兼容留着
 * （现在相机统一走 4:3 那一路，不再按它分支）；
 * 其余八个字段就是当年的九个布尔，留着是为了让旧版本装回来还读得懂这些设置。
 */
enum class CamRatio(
    val label: String,
    val cropAspect: Float = 0f,
    val sensor43: Boolean = false,
    val square: Boolean = false,
    val r32: Boolean = false,
    val r916: Boolean = false,
    val r45: Boolean = false,
    val r239: Boolean = false,
    val r185: Boolean = false,
    val r53: Boolean = false,
    val r85: Boolean = false,
) {
    // 16:9 以前没有 cropAspect：它不裁，改由相机开一条 16:9 的流。
    // 实测那条流只有 720×405（0.29 MP），而 4:3 满幅是 1856×1392（2.6 MP），
    // 而且这条级联里 `else -> 9f / 16f` 那一支永远轮不到它，等于「16:9」这一档名不副实。
    R169("16:9", 16f / 9f),
    R11("1:1", 1f, sensor43 = true, square = true),
    R32("3:2", 3f / 2f, sensor43 = true, r32 = true),
    R916("9:16", 9f / 16f, r916 = true),
    R45("4:5", 4f / 5f, r45 = true),
    R239("2.39", 2.39f, r239 = true),
    R185("1.85", 1.85f, r185 = true),
    R53("5:3", 5f / 3f, r53 = true),
    R85("8:5", 8f / 5f, r85 = true),
    // 只有这一档不裁（0 = 铺满传感器那一帧）。
    //
    // 这里曾经写上 4/3，理由看着很硬：量到过 1656×1392 = 1.19:1 的落盘，"4:3"这个名字是假的。
    // 但那个 1.19 是**另一个根因**的表象（后处理在传感器坐标系里裁、写回又不带 EXIF，见 §13b），
    // 而写上 4/3 的代价是实测出来的：竖拍按 EXIF 摆正之后画面是 1440×1920，
    // 再裁成 4:3 只剩 1440×1080 —— 2.76 MP 变 1.56 MP，默认档白丢 44 % 像素。
    // 所以：名字要管到每一档，但"铺满传感器"这一档的语义就是不裁。
    R43("4:3", sensor43 = true),
    ;

    companion object {
        /** 新键 ratio_idx 优先；没有就按老的九个布尔还原，全默认时是 4:3。 */
        fun fromPrefs(prefs: SharedPreferences): CamRatio {
            val saved = prefs.getInt("ratio_idx", -1)
            if (saved in values().indices) return values()[saved]
            val fromLegacy = when {
                prefs.getBoolean("ratio_85", false) -> R85
                prefs.getBoolean("ratio_53", false) -> R53
                prefs.getBoolean("ratio_185", false) -> R185
                prefs.getBoolean("ratio_239", false) -> R239
                prefs.getBoolean("ratio_45", false) -> R45
                prefs.getBoolean("ratio_916", false) -> R916
                prefs.getBoolean("ratio_32", false) -> R32
                prefs.getBoolean("ratio_square", false) -> R11
                prefs.getBoolean("ratio_43", true) -> R43
                else -> R169
            }
            prefs.edit().putInt("ratio_idx", fromLegacy.ordinal).apply()
            return fromLegacy
        }
    }
}

/** 构图辅助线只有 6 种基元，24 个预设全是它们的组合（原来是 24 段手写 drawLine 级联）。 */
enum class GridPart { THIRD, PHI, DIAG, TRI, CROSS, CORNER }

/**
 * ordinal 就是存进 prefs 的 grid_mode，声明顺序 = 循环顺序。
 * **所以这一张表只许"就地换内容"或"往后追加"，不许插入、删除或重排**——
 * 那样会把用户存过的序号悄悄换成另一条辅助线。
 *
 * 24 条预设 = 6 个单基元 + 15 种两两组合 + 3 种三重组合，**两两不重画同一套线**
 * （商店文案里"24 种构图辅助线预设"数的就是它）。
 * 这一条以前不成立：`井字中点/井字十字`、`四角中点/十字四角`、`对角中点/对角十字`、
 * `黄金对角/对角黄金` 四对画出来一模一样，而 `井字+三角`、`对角+三角` 两种反而没有——
 * 名字挂着 23 条，实际只有 19 套线，用户循到那四条时看到的是"点了没反应"。
 * 守卫 `CamGridGuardTest`（重画、同名、缺组合、序号漂移都会红）。
 */
enum class CamGrid(val label: String, val parts: List<GridPart>) {
    OFF("网格 关", emptyList()),
    THIRD("九宫格", listOf(GridPart.THIRD)),
    PHI("黄金分割", listOf(GridPart.PHI)),
    DIAG("对角", listOf(GridPart.DIAG)),
    CROSS("中心点", listOf(GridPart.CROSS)),
    TRI("三角", listOf(GridPart.TRI)),
    THIRD_DIAG("井字对角", listOf(GridPart.THIRD, GridPart.DIAG)),
    CORNER("四角", listOf(GridPart.CORNER)),
    THIRD_CROSS("井字中点", listOf(GridPart.THIRD, GridPart.CROSS)),
    THIRD_PHI("井字黄金", listOf(GridPart.THIRD, GridPart.PHI)),
    CORNER_CROSS("四角中点", listOf(GridPart.CORNER, GridPart.CROSS)),
    DIAG_CROSS("对角中点", listOf(GridPart.DIAG, GridPart.CROSS)),
    THIRD_CORNER("井字四角", listOf(GridPart.THIRD, GridPart.CORNER)),
    PHI_CORNER("黄金四角", listOf(GridPart.PHI, GridPart.CORNER)),
    DIAG_CORNER("对角四角", listOf(GridPart.DIAG, GridPart.CORNER)),
    TRI_CORNER("三角四角", listOf(GridPart.TRI, GridPart.CORNER)),
    PHI_DIAG_CROSS("黄金对角中点", listOf(GridPart.PHI, GridPart.DIAG, GridPart.CROSS)),
    THIRD_DIAG_CORNER("井字对角四角", listOf(GridPart.THIRD, GridPart.DIAG, GridPart.CORNER)),
    THIRD_TRI("井字三角", listOf(GridPart.THIRD, GridPart.TRI)),
    PHI_CROSS("黄金十字", listOf(GridPart.PHI, GridPart.CROSS)),
    DIAG_TRI("对角三角", listOf(GridPart.DIAG, GridPart.TRI)),
    TRI_CROSS("三角十字", listOf(GridPart.TRI, GridPart.CROSS)),
    PHI_TRI("黄金三角", listOf(GridPart.PHI, GridPart.TRI)),
    DIAG_PHI("对角黄金", listOf(GridPart.DIAG, GridPart.PHI)),
    THIRD_PHI_CORNER("井字黄金四角", listOf(GridPart.THIRD, GridPart.PHI, GridPart.CORNER)),
    ;

    companion object {
        /**
         * 按存过的 `grid_mode` 取一档。序号来自 SharedPreferences，
         * 从更新的版本降级回来时可能比这张表还长 —— 那里是一次 `values()[i]` 越界，
         * 而它在取景 composition 里，崩了整个相机页。
         */
        fun at(mode: Int): CamGrid = values()[mode.coerceIn(0, values().lastIndex)]
    }
}

enum class CameraFlagGroup(val title: String, val hint: String) {
    VIEW("取景辅助", "辅助线只画在预览上，不进照片"),
    EXPOSURE("曝光与对焦", "点按对焦、拖动调 EV"),
    RHYTHM("快门与节奏", "延时、连拍、间隔"),
    OUTPUT("出片与水印", "拍完之后怎么处理这张"),
}

/**
 * 相机上所有「记住上次怎么设」的开关，收成一张表——和一瞬、相册两处同一做法：
 * 界面从表生成，落盘 key 与默认值一字未改。
 *
 * @param labelOff 关着时格子上写什么（说当前状态，不说动作，省得读的人猜点了会变成什么）
 * @param enabled 这一格此刻能不能点（例如前摄才谈得上镜像）；不可见的格子直接不画
 */
enum class CameraFlag(
    val key: String,
    val group: CameraFlagGroup,
    val labelOff: String,
    val labelOn: String,
    val get: (CameraUiState) -> Boolean,
    val set: (CameraUiState, Boolean) -> Unit,
    val shown: (CameraUiState) -> Boolean = { true },
) {
    SHOW_LEVEL(
        "show_level", CameraFlagGroup.VIEW, "水平仪 关", "水平仪 开",
        { it.showLevel }, { s, v -> s.showLevel = v },
    ),
    LEVEL_SHUTTER(
        "level_shutter", CameraFlagGroup.VIEW, "水平快门 关", "水平快门 开",
        { it.levelShutter }, { s, v -> s.levelShutter = v },
    ),
    ANTI_SHAKE(
        "anti_shake", CameraFlagGroup.VIEW, "防抖 关", "防抖 开",
        { it.antiShake }, { s, v -> s.antiShake = v },
    ),
    SHOW_CENTER(
        "show_center", CameraFlagGroup.VIEW, "中点 关", "中点 开",
        { it.showCenter }, { s, v -> s.showCenter = v },
    ),
    SHOW_SAFE(
        "show_safe", CameraFlagGroup.VIEW, "安全框 关", "安全框 开",
        { it.showSafe }, { s, v -> s.showSafe = v },
    ),
    SHOW_PHI(
        "show_phi_frame", CameraFlagGroup.VIEW, "黄金框 关", "黄金框 开",
        { it.showPhiFrame }, { s, v -> s.showPhiFrame = v },
    ),
    GRID_BOLD(
        "grid_bold", CameraFlagGroup.VIEW, "网格 淡", "网格 浓",
        { it.gridBold }, { s, v -> s.gridBold = v },
        shown = { it.gridMode > 0 },
    ),
    GRID_GOLD(
        "grid_gold", CameraFlagGroup.VIEW, "网格 白", "网格 金",
        { it.gridGold }, { s, v -> s.gridGold = v },
        shown = { it.gridMode > 0 },
    ),
    PREVIEW_FILL(
        "camera_fill", CameraFlagGroup.VIEW, "预览适应", "预览铺满",
        { it.previewFill }, { s, v -> s.previewFill = v },
    ),
    SIMPLE_UI(
        "camera_simple", CameraFlagGroup.VIEW, "界面 全", "界面 简",
        { it.simpleUi }, { s, v -> s.simpleUi = v },
    ),
    AE_HOLD(
        "ae_hold", CameraFlagGroup.EXPOSURE, "AE 自动", "AE 保持",
        { it.aeHold }, { s, v -> s.aeHold = v },
    ),
    VOL_ZOOM(
        "vol_zoom", CameraFlagGroup.EXPOSURE, "音量 快门", "音量 变焦",
        { it.volZoom }, { s, v -> s.volZoom = v },
    ),
    SHUTTER_SOUND(
        "shutter_sound", CameraFlagGroup.RHYTHM, "快门声 关", "快门声 开",
        { it.shutterSound }, { s, v -> s.shutterSound = v },
    ),
    SHUTTER_HAPTIC(
        "shutter_haptic", CameraFlagGroup.RHYTHM, "震动 关", "震动 开",
        { it.shutterHaptic }, { s, v -> s.shutterHaptic = v },
    ),
    REVIEW_ON(
        "shot_review", CameraFlagGroup.RHYTHM, "回看 关", "回看 开",
        { it.reviewOn }, { s, v -> s.reviewOn = v },
    ),
    DATE_STAMP(
        "shot_stamp", CameraFlagGroup.OUTPUT, "水印 关", "水印 开",
        { it.dateStamp }, { s, v -> s.dateStamp = v },
    ),
    STAMP_ALPHA(
        "stamp_alpha", CameraFlagGroup.OUTPUT, "水印 实色", "水印 半透",
        { it.stampAlpha }, { s, v -> s.stampAlpha = v },
        shown = { it.dateStamp },
    ),
    AUTO_YISHUN(
        "auto_yishun", CameraFlagGroup.OUTPUT, "拍完存图", "拍完一瞬",
        { it.autoYishun }, { s, v -> s.autoYishun = v },
    ),
    MIRROR_FRONT(
        "mirror_front", CameraFlagGroup.OUTPUT, "镜像 关", "镜像 开",
        { it.mirrorFront }, { s, v -> s.mirrorFront = v },
        shown = { it.lensFacing == CameraSelector.LENS_FACING_FRONT },
    ),
    SELFIE_TIMER(
        "selfie_timer", CameraFlagGroup.OUTPUT, "自拍延时 关", "自拍延时 开",
        { it.selfieTimer }, { s, v -> s.selfieTimer = v },
    ),
    TORCH_OFF(
        "torch_off", CameraFlagGroup.OUTPUT, "灯常开", "拍完关灯",
        { it.torchOff }, { s, v -> s.torchOff = v },
    ),
    ;

    companion object {
        /**
         * 分组表算一次。原来每次调用都 `values().filter{}` —— 那是"克隆枚举数组 + 过滤"，
         * 而抽屉打开时**每一帧**要为四个分组各做一遍（相机那一层的重组源不少）。
         */
        private val byGroup: Map<CameraFlagGroup, List<CameraFlag>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            values().filter { it.key.isNotEmpty() }.groupBy { it.group }
        }

        fun inGroup(group: CameraFlagGroup): List<CameraFlag> = byGroup[group] ?: emptyList()
    }
}
