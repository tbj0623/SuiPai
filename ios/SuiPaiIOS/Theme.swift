import SwiftUI

/// 深色暖色主题。色值直接取自 Android 的 ui/theme/Theme.kt，
/// 保证两端观感一致：近黑的墨色背景、象牙白前景、朱红作为唯一强调色。
enum SuiPaiTheme {
    // 与 Android 一一对应的色板
    static let ink = Color(hex: 0x0C0C0D)
    static let panel = Color(hex: 0x161618)
    static let ivory = Color(hex: 0xF2EDE6)
    static let paper = Color(hex: 0xF6F3EE)
    static let shutter = Color(hex: 0xD94F3D)
    static let quiet = Color(hex: 0x8A8680)
    static let inkText = Color(hex: 0x1C1B19)
    static let surfaceVariant = Color(hex: 0x222226)
    static let outline = Color(hex: 0x3A3936)

    // 语义别名，视图里读这些，避免到处写 hex
    static let background = ink
    static let surface = panel
    static let onBackground = ivory
    static let onSurface = ivory
    static let secondaryText = quiet
    static let divider = outline

    static let title = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 15)
    static let caption = Font.system(size: 12)
    static let chip = Font.system(size: 11, weight: .medium)
}

extension Color {
    /// 主题常量用 0xRRGGBB 写，比 Color(red:green:blue:) 少出错。
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}