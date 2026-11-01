import SwiftUI
import UIKit

@main
struct SuiPaiIOSApp: App {
    @StateObject private var permissions = Permissions()
    @StateObject private var library = PhotoLibraryService.shared

    init() {
        SuiPaiAppearance.apply()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(permissions)
                .environmentObject(library)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            GalleryView()
                .tabItem { Label("相册", systemImage: "photo.on.rectangle") }
                .tag(0)
            CameraView()
                .tabItem { Label("相机", systemImage: "camera") }
                .tag(1)
            SyncView()
                .tabItem { Label("互通", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(2)
        }
        .tint(SuiPaiTheme.shutter)
    }
}

/// Tab 栏默认是浅色半透明，在近黑背景上会发灰；统一压成深色。
enum SuiPaiAppearance {
    static func apply() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(SuiPaiTheme.panel)
        UITabBar.appearance().standardAppearance = appearance
        if #available(iOS 15.0, *) {
            UITabBar.appearance().scrollEdgeAppearance = appearance
        }
    }
}