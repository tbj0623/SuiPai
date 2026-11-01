import Foundation
import Photos
import Combine
import SuiPaiKit

// 互通的 Kotlin 侧是实打实存在的（shared/src/commonMain/kotlin/com/suipai/core/sync/），
// 本文件只是它在 Swift 侧的适配层。逐个核对过的声明（以 Kotlin 源码为准）：
//   class SyncHub(deviceId: String, deviceName: String, platform: String,
//                 source: SyncPhotoSource?, sink: SyncPhotoSink?,
//                 tcpPort: Int = SyncProtocol.TCP_PORT, udpPort: Int = SyncProtocol.UDP_PORT)
//     fun start() / stop() / refreshPeers()          // start() 非阻塞；不调它什么网络都没有
//     val state: StateFlow<SyncState>
//     suspend fun sendPhoto(peer:meta:bytes:) -> Boolean   // 失败收敛成 false + lastError，不抛
//     fun acceptIncoming(transferId: String) / rejectIncoming(transferId: String)
//   interface SyncPhotoSource { listPhotos(offset:limit:) -> List<SyncPhotoMeta>
//                               readPhotoBytes(id:) -> ByteArray?
//                               readThumbBytes(id:maxEdge:) -> ByteArray? }
//   interface SyncPhotoSink { saveIncoming(bytes:suggestedName:dateTaken:) -> Boolean }
//   data class SyncState(running: Boolean, peers: List<PeerInfo>, incoming: List<IncomingTransfer>,
//                        outgoing: List<OutgoingTransfer>, lastError: String?)
//   data class IncomingTransfer(transferId: String, fromPeer: PeerInfo, meta: SyncPhotoMeta, bytes: ByteArray)
//   data class SyncPhotoMeta(id: String, name: String, dateTaken: Long, width: Int, height: Int, sizeBytes: Long)
//   data class PeerInfo(id: String, name: String, platform: String, host: String, tcpPort: Int, lastSeenMs: Long)
// 装配与生命周期以 Android 为基准：app/src/main/java/com/suipai/app/sync/SyncSupport.kt
// （SyncManager.ensure 创建即 start；rename 先 stop 旧的再起新的，否则 TCP 端口被旧 hub 占着）。
// 注意 Kotlin 的默认参数值不导出到 Swift，所以构造器参数必须全量显式传。
// ⚠ 除 SyncHub 外，上面这些类型在 Kotlin 侧都没有 @ObjCName(exact = true)，而 K/N 会给未固定的
// 名字加框架前缀（baseName = SuiPaiKit）。首次 Mac 编译若报「找不到类型」，二选一：
// 在 shared 侧照 SyncHub 那样补 @ObjCName(exact = true)，或在本文件顶部加一组 typealias 收口。

/// 界面用的附近设备。
struct PeerDevice: Identifiable {
    let id: String
    let name: String
    let platform: String
    let peer: PeerInfo
}

/// 待确认的接收请求。
struct IncomingOffer: Identifiable {
    let id: String
    let fromName: String
    let suggestedName: String
}

/// 从 SyncHub 状态里读出来的快照。所有字段都是 Swift 原生类型，UI 不碰 Kotlin。
struct SyncSnapshot {
    var peers: [PeerDevice] = []
    var incoming: IncomingOffer?
    var status: String = ""
}

final class SyncBridge: NSObject, ObservableObject {
    @Published private(set) var snapshot = SyncSnapshot()
    @Published private(set) var isSending = false
    @Published var lastError: String?

    private(set) var hub: SyncHub!
    private var timer: Timer?
    private let deviceName: String
    /// 已经回显过的 hub 错误，用来判断 lastError 是不是新变化。
    private var lastSeenHubError: String?

    init(deviceName: String) {
        self.deviceName = deviceName
        super.init()
        // 用 self 当 source/sink：SyncBridge 同时实现两个 Kotlin protocol
        hub = SyncHub(
            deviceId: SyncBridge.persistentDeviceId(),
            deviceName: deviceName,
            platform: "ios",
            source: self,
            sink: self,
            tcpPort: SyncBridge.tcpPort,
            udpPort: SyncBridge.udpPort
        )
        // 不调 start() 的话 SyncHub 就是个空壳：TCP server 不监听、UDP 发现既不广播也不收，
        // 整个互通功能是死的。start() 非阻塞（内部起 Dispatchers.Default 协程）。
        hub.start()
    }

    /// 停掉 hub 的后台活动。改名重建前必须先调它：旧 hub 不放掉 47471，新 hub 的
    /// server.start 会直接失败（Android 的 SyncManager.rename 就是这个顺序）。
    ///
    /// 只能显式调用，别指望 deinit：Kotlin 的 SyncHub 持有 source/sink（也就是 self）强引用，
    /// bridge ↔ hub 是个引用环，deinit 永远不会跑。想要真正回收，得先 stop() 再断开 hub。
    func shutdown() {
        stopPolling()
        hub.stop()
    }

    /// 端口与 shared/.../sync/SyncProtocol.kt 的 UDP_PORT = 47470 / TCP_PORT = 47471 对齐。
    /// 构造器在 Kotlin 侧有默认值，但默认值不导出给 Swift，必须显式传；写字面量而不是
    /// SyncProtocol.tcpPort 是因为 `const val` 的导出形式（类属性还是 shared 实例属性）
    /// 在 Mac 上没验证过，同 Support.swift 的 EngineLimits.autoLookId 一个理由。
    private static let tcpPort: Int32 = 47471
    private static let udpPort: Int32 = 47470

    // MARK: - 轮询状态

    /// Kotlin Flow 在 Swift 侧不直接订阅，起 Timer 每 0.5s 读一次 .value，
    /// 转成 Swift 结构后再进 UI。
    func startPolling() {
        stopPolling()
        pollOnce()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.pollOnce()
        }
    }

    func stopPolling() {
        timer?.invalidate()
        timer = nil
    }

    private func pollOnce() {
        // 状态就是 Kotlin 的 data class SyncState(running, peers, incoming, outgoing, lastError)。
        // 旧写法用 KVC 去摸 status/phase/message 这些根本不存在的字段，所以永远读不到东西。
        guard let state = hub.state.value as? SyncState else { return }
        var next = SyncSnapshot()
        next.status = state.running ? "正在发现附近设备…" : "互通未启动"
        next.peers = state.peers.map { peer in
            PeerDevice(id: peer.id, name: peer.name, platform: peer.platform, peer: peer)
        }
        // incoming 是 List，不是单个对象：一次只把最早的一条交给 UI（Android 同样是 firstOrNull）。
        // 旧写法把它当对象判断，非空即真，于是弹窗无条件弹出来且全是占位数据。
        if let transfer = state.incoming.first {
            next.incoming = IncomingOffer(
                id: transfer.transferId,
                fromName: transfer.fromPeer.name,
                suggestedName: transfer.meta.name
            )
        }
        let hubError = state.lastError
        let publish = {
            self.snapshot = next
            // Kotlin 侧从不清空 lastError，原样透传会让一次旧错误常驻红字；只在它变化时
            // 顶一次，语义对齐 Android 的 LaunchedEffect(state.lastError) { snackbar(...) }。
            if let hubError = hubError, hubError != self.lastSeenHubError {
                self.lastSeenHubError = hubError
                self.lastError = hubError
            }
        }
        // @Published 更新必须回主线程
        if Thread.isMainThread {
            publish()
        } else {
            DispatchQueue.main.async(execute: publish)
        }
    }

    // MARK: - 发送

    func send(peer: PeerInfo, asset: PHAsset, completion: @escaping (Bool) -> Void) {
        isSending = true
        let meta = makeMeta(asset: asset)
        let id = asset.localIdentifier
        Task {
            var ok = false
            do {
                guard let data = await SyncBridge.loadBytes(assetId: id) else {
                    throw SyncError.readFailed
                }
                let bytes = ByteBridge.kotlinByteArray(from: data)
                // KMP 2.0 把 Kotlin suspend 导出成 Swift async。
                // sendPhoto 不抛异常：连不上、对端拒绝、帧读坏一律返回 false（原因写进
                // SyncState.lastError），所以必须看返回值，不能「没崩就算成功」。
                ok = try await hub.sendPhoto(peer: peer, meta: meta, bytes: bytes)
            } catch {
                await MainActor.run { self.lastError = "发送失败：\(error.localizedDescription)" }
            }
            await MainActor.run {
                self.isSending = false
                completion(ok)
            }
        }
    }

    /// 接受/拒绝都要带 transferId：Kotlin 侧按 id 在 state.incoming 里找那一条。
    /// 弹窗的 offer.id 就是 transferId（见 pollOnce）。
    ///
    /// acceptIncoming 是普通（非 suspend）函数，会在**调用线程**上同步回调
    /// sink.saveIncoming —— 那条路径要等相册写盘（本文件里最长等 30s），所以不能
    /// 在主线程上跑。落地失败时 hub 不会把这条移出 incoming，轮询会把它再顶上来，
    /// 因此这里不乐观清空 snapshot.incoming。
    func acceptIncoming(transferId: String) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.hub.acceptIncoming(transferId: transferId)
            self?.pollOnce()
        }
    }

    func rejectIncoming(transferId: String) {
        hub.rejectIncoming(transferId: transferId)
        // 拒绝是纯内存操作，立刻刷一次快照，弹窗不用等下一个 0.5s
        pollOnce()
    }

    // MARK: - 工具

    private func makeMeta(asset: PHAsset) -> SyncPhotoMeta {
        // 字段名与顺序按 shared/.../sync/SyncProtocol.kt 的
        // SyncPhotoMeta(id, name, dateTaken: Long, width: Int, height: Int, sizeBytes: Long)。
        // sizeBytes 传 0 是已知缺口：Android 侧会查 MediaStore.SIZE，iOS 这边要么走
        // PHAssetResource.estimatedFileSize（同步、可能卡主线程），要么等列表时异步补。
        // 协议里的 MAX_BODY 检查用的是实际 body，不依赖这个字段，所以先留 0。
        SyncPhotoMeta(
            id: asset.localIdentifier,
            name: PhotoLibraryService.shared.filename(for: asset),
            dateTaken: Int64((asset.creationDate ?? Date()).timeIntervalSince1970 * 1000),
            width: Int32(asset.pixelWidth),
            height: Int32(asset.pixelHeight),
            sizeBytes: 0
        )
    }

    private static func loadBytes(assetId: String) async -> Data? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil)
        guard let asset = result.firstObject else { return nil }
        return await PhotoInputLoader.requestData(asset: asset)
    }

    private static func persistentDeviceId() -> String {
        let key = "suipai.sync.deviceId"
        if let existing = UserDefaults.standard.string(forKey: key) {
            return existing
        }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }

    enum SyncError: LocalizedError {
        case readFailed
        var errorDescription: String? {
            switch self {
            case .readFailed: return "无法读取照片数据"
            }
        }
    }
}

// MARK: - SyncPhotoSource（给对端列照片/读字节）

extension SyncBridge: SyncPhotoSource {
    func listPhotos(offset: Int32, limit: Int32) -> [SyncPhotoMeta] {
        let library = PhotoLibraryService.shared
        let start = max(0, Int(offset))
        let end = min(library.assets.count, start + max(0, Int(limit)))
        guard start < end else { return [] }
        return library.assets[start..<end].map { makeMeta(asset: $0) }
    }

    /// Kotlin 侧的返回类型是 `ByteArray?`，所以这里必须是可选值：
    /// 找不到 / 读不出来 / 超时都返回 nil，让 SyncHub 回 "not found"。
    /// 之前返回空数组会被 handleGetPhoto 当成一张合法的空照片发给对端。
    func readPhotoBytes(id: String) -> KotlinByteArray? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
        guard let asset = result.firstObject else { return nil }
        // Kotlin protocol 要求同步返回，这里用信号量等异步取图。
        let semaphore = DispatchSemaphore(value: 0)
        var payload = Data()
        Task {
            if let data = await PhotoInputLoader.requestData(asset: asset) {
                payload = data
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 30)
        guard !payload.isEmpty else { return nil }
        return ByteBridge.kotlinByteArray(from: payload)
    }

    func readThumbBytes(id: String, maxEdge: Int32) -> KotlinByteArray? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
        guard let asset = result.firstObject else { return nil }
        let semaphore = DispatchSemaphore(value: 0)
        var payload = Data()
        let edge = Int(maxEdge)
        Task {
            if let data = await PhotoInputLoader.requestData(asset: asset),
               let image = ImageEncoder.cgImage(from: data, maxEdge: edge),
               let thumb = PixelBridge.scaled(image, maxEdge: edge),
               let encoded = ImageEncoder.jpeg(from: thumb, quality: 0.8, userComment: nil) {
                payload = encoded
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 15)
        guard !payload.isEmpty else { return nil }
        return ByteBridge.kotlinByteArray(from: payload)
    }
}

// MARK: - SyncPhotoSink（接收对端照片）

extension SyncBridge: SyncPhotoSink {
    func saveIncoming(bytes: KotlinByteArray, suggestedName: String, dateTaken: Int64) -> Bool {
        let data = ByteBridge.data(from: bytes)
        guard !data.isEmpty else { return false }
        let name = suggestedName.isEmpty ? "SuiPai_收到_\(dateTaken).jpg" : suggestedName
        let semaphore = DispatchSemaphore(value: 0)
        var ok = false
        Task {
            ok = await PhotoLibraryService.shared.saveImageData(
                data, filename: name, albumName: EngineLimits.albumName
            )
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 30)
        return ok
    }
}