# 随拍 iOS —— 构建说明

iOS 壳是纯 SwiftUI 应用（零第三方依赖，目标 iOS 16+），调色/场景识别/预设/互通
全部来自 KMP 模块 `shared/` 编译出的动态框架 **SuiPaiKit**。

> 重要：本仓库在 Windows 上开发，Kotlin 共享层已在本机验证编译；**Swift 层尚未在
> Mac 上编译过**。首次在 Mac 上编译时请预留一次「按实际生成的 Objective-C 头文件
> 微调 Swift 调用」的时间，可能出问题的点见文末清单。

---

## 1. 前置环境

| 需要 | 版本 |
| --- | --- |
| macOS | 13 Ventura 或更高 |
| Xcode | 15.0 或更高（含 iOS 16+ SDK） |
| JDK | 17（Kotlin/Gradle 编译 KMP 用） |
| XcodeGen | `brew install xcodegen` |
| Gradle | 用仓库自带 `gradlew`，无需单独安装 |

首次构建会下载 Kotlin/Native 工具链，需要较长时间与稳定网络。

---

## 2. 先单独验证 Kotlin 共享层（推荐）

在仓库根目录执行，确认 SuiPaiKit 本身能编译：

```bash
# 列出 shared 模块所有可用的构建任务（任务名以实际输出为准）
cd shared && ../gradlew :shared:tasks --all | grep -i "SuiPaiKit\|Framework"

# 真机（arm64）
../gradlew :shared:assembleSuiPaiKitDebugIosArm64

# 模拟器（Apple Silicon 的 SimulatorArm64）
../gradlew :shared:assembleSuiPaiKitDebugIosSimulatorArm64
```

产物位置：

- 真机：`shared/build/bin/iosArm64/debugFramework/SuiPaiKit.framework`
- 模拟器：`shared/build/bin/iosSimulatorArm64/debugFramework/SuiPaiKit.framework`

> Gradle 任务名按 KMP 约定为 `assemble<框架名><BuildType><Target>`；`SuiPaiKit` 的
> baseName 在 `shared/build.gradle.kts` 里固定。若上面的任务名报不存在，用
> `./gradlew :shared:tasks --all` 查实际名字。

---

## 3. 生成并打开 Xcode 工程

```bash
cd ios
xcodegen generate          # 由 project.yml 生成 SuiPaiIOS.xcodeproj
open SuiPaiIOS.xcodeproj
```

在 Xcode 里：

1. 选中 `SuiPaiIOS` target → Signing & Capabilities → 选择你的 Team（真机必需）。
2. 直接 Run（⌘R）。构建阶段会自动执行 `Embed SuiPaiKit (KMP)`：
   ```
   cd "$SRCROOT/.." && ./gradlew :shared:embedAndSignAppleFrameworkForXcode
   ```
   该任务读取 Xcode 注入的 `CONFIGURATION` / `SDK_NAME` / `ARCHS` /
   `TARGET_BUILD_DIR` / `FRAMEWORKS_FOLDER_PATH`，自动编译正确的 KMP target、
   把 `SuiPaiKit.framework` 拷进 app 的 `Frameworks/` 并签名。

框架搜索路径（已写进 `project.yml` 的 `FRAMEWORK_SEARCH_PATHS`）：

```
$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)   # embedAndSign 产物
$(SRCROOT)/../shared/build/bin/iosArm64/debugFramework                     # 手工 assemble（真机）
$(SRCROOT)/../shared/build/bin/iosSimulatorArm64/debugFramework            # 手工 assemble（模拟器）
$(SRCROOT)/../shared/build/bin/iphoneos/Debug-iphoneos                     # 备用
$(SRCROOT)/../shared/build/bin/iphonesimulator/Debug-iphonesimulator       # 备用
```

链接由 `OTHER_LDFLAGS = -framework SuiPaiKit` 完成；不需要在 Xcode 里手动
「Add Files」加 framework。

### 只跑模拟器 / 只跑真机

`embedAndSignAppleFrameworkForXcode` 会按当前 destination 自动选 target，
不需要改配置。若想跳过 Gradle 用已有产物，可在 scheme 的 Build 阶段临时禁用
`Embed SuiPaiKit (KMP)`，并确保对应 `bin/...` 路径下已有 framework。

---

## 4. 权限

`project.yml` 通过 `INFOPLIST_KEY_*` 注入了：

- `NSPhotoLibraryUsageDescription`（读相册）
- `NSPhotoLibraryAddUsageDescription`（存成片到「SuiPai」相簿）
- `NSCameraUsageDescription`（拍照）
- `NSLocalNetworkUsageDescription`（局域网发现并直传照片）

不需要 `UIBackgroundModes`。互通只在 App 前台时进行。

---

## 5. 目录

```
ios/
  project.yml              XcodeGen 工程定义
  BUILD.md                 本文件
  SuiPaiIOS/
    SuiPaiIOSApp.swift     @main + TabView（相册 / 相机 / 互通）
    Theme.swift            深色暖色主题常量
    Permissions.swift      相册 / 相机权限
    Support.swift          CGImage↔KotlinIntArray、JPEG+EXIF、字节桥接、引擎管线
    PhotoLibrary.swift     PHPhotoLibrary 封装（列表/缩略图/原图/保存到 SuiPai 相簿）
    GalleryView.swift      LazyVGrid 网格 + 日期分组 + 加载更多
    ViewerView.swift       分页滑动 + 缩放 + 「一瞬」入口
    YishunView.swift       一瞬：选 Look + 强度 + 前后对比 + 导出
    CameraView.swift       AVCaptureSession 拍照
    SyncView.swift         互通 UI
    SyncBridge.swift       SyncHub 装配 + 状态轮询
```

---

## 6. 首次 Mac 编译最可能需要微调的点

以下位置是与 KMP 生成接口的接触面，若 Xcode 报「no such member / 参数不匹配」，
按 Xcode 自动补全给出的实际签名改，**改动集中在 `Support.swift` 与
`SyncBridge.swift` 两个文件**：

1. **`import SuiPaiKit`** —— 若 Xcode 提示找不到模块，检查 framework 的
   `baseName`（`shared/build.gradle.kts` 里为 `SuiPaiKit`）与 Build Settings 的
   `FRAMEWORK_SEARCH_PATHS` 是否命中。
2. **`KotlinIntArray` 取值/赋值 API** —— 代码用 `KotlinIntArray(size:)`、
   `get(index:)` / `set(index:value:)`、`.size`。若生成的是 `IntArray` 或
   下标访问，改 `Support.swift` 的 `argbBuffer(from:)` / `cgImage(from:)`。
3. **`KotlinByteArray`** —— 同上，`SyncBridge.swift` 的
   `kotlinByteArray(from:)` / `data(from:)`。
4. **`ColorCore.shared.process(pixels:w:h:p:)`** —— 已按 Kotlin 源码核对：`process`
   返回 `Unit`，是**就地改写**传进去的 `pixels`，不返回新数组。
   （2026-09 已改，`Support.swift` 不再接返回值。）
5. **`EngineParams(...)` 构造器** —— 已核对，Kotlin 侧默认值不导出到 Swift，
   **v3 起 18 个参数必须全传**（v2 时代是 15 个）：
   `look, intensity, w, h, grainEdge, seed, grainMul, vignetteMul, tempShift, contrastShift,
   satShift, shadowShift, fadeShift, highlightShift, softShift, curves, hsl, masks`。
   （参数顺序 = `ColorCore.kt` 第 9-37 行的声明顺序；`contrast` 之后那些是类体内的
   计算属性，不是构造参数，别照着抄。）
   `w/h` 是工作缓冲尺寸，`grainEdge` 是**成片长边**（预览也要提成片边长，否则所见非所得）。
   后三个是 v3 新增的三组专业算子，详见下面**第 8 节**。
6. **`SceneAnalyzerCore.shared.analyze(pixels:w:h:exif:faces:)`** —— 已核对：
   `exif: ExifData` 非空，`ExifData(iso:flashFired:exposureSec:focal35mm:wbMode:hourOfDay:)`
   六个字段全是 Kotlin 可空包装（`nil` 直接传即可），当前 iOS 全传 `nil`。
   要把 EXIF 喂进去，照 `app/.../engine/SceneAnalyzer.kt` 的填法补。
7. **`Adaptation` 字段名** —— 现有 Kotlin 源码是 `scene / look / intensity /
   reason`，Swift 侧读 `adaptation.look` / `.intensity` / `.reason`（不读 `scene`）。
8. **`LookLibrary.AUTO_ID`（const val）** —— Swift 侧未直接引用该常量，改用本地
   常量 `EngineLimits.autoLookId = "auto"`（值已与 Kotlin 源码核对一致），
   规避 `const val` 导出成 `LookLibrary.autoId` 还是 `LookLibrary.shared.autoId`
   的不确定性。若确认导出形式，可改回直接引用。
9. **`RecipeCodec` / `YishunRecipe`** —— 已核对当前形态：`encode(recipe:)` 返回
   非空 `String`，`YishunRecipe` 是 **16 个字段（v3）、没有 seed**（种子由
   `ColorCore.grainSeedFor(lookId:)` 单点决定）、多了 `exportEdge`；
   `v` 按 `Int32` 传，值与 `YishunRecipe.CURRENT_VERSION = 3` 核对一致。
   v3 新增的三个可空字段见**第 8 节**。
10. **互通（`SyncHub` / `SyncPhotoSource` / `SyncPhotoSink` / `SyncPhotoMeta` /
    `PeerInfo` / `SyncState` / `IncomingTransfer`）** —— 这些早就在 `shared/` 里
    （`shared/src/commonMain/kotlin/com/suipai/core/sync/SyncHub.kt`），不存在
    「按约定形状编写」的问题；逐个核对过的签名已写在 `SyncBridge.swift` 顶部。
    生命周期与 source/sink 的装配以 `app/src/main/java/com/suipai/app/sync/SyncSupport.kt`
    为基准实现（创建即 `start()`，改名先 `stop()` 再起新的）。
11. **`hub.sendPhoto` 的 suspend 导出** —— 代码写
    `try await hub.sendPhoto(peer:meta:bytes:)`（KMP 2.0 suspend → Swift
    async）。它返回 `Boolean`：失败不抛异常而是返回 `false`（原因进
    `SyncState.lastError`），**必须看返回值**。若 Xcode 给出的是 completionHandler
    版本，改 `SyncBridge.send`。
12. **`hub.state.value` 的读取** —— 已改成 `hub.state.value as? SyncState` 后读
    `running / peers / incoming / outgoing / lastError` 这些真实字段，不再用 KVC
    字符串摸不存在的属性。`incoming` 是 `List<IncomingTransfer>`，UI 一次只弹
    `first` 那一条（对齐 Android 的 `firstOrNull()`）。
13. **未固定类型的导出名** —— Kotlin/Native 会给没标 `@ObjCName(exact = true)` 的
    类型加框架前缀（`baseName = SuiPaiKit`）。目前 `shared` 里只固定了
    `ColorCore / EngineParams / ExifData / FaceBox / SceneAnalyzerCore / SyncHub /
    Look / LookLibrary / SceneKind / Adaptation / YishunRecipe / RecipeCodec /
    RecipeCurves / RecipeHsl / RecipeMask / RecipePro / PeerInfo / SyncPhotoMeta /
    SyncState / IncomingTransfer / OutgoingTransfer / SyncException / SyncEofException`
    这些名字，Swift 侧却在按裸名引用 `SyncPhotoSource` / `SyncPhotoSink` 两个协议。
    若首次编译报「cannot find type」，两种改法二选一：`shared` 侧照 `SyncHub` 补
    `@ObjCName(exact = true)`（推荐，一处改动两端受益），或 Swift 侧加 `typealias`。
    **v3 新增的四个引擎类型（`ToneCurves` / `ToneCurve` / `HslBands` / `HslBand` /
    `CurvePoint` / `LocalMask` / `LinearMask` / `RadialMask` / `LocalAdjust` /
    `MaskShape`）一个都没固定**，它们是第 8 节的主要风险来源。

---

## 7. 已知取舍（与 Android 的差异）

- **一瞬**：iOS 版实现「选 Look + 强度 + 前后对比 + 导出」，Android 的大量微调
  开关（颗粒/暗角/温度/分色等）未移植；`EngineParams` 的微调位一律传中性值，
  留待后续按需补 UI。
- **互通**：iOS 只做「发现设备 + 发送照片 + 接收确认」；浏览下载入口留 TODO
  （Android 侧为基准实现）。
- **预设缩略图**：5000+ 预设不做一次性全量渲染（会卡很久），改为按可见项
  懒计算并缓存。
- **场景分析**：`ExifData` 会构造，但六个字段全传 `nil` —— iOS 手里只有解码后的
  `CGImage`，EXIF 还留在原始 `Data` 里没接出来。因此「按拍摄时间/ISO 判断夜景、
  黄昏」这类依赖 EXIF 的分支在 iOS 上暂不生效；图像直方图分支正常。

---

## 8. v3 引擎对齐（曲线 / 分色 / 局部蒙版）

> **一句话**：这一节记录的是**纯文本层面的移植** —— 本仓库在 Windows 上开发，
> 没有 Xcode，**下面这些 Swift 一行都没编译过**。参数名与顺序全部照
> `shared/src/commonMain/kotlin/com/suipai/core/engine/` 的源码逐字抄，
> 但「参数名 / 顺序对不对」和「类型名导出成什么」是两件事，后者只能上 Mac 验。

### 8.1 这一轮 `shared/` 变了什么

| 变化 | 位置（Kotlin 源码为准） |
| --- | --- |
| `EngineParams` 从 15 个参数变 **18 个** | `ColorCore.kt` 第 9-37 行，末尾追加 `curves / hsl / masks` |
| 新增 `ToneCurves` / `ToneCurve` / `CurvePoint` | `ToneCurve.kt` |
| 新增 `HslBands` / `HslBand`（8 个带） | `HslBands.kt` |
| 新增 `LocalMask` / `LocalAdjust` / `MaskShape` / `LinearMask` / `RadialMask` | `LocalMask.kt` |
| `YishunRecipe` 升 **v3**，13 → 16 字段 | `RecipeCodec.kt` 第 23-61 行，末尾追加 `curve / hsl / masks` |
| 新增 `RecipePro` 对象 | `RecipeCodec.kt` 第 212 行起 |
| `ColorCore.process(pixels:w:h:p:)` **签名没变** | `ColorCore.kt` 第 170 行 |

三组算子的数学定义、量级、性能预算见 `docs/engine/COLOR-PRO.md`（唯一权威）。
**中性输入 → 输出与 v2 逐字节相同**，是这一轮不能砸掉的第一条承诺
（`ProPipelineIdentityTest` 用 64×64 图钉死）。

### 8.2 Swift 侧怎么写的

**`Support.swift` — 新增 `enum ProNeutral`**，把三个中性值集中在一处构造：

```swift
static func curves() -> ToneCurves { ToneCurves(master: nil, r: nil, g: nil, b: nil) }
static func band() -> HslBand { HslBand(hue: 0, sat: 0, lum: 0) }
static func hsl() -> HslBands {
    HslBands(red: band(), orange: band(), yellow: band(), green: band(),
             cyan: band(), blue: band(), purple: band(), magenta: band())
}
```

**`Support.swift` — `EnginePipeline.makeParams`** 加了三个带默认值的可选参数，
末尾三个实参照 `ColorCore.kt` 的声明顺序传：

```swift
static func makeParams(look: Look, intensity: Float, image: CGImage, grainEdge: Int32,
                       curves: ToneCurves? = nil,
                       hsl: HslBands? = nil,
                       masks: [LocalMask] = []) -> EngineParams
// …
    curves: curves ?? ProNeutral.curves(),
    hsl:    hsl    ?? ProNeutral.hsl(),
    masks:  masks
```

默认值走中性，所以 `YishunView.swift` 里**已有的 4 处 `makeParams(...)` 调用点
一个字都不用改**，输出与 v2 逐位相同。这也是刻意的：中性三组在 `EngineParams.init`
里被判掉（四张 LUT 全 null / `hasAny == false` / `maskField == null`），
`ColorCore.processRange` 里主循环之后那三段一次都不进。

`masks` 用 `[LocalMask]` 不是 `NSArray`：Kotlin `List<T>` 导出到 Swift 就是数组类型。
参照先例是 `SceneAnalyzerCore.analyze(faces:)` 那一处的 `faces: []`（它在第 6 节第 6 条
里被记为「已按 Kotlin 源码核对」，但**同样没在 Mac 上编译过**，所以这只是一致性参照，
不是已验证的凭据）。当前 iOS 只传空数组，非空桥接的风险要等接面板时才真正暴露。

**`YishunView.swift` — `export()`**：`YishunRecipe(v: 3, …)` 补了 `curve: nil,
hsl: nil, masks: nil`，注释里的「核对为 2」改成 3。

### 8.3 ⚠ 哪些签名是猜的（逐条，必须真机核对）

按「错了会怎么暴露」排序。**第 1 条最可能先炸（编译错），第 3 条最危险（编译过、
只是颜色错）**。第 7-9 条是 8.6 读路径新增的，都集中在两三个函数里，好改。

下面命令里的 `$HDR` 都指：
`shared/build/bin/iosArm64/debugFramework/SuiPaiKit.framework/Headers/SuiPaiKit.h`
（先 `HDR=...` 赋给 shell 变量，或直接替换成上面这条路径）。

| # | 猜的东西 | 我写成了 | 为什么不确定 | 错了会怎样 |
| --- | --- | --- | --- | --- |
| 1 | **`ToneCurves` / `HslBands` / `HslBand` / `LocalMask` 的类型名** | 裸名 | 这四个都没标 `@ObjCName(exact = true)`，K/N 会给未固定名加 `SuiPaiKit` 前缀 | `cannot find type 'ToneCurves' in scope` |
| 2 | **`HslBand` 能否零参构造** | `HslBand(hue: 0, sat: 0, lum: 0)` | Kotlin 三个参数全有默认值，但 K/N **不导出 Kotlin 默认值**，只生成全参构造器 | 若真存在零参 `init()`，我这种写法照样编过 → **风险很低** |
| 3 | **`HslBands` 的 8 个参数标签与顺序** | `red, orange, yellow, green, cyan, blue, purple, magenta` | 照 `HslBands.kt` 第 22-31 行抄，应该对；但 8 个同类型参数，错一个不会报错、只会静默串色 | 编译过但颜色不对（这是最危险的一类） |
| 4 | **`ToneCurves` 的 4 个参数标签** | `master, r, g, b`，全 `nil` | 同上，照 `ToneCurve.kt` 第 262-267 行抄 | 编译错（标签不对）；静默风险低 |
| 5 | **`YishunRecipe` 的 `curve` / `hsl` / `masks` 参数形态** | 三个全 `nil` | `List<RecipeMask>?` 在 Swift 侧是 `[RecipeMask]?` 还是 `NSArray?` 未验证；**传 `nil` 在两种形态下都成立** | 风险很低（可选值兼容两种） |
| 6 | **`masks: [LocalMask]` 能否直接桥接 `List<LocalMask>`** | 直接传 Swift 数组 | `List<T>` → `NSArray` 的自动桥接有 `analyze(faces:)` 先例，但那是空数组 | 非空时会 `Type mismatch`；**8.6 之后会真的传非空了**，这条从"暂不触发"升级为"必须核" |
| 7 | **`RecipePro.withPro(base:curves:hsl:masks:)` 的 Swift 形态** | `RecipePro.shared.withPro(base:curves:hsl:masks:)` | `RecipePro` 是 Kotlin `object`，按既有 `<对象>.shared.<函数>` 形态写；参数顺序照 `RecipeCodec.kt` 第 221-233 行 | 编译错；兜底是"三组留 nil、只保留 9 个微调位"（功能降级但不崩） |
| 8 | **`RecipePro.curvesOf / hslOf / masksOf`** | `RecipePro.shared.curvesOf(recipe:)` 等 | 同上。这三个是 8.6 读路径的核心 | 编译错。**全部收敛在 `ProInputs.of(recipe:)` 一个函数里**，改只改那一处 |
| 9 | **`RecipeCodec.decode(text:)`** | `RecipeCodec.shared.decode(text: text)` | 同对象的 `encodeForExif(recipe:)` 已在用，标签规则应一致；但 `decode` 本身从未被 iOS 调用过 | 编译错；改 `RecipeReader.recipe(from:)` 一行 |
| 10 | **`YishunRecipe` 的 9 个微调位能否按名读** | `recipe.grainMul` / `.tempShift` 等 | 这些是普通 `val`，`data class` 的属性导出形态在 Swift 侧一直是 `recipe.xxx`（`recipe.lookId` / `recipe.intensity` 已按此用法写过） | 风险很低 |

`ProNeutral` 里那几个 `HslBand` / `ToneCurves` 的构造器，**每一个都是猜的**，
但都收敛在 `Support.swift` 的 `ProNeutral` 这一个 enum 里 —— 改只改一处。

### 8.4 给 Mac 上的人的核对步骤

**第 0 步：先拿到 Objective-C 头**。这是唯一的事实来源，别靠猜。

```bash
cd /path/to/SuiPai
./gradlew :shared:assembleSuiPaiKitDebugIosArm64
# 头文件在：
#   shared/build/bin/iosArm64/debugFramework/SuiPaiKit.framework/Headers/SuiPaiKit.h
```

**第 1 步：核类型名**（对应 8.3 第 1 条）。

```bash
grep -nE '@interface (SuiPaiKit)?ToneCurves|@interface (SuiPaiKit)?HslBands|@interface (SuiPaiKit)?HslBand' \
  shared/build/bin/iosArm64/debugFramework/SuiPaiKit.framework/Headers/SuiPaiKit.h
```

- **期望看到**：`@interface ToneCurves` / `@interface HslBands` / `@interface HslBand`。
- **不对的样子**：`@interface SuiPaiKitToneCurves` —— 那就是加了前缀。
  二选一：① 在 `shared/.../ToneCurve.kt`、`HslBands.kt`、`LocalMask.kt` 的类上照
  `EngineParams` 补 `@kotlin.native.ObjCName("ToneCurves", exact = true)`
  （推荐，一次修两端，但**这一轮约定不改 shared**，所以要走这条得先跟 lead 说）；
  ② 在 `Support.swift` 顶部加 `typealias`。

**第 2 步：核 `ToneCurves` / `HslBands` / `HslBand` 的构造器**（8.3 第 2/3/4 条）。

```bash
grep -nA3 'initWithMaster' $HDR
grep -nB2 -A10 'interface HslBands' $HDR
```

- **期望看到**：`- (instancetype)initWithMaster:(ToneCurve * _Nullable)master r:… g:… b:…;`
  与 `initWithRed:orange:yellow:green:cyan:blue:purple:magenta:`。
- Swift 侧的标签由 K/N 生成：ObjC 的 `initWithRed:orange:` → Swift 的
  `HslBands(red:orange:)`。**逐个字母对**，8 个带错一个不会报错。
- 若还额外生成了 `- (instancetype)init`（零参），那 8.3 第 2 条也顺带解决，
  但**不要**因此改成 `HslBands()` —— 现在的显式写法在两种情况下都成立。

**第 3 步：核 `EngineParams` 的 18 个参数**（8.3 表外，但最容易漏）。

```bash
grep -n -A22 'interface EngineParams' $HDR
```

- **期望看到**：`initWithLook:intensity:w:h:grainEdge:seed:grainMul:vignetteMul:tempShift:
  contrastShift:satShift:shadowShift:fadeShift:highlightShift:softShift:curves:hsl:masks:`，
  **18 个冒号**，且 `curves` / `hsl` / `masks` 在**最后三个位置**。
- **不对的样子**：三个新参数插在中间（说明我看错了声明顺序）、或 `masks` 的类型是
  `NSArray<LocalMask *> *` 而 `Support.swift` 里传的是 Swift 数组（这种能自动桥接，没问题，
  但若生成的是 `KotlinArray` 就得改成 `KotlinArray` 构造）。

**第 4 步：核 `YishunRecipe` 的 16 个字段**。

```bash
grep -n -A20 'interface YishunRecipe' $HDR
```

- **期望看到**：最后三个是 `curve`（`RecipeCurves * _Nullable`）、
  `hsl`（`RecipeHsl * _Nullable`）、`masks`（`NSArray<RecipeMask *> * _Nullable`）。
- 构造器参数顺序必须与 `RecipeCodec.kt` 第 23-54 行一致；`v` 在最前。
- **看到什么算不对**：`v` 不在第一个、或三个新字段不在最后三个。

**第 5 步：核行为（编译过之后）**。这是纯 Swift 侧的自检，别省：

1. 打开一瞬，随便选个 Look 导出 → 存下来的 JPEG 的 EXIF UserComment 应该是
   `ASCII\0\0\0` + `SPRECIPE1:` 开头，Base64 解出来是
   `{"v":3,"lookId":…,"intensity":…}` —— **只有 3 个键**（`exportEdge` 因为等于默认值
   4096 也被 `encodeDefaults = false` 省掉了，解码端会补回 4096，语义没错）。
   多出 `tempShift:0` 这类键说明 `encodeDefaults = false` 没生效（那是 shared 侧的问题）；
   少 `v` 或 `v` 不是 3 说明 8.2 的改动没生效。
2. 同一张图，「改前」与「改后」的预览应当是**逐像素相同**的（中性三组不进分支）。
   如果肉眼看出差异，说明某一个中性值不等价于 Kotlin 的默认值。
3. 拿一张 Android 侧导出的 v2 配方照片进来（`RecipeCodec.decode`）能读出 lookId —— 
   顺带证明 v3 的 `decode` 没有把 v2 payload 拒掉。

### 8.5 为什么两端的配方文件不一样长，但都对

这一条解释一个**看起来像 bug、其实是设计**的现象：同一套参数，iOS 写出来的配方
比 Android 短。

`RecipeCodec` 的 JSON 用的是 `encodeDefaults = false`，所以**任何等于声明默认值的
字段都不会写出去**（唯一例外是 `v`，它带 `@EncodeDefault(ALWAYS)`）。

| 字段 | Kotlin 默认值 | iOS 实际值 | 会不会写出去 |
| --- | --- | --- | --- |
| `v` | `CURRENT_VERSION` | 3 | **总是**（`@EncodeDefault`） |
| `lookId` / `intensity` | 无默认（必填） | 真值 | 总是 |
| 8 个 shift | 0 | 0 | 否 |
| `grainMul` / `vignetteMul` | 1 | 1 | 否 |
| `exportEdge` | **4096** | `EngineLimits.exportEdge` = **4096** | 否（恰好相等） |
| `curve` / `hsl` / `masks` | null | 见 8.6 | 仅在带专业三组时 |

所以 iOS 的**零偏移**导出实际只有 3 个键：`{"v":3,"lookId":…,"intensity":…}`。

**Android 不一定是这个形状**：那边的 `exportEdge()` 按可用内存分档
（`<192MB → 1600`、`<384MB → 2560`、否则 `4096`，见 `app/.../engine/ColorPipeline.kt`）。
只要机器不是最大档，`exportEdge` 就**不等于** 4096，于是会被写出去；
同理用户在安卓上调过颗粒/暗角，`grainMul` / `vignetteMul` 也不再是 1。

> 结论：**键数是"这一组参数偏离默认值多少"的函数，不是版本号决定的。**
> 解码端一律按"缺省即默认值"补回，所以两边读出来的 `YishunRecipe` 完全等价。
> 判断一份配方对不对，**不要数键**，要看解出来的字段值。

（这条同样是 `RecipeV3Test.v2PayloadWithoutNewKeysProducesIdenticalKnobs` 的前提：
老 payload 里没有 `curve`/`hsl`/`masks` 三个键，读进来就是三个 null。）

### 8.6 读取别人的专业配方（v3 读路径）

**之前的破口**：iOS 只**写**配方，从来**不读**。`RecipeCodec.decode` 虽然在
`Support.swift` 的注释里被提到过，但整个 `ios/` 里**没有任何一处调用它** ——
所以"配方随照片走"这条承诺在 iOS 上只有一半：写得出，读不回。

现在补上了，链路是：

```
照片字节 Data
   │  RecipeReader.userComment(from:)      ← ImageIO 读 kCGImagePropertyExifUserComment
   ▼  (ASCII\0\0\0 + SPRECIPE1:Base64Url)
RecipeReader.recipe(from:)
   │  RecipeCodec.shared.decode(text:)     ⚠ 未验证
   ▼
YishunRecipe
   │  ProInputs.of(recipe:)                ⚠ 内部三次 RecipePro.shared.* 调用未验证
   │  RecipeKnobs.of(recipe:)
   ▼
detectedPro / detectedKnobs  ──►  makeParams(curves:hsl:masks:knobs:)
                                          │
                                    ColorCore.process
```

四条设计决定，都有理由：

1. **自动套用，但保留「忽略」。** 读到配方且 lookId 还在库里时，`load()` 的**首帧**
   就按配方渲染（`selectedId` / `intensity` / 9 个微调位 / 专业三组全按配方），
   横幅显示「已还原」，点「忽略」才回到智能适配。

   > **决议：这是 iOS 与 Android 的「有意分歧」，不是没对齐，别把它当 bug 修掉。**
   >
   > - **iOS：打开即还原。** 「配方随照片走」的全部意义就是成片再打开时看到当时
   >   那个样子。若先渲染智能适配，用户先看到的是**别人的**调色，不点「还原」
   >   就一直是错的。iOS 的一瞬更像"看这张照片当时的调色"。
   > - **Android：先弹横幅问一句再套。** 那其实是一种**退让** —— 安卓的一瞬是
   >   重编辑入口（专业面板、微调滑杆、我的配方都在那里），用户很可能想从零开始，
   >   所以不该替他决定用哪条。
   >
   > 两端入口性质不同，所以默认行为不同。**将来若 iOS 也做了完整专业编辑面板，
   > 这条应重新评估** —— 到那时安卓那种"先问"的理由在 iOS 上也成立了。

   边界：lookId **不在库里**（别的版本导出的成片）时不自动套，退回智能适配，
   横幅的「还原」按钮置灰 —— 与 Android「这个配方指的色调不在库里」同一结论。
2. **必须用原图字节读。** `ImageEncoder.cgImage(from:maxEdge:)` 走的是缩略图解码，
   拿到的 `CGImage` 里已经没有 UserComment 了。所以 `readRecipeFromInput()` 对
   `.asset` 会再走一次 `PhotoInputLoader.requestData`。
3. **相机拍的新图没有配方**，`.imageData` 分支照常读、读不到就静默返回 `nil`。
   读不到是**常态**，不是错误 —— 所以不弹任何提示。
4. **手动换 Look 会清掉专业三组**（`clearProFromRecipe()`），「忽略」也会。
   不清就会出现「配方说没曲线、画面还带着上一条的曲线」——
   安卓那边同一条警告写在 `ProOps.of` 的注释里。
5. **蒙版出厂降级（`ProInputs.enableMasks = false`）。** 曲线 + 分色照常还原，
   `masks` 先丢掉，横幅上明写「这条配方带局部蒙版，本版本暂不还原」。
   理由是风险不对称：`ToneCurves` / `HslBands` 桥接错了顶多数值不对，
   而 `List<LocalMask>`（`List<数据类>`）桥接错了是**崩**或整张图糊掉。
   打开步骤与验证方式见 `ios/VERIFY-ON-MAC.md` 第 6 条。

**导出时也会把当前生效的微调位与专业三组写回去**，所以「还原别人的配方 → 另存一张」
不会丢参数。这条路径用的是**从配方解出来的原始值**，不是从 `EngineParams` 反推
（后者走不通：构造期已烘焙成数组，控制点没留下）。

⚠ 降级状态下的一个刻意取舍：`hasAny` **不把** `recipe.masks` 算进去。
既然蒙版被丢了，就不能让 `RecipePro.withPro` 把一条**包含蒙版**的配方写出去 ——
那会写成"配方说有蒙版、画面里没有"，比丢掉更糟（下次读回来会真的去应用一个
从没生效过的蒙版）。所以降级时导出的是「曲线 + 分色 + 微调位」，蒙版字段留空。
用户看到的是横幅那句话，不是一个自相矛盾的配方。

---

## 9. 上 Mac 之后的验收

`BUILD.md` 第 8 节讲的是**为什么这么写**；真正照着一步步做、按顺序勾选的清单在
**`ios/VERIFY-ON-MAC.md`**（目标：30 分钟内跑完，每条都给出「期望看到什么」
和「不对的话改哪个文件的哪一行」）。先做那份，再回来看本节。

