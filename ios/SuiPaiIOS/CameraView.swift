import SwiftUI
import AVFoundation
import UIKit

/// 一次拍摄的产物：数据留着给「去一瞬」直接调色，省得再从相册读一遍。
struct CapturedPhoto: Equatable {
    let data: Data
    var savedToLibrary: Bool = false
}

// MARK: - 会话控制

final class CameraController: NSObject, ObservableObject {
    @Published private(set) var isRunning = false
    @Published var flashMode: AVCaptureDevice.FlashMode = .off
    @Published var position: AVCaptureDevice.Position = .back
    @Published var lastCapture: CapturedPhoto?
    @Published var errorMessage: String?
    @Published var isCapturing = false

    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(label: "com.suipai.camera.session")
    private var isConfigured = false
    weak var previewLayer: AVCaptureVideoPreviewLayer?

    // MARK: 生命周期

    func start() {
        sessionQueue.async {
            self.configureIfNeeded()
            if !self.session.isRunning {
                self.session.startRunning()
            }
            DispatchQueue.main.async { self.isRunning = true }
            self.updateVideoOrientation()
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning {
                self.session.stopRunning()
            }
            DispatchQueue.main.async { self.isRunning = false }
        }
    }

    private func configureIfNeeded() {
        guard !isConfigured else { return }
        session.beginConfiguration()
        session.sessionPreset = .photo
        session.inputs.forEach { session.removeInput($0) }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
                ?? AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.errorMessage = "无法访问相机" }
            return
        }
        session.addInput(input)
        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
        }
        // iOS 16 起标记为废弃，但仍可用；换成 maxPhotoDimensions 会绑死新 SDK，
        // 这里保守用旧 API。
        photoOutput.isHighResolutionCaptureEnabled = true
        session.commitConfiguration()
        isConfigured = true
    }

    // MARK: 操作

    func switchCamera() {
        sessionQueue.async {
            guard let current = self.session.inputs.first as? AVCaptureDeviceInput else { return }
            let target: AVCaptureDevice.Position = current.device.position == .back ? .front : .back
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: target),
                  let input = try? AVCaptureDeviceInput(device: device) else { return }
            self.session.beginConfiguration()
            self.session.removeInput(current)
            if self.session.canAddInput(input) {
                self.session.addInput(input)
            } else {
                self.session.addInput(current)
            }
            self.session.commitConfiguration()
            DispatchQueue.main.async { self.position = target }
            self.updateVideoOrientation()
        }
    }

    func cycleFlash() {
        switch flashMode {
        case .off: flashMode = .auto
        case .auto: flashMode = .on
        default: flashMode = .off
        }
    }

    /// 点按对焦：把预览层坐标转成设备坐标。
    func focus(atLayerPoint point: CGPoint) {
        guard let layer = previewLayer else { return }
        let devicePoint = layer.captureDevicePointConverted(fromLayerPoint: point)
        sessionQueue.async {
            guard let device = (self.session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.autoFocus) {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = .autoFocus
                }
                if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.autoExpose) {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = .autoExpose
                }
                device.unlockForConfiguration()
            } catch {
                // 对焦失败不打断拍摄，静默忽略
            }
        }
    }

    func capture() {
        DispatchQueue.main.async { self.isCapturing = true }
        sessionQueue.async {
            let settings = AVCapturePhotoSettings()
            settings.flashMode = self.flashMode
            settings.isHighResolutionCaptureEnabled = true
            if let conn = self.photoOutput.connection(with: .video), conn.isVideoOrientationSupported {
                conn.videoOrientation = CameraController.currentVideoOrientation()
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    // MARK: 方向

    /// AVCaptureVideoOrientation 与 UIInterfaceOrientation 在横屏上是左右互换的，
    /// 这是 Apple 示例里的标准映射。
    private static func videoOrientationFromInterface() -> AVCaptureVideoOrientation {
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        switch scene?.interfaceOrientation ?? .portrait {
        case .landscapeLeft: return .landscapeRight
        case .landscapeRight: return .landscapeLeft
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    /// UIApplication 只能在主线程读，会话线程上就同步切回主线程取一次。
    static func currentVideoOrientation() -> AVCaptureVideoOrientation {
        if Thread.isMainThread {
            return videoOrientationFromInterface()
        }
        var result: AVCaptureVideoOrientation = .portrait
        DispatchQueue.main.sync { result = videoOrientationFromInterface() }
        return result
    }

    func updateVideoOrientation() {
        DispatchQueue.main.async {
            guard let conn = self.previewLayer?.connection, conn.isVideoOrientationSupported else { return }
            conn.videoOrientation = CameraController.videoOrientationFromInterface()
        }
    }
}

// MARK: - 拍照回调

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        if let error = error {
            DispatchQueue.main.async {
                self.isCapturing = false
                self.errorMessage = error.localizedDescription
            }
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async {
                self.isCapturing = false
                self.errorMessage = "照片数据为空"
            }
            return
        }
        let captured = CapturedPhoto(data: data)
        DispatchQueue.main.async {
            self.isCapturing = false
            self.lastCapture = captured
        }
        let filename = "SuiPai_拍摄_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
        Task {
            let ok = await PhotoLibraryService.shared.saveImageData(
                data, filename: filename, albumName: EngineLimits.albumName
            )
            await MainActor.run {
                self.lastCapture?.savedToLibrary = ok
            }
        }
    }
}

// MARK: - 预览层

private final class PreviewLayerView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    let onLayerReady: (AVCaptureVideoPreviewLayer) -> Void

    func makeUIView(context: Context) -> PreviewLayerView {
        let view = PreviewLayerView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        onLayerReady(view.previewLayer)
        return view
    }

    func updateUIView(_ uiView: PreviewLayerView, context: Context) {
        if uiView.previewLayer.session !== session {
            uiView.previewLayer.session = session
        }
    }
}

// MARK: - 相机页

struct CameraView: View {
    @EnvironmentObject private var permissions: Permissions
    @StateObject private var controller = CameraController()

    @State private var shutterFlash = 0.0
    @State private var focusPoint: CGPoint?
    @State private var focusClearTask: Task<Void, Never>?
    @State private var yishunTarget: YishunTarget?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if permissions.camera == .authorized {
                cameraBody
            } else {
                permissionWall
            }
        }
        .onAppear {
            permissions.refresh()
            if permissions.camera == .authorized {
                controller.start()
            }
        }
        .onDisappear { controller.stop() }
        .onChange(of: permissions.camera) { status in
            if status == .authorized {
                controller.start()
            }
        }
        .alert("相机出错", isPresented: Binding(
            get: { controller.errorMessage != nil },
            set: { if !$0 { controller.errorMessage = nil } }
        )) {
            Button("好", role: .cancel) { controller.errorMessage = nil }
        } message: {
            Text(controller.errorMessage ?? "")
        }
        .fullScreenCover(item: $yishunTarget) { target in
            YishunView(input: target.input, onClose: { yishunTarget = nil })
                .environmentObject(PhotoLibraryService.shared)
        }
    }

    private var cameraBody: some View {
        ZStack {
            CameraPreviewView(session: controller.session) { layer in
                controller.previewLayer = layer
            }

            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { location in
                    focusPoint = location
                    controller.focus(atLayerPoint: location)
                    focusClearTask?.cancel()
                    focusClearTask = Task {
                        try? await Task.sleep(nanoseconds: 900_000_000)
                        if Task.isCancelled { return }
                        await MainActor.run { focusPoint = nil }
                    }
                }

            if let point = focusPoint {
                RoundedRectangle(cornerRadius: 2)
                    .stroke(Color.yellow, lineWidth: 1.5)
                    .frame(width: 68, height: 68)
                    .position(point)
                    .allowsHitTesting(false)
            }

            Color.white
                .opacity(shutterFlash)
                .allowsHitTesting(false)

            VStack {
                Spacer()
                bottomBar
            }
        }
        // 整块忽略安全区：预览层铺满屏幕，点按对焦的坐标换算才能和它对齐
        .ignoresSafeArea()
    }

    private var bottomBar: some View {
        VStack(spacing: 14) {
            if let captured = controller.lastCapture {
                Button {
                    yishunTarget = YishunTarget(input: .imageData(captured.data))
                } label: {
                    Text(captured.savedToLibrary ? "去一瞬" : "去一瞬（未存相册）")
                        .font(SuiPaiTheme.body)
                        .foregroundColor(SuiPaiTheme.ivory)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 8)
                        .background(SuiPaiTheme.shutter)
                        .clipShape(Capsule())
                }
            }

            HStack {
                Button {
                    controller.cycleFlash()
                } label: {
                    Image(systemName: flashSymbol)
                        .font(.system(size: 20))
                        .foregroundColor(SuiPaiTheme.ivory)
                        .frame(width: 48, height: 48)
                }

                Spacer()

                Button {
                    shutter()
                } label: {
                    ZStack {
                        Circle().stroke(SuiPaiTheme.ivory, lineWidth: 3)
                            .frame(width: 74, height: 74)
                        Circle().fill(SuiPaiTheme.ivory)
                            .frame(width: 60, height: 60)
                        if controller.isCapturing {
                            ProgressView().tint(SuiPaiTheme.ink)
                        }
                    }
                }
                .disabled(controller.isCapturing)

                Spacer()

                Button {
                    controller.switchCamera()
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath.camera")
                        .font(.system(size: 20))
                        .foregroundColor(SuiPaiTheme.ivory)
                        .frame(width: 48, height: 48)
                }
            }
            .padding(.horizontal, 30)
        }
        .padding(.bottom, 34)
        .padding(.top, 20)
        .background(
            LinearGradient(colors: [Color.black.opacity(0), Color.black.opacity(0.65)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private var flashSymbol: String {
        switch controller.flashMode {
        case .on: return "bolt.fill"
        case .auto: return "bolt.badge.a"
        default: return "bolt.slash"
        }
    }

    private func shutter() {
        controller.capture()
        shutterFlash = 0.85
        withAnimation(.easeOut(duration: 0.25)) {
            shutterFlash = 0
        }
    }

    private var permissionWall: some View {
        VStack(spacing: 14) {
            Image(systemName: "camera")
                .font(.system(size: 36))
                .foregroundColor(SuiPaiTheme.quiet)
            Text("拍照需要相机权限")
                .font(SuiPaiTheme.title)
                .foregroundColor(SuiPaiTheme.onBackground)
            Button("授权相机") {
                _ = Task { await permissions.requestCamera() }
            }
            .font(SuiPaiTheme.body)
            .foregroundColor(SuiPaiTheme.ivory)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(SuiPaiTheme.shutter)
            .clipShape(Capsule())
        }
    }
}