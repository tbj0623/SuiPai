# 设置中心 · 语义、默认值与落盘键

> 本文件是 `app/src/main/java/com/suipai/app/settings/` 那一层的说明书。
> 与 `docs/design/DESIGN-SYSTEM.md`（视觉）、`docs/arch/APP-ARCH.md`（导航）平行。
>
> **改设置项的顺序**：`SettingsKeys.kt` 加键 → `SettingsModel.kt` 加一条 `SettingItem` →
> 本文件加一行 → 跑 `:app:testDebugUnitTest --tests "com.suipai.app.settings.*"`。
> 单测会核对「键全局唯一 / 分组非空 / Choice 默认值在选项里 / 同组不同名 / 键都挂在目录上」，
> 以及**「标了 LIVE 就必须真的有读者」**（`SettingsBridgeTest.everyLiveWritableItemReallyHasAReader`）。

## 0. 三条总规则

1. **新键一律 `cfg_` 前缀。** 相机 / 一瞬 / 相册三张老表（`CameraFlag`、`YishunFlag`、
   `GalleryFlag`）共用 `suipai.xml`，`FlagTableGuardTest.prefKeysAreGloballyUnique` 钉着
   "落盘键名全局唯一"。`cfg_` 前缀同时让 `SettingsStore.resetAll()` 能安全地只清本模块的键。
2. **默认值只写在 `SettingsKeys.kt`。** 界面不许再抄一份；`docs` 与本表也是照它抄的。
3. **`cfg_` 是唯一真源，老键降级为"兼容写"。** 这是本轮（0.8.0）改的口径，见 §3。

## 1. 落盘位置与备份

| 文件 | 内容 | 参与系统备份 |
| --- | --- | --- |
| `suipai.xml` | 相机 / 一瞬 / 相册的老偏好 **+ 本模块全部 `cfg_` 键** | ✅ 是（`backup_rules.xml` 与 `data_extraction_rules.xml` 两处都只 include 这一个） |
| `suipai.private.xml` | 收藏与隐藏的 id 清单 | ❌ 两处都显式 exclude |
| `suipai.ai` | 加密后的 API Key 与 AI 档位 | ❌ 不在这两个规则里；且换机后主密钥消失，密文解不开 |

照片本身在 `MediaStore`，不归备份管。

## 2. 全部设置项

「接线」列说的是**今天谁读它**。四档口径：

- **`LIVE`** —— 拨了之后，下一次进入对应界面能看到变化；主题/字号是当场就变。
  **22 个"可写项"（12 Toggle + 7 Choice + 3 Number）里 18 个是 `LIVE`**，
  这 18 项都过了 `SettingsBridgeTest.everyLiveWritableItemReallyHasAReader`：
  标了 LIVE 却在桥与「直接读者」清单里都找不到读者，测试直接红。
  （另外 15 个 Action / ReadOnly 类型的 `live` 默认就是 `LIVE`，含义是"这一行点得动"，与偏好无关。）
- **`PREVIEW`** —— 值写对了，但生效时机不在本 lane 能控制的地方（现在只剩「启动页」一项：
  它是 `NavState` 的 `rememberSaveable` 初值，**要重建任务才换页**）。
- **`PENDING`** —— 没有读者。界面把它灰掉并写「（等接线）」，**不许假装生效**。**现在只剩三项**。
- 动作 / 只读 / 说明三类（Action / ReadOnly / Info）不带偏好，`live` 的含义是"这一行点得动"。

> **这一节的口径在 2026-10-29 变过。** 旧版写的是「读数点没接完」「`cfg_` 只是初值层」——
> 那两句话**都已经不成立了**：`cfg_` 现在是**真源**，老键降级为**只写不读的兼容写**（见 §3），
> 绝大多数项已 `LIVE`。仍然没接完的只有三项，原因逐个写在 §2 末尾那张表里。

### 通用（`SettingsGroup.GENERAL`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| 外观（动作） | — | Action | — | 进外观子屏 | ✅ LIVE |
| 重看首次引导（动作） | `cfg_onboarding_version`（写 0） | Action → Int | 1 | 把内容版本退回 0，下次冷启动重弹三屏 | ✅ LIVE |
| 启动页 | `cfg_start_tab` | Choice | `PHOTOS` | 照片/相册/互通 三选一 | 🔶 PREVIEW（读者在 `state/NavState.kt` 的初值里，**要重建任务才换页**） |
| 删除前确认 | `cfg_confirm_delete` | Bool | `true` | 批量删除前再问一次 | ✅ LIVE（`ui/gallery/GalleryScreen.kt` 的批量工具条） |
| 桌面拍照快捷方式 | `cfg_shortcut_camera` | Bool | `false` | 长按图标的快捷菜单 | ⏳ PENDING（缺：`widget/DynamicShortcuts.kt` 读它并决定「拍照」那条在不在） |

内部键（不进设置列表，别的动作顺手写）：

| 键 | 类型 | 默认 | 用途 |
| --- | --- | --- | --- |
| `cfg_onboarding_done` | Bool | `false` | 首启引导是否走完（`OnboardingGate.shouldShow` 的第一条件） |
| `cfg_onboarding_version` | Int(0..99) | `1` | 走完的是哪一版内容；`OnboardingGate.CONTENT_VERSION` 是当前值 |
| `cfg_settings_search_history` | Set | 空 | 设置搜索最近词，最多 8 条 |
| `cfg_settings_hint_seen` | Bool | `false` | 首屏那句提示是否已点掉 |
| `cfg_settings_compact` | Bool | `false` | 设置列表紧凑行距 |

### 外观（`APPEARANCE`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| 主题 | `cfg_theme_mode` | Choice | `FOLLOW_SYSTEM` | 跟随系统 / 暗房 / 日光印样 | ✅ LIVE（`ui/theme/Theme.kt` 的 `SuiPaiTheme`，当场换；老键 `dark_ui` 兼容写） |
| 字号 | `cfg_font_scale` | Choice | `STANDARD` | 小 85% / 标准 100% / 大 115% / 特大 130%（**在系统字级之上**再乘一档，不覆盖系统） | ✅ LIVE（`Theme.kt` 乘进 `LocalDensity.fontScale`） |
| 照片网格 | `cfg_grid_columns` | Choice | `STANDARD` | 密排 64dp / 小格 86 / 标准 118 / 大格 168 | ✅ LIVE（`GalleryScreen` 的 `cellDp` → `GridCells.Adaptive`；老键 `grid_cell` 兼容写） |
| 缩略图质量 | `cfg_thumb_quality` | Choice | `STANDARD` | 省电 256px / 标准 420 / 清晰 720 | ✅ LIVE（`GalleryScreen` 的 `ImageRequest.size` / `FastThumb(px)`） |
| 减弱动效 | `cfg_reduce_motion` | Bool | `false` | 关掉淡入淡出与按压动画；**不与系统「移除动画」叠加** | ✅ LIVE（`ui/design/Motion.kt` 的 `rememberMotionFactor()`；相册位置横带按它取 0/原时长） |

外观子屏自带一块**即时预览**：按当前档位画 12 个占位色块（不是照片）。
它现在与真界面读的是同一套 token，所以主题一换，预览块跟着换。

### 相册（`GALLERY`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| 默认排序 | `cfg_gallery_sort` | Choice | `NEWEST` | 最新 / 最早 / 文件名 / 体积 | ✅ LIVE（`GalleryScreen` 的 `sortMode` / `newestFirst` 初值） |
| 分组 | `cfg_gallery_group` | Choice | `DAY` | 按日 / 按月 / 按年 | ✅ LIVE（`GalleryScreen` 的 `groupMode` 初值） |
| 显示已隐藏 | `cfg_gallery_show_hidden` | Bool | `false` | 打开后被藏起来的照片回到时间线 | ✅ LIVE（`GalleryScreen` 的 `showHiddenEffective`，与「已隐藏」入口取**或**） |
| 年份索引 | `cfg_gallery_section_index` | Bool | `true` | 网格右侧快速跳转的年月轨 | ⏳ PENDING（缺：相册页没有那条右侧索引轨；年/月跳转今天是筛选弹层里的一格） |

### 相机（`CAMERA`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| JPEG 画质 | `cfg_camera_jpeg` | Int(95..100) | `95` | 拍照落盘的 JPEG 质量；区间外不写 | ✅ LIVE（`CameraUiState.jpegQ` → `ImageCapture.setJpegQuality`） |
| 构图辅助线 | `cfg_camera_show_grid` | Bool | `true` | 默认画上九宫格 | ✅ LIVE（`CameraUiState.gridMode`：0 = 不画，> 0 = 画；**保留用户挑的那一款**） |
| 快门声 | `cfg_camera_shutter_sound` | Bool | `true` | 关掉后拍照不发声 | ✅ LIVE（`CameraUiState.shutterSound`） |
| 前置镜像 | `cfg_camera_mirror_front` | Bool | `true` | 自拍是否左右翻转保存 | ✅ LIVE（`CameraUiState.mirrorFront`） |
| 极简取景 | `cfg_camera_simple_ui` | Bool | `false` | 只留快门与画幅 | ✅ LIVE（`CameraUiState.simpleUi`） |

### 一瞬（`YISHUN`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| 成片画质 | `cfg_yishun_export_jpeg` | Int(95..100) | `95` | 保存成片时的 JPEG 质量 | ✅ LIVE（`YishunScreen.jpegQ`；顶栏那颗「画质」也回写这一项） |
| 默认强度 | `cfg_yishun_default_intensity` | Int(0..100) | `60` | 新配方**第一次**套上时的强度 | ✅ LIVE（`YishunScreen.firstIntensity()`；**没拨过时仍用配方自己的 `defaultIntensity`**） |
| 按住看原图 | `cfg_yishun_hold_compare` | Bool | `true` | 按住回到原片，松手回成片 | ✅ LIVE（`YishunScreen` 的 `onPress`；关掉后顶栏「看原图」按钮照旧可用） |
| 自动识别场景 | `cfg_yishun_auto_scene` | Bool | `true` | 端侧分析照片，先挑一条相近配方 | ✅ LIVE（`YishunScreen` 里 `SceneAnalyzer.analyze` 外面那一层判断） |

### 隐私（`PRIVACY`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 |
| --- | --- | --- | --- | --- |
| 隐私与数据（动作） | — | Action | — | 进隐私子屏 |
| 相册权限（动作） | — | Action | — | 跳系统照片授权页（两条出口见 `Launch.kt`） |
| 智能配方（只读） | — | ReadOnly | — | 显示 `AiSettings.enabled` 与是否有 Key |
| 系统备份带什么（只读） | — | ReadOnly | — | 一句固定结论 |

内部键：`cfg_privacy_export_at`（Text，上次导出时间）、`cfg_privacy_notice_seen`（Bool）。

### 存储（`STORAGE`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 | 接线 |
| --- | --- | --- | --- | --- | --- |
| 存储管理（动作） | — | Action | — | 进存储子屏 | ✅ LIVE |
| 本机数据（只读） | — | ReadOnly | — | 随拍自己写的文件占用（不含照片） | ✅ LIVE |
| 图片缓存（只读） | — | ReadOnly | — | 可再生的缩略图/预览缓存 | ✅ LIVE |
| 清除缓存（动作） | `cfg_cache_clean_at`（写时间） | Action | — | 只删可再生文件 | ✅ LIVE |
| 缓存过大时提醒 | `cfg_auto_cache_clean` | Bool | `false` | **只在设置页提示，不自动删**（没有后台任务） | ⏳ PENDING（缺：设置页拿到 `cacheBytes` 之后读这一项、超阈值时显示一行提示；那个数在 `nav/AppNavHost.kt` 里算） |
| 照片保存位置（只读） | — | ReadOnly | — | `Pictures/SuiPai` | ✅ LIVE |

### 关于（`ABOUT`）

| 界面项 | 落盘键 | 类型 | 默认 | 语义 |
| --- | --- | --- | --- | --- |
| 关于随拍（动作） | — | Action | — | 进关于整页（`AboutScreen`） |
| 版本 / 这台设备 / 引导状态（只读） | — | ReadOnly | — | 运行时真值 |

### 仍然是 PENDING 的项 + 缺什么

一共**三项**（0.8.0 之前是二十多项）。每一项都写清"缺哪一件事"，下一波照着补即可：

| 设置项 | 缺什么 | 在哪 |
| --- | --- | --- |
| 年份索引（`cfg_gallery_section_index`） | **那条右侧索引轨本身**。相册页今天没有它；年/月跳转是筛选弹层里的一格。补的时候先决定它是常驻一列还是滚动时浮出 | `ui/gallery/GalleryScreen.kt` |
| 桌面拍照快捷方式（`cfg_shortcut_camera`） | `DynamicShortcuts.refresh` 读这一项，决定「拍照」那条在不在；并在设置页拨完主动叫一次刷新 | `widget/DynamicShortcuts.kt` + 设置页回调 |
| 缓存过大时提醒（`cfg_auto_cache_clean`） | 设置页拿到 `cacheBytes` 后读这一项、超阈值显示一行提示。那个数由 `storageNumbers()` 算 | `ui/settings/StorageScreen.kt` 的调用点（`nav/AppNavHost.kt`） |

**这三项为什么不该被"顺手点亮"**：它们各自缺的是**一件真实的功能**（一条索引轨 / 一处桌面重发 /
一处设置页读数），不是一个开关少写了一行读者。把 `live` 改成 `LIVE` 只会让界面变亮、
用户拨了什么都不发生 —— 那正是这一轮在修的病的同一种。

**这一轮（2026-10-29）没有把这三项中的任何一项往前推**。

## 3. 接线：桥怎么做的（0.8.0）

### 3.1 真源在哪边

**`cfg_` 是真源，老键降级为"兼容写"。** 三条理由：

1. 新键在 `SettingsKeys` 里有默认值、有区间、有单测；老键的默认值散在六个调用点里。
2. 真源必须唯一。反过来（老键做真源、`cfg_` 只做镜像）会让设置页显示的值和实际生效的值
   来自两个地方，迟早说岔 —— 那正是这一轮要修的病的同一种。
3. 老键不能删（降级安装要认），所以走"写两边"：设置中心改 → 顺手把老键写成同一个选择；
   界面自己拨老键 → 顺手把 `cfg_` 写成同一个选择。两条都不是镜像。

### 3.2 映射与桥

`settings/SettingsBridge.kt`（纯 Kotlin，不 import android，整座桥都能在 JVM 上钉住）。
每个映射一个函数，**枚举到整数全部显式写、不用 `ordinal`**（枚举重排一次，
老用户的"按月"就会变成"按年"，而这种错不会崩、只会悄悄错）。

| 设置 | 老键 | 映射 | 注意 |
| --- | --- | --- | --- |
| `cfg_theme_mode` | `dark_ui` | 暗房→true / 日光→false / **跟随系统→不写** | 跟随系统写一个固定值等于把它偷偷改成固定 |
| `cfg_grid_columns` | `grid_cell` | 64 / 86 / 118 / 168 | 反向"就近向上落档"，中间值必须落到存在的档 |
| `cfg_thumb_quality` | （无） | 256 / 420 / 720 | 只给相册读，`420` 是原来写死的那个数 |
| `cfg_gallery_sort` | `gallery_sort` + `newest_first` | 0/1/4/2 与布尔 | **两个键一起写**，否则 sort 与 newest_first 自相矛盾 |
| `cfg_gallery_group` | `group_mode` | 0/1/2 | 反向 3（按周）落到按年，不许悄悄变按日 |
| `cfg_camera_jpeg` | `camera_jpeg` | 直通 | |
| `cfg_camera_show_grid` | `show_grid` | 直通 | **不写** `grid_mode`：那会永久丢掉用户挑的 20 款样式之一 |
| `cfg_camera_shutter_sound` | `shutter_sound` | 直通 | |
| `cfg_camera_mirror_front` | `mirror_front` | 直通 | |
| `cfg_camera_simple_ui` | `camera_simple` | 直通 | |
| `cfg_yishun_export_jpeg` | `yishun_jpeg` | 直通 | |

没有老键对应物、直接读 `cfg_` 的那几项：`appearance_font_scale`、`appearance_reduce_motion`、
`gallery_show_hidden`、`general_confirm_delete`、`yishun_default_intensity`、
`yishun_hold_compare`、`yishun_auto_scene`。它们由 `SettingsBridgeTest` 的
`directReaders` 清单逐个点名 —— **加一条直接读的项必须同时加进那张清单**，否则测试会红。

### 3.3 升级迁移：老用户的选择不许丢

读法是"**存过就按 `cfg_`，没存过才看老键**"（`SettingsStore.kt` 里那三个扩展：
`settingsBool` / `settingsInt` / `storedChoiceName`）。迁移只发生一次：
一旦设置页（或桥）写过一次 `cfg_`，老键就再也影响不了读数。

反过来的写法（每次都先看老键）看起来更"兼容"，实际会让用户改完设置又被老值盖回去。

### 3.4 界面自己拨老键时也回写

`SettingsStore.syncFromLegacyBool` / `syncFromLegacyInt`：相机抽屉的「快门声 / 镜像 / 极简 /
辅助线」、相册工具条的「分组 / 排序 / 网格」、一瞬顶栏的「画质」、以及页眉那颗 DARKROOM/DAYLIGHT
开关，都会把 `cfg_` 写成同一个选择。

**从老键回写时不再反向扇回老键**（`fanBackToLegacy = false`）：`legacyWritesFor` 是**有损**的
（20 款网格 → 一个开关、六档排序 → 四档），扇回去会把用户挑的「黄金分割」改写成「九宫格」。

### 3.5 主题/字号为什么能"当场"变

`SuiPaiTheme` 在导航宿主**之上**，设置页改一条偏好不会让它重组。所以写路径统一 +1 一个
`SettingsSignal.revision`（`settings/SettingsSignal.kt`），`Theme.kt` 在组合里读它
（`remember(prefs, revision) { prefs.storedChoiceName(...) }`）。
没有这一层，用户选了日光要等下次冷启动才看得到 —— 那会被当成"设置坏了"。

`SettingsSignal` 是**普通 Compose 状态**（`mutableIntStateOf`，不依赖 Android，JVM 单测里照样能用），
`revision` **只增不清**：读者要的是"变过了"，不是"现在第几次"。
它在 `commit()` 里、落盘之后**同步** `bump()` —— 不抛异常、不做 IO。

**这一条没装机验过。** "选了日光、整屏当场变白"是这一轮最需要人眼确认的一件事
（`ANDROID-VERIFY.md` §45.2 A2）。

### 3.6 还没接的（下一波）

| 设置 | 要改的文件 | 怎么接 |
| --- | --- | --- |
| `cfg_start_tab` | `state/NavState.kt`（构造参数 `initial`） | 把 `BackStack.of(AppRoute.Gallery)` 换成按档位选 tab。**注意它是 `rememberSaveable` 的初值**，改完只影响新任务。这是唯一一项 `PREVIEW` |
| `cfg_theme_mode` 的「跟随系统」 | `state/ChromeState.kt` | `darkUi` 改成从 `cfg_theme_mode` 派生 + 读 `uiMode`；现在它等价于"沿用页眉那颗开关的当前状态"。**这一项本身是 LIVE**（暗房/日光当场生效），缺的只是「跟随系统」那一档的语义 |
| `cfg_shortcut_camera` | `widget/DynamicShortcuts.kt` | 见 §2 的 PENDING 表 |
| `cfg_auto_cache_clean` | 设置页 / `nav/AppNavHost.kt` | 见 §2 的 PENDING 表 |
| `cfg_gallery_section_index` | `ui/gallery/GalleryScreen.kt` | 见 §2 的 PENDING 表 |

⚠️ **接管老键时不要删老键的写入**：`CameraFlag` 表与 `FlagTableGuardTest` 都按老键判唯一性，
删掉会让"降级安装读到另一档"变成静默错误。现在老键由 `SettingsBridge.legacyWritesFor`
统一写，键名收在 `LegacyKeys` 里 —— **别在调用点写字面量**（`PerfSourceGuardTest`
的 `deadPreferenceKeys` 只认字面量，写成字面量会让"为了兼容而写"被误报成"只写不读"）。

## 4. 首启引导

- **判定**：`OnboardingGate.shouldShow(snapshot)` = `!onboarded || version < CONTENT_VERSION`。
  两个条件分开，所以"改引导内容"只需把 `CONTENT_VERSION` +1，不用写迁移。
- **完成**：`OnboardingGate.complete(store)` 写 `onboarded=true` 与 `version=CONTENT_VERSION`，
  **幂等**（重复完成不改值）。
- **重看**：`OnboardingGate.replay(store)` 只把 version 退回 0，**不清 onboarded**。
  这样用户中途退出不会被永远当成新用户。
- **三屏内容**在 `onboardingPages()`（纯函数，可单测）；文案只讲现有能力，
  每条都对得上代码（5446 条配方 = `LookLibrary.all.size`、配方随 EXIF = `data/RecipeExif.kt`、
  出网只有两处 = 隐私政策结论段）。
- **权限不在这里申请**：`OnboardingScreen` 只把 `onFinish()` 交给调用方，
  由现有的 `PermissionGate` / `requiredPermissions()` 那条装机验证过的路径去弹。

## 5. 隐私与数据

- 正文口径与 `ui/about/AboutSheet.kt`、`store/privacy-policy.html`、`store/listing-zh.md` **三处一致**。
- 缩略图长边与质量**引常量**（`AiAdvisor.SEND_EDGE` / `SEND_QUALITY`），不重抄数字：
  `AiGuardTest` 会核对政策文件与关于抽屉里的那个数。
- 权限表与 `AndroidManifest.xml` 逐条对应；改 manifest 必须同步 `permissionRows()`。
- 「导出本机数据说明」= 手写 JSON（`PrivacyExport.buildJson`）：**不引序列化库**，
  内容是设置快照的键值 + 固定的事实摘要，不含照片、文件名、位置、Key。
- 「清除设置」只清 `cfg_` 前缀（`SettingsStore.resetAll`）。要清全部走系统「清除数据」。

## 6. 防呆

| 守卫 | 钉住什么 |
| --- | --- |
| `SettingsModelTest` | 键全局唯一、分组非空、Choice 默认值在选项表里且与落盘选项表一致、同组不同名、可写项必有落盘键、每个落盘键都有界面入口或进内部豁免表、搜索命中 |
| `SettingsStoreTest` | 全键往返、坏类型回默认值不崩、越界整数回默认值且拒写、枚举按 name 落盘且大小写无关、`resetAll` 只清 `cfg_`、导出覆盖全键 |
| `SettingsBridgeTest` | 每个映射的值域与边界、**枚举→整数逐项硬编码期望**、写新键→桥→老键值==期望、往返闭合、**标了 LIVE 的可写项必须有读者**、坏输入不抛且不瞎写 |
| `OnboardingTest` | 首启必弹、完成幂等（连读三次一致）、重看不清完成态、内容版本闸门真的会放回老用户 |
| `FlagTableGuardTest` | 落盘键名全仓唯一（**老表那一侧**；本模块由 `cfg_` 前缀保证不撞） |
| `PerfSourceGuardTest` | 不许 `horizontalScroll(` 装动作；不许只写不读的偏好键（兼容写因此走 `LegacyKeys`，不写字面量） |
| `A11ySourceGuardTest` | 滑杆要有名字、会变的字要标活区 |

每条新守卫都带"喂坏例子必须变红"的自检（三个测试文件末尾各有 `checkersActuallyCatchBadCode` /
`fakePrefsThrowsLikeTheRealOne` 一类的用例）。

## 7. 页面清单与签名

| 文件 | 入口 | 备注 |
| --- | --- | --- |
| `ui/settings/SettingsScreen.kt` | `SettingsScreen(snapshot, readonly, onChange, onBack)` | 分组列表 + 搜索；改动通过 `SettingChange` 值对象描述 |
| `ui/settings/AppearanceScreen.kt` | `AppearanceScreen(snapshot, onChange, onBack)` | 四项档位 + 即时预览 |
| `ui/settings/StorageScreen.kt` | `StorageScreen(usedText, cacheText, libraryText, lastCleanAt, hint, onAction, onBack)` | 占用 / 清缓存 / 导出 / 两步确认清除 |
| `ui/settings/AboutScreen.kt` | `AboutScreen(versionText, deviceText, savePath, onboardingStatus, onReplayOnboarding, onBack)` | 把 `AboutSheet` 升级成整页，sheet 保留 |
| `ui/onboarding/OnboardingScreen.kt` | `OnboardingScreen(onFinish, onBack)` | 三屏 pager，可滑可跳过 |
| `ui/privacy/PrivacyScreen.kt` | `PrivacyScreen(readonly, exportText, exportHint, onAction, onBack)` | 数据去向 / 权限逐项 / 导出 JSON |

**这六个文件都已经接到导航上了**（0.7.0 第二轮接入）：`AppRoute.Settings` / `SettingsAppearance` /
`SettingsStorage` / `SettingsAbout` / `Privacy` / `Onboarding` 各有一个 `AppNavHost` 分支。
屏幕函数仍按"收参数 + 回调"写 —— 它们自己不知道路由、不知道仓库，去哪一屏是 `AppNavHost` 的决定。
入口是页眉「更多」› 设置组（`chrome_open_settings` / `chrome_open_privacy`），
引导的另一条入口在「关于 › 重看首次引导」。

> **2026-10-29 复核**：这一层的代码状态 = `cfg_` 真源 + 老键兼容写 + 18 项 `LIVE` +
> 1 项 `PREVIEW`（启动页）+ **3 项 `PENDING`**（见 §2 末尾那张表）。
> **本轮没有装机验证** —— 上面的「接线」列说的是"代码里有读者"，不是"用户拨了确实变"。
> 「主题/字号当场换」这条链路（`SettingsSignal`）尤其需要装机看一眼。
