import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255,
            opacity: opacity,
        )
    }

    fileprivate init(dark: UInt32, light: UInt32) {
        #if os(macOS)
        let darkColor = NSColor(Color(hex: dark))
        let lightColor = NSColor(Color(hex: light))
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? darkColor : lightColor
        })
        #else
        let darkColor = UIColor(Color(hex: dark))
        let lightColor = UIColor(Color(hex: light))
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? darkColor : lightColor
        })
        #endif
    }
}

/// The Android app's Material 3 appearance palettes (ui/theme/Theme.kt).
enum Brand {
    static let violet = Color(hex: 0x4A2BD1)
    static let teal = Color(hex: 0x0E8F9A)
    static let mountain = Color(hex: 0x2B2F77)

    static let playbackAccent = Color(hex: 0x2EE6C9)
    static let primary = Color(dark: 0x2EE6C9, light: 0x006B5D)
    static let onPrimary = Color(dark: 0x00382F, light: 0xFFFFFF)
    static let secondaryContainer = Color(dark: 0x2E3A8C, light: 0xDDE1FF)
    static let onSecondaryContainer = Color(dark: 0xDDE1FF, light: 0x101A58)
    static let tertiary = Color(dark: 0xFFB86B, light: 0x854E10)
    static let error = Color(dark: 0xEF5350, light: 0xAD302D)
    static let onError = Color(dark: 0x2B0606, light: 0xFFFFFF)
    static let background = Color(dark: 0x0B1020, light: 0xF7F8FD)
    static let onSurface = Color(dark: 0xE3E6F3, light: 0x191D2C)
    static let onSurfaceVariant = Color(dark: 0xA9B0C9, light: 0x454C62)
    static let surfaceVariant = Color(dark: 0x1F2742, light: 0xE2E6F2)
    static let outline = Color(dark: 0x5A627F, light: 0x666E85)
    static let outlineVariant = Color(dark: 0x2B3354, light: 0xC4CBDE)
    static let surfaceContainer = Color(dark: 0x151B30, light: 0xEAEDF6)
    static let surfaceContainerHigh = Color(dark: 0x1B2238, light: 0xE4E8F3)
    static let surfaceContainerHighest = Color(dark: 0x232B45, light: 0xDEE3EF)

    // Aliases kept for the logo and splash.
    static let mint = primary
    static let text = onSurface
    static let card = surfaceContainerHighest

    static let gradient = LinearGradient(colors: [violet, teal], startPoint: .topLeading, endPoint: .bottomTrailing)
}

/// Green for the positive or start action, red for the negative or end action.
enum ActionColors {
    static let positive = Color(dark: 0x66BB6A, light: 0x25672C)
    static let onPositive = Color(dark: 0x0B2A10, light: 0xFFFFFF)
    static let negative = Brand.error
    static let onNegative = Brand.onError
}

/// The fixed set of accent tones for icons: the icon colour and a soft tint for its tile.
enum Tone: CaseIterable {
    case blue, green, amber, red, violet, teal, pink

    var icon: Color {
        switch self {
        case .blue: return Color(dark: 0x6DB0F8, light: 0x215B91)
        case .green: return Color(dark: 0x5FD096, light: 0x246440)
        case .amber: return Color(dark: 0xF0B45A, light: 0x794B06)
        case .red: return Color(dark: 0xFF8A7E, light: 0x99352B)
        case .violet: return Color(dark: 0xC4B5FD, light: 0x6046A1)
        case .teal: return Color(dark: 0x5FCDE0, light: 0x176475)
        case .pink: return Color(dark: 0xF59AC4, light: 0x8D3C64)
        }
    }

    var tint: Color {
        switch self {
        case .blue: return Color(dark: 0x14253B, light: 0xDCEEFF)
        case .green: return Color(dark: 0x112A1C, light: 0xDBF5E4)
        case .amber: return Color(dark: 0x33260C, light: 0xFFEDD0)
        case .red: return Color(dark: 0x3A1815, light: 0xFFE3DE)
        case .violet: return Color(dark: 0x2A1F45, light: 0xEDE4FF)
        case .teal: return Color(dark: 0x0F2D35, light: 0xD7F4FA)
        case .pink: return Color(dark: 0x3D1830, light: 0xFBE3EF)
        }
    }
}

/// A repeatable 4/8/16/24/32/48 rhythm.
enum Spacing {
    static let xs: CGFloat = 4, sm: CGFloat = 8, md: CGFloat = 16, lg: CGFloat = 24, xl: CGFloat = 32, xxl: CGFloat = 48
}

/// Corner radii by role: 8 extra small, 12 small, 16 medium, 24 large, 28 extra large (dialogs).
enum Radius {
    static let xs: CGFloat = 8, sm: CGFloat = 12, md: CGFloat = 16, lg: CGFloat = 24, xl: CGFloat = 28
}

enum InterWeight {
    case regular, medium, semibold, bold

    var swiftUI: Font.Weight {
        switch self {
        case .regular: return .regular
        case .medium: return .medium
        case .semibold: return .semibold
        case .bold: return .bold
        }
    }
}

extension Font {
    /// Inter, bundled from the Android app's res/font. The variable font exposes one family; the weight picks the
    /// instance along its wght axis.
    static func inter(size: CGFloat, weight: InterWeight = .regular) -> Font {
        Font.custom("Inter", size: size).weight(weight.swiftUI)
    }

    /// Kept for the splash and the logo.
    static func inter(_ style: Font.TextStyle, weight: InterWeight = .regular) -> Font {
        inter(size: style == .title2 ? 22 : 17, weight: weight)
    }
}

/// The Android type scale (ui/theme/Type.kt): size, line height and weight.
enum TextStyle {
    case titleLarge, titleMedium, titleSmall, headlineSmall
    case bodyLarge, bodyMedium, bodySmall
    case labelLarge, labelMedium, labelSmall

    var spec: (size: CGFloat, line: CGFloat, weight: InterWeight) {
        switch self {
        case .titleLarge: return (28, 36, .bold)
        case .titleMedium: return (18, 26, .semibold)
        case .titleSmall: return (14, 20, .semibold)
        case .headlineSmall: return (24, 32, .semibold)
        case .bodyLarge: return (16, 24, .regular)
        case .bodyMedium: return (14, 22, .regular)
        case .bodySmall: return (12, 18, .regular)
        case .labelLarge: return (14, 20, .medium)
        case .labelMedium: return (12, 16, .medium)
        case .labelSmall: return (11, 16, .medium)
        }
    }
}

private struct TextStyleModifier: ViewModifier {
    let style: TextStyle
    var color: Color?

    func body(content: Content) -> some View {
        let spec = style.spec
        content
            .font(.inter(size: spec.size, weight: spec.weight))
            // Inter's natural line height is about 1.21 times the size; the rest becomes extra line spacing.
            .lineSpacing(max(0, spec.line - spec.size * 1.21))
            .foregroundColor(color ?? Brand.onSurface)
    }
}

extension View {
    func textStyle(_ style: TextStyle, color: Color? = nil) -> some View {
        modifier(TextStyleModifier(style: style, color: color))
    }

    /// Fills the screen with the brand background.
    func appBackground() -> some View {
        background(Brand.background.ignoresSafeArea())
    }
}
