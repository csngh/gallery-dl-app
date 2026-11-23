import Foundation
import Network

/// A copy of the app on the network.
struct SyncDevice: Identifiable {
    let id: String
    var name: String
    var platform: String
    var host: String
    var port: Int
}

/// Another device asking to send this one its links and favorite videos.
struct SyncOfferInfo: Identifiable {
    let id = UUID()
    let from: String
    let links: Int
    let videos: Int
    let bytes: Int64
}

enum SyncStatus {
    /// `file` and `fraction` describe the file in flight right now (fraction 0...1).
    case running(String, done: Int, total: Int, file: String? = nil, fraction: Double? = nil)
    /// `details` lists what was left out and why, one line each.
    case finished(String, details: [String] = [])
    case failed(String)
}

/// The addresses of this device's own networks, as the host part of each: up to 254 around its own.
func localNeighbours() -> [String] {
    var result: [String] = []
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0, let first = list else { return [] }
    defer { freeifaddrs(list) }
    for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let entry = pointer.pointee
        guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET), let netmask = entry.ifa_netmask else { continue }
        let flags = Int32(entry.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
        let name = String(cString: entry.ifa_name)
        guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
        let ip = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
        let mask = netmask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
        let prefix = min(max(mask.nonzeroBitCount, 24), 30)
        let size = (1 << (32 - prefix)) - 1
        let base = ip & ~UInt32((1 << (32 - prefix)) - 1)
        for offset in 1..<size {
            let value = base + UInt32(offset)
            if value == ip { continue }
            result.append("\(value >> 24 & 255).\(value >> 16 & 255).\(value >> 8 & 255).\(value & 255)")
        }
    }
    return result
}

/// What arrives from another device and what this one says about itself, on the manager's behalf.
private final class SyncAdapter: ReceiveListener {
    weak var manager: SyncManager?
    private let info: () -> DeviceInfo
    init(info: @escaping () -> DeviceInfo) { self.info = info }

    func myInfo() -> DeviceInfo { info() }
    func registered(_ device: DeviceInfo, address: String) { Task { @MainActor in manager?.note(device, host: address) } }
    func wanted(_ files: [FileInfo]) async -> [FileInfo] { await manager?.wanted(files) ?? files }
    func offered(from: DeviceInfo, address: String, files: [FileInfo]) async -> Bool { await manager?.ask(from: from, files: files) ?? false }
    func progress(received: Int, total: Int) { Task { @MainActor in manager?.receivedProgress(received, total) } }
    func fileProgress(done: Int, total: Int, name: String, fraction: Double) { Task { @MainActor in manager?.receivedFile(done: done, total: total, name: name, fraction: fraction) } }
    func finished(from: DeviceInfo, dir: URL, files: [FileInfo]) { Task { @MainActor in await manager?.apply(dir: dir, files: files) } }
    func cancelled() { Task { @MainActor in manager?.status = .finished(S.syncStopped, details: []) } }
}

/// Runs device sync while the Sync screen is open, over LocalSend's protocol (see LocalSend.swift): the other copies of the app are
/// found by themselves, a send is offered to the other device's person, who accepts or declines, and nothing is stored before
/// that. There is no pairing.
@MainActor
final class SyncManager: ObservableObject {
    @Published private(set) var devices: [SyncDevice] = []
    @Published var status: SyncStatus?
    @Published var offer: SyncOfferInfo?
    @Published private(set) var problem: String?
    @Published private(set) var port: UInt16 = 0
    @Published var deviceName: String {
        didSet { UserDefaults.standard.set(deviceName, forKey: "syncDeviceName") }
    }

    private let store: AppStore
    private let deviceId: String
    private var adapter: SyncAdapter?
    private var receiver: LocalSendReceiver?
    private var server: HttpServer?
    private var seen: [String: (device: SyncDevice, at: Date)] = [:]
    private var searchTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?
    private var decision: CheckedContinuation<Bool, Never>?
    private var decisionTimer: Task<Void, Never>?
    private var openConnection: SyncConnection?
    private var receivingFrom = ""
    private var running = false
    /// Bumped by every start and every stop. A start that took long to listen (no usable network, a port taken)
    /// puts nothing in place once a newer start has begun or the screen has been closed.
    private var generation = 0

    init(store: AppStore) {
        self.store = store
        let defaults = UserDefaults.standard
        let id = defaults.string(forKey: "syncDeviceId") ?? UUID().uuidString.lowercased()
        defaults.set(id, forKey: "syncDeviceId")
        deviceId = id
        deviceName = defaults.string(forKey: "syncDeviceName") ?? Platform.deviceName
    }

    private func info() -> DeviceInfo { DeviceInfo(alias: deviceName, fingerprint: deviceId, port: Int(port == 0 ? localSendPort : port), deviceType: Platform.deviceType) }
    private var client: LocalSendClient { LocalSendClient(me: { [weak self] in self?.info() ?? DeviceInfo(alias: "iPhone", fingerprint: "", port: Int(localSendPort)) }) }

    // MARK: Starting and stopping

    /// Starts listening, and with `searching` also looking for other devices. The Mac listens from the moment the app opens,
    /// so an offer reaches it on any screen, and searches only while the Sync screen is open; the iPhone does both only
    /// while that screen is open.
    func start(searching: Bool = true) async {
        guard !running else {
            if searching { beginSearching() }
            return
        }
        running = true
        problem = nil
        generation += 1
        let mine = generation
        let adapter = SyncAdapter(info: { [weak self] in self?.info() ?? DeviceInfo(alias: "iPhone", fingerprint: "", port: Int(localSendPort)) })
        adapter.manager = self
        let receiver = LocalSendReceiver(cacheDir: FileManager.default.temporaryDirectory, listener: adapter)
        let server = HttpServer(handler: { await receiver.handle($0) })
        do {
            port = try await server.start()
        } catch {
            // Only the start that is still current may report a problem: a stop, or a newer start, owns the state now.
            if mine == generation {
                problem = S.syncCannotListen
                running = false
            }
            return
        }
        // The screen may have been closed, or the screen opened again, while the server was starting: this start
        // then owns nothing and takes its own server down, instead of leaving it listening and announcing.
        guard running, mine == generation else {
            server.stop()
            receiver.stop()
            // The port is only cleared when nothing owns it: a newer start may have set its own by now.
            if !running { port = 0 }
            return
        }
        self.adapter = adapter
        self.receiver = receiver
        self.server = server
        if searching { beginSearching() }
    }

    private func beginSearching() {
        guard searchTask == nil else { return }
        searchTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.search()
                try? await Task.sleep(nanoseconds: 8_000_000_000)
            }
        }
    }

    /// Stops looking for devices but keeps listening, so offers still arrive.
    func stopSearching() {
        searchTask?.cancel()
        searchTask = nil
        seen = [:]
        devices = []
    }

    func stop() {
        running = false
        generation += 1
        searchTask?.cancel()
        searchTask = nil
        sendTask?.cancel()
        decisionTimer?.cancel()
        resolve(false)
        server?.stop()
        receiver?.stop()
        server = nil
        receiver = nil
        adapter = nil
        seen = [:]
        devices = []
        port = 0
    }

    // MARK: Finding devices

    /// Asks every address of the local network whether a copy of the app answers.
    private func search() async {
        let hosts = localNeighbours()
        let me = info()
        let client = LocalSendClient(me: { me })
        await withTaskGroup(of: (DeviceInfo, String)?.self) { group in
            var next = hosts.makeIterator()
            var running = 0
            func add() {
                guard let host = next.next() else { return }
                running += 1
                group.addTask { await client.register(host: host).map { ($0, host) } }
            }
            for _ in 0..<40 { add() }
            while let result = await group.next() {
                running -= 1
                if let (device, host) = result { await MainActor.run { self.note(device, host: host) } }
                add()
            }
        }
        prune()
    }

    func searchAgain() { Task { await search() } }

    func note(_ info: DeviceInfo, host: String, port: Int? = nil) {
        guard info.isGalleryDl, info.fingerprint != deviceId, !info.fingerprint.isEmpty else { return }
        seen[info.fingerprint] = (SyncDevice(id: info.fingerprint, name: info.alias, platform: info.deviceType, host: host, port: port ?? info.port), Date())
        publish()
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-70)
        seen = seen.filter { $0.value.at >= cutoff }
        publish()
    }

    private func publish() {
        devices = seen.values.map(\.device).sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// Adds a device by its address (for a network that hides devices from each other), as shown on its own Sync screen.
    func add(address text: String) async -> String? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard parts.count >= 1, parts.count <= 2, !parts[0].isEmpty else { return S.syncBadAddress }
        let typed = parts.count == 2 ? Int(parts[1].trimmingCharacters(in: .whitespaces)) : Int(localSendPort)
        guard let port = typed, (1...65535).contains(port) else { return S.syncBadAddress }
        guard let device = await client.register(host: String(parts[0]), port: port, connectTimeout: 3) else { return S.syncCouldNotReach }
        // The port that was typed is the one that answered, whatever port the device says it uses.
        note(device, host: String(parts[0]), port: port)
        return nil
    }

    // MARK: Receiving

    /// Ids of videos this device already has, so they are not uploaded again; the result tells how many.
    private var alreadyHere = Set<String>()

    /// Each video carries its address and name, so what Favorites already has is left out before anything is uploaded.
    func wanted(_ files: [FileInfo]) -> [FileInfo] {
        alreadyHere = []
        var keep: [FileInfo] = []
        for f in files {
            if f.id.hasPrefix("fav-"), let text = f.preview, let key = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
               store.favoriteMatch(parent: key["p"] as? String ?? "", video: key["v"] as? String ?? "", name: key["n"] as? String ?? "") != nil {
                alreadyHere.insert(f.id)
            } else { keep.append(f) }
        }
        let videos = keep.filter { $0.id.hasPrefix("fav-") }.count
        let links = keep.contains { $0.fileName == linksFileName }
        if videos == 0 && !links {
            status = .finished(S.syncAllHere(alreadyHere.count), details: [])
            return []
        }
        return videos == 0 ? keep.filter { $0.fileName != favoritesFileName } : keep
    }

    /// Waits for the person's answer to an offer; no answer in 90 seconds is a no.
    func ask(from: DeviceInfo, files: [FileInfo]) async -> Bool {
        let links = files.first { $0.fileName == linksFileName }?.preview.flatMap { Int($0) } ?? 0
        let videos = files.filter { $0.id.hasPrefix("fav-") }.count
        receivingFrom = from.alias
        offer = SyncOfferInfo(from: from.alias, links: links, videos: videos, bytes: files.reduce(0) { $0 + $1.size })
        let answer = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            decision = continuation
            decisionTimer = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 90_000_000_000)
                if !Task.isCancelled { self?.resolve(false) }
            }
        }
        offer = nil
        return answer
    }

    func accept() { resolve(true) }
    func decline() { resolve(false) }

    private func resolve(_ yes: Bool) {
        decisionTimer?.cancel()
        let pending = decision
        decision = nil
        pending?.resume(returning: yes)
    }

    func receivedFile(done: Int, total: Int, name: String, fraction: Double) {
        status = .running(S.syncReceiving(receivingFrom), done: done, total: total, file: name, fraction: fraction)
    }

    func receivedProgress(_ received: Int, _ total: Int) {
        status = .running(S.syncReceiving(receivingFrom), done: received, total: total)
    }

    func apply(dir: URL, files: [FileInfo]) async {
        defer { try? FileManager.default.removeItem(at: dir) }
        func safe(_ id: String) -> String { id.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? String($0) : "_" }.joined() }
        var linksAdded = 0, favoritesAdded = 0
        var left: [String] = []
        let toSave = files.filter { $0.id.hasPrefix("fav-") }.count
        var savedSoFar = 0
        let byName = Dictionary(files.map { ($0.fileName, $0) }, uniquingKeysWith: { first, _ in first })
        // A manifest is a small JSON file and it is read whole, so one larger than this is not read at all: another
        // device states the size it will send, but it is not trusted, and the file on disk is what is checked.
        let maxManifest = LocalSendReceiver.maxManifestSize
        func manifest(_ file: FileInfo) -> [String: Any]? {
            let url = dir.appendingPathComponent(safe(file.id))
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            guard size > 0, size <= maxManifest,
                  let data = try? Data(contentsOf: url),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            return object
        }
        if let object = byName[linksFileName].flatMap(manifest), let links = object["links"] as? [[String: Any]] {
            for link in links {
                guard let url = link["url"] as? String, isPublicWebLink(url) else { continue }
                if !store.hasTask(url: url) {
                    store.enqueue(url: url, site: (link["site"] as? String).flatMap { $0.isEmpty ? nil : $0 })
                    linksAdded += 1
                }
            }
        }
        if let object = byName[favoritesFileName].flatMap(manifest), let items = object["items"] as? [[String: Any]] {
            let web = { (u: String) in u.isEmpty || self.isPublicWebLink(u) }
            for item in items {
                let name = (item["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "(no name)"
                guard let id = item["id"] as? String, let received = files.first(where: { $0.id == id }) else { if let id = item["id"] as? String, alreadyHere.contains(id) {} else { left.append("\(name): the video did not arrive") }; continue }
                status = .running(S.syncSaving, done: savedSoFar, total: toSave, file: name, fraction: nil)
                savedSoFar += 1
                let file = dir.appendingPathComponent(safe(received.id))
                let parent = item["parent"] as? String ?? "", video = item["video"] as? String ?? ""
                let mime = (item["mime"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? received.fileType
                let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
                if size <= 0 { left.append("\(name): the file arrived empty"); continue }
                if !web(parent) || !web(video) { left.append("\(name): its saved address is not a web address, so it was not kept"); continue }
                // A video Favorites already has is left alone.
                if let same = store.favoriteMatch(parent: parent, video: video, name: name) { left.append("\(name): already in Favorites (\(same))"); continue }
                if store.saveFavorite(file: file, name: name, parent: parent, video: video, mime: mime) { favoritesAdded += 1 }
                else { left.append("\(name): could not be saved on this device (is the storage full?)") }
            }
        }
        let here = alreadyHere.count
        let head = here > 0 && linksAdded + favoritesAdded > 0 ? S.syncReceived(links: linksAdded, favorites: favoritesAdded) + " " + S.syncAlreadyHere(here)
            : here > 0 && left.isEmpty ? S.syncAllHere(here) : S.syncReceived(links: linksAdded, favorites: favoritesAdded)
        status = .finished(head + (left.isEmpty ? "" : " " + S.syncLeftOut(left.count, sent: false)), details: left)
    }

    /// Whether an address from another device may reach Home or Favorites. It still has to be a public web address:
    /// a device on this network must not make the app fetch the network's own services (a router's page, a machine).
    /// A name that only resolves to a private address (a wildcard DNS name, for one) is not caught here.
    private func isPublicWebLink(_ text: String) -> Bool {
        guard let url = URL(string: text), var host = url.host?.lowercased(),
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return false }
        if host.hasPrefix("[") { return false }                         // a bare IPv6 address, which may be this network's
        if host.hasSuffix(".") { host.removeLast() }
        if host == "localhost" || !host.contains(".") { return false }  // a machine on this network, named or bare
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        // A last part made of numbers means the system reads the whole host as an address, and it has spellings of its own
        // ("127.1", "0x7f.0.0.1", "0177.0.0.1" are all this machine). Only the plain four-part form is understood here, so
        // every other spelling is refused.
        guard let last = labels.last, Self.isNumberLabel(last) else { return true }
        guard labels.count == 4 else { return false }
        var numbers: [Int] = []
        for label in labels {
            guard label.allSatisfy({ $0.isASCII && $0.isNumber }), label.count == 1 || !label.hasPrefix("0"),
                  let value = Int(label), value <= 255 else { return false }
            numbers.append(value)
        }
        let (a, b) = (numbers[0], numbers[1])
        let local = a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 192 && b == 168) || (a == 172 && (16...31).contains(b))
        return !local
    }

    /// Whether a part of a host is a number the way an address is written: decimal digits, or 0x and hex digits.
    private static func isNumberLabel(_ label: Substring) -> Bool {
        if label.isEmpty { return false }
        if label.allSatisfy({ $0.isASCII && $0.isNumber }) { return true }
        let lower = label.lowercased()
        return lower.hasPrefix("0x") && lower.count > 2 && lower.dropFirst(2).allSatisfy { $0.isHexDigit }
    }

    /// The address and name a receiver uses to tell whether it already has this video, before it is uploaded.
    private func favoriteKey(_ f: SyncFavorite) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: ["p": f.parent, "v": f.video, "n": f.name])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: Sending

    /// Offers this device's links and favorite videos to `device`; its person decides.
    func send(to device: SyncDevice, links withLinks: Bool = true, favorites withFavorites: Bool = true) {
        if case .running? = status { return }
        status = .running(S.syncSending(device.name), done: 0, total: 0)
        sendTask = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.sendNow(device, withLinks: withLinks, withFavorites: withFavorites)
                self.status = .finished(result.text, details: result.details)
            } catch {
                if Task.isCancelled { self.status = .finished(S.syncStopped, details: []) }
                else {
                    let timedOut = error.localizedDescription.hasPrefix("timed out") || (error as? SyncError)?.message.hasPrefix("timed out") == true
                    self.status = .failed(S.syncCouldNotReach + " (\(error.localizedDescription))" + (timedOut ? " " + S.syncTimeoutHint : ""))
                }
            }
        }
    }

    /// What this device has to offer, and which favorites cannot be sent and why.
    func counts() -> (links: Int, favorites: Int, bytes: Int64, leftOut: [String]) {
        let plan = store.syncFavoritePlan()
        return (store.syncLinks().count, plan.send.count, plan.send.reduce(0) { $0 + $1.size }, plan.leftOut)
    }

    private func sendNow(_ device: SyncDevice, withLinks: Bool, withFavorites: Bool) async throws -> (text: String, details: [String]) {
        let links = withLinks ? store.syncLinks() : []
        let plan = withFavorites ? store.syncFavoritePlan() : (send: [], leftOut: [])
        let favorites = plan.send
        if links.isEmpty && favorites.isEmpty { return (S.syncNothingToSend, plan.leftOut) }
        func memory(_ data: Data) -> () throws -> (read: (Int) throws -> Data, close: () -> Void) {
            { var done = false; return ({ _ in if done { return Data() }; done = true; return data }, {}) }
        }
        var outgoing: [OutgoingFile] = []
        if !links.isEmpty {
            let data = linksJSON(links)
            outgoing.append(OutgoingFile(info: FileInfo(id: "links", fileName: linksFileName, size: Int64(data.count), fileType: "application/json", preview: String(links.count)), open: memory(data)))
        }
        if !favorites.isEmpty {
            let data = favoritesJSON(favorites.enumerated().map { (id: "fav-\($0.offset)", favorite: $0.element) })
            outgoing.append(OutgoingFile(info: FileInfo(id: "favorites", fileName: favoritesFileName, size: Int64(data.count), fileType: "application/json", preview: String(favorites.count)), open: memory(data)))
            // Each video goes as a file of its own, named as the manifest says.
            for (index, favorite) in favorites.enumerated() {
                guard let item = store.favoriteItem(id: favorite.id) else { continue }
                let url = item.fileURL
                outgoing.append(OutgoingFile(info: FileInfo(id: "fav-\(index)", fileName: favorite.name, size: favorite.size, fileType: favorite.mime, preview: favoriteKey(favorite)), open: {
                    let handle = try FileHandle(forReadingFrom: url)
                    return ({ n in try handle.read(upToCount: n) ?? Data() }, { try? handle.close() })
                }))
            }
        }
        let name = device.name
        let outcome = try await client.send(
            host: device.host, port: device.port, files: outgoing,
            progress: { done, total in Task { @MainActor in self.status = .running(S.syncSendingTo(name), done: done, total: total) } },
            fileProgress: { done, total, file, fraction in Task { @MainActor in self.status = .running(S.syncSendingTo(name), done: done, total: total, file: file, fraction: fraction) } },
            cancelled: { Task.isCancelled },
            connection: { connection in Task { @MainActor in self.openConnection = connection } },
        )
        switch outcome {
        case .sent: return (S.syncSent(name) + (plan.leftOut.isEmpty ? "" : " " + S.syncLeftOut(plan.leftOut.count, sent: true)), plan.leftOut)
        case .declined: return (S.syncDeclined(name), [])
        case .busy: return (S.syncBusy(name), [])
        case .nothing: return (S.syncOtherHasAll(name), plan.leftOut)
        }
    }

    func cancel() {
        sendTask?.cancel()
        // A transfer waiting on the network would not notice; closing the connection ends it.
        openConnection?.close()
    }

    func dismissStatus() { if case .running = status { return }; status = nil }
}
