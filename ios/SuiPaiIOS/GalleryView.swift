import SwiftUI
import Photos
import UIKit

struct ViewerTarget: Identifiable {
    let id = UUID()
    let index: Int
}

struct GalleryView: View {
    @EnvironmentObject private var permissions: Permissions
    @EnvironmentObject private var library: PhotoLibraryService

    @State private var viewerTarget: ViewerTarget?

    private let columns = [
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
    ]

    var body: some View {
        ZStack {
            SuiPaiTheme.background.ignoresSafeArea()
            content
        }
        .onAppear {
            permissions.refresh()
            if permissions.photo.canRead {
                library.reload()
            }
        }
        .onChange(of: permissions.photo) { state in
            if state.canRead {
                library.reload()
            }
        }
        .fullScreenCover(item: $viewerTarget) { target in
            ViewerView(initialIndex: target.index)
                .environmentObject(library)
                .environmentObject(permissions)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch permissions.photo {
        case .notDetermined:
            PermissionWall(
                title: "相册还没打开",
                message: "随拍需要读取相册才能浏览和调色。照片只在本机处理，不上传。",
                primary: ("允许访问相册", { _ = Task { await permissions.requestPhoto() } })
            )
        case .denied:
            PermissionWall(
                title: "相册访问被拒绝",
                message: "到「设置 → 随拍 → 照片」里允许访问相册，再回到这里。",
                primary: ("打开设置", { openSettings() })
            )
        default:
            galleryBody
        }
    }

    private var galleryBody: some View {
        Group {
            if library.isLoading && library.assets.isEmpty {
                ProgressView().tint(SuiPaiTheme.ivory)
            } else if library.assets.isEmpty {
                emptyState
            } else {
                grid
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: 34))
                .foregroundColor(SuiPaiTheme.quiet)
            Text("相册里还没有照片")
                .foregroundColor(SuiPaiTheme.onBackground)
            Text("去「相机」拍一张吧")
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.secondaryText)
        }
    }

    private var grid: some View {
        ScrollView {
            if permissions.photo == .limited {
                limitedBanner
            }
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                ForEach(library.dayGroups(limit: library.displayCount)) { group in
                    Section {
                        ForEach(group.assets, id: \.localIdentifier) { asset in
                            cell(for: asset)
                        }
                    } header: {
                        sectionHeader(group)
                    }
                }
            }
            if library.hasMore {
                Button {
                    library.loadMore()
                } label: {
                    Text("加载更多（\(library.totalCount - library.displayCount)）")
                        .font(SuiPaiTheme.body)
                        .foregroundColor(SuiPaiTheme.ivory)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .background(SuiPaiTheme.surface)
                .padding(.top, 8)
            }
        }
        .background(SuiPaiTheme.background)
    }

    private func cell(for asset: PHAsset) -> some View {
        Button {
            if let idx = library.index(of: asset) {
                viewerTarget = ViewerTarget(index: idx)
            }
        } label: {
            ThumbnailCell(asset: asset, side: cellSide)
        }
        .buttonStyle(.plain)
    }

    /// 网格宽约 1/3 屏；缩略图按 2 倍目标尺寸取，滚动时够清晰也不吃内存。
    private var cellSide: CGFloat {
        (UIScreen.main.bounds.width - 4) / 3
    }

    private func sectionHeader(_ group: DayGroup) -> some View {
        HStack {
            Text(Formatting.day(group.day))
                .font(SuiPaiTheme.title)
                .foregroundColor(SuiPaiTheme.onBackground)
            Spacer()
            Text("\(group.assets.count) 张")
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.secondaryText)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(SuiPaiTheme.background)
    }

    private var limitedBanner: some View {
        HStack(spacing: 10) {
            Text("只授权了部分照片")
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.onBackground)
            Spacer()
            Button("选择更多") { presentLimitedPicker() }
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.shutter)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(SuiPaiTheme.surface)
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func presentLimitedPicker() {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            return
        }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top)
    }
}

// MARK: - 网格单元

private struct ThumbnailCell: View {
    let asset: PHAsset
    let side: CGFloat

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            SuiPaiTheme.surfaceVariant
            if let image = image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .frame(width: side, height: side)
        .clipped()
        .contentShape(Rectangle())
        .task(id: asset.localIdentifier) {
            library.thumbnail(for: asset, size: CGSize(width: side, height: side)) { loaded in
                image = loaded
            }
        }
    }
}

// MARK: - 权限墙

private struct PermissionWall: View {
    let title: String
    let message: String
    let primary: (String, () -> Void)

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "photo.stack")
                .font(.system(size: 36))
                .foregroundColor(SuiPaiTheme.quiet)
            Text(title)
                .font(SuiPaiTheme.title)
                .foregroundColor(SuiPaiTheme.onBackground)
            Text(message)
                .font(SuiPaiTheme.body)
                .foregroundColor(SuiPaiTheme.secondaryText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button(primary.0) { primary.1() }
                .font(SuiPaiTheme.body)
                .foregroundColor(SuiPaiTheme.ivory)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(SuiPaiTheme.shutter)
                .clipShape(Capsule())
                .padding(.top, 6)
        }
    }
}