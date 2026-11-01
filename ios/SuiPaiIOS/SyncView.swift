import SwiftUI
import Photos
import UIKit
import SuiPaiKit

/// 互通页：设置本机名字、看附近设备、选照片发送、确认收到的照片。
/// 浏览/下载对端照片的入口留 TODO（以 Android 侧为基准实现）。
struct SyncView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var permissions: Permissions

    @State private var deviceName: String = SyncView.storedName()
    @State private var bridge: SyncBridge?
    @State private var pickTarget: PeerDevice?

    var body: some View {
        ZStack {
            SuiPaiTheme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    nameSection
                    peersSection
                    statusSection
                }
                .padding(16)
            }
        }
        .onAppear {
            permissions.refresh()
            if permissions.photo.canRead {
                library.reload()
            }
            if bridge == nil {
                restartBridge()
            }
        }
        .onDisappear { bridge?.stopPolling() }
        .sheet(item: $pickTarget) { peer in
            PhotoPickerSheet(peer: peer, bridge: bridge)
                .environmentObject(library)
        }
        .alert(item: Binding(
            get: { bridge?.snapshot.incoming },
            set: { _ in }
        )) { offer in
            Alert(
                title: Text("收到照片"),
                message: Text("来自 \(offer.fromName)：\(offer.suggestedName)"),
                primaryButton: .default(Text("接收")) { bridge?.acceptIncoming(transferId: offer.id) },
                secondaryButton: .cancel(Text("拒绝")) { bridge?.rejectIncoming(transferId: offer.id) }
            )
        }
    }

    // MARK: - 名字

    private var nameSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("本机名字")
                .font(SuiPaiTheme.title)
                .foregroundColor(SuiPaiTheme.onBackground)
            HStack(spacing: 10) {
                TextField("例如：我的 iPhone", text: $deviceName)
                    .textFieldStyle(.plain)
                    .foregroundColor(SuiPaiTheme.ivory)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(SuiPaiTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                Button("应用") {
                    UserDefaults.standard.set(deviceName, forKey: SyncView.nameKey)
                    restartBridge()
                }
                .font(SuiPaiTheme.body)
                .foregroundColor(SuiPaiTheme.ivory)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(SuiPaiTheme.shutter)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            Text("改名字会重启发现服务；对方看到的也是这个名字。")
                .font(SuiPaiTheme.caption)
                .foregroundColor(SuiPaiTheme.secondaryText)
        }
    }

    // MARK: - 设备

    private var peersSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("附近设备")
                    .font(SuiPaiTheme.title)
                    .foregroundColor(SuiPaiTheme.onBackground)
                Spacer()
                Text("\(bridge?.snapshot.peers.count ?? 0) 台")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
            }
            if let peers = bridge?.snapshot.peers, !peers.isEmpty {
                ForEach(peers) { peer in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peer.name)
                                .font(SuiPaiTheme.body)
                                .foregroundColor(SuiPaiTheme.onBackground)
                            Text(peer.platform.isEmpty ? "未知平台" : peer.platform)
                                .font(SuiPaiTheme.caption)
                                .foregroundColor(SuiPaiTheme.secondaryText)
                        }
                        Spacer()
                        Button("发送照片") {
                            pickTarget = peer
                        }
                        .font(SuiPaiTheme.caption)
                        .foregroundColor(SuiPaiTheme.ivory)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(SuiPaiTheme.shutter)
                        .clipShape(Capsule())
                        .disabled(bridge?.isSending ?? false)
                    }
                    .padding(12)
                    .background(SuiPaiTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            } else {
                Text("还没发现设备。确认两台设备在同一局域网，且都打开了随拍。")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
                    .padding(.vertical, 12)
            }
            // TODO: 浏览并下载对端照片（以 Android 侧为基准实现）
        }
    }

    private var statusSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let status = bridge?.snapshot.status, !status.isEmpty {
                Text(status)
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.secondaryText)
            }
            if let error = bridge?.lastError {
                Text(error)
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.shutter)
            }
            if !permissions.photo.canRead {
                Text("需要相册权限才能选照片发送。")
                    .font(SuiPaiTheme.caption)
                    .foregroundColor(SuiPaiTheme.shutter)
            }
        }
    }

    // MARK: - 工具

    private static let nameKey = "suipai.sync.name"

    private static func storedName() -> String {
        if let saved = UserDefaults.standard.string(forKey: nameKey), !saved.isEmpty {
            return saved
        }
        return UIDevice.current.name
    }

    private func restartBridge() {
        // 改名只能重建：SyncHub 的 deviceName 是构造期冻住的，没有 setter。
        // 重建前必须 shutdown() 旧的 —— 旧 hub 不放掉 TCP 端口的话，新 hub 的
        // server.start 直接失败。对照 Android 的 SyncManager.rename（先 stop 再起）。
        bridge?.shutdown()
        let created = SyncBridge(deviceName: deviceName.isEmpty ? UIDevice.current.name : deviceName)
        bridge = created
        created.startPolling()
    }
}

// MARK: - 选照片

private struct PhotoPickerSheet: View {
    let peer: PeerDevice
    let bridge: SyncBridge?

    @EnvironmentObject private var library: PhotoLibraryService
    @Environment(\.dismiss) private var dismiss

    @State private var selection = Set<String>()
    @State private var sending = false
    @State private var done = 0
    @State private var failed = 0

    private let columns = [
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
    ]

    var body: some View {
        NavigationView {
            ZStack {
                SuiPaiTheme.background.ignoresSafeArea()
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 2) {
                        ForEach(library.assets.prefix(library.displayCount), id: \.localIdentifier) { asset in
                            cell(asset)
                        }
                    }
                    if library.hasMore {
                        Button("加载更多") { library.loadMore() }
                            .font(SuiPaiTheme.body)
                            .foregroundColor(SuiPaiTheme.ivory)
                            .padding(.vertical, 12)
                    }
                }
            }
            .navigationTitle("发给 \(peer.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(sending ? "发送中 \(done)/\(selection.count)" : "发送(\(selection.count))") {
                        send()
                    }
                    .disabled(selection.isEmpty || sending)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func cell(_ asset: PHAsset) -> some View {
        let side = (UIScreen.main.bounds.width - 4) / 3
        let selected = selection.contains(asset.localIdentifier)
        return ZStack(alignment: .bottomTrailing) {
            SyncThumb(asset: asset, side: side)
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(SuiPaiTheme.shutter)
                    .padding(5)
            }
        }
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .onTapGesture {
            if selected {
                selection.remove(asset.localIdentifier)
            } else {
                selection.insert(asset.localIdentifier)
            }
        }
    }

    private func send() {
        guard let bridge = bridge else { return }
        sending = true
        done = 0
        failed = 0
        let assets = library.assets.filter { selection.contains($0.localIdentifier) }
        sendNext(assets, index: 0, bridge: bridge)
    }

    private func sendNext(_ assets: [PHAsset], index: Int, bridge: SyncBridge) {
        guard index < assets.count else {
            sending = false
            if failed == 0 { dismiss() }
            return
        }
        bridge.send(peer: peer.peer, asset: assets[index]) { ok in
            if ok { done += 1 } else { failed += 1 }
            sendNext(assets, index: index + 1, bridge: bridge)
        }
    }
}

private struct SyncThumb: View {
    let asset: PHAsset
    let side: CGFloat

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            SuiPaiTheme.surfaceVariant
            if let image = image {
                Image(uiImage: image).resizable().scaledToFill()
            }
        }
        .frame(width: side, height: side)
        .clipped()
        .task(id: asset.localIdentifier) {
            library.thumbnail(for: asset, size: CGSize(width: side, height: side)) { loaded in
                image = loaded
            }
        }
    }
}