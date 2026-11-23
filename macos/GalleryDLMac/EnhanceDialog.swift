import AVKit
import SwiftUI

/// Runs the enhancement of one video and holds what it makes until the person decides what to do with it.
@MainActor
final class EnhanceModel: ObservableObject {
    enum Stage {
        case checking
        case choosing(EnhanceSource)
        case refused(String)
        case working(String, Double?)
        case ready(EnhanceResult)
        case failed(String)
    }

    @Published private(set) var stage = Stage.checking
    @Published private(set) var source: EnhanceSource?
    @Published private(set) var original: CGImage?
    @Published private(set) var enhanced: CGImage?
    /// The moment (in seconds) that the compared pictures show.
    @Published private(set) var time: Double = 0
    /// The color numbers of the two pictures shown in the comparison.
    @Published private(set) var beforeStats: ColorStats?
    @Published private(set) var afterStats: ColorStats?

    let item: LibraryItem
    private var task: Task<Void, Never>?
    private var result: EnhanceResult?
    private var stillTask: Task<Void, Never>?
    /// The upscaler loading (or loaded) while the person is still choosing. Held as `Any` because it only exists on macOS 26.
    private var warmTask: Task<Any?, Never>?

    init(item: LibraryItem) { self.item = item }

    /// Looks at the video and offers the choice, or says why it cannot be enhanced.
    func start() {
        guard task == nil, case .checking = stage else { return }
        guard #available(macOS 26.0, *) else { stage = .refused("Enhancing needs macOS 26 or newer."); return }
        task = Task {
            do {
                let source = try await VideoEnhancer.inspect(item)
                self.source = source
                if let problem = VideoEnhancer.problem(with: source) { stage = .refused(problem); return }
                stage = .choosing(source)
                task = nil
                // The upscaler takes several seconds to load its model: that starts now, while the person decides.
                if #available(macOS 26.0, *), warmTask == nil {
                    warmTask = Task { await VideoEnhancer.warmUp(source) as Any? }
                }
                #if DEBUG
                // `-testEnhanceQuality go` starts by itself.
                if UserDefaults.standard.string(forKey: "testEnhanceQuality") != nil { begin() }
                #endif
            } catch {
                stage = .failed(error.localizedDescription)
                task = nil
            }
        }
    }

    func begin() {
        guard task == nil, let source else { return }
        guard #available(macOS 26.0, *) else { return }
        task = Task {
            do {
                stage = .working("Getting ready…", nil)
                // The upscaler that was loaded while the person was choosing.
                let warm = await warmTask?.value as? WarmUpscaler
                warmTask = nil
                let made = try await VideoEnhancer.enhance(item, source: source, warm: warm) { [weak self] progress in
                    Task { @MainActor in self?.update(progress) }
                }
                if Task.isCancelled { try? FileManager.default.removeItem(at: made.url.deletingLastPathComponent()); return }
                result = made
                // One picture a little way in, before and after, to compare close up. The "after" is read from the finished
                // file, so it shows exactly what was made.
                var first = min(1.0, source.duration / 2)
                #if DEBUG
                if let text = UserDefaults.standard.string(forKey: "testEnhanceSeek"), let at = Double(text) { first = at }
                #endif
                time = first
                async let before = VideoEnhancer.still(of: item.fileURL, isWebM: item.isWebM, at: first)
                async let after = VideoEnhancer.still(of: made.url, isWebM: false, at: first)
                (original, enhanced) = await (before, after)
                refreshColors()
                stage = .ready(made)
            } catch is CancellationError {
                // Closed by the person: nothing to report.
            } catch {
                stage = .failed(error.localizedDescription)
            }
        }
    }

    private func update(_ progress: EnhanceProgress) {
        guard case .working = stage else { return }
        switch progress {
        case .downloadingModel(let fraction):
            stage = .working("Downloading Apple's enhancement model, once (\(Int(fraction * 100))%)…", fraction)
        case .starting:
            stage = .working("Getting ready…", nil)
        case .frames(let done, let total, let left):
            var text = "Enhancing frame \(done) of \(total)"
            if let left, left >= 1 { text += left >= 90 ? " · about \(Int((left / 60).rounded())) min left" : " · about \(Int(left.rounded())) s left" }
            stage = .working(text, Double(done) / Double(max(1, total)))
        }
    }

    /// Measures the two pictures shown.
    private func refreshColors() {
        beforeStats = original.flatMap { ColorStats.measure($0) }
        afterStats = enhanced.flatMap { ColorStats.measure($0) }
    }

    /// Shows the same moment of both videos. Called as the seek bar moves, so a newer request replaces one still being read.
    func seek(to seconds: Double) {
        time = seconds
        stillTask?.cancel()
        guard #available(macOS 26.0, *), let url = result?.url else { return }
        let item = item
        stillTask = Task {
            try? await Task.sleep(nanoseconds: 60_000_000)
            guard !Task.isCancelled else { return }
            async let before = VideoEnhancer.still(of: item.fileURL, isWebM: item.isWebM, at: seconds)
            async let after = VideoEnhancer.still(of: url, isWebM: false, at: seconds)
            let (o, e) = await (before, after)
            guard !Task.isCancelled else { return }
            if let o { original = o }
            if let e { enhanced = e }
            refreshColors()
        }
    }

    /// Stops the work and removes what it made. Safe to call more than once.
    func discard() {
        task?.cancel()
        // An upscaler loaded in advance that was never used is let go, so its memory is returned.
        if let waiting = warmTask {
            warmTask = nil
            Task { if #available(macOS 26.0, *), let loaded = await waiting.value as? WarmUpscaler { loaded.end() } }
        }
        stillTask?.cancel()
        if let result { try? FileManager.default.removeItem(at: result.url.deletingLastPathComponent()) }
        result = nil
    }
}

struct EnhanceDialog: View {
    @EnvironmentObject private var ui: UIState
    @EnvironmentObject private var store: AppStore
    @StateObject private var model: EnhanceModel
    /// Whether the side panel with the color charts is shown.
    #if DEBUG
    @State private var showCharts = UserDefaults.standard.bool(forKey: "testEnhanceCharts")
    #else
    @State private var showCharts = false
    #endif
    /// How far the compared pictures are zoomed, and how far they have been moved; both pictures always show the same spot.
    @State private var zoom: CGFloat = 2.6
    @State private var pan = CGSize.zero
    @State private var panStart: CGSize?
    @State private var zoomStart: CGFloat?
    private static let defaultZoom: CGFloat = 2.6

    init(item: LibraryItem) { _model = StateObject(wrappedValue: EnhanceModel(item: item)) }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.md) {
            Text("Enhance video").textStyle(.headlineSmall)
            Text(model.item.displayName).textStyle(.bodyMedium, color: Brand.onSurfaceVariant).lineLimit(1)
            content
        }
        .padding(Spacing.lg)
        .frame(maxWidth: dialogWidth)
        .background(RoundedRectangle(cornerRadius: Radius.xl, style: .continuous).fill(Brand.surfaceContainerHigh))
        .padding(Spacing.lg)
        .onAppear { model.start() }
        .onDisappear { model.discard() }
    }

    private var dialogWidth: CGFloat { showCharts ? 1180 : 820 }

    @ViewBuilder private var content: some View {
        switch model.stage {
        case .checking:
            status("Looking at the video…", nil) { cancelButton }
        case .choosing(let source):
            choices(source)
        case .refused(let message):
            Text(message).textStyle(.bodyLarge)
            HStack { Spacer(); TextPill(title: S.actionClose, color: Brand.onSurfaceVariant) { ui.dismissDialog() } }
        case .working(let text, let fraction):
            status(text, fraction) { cancelButton }
            Text("Your original is not changed. You will see the result before anything is saved.")
                .textStyle(.bodySmall, color: Brand.onSurfaceVariant)
        case .failed(let message):
            Text(message).textStyle(.bodyLarge, color: Brand.error)
            HStack { Spacer(); TextPill(title: S.actionClose, color: Brand.onSurfaceVariant) { ui.dismissDialog() } }
        case .ready(let result):
            ready(result)
        }
    }

    private func choices(_ source: EnhanceSource) -> some View {
        let rate = String(format: "%g", (source.fps * 10).rounded() / 10)
        return VStack(alignment: .leading, spacing: Spacing.md) {
            Text("\(source.width)×\(source.height) · \(Int(source.duration.rounded())) seconds · \(source.frames) frames · \(rate) fps")
                .textStyle(.bodyMedium, color: Brand.onSurfaceVariant)
            choice(source)
            Text("The result is up to 1920×1080 in high-bitrate HEVC. Frame rate, timing, colors and HDR stay as they are, and nothing is sharpened or smoothed on top. Your original is not changed, and you will see the result before anything is saved.")
                .textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            HStack { Spacer(); TextPill(title: S.actionCancel, color: Brand.onSurfaceVariant) { ui.dismissDialog() } }
        }
    }

    @available(macOS 26.0, *)
    private func timeText(_ source: EnhanceSource) -> String {
        let seconds = VideoEnhancer.estimateSeconds(source)
        return seconds >= 90 ? "about \(Int((seconds / 60).rounded())) min" : "about \(Int(seconds.rounded())) s"
    }

    private func choice(_ source: EnhanceSource) -> some View {
        Button { model.begin() } label: {
            HStack(spacing: Spacing.md) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Enhance").textStyle(.titleMedium)
                    Text("Best quality: the upscaler sees every detail of the video.").textStyle(.bodySmall, color: Brand.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if #available(macOS 26.0, *) { Text(timeText(source)).textStyle(.labelLarge, color: Brand.primary) }
            }
            .padding(Spacing.md)
            .background(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).fill(Brand.surfaceContainerHighest))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var cancelButton: some View {
        HStack { Spacer(); TextPill(title: S.actionCancel, color: Brand.onSurfaceVariant) { ui.dismissDialog() } }
    }

    private func status<Actions: View>(_ text: String, _ fraction: Double?, @ViewBuilder actions: () -> Actions) -> some View {
        VStack(alignment: .leading, spacing: Spacing.md) {
            Text(text).textStyle(.bodyLarge)
            if let fraction { ProgressView(value: fraction).tint(Brand.primary) } else { ProgressView().controlSize(.small) }
            actions()
        }
    }

    private func ready(_ result: EnhanceResult) -> some View {
        VStack(alignment: .leading, spacing: Spacing.md) {
            HStack(spacing: Spacing.sm) {
                Toggle("Show color charts", isOn: $showCharts).toggleStyle(.switch).controlSize(.small).tint(Brand.primary)
                Text("Drag to move the picture").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
                Spacer(minLength: 0)
                Text("Zoom").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
                Slider(value: $zoom, in: 1...6).tint(Brand.primary).frame(width: 200)
                    .onChange(of: zoom) { _, _ in pan = clamped(pan, in: lastPanelSize) }
                Text(String(format: "%.1f×", Double(zoom))).textStyle(.labelLarge).frame(width: 44, alignment: .trailing)
                TextPill(title: "Reset") { zoom = Self.defaultZoom; pan = .zero }
            }

            HStack(alignment: .top, spacing: Spacing.sm) {
                comparison
                    .frame(height: 400)
                    .background(Color.black)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                if showCharts {
                    ColorChartsPanel(before: model.beforeStats, after: model.afterStats)
                        .frame(width: 300, height: 400)
                }
            }

            // The seek bar: both pictures always show the same moment of the original and the enhanced video.
            HStack(spacing: Spacing.sm) {
                Text(clock(model.time)).textStyle(.labelLarge).frame(width: 44, alignment: .leading)
                Slider(value: Binding(get: { model.time }, set: { model.seek(to: $0) }), in: 0...max(0.1, (model.source?.duration ?? 1) - 0.05))
                    .tint(Brand.primary)
                Text(clock(model.source?.duration ?? 0)).textStyle(.labelLarge, color: Brand.onSurfaceVariant).frame(width: 44, alignment: .trailing)
            }

            if let source = model.source {
                Text("\(source.width)×\(source.height)  →  \(result.width)×\(result.height) · \(sizeText(result.bytes)) (was \(sizeText(fileBytes(model.item.fileURL))))")
                    .textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            // (Debug only: choose by itself, for checking the saving without anyone clicking.)
            Color.clear.frame(height: 0).onAppear {
                #if DEBUG
                if let action = UserDefaults.standard.string(forKey: "testEnhanceAction") { save(result, replacing: action == "replace") }
                #endif
            }
            HStack(spacing: Spacing.sm) {
                Text("Save a copy goes to the \(AppStore.enhancedFolder) folder. Replace deletes the original.").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
                Spacer(minLength: 0)
                TextPill(title: "Discard", color: Brand.onSurfaceVariant) { ui.dismissDialog() }
                OutlinedPill(title: "Save a copy") { save(result, replacing: false) }
                FilledPill(title: "Replace original", container: ActionColors.negative, content: ActionColors.onNegative) { save(result, replacing: true) }
            }
        }
    }

    private func clock(_ seconds: Double) -> String { String(format: "%d:%04.1f", Int(seconds) / 60, seconds.truncatingRemainder(dividingBy: 60)) }

    /// The size of one compared picture, kept so the zoom slider can keep the pictures inside their frames.
    @State private var lastPanelSize = CGSize(width: 380, height: 400)

    /// The same moment before and after, blown up and movable, so the difference in detail is easy to see.
    private var comparison: some View {
        GeometryReader { geometry in
            let size = CGSize(width: (geometry.size.width - 2) / 2, height: geometry.size.height)
            ZStack {
                HStack(spacing: 2) {
                    panel("Original", model.original, size)
                    panel("Enhanced", model.enhanced, size)
                }
                // The only thing in here that takes the mouse. The pictures are larger than their frames when zoomed, and a
                // picture that is clipped still catches clicks outside its frame, which stopped the tabs and the buttons from
                // working; they take no mouse input at all.
                Color.clear
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                if panStart == nil { panStart = pan }
                                let start = panStart ?? .zero
                                pan = clamped(CGSize(width: start.width + value.translation.width, height: start.height + value.translation.height), in: size)
                            }
                            .onEnded { _ in panStart = nil }
                    )
                    .simultaneousGesture(
                        MagnifyGesture()
                            .onChanged { value in
                                if zoomStart == nil { zoomStart = zoom }
                                zoom = min(max((zoomStart ?? zoom) * value.magnification, 1), 6)
                                pan = clamped(pan, in: size)
                            }
                            .onEnded { _ in zoomStart = nil }
                    )
                    .onTapGesture(count: 2) { zoom = Self.defaultZoom; pan = .zero }
            }
            .onAppear { lastPanelSize = size }
            .onChange(of: geometry.size) { _, _ in lastPanelSize = size }
        }
    }

    private func panel(_ title: String, _ image: CGImage?, _ size: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            if let image {
                Image(decorative: image, scale: 1).resizable().interpolation(.high).scaledToFit()
                    .frame(width: size.width, height: size.height)
                    .scaleEffect(zoom)
                    .offset(pan)
                    .allowsHitTesting(false)
            }
            Text(title).textStyle(.labelLarge, color: .white)
                .padding(.horizontal, Spacing.sm).padding(.vertical, 4)
                .background(Capsule().fill(Color.black.opacity(0.6)))
                .padding(Spacing.sm)
                .allowsHitTesting(false)
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .allowsHitTesting(false)
    }

    /// Keeps the picture from being moved off its frame: it can only travel as far as the zoom has made it larger.
    private func clamped(_ offset: CGSize, in panel: CGSize) -> CGSize {
        guard let image = model.enhanced ?? model.original, image.width > 0, image.height > 0 else { return .zero }
        let fit = min(panel.width / CGFloat(image.width), panel.height / CGFloat(image.height))
        let shown = CGSize(width: CGFloat(image.width) * fit * zoom, height: CGFloat(image.height) * fit * zoom)
        let maxX = max(0, (shown.width - panel.width) / 2), maxY = max(0, (shown.height - panel.height) / 2)
        return CGSize(width: min(max(offset.width, -maxX), maxX), height: min(max(offset.height, -maxY), maxY))
    }

    private func save(_ result: EnhanceResult, replacing: Bool) {
        let saved = store.addEnhanced(result.url, of: model.item, replacing: replacing)
        ui.dismissDialog()
        if saved {
            // The viewer was showing the file that is now gone (or that has a sibling), so it closes.
            if replacing { ui.viewer = nil }
            ui.present(EnhanceNotice(message: replacing ? "The enhanced video replaced the original, in the same folder." : "Saved the enhanced copy in the \(AppStore.enhancedFolder) folder."))
        } else {
            ui.present(EnhanceNotice(message: "The enhanced video could not be saved. The original was not changed."))
        }
    }

    private func fileBytes(_ url: URL) -> Int64 { (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0 }
    private func sizeText(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }
}

/// A short word after saving, under the feature's own name.
struct EnhanceNotice: View {
    @EnvironmentObject private var ui: UIState
    let message: String

    var body: some View {
        DialogCard(title: "Enhance video") {
            Text(message).textStyle(.bodyLarge)
        } actions: {
            FilledPill(title: S.actionOk) { ui.dismissDialog() }
        }
    }
}
