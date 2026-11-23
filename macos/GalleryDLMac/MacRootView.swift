import AppKit
import SwiftUI

/// Where the sidebar can go. The iPhone reaches Queue, Activity, Sync and Settings from menus; a Mac window has the room to
/// list them all.
private enum Section: Hashable, CaseIterable {
    case home, queue, library, activity, sync, settings

    var label: String {
        switch self {
        case .home: return S.navHome
        case .queue: return S.navQueue
        case .library: return S.navLibrary
        case .activity: return S.navActivity
        case .sync: return S.syncTitle
        case .settings: return S.settingsTitle
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .queue: return "arrow.down.to.line"
        case .library: return "photo.on.rectangle.angled"
        case .activity: return "chart.bar.fill"
        case .sync: return "arrow.triangle.2.circlepath"
        case .settings: return "gearshape.fill"
        }
    }

    var tone: Tone {
        switch self {
        case .home: return .pink
        case .queue: return .blue
        case .library: return .violet
        case .activity: return .green
        case .sync: return .teal
        case .settings: return .amber
        }
    }

    var shortcut: KeyEquivalent {
        switch self {
        case .home: return "1"
        case .queue: return "2"
        case .library: return "3"
        case .activity: return "4"
        case .sync: return "5"
        case .settings: return ","
        }
    }
}

struct MacRootView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var ui: UIState
    @Environment(\.scenePhase) private var scenePhase

    @State private var section = Section.home
    @State private var showSplash = true
    @StateObject private var homeInput = LinkInputModel()
    @StateObject private var queueInput = LinkInputModel()
    @StateObject private var library = LibraryViewModel()
    @StateObject private var previews = PreviewHost()

    var body: some View {
        ZStack {
            Brand.background.ignoresSafeArea()

            HStack(spacing: 0) {
                sidebar
                Rectangle().fill(Brand.outlineVariant).frame(width: 1)
                detail
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if let request = ui.viewer { ViewerOverlay(request: request).transition(.opacity).zIndex(1) }
            DialogHost().zIndex(2)
            if showSplash {
                SplashOverlay(playSound: settings.startupSound) { showSplash = false }.zIndex(3)
            }
        }
        .font(.inter(size: 16))
        .preferredColorScheme(settings.darkAppearance ? .dark : .light)
        // The Mac draws a bezel around a text field and a box around a menu button; the screens draw their own.
        .textFieldStyle(.plain)
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .environmentObject(previews)
        .background(WindowConfigurator())
        .background(SyncOfferWatcher(sync: app.sync))
        .onAppear { previews.enabledBySetting = settings.livePreviews; PreviewHost.speed = settings.previewSpeed }
        .onChange(of: settings.previewSpeed) { _, speed in PreviewHost.speed = speed }
        .onChange(of: settings.livePreviews) { _, on in previews.enabledBySetting = on }
        // Previews stop behind the full-screen viewer and whenever the app is not in front.
        .onChange(of: ui.viewer) { _, viewer in previews.viewerOpen = viewer != nil }
        .task {
            #if DEBUG
            if !UserDefaults.standard.bool(forKey: "noPermissionPrompt") { Notifications.requestPermission() }
            #else
            Notifications.requestPermission()
            #endif
            await app.boot()
            // Listening from the start, so another device can send favorites and links whatever screen this is on.
            await app.sync.start(searching: false)
            #if DEBUG
            await debugLaunchActions()
            #endif
        }
        .onChange(of: scenePhase) { _, phase in
            app.sceneChanged(phase)
            previews.appActive = phase == .active
            if phase == .active { previews.refreshSystemState() }
        }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.sm) {
                LogoArt(size: 36, cardsScale: 1, badgeDrop: 0)
                Text("Gallery DL").textStyle(.titleMedium)
            }
            // Room for the window's traffic-light buttons, which sit over the top of a window with no title bar.
            .padding(.top, 38)
            .padding(.bottom, Spacing.md)
            .padding(.horizontal, Spacing.sm)

            ForEach([Section.home, .queue, .library, .activity], id: \.self) { row($0) }
            Divider().overlay(Brand.outlineVariant).padding(.vertical, Spacing.sm)
            ForEach([Section.sync, .settings], id: \.self) { row($0) }

            Spacer(minLength: 0)
            if !app.galleryDlVersion.isEmpty {
                Text("gallery-dl \(app.galleryDlVersion)")
                    .textStyle(.labelSmall, color: Brand.onSurfaceVariant)
                    .padding(.horizontal, Spacing.sm)
            }
        }
        .padding(.horizontal, Spacing.sm)
        .padding(.bottom, Spacing.md)
        .frame(width: 220)
        .background(Brand.surfaceContainer)
    }

    private func row(_ target: Section) -> some View {
        let selected = section == target
        return Button {
            if selected, target == .library { library.closeFolder() }
            section = target
        } label: {
            HStack(spacing: Spacing.sm + 4) {
                Image(systemName: target.symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(target.tone.icon)
                    .frame(width: 28, height: 28)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(selected ? target.tone.tint : Color.clear))
                Text(target.label).textStyle(.labelLarge, color: selected ? Brand.onSurface : Brand.onSurfaceVariant)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.sm)
            .frame(height: 40)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous).fill(selected ? Brand.secondaryContainer.opacity(0.55) : Color.clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .keyboardShortcut(target.shortcut, modifiers: .command)
    }

    // MARK: Detail

    @ViewBuilder private var detail: some View {
        switch section {
        case .home:
            narrow(HomeScreen(input: homeInput, onOpenSettings: { section = .settings }, onOpenQueue: { section = .queue },
                              onOpenActivity: { section = .activity }, onOpenSync: { section = .sync }))
        case .queue: narrow(QueueScreen(input: queueInput, onBack: { section = .home }))
        case .library: LibraryScreen(model: library).appBackground()
        case .activity: narrow(ActivityScreen(onBack: { section = .home }))
        case .sync: narrow(SyncScreen { section = .home })
        case .settings: narrow(SettingsScreen { section = .home })
        }
    }

    /// The phone screens are laid out for a narrow column, so on a wide window they stay a readable width in the middle.
    private func narrow<Content: View>(_ content: Content) -> some View {
        content
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
            .appBackground()
    }

    #if DEBUG
    /// Launch arguments for checking the app: `-testURLs a,b` queues and starts downloads, `-testSection
    /// home|queue|library|activity|sync|settings` chooses the screen, `-testFolder 1` opens the first library folder and
    /// `-testViewer 1` its first file.
    private func debugLaunchActions() async {
        let defaults = UserDefaults.standard
        for link in (defaults.string(forKey: "testURLs") ?? "").split(separator: ",").map(String.init) {
            let result = await app.validator.check(link)
            if result.check == .valid { app.store.enqueue(url: link, site: result.site) }
        }
        if defaults.string(forKey: "testURLs") != nil { app.engine.startAll() }
        switch defaults.string(forKey: "testSection") {
        case "queue": section = .queue
        case "library": section = .library
        case "activity": section = .activity
        case "sync": section = .sync
        case "settings": section = .settings
        default: break
        }
        // `-testEnhance name` opens Enhance on the file whose name contains that text; `-testEnhanceAction copy|replace` then
        // chooses by itself once the result is ready.
        if #available(macOS 26.0, *), let name = defaults.string(forKey: "testEnhance"),
           let item = app.store.items.first(where: { $0.displayName.contains(name) }) {
            ui.present(EnhanceDialog(item: item))
            // `-testEnhanceCancelAfter 20` closes the dialog that many seconds later, as Cancel does.
            if defaults.object(forKey: "testEnhanceCancelAfter") != nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + defaults.double(forKey: "testEnhanceCancelAfter")) { ui.dismissDialog() }
            }
        }
        if defaults.bool(forKey: "testFolder"), let folder = app.store.folderSummaries(query: "").first(where: { $0.name == (defaults.string(forKey: "testFolderName") ?? $0.name) }) {
            section = .library
            library.open(folder)
            if defaults.bool(forKey: "testViewer") {
                ui.viewer = ViewerRequest(folderId: folder.id, query: "", startIndex: defaults.integer(forKey: "testViewerIndex"))
            }
        }
    }
    #endif
}

/// The window has no title bar, so it is dragged by its background, and its sidebar reaches the top.
private struct WindowConfigurator: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            guard let window = view.window else { return }
            window.isMovableByWindowBackground = true
            window.titlebarAppearsTransparent = true
            window.backgroundColor = NSColor(Brand.background)
        }
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {}
}

/// Asks about an offer from another device on whatever screen is showing, because the Mac listens all the time.
private struct SyncOfferWatcher: View {
    @ObservedObject var sync: SyncManager
    @EnvironmentObject private var ui: UIState
    @State private var shown = false

    var body: some View {
        Color.clear
            .onChange(of: sync.offer?.id) { _, _ in
                if let offer = sync.offer {
                    ui.present(SyncOfferDialog(sync: sync, offer: offer))
                    shown = true
                    #if DEBUG
                    // `-testAcceptOffers 1` answers yes by itself, to check receiving without anyone clicking.
                    if UserDefaults.standard.bool(forKey: "testAcceptOffers") { sync.accept() }
                    #endif
                } else if shown {
                    // The offer ended without a tap (no answer in time): the question must not stay on screen.
                    ui.dismissDialog()
                    shown = false
                }
            }
    }
}
