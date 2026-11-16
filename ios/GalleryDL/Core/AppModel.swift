import SwiftUI

/// Builds and owns everything the screens share (the counterpart of Android's AppContainer).
@MainActor
final class AppModel: ObservableObject {
    let store = AppStore()
    let settings = SettingsStore()
    let network = NetworkMonitor()
    let validator = UrlValidator()
    let storage = StorageMonitor()
    let engine: DownloadEngine
    let sync: SyncManager

    @Published private(set) var pythonReady = false
    @Published private(set) var galleryDlVersion = ""
    @Published var transfer: Transfer?
    @Published var favoritesTransfer: FavoritesTransfer?
    /// The video being fetched right now by the favorites import, with how much of it has arrived.
    @Published var favoritesStep: FavoriteStep?

    init() {
        engine = DownloadEngine(store: store, settings: settings, network: network, storage: storage)
        sync = SyncManager(store: store)
    }

    // MARK: Export and import of links

    private static let maxListBytes = 5 * 1024 * 1024

    /// Writes every link to a text file in the temporary folder and returns it, or nil when there are none.
    func exportFile() -> (url: URL, count: Int)? {
        let urls = store.tasks.sorted { $0.id < $1.id }.map(\.url)
        let text = LinkList.format(urls)
        let count = text.split(separator: "\n").count
        guard count > 0 else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(S.exportFileName)
        guard (try? text.write(to: url, atomically: true, encoding: .utf8)) != nil else { transfer = .failed; return nil }
        return (url, count)
    }

    private var favoritesTask: Task<Void, Never>?

    /// The videos of a favorites sheet (saved by Export video links), read as soon as the file is chosen. Nothing is imported
    /// yet: the person is asked first. Empty when the file cannot be read or holds no videos.
    func readFavoritesSheet(from url: URL) -> [FavoriteRow] {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), data.count <= Self.maxListBytes,
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return [] }
        return LinkList.parseFavoriteVideos(text)
    }

    /// Adds the videos to Favorites in the background: ones Favorites already has are ignored, ones the library has are
    /// copied, the rest are fetched. Progress shows on Library and Home, and `cancelFavoritesImport` stops it.
    func startFavoritesImport(_ rows: [FavoriteRow], whenEnded: @escaping (_ completed: Bool) -> Void = { _ in }) {
        if case .importing? = favoritesTransfer { return }
        favoritesTransfer = .importing(done: 0, total: rows.count, stopping: false)
        let options = settings.options.toJson(cookiesPath: settings.cookiesPath)
        // The current address of files whose saved one no longer works, asked of the link they came from.
        let resolve: (String, [String]) async -> [String: String] = { parent, names in
            let reply = try? await PythonRuntime.shared.call("resolve", ["url": parent, "names": names, "options": options])
            return reply?["files"] as? [String: String] ?? [:]
        }
        favoritesTask = Task { [weak self] in
            guard let self else { return }
            let result = await store.importFavoriteVideos(rows, resolve: resolve, progress: { [weak self] done in
                self?.favoritesTransfer = .importing(done: done, total: rows.count, stopping: Task.isCancelled)
            }, step: { [weak self] step in
                Task { @MainActor in self?.favoritesStep = step }
            })
            favoritesStep = nil
            favoritesTransfer = .imported(result, stopped: Task.isCancelled)
            favoritesTask = nil
            whenEnded(!Task.isCancelled)
        }
    }

    /// Ends the import after the video being fetched right now; what was already added stays.
    func cancelFavoritesImport() {
        guard case .importing(let done, let total, _)? = favoritesTransfer else { return }
        favoritesTransfer = .importing(done: done, total: total, stopping: true)
        favoritesTask?.cancel()
        // A walk through a big link would otherwise keep going until it ended on its own.
        Task { _ = try? await PythonRuntime.shared.call("cancel_resolve", [:]) }
    }

    /// Hides the result once it has been read.
    func dismissFavoritesTransfer() {
        if case .importing? = favoritesTransfer { return }
        favoritesTransfer = nil
    }

    /// Adds the links of a text file as "ready to start" links. Links already in the list and links on websites that
    /// gallery-dl does not support are skipped, and the result says how many of each.
    func importLinks(from url: URL) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), data.count <= Self.maxListBytes,
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            transfer = .failed
            return
        }
        let lines = LinkList.parse(text)
        var result = ImportResult()
        var known = Set(store.tasks.map(\.url))
        transfer = .importing(done: 0, total: lines.count)
        for (index, line) in lines.enumerated() {
            switch line {
            case .notALink:
                result.notLinks += 1
            case .link(let link):
                if !known.insert(link).inserted {
                    result.duplicates += 1
                } else {
                    let check = await validator.check(link)
                    if check.check == .valid {
                        store.enqueue(url: link, site: check.site)
                        result.added += 1
                    } else {
                        result.unsupported += 1
                    }
                }
            }
            transfer = .importing(done: index + 1, total: lines.count)
        }
        transfer = .imported(result)
    }

    /// Starts the embedded Python off the main thread, so the intro animation keeps running.
    func boot() async {
        guard !pythonReady else { return }
        let started: Bool = await Task.detached { (try? PythonRuntime.shared.start()) != nil }.value
        guard started else { return }
        let info = try? await PythonRuntime.shared.call("info")
        galleryDlVersion = info?["gallery_dl"] as? String ?? ""
        pythonReady = true
        store.pruneMissing()
        engine.resumeOnLaunch()
    }

    func waitUntilReady() async {
        while !pythonReady { try? await Task.sleep(nanoseconds: 50_000_000) }
    }

    func sceneChanged(_ phase: ScenePhase) {
        switch phase {
        case .active: engine.sceneBecameActive(); store.pruneMissing()
        case .inactive: store.flush()
        case .background:
            store.flush()
            engine.sceneEnteredBackground()
        default: break
        }
    }
}
