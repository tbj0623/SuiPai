import Foundation
import Photos
import AVFoundation
import Combine

/// 相册访问档位。iOS 的「受限访问」（limited）要和「拒绝」区分开：
/// 受限时仍能读到用户挑过的照片，不该直接甩权限墙。
enum PhotoAccessState: Equatable {
    case notDetermined
    case denied
    case limited
    case authorized

    var canRead: Bool { self == .authorized || self == .limited }
}

/// 权限状态。刻意不加 @MainActor：请求回调可能落在任意线程，
/// 统一在赋值时切回主线程，省掉一层 actor 隔离带来的编译期麻烦。
final class Permissions: ObservableObject {
    @Published private(set) var photo: PhotoAccessState = .notDetermined
    @Published private(set) var camera: AVAuthorizationStatus = .notDetermined

    init() {
        refresh()
    }

    func refresh() {
        let photoState = Self.currentPhotoState()
        let cameraState = AVCaptureDevice.authorizationStatus(for: .video)
        DispatchQueue.main.async {
            self.photo = photoState
            self.camera = cameraState
        }
    }

    /// 请求相册读写权限。用 .readWrite：存成片到「SuiPai」相簿需要写权限。
    func requestPhoto() async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        let mapped = Self.map(status)
        await MainActor.run { self.photo = mapped }
    }

    func requestCamera() async {
        _ = await AVCaptureDevice.requestAccess(for: .video)
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        await MainActor.run { self.camera = status }
    }

    private static func currentPhotoState() -> PhotoAccessState {
        map(PHPhotoLibrary.authorizationStatus(for: .readWrite))
    }

    private static func map(_ status: PHAuthorizationStatus) -> PhotoAccessState {
        switch status {
        case .notDetermined: return .notDetermined
        case .restricted, .denied: return .denied
        case .limited: return .limited
        case .authorized: return .authorized
        @unknown default: return .denied
        }
    }
}