import SwiftUI
import Photos
import SuiPaiKit

/// 一瞬的输入包装（fullScreenCover(item:) 要求 Identifiable）。
struct YishunTarget: Identifiable {
    let id = UUID()
    let input: PhotoInput
}

/// 预设缩略图缓存。
/// 5000+ 预设一次性全渲染会卡很久，所以改成「滚到哪个算哪个」，
/// 结果按 look.id 缓存，来回滚动不重复算。
final class LookThumbCache: ObservableObject {
    @Published private(set) var images: [String: CGImage] = [:]

    private var inFlight = Set<String>()
    private let lock = NSLock()

    func ensure(look: Look, source: CGImage?, intensity: Float) {
        guard let source = source else { return }
        let key = look.id
        lock.lock()
        if images[key] != nil || inFlight.contains(key) {
            lock.unlock()
            return
        }
        inFlight.insert(key)
        lock.unlock()

        let edge = EngineLimits.thumbEdge
        Task.detached(priority: .utility) {
            let mini = PixelBridge.scaled(source, maxEdge: Int(edge)) ?? source
            // 预设小图只看色调走向，颗粒按缩略图自己的长边算即可
            // （Android 侧同样是 maxEdge = THUMB_EDGE，grainEdge 跟着它）。
            let out = EnginePipeline.apply(
                image: mini,
                params: EnginePipeline.makeParams(look: look, intensity: intensity,
                                                  image: mini, grainEdge: edge)
            )
            await MainActor.run {
                if let out = out {
                    self.images[key] = out
                }
                self.lock.lock()
                self.inFlight.remove(key)
                self.lock.unlock()
            }
        }
    }
}

struct YishunView: View {
    let input: PhotoInput
    let onClose: () -> Void

    @StateObject private var thumbs = LookThumbCache()

    @State private var original: CGImage?
    @State private var preview: CGImage?
    @State private var miniImage: CGImage?
    @State private var looks: [Look] = []
    @State private var selectedId: String = EngineLimits.autoLookId
    @State private var intensity: Float = 0.55
    @State private var autoLook: Look?
    @State private var autoIntensity: Float = 0.55
    @State private var reason: String = "正在分析"
    @State private var busy = true
    @State private var saving = false
    @State private var message: String?
    @State private var compareOn = true
    @State private var split: CGFloat = 0.5
    @State private var hist: ([Int], [Int], [Int])?
    @State private var gen = 0
    @State private var sliderTask: Task<Void, Never>?

    // MARK: - 配方还原（v3：能读别人的专业配方）

    /// 从这张照片的 EXIF 里读出来的配方。`nil` = 这张图不是随拍成片（最常见的情况）。
    @State private var detectedRecipe: YishunRecipe?
    /// 这条配方带的专业三组（曲线 / 分色 / 蒙版）。上面是 nil 时它是 `.neutral`。
    @State private var detectedPro: ProInputs = .neutral
    /// 配方里的 9 个 v2 微调位。上面是 nil 时它是 `.neutral`。
    @State private var detectedKnobs: RecipeKnobs = .neutral
    /// 用户点了「忽略」之后不再打扰。与 Android 的 `recipeDismissed` 同义。
    @State private var recipeDismissed = false
    /// 配方是否**已经**套在画面上了（`load()` 自动套，或用户点了「还原」）。
    /// 用来把横幅文案从「还原／忽略」换成「已还原／忽略」，避免出现
    /// 「画面已经是配方颜色了，按钮还在问你还原不还原」这种自相矛盾的界面。
    @State private var recipeApplied = false

    var body: some View {
        ZStack {
            SuiPaiTheme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                imageArea
                if let hist = hist {
                    HistogramView(rgb: hist)
                        .frame(height: 34)
                        .padding(.horizontal, 16)
                        .padding(.top, 6)
                }
                if busy {
                    HStack(spacing: 8) {
                        ProgressView().tint(SuiPaiTheme.ivory)
                        Text("正在处理")
                            .font(SuiPaiTheme.caption)
                            .foregroundColor(SuiPaiTheme.secondaryText)
                    }
                    .padding(.vertical, 6)
                } else {
                    Text(reason)
                        .font(SuiPaiTheme.caption)
                        .foregroundColor(SuiPaiTheme.secondaryText)
                        .lineLimit(1)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                }
                recipeBanner
                lookStrip
                controls
            }
        }
        .task { await load() }
        .onDisappear { sliderTask?.cancel() }
    }

    // MARK: - 配方还原横幅

    /// 「检测到一瞬配方 · 某方案 ＋ 还原／已还原 ＋ 忽略」。
    ///
    /// 对齐 Android 的 `YishunScreen` 同名横幅（那边是 `FlowRow`，因为字级放大后
    /// 两格会被挤扁）。这里用 `HStack` + `layoutPriority` 达到同样目的：
    /// 标题那一行可以被截断，两个按钮保持完整 —— 按钮点不着比标题截断严重得多。
    @ViewBuilder
    private var recipeBanner: some View {
        if let recipe = detectedRecipe, !recipeDismissed {
            HStack(spacing: 8) {
                Text("检测到一瞬配方 · \(lookName(for: recipe.lookId))")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .layoutPriority(0)
                Spacer(minLength: 4)
                if recipeApplied {
                    // 已经套上了就别再问一次（`load()` 会自动套，见那里的注释）。
                    Text("已还原")
                        .font(SuiPaiTheme.caption)
                        .foregroundColor(SuiPaiTheme.quiet)
                        .layoutPriority(1)
                } else {
                    Button("还原") { applyDetectedRecipe() }
                        .font(SuiPaiTheme.caption)
                        .foregroundColor(SuiPaiTheme.shutter)
                        .layoutPriority(1)
                        .disabled(LookLibrary.shared.byId(id: recipe.lookId) == nil)
                }
                Button("忽略") {
                    // 「忽略」= 回到智能适配，同时把配方三组清掉 —— 否则会出现
                    // 「界面回到了智能适配、画面却还带着别人的曲线」。
                    recipeDismissed = true
                    applyAutoLook()
                }
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.quiet)
                .layoutPriority(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            // 蒙版被降级丢掉时说一句。**不能静默**：用户会以为"蒙版效果没生效"是 bug。
            // 这条只在 `ProInputs.enableMasks == false`（出厂状态）且配方真带蒙版时出现；
            // 降级的完整理由写在 `ProInputs.enableMasks` 的注释里。
            if detectedPro.masksDropped {
                Text("这条配方带局部蒙版，本版本暂不还原（曲线与分色已生效）")
                    .font(SuiPaiTheme.chip)
                    .foregroundColor(SuiPaiTheme.quiet)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }
        }
    }

    /// 配方里的 lookId 可能不在当前库里（别的版本导出的成片）。
    /// Android 那边会当场提示「这个配方指的色调不在库里」；这里把名字降级成
    /// `unknown`，并由「还原」按钮的 disabled 状态挡住点击。
    private func lookName(for lookId: String) -> String {
        LookLibrary.shared.byId(id: lookId)?.name ?? "未知方案"
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack {
            Button("返回") { onClose() }
                .font(SuiPaiTheme.body)
                .foregroundColor(SuiPaiTheme.ivory)
            Spacer()
            Text("一瞬 · \(selectedId == EngineLimits.autoLookId ? "智能" : currentLook().name)")
                .font(SuiPaiTheme.title)
                .foregroundColor(SuiPaiTheme.onBackground)
            Spacer()
            Button(compareOn ? "对比中" : "对比") {
                compareOn.toggle()
            }
            .font(SuiPaiTheme.body)
            .foregroundColor(compareOn ? SuiPaiTheme.shutter : SuiPaiTheme.ivory)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: - 图像区（拖动分屏对比）

    private var imageArea: some View {
        GeometryReader { geo in
            ZStack {
                SuiPaiTheme.ink
                if let preview = preview {
                    Image(decorative: preview, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .frame(width: geo.size.width, height: geo.size.height)
                }
                if compareOn, let original = original {
                    Image(decorative: original, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .mask(
                            Rectangle()
                                .frame(width: geo.size.width * split, height: geo.size.height)
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                        )
                    Rectangle()
                        .fill(SuiPaiTheme.ivory)
                        .frame(width: 1.5, height: geo.size.height)
                        .position(x: geo.size.width * split, y: geo.size.height / 2)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard compareOn else { return }
                        split = min(max(value.location.x / max(geo.size.width, 1), 0), 1)
                    }
            )
        }
        .frame(maxWidth: .infinity)
        .frame(height: 340)
        .clipped()
    }

    // MARK: - Look 横滑

    private var lookStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 10) {
                autoChip
                ForEach(looks, id: \.id) { look in
                    LookChip(
                        look: look,
                        title: look.name,
                        source: miniImage,
                        intensity: look.defaultIntensity,
                        selected: selectedId == look.id,
                        cache: thumbs
                    ) {
                        select(id: look.id)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .frame(height: 96)
    }

    private var autoChip: some View {
        Button {
            select(id: EngineLimits.autoLookId)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    SuiPaiTheme.surfaceVariant
                    if let autoLook = autoLook, let img = thumbs.images[autoLook.id] {
                        Image(decorative: img, scale: 1)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: "wand.and.stars")
                            .foregroundColor(SuiPaiTheme.quiet)
                    }
                }
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(selectedId == EngineLimits.autoLookId ? SuiPaiTheme.shutter : SuiPaiTheme.outline,
                                lineWidth: selectedId == EngineLimits.autoLookId ? 2 : 0.5)
                )
                Text("智能")
                    .font(SuiPaiTheme.chip)
                    .foregroundColor(selectedId == EngineLimits.autoLookId ? SuiPaiTheme.ivory : SuiPaiTheme.quiet)
            }
            .frame(width: 62)
        }
        .buttonStyle(.plain)
        .task(id: autoLook?.id ?? "auto-pending") {
            if let autoLook = autoLook {
                thumbs.ensure(look: autoLook, source: miniImage, intensity: autoIntensity)
            }
        }
    }

    // MARK: - 强度 + 保存

    private var controls: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                Text("强度")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
                Slider(value: Binding(
                    get: { Double(intensity) },
                    set: { intensity = Float($0) }
                ), in: 0...1)
                .tint(SuiPaiTheme.shutter)
                .onChange(of: intensity) { value in
                    sliderTask?.cancel()
                    sliderTask = Task {
                        try? await Task.sleep(nanoseconds: 160_000_000)
                        if Task.isCancelled { return }
                        recompute(look: currentLook(), amount: value, showBusy: false)
                    }
                }
                Text("\(Int((intensity * 100).rounded()))%")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.ivory)
                    .frame(width: 40, alignment: .trailing)
            }
            .padding(.horizontal, 16)

            if let message = message {
                Text(message)
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
            }

            Button {
                export()
            } label: {
                Text(saving ? "保存中" : "应用并保存")
                    .font(SuiPaiTheme.body)
                    .foregroundColor(SuiPaiTheme.ivory)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(saving ? SuiPaiTheme.outline : SuiPaiTheme.shutter)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .disabled(saving || preview == nil)
            .padding(.horizontal, 16)
        }
        .padding(.bottom, 14)
        .padding(.top, 4)
    }

    // MARK: - 数据流

    private func currentLook() -> Look {
        if selectedId == EngineLimits.autoLookId {
            return autoLook ?? LookLibrary.shared.daylight
        }
        return LookLibrary.shared.byId(id: selectedId) ?? LookLibrary.shared.daylight
    }

    @MainActor
    private func load() async {
        busy = true
        message = nil
        detectedRecipe = nil
        detectedPro = .neutral
        detectedKnobs = .neutral
        recipeDismissed = false
        recipeApplied = false
        looks = LookLibrary.shared.all
        guard let previewImage = await PhotoInputLoader.image(for: input, maxEdge: Int(EngineLimits.previewEdge)) else {
            busy = false
            message = "无法读取这张照片"
            return
        }
        original = previewImage
        miniImage = PixelBridge.scaled(previewImage, maxEdge: Int(EngineLimits.thumbEdge))

        // 读 EXIF 里的配方。**必须用原图字节**，不能用 previewImage：
        // 缩略图解码路径拿到的 CGImage 里已经没有 UserComment 了
        // （ImageEncoder.cgImage(from:maxEdge:) 只解像素）。
        // 这一步失败是正常的（绝大多数照片不是随拍成片），所以不弹任何提示。
        let recovered = await readRecipeFromInput()
        // 读到配方时，**首帧就按那条配方渲染**，而不是「先智能适配、等用户点还原」。
        //
        // 为什么要这样：这是「配方随照片走」的全部意义 —— 一张随拍拍的成片再打开，
        // 看到的就是当时那条颜色。如果先渲染智能适配（另一条 Look），用户会先看到
        // 一个**别人的**调色，再点「还原」才变回自己的；不点就一直是错的。
        // 横幅保留（可以「忽略」回到智能适配），但默认值必须是配方本身。
        //
        // 已知取舍：只要 lookId 还在库里就自动套；不在库里（别的版本导出的成片）
        // 就退回智能适配，并让横幅上的「还原」按钮置灰 —— 与 Android 的
        // 「这个配方指的色调不在库里」同一个意思，只是这里提前了一步。
        let recoveredLook = recovered.flatMap { LookLibrary.shared.byId(id: $0.lookId) }
        let useRecovered = recovered != nil && recoveredLook != nil
        let initialPro = useRecovered ? detectedPro : ProInputs.neutral
        let initialKnobs = useRecovered ? detectedKnobs : RecipeKnobs.neutral

        // 预览的颗粒要按**成片长边**调校，不是按预览缓冲的长边：喂 previewEdge 的话
        // 预览永远吃 0.7× 细颗粒，和成片差一截（所见非所得）。Android 侧同理，
        // 它传的是 exportEdgeNow()。iOS 的导出长边固定为 EngineLimits.exportEdge。
        let grainEdge = EngineLimits.exportEdge
        let outcome = await Task.detached(priority: .userInitiated) { () -> (Adaptation?, CGImage?) in
            let adaptation = EnginePipeline.analyze(image: previewImage)
            let autoLook = adaptation?.look ?? LookLibrary.shared.daylight
            let autoAmount = adaptation?.intensity ?? autoLook.defaultIntensity
            // 走哪条 Look / 强度：配方可用就整条用配方的，否则整条用智能适配。
            // **两条路径必须整体切换**：不能"用配方的强度配智能适配的 Look"——
            // 强度是相对某条 Look 的调校曲线而言的，张冠李戴会得到一个谁都没设计过的颜色。
            let look: Look
            let amount: Float
            if useRecovered, let recovered = recovered {
                look = recoveredLook ?? autoLook   // useRecovered 保证非 nil，这里是给编译器看
                amount = recovered.intensity
            } else {
                look = autoLook
                amount = autoAmount
            }
            let out = EnginePipeline.apply(
                image: previewImage,
                params: EnginePipeline.makeParams(look: look, intensity: amount,
                                                  image: previewImage, grainEdge: grainEdge,
                                                  curves: initialPro.curves,
                                                  hsl: initialPro.hsl,
                                                  masks: initialPro.masks,
                                                  knobs: initialKnobs)
            )
            return (adaptation, out)
        }.value

        autoLook = outcome.0?.look
        autoIntensity = outcome.0?.intensity ?? 0.55
        if let recovered = recovered, let recoveredLook = recoveredLook {
            // 选了配方：顶栏显示配方指的 Look，强度/微调位都按配方。
            selectedId = recoveredLook.id
            intensity = recovered.intensity
            reason = recoveredLook.summary
            // 横幅仍然留着（用户可以「忽略」跳回智能适配），所以不置 recipeDismissed。
            recipeApplied = true
        } else {
            selectedId = EngineLimits.autoLookId
            intensity = autoIntensity
            reason = "智能适配 · " + (outcome.0?.reason ?? "默认")
        }
        preview = outcome.1
        busy = false
        computeHistogram()
    }

    private func select(id: String) {
        selectedId = id
        let look: Look
        let amount: Float
        if id == EngineLimits.autoLookId {
            look = autoLook ?? LookLibrary.shared.daylight
            amount = autoIntensity
            reason = "智能适配 · \(look.name)"
        } else {
            look = LookLibrary.shared.byId(id: id) ?? LookLibrary.shared.daylight
            amount = look.defaultIntensity
            reason = look.summary
        }
        intensity = amount
        // 用户手动换 Look = 丢掉上一张图带来的配方三组。
        // 与 Android 的语义一致：`applyRecipe` 只在「点还原」时设 pro，
        // 手动选 Look 走 `select`，不带上别人的曲线/蒙版。
        // **这不是可选行为**：不清掉就会出现「配方说没曲线、画面还带着上一条的曲线」。
        clearProFromRecipe()
        // 用户主动换了 Look：画面已经不是配方那一条了，横幅回到「还原」而不是「已还原」
        // （否则会出现「明明已经换成别的 Look、它还说已还原」）。
        recipeApplied = false
        recompute(look: look, amount: amount)
    }

    /// 读取输入照片 EXIF 里的配方，填 `detectedRecipe` / `detectedPro` / `detectedKnobs`。
    ///
    /// 关键点：**只读，不应用**。Android 那边也是先弹横幅问一句，用户点「还原配方」
    /// 才真的套上去（`detectedRecipe` 与 `pro` 是两个独立状态）。
    /// 这里如果读出来就直接套，用户会觉得"我只是打开一张图，颜色怎么自己变了"。
    ///
    /// 现在返回读到的配方（而不是只写 @State）：`load()` 要用它**直接算首帧预览**，
    /// 否则会先按智能适配出一帧、再等用户点「还原」才变 —— 而用户往往根本没点。
    @discardableResult
    private func readRecipeFromInput() async -> YishunRecipe? {
        let source = input
        let data: Data?
        switch source {
        case .imageData(let raw):
            data = raw
        case .asset(let asset):
            // 需要原图字节：缩略图/预览解码出来的 CGImage 里没有 UserComment。
            data = await PhotoInputLoader.requestData(asset: asset)
        }
        guard let data = data, !data.isEmpty else { return nil }
        let recipe = await Task.detached(priority: .utility) { () -> YishunRecipe? in
            RecipeReader.recipe(from: data)
        }.value
        guard let recipe = recipe else { return nil }
        detectedRecipe = recipe
        // ⚠ 这两步都依赖未验证的 Swift 签名（见 ProInputs / RecipeKnobs 的类型注释）。
        detectedKnobs = RecipeKnobs.of(recipe: recipe)
        detectedPro = ProInputs.of(recipe: recipe)
        return recipe
    }

    /// 把检测到的配方整条套上去。逐条对齐 Android 的 `applyRecipe`：
    /// lookId → 强度 → 9 个微调位 → 专业三组，然后重算预览。
    private func applyDetectedRecipe() {
        guard let recipe = detectedRecipe else { return }
        // lookId 不在库里时什么都不改，但要给一句解释 —— Android 那边踩过这个坑
        // （原实现 `?: return` 之后画面没变也没人说话）。
        guard let look = LookLibrary.shared.byId(id: recipe.lookId) else {
            recipeDismissed = true
            message = "这个配方指的色调不在库里，什么都没改"
            return
        }
        selectedId = look.id
        intensity = recipe.intensity
        detectedKnobs = RecipeKnobs.of(recipe: recipe)
        detectedPro = ProInputs.of(recipe: recipe)
        reason = look.summary
        // 这里**不**置 recipeDismissed：横幅要留着，并且文案转成「已还原」，
        // 让用户看得见"这条配方现在正生效"。想退出就点「忽略」。
        recipeApplied = true
        recompute(look: look, amount: recipe.intensity)
    }

    /// 回到「智能适配」那一条 Look，并丢掉配方带来的三组与微调位。
    ///
    /// 「忽略」按钮走这里。**必须清 pro**：只把 selectedId 改回 auto 而不清三组，
    /// 就会出现「界面说在用智能适配、画面却还带着别人的曲线」——
    /// 这正是 Android 那条注释警告过的错法。
    private func applyAutoLook() {
        clearProFromRecipe()
        recipeApplied = false
        selectedId = EngineLimits.autoLookId
        let look = autoLook ?? LookLibrary.shared.daylight
        intensity = autoIntensity
        reason = "智能适配 · \(look.name)"
        recompute(look: look, amount: autoIntensity)
    }

    /// 丢弃来自配方的专业三组与微调位，回到中性。
    private func clearProFromRecipe() {
        detectedPro = .neutral
        detectedKnobs = .neutral
    }

    private func recompute(look: Look, amount: Float, showBusy: Bool = true) {
        guard let source = original else { return }
        if showBusy { busy = true }
        gen += 1
        let myGen = gen
        // 和 load() 一样：颗粒按成片长边调校，预览才和成片是同一份颗粒。
        let grainEdge = EngineLimits.exportEdge
        // 值类型在进 Task 之前先抄一份：detached 闭包里不能读 @State 的当前值
        // （那是主线程状态，跨线程读会拿到不一致的快照）。
        let pro = detectedPro
        let knobs = detectedKnobs
        Task.detached(priority: .userInitiated) {
            let out = EnginePipeline.apply(
                image: source,
                params: EnginePipeline.makeParams(look: look, intensity: amount,
                                                  image: source, grainEdge: grainEdge,
                                                  curves: pro.curves,
                                                  hsl: pro.hsl,
                                                  masks: pro.masks,
                                                  knobs: knobs)
            )
            await MainActor.run {
                // 只有最后一次请求能落地，避免快速滑强度时旧结果覆盖新结果
                guard self.gen == myGen else { return }
                self.preview = out
                self.busy = false
                self.computeHistogram()
            }
        }
    }

    private func computeHistogram() {
        guard let image = preview else {
            hist = nil
            return
        }
        Task.detached(priority: .utility) {
            let rgb = Histogram.rgb(from: image)
            await MainActor.run { self.hist = rgb }
        }
    }

    @MainActor
    private func export() {
        saving = true
        message = nil
        let look = currentLook()
        let amount = intensity
        let name = Formatting.exportName(lookName: look.name, intensity: amount)
        let edge = EngineLimits.exportEdge
        // 进 Task 前把值类型抄出来（detached 闭包里不读 @State）。
        let pro = detectedPro
        let knobs = detectedKnobs
        // 配方要记下成片长边：颗粒是按 EngineParams.grainEdge 调校的，不带着它，
        // 另一台机器（Android 的 exportEdge 按内存分档）还原不出同一份颗粒。
        // v **已与 YishunRecipe.CURRENT_VERSION = 3 核对为 3**（shared/.../RecipeCodec.kt
        // 第 56 行）。为什么跟着升：解码端 `RecipeCodec.decode` 有一句
        // `if (recipe.v > CURRENT_VERSION || recipe.v < 1) null`，写 2 不会报错，
        // 但 v3 的形状配 v2 的版本号会让「这条配方有没有专业三组」变成读的人自己猜；
        // 反过来 v2 的 App 读到 v=3 会**整条拒绝**而不是拿老语义静默错色（这是设计意图，
        // 见 COLOR-PRO.md §7 的兼容矩阵）。
        // 写死字面量是沿用 EngineLimits.autoLookId 的做法
        // （`const val` 的导出形式在 Mac 上没验证过，别引用 CURRENT_VERSION）。
        // 原来的 seed 字段已从 YishunRecipe 删掉：种子由 ColorCore.grainSeedFor
        // 按 lookId 单点决定，写进配方里也从来没人回读。
        //
        // Kotlin 默认值不导出到 Swift，所以 **v3 的全部 16 个字段都要显式传**
        // （v2 时代是 13 个：v + lookId + intensity + 8 个 shift + grainMul +
        // vignetteMul + exportEdge；v3 在**末尾**追加 curve / hsl / masks 三个可空字段）。
        // 注意 shared 侧 `YishunRecipe.V2_FIELD_COUNT = 14` 这个常量与声明数对不上
        // （实际 13 个）、且没有任何测试引用它 —— 见 handoff.md §11 的记账。
        //
        // ── 专业三组：**只有真的带着的时候才写** ─────────────────────────────
        // 这里不再一律传 nil。`detectedPro` 是「这条配方实际生效的三组」：
        //   · 新建调整（没点过「还原」）→ `.neutral` → 走下面的零偏移路径；
        //   · 从别人照片里还原过带专业三组的配方 → 非中性 → 走 `RecipePro.withPro`。
        // 「从 EngineParams 反推 ToneCurves」依然走不通（构造期已烘焙成
        // `curveMaster` / `curveByteR` 等数组，控制点没留下），所以这里**不反推**，
        // 而是直接复用还原时从配方解出来的那份原始值 —— 同样一次往返，零信息损失。
        //
        // 为什么不无条件走 withPro：`withPro` 内部对中性输入也返回 null，语义上等价；
        // 但它在 Swift 侧的签名**没验证过**（见下），能不碰就不碰。
        // 零偏移路径（iOS 的常态）因此完全不经过 RecipePro，风险面更小。
        //
        // 写 nil 的实际效果：`encodeDefaults = false` 让这三个字段**不出现在 JSON 里**，
        // 而且它省掉的不止这三个 —— `YishunRecipe` 里**任何**等于声明默认值的字段都会被省。
        // 零偏移路径上那一串 0 / 1 全都等于默认值，所以实际写出去的 JSON 只有 **3 个键**：
        //     {"v":3,"lookId":"…","intensity":…}
        // 注意 `exportEdge` 也在被省掉的名单里：Kotlin 侧 `exportEdge: Int = 4096`，
        // 而 EngineLimits.exportEdge 恰好也是 4096，两者相等 → 省略。解码端会把默认值
        // 补回来，所以**当前**读回来仍是 4096，语义没错；但这意味着「配方里记着 exportEdge」
        // 只在成片边长 ≠ 4096 时才真的生效（Android 按内存分档，2560/1600 档会写出去）。
        // 唯一的永远写出字段是 `v` —— 它带 `@EncodeDefault(ALWAYS)`。
        // 判据见 shared/src/commonTest/.../RecipeCodecTest.versionIsAlwaysWritten。
        //
        // encodeForExif 而不是 encode：EXIF UserComment 必须带 8 字节字符码（EXIF 2.32 §4.6.4），
        // 约定放在 shared 里，两端写出完全一样的字节。
        var base = YishunRecipe(
            v: 3,
            lookId: look.id,
            intensity: amount,
            // 9 个微调位从**实际生效的那一份**抄，而不是写死中性：
            // 还原别人的配方时它们不是 0，写死会让"还原了但存出去变了样"。
            tempShift: knobs.tempShift,
            contrastShift: knobs.contrastShift,
            satShift: knobs.satShift,
            shadowShift: knobs.shadowShift,
            fadeShift: knobs.fadeShift,
            highlightShift: knobs.highlightShift,
            softShift: knobs.softShift,
            grainMul: knobs.grainMul,
            vignetteMul: knobs.vignetteMul,
            exportEdge: edge,
            // 零偏移路径默认全 nil；下面 hasAny 时才被 withPro 覆盖。
            // ⚠ `List<RecipeMask>?` → Swift 侧到底是 `[RecipeMask]?` 还是 `NSArray?`
            // 未验证；传 nil 在两种形态下都成立（可选值）。
            curve: nil,
            hsl: nil,
            masks: nil
        )
        if pro.hasAny {
            // ⚠ **本行签名未在 Xcode 验证**：`RecipePro` 是 Kotlin `object`，
            // 按 `<对象>.shared.<函数>` 的既有形态写成 `RecipePro.shared.withPro(...)`，
            // 参数照 `RecipeCodec.kt` 第 221-233 行的声明顺序
            // `(base, curves, hsl, masks)`。若 Mac 上编译不过，**只改这一行**；
            // 兜底写法是「三组都留 nil，只保留 9 个微调位」（功能降级但不崩）。
            base = RecipePro.shared.withPro(base: base,
                                            curves: pro.curves,
                                            hsl: pro.hsl,
                                            masks: pro.masks)
        }
        let recipe = RecipeCodec.shared.encodeForExif(recipe: base)
        let source = input
        Task {
            guard let full = await PhotoInputLoader.image(for: source, maxEdge: Int(edge)) else {
                saving = false
                message = "无法读取原图"
                return
            }
            let data = await Task.detached(priority: .userInitiated) { () -> Data? in
                guard let out = EnginePipeline.apply(
                    image: full,
                    params: EnginePipeline.makeParams(look: look, intensity: amount,
                                                      image: full, grainEdge: edge,
                                                      curves: pro.curves,
                                                      hsl: pro.hsl,
                                                      masks: pro.masks,
                                                      knobs: knobs)
                ) else { return nil }
                return ImageEncoder.jpeg(from: out, quality: EngineLimits.exportQuality, userComment: recipe)
            }.value
            guard let jpeg = data else {
                saving = false
                message = "JPEG 编码失败"
                return
            }
            let ok = await PhotoLibraryService.shared.saveImageData(
                jpeg, filename: name, albumName: EngineLimits.albumName
            )
            saving = false
            message = ok ? "已存到「SuiPai」相簿" : "保存失败，请检查相册写入权限"
        }
    }
}

// MARK: - Look 缩略图单元

private struct LookChip: View {
    let look: Look
    let title: String
    let source: CGImage?
    let intensity: Float
    let selected: Bool
    @ObservedObject var cache: LookThumbCache
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                ZStack {
                    SuiPaiTheme.surfaceVariant
                    if let img = cache.images[look.id] {
                        Image(decorative: img, scale: 1)
                            .resizable()
                            .scaledToFill()
                    }
                }
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(selected ? SuiPaiTheme.shutter : SuiPaiTheme.outline,
                                lineWidth: selected ? 2 : 0.5)
                )
                Text(title)
                    .font(SuiPaiTheme.chip)
                    .foregroundColor(selected ? SuiPaiTheme.ivory : SuiPaiTheme.quiet)
                    .lineLimit(1)
                    .frame(width: 60)
            }
            .frame(width: 62)
        }
        .buttonStyle(.plain)
        .task(id: look.id) {
            cache.ensure(look: look, source: source, intensity: intensity)
        }
    }
}

// MARK: - 直方图

private struct HistogramView: View {
    let rgb: ([Int], [Int], [Int])

    private var bins: Int { rgb.0.count }

    /// 峰值放在普通计算属性里算，避免在 ViewBuilder 里写可变局部变量。
    private var peak: Int {
        var result = 1
        for i in 0..<bins {
            result = max(result, rgb.0[i] + rgb.1[i] + rgb.2[i])
        }
        return result
    }

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(0..<bins, id: \.self) { i in
                    Rectangle()
                        .fill(SuiPaiTheme.ivory.opacity(0.8))
                        .frame(width: geo.size.width / CGFloat(max(bins, 1)),
                               height: max(1, geo.size.height * CGFloat(total(at: i)) / CGFloat(peak)))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
        }
    }

    private func total(at i: Int) -> Int {
        guard i >= 0, i < bins else { return 0 }
        return rgb.0[i] + rgb.1[i] + rgb.2[i]
    }
}