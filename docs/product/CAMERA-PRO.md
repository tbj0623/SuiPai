# 相机专业模式（PRO）· 设计、档位表与降级矩阵

> 交付：ROADMAP 波段 2 `writer-camera-pro`。
> 代码：`app/src/main/java/com/suipai/app/ui/camera/CameraProState.kt`（纯逻辑）、
> `CameraProPanel.kt`（界面）、`CameraProBindings.kt`（CameraX 接缝）、
> `CameraScreen.kt`（顶栏一颗 PRO 格 + 拍摄设置抽屉一段 + 直方图角标）。
> 测试：`app/src/test/java/com/suipai/app/ui/camera/CameraProStateTest.kt`（34 条）+
> `app/src/test/java/com/suipai/app/ui/PerfSourceGuardTest.kt` 的 `everyProToggleAppliesItsPlan`
> （钉调用点：每处 `pro.toggle()` 必须紧跟下发）

**这一轮没有改任何依赖。** `Camera2Interop` / `Camera2CameraInfo` / `Camera2CameraControl`
随 `androidx.camera:camera-camera2:1.4.1` 一起出（已用 `javap` 从 Gradle 缓存里的
`camera-camera2-1.4.1.aar` 核对过类与方法签名），`app/build.gradle.kts` 一行未动。

---

## 0. 一句话

相机多了五项手动控制（ISO / 快门 / EV / 白平衡 / 手动对焦）+ 一个可开关的亮度直方图；
**每一项都先问设备要得到范围才点亮**，要不到就置灰并给一句人话，**永不把越界值递进 CaptureRequest**。
关闭 PRO 时五项一起复位成自动、并清空 session 上的 CaptureRequest options。

---

## 1. 档位表

全部在 `ProTables`（`CameraProState.kt:154` 一带）。界面从这张表生成，单测对它做
单调性/唯一性断言，所以**表就是唯一事实来源**——界面不会另造一档。

### 1.1 ISO（22 档，1/3 档一节）

```
50 64 80 100 125 160 200 250 320 400 500 640
800 1000 1250 1600 2000 2500 3200 4000 5000 6400
```

标签 `ISO 400`。设备范围由 `SENSOR_INFO_SENSITIVITY_RANGE` 给，表里**落在范围内的档**才可用
（见 §2）。

### 1.2 快门（40 档，1/4000 – 2 秒）

```
1/4000 1/3200 1/2500 1/2000 1/1600 1/1250 1/1000 1/800 1/640 1/500
1/400 1/320 1/250 1/200 1/160 1/125 1/100 1/80 1/60 1/50
1/40 1/30 1/25 1/20 1/15 1/13 1/10 1/8 1/6 1/5
1/4 0.3s 0.4s 0.5s 0.6s 0.8s 1s 1.3s 1.6s 2s
```

内部以**纳秒**存（`SENSOR_EXPOSURE_TIME` 的单位），标签按人话：
短于 0.4 秒写 `1/125s`，长于它写 `0.5s` / `2s`。
标签表在 `ProTables.shutterLabels` 里**声明一次**（`String.format` 每次调用会现造
`Formatter`，而这一行在拖动时每帧重拼——与 `PerfSourceGuardTest.formattersAreDeclaredOnce`
管同一件事）。

### 1.3 曝光补偿（±3 EV，19 档下标）

下标 −9..+9，一格 1/3 EV。标签 `EV +0.7` / `EV −1.0` / `EV 0`（负号用 U+2212，
与相机既有的 `EV −` 格子同一写法）。四舍五入到十分位：`+2/3` 显示 `+0.7`，不截断成 `+0.6`。
与既有的 `nudgeEv` / `evText`（`CameraScreen.kt:1449`）**同口径**（都是
`setExposureCompensationIndex` 的档位下标，不是 EV 浮点值）。

### 1.4 白平衡（9 档）

```
2800K 白炽 / 3200K 钨丝 / 4000K 荧光 / 4500K 日光 / 5000K 标准
5500K 标准 / 6500K 阴天 / 7500K 阴影 / 9000K 深阴影
```

开尔文 → `RggbChannelVector` 用 Tanner Helland 黑体辐射近似（`kelvinToGains`），
按 G 归一化后夹进 `[0.25, 4]`——某些驱动对极端增益直接抛 `ILLEGAL_ARGUMENT`。
低色温 R>B、高色温 B>R（单测钉住）。**不做**色彩矩阵/双光源校正（见 §6）。

### 1.5 手动对焦

按**屈光度**（1/米），档位随设备截断：

```
0（∞） 0.5（2m） 1（1m） 2（0.5m） 3（0.33m） 5（0.2m） 10（0.1m）
```

只保留 ≤ `LENS_INFO_MINIMUM_FOCUS_DISTANCE` 的档；设备给 0 或负值 → 整档置灰。
抽屉里另有一根**连续滑杆**（`0f..maxFocusDiopters`），档位之间还能微调。
那根滑杆的 `contentDescription` 写在 `Slider(` 自己的括号里——
`A11ySourceGuardTest` 只认这个位置（material3 1.3.1 的 `sliderSemantics` 与它同节点）。

---

## 2. 钳制规则（`clampToCapabilities` 一族）

三条语义，缺一条降级矩阵就不成立：

| 情形 | 结果 | 为什么 |
| --- | --- | --- |
| `caps` 里那一项是 `null` / `false` | `null`（置灰） | "拿不到"就是"不支持"，不许猜一个默认值 |
| 范围非空但**表里没有任何一档落在范围内** | `null`（置灰） | 把 80 塞给只到 49 的设备 = 相机线程上的 `IllegalArgumentException` |
| 用户值超出上下界 | 夹到范围内最近的一档 | 换镜头/换设备后旧值必须还能用 |
| 用户值恰好落在界上 | 不动 | 边界不许被"夹"歪 |
| 用户值是 `null` | `null`（自动） | null 的语义是"交给相机"，钳制不许把它变成具体值 |
| 空范围（`IntRange.EMPTY`） | `null` | 真实设备不会这么报，但它是从 prefs 一路进来的外部数据 |

`E`（曝光补偿）是唯一的例外：设备不支持时回 `0` 而不是置灰——"补偿关着"与"补偿不可用"
在体验上是同一件事，而 EV 是普通相机本来就有的能力。

---

## 3. 降级矩阵

`availability(caps)` 算出五格的可点状态（`ProAvailability`），界面**只置灰、不隐藏**。
消失的控件在用户眼里与"坏了"没有区别，而这台机器（qemu 模拟器）大概率一整排都用不了。

### 3.1 设备能力 → 看到什么

| 设备给的东西 | PRO 入口 | ISO | 快门 | EV | 白平衡 | 手动对焦 | 面板里的说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 全支持（旗舰） | 亮 | 亮 | 亮 | 亮 | 亮 | 亮 | —（无原因句） |
| 只有 `AE_MODE_OFF` | 亮 | 亮 | 亮 | 视 EV 范围 | 灰 | 灰 | 抽屉里对应档整段不出 |
| 只有 EV 范围 | 亮 | 灰 | 灰 | 亮 | 灰 | 灰 | 同上 |
| 只有 `AF_MODE_OFF` | 亮 | 灰 | 灰 | 视范围 | 灰 | 亮 | 同上 |
| 什么都不给（模拟器） | **灰** | 灰 | 灰 | 灰 | 灰 | 灰 | 「这台设备的相机不提供手动参数」+ 一段"No 也能继续用网格/水平仪/画幅/水印" |
| `Camera2CameraInfo.from()` 抛异常 | 灰 | 灰 | 灰 | 灰 | 灰 | 灰 | 同"什么都不给"（`readCapsOrNull` 整段 `runCatching`，返回 null） |
| 三路流排不下（Preview+Capture+Analysis） | 正常 | 正常 | 正常 | 正常 | 正常 | 正常 | 拍摄设置里直方图开关置灰 + 「这台设备的相机排不出直方图用的第三条流」 |
| 参数下发失败（`apply` 返回 false） | 正常 | 灰 | 灰 | 灰 | 灰 | 灰 | 状态行「专业参数没能下发，已回到自动」 |
| `readCapsOrNull` 这一次读不到（返回 null） | 同上 | 灰 | 灰 | 灰 | 灰 | 灰 | 同上（**但不写盘**，见 §3.3） |

### 3.2 置灰时的三条硬要求（都在测试里钉住）

1. **PRO 入口自己也要灰**（`ProAvailability.any == false`）——否则点进去是一片空白。
2. **必须有一句人话**（`reason` 非空，且含"设备"两个字）——否则用户以为坏了。
3. **一格都不许消失**——五颗格子永远画出来，只是 `enabled = false`。

### 3.3 参数下发失败：只降内存，不擦用户参数

`CameraProBindings.apply()` 的每一处都包在 `runCatching { }.getOrDefault(false)` 里。
返回 false 时调用点（`CameraScreen.applyProNow()`：绑相机之后 / 两个 PRO 开关 / 选完档位
共用这一个出口）**就地降级**：调 `CamProState.degradeInMemory()` + 状态行一句话。
绝不留一颗亮着却没生效的格子。

**这条语义在 2026-10-29 改过，改之前的写法是有害的：**

| | 旧语义（错） | 新语义 |
| --- | --- | --- |
| 调用 | `pro.updateCaps(ProCaps.None)` | `pro.degradeInMemory()` |
| 内存里的 `caps` / `avail` / `mode` | 降成全自动 | 降成全自动（一样） |
| **SharedPreferences 里的 `cam_pro_*`** | **被写回全 null** | **原值保留** |
| 下一次进相机页 | 用户的 ISO 3200 已经没了 | 重新读能力，再试一次下发 |

为什么旧写法是 bug：`updateCaps` 的语义是"读到了这台设备的**真实能力**"，它会顺带把
钳制后的值写盘。而"参数没能下发"只是**这一次**的失败——一次 `ILLEGAL_ARGUMENT`、
或 `readCapsOrNull` 偶发抛异常，就把用户存盘的 ISO/快门/白平衡/对焦
从磁盘擦掉，换设备、重启、甚至只是退出再进相机页都回不来。

配套的两条：

- `CameraProBindings.readCapsOrNull(camera)` 返回 `ProCaps?`：`null` = **这一次没读到**，
  调用点走 `degradeInMemory()` 而不是 `updateCaps(ProCaps.None)`。
  它现在是**唯一的读取入口** —— 原来那个"读不到折成 `ProCaps.None`"的 `readCaps` 已删除：
  留着它就等于留着一个"谁顺手改回去，P0-3 无声无息复发"的入口。
- 真实 `updateCaps`（含钳制写盘）**只在相机绑好、`readCapsOrNull` 真读到能力时**调用。

JVM 测试：`CameraProStateTest.degradeInMemoryKeepsTheSavedPreferencesOnDisk`
（降级后 `cam_pro_iso` 仍是 3200；而同一份 prefs 走 `updateCaps(ProCaps.None)` 会变 -1，
两条并排才说明"两个入口真不是一回事"）与
`updateCapsStillClampsAndWritesWhenTheCapsAreReal`（换镜头后越界旧值仍被钳制写盘）。

### 3.4 关 PRO 必须真的清掉 session 上的参数

`CamProState.toggle()` 只改状态，**下发必须由调用点负责**。两个入口（顶栏 `cam_pro` 格、
抽屉里 `cam_pro_sheet` 格）以前都只写了 `pro.toggle()`，于是关了 PRO 之后
`SENSOR_SENSITIVITY` 仍留在 session 上，下一张照片还按上次的 ISO 曝光。

现在四处调用点（绑相机、两个 toggle、选完一档）共用 `CameraScreen.applyProNow()`，
并由源码守卫 `PerfSourceGuardTest.everyProToggleAppliesItsPlan` 钉住：
**每一处 `pro.toggle()` 向下 8 行内必须出现 `applyProNow(` 或 `CameraProBindings.apply`**。
纯函数测试（`disabledModeAlwaysPlansAReset_notANoOp`）只保证"计划该是 Reset"，
钉不住"有没有人真去下发"——这条源码守卫补的就是那个缺口。

### 3.5 拍完回读：请求值 vs 实际值

拍完在 `captureOnce` 的 IO 段读一次刚存那张 JPEG 的 `TAG_PHOTOGRAPHIC_SENSITIVITY` /
`TAG_EXPOSURE_TIME`（读不到再退到 `TAG_ISO_SPEED_RATINGS`、`TAG_ISO_SPEED`），
与 `planCaptureOptions` 里**这一次请求的** `sensitivity` / `exposureNs` 比对；
不一致时状态行追加一句 `请求 ISO 400 · 实际 800`（`exposureMismatchNote`）。

存在的唯一理由是**给 P0-1 那类 bug 一条可观测的证据**：界面上「PRO 关」四个字可以是真
（状态袋翻了），而 session 上的参数是假（没人下发）。EXIF 里的数字是唯一说得清的那一方。

三条纪律：

1. **读在后处理之前**：画幅/水印/镜像会用 `Bitmap.compress` 重写同一个 uri，而 compress 不写 EXIF。
2. **量不到就不说**：没请求过手动曝光、或照片里两个标签都读不到 → 空串，不猜。
3. **容差 1/3 档**（比值 ≤ 1.3）：HAL 会把 ISO/快门量化到最近的 1/3 档，
   请求 1/125 得到 1/100 是正常的；差 1 档（×2）一定被抓到，那正是"计划没生效"的形状。

[未验证] 模拟器 HAL 是否把请求值回显进 EXIF —— 需要在 AVD 上拍一张看状态行有没有这句话。
没有这句话有两种可能（真的一致，或 EXIF 里根本没写），**这两种情况目前无法区分**。

---

## 4. CaptureRequest 计划（`planCaptureOptions`）

只产出一张**数据表**（`CaptureOptionPlan`），真正 set 的地方在
`CameraProBindings.apply()`，在那里每一处都 `runCatching`。

### 4.1 组合规则

| 用户动了什么 | `CONTROL_AE_MODE` | `SENSOR_SENSITIVITY` | `SENSOR_EXPOSURE_TIME` | 其他 |
| --- | --- | --- | --- | --- |
| 什么都不动 / PRO 关 | 不下发 | — | — | PRO 关时走 `clearCaptureRequestOptions()` |
| 只 EV | 不下发（保持自动） | — | — | `CONTROL_AE_EXPOSURE_COMPENSATION` |
| ISO 或快门（任一） | `AE_MODE_OFF` | 用户值或**范围中位** | 用户值或**范围中位** | EV 下标不下发（手动曝光下会被忽略） |
| 白平衡 | — | — | — | `AWB_MODE_OFF` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` + `COLOR_CORRECTION_GAINS` |
| 手动对焦 | — | — | — | `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` |

**`SENSOR_SENSITIVITY` 与 `SENSOR_EXPOSURE_TIME` 要么都不给、要么都给。**
`AE_MODE_OFF` 一旦打开，只给一个会被驱动按 0 处理（黑图）。缺的那一半用设备范围的
中间档补齐（`midIso` / `midShutter`）；补不出来就**整组放弃、退回 AE 自动**
——宁可自动，也不要黑图。

`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` 是承重的一条：手动增益不配它，
驱动会忽略 `COLOR_CORRECTION_GAINS`、自动 AWB 把画面盖回来，
表现为"白平衡格子点了没反应"（这种 bug 只能靠用户投诉发现）。

### 4.2 「关掉 PRO」为什么返回 `Reset` 而不是 `None`

`None` 是"什么都不用改"，但上一次 `addCaptureRequestOptions` 加进去的
`SENSOR_SENSITIVITY` 仍留在 session 上 → 用户以为回到自动，照片还是按 ISO 3200 曝光。
`Reset` 走 `clearCaptureRequestOptions()`，**必须清**。

同一条纪律体现在 `resetOnDisable()` 返回全 null 的 `ProMode`（不是
`copy(enabled = false)`——那会把五项原样带回去）。

### 4.3 CameraMetadata 常量为什么抄一份

`app/build.gradle.kts` 开了 `unitTests.isReturnDefaultValues = true`，
JVM 单测里 android.jar 全是桩，**读 `CameraMetadata` 的静态字段一律得到 0**。
于是 `assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, plan.aeMode)` 会变成 "0 == 0" 的恒真空跑，
而"AE 该开还是该关"正是这里最要命的一条。

所以生产代码用 `Camera2Codes`（`const val`，Kotlin 内联成真字面量），
单测里用一张**独立抄写的字面量表**对账（`CameraProStateTest.androidCodesMatchTheSdk`），
并额外钉住 `TRANSFORM_MATRIX != FAST`、`AE_MODE_OFF != AE_MODE_ON` 这两条"撞值就恒真"的底线。
值来源写在代码注释里：`android-36/android.jar` 上的
`javap -constants android.hardware.camera2.CameraMetadata`。**改之前先重跑那条命令。**

---

## 5. 直方图

### 5.1 帧源：第三条 `ImageAnalysis` 流

**没有从 preview 偷帧**（`PreviewView.getBitmap()` 主线程 + PixelCopy，最多阻塞 100 ms，
见 `SurfaceViewImplementation.getBitmap` 的实现；每帧一次等于把 UI 线程打死）。
而是新绑一路 `ImageAnalysis`：

```kotlin
ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .setResolutionSelector(640×480 / FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
```

- `STRATEGY_KEEP_ONLY_LATEST`：分析跟不上就丢帧，**永不排队**（排队会吃内存并拖慢预览）。
- 单独一条 640×480 的 resolution selector：借用预览那条
  `HIGHEST_AVAILABLE_STRATEGY` 会把分析流顶到满分辨率，每帧多拷一份大图。
- 分析线程是**独立的单线程执行器**（`Executors.newSingleThreadExecutor()`），
  不用 `Dispatchers.Default`——那里被 `ColorPipeline` 的 8 路分块占着（`ColorPipeline.kt:56`）。

### 5.2 三路绑定失败 = 直方图降级，不是相机崩

```kotlin
provider.bindToLifecycle(lifecycle, sel, preview, capture, analysis)   // 先试三路
    .recoverCatching {
        analysis?.clearAnalyzer()
        provider.bindToLifecycle(lifecycle, sel, preview, capture)     // 排不下就退回两路
    }
```

失败后 `histogramNote` 由 `histogramNoteFor(executorShutdown, analysisBuilt, threeStreamBound)`
统一给出，界面把「直方图」格子置灰并在下面显示这句话。**拍照与预览完全不受影响。**

三种失败各说各的话（`CameraProStateTest.histogramNoteForDistinguishesEveryFailure` 钉住）：

| 情形 | `histogramNote` |
| --- | --- |
| 执行器已 shutdown（相机页正在退出） | 「直方图已停」 |
| `ImageAnalysis.Builder()…build()` 造不出对象 | 「这台设备的相机建不出直方图要用的分析流」 |
| 造得出但三路排不下 | 「这台设备的相机排不出直方图用的第三条流」 |
| 三路都成功 | `""`（开关可点） |

**这条语义在 2026-10-29 改过。** 旧写法在两个地方各写一次：`recoverCatching` 里写一句
降级原因，绑好之后又按 `frameSource == null` 重算一次并**覆盖**。而降级发生时
`analysis` 对象仍然非空（只是 analyzer 被 clear 了），所以 `frameSource` 不是 null，
重算落到 `else -> ""` —— 那句人话被擦成空串。用户看到「直方图 开」亮着可点、
屏幕上什么都不画（空数组时 Canvas 不画）、下面也没有任何解释。

现在 `threeStreamBound` 是**唯一**的降级事实来源（在 `bind` 里由三路绑定的实际结果赋值），
`frameSource` 在降级时也被置 null，读那一段只负责把三个事实交给纯函数。

### 5.3 采样策略与性能预算

`lumaHistogram(y, w, h, rowStride, pixelStride, bins=64, step=8)`（纯算术，JVM 可测）：

| 项 | 值 | 依据 |
| --- | --- | --- |
| 分辨率 | 640×480 | 直方图只要分布形状，不需要细节 |
| 采样步长 | 横竖各 8 像素 | 每帧读 `640/8 × 480/8 = 4800` 点（全帧 307200 的 1/64） |
| 桶数 | 64 | 96dp 宽的画布上每桶 1.5dp，再多只是噪声 |
| 读取内容 | 只读 Y 平面（`image.planes[0]`） | 亮度直方图不需要 UV；省掉 2/3 的拷贝 |
| 单帧成本 | 一次 `ByteArray(remaining)` + 4800 次数组读 + 64 次桶自增 | 单核几十微秒量级 |
| 更新频率 | 每个分析帧（KEEP_ONLY_LATEST，实际 ≈ 预览帧率） | 每帧重建一个 `IntArray(64)` + `FloatArray(64)`，可忽略 |
| `image.close()` | 在 `runCatching { }` **之外**、无条件调用 | 分析线程上漏一次 close 会让整路流卡死 |

`step=8` 是选的不是拍的：`step=4` 读 19200 点、`step=16` 只剩 1200 点（暗部只剩几个桶有值，
直方图开始抖）。8 在"形状稳定"与"每帧几十微秒"之间的拐点上。

**任何异常都不许冒到分析线程上**：`ImageAnalysis` 的默认行为是 analyzer 抛异常
直接结束这一路流。所以整段采样包在 `runCatching` 里，`close()` 在 `finally` 语义的位置。

### 5.4 线程与生命周期

- 执行器随 `CameraScreen` 的 `DisposableEffect(Unit)` 创建，`onDispose` 里
  `clearAnalyzer()` + `shutdown()`。
- 不 shutdown 的后果：相机页退出后线程还在跑；而 analyzer 还挂着会让 CameraX
  在下一次打开时抱怨流被占用。
- 直方图结果通过 `scope.launch { pro.histogram = ... }` 回到主线程（Compose state 只能主线程写）。

### 5.5 界面上

- 位置：右下角，96×40 dp，`bottom = 268.dp`（在变焦档 100 / 变焦滑杆 148 / PRO 那排 196 之上）。
- 只在 `PRO 开 && 直方图开关开 && histogram.isNotEmpty() && !uiLocked` 时画。
  `isNotEmpty()` 这条很要紧：**空框会被当成"直方图坏了"**，什么都不画才是对的。
- 它是图不是控件，但带 `contentDescription = "亮度直方图"`——否则语义树里是个不可读的空白节点。
- 开关在**拍摄设置抽屉 → 专业模式**那一组里（不是顶栏），理由：它是"这一趟取景想看什么"
  的临时动作，与「系统相机 / 对焦中央」同性质，不该占顶栏一格。

---

## 6. 这一轮**没做**的（明确列出，不许当成已实现）

| 项 | 状态 | 原因 / 下一步 |
| --- | --- | --- |
| **峰值对焦**（focus peaking） | **没做** | 需要在预览上做边缘检测并染色，得拿到每一帧的 Y 平面并在预览上叠加——那是"预览替换"级别的改动（要么 GL/Surface 合成，要么每帧把 bitmap 画到 Canvas 上），与本轮"不重写相机"的约束冲突。手动对焦已给档位 + 连续滑杆 + 距离读数，可用但不是峰值。 |
| **手动对焦的琥珀刻度叠加** | **没做** | 同上（属于峰值那一族）；做成了抽屉里的 `∞ / 2m / 1m / 0.5m…` 档位 + 连续滑杆 + `caption`。 |
| **RAW / DNG** | 没做 | 需要 `ImageCapture.OUTPUT_FORMAT_RAW` 与新的存盘路径（MediaStore 的 DNG 落点、EXIF 配方写入、相册侧识别），超出本轮范围。 |
| **HDR 包围曝光** | 没做 | 需要连拍 + 合成（跨 3 档曝光对齐去鬼影），是引擎级工作。现有的 HDR 走 CameraX extensions（`ExtensionMode.HDR`，`CameraScreen.kt:549` 一带）。 |
| **白平衡的色彩矩阵校正** | 没做 | 需要 `SENSOR_COLOR_TRANSFORM_*` / `COLOR_CORRECTION_TRANSFORM` 矩阵与双光源（`DUAL_ILLUMINANT`）处理。当前用黑体辐射近似，误差在几百 K 量级。 |
| **自动 ISO/快门联动（P 档）** | 没做 | 现在手动 ISO 与手动快门是同一个 `AE_MODE_OFF` 开关；真正的 P/A/S/M 档位机需要读 `CaptureResult` 回显的当前 ISO/快门来"锁定一半"，那是另一套状态机。 |
| **测光模式**（点测/中央重点） | 没做 | 现有对焦动作已经带测光区域（`FocusMeteringAction` 的 FLAG_AE/FLAG_AF），但没有独立的测光模式选择。 |
| **直方图 RGB 分通道** | 没做 | 现在只有亮度（Y 平面）。RGB 要先做 YUV→RGB 转换，成本 ×3，而 96dp 宽画三通道看不清。 |
| **波形图 / 斑马纹** | 没做 | 与直方图同一数据源，但需要不同的绘制与判读教育。 |
| **机内降噪/锐化开关** | 没做 | 需要 `NOISE_REDUCTION_MODE` / `EDGE_MODE` / `TONEMAP_MODE` 一整套，且各厂商差异极大。 |

---

## 7. 交互

### 7.1 入口

三处，都指向同一份 `CamProState`：

1. **顶栏一颗「PRO」格**（`cam_pro`）：`off = PRO`、`on = PRO 开`。本机一项都不支持时它是灰的。
   点它 = `pro.toggle()` + `applyProNow()`（见 §3.4）。
2. **拍摄设置抽屉 → 「专业模式」段**：`PRO 关/开` 格（`cam_pro_sheet`）+ 五个当前值格
   （ISO 400 / 1/125s / EV 0 / 3200K / MF 2m）+ 直方图开关（`cam_hist`）。这一段的分段说明就是
   `describe()` 那一行——**屏幕上写的与读屏念的是同一句**。
   **五格现在各有稳定 testTag**：`cam_pro_slot_iso` / `_shutter` / `_ev` / `_wb` / `_focus`。
   加它们的理由是标签会随值变（`ISO 自动` → `ISO 400`），按文字定位的 AVD 取证脚本抓不住；
   底部那排 chips 的 `cam_pro_chip_*` 是另一套节点，没动。
3. **PRO 开着时，底部那排「当前值」**（`cam_pro_chip_*`）：压在变焦档之上，
   点任一格直接进对应抽屉。这一排的意义是"不打开抽屉也知道自己把相机设成了什么"——
   忘了 ISO 停在 3200 是这类相机最常见的废片原因。

### 7.2 抽屉

一格参数一个抽屉（`CamProSheet`），内容是 `AppSheetSection` + `AppChipFlow`（FlowRow）。
**没有 `horizontalScroll`**：`PerfSourceGuardTest.noHorizontalScrollOnActionRows` 明令禁止，
而滚出视野的节点根本不在语义树里。
选完一档立刻：`pro.update(...)` → `applyProNow()`（内部 `planCaptureOptions` →
`CameraProBindings.apply(...)`）。抽屉关闭。每一档都有一颗「自动」格（回到 null）。

### 7.3 落盘

`CameraProStore`（`cam_pro_on` / `cam_pro_iso` / `cam_pro_shutter` / `cam_pro_ev` /
`cam_pro_wb` / `cam_pro_focus`）。用哨兵值 `-1` 表示"自动"，不用 `remove()`：
"自动"是一个能读回来的状态，而不是"这个键不存在"这种隐式状态。
关 PRO 时立刻写回全自动值。

---

## 8. 测试（34 条，全 JVM）

`CameraProStateTest`：

| 组 | 条数 | 钉住什么 |
| --- | --- | --- |
| 档位表 | 6 | ISO/快门严格递增且无重复、标签唯一、EV 四舍五入不截断、对焦档随设备截断、`describe` 永远说得出一句人话 |
| 钳制边界 | 6 | 六种边界各一条（caps 缺失 / 单点 / 超上下界 / 恰在界上 / 空范围 / 表里没有可用档），外加五项一起钳 |
| 关闭复位 | 2 | `resetOnDisable` 五项全 null、`enabled=false` 的计划必须是 `Reset` 而不是 `None` |
| 下发计划 | 6 | AE_MODE_OFF 与两值配对、只给一半时补齐、补不出来时退回自动、WB/AF 的模式配对、全自动时不下发、`equals/hashCode` 按内容 |
| 置灰矩阵 | 4 | 全支持 / 模拟器 / 部分支持 / 只有 EV |
| 直方图 | 4 | 计数守恒、尊重 rowStride/pixelStride、越界安全退化、归一化到 0..1 |
| 白平衡增益 | 1 | 有限且为正、低色温 R>B 高色温 B>R、夹进 `[0.25, 4]` |
| 落盘 | 1 | 往返一致、哨兵读回是自动 |
| 常量表 | 1 | `Camera2Codes` 与独立抄写的 SDK 字面量表对账 + 两条"撞值就恒真"的底线 |
| **直方图那句话**（新） | 1 | `histogramNoteFor` 四种输入给四种话；喂"折成一句话"与"优先级错"两个坏实现都必须红 |
| **降级不写盘**（新） | 2 | `degradeInMemory` 后 `cam_pro_iso` 仍是 3200（并排验证 `updateCaps` 会写成 -1）；`updateCaps` 换镜头钳制写盘不许被误伤 |
| **请求 vs 实际**（新） | 1 | 没请求 / 读不到 / 一致 / 1/3 档量化误差 → 不说话；差一档 → 「请求 ISO 400 · 实际 800」；喂"永远沉默"与"容差过大"两个坏实现都必须红 |

`PerfSourceGuardTest` 新增 **1 条**：`everyProToggleAppliesItsPlan`
（每处 `pro.toggle()` 向下 8 行必须有 `applyProNow(` / `CameraProBindings.apply`；
自带四个坏样本 + 三个好样本 + 注释不算站点 + 窗口过宽不算数的自检）。

每条判据都配了「故意喂坏例子」的自检（项目纪律：没见过自己变红的守卫不算守卫）。

跑法：

```
pwsh tools/abuild.ps1 :app:testDebugUnitTest --tests "com.suipai.app.ui.camera.*"
```

---

## 9. 已知风险

1. **三路流在低端机上可能排不下**：已写成"三路 → 两路"的降级，且降级失败仍能拍照。
   [未验证] 本机模拟器上到底是两路还是三路成功——需要装机时看 `histogramNote` 是否为空。
   （注意：`histogramNote` 为空有两种含义：三路都成，或这一段根本没跑到。装机时配合
   「直方图」格子是否可点一起看。）
2. **`Camera2Interop` 是 `@ExperimentalCamera2Interop`**：用的是 `@OptIn` 而非全局 opt-in，
   升级 CameraX 时要重新核对 API（`Camera2CameraInfo.from` 在 1.3 之后改过一次签名）。
3. **`COLOR_CORRECTION_GAINS` 的实际效果依赖厂商**：部分设备的 `TRANSFORM_MATRIX` 是空操作，
   表现为白平衡档位"看起来没变化"。这不会有崩溃，但也无法在端上探测——只能装机对比。
4. **手动曝光的黑图风险**：`AE_MODE_OFF` + 极端参数组合（如 ISO 50 + 1/4000 在暗处）必然欠曝，
   这是物理不是 bug。界面已经通过"快门长于 1/60 记得端稳"这类提示说明，但不做自动保护。
