# iOS · 上 Mac 之后的验收清单（约 30 分钟）

> **这份文件写给"第一次拿到这台 Mac 的人"。** 本仓库在 Windows 上开发，
> iOS 的 Swift 代码**一行都没编译过**。所以下面每一步都是「做什么 → 期望看到什么 →
> 不对的话改哪里」，按顺序做，别跳。
>
> 分工：**为什么这么写**在 `BUILD.md` 第 8 节；**照着做**在本文件。
> 全部做完预计 25–35 分钟（含第一次 Gradle 下载 Kotlin/Native 工具链的时间，那步可能更久）。

**总失败原则**：每一条都给了「预期失败信号」。**看到信号就去改指定的那一行，别整个文件重写。**
本项目所有与 KMP 的接触点都被刻意收敛到了少数几个函数里。

---

## 第 0 步 · 先让 Kotlin 侧编译出来（5–15 分钟，视网络）

```bash
cd <repo>
./gradlew :shared:assembleSuiPaiKitDebugIosSimulatorArm64
```

- ✅ **期望**：`BUILD SUCCESSFUL`，且存在
  `shared/build/bin/iosSimulatorArm64/debugFramework/SuiPaiKit.framework`。
- ❌ **失败信号**：`SDK "iphonesimulator" cannot be located`
  → 不是代码问题，是 Xcode command line tools 没装好：`xcode-select --install`。
- ❌ `Cannot find a Java installation` → 装 JDK 17（见 `BUILD.md` §1）。

> 从这一步开始，下面所有 `$HDR` 都指这个路径：
> `shared/build/bin/iosSimulatorArm64/debugFramework/SuiPaiKit.framework/Headers/SuiPaiKit.h`
> 先设一下：`HDR=shared/build/bin/iosSimulatorArm64/debugFramework/SuiPaiKit.framework/Headers/SuiPaiKit.h`

---

## 第 1 步 · 核**类型名**是否带 `SuiPaiKit` 前缀 ⚠ 最高风险

这是最可能第一个炸的地方：v3 新加的 `ToneCurves` / `HslBands` / `HslBand` /
`LocalMask` 在 `shared` 里**都没有** `@ObjCName(exact = true)`，
K/N 可能给它们加框架前缀。

```bash
grep -nE '@interface (SuiPaiKit)?(ToneCurves|HslBands|HslBand|LocalMask|CurvePoint|LinearMask|RadialMask|LocalAdjust)\b' "$HDR"
```

- ✅ **期望**：八个 `@interface ToneCurves` / `@interface HslBands` / …（**裸名，无前缀**）。
- ❌ **失败信号**：`@interface SuiPaiKitToneCurves` 这种带前缀的。

**改哪里**（二选一，首选 ①）：

1. **改 shared（推荐，一次修两端）**：在对应的 Kotlin 类上加注解，照 `EngineParams` 的写法：
   `ToneCurve.kt` 的 `ToneCurves`、`HslBands.kt` 的 `HslBands` / `HslBand`、
   `LocalMask.kt` 的 `LocalMask` / `LocalAdjust` / `LinearMask` / `RadialMask`。
   ```kotlin
   @OptIn(kotlin.experimental.ExperimentalObjCName::class)
   @kotlin.native.ObjCName("ToneCurves", exact = true)
   data class ToneCurves(...)
   ```
   ⚠ 这违反本轮的"只改 ios/"约定 —— **要走这条路先跟 lead 说一声**。
2. **改 Swift（不碰 shared）**：在 `ios/SuiPaiIOS/Support.swift` **文件顶部**
   （`import SuiPaiKit` 之后）加一组：
   ```swift
   typealias ToneCurves = SuiPaiKitToneCurves
   typealias HslBands   = SuiPaiKitHslBands
   typealias HslBand    = SuiPaiKitHslBand
   typealias LocalMask  = SuiPaiKitLocalMask
   ```
   （实际前缀名以 grep 到的为准。）

---

## 第 2 步 · 核 `EngineParams` 的 **18 个冒号**

```bash
grep -n -A24 '@interface EngineParams' "$HDR" | grep -E 'initWith'
```

- ✅ **期望**：一条 `initWithLook:...:masks:`，**18 个冒号**，且最后三个依次是
  `curves:` `hsl:` `masks:`。
  数冒号：`grep -o ':' <<< "<那一行>" | wc -l`（再减掉返回值里的那些，以肉眼为准）。
- ❌ **失败信号**：
  - 只有 15 个冒号 → 你拿到的是 v2 的框架，**重新跑第 0 步**（八成是 Gradle 缓存了旧产物）。
  - `curves` 在中间而不是倒数第三个 → 我在 `Support.swift` 里抄错了声明顺序。
    **改哪里**：`Support.swift` 的 `EnginePipeline.makeParams` 末尾三个实参的顺序
    （`curves:` / `hsl:` / `masks:`），以及变量声明里的顺序。

---

## 第 3 步 · 核 `RecipePro` 的三个方法 ⚠

```bash
grep -n -A6 '@interface RecipePro' "$HDR"
```

- ✅ **期望**：三个实例方法，形如
  `- (ToneCurves *)curvesOfRecipe:(YishunRecipe *)recipe;`
  `- (HslBands *)hslOfRecipe:(YishunRecipe *)recipe;`
  `- (NSArray<LocalMask *> *)masksOfRecipe:(YishunRecipe *)recipe;`
- Swift 侧看到的标签由 K/N 生成：`curvesOf(recipe:)`。
- ❌ **失败信号**：方法名被改成 `curvesOfRecipe:` 这种**没有 Swift 标签**的形式，
  或 `RecipePro` 变成 `SuiPaiKitRecipePro`。

**改哪里**：`Support.swift` 的 `ProInputs.of(recipe:)` —— 那一个函数里只有三行调用，
改这三行即可，**调用方一个字都不用动**。
（若 `RecipePro` 带前缀，同第 1 步加 `typealias`。）

---

## 第 4 步 · 核 `RecipeCodec.decode`

```bash
grep -n -E 'decodeText:|decode:' "$HDR"
```

- ✅ **期望**：`- (YishunRecipe * _Nullable)decodeText:(NSString *)text;`
  → Swift 里是 `RecipeCodec.shared.decode(text:)`。
- ❌ **失败信号**：没有 `decodeText`，只有 `encodeForExif` → 说明你看的是旧头文件。

**改哪里**：`Support.swift` 的 `RecipeReader.recipe(from:)`，**只有一行**。

---

## 第 5 步 · 编译整个 App

```bash
cd ios && xcodegen generate && open SuiPaiIOS.xcodeproj
# Xcode 里 ⌘B
```

- ✅ **期望**：`Build Succeeded`。
- ❌ **失败信号与对应改法**：

  | 报错长什么样 | 去哪改 |
  | --- | --- |
  | `cannot find type 'ToneCurves' in scope` | 第 1 步 |
  | `value of type 'EngineParams' has no member ...` / `extra argument 'curves'` | 第 2 步 |
  | `type 'RecipePro' has no member 'curvesOf'` | 第 3 步 |
  | `type 'RecipeCodec' has no member 'decode'` | 第 4 步 |
  | `cannot find 'masksOf' in scope` | 第 3 步（注意 `enableMasks = false` 时这行**仍要能编译**，只是不执行） |

> ⚠ 注意：`ProInputs.enableMasks` 是 `false` 时，`masksOf` 那一行在 `if` 的
> 另一支里，**仍然参与编译**。所以第 3 步的报错不会因为开关关着而消失。

---

## 第 6 步 · 验 `masks` 桥接（**先验这个，再决定要不要打开开关**）

出厂 `ProInputs.enableMasks = false`。这一步是**在打开它之前**先证明桥接是好是坏。

1. 打开 `ios/SuiPaiIOS/Support.swift`，找到 `static let enableMasks = false`，**临时**改成 `true`。
2. 准备一张**带蒙版**的 v3 配方成片（找 lead 要 base64，或自己用安卓导一张）。
3. 一瞬里打开它，观察：

   - ✅ **期望**：正常显示，蒙版区域（径向亮/暗）位置正确，**不崩**。
   - ❌ **崩溃信号**（任一即是桥接有问题）：
     - `EXC_BAD_ACCESS` 停在 `RecipePro.masksOf` 或 `LocalMask` 相关帧；
     - `KotlinException` 提到 `ClassCastException` / `UnsupportedOperation`；
     - 在 `EngineParams.init` 里崩（那是 `masks` 被传进 Kotlin 构造函数时炸的）。
   - ❌ **糊掉信号**：整张图变成均匀的灰/黑/纯色 → 蒙版权重场算成了 0 或 NaN。

4. **验完不论成败，先把 `enableMasks` 改回 `false`**，把结论记到本节下面的空位。

**如果不通过**：`enableMasks` 保持 `false` 就是正确的出厂状态，曲线 + 分色照常工作；
在 `Support.swift` 的 `enableMasks` 注释里补一句实测结论，并告诉 lead。

实测结论：<!-- 在 Mac 上填：通过 / 崩溃（贴栈顶三帧）/ 糊掉；日期 -->

---

## 第 7 步 · 验配方**读→写→读**一致

1. 拿一张带 v3 配方的图进一瞬 → 应该自动按配方渲染（横幅显示「已还原」）。
2. 点「应用并保存」导出。
3. 用 macOS 的 `exiftool` 看新文件的 UserComment：

   ```bash
   exiftool -UserComment <导出的文件>.jpg
   ```

   - ✅ **期望**：`ASCII\0\0\0SPRECIPE1:` 开头（前 8 字节是字符码），
     后面 Base64 解出来含 `"v":3` 和原配方的 `lookId`。
   - ❌ **失败信号**：
     - 前 8 字节不是 `ASCII\0\0\0` → `RecipeCodec.encodeForExif` 没被用上（检查 `export()` 里是不是误用了 `encode`）。
     - 一条**零偏移**的配方（`{"v":3,"lookId":…,"intensity":…}`，3 个键）→ 说明还原没生效，
       回到第 3 步。**注意：如果原配方本来就是零偏移，3 个键是正常的。**

4. 把导出的文件**重新**拖进一瞬 → 应该还原出同样的颜色（这一条是"配方随照片走"的闭环）。

---

## 第 8 步 · 验中性路径**逐像素不变**

这一条证明 v3 的改动没有污染 v2 的输出。

1. 随便选一张普通照片（没有配方），选一条 Look，记下预览。
2. 用 git 切到 **v3 改动之前**的提交，`⌘B` 跑一次，同一张图同一 Look 导出。
3. 比两个文件：

   ```bash
   cmp -l a.jpg b.jpg | wc -l   # 期望 0
   ```

   - ✅ **期望**：**0**（中性三组在 `EngineParams.init` 里就被守卫掉，不进任何分支）。
   - ⚠ 若不为 0：先确认两边的 `grainEdge` 一样（都应是 4096），
     以及 JPEG 编码参数一致。**这是最容易误报的一条**，不为 0 先怀疑测量方法。

---

## 第 9 步 · 收尾

```bash
./gradlew --stop          # Kotlin/Native 守护进程很吃内存
```

- 别忘了把第 6 步的结论填上，以及把本文件里做过但**结果与预期不符**的条目
  回报给 lead。**没验的项要写"没验"，不许写成"已修"。**

---

## 附：这一轮全部 ⚠ 未验证签名（一张表看全）

| # | 位置 | 签名 | 对应验收步骤 |
| --- | --- | --- | --- |
| 1 | `Support.swift` `ProNeutral` / `ProInputs` | `ToneCurves` / `HslBands` / `HslBand` / `LocalMask` 的**类型名** | 第 1 步 |
| 2 | 同上 | `ToneCurves(master:r:g:b:)` 四个标签 | 第 5 步 |
| 3 | 同上 | `HslBands(red:orange:yellow:green:cyan:blue:purple:magenta:)` 八个标签**顺序** | 第 5 步（错一个不报错、只会串色） |
| 4 | 同上 | `HslBand(hue:sat:lum:)` | 第 5 步 |
| 5 | `Support.swift` `makeParams` | `EngineParams` **18 个参数顺序** | 第 2 步 |
| 6 | `YishunView.swift` `export()` | `YishunRecipe(curve:hsl:masks:)` 三个 `nil` | 第 5 步 |
| 7 | `Support.swift` `ProInputs.of` | `RecipePro.shared.curvesOf/hslOf/masksOf(recipe:)` | 第 3 步 |
| 8 | `YishunView.swift` `export()` | `RecipePro.shared.withPro(base:curves:hsl:masks:)` | 第 5 步 |
| 9 | `Support.swift` `RecipeReader.recipe` | `RecipeCodec.shared.decode(text:)` | 第 4 步 |
| 10 | `Support.swift` `ProInputs` | 非空 `[LocalMask]` 的桥接 | 第 6 步 |

前 4 条错了集中在 `ProNeutral` / `ProInputs` 两个类型里；第 5-9 条各只影响一个函数；
第 10 条有开关兜底（`enableMasks = false`）。
