package com.suipai.app.ui.camera

import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarRow
import com.suipai.app.ui.common.AppChipFlow
import com.suipai.app.ui.common.AppSheet
import com.suipai.app.ui.common.AppSheetSection

/**
 * PRO 的**运行期状态袋**。放这里而不是塞进 `CameraUiState`，理由与那个文件开头那段注释同源：
 * `CameraScreen` 那一层每多一个 `by remember` 委托，就离 ART 校验器的悬崖更近一步
 * （一瞬 58→40、相册 50→23 都是这么治的）。PRO 的六个状态收进一个类，
 * `CameraScreen` 里只多一个 `remember`。
 *
 * 之所以字段是 `var … by mutableStateOf` 而不是 `data class`：这些值在拖动滑杆/点档位时
 * 高频变化，需要细粒度订阅；一个不可变快照会让整块面板重组。
 */
class CamProState(private val prefs: SharedPreferences) {
    /** 用户参数（唯一真值）。写盘在 [commit]，读盘在构造。 */
    var mode by mutableStateOf(CameraProStore.load(prefs))
        private set

    /** 这台设备的能力。相机绑好之后由 `CameraScreen` 填；`None` = 还没读到/读不到。 */
    var caps by mutableStateOf(ProCaps.None)
        private set

    /** 置灰矩阵，从 [caps] 推出来，缓存住免得每帧重算。 */
    var avail by mutableStateOf(availability(ProCaps.None))
        private set

    /** 直方图（已归一化到 0..1）。空 = 还没跑起来 / 不支持 / 关着。 */
    var histogram by mutableStateOf(FloatArray(0))

    /** 直方图跑不起来时的原因（给界面说一句人话，不许静默空白）。 */
    var histogramNote by mutableStateOf("")

    fun updateCaps(next: ProCaps) {
        caps = next
        avail = availability(next)
        // 读了能力之后必须**立刻重钳**上一次落盘的参数：换镜头/换设备时旧值可能越界，
        // 而越界的 ISO 递进 CaptureRequest 是一次 IllegalArgumentException（相机直接黑）。
        val clamped = clampToCapabilities(mode, next)
        if (clamped != mode) {
            mode = clamped
            CameraProStore.save(prefs, clamped)
        }
    }

    /**
     * 只把**这一次取景**降级成"本机不支持"，**不写盘**。
     *
     * 为什么必须与 [updateCaps] 分开（P0-3）：`updateCaps` 的语义是"读到了这台设备的真实能力"，
     * 它会顺带把钳制后的值写回 prefs。而参数下发失败（`CameraProBindings.apply` 返回 false，
     * 或 `readCapsOrNull` 偶发抛异常）**只说明这一次下发没成**，
     * 不代表用户上次停在 ISO 3200 这个偏好是错的。以前这两件事共用一个入口，
     * 于是一次 `ILLEGAL_ARGUMENT` 就把磁盘上的 ISO/快门/WB/对焦永久擦掉 ——
     * 换设备、重启、甚至只是把相机页关掉再进，用户的选择都回不来了。
     *
     * 内存里的 `mode` 仍然要按 [ProCaps.None] 钳成全自动：界面不许留一颗亮着却没生效的格子，
     * 而那正是"降级"这个词在用户眼里的意思。
     */
    fun degradeInMemory() {
        updateCapsInMemoryOnly(ProCaps.None)
    }

    /** [degradeInMemory] 的实现体：与 [updateCaps] 只差最后那次 `CameraProStore.save`。 */
    private fun updateCapsInMemoryOnly(next: ProCaps) {
        caps = next
        avail = availability(next)
        // 不写盘的代价：下次进相机页会从 prefs 读回用户原来的值，再一次尝试下发能力。
        // 那正是我们要的——"这台设备这一次没吃下"不该变成用户偏好的墓碑。
        mode = clampToCapabilities(mode, next)
    }

    /** 用户改了一项。写盘 + 就地钳制（界面不该出现一个按下去没反应的格子）。 */
    fun update(next: ProMode) {
        val clamped = clampToCapabilities(next, caps)
        mode = clamped
        CameraProStore.save(prefs, clamped)
    }

    /**
     * 点 PRO 开关。**关掉时走 [resetOnDisable]**，不是 `copy(enabled=false)`：
     * 后者会把 iso/shutter/… 原样带回去，于是 CaptureRequest 里残留 SENSOR_SENSITIVITY，
     * 下一张照片仍然按 ISO 3200 曝光——「关掉了但没关掉」。
     */
    fun toggle() {
        mode = if (mode.enabled) resetOnDisable() else clampToCapabilities(mode.copy(enabled = true), caps)
        CameraProStore.save(prefs, mode)
    }

    fun line(): String = describe(mode, caps)
}

/**
 * 专业模式（PRO）的界面层：**只画格子，不做决定**。
 *
 * 三条来自本项目既有教训的形状约束（都是守卫会直接判红的）：
 *
 * 1. **不横向滚动**：`PerfSourceGuardTest.noHorizontalScrollOnActionRows` 明令禁止
 *    `horizontalScroll(`——滚出视野的 Compose 节点根本不在语义树里，读屏与自动化都够不着，
 *    而专业模式恰恰是"参数多、必须一屏可达"的界面。所以档位列表一律用
 *    `AppChipFlow`（FlowRow）+ 抽屉里的 `AppSheetSection` 分组。
 * 2. **每颗格子自带名字**：`AppBarCell` 已经把 `contentDescription` 挂在可点那一颗节点上
 *    （`AppBar.kt:99-105`），这里不再叠 `semantics`，否则会把名字盖成空。
 * 3. **滑杆自己报名字**：`A11ySourceGuardTest.everySliderHasItsOwnName` 只认写在
 *    `Slider(` **调用方 modifier** 上的 `contentDescription`（material3 1.3.1 的
 *    `sliderSemantics` 与它落在同一个节点）。对焦那根连续滑杆按这条写。
 *
 * 置灰矩阵（[ProAvailability]）在这里被**翻译成看得见的东西**：不可用的格子仍然画出来、
 * 但 `enabled = false`，并在抽屉里写一句原因。**不许整格消失**——消失的控件在
 * 用户眼里与"这个功能坏了"没有区别，而这台机器（qemu 模拟器）大概率一整排都用不了。
 */

@Composable
fun CamProToggleCell(
    pro: ProMode,
    availability: ProAvailability,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 标签是动态的（`if`），不参与 FlagTableGuardTest 的"同标签同状态"判据；
    // 那句话说的是**此刻的状态**，不是点了会变成什么——与全项目其它格子同一约定。
    AppBarCell(
        label = if (pro.enabled) "PRO 开" else "PRO",
        on = pro.enabled,
        enabled = availability.any,
        onClick = onToggle,
        modifier = modifier.testTag("cam_pro"),
    )
}

/** 面板里的一档。用枚举而不是 5 个布尔：抽屉只有一层，打开哪一档是**互斥**的。 */
enum class ProSlot(val title: String, val tag: String) {
    ISO("ISO", "cam_pro_iso"),
    SHUTTER("快门", "cam_pro_shutter"),
    EV("曝光补偿", "cam_pro_ev"),
    WB("白平衡", "cam_pro_wb"),
    FOCUS("手动对焦", "cam_pro_focus"),
}

/**
 * PRO 打开后压在取景下方那一排「当前值」。**每一格说的都是现在的值**，
 * 所以用户不打开抽屉也知道自己把相机设成了什么——这一条在专业模式里比什么都重要：
 * 忘了 ISO 停在 3200 是这类相机最常见的废片原因。
 */
@Composable
fun CamProChips(
    pro: ProMode,
    availability: ProAvailability,
    onOpen: (ProSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppBarRow(modifier.fillMaxWidth()) {
        AppBarCell(
            label = pro.iso?.let { ProTables.isoLabel(it) } ?: "ISO 自动",
            on = pro.iso != null,
            enabled = availability.iso,
            onClick = { onOpen(ProSlot.ISO) },
            modifier = Modifier.testTag("cam_pro_chip_iso"),
        )
        AppBarCell(
            label = pro.shutterNs?.let { ProTables.shutterLabel(it) } ?: "快门 自动",
            on = pro.shutterNs != null,
            enabled = availability.shutter,
            onClick = { onOpen(ProSlot.SHUTTER) },
            modifier = Modifier.testTag("cam_pro_chip_shutter"),
        )
        AppBarCell(
            label = ProTables.evLabel(pro.ev),
            on = pro.ev != 0,
            enabled = availability.ev,
            onClick = { onOpen(ProSlot.EV) },
            modifier = Modifier.testTag("cam_pro_chip_ev"),
        )
        AppBarCell(
            label = pro.wbKelvin?.let { "${it}K" } ?: "白平衡 自动",
            on = pro.wbKelvin != null,
            enabled = availability.wb,
            onClick = { onOpen(ProSlot.WB) },
            modifier = Modifier.testTag("cam_pro_chip_wb"),
        )
        AppBarCell(
            label = pro.focusDiopters?.let { "MF ${ProTables.focusLabel(it)}" } ?: "对焦 自动",
            on = pro.focusDiopters != null,
            enabled = availability.focus,
            onClick = { onOpen(ProSlot.FOCUS) },
            modifier = Modifier.testTag("cam_pro_chip_focus"),
        )
    }
}

/**
 * 一档参数的档位抽屉。**值只从 `ProTables` 出**，界面不自己造档位——
 * 否则 `CameraProStateTest` 里那张单调性断言就管不到界面上真正能点到的值。
 *
 * @param slot 打开的是哪一档；null = 抽屉关着（由调用方决定要不要组合本函数）
 * @param onPick 选完即回调，调用方负责 `clampToCapabilities` 之后再写进 CaptureRequest
 */
@Composable
fun CamProSheet(
    slot: ProSlot,
    pro: ProMode,
    caps: ProCaps,
    availability: ProAvailability,
    onPick: (ProMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(title = "专业模式 · ${slot.title}", tag = slot.tag, onDismiss = onDismiss) {
        // 一台一项都用不了的设备（本机的模拟器就是这一支）会有这一屏：一句人话 + 一行怎么退出。
        // 不许是空白面板——空白在用户眼里等于"点了没反应"。
        if (!availability.any) {
            AppSheetSection("本机不支持", availability.reason)
            Text(
                "这台设备没有向应用开放手动曝光/对焦能力。普通拍摄不受影响，可以继续使用" +
                    "网格、水平仪、画幅与水印。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@AppSheet
        }
        when (slot) {
            ProSlot.ISO -> {
                if (!availability.iso) return@AppSheet
                AppSheetSection("ISO", "点一档即生效；关掉 PRO 会全部回到自动")
                AppChipFlow {
                    AppBarCell(
                        label = "自动",
                        on = pro.iso == null,
                        onClick = { onPick(pro.copy(iso = null)) },
                    )
                    ProTables.isos.forEach { iso ->
                        AppBarCell(
                            label = ProTables.isoLabel(iso),
                            on = pro.iso == iso,
                            onClick = { onPick(pro.copy(iso = iso)) },
                            modifier = Modifier.testTag("cam_iso_$iso"),
                        )
                    }
                }
            }
            ProSlot.SHUTTER -> {
                if (!availability.shutter) return@AppSheet
                AppSheetSection("快门", "1/4000 – 2 秒；长于 1/60 记得端稳")
                AppChipFlow {
                    AppBarCell(
                        label = "自动",
                        on = pro.shutterNs == null,
                        onClick = { onPick(pro.copy(shutterNs = null)) },
                    )
                    ProTables.shuttersNs.forEach { ns ->
                        AppBarCell(
                            label = ProTables.shutterLabel(ns),
                            on = pro.shutterNs == ns,
                            onClick = { onPick(pro.copy(shutterNs = ns)) },
                            modifier = Modifier.testTag("cam_sh_$ns"),
                        )
                    }
                }
            }
            ProSlot.EV -> {
                if (!availability.ev) return@AppSheet
                AppSheetSection("曝光补偿", "1/3 档一格；手动 ISO/快门时由它们接管")
                AppChipFlow {
                    AppBarCell(
                        label = "EV 0",
                        on = pro.ev == 0,
                        onClick = { onPick(pro.copy(ev = 0)) },
                    )
                    (ProTables.EV_MIN_INDEX..ProTables.EV_MAX_INDEX)
                        .filter { it != 0 }
                        .forEach { idx ->
                            AppBarCell(
                                label = ProTables.evLabel(idx),
                                on = pro.ev == idx,
                                onClick = { onPick(pro.copy(ev = idx)) },
                                modifier = Modifier.testTag("cam_ev_$idx"),
                            )
                        }
                }
            }
            ProSlot.WB -> {
                if (!availability.wb) return@AppSheet
                AppSheetSection("白平衡", "数值越小越冷（把现场的暖光当暖光），越大越把画面压冷")
                AppChipFlow {
                    AppBarCell(
                        label = "自动",
                        on = pro.wbKelvin == null,
                        onClick = { onPick(pro.copy(wbKelvin = null)) },
                    )
                    caps.wbKelvins.forEach { k ->
                        AppBarCell(
                            label = ProTables.wbLabel(k),
                            on = pro.wbKelvin == k,
                            onClick = { onPick(pro.copy(wbKelvin = k)) },
                            modifier = Modifier.testTag("cam_wb_$k"),
                        )
                    }
                }
            }
            ProSlot.FOCUS -> {
                if (!availability.focus) return@AppSheet
                AppSheetSection("手动对焦", "0 = 无穷远，数字越小对得越近")
                AppChipFlow {
                    AppBarCell(
                        label = "自动",
                        on = pro.focusDiopters == null,
                        onClick = { onPick(pro.copy(focusDiopters = null)) },
                    )
                    ProTables.focusStops(caps.maxFocusDiopters).forEach { d ->
                        AppBarCell(
                            label = ProTables.focusLabel(d),
                            on = pro.focusDiopters != null &&
                                kotlin.math.abs(pro.focusDiopters!! - d) < 1e-3f,
                            onClick = { onPick(pro.copy(focusDiopters = d)) },
                            modifier = Modifier.testTag("cam_mf_${(d * 100).toInt()}"),
                        )
                    }
                }
                // 连续那根：档位之间还是要能微调，否则 1m / 2m 之间就是空的。
                // 名字必须写在这个 `Slider(` 自己的括号里（见 A11ySourceGuardTest 的判据）。
                val current = pro.focusDiopters ?: 0f
                val caption = "手动对焦 " + ProTables.focusLabel(current)
                Text(
                    caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Slider(
                    value = current,
                    onValueChange = { onPick(pro.copy(focusDiopters = it)) },
                    valueRange = 0f..caps.maxFocusDiopters,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .semantics { contentDescription = caption }
                        .testTag("cam_pro_focus_slider"),
                )
            }
        }
        Text(
            "关掉 PRO 会把 ISO / 快门 / 白平衡 / 对焦一次性交还相机自动。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
