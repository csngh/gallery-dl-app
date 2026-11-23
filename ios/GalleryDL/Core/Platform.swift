import SwiftUI
import CoreGraphics

// One place for what differs between the iPhone and the Mac, so the rest of the code does not ask which one it is on.

#if canImport(UIKit)
import UIKit

typealias PlatformImage = UIImage

enum Platform {
    static let isMac = false

    /// Opens the "Enhance" flow for a video. Only the Mac has one.
    @MainActor static var enhance: ((LibraryItem, UIState) -> Void)?

    /// What this device is called in the app's own words, and what it tells other devices on the network it is.
    static let device = "phone"
    static let deviceType = "mobile"

    /// How many columns the library's folders and a folder's files may be shown in, and the number to start with.
    static let libraryColumns = 2...3, libraryColumnsDefault = 2
    static let folderColumns = 2...4, folderColumnsDefault = 3

    static var reduceMotion: Bool { UIAccessibility.isReduceMotionEnabled }

    static var deviceName: String { UIDevice.current.name }

    static func copyToClipboard(_ text: String) { UIPasteboard.general.string = text }

    static var screenSize: CGSize { UIScreen.main.bounds.size }

    /// Posted when the system is short of memory.
    static let memoryWarning = UIApplication.didReceiveMemoryWarningNotification
    static let reduceMotionChanged = UIAccessibility.reduceMotionStatusDidChangeNotification
    static let reduceMotionCenter = NotificationCenter.default
}

/// Keeps downloads going for a while after the app leaves the front. iOS gives a few minutes and then calls `onExpire`.
@MainActor
final class BackgroundWork {
    private var task: UIBackgroundTaskIdentifier = .invalid
    var isActive: Bool { task != .invalid }

    func begin(name: String, onExpire: @escaping () -> Void) {
        guard !isActive else { return }
        task = UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: onExpire)
    }

    func end() {
        guard isActive else { return }
        UIApplication.shared.endBackgroundTask(task)
        task = .invalid
    }
}
#else
import AppKit

typealias PlatformImage = NSImage

/// The few `UIImage` calls the shared code uses, on `NSImage`, so that code is the same on both.
extension NSImage {
    convenience init(cgImage: CGImage) {
        self.init(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    var cgImage: CGImage? { cgImage(forProposedRect: nil, context: nil, hints: nil) }

    func jpegData(compressionQuality: CGFloat) -> Data? {
        guard let cg = cgImage else { return nil }
        return NSBitmapImageRep(cgImage: cg).representation(using: .jpeg, properties: [.compressionFactor: compressionQuality])
    }

    /// The Mac decodes when it draws, so there is nothing to prepare.
    func preparingForDisplay() -> NSImage? { self }
}

extension Image {
    init(uiImage: NSImage) { self.init(nsImage: uiImage) }
}

// The on-screen keyboard and automatic capitals are iPhone things; on the Mac these are accepted and do nothing.
enum UIKeyboardType { case `default`, URL, asciiCapable, numbersAndPunctuation }

struct TextInputAutocapitalization {
    static let never = TextInputAutocapitalization()
}

enum UITextAutocapitalizationType { case none }

extension View {
    func autocapitalization(_ style: UITextAutocapitalizationType) -> some View { self }
    func keyboardType(_ type: UIKeyboardType) -> some View { self }
    func textInputAutocapitalization(_ value: TextInputAutocapitalization?) -> some View { self }
}

enum Platform {
    static let isMac = true

    @MainActor static var enhance: ((LibraryItem, UIState) -> Void)?

    static let device = "Mac"
    static let deviceType = "desktop"

    // A Mac window is wide, so it has room for more columns than a phone.
    static let libraryColumns = 3...6, libraryColumnsDefault = 4
    static let folderColumns = 3...8, folderColumnsDefault = 5

    static var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    static var deviceName: String { Host.current().localizedName ?? ProcessInfo.processInfo.hostName }

    static func copyToClipboard(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    static var screenSize: CGSize { NSScreen.main?.frame.size ?? CGSize(width: 1440, height: 900) }

    static let memoryWarning = Notification.Name("GalleryDLMemoryWarning")
    static let reduceMotionChanged = NSWorkspace.accessibilityDisplayOptionsDidChangeNotification
    static let reduceMotionCenter = NSWorkspace.shared.notificationCenter
}

/// Keeps the Mac from napping the app while downloads run. A Mac does not take background time away, so `onExpire` is
/// never called.
@MainActor
final class BackgroundWork {
    private var activity: NSObjectProtocol?
    var isActive: Bool { activity != nil }

    func begin(name: String, onExpire: @escaping () -> Void) {
        guard !isActive else { return }
        activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleSystemSleepDisabled], reason: name)
    }

    func end() {
        guard let activity else { return }
        ProcessInfo.processInfo.endActivity(activity)
        self.activity = nil
    }
}
#endif

extension View {
    /// The iPhone hides its status bar while a picture fills the screen; a Mac window has none.
    func hidingStatusBar() -> some View {
        #if canImport(UIKit)
        statusBarHidden(true)
        #else
        self
        #endif
    }
}

extension View {
    /// Applies `transform` on the Mac only. The iPhone does the same thing with a long press instead.
    @ViewBuilder func onMac<Content: View>(_ transform: (Self) -> Content) -> some View {
        if Platform.isMac { transform(self) } else { self }
    }
}

extension View {
    /// A text field with a border in the app's colors: the system's rounded field on the iPhone, a plain one with its own
    /// outline on the Mac, where the system field is a dark bezel that does not match.
    @ViewBuilder func outlinedTextField() -> some View {
        #if canImport(UIKit)
        textFieldStyle(.roundedBorder)
        #else
        textFieldStyle(.plain)
            .padding(.horizontal, Spacing.sm + 4)
            .frame(height: 40)
            .overlay(RoundedRectangle(cornerRadius: Radius.sm).stroke(Brand.outline, lineWidth: 1))
        #endif
    }
}
