import SwiftUI
import Photos

/// 全屏看图：左右滑动切张、双指缩放、双击缩放，顶栏放日期/文件名和「一瞬」入口。
struct ViewerView: View {
    let initialIndex: Int

    @EnvironmentObject private var library: PhotoLibraryService
    @Environment(\.dismiss) private var dismiss

    @State private var index: Int
    @State private var yishunTarget: YishunTarget?

    init(initialIndex: Int) {
        self.initialIndex = initialIndex
        _index = State(initialValue: initialIndex)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            pages
            chrome
        }
        .statusBarHidden(false)
        .fullScreenCover(item: $yishunTarget) { target in
            YishunView(input: target.input, onClose: { yishunTarget = nil })
                .environmentObject(library)
        }
    }

    private var pages: some View {
        TabView(selection: $index) {
            ForEach(0..<library.totalCount, id: \.self) { i in
                ZoomableImage(asset: library.assets[i])
                    .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
    }

    private var currentAsset: PHAsset? {
        guard index >= 0, index < library.totalCount else { return nil }
        return library.assets[index]
    }

    private var chrome: some View {
        VStack {
            topBar
            Spacer()
            bottomBar
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(SuiPaiTheme.ivory)
                    .frame(width: 36, height: 36)
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(Formatting.day(currentAsset?.creationDate))
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.ivory)
                Text(currentAsset.map { library.filename(for: $0) } ?? "")
                    .font(.system(size: 11))
                    .foregroundColor(SuiPaiTheme.quiet)
                    .lineLimit(1)
            }

            Spacer()

            Button {
                if let asset = currentAsset {
                    yishunTarget = YishunTarget(input: .asset(asset))
                }
            } label: {
                Text("一瞬")
                    .font(SuiPaiTheme.body)
                    .foregroundColor(SuiPaiTheme.ivory)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(SuiPaiTheme.shutter)
                    .clipShape(Capsule())
            }
            .disabled(currentAsset == nil)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(
            LinearGradient(colors: [Color.black.opacity(0.6), Color.black.opacity(0)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
        )
    }

    private var bottomBar: some View {
        HStack {
            Spacer()
            Text("\(min(index + 1, library.totalCount)) / \(library.totalCount)")
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.ivory)
            Spacer()
        }
        .padding(.vertical, 10)
        .background(
            LinearGradient(colors: [Color.black.opacity(0), Color.black.opacity(0.55)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

// MARK: - 可缩放单张

private struct ZoomableImage: View {
    let asset: PHAsset

    @State private var image: CGImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let image = image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(scale)
                        .offset(offset)
                        .gesture(magnification)
                        .simultaneousGesture(drag)
                        .onTapGesture(count: 2) { toggleZoom() }
                } else {
                    ProgressView().tint(SuiPaiTheme.ivory)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .clipped()
            .task(id: asset.localIdentifier) { await load() }
        }
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(lastScale * value, 1), 6)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.01 {
                    withAnimation(.easeOut(duration: 0.18)) {
                        scale = 1
                        lastScale = 1
                        offset = .zero
                        lastOffset = .zero
                    }
                }
            }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1 else { return }
                offset = CGSize(width: lastOffset.width + value.translation.width,
                                height: lastOffset.height + value.translation.height)
            }
            .onEnded { _ in
                guard scale > 1 else { return }
                lastOffset = offset
            }
    }

    private func toggleZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if scale > 1 {
                scale = 1
                lastScale = 1
                offset = .zero
                lastOffset = .zero
            } else {
                scale = 2.5
                lastScale = 2.5
            }
        }
    }

    private func load() async {
        let cg = await PhotoInputLoader.image(for: .asset(asset), maxEdge: Int(EngineLimits.previewEdge))
        await MainActor.run { self.image = cg }
    }
}