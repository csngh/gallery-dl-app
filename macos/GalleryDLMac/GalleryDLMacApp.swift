import AppKit
import CoreText
import SwiftUI

/// Keeps the app running after its window is closed, so downloads carry on, and brings the window back when the Dock
/// icon is clicked.
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    #if DEBUG
    /// `-snapshotAt 6,12 -snapshot /tmp/shot` saves the window as /tmp/shot-6.png and /tmp/shot-12.png after that many
    /// seconds, so the screen can be checked without the screen-recording permission.
    /// Marked deprecated so the old capture call below, which is fine for an app's own window, does not warn.
    @available(macOS, deprecated: 14.0, message: "Debug only")
    func applicationDidFinishLaunching(_ notification: Notification) {
        let defaults = UserDefaults.standard
        guard let base = defaults.string(forKey: "snapshot") else { return }
        // `-testClicks "40,404,186;48,270,186"` clicks the window at those points (seconds after launch, then x and y from the
        // top left), the way a mouse would, so buttons can be checked without anyone clicking.
        for click in (defaults.string(forKey: "testClicks") ?? "").split(separator: ";") {
            let parts = click.split(separator: ",").compactMap { Double($0) }
            // "time,x,y" is a click; "time,x,y,x2,y2" drags from the first point to the second.
            guard parts.count == 3 || parts.count == 5 else { continue }
            DispatchQueue.main.asyncAfter(deadline: .now() + parts[0]) {
                guard let window = NSApp.windows.first(where: \.isVisible), let view = window.contentView else { return }
                func send(_ type: NSEvent.EventType, _ x: Double, _ y: Double) {
                    let point = NSPoint(x: x, y: view.bounds.height - y)
                    if let event = NSEvent.mouseEvent(with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                                      windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1) {
                        window.sendEvent(event)
                    }
                }
                send(.leftMouseDown, parts[1], parts[2])
                if parts.count == 5 {
                    // Spread over about half a second, like a hand; all at once, the window can miss them.
                    for step in 1...12 {
                        let t = Double(step) / 12
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.04 * Double(step)) {
                            send(.leftMouseDragged, parts[1] + (parts[3] - parts[1]) * t, parts[2] + (parts[4] - parts[2]) * t)
                        }
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.04 * 13) { send(.leftMouseUp, parts[3], parts[4]) }
                } else {
                    send(.leftMouseUp, parts[1], parts[2])
                }
            }
        }
        for second in (defaults.string(forKey: "snapshotAt") ?? "6").split(separator: ",").compactMap({ Double($0) }) {
            DispatchQueue.main.asyncAfter(deadline: .now() + second) {
                guard let window = NSApp.windows.first(where: \.isVisible) else { return }
                // The window's own picture, so video layers are in it; an app may always capture its own windows.
                guard let image = Self.picture(of: window) else { return }
                try? NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: "\(base)-\(Int(second)).png"))
            }
        }
    }

    @available(macOS, deprecated: 14.0, message: "Debug only")
    private static func picture(of window: NSWindow) -> CGImage? {
        CGWindowListCreateImage(.null, .optionIncludingWindow, CGWindowID(window.windowNumber), [.bestResolution, .boundsIgnoreFraming])
    }
    #endif

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { sender.windows.first?.makeKeyAndOrderFront(nil) }
        return true
    }
}

@main
struct GalleryDLMacApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var delegate
    @StateObject private var app = AppModel()
    @StateObject private var ui = UIState()

    init() {
        // Enhanced videos not yet saved live in the temporary folder; one left by an app that was closed meanwhile is stale.
        let temporary = FileManager.default.temporaryDirectory
        for name in (try? FileManager.default.contentsOfDirectory(atPath: temporary.path)) ?? [] where name.hasPrefix("enhance-") || name.hasPrefix("enhanced-") {
            try? FileManager.default.removeItem(at: temporary.appendingPathComponent(name))
        }
        // The enhancer is a macOS 26 feature; on an older Mac the menu entry is simply not there.
        if #available(macOS 26.0, *) { Platform.enhance = { item, ui in ui.present(EnhanceDialog(item: item)) } }
        // The brand font ships inside the app; a Mac does not read it from the Info.plist like an iPhone does.
        if let url = Bundle.main.url(forResource: "inter_variable", withExtension: "ttf") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    var body: some Scene {
        Window("Gallery DL", id: "main") {
            MacRootView()
                .environmentObject(app)
                .environmentObject(app.store)
                .environmentObject(app.settings)
                .environmentObject(app.network)
                .environmentObject(ui)
                .frame(minWidth: 860, minHeight: 600)
        }
        .defaultSize(width: 1180, height: 780)
        .windowStyle(.hiddenTitleBar)
    }
}

/// A Mac window is never turned, so the orientation calls the iPhone viewer makes do nothing here.
@MainActor
enum ScreenOrientation {
    static func allowRotation() {}
    static func lockPortrait() {}
    static func rotate(toLandscape: Bool) {}
}
