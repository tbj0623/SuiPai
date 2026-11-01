package com.suipai.app.ui.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl

/**
 * PRO 与 CameraX 的**唯一接缝**：读能力、写 CaptureRequest。
 *
 * 这一层与 [CameraProState] 分开，是因为这里每一个调用都可能抛、可能返回垃圾，
 * 而上面那层必须是纯函数（可单测）。三条纪律，全部从"崩溃比缺功能更糟"推出来：
 *
 * 1. **读能力只读一次**（相机绑好之后），读失败 → [ProCaps.None] → 整个 PRO 入口置灰。
 *    不做"先猜再试"：猜错的代价是相机线程上的 `IllegalArgumentException`。
 * 2. **写参数一律走 [Camera2CameraControl.setCaptureRequestOptions]**，不重新 bind。
 *    重新 bind 会闪一下预览（`LaunchedEffect` 里已经因为这条把画幅从 key 里拿掉了，
 *    见 `CameraScreen.kt:533-535`），而在拍摄中途 rebind 更可能丢掉正在写的 ImageCapture。
 *    代价是首次套用要等一帧 session 更新——比闪屏与丢片好得多。
 * 3. **每一处都 `runCatching`**，失败返回 `false`，调用方把这一档就地降级为"本机不支持"。
 *    永不向上抛：`LaunchedEffect` 里抛出去就是整个相机页的 FATAL。
 */
object CameraProBindings {

    /**
     * 读这台设备/这枚镜头的能力。**读不到就返回 null**，不猜、不给默认值。
     *
     * 为什么不用 `camera.cameraInfo.cameraCapabilities`（CameraX 1.4 的新 API）：
     * 它的是打包后的能力对象，缺 `LENS_INFO_MINIMUM_FOCUS_DISTANCE`、
     * `CONTROL_AE_COMPENSATION_RANGE` 这类"必须回到 Camera2 特性"的项；
     * 而 `Camera2CameraInfo` 是同一个 aar 里现成的（camera2 interop），一次读全。
     *
     * 为什么返回 `ProCaps?` 而不是"读不到就当 [ProCaps.None]"（P0-3）：
     * [ProCaps.None] 的语义是"这台设备**确实**一项手动能力都没有"，调用点拿它去
     * `CamProState.updateCaps` 会把用户存的参数按新能力钳一遍**并写回 prefs**。
     * 于是一次偶发的 `Camera2CameraInfo.from()` 失败（或特性为 null 的驱动）就把
     * 磁盘上的 ISO 3200 永久擦成"自动"。`null` 在这里的含义是"这一次没读到"，
     * 调用点应该只降内存（`degradeInMemory`），不动用户的偏好。
     *
     * **这个对象故意只留一个读取入口**：以前有个 `readCaps` 把 null 折成 None，
     * 谁顺手改用它，P0-3 就无声无息地回来了。
     *
     * `camera == null` 也返回 null：那同样是"读不到"，不是"这台设备不支持"。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun readCapsOrNull(camera: Camera?): ProCaps? {
        val cam = camera ?: return null
        return runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val aeModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            val awbModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val shutter = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val ev = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ProCaps(
                isoRange = iso?.toIntRange(),
                shutterNsRange = shutter?.toLongRange(),
                evRange = ev?.toIntRange(),
                maxFocusDiopters = minFocus ?: 0f,
                wbKelvins = ProTables.wbKelvins,
                // AE_MODE_OFF 出现在表里才有手动曝光。**没有它在表里就绝不去设**
                // （LEGACY 级设备不支持，设了就是相机线程上的异常）。
                aeOffSupported = aeModes?.contains(CameraCharacteristics.CONTROL_AE_MODE_OFF) == true,
                // 与 AE 同一口径：表里没有 AWB_MODE_OFF 就不给手动白平衡。
                // 宁可少给一档，也不冒"驱动不认这条 request"的风险（见 CAMERA-PRO.md §3）。
                awbOffSupported = awbModes?.contains(CameraCharacteristics.CONTROL_AWB_MODE_OFF) == true,
                afOffSupported = afModes?.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF) == true,
            )
        }.getOrNull()
    }

    /**
     * 把 [plan] 写进这台相机的 session。返回 true = 已下发给 CameraX
     * （真正的失败会在下一帧的 request 里体现，这里能抓的是"调用本身"那层）。
     *
     * `Reset` / `None` 走 `clearCaptureRequestOptions()`：**必须清**，
     * 否则上次 add 进去的 `SENSOR_SENSITIVITY` 会一直留在 session 上，
     * 表现为"PRO 关了但照片还是按 ISO 3200 曝光"。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun apply(control: CameraControl?, plan: CaptureOptionPlan): Boolean {
        val cam = control ?: return false
        return runCatching {
            val c2 = Camera2CameraControl.from(cam)
            when (plan) {
                CaptureOptionPlan.None, CaptureOptionPlan.Reset -> {
                    c2.clearCaptureRequestOptions()
                    true
                }
                is CaptureOptionPlan.Options -> {
                    val builder = CaptureRequestOptions.Builder()
                    plan.aeMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, it) }
                    plan.sensitivity?.let {
                        builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
                    }
                    plan.exposureNs?.let {
                        builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                    }
                    plan.awbMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it) }
                    plan.colorCorrectionMode?.let {
                        builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, it)
                    }
                    plan.colorGains?.let {
                        // RGGB 顺序：Camera2 的 RggbChannelVector(r, ge, go, b)。
                        // 四通道全给同一对增益（绿通道有两个），这是手动白平衡的标准写法。
                        builder.setCaptureRequestOption(
                            CaptureRequest.COLOR_CORRECTION_GAINS,
                            android.hardware.camera2.params.RggbChannelVector(it[0], it[1], it[1], it[2]),
                        )
                    }
                    plan.afMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it) }
                    plan.focusDiopters?.let {
                        builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                    }
                    plan.evIndex?.let {
                        builder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            it,
                        )
                    }
                    c2.setCaptureRequestOptions(builder.build())
                    true
                }
            }
        }.getOrDefault(false)
    }

    private fun Range<Int>.toIntRange(): IntRange = IntRange(lower, upper)
    private fun Range<Long>.toLongRange(): LongRange = LongRange(lower, upper)
}
