import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import UIKit
import Photos
import SuiPaiKit

// MARK: - 引擎常量

/// 与 Kotlin 侧对齐的常量。
enum EngineLimits {
    /// 对应 LookLibrary.AUTO_ID。这里写死字面量而不是引用 Kotlin 的 const val，
    /// 是因为 `const val` 在 Kotlin/Native 里导出成类属性还是 shared 实例属性
    /// 不确定；值已与 shared 源码核对（AUTO_ID = "auto"）。详见 BUILD.md 第 6 节。
    static let autoLookId = "auto"
    static let previewEdge: Int32 = 1600
    static let exportEdge: Int32 = 4096
    static let thumbEdge: Int32 = 128
    static let albumName = "SuiPai"
    static let exportQuality: CGFloat = 0.95
}

// MARK: - 像素缓冲

/// 引擎只认「ARGB 打包进 Int32 的 IntArray」，这里就是那层适配。
struct ARGBBuffer {
    let pixels: KotlinIntArray
    let width: Int
    let height: Int
}

/// ⚠ 已知性能债（本次刻意没改，需要在 Mac 上验证才能动）：
/// 下面两个转换是**逐像素**走 KotlinIntArray 的 get(index:)/set(index:value:)，
/// 每一次都是一条 ObjC 消息发送；4096×4096 就是 3300 万次跨语言往返，而 Android
/// 侧只有一次 getPixels 批量拷贝（见 app/.../engine/ColorPipeline.kt）。
/// 正确的修法是在 shared/src/iosMain 加一层门面：把 CGImage 的位图内存
/// （dataProvider 拿到的 CFData 指针）包成 CPointer<Int32> 交给 Kotlin，
/// 在 Kotlin 侧直接遍历内存，Swift 不再逐元素往返。
/// iosMain 依赖 platform.Foundation/platform.posix 的 cinterop，Windows 上
/// 根本编不出来，改动无法验证，所以先记账。
enum PixelBridge {
    /// CGImage → RGBA8 → ARGB Int32 数组。
    /// 照片恒为不透明（alpha=255），用 premultipliedLast 与 Android 的
    /// ARGB_8888 语义一致，直方图/调色结果不会因预乘产生偏差。
    static func argbBuffer(from image: CGImage) -> ARGBBuffer? {
        let w = image.width
        let h = image.height
        guard w > 0, h > 0 else { return nil }
        let bytesPerRow = w * 4
        var rgba = [UInt8](repeating: 0, count: bytesPerRow * h)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        let ok: Bool = rgba.withUnsafeMutableBytes { raw -> Bool in
            guard let base = raw.baseAddress,
                  let ctx = CGContext(data: base, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                      space: space, bitmapInfo: bitmapInfo) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }

        let count = w * h
        let arr = KotlinIntArray(size: Int32(count))
        for i in 0..<count {
            let o = i * 4
            // 用 UInt32 组装再 bitPattern 转换：alpha=0xFF 时结果超出 Int32.max，
            // 直接算 Int32 会溢出崩溃。
            let v = (UInt32(rgba[o + 3]) << 24)
                | (UInt32(rgba[o]) << 16)
                | (UInt32(rgba[o + 1]) << 8)
                | UInt32(rgba[o + 2])
            arr.set(index: Int32(i), value: Int32(bitPattern: v))
        }
        return ARGBBuffer(pixels: arr, width: w, height: h)
    }

    /// ARGB Int32 数组 → RGBA8 → CGImage。
    static func cgImage(from buffer: ARGBBuffer) -> CGImage? {
        let w = buffer.width
        let h = buffer.height
        let count = w * h
        guard count > 0 else { return nil }
        var rgba = [UInt8](repeating: 255, count: count * 4)
        for i in 0..<count {
            let v = UInt32(bitPattern: buffer.pixels.get(index: Int32(i)))
            rgba[i * 4] = UInt8((v >> 16) & 0xFF)
            rgba[i * 4 + 1] = UInt8((v >> 8) & 0xFF)
            rgba[i * 4 + 2] = UInt8(v & 0xFF)
            rgba[i * 4 + 3] = UInt8((v >> 24) & 0xFF)
        }
        let data = Data(rgba)
        guard let provider = CGDataProvider(data: data as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: w, height: h,
                       bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: space,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil,
                       shouldInterpolate: false, intent: .defaultIntent)
    }

    /// 等比缩放到最长边不超过 maxEdge；已够小则原样返回。
    static func scaled(_ image: CGImage, maxEdge: Int) -> CGImage? {
        let longest = max(image.width, image.height)
        guard longest > 0 else { return nil }
        if longest <= maxEdge { return image }
        let scale = CGFloat(maxEdge) / CGFloat(longest)
        let w = max(1, Int((CGFloat(image.width) * scale).rounded()))
        let h = max(1, Int((CGFloat(image.height) * scale).rounded()))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h,
                                  bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return nil
        }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }
}

// MARK: - 专业分级三组算子的中性值（配方 v3）

/// 曲线 / 分色 / 蒙版这三组算子的**中性值**，集中在这一处构造。
///
/// 为什么单独收一个 enum：这三组参数里每一个都是「猜的 Swift 签名」—— shared 侧
/// `ToneCurves` / `HslBands` / `HslBand` / `LocalMask` 都**没有** `@ObjCName(exact = true)`，
/// 而 Kotlin 的默认参数值又不导出到 Objective-C。把全部不确定的构造收敛到一个地方，
/// Mac 上首次编译出错时只改这一块就够（核对步骤见 BUILD.md 第 8 节）。
///
/// ⚠ **本 enum 里的所有构造器均未在 Xcode 验证**（这台机器是 Windows，没有 Xcode，
/// 一行 Swift 都没编译过）。不确定的只有两件事，其余（参数名、参数顺序、类型）都是
/// 照 `shared/src/commonMain/kotlin/com/suipai/core/engine/` 的源码逐字抄的：
///
/// 1. **类型名可能带框架前缀**。K/N 会给未用 `@ObjCName` 固定的名字加 baseName 前缀
///    （这里是 `SuiPaiKit`），所以 `ToneCurves` 的真名可能是 `SuiPaiKitToneCurves`。
///    首次编译若报 `cannot find type 'ToneCurves' in scope`，按 BUILD.md 第 6 节第 13 条
///    二选一：shared 侧补 `@ObjCName(exact = true)`（推荐，一次修两端），或在本文件顶部加
///    `typealias ToneCurves = SuiPaiKitToneCurves` 收口。四个类型可能都要加。
///
/// 2. **能不能零参构造**。`ToneCurves` / `HslBands` / `HslBand` 的构造参数在 Kotlin 侧
///    **全都有默认值**，但 Kotlin 的默认参数不会导出成多个重载，所以这里一律**显式传满**：
///    宁可多写几个 `nil` / `0`，也不赌 `ToneCurves()` 这种零参构造存在。
///    反过来说，即使 K/N 真的生成了零参 `init()`，下面这种写法照样能编过 ——
///    「显式全传」在两种情况下都成立，「零参」只在其中一种下成立，所以选前者。
///
/// 另外刻意**不**引用 Kotlin 侧现成的 `HslBands.NONE` / `ToneCurve.IDENTITY`：它们都在
/// companion object 里，Swift 侧到底是 `HslBands.companion.NONE` 还是
/// `HslBands.Companion.shared.NONE` 没验证过 —— 与 `LookLibrary.AUTO_ID` 同一个坑
/// （见上面 EngineLimits.autoLookId 的注释），所以自己构造中性值。
enum ProNeutral {
    /// `ToneCurves(master = null, r = null, g = null, b = null)`：
    /// 四条曲线全为 null。`null` 在 [EngineParams] 构造期判成「不是非恒等曲线」，
    /// 于是 `curveMaster` / `curveR` / `curveG` / `curveB` 全为 null，
    /// 烘焙出来的 `curveByteR/G/B` 也全为 null —— 主循环之后 `applyCurves` 整段跳过。
    static func curves() -> ToneCurves {
        ToneCurves(master: nil, r: nil, g: nil, b: nil)
    }

    /// `HslBand()`：单个色相带的三个偏移全 0（hue/sat/lum 都是 Float）。
    static func band() -> HslBand {
        HslBand(hue: 0, sat: 0, lum: 0)
    }

    /// `HslBands()`：8 个带全中性。字段顺序照 Kotlin 声明抄，不是自己排的：
    /// `red / orange / yellow / green / cyan / blue / purple / magenta`，
    /// 对应 `HslBands.HUES = [0, 30, 60, 120, 180, 240, 280, 320]`。
    /// 全部偏移为 0 时 `HslBands.hasAny == false`，`EngineParams.hasHsl` 为 false，
    /// `Hsl.applyRange` 不会被调用。
    static func hsl() -> HslBands {
        HslBands(red: band(),
                 orange: band(),
                 yellow: band(),
                 green: band(),
                 cyan: band(),
                 blue: band(),
                 purple: band(),
                 magenta: band())
    }
}

// MARK: - 引擎管线

/// 从一条配方里取出的**专业三组**（曲线 / 分色 / 蒙版）。
///
/// 这是「能读别人的专业配方」的关键一环：`RecipeCodec.decode` 只负责把 JSON 变回
/// `YishunRecipe`，而配方里的三个字段是 `RecipeCurves?` / `RecipeHsl?` /
/// `List<RecipeMask>?` —— 和引擎要的 `ToneCurves` / `HslBands` / `[LocalMask]`
/// 是**两套类型**。中间的翻译由 Kotlin 的 `RecipePro` 负责（`curvesOf` / `hslOf` /
/// `masksOf`），Swift 侧只做一次转发。
///
/// 为什么 `hasAny` 是自己算的、不去读 `ToneCurves.isNeutral` / `HslBands.isNeutral`：
/// 配方里的三个字段**本来就可空**，`recipe.curve != nil` 就是最直接的判据，
/// 而那两个 `isNeutral` 是 Kotlin 的计算属性，导出成 Swift 的形态没验证过。
/// 少依赖一个未验证签名，就少一个上 Mac 才会炸的地方。
///
/// ⚠ **本结构里的 `RecipePro.shared.*` 三处调用均未在 Xcode 验证。**
/// 依据是 Kotlin `object` → Swift `<对象名>.shared`（仓库里 `ColorCore.shared` /
/// `RecipeCodec.shared` / `LookLibrary.shared` 都是这个形态，已有先例）。
/// 若签名不对（例如生成的是 `RecipePro.companion.curvesOf(...)`），**只改这一个
/// `of(recipe:)` 函数**即可，调用方一个字都不用动。
struct ProInputs {
    let curves: ToneCurves
    let hsl: HslBands
    let masks: [LocalMask]
    /// 这条配方是否**真的**带专业三组。用来决定导出时要不要走 `RecipePro.withPro`
    /// （中性时保持零偏移路径，写出去的字节与 v2 完全一致）。
    let hasAny: Bool
    /// 配方里其实带了蒙版、但被 [enableMasks] 这个开关**丢掉了**。
    /// 界面必须把这件事说出来（见 `YishunView` 的横幅），否则用户会以为
    /// "蒙版效果没生效"是个 bug —— 而它是个已知的、有意的降级。
    let masksDropped: Bool

    static let neutral = ProInputs(curves: ProNeutral.curves(),
                                   hsl: ProNeutral.hsl(),
                                   masks: [],
                                   hasAny: false,
                                   masksDropped: false)

    /// **局部蒙版的桥接开关 —— 出厂关闭，上 Mac 验过再打开。**
    ///
    /// 关掉的理由是风险不对称：
    /// - `ToneCurves` / `HslBands` 是「`data class` 里放数值 + 可空引用」，
    ///   桥接万一错了，最坏结果是**数值不对**（颜色偏了）；
    /// - `masks` 是 **`List<数据类>`（`List<LocalMask>`）**，Kotlin/Native 要把
    ///   每个元素逐个装箱成 Objective-C 对象再拼成 `NSArray`。这一层没验证过，
    ///   错了的后果是**崩**（消息发给不响应的对象 / 类型不匹配），或者一整张图被
    ///   权重场算成 0 后糊掉 —— 不是"少一个效果"这种程度。
    ///
    /// 所以先让曲线 + 分色（90% 的观感）能用，把最危险的这 10% 关在后面。
    ///
    /// **打开步骤**（在 Mac 上）：
    /// 1. 改这一个常量成 `true`；
    /// 2. 按 `ios/VERIFY-ON-MAC.md` 第 6 条走一遍「带蒙版的配方」；
    /// 3. 确认无崩溃、蒙版位置与预览/成片一致（`COLOR-PRO.md` §6 要求归一化除数是
    ///    `w-1` / `h-1`，两端同值）；
    /// 4. 才可以把 `if (!Self.enableMasks)` 那段降级分支删掉。
    ///
    /// ⚠ 这个常量本身是纯 Swift，没有任何未验证 API；**未验证的是它打开之后**
    /// 那一行 `RecipePro.shared.masksOf(recipe:)` 与非空 `[LocalMask]` 的传参。
    static let enableMasks = false

    /// 配方 → 三组算子。v2 配方（三个字段都是 null）会得到全中性，
    /// 语义与 Android 的 `ProOps.of(recipe)` 逐条对齐。
    ///
    /// ⚠ 三个 `RecipePro.shared.*` 调用未验证（见类型注释）。
    static func of(recipe: YishunRecipe) -> ProInputs {
        // `masks` 在 Kotlin 侧是 `List<RecipeMask>?`，Swift 侧可能是 `[RecipeMask]?`。
        // 这里用 `?.isEmpty == false` 判断，两种形态都成立。
        let recipeHasMasks = recipe.masks?.isEmpty == false
        if Self.enableMasks {
            return ProInputs(
                curves: RecipePro.shared.curvesOf(recipe: recipe),
                hsl: RecipePro.shared.hslOf(recipe: recipe),
                masks: RecipePro.shared.masksOf(recipe: recipe),
                hasAny: recipe.curve != nil || recipe.hsl != nil || recipeHasMasks,
                masksDropped: false
            )
        }
        // 降级路径：曲线 + 分色照常，蒙版**不调用 masksOf**（连那一次跨语言调用都省掉，
        // 于是 `enableMasks = false` 时这条路径完全不碰 `List<数据类>` 的桥接）。
        //
        // hasAny 刻意**不**把 recipeHasMasks 算进去：hasAny 的用途是"导出时要不要走
        // RecipePro.withPro"。既然蒙版被丢了，就不能让 withPro 把一条**包含蒙版**的
        // 配方写出去 —— 那会写成"配方说有蒙版、画面里没有"，比丢掉更糟
        // （下次读回来会真的去应用一个从没生效过的蒙版）。
        // 但用户仍要知道这件事，所以 masksDropped 单独记一笔给界面用。
        return ProInputs(
            curves: RecipePro.shared.curvesOf(recipe: recipe),
            hsl: RecipePro.shared.hslOf(recipe: recipe),
            masks: [],
            hasAny: recipe.curve != nil || recipe.hsl != nil,
            masksDropped: recipeHasMasks
        )
    }
}

/// 配方里那 9 个 v2 微调位（8 个 shift + grainMul + vignetteMul）。
///
/// 单独立一个类型而不是往 `makeParams` 上加 9 个 `Float` 参数：调用点几乎总是
/// 「要么全中性、要么整条配方照抄」，拆成 9 个实参只会让每个调用点多 9 行噪音。
/// 字段顺序与 `EngineParams` 的声明顺序一致（`ColorCore.kt` 第 21-29 行），
/// 与 `YishunRecipe` 的对应字段同名。
///
/// 默认值全是「不改画面」：`grainMul` / `vignetteMul` 是 1（Kotlin 默认也是 1），
/// 其余是 0。所以 `.neutral` 与 v2 时代硬编码在 `makeParams` 里的那串字面量逐位相同。
struct RecipeKnobs {
    var grainMul: Float = 1
    var vignetteMul: Float = 1
    var tempShift: Float = 0
    var contrastShift: Float = 0
    var satShift: Float = 0
    var shadowShift: Float = 0
    var fadeShift: Float = 0
    var highlightShift: Float = 0
    var softShift: Float = 0

    static let neutral = RecipeKnobs()

    /// 从配方原样抄 9 个字段。v2 配方里它们本来就有值（不是可空），所以这条路径
    /// 不依赖任何新增的 Kotlin 签名 —— 只是普通的属性读。
    static func of(recipe: YishunRecipe) -> RecipeKnobs {
        RecipeKnobs(grainMul: recipe.grainMul,
                    vignetteMul: recipe.vignetteMul,
                    tempShift: recipe.tempShift,
                    contrastShift: recipe.contrastShift,
                    satShift: recipe.satShift,
                    shadowShift: recipe.shadowShift,
                    fadeShift: recipe.fadeShift,
                    highlightShift: recipe.highlightShift,
                    softShift: recipe.softShift)
    }
}

enum EnginePipeline {
    /// 微调位默认全中性：iOS 版暂未移植 Android 的微调开关。
    ///
    /// 参数与 Kotlin 的 `EngineParams(look, intensity, w, h, grainEdge = 4096, seed = 0,
    /// grainMul, vignetteMul, tempShift, contrastShift, satShift, shadowShift, fadeShift,
    /// highlightShift, softShift, **curves = ToneCurves(), hsl = HslBands(),
    /// masks = emptyList()**)`（shared/.../engine/ColorCore.kt）一一对应；
    /// Kotlin 的默认值不会导出到 Swift，所以 **18 个参数必须全传**
    /// （v3 之前是 15 个，新增的三个在后面三个位置，顺序即声明顺序）。
    ///
    /// - w/h：取**送进 apply 的这张图**的尺寸。EngineParams 用它算暗角圆心和 maxR，
    ///   而 apply 内部正是用这张图建像素缓冲，从 image 上取就不可能和工作缓冲错位。
    /// - grainEdge：**成片长边**，不是当前工作缓冲的边长（Kotlin 侧那段注释就是讲这个：
    ///   喂预览边长会让颗粒永远吃 0.7× 细颗粒，与成片不是一份颗粒）。
    /// - curves/hsl/masks：v3 新增的三组专业算子，不传就是中性值。
    ///   **默认值必须让老调用点的输出逐位不变**（这是 COLOR-PRO.md §0 的第一条承诺）：
    ///   中性曲线 → 四张 LUT 全 null；8 带全 0 → `hasAny == false`；空蒙版表 →
    ///   `maskField == null`。三者都在 `EngineParams.init` 里被守卫掉，
    ///   `ColorCore.processRange` 里主循环之后的那三段一次都不进。
    /// - knobs：配方里的 9 个 v2 微调位。默认 `.neutral`，与 v2 时代写死的那串
    ///   字面量（`grainMul: 1, vignetteMul: 1, 其余 0`）逐位相同。
    ///   **只有在「应用别人照片里带的配方」时才会传非中性值** ——
    ///   iOS 自己的一瞬页仍然没有这些滑杆，新建的调整永远走中性。
    static func makeParams(look: Look,
                           intensity: Float,
                           image: CGImage,
                           grainEdge: Int32,
                           curves: ToneCurves? = nil,
                           hsl: HslBands? = nil,
                           masks: [LocalMask] = [],
                           knobs: RecipeKnobs = .neutral) -> EngineParams {
        EngineParams(
            look: look,
            intensity: intensity,
            w: Int32(image.width),
            h: Int32(image.height),
            grainEdge: grainEdge,
            // 种子只有一个权威算法：ColorCore.grainSeedFor = Kotlin 的 String.hashCode。
            // Android 也走它（ColorPipeline.apply 的 seed 默认值）。这里以前自己写了个
            // FNV-1a，和 31 进制的 hashCode 结果完全不同，于是同一配方两端长出两套颗粒。
            seed: ColorCore.shared.grainSeedFor(lookId: look.id),
            grainMul: knobs.grainMul,
            vignetteMul: knobs.vignetteMul,
            tempShift: knobs.tempShift,
            contrastShift: knobs.contrastShift,
            satShift: knobs.satShift,
            shadowShift: knobs.shadowShift,
            fadeShift: knobs.fadeShift,
            highlightShift: knobs.highlightShift,
            softShift: knobs.softShift,
            // v3 的三组专业算子。同样是「Kotlin 默认值不导出」，必须显式传；
            // 顺序照 ColorCore.kt 的声明：curves → hsl → masks。
            // 传 nil / 空数组时用 ProNeutral 里的中性值（那里集中记了未验证的签名）。
            curves: curves ?? ProNeutral.curves(),
            hsl: hsl ?? ProNeutral.hsl(),
            // Kotlin 的 List<LocalMask> 导出成 NSArray（Swift 侧应为 [LocalMask]），
            // Swift 数组在这里应能自动桥接 —— 参照 SceneAnalyzerCore.analyze(faces:)
            // 那处的 `faces: []`。⚠ 但非空数组的桥接**未在 Xcode 验证**：
            // 当前 iOS 只传空数组，所以这条风险暂时不触发（见 BUILD.md 8.3 第 6 条）。
            masks: masks
        )
    }

    static func apply(image: CGImage, params: EngineParams) -> CGImage? {
        guard let buf = PixelBridge.argbBuffer(from: image) else { return nil }
        // Kotlin 的 process 返回 Unit、就地改写 pixels，不要把返回值当成新数组。
        // v3 没有改这个签名（ColorCore.kt: `process(pixels, w, h, p)`）：曲线/分色/蒙版
        // 是 `processRange` 内部按 `p` 上的守卫决定要不要跑，调用方不需要知道。
        ColorCore.shared.process(pixels: buf.pixels,
                                 w: Int32(buf.width),
                                 h: Int32(buf.height),
                                 p: params)
        return PixelBridge.cgImage(from: buf)
    }

    /// 场景识别。Kotlin 签名是 `analyze(pixels, w, h, exif: ExifData, faces: List<FaceBox>)`，
    /// exif 非空且六个字段都得传（默认值不导出），所以这里必须构造 ExifData。
    /// 六个字段先全传 nil：iOS 这条路径上手里只有解码后的 CGImage，EXIF 还留在原始 Data 里
    /// 没接出来（ImageEncoder.cgImage(from:maxEdge:) 走的是缩图解码）。等价于 Android 拿不到
    /// ExifInterface 的情形 —— 直方图分支照常工作，只是 ISO/拍摄时刻/闪光灯那几条判断在 iOS
    /// 上不生效。要补的话就照 app/.../engine/SceneAnalyzer.kt 里那份 ExifData 填法来。
    static func analyze(image: CGImage) -> Adaptation? {
        guard let buf = PixelBridge.argbBuffer(from: image) else { return nil }
        let exif = ExifData(iso: nil,
                            flashFired: nil,
                            exposureSec: nil,
                            focal35mm: nil,
                            wbMode: nil,
                            hourOfDay: nil)
        return SceneAnalyzerCore.shared.analyze(pixels: buf.pixels,
                                                w: Int32(buf.width),
                                                h: Int32(buf.height),
                                                exif: exif,
                                                faces: [])
    }
}

// MARK: - JPEG / EXIF

enum ImageEncoder {
    /// JPEG 编码并把 Recipe 文本塞进 EXIF UserComment —— 下游 App 拿到成片后
    /// 仍能还原出这次用了什么配方（RecipeCodec.decode）。
    static func jpeg(from image: CGImage, quality: CGFloat, userComment: String?) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out,
                                                          UTType.jpeg.identifier as CFString,
                                                          1, nil) else { return nil }
        var props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality,
        ]
        if let comment = userComment {
            props[kCGImagePropertyExifDictionary] = [
                kCGImagePropertyExifUserComment: comment,
            ] as CFDictionary
        }
        CGImageDestinationAddImage(dest, image, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    /// 从编码数据解码出 CGImage，顺带按 EXIF 方向摆正、缩到 maxEdge。
    /// 用 thumbnail API 而不是先解码原图再缩放，避免大图峰值内存。
    static func cgImage(from data: Data, maxEdge: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxEdge,
        ]
        if let thumb = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) {
            return thumb
        }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }
}

/// 从 JPEG/HEIC 的字节里把一瞬配方读回来。
///
/// **这是「配方随照片走」在 iOS 侧的读那一半。** 之前 iOS 只写不读：`export()` 把
/// `RecipeCodec.encodeForExif` 写进 UserComment，但没有任何代码把它读出来，
/// 于是「拿一张随拍拍的成片进一瞬，还原出当时那条配方」这条路在 iOS 上是断的。
///
/// 对应 Android 的 `app/.../data/RecipeExif.kt` 的 `readRecipe`（那边是
/// `ExifInterface.TAG_USER_COMMENT`；这里是 ImageIO 的
/// `kCGImagePropertyExifUserComment`）。两边的约定完全一致，因为
/// **解码都交给同一个 `RecipeCodec.decode`**，字符码也由 `encodeForExif` 统一负责。
///
/// 三条刻意保留的宽容（对齐 Android 的 `runCatching { }`）：
/// 1. 任何一步失败都返回 `nil`，不抛 —— 照片里没配方是**正常情况**，不是错误；
/// 2. UserComment 里可能有前导的 8 字节字符码 `ASCII\0\0\0`，`RecipeCodec.decode`
///    自己用 `indexOf(MARKER)` 找 `SPRECIPE1:`，所以这里**不需要**先剥字符码；
/// 3. ImageIO 有时会把 UserComment 交成 `Data` 而不是 `String`（尤其是带 NUL 的
///    那 8 个字符码），两种都接。
enum RecipeReader {
    /// - Parameter data: 照片的原始字节（不是缩略图 —— 缩略图里没有 UserComment）。
    static func recipe(from data: Data) -> YishunRecipe? {
        guard let text = userComment(from: data) else { return nil }
        // ⚠ `RecipeCodec.shared.decode(text:)` 的 Swift 标签未在 Xcode 验证。
        // 依据：同文件里已经在用的 `RecipeCodec.shared.encodeForExif(recipe:)`
        // 就是 `<对象>.shared.<函数>(标签:)` 这个形态，K/N 对同一对象的两个方法
        // 不会用两套导出规则。若真不对，改这一行。
        return RecipeCodec.shared.decode(text: text)
    }

    /// 只取 EXIF UserComment 原文。单独拆出来是因为它不依赖 `RecipeCodec`
    /// （解不出来时还能把原文显示给人看，排查"配方没读到"到底卡在哪一步）。
    static func userComment(from data: Data) -> String? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        guard let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
              let raw = exif[kCGImagePropertyExifUserComment] else { return nil }
        if let text = raw as? String {
            return text.isEmpty ? nil : text
        }
        // 带 NUL 的字符码会让 ImageIO 退成 Data 交付。ASCII 解码即可：
        // 配方本体是 Base64Url（纯 ASCII），字符码本身也是 ASCII + NUL。
        if let bytes = raw as? Data {
            return String(data: bytes, encoding: .ascii)
        }
        return nil
    }
}

// MARK: - RGB 直方图（一瞬页顶部小图，可选实现）

enum Histogram {
    /// 与 Android ColorPipeline.rgbHistogram 同口径：96×96 采样、等宽分箱。
    static func rgb(from image: CGImage, bins: Int = 48) -> ([Int], [Int], [Int]) {
        var r = [Int](repeating: 0, count: bins)
        var g = r
        var b = r
        let w = image.width
        let h = image.height
        guard w > 0, h > 0, bins > 0 else { return (r, g, b) }
        let stepX = max(1, w / 96)
        let stepY = max(1, h / 96)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return (r, g, b) }
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        let drawn: Bool = rgba.withUnsafeMutableBytes { raw -> Bool in
            guard let base = raw.baseAddress,
                  let ctx = CGContext(data: base, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
                return false
            }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return (r, g, b) }
        var y = 0
        while y < h {
            var x = 0
            while x < w {
                let o = (y * w + x) * 4
                r[Int(rgba[o]) * bins / 256] += 1
                g[Int(rgba[o + 1]) * bins / 256] += 1
                b[Int(rgba[o + 2]) * bins / 256] += 1
                x += stepX
            }
            y += stepY
        }
        return (r, g, b)
    }
}

// MARK: - Kotlin ByteArray 桥接

enum ByteBridge {
    static func kotlinByteArray(from data: Data) -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(data.count))
        for (i, byte) in data.enumerated() {
            arr.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return arr
    }

    static func data(from bytes: KotlinByteArray) -> Data {
        let n = Int(bytes.size)
        var out = Data(count: n)
        for i in 0..<n {
            out[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        return out
    }
}

// MARK: - 输入来源

/// 一瞬/查看器的输入：相册资源，或相机刚拍下还没落盘的数据。
enum PhotoInput {
    case asset(PHAsset)
    case imageData(Data)
}

/// 把一次性回调包成 async，且保证只 resume 一次 —— PHImageManager 在降级交付时
/// 可能回调多次，直接 resume 会 crash。
private final class ResumeOnce {
    private var done = false
    private let lock = NSLock()
    func run(_ body: () -> Void) {
        lock.lock()
        if done { lock.unlock(); return }
        done = true
        lock.unlock()
        body()
    }
}

enum PhotoInputLoader {
    /// 取原图数据。isNetworkAccessAllowed 让 iCloud 照片也能拉下来。
    /// 数据请求拿到的始终是原图，缩放交给调用方解码时做。
    static func requestData(asset: PHAsset) async -> Data? {
        await withCheckedContinuation { cont in
            let opts = PHImageRequestOptions()
            opts.isNetworkAccessAllowed = true
            opts.deliveryMode = .highQualityFormat
            opts.resizeMode = .exact
            opts.version = .current
            let once = ResumeOnce()
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: opts) { data, _, _, _ in
                once.run { cont.resume(returning: data) }
            }
        }
    }

    static func image(for input: PhotoInput, maxEdge: Int) async -> CGImage? {
        switch input {
        case .asset(let asset):
            guard let data = await requestData(asset: asset) else { return nil }
            return ImageEncoder.cgImage(from: data, maxEdge: maxEdge)
        case .imageData(let data):
            return ImageEncoder.cgImage(from: data, maxEdge: maxEdge)
        }
    }
}

// MARK: - 便捷格式化

enum Formatting {
    static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy年M月d日 EEEE"
        return f
    }()

    static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "HH:mm"
        return f
    }()

    static func day(_ date: Date?) -> String {
        guard let date = date else { return "未知日期" }
        return dayFormatter.string(from: date)
    }

    static func time(_ date: Date?) -> String {
        guard let date = date else { return "" }
        return timeFormatter.string(from: date)
    }

    /// 导出文件名沿用 Android 的约定：SuiPai_一瞬_配方_强度_时间戳.jpg
    static func exportName(lookName: String, intensity: Float) -> String {
        let pct = Int((intensity * 100).rounded())
        return "SuiPai_一瞬_\(lookName)_\(pct)_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
    }
}