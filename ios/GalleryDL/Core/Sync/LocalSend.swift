import Foundation
import Network

// Device sync speaks LocalSend's protocol (v2, plain HTTP, https://github.com/localsend/protocol), the same one as the Android
// side (app/src/main/java/app/gallerydl/sync/LocalSend.kt): devices find each other by themselves, a sender offers files with
// POST /prepare-upload, the receiver's person says yes or no, and only then does the sender POST each file to /upload. There is
// no pairing: nothing is taken in without a tap. Gallery DL's own copies are told apart by deviceModel "Gallery DL".

/// Gallery DL's own port, next to LocalSend's 53317: the LocalSend app, when it is installed on the same device, keeps its port, and
/// the two do not see each other. The protocol is the same.
let localSendPort: UInt16 = 53318
let appModel = "Gallery DL"
let linksFileName = "gallery-dl-links.json"
let favoritesFileName = "gallery-dl-favorites.json"
private let api = "/api/localsend/v2"
private let maxHead = 64 * 1024
private let maxJSON = 16 * 1024 * 1024
private let sessionIdleSeconds: TimeInterval = 60

struct SyncError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct SyncLink: Equatable { let url: String, site: String, folder: String }
struct SyncFavorite: Equatable { let id: String, parent: String, video: String, name: String, mime: String, size: Int64 }

/// A device as LocalSend describes it.
struct DeviceInfo {
    var alias: String
    var fingerprint: String
    var port: Int
    var deviceType = "mobile"
    var deviceModel: String? = appModel
    var version = "2.0"
    var proto = "http"

    var isGalleryDl: Bool { deviceModel == appModel }

    func json() -> [String: Any] {
        ["alias": alias, "version": version, "deviceModel": deviceModel ?? NSNull(), "deviceType": deviceType,
         "fingerprint": fingerprint, "port": port, "protocol": proto, "download": false]
    }

    init(alias: String, fingerprint: String, port: Int, deviceType: String = "mobile", deviceModel: String? = appModel) {
        self.alias = alias; self.fingerprint = fingerprint; self.port = port; self.deviceType = deviceType; self.deviceModel = deviceModel
    }

    init(json: [String: Any], defaultPort: Int = Int(localSendPort)) {
        alias = json["alias"] as? String ?? "Device"
        fingerprint = json["fingerprint"] as? String ?? ""
        port = json["port"] as? Int ?? defaultPort
        deviceType = json["deviceType"] as? String ?? "desktop"
        deviceModel = json["deviceModel"] as? String
        version = json["version"] as? String ?? "2.0"
        proto = json["protocol"] as? String ?? "http"
    }
}

/// One file of an offer. `preview` carries a short note (for the links file, how many links it holds).
struct FileInfo {
    let id: String, fileName: String, size: Int64, fileType: String, preview: String?
    func json() -> [String: Any] {
        ["id": id, "fileName": fileName, "size": size, "fileType": fileType, "sha256": NSNull(), "preview": preview ?? NSNull(), "metadata": NSNull()]
    }
}

/// A file to send: its description and a way to read it (`read` returns an empty Data at the end).
struct OutgoingFile {
    let info: FileInfo
    let open: () throws -> (read: (Int) throws -> Data, close: () -> Void)
}

// MARK: Connection

/// One TCP connection, read and written in pieces. One that goes quiet for longer than `idleLimit` is closed.
final class SyncConnection {
    let connection: NWConnection
    private let queue = DispatchQueue(label: "sync.connection")
    private var lastActive = Date()
    private var closed = false
    var idleLimit: TimeInterval = 150

    init(_ connection: NWConnection) { self.connection = connection }

    /// Starts the connection and returns when it is ready. "Waiting" is not a failure: it is also what a connection does while
    /// the person has not yet answered the question about the local network, so it is given `timeout` seconds.
    func start(timeout: TimeInterval = 20) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var resumed = false
            var lastWait: Error?
            func finish(_ result: Result<Void, Error>) {
                guard !resumed else { return }
                resumed = true
                continuation.resume(with: result)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(.success(()))
                case .failed(let error): finish(.failure(error))
                case .cancelled: finish(.failure(SyncError("connection closed")))
                case .waiting(let error): lastWait = error
                default: break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [self] in
                guard !resumed else { return }
                connection.cancel()
                finish(.failure(SyncError(lastWait.map { "timed out: \($0.localizedDescription)" } ?? "timed out")))
            }
        }
        watch()
    }

    private func watch() {
        queue.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, !closed else { return }
            if Date().timeIntervalSince(lastActive) > idleLimit { close() } else { watch() }
        }
    }

    func close() {
        closed = true
        connection.cancel()
    }

    /// 1 to `max` bytes, or an empty Data when the other side has closed.
    func readSome(_ max: Int) async throws -> Data {
        let data: Data = try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: max) { data, _, isComplete, error in
                if let error { continuation.resume(throwing: error) }
                else if let data, !data.isEmpty { continuation.resume(returning: data) }
                else if isComplete { continuation.resume(returning: Data()) }
                else { continuation.resume(returning: Data()) }
            }
        }
        lastActive = Date()
        return data
    }

    func write(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
        lastActive = Date()
    }

    /// The address the other side connected from, without a scope, when it is known.
    var remoteAddress: String {
        let endpoint = connection.currentPath?.remoteEndpoint ?? connection.endpoint
        guard case .hostPort(let host, _) = endpoint else { return "" }
        var text = "\(host)".split(separator: "%").first.map(String.init) ?? ""
        // A device reached over IPv4 can show up as an IPv6 address that wraps its IPv4 one.
        if text.hasPrefix("::ffff:") { text.removeFirst("::ffff:".count) }
        return text
    }
}

// MARK: HTTP

/// Reads lines and bytes from a connection, keeping what was read past the end of what was asked for.
final class HttpReader {
    let connection: SyncConnection
    private var buffer = Data()

    init(_ connection: SyncConnection) { self.connection = connection }

    private func fill() async throws -> Bool {
        let more = try await connection.readSome(64 * 1024)
        buffer.append(more)
        return !more.isEmpty
    }

    /// One line without its line break; nil at the end of the connection before any byte of it.
    func readLine(limit: Int = 8192) async throws -> String? {
        while true {
            if let end = buffer.firstIndex(of: 10) {
                var line = buffer[buffer.startIndex..<end]
                buffer.removeSubrange(buffer.startIndex...end)
                if line.last == 13 { line = line.dropLast() }
                return String(decoding: line, as: UTF8.self)
            }
            if buffer.count > limit { throw SyncError("line too long") }
            if !(try await fill()) {
                if buffer.isEmpty { return nil }
                throw SyncError("connection closed")
            }
        }
    }

    /// 1 to `max` bytes, or an empty Data at the end of the connection.
    func readSome(_ max: Int) async throws -> Data {
        if buffer.isEmpty { _ = try await fill() }
        let part = buffer.prefix(max)
        buffer.removeFirst(part.count)
        return Data(part)
    }

    func readHeaders() async throws -> [String: String] {
        var headers: [String: String] = [:]
        var size = 0
        while true {
            guard let line = try await readLine() else { throw SyncError("connection closed") }
            if line.isEmpty { return headers }
            size += line.count
            if size > maxHead { throw SyncError("headers too large") }
            if let colon = line.firstIndex(of: ":") {
                headers[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
    }
}

/// The body of a request or an answer: a number of bytes ("Content-Length"), pieces ("chunked"), or until the end.
final class HttpBody {
    private let reader: HttpReader
    private var left: Int64
    private let chunked: Bool
    private let untilEnd: Bool
    private var finished = false

    init(_ reader: HttpReader, headers: [String: String], defaultToEnd: Bool = false) {
        self.reader = reader
        chunked = headers["transfer-encoding"]?.lowercased().contains("chunked") == true
        if let length = headers["content-length"].flatMap({ Int64($0) }) {
            left = length; untilEnd = false
        } else {
            left = 0; untilEnd = defaultToEnd && !chunked
        }
    }

    /// Up to `max` bytes, or an empty Data at the end of the body.
    func read(_ max: Int) async throws -> Data {
        if finished { return Data() }
        if chunked {
            if left == 0 {
                guard let sizeLine = try await reader.readLine(), let size = Int64(sizeLine.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? "", radix: 16), size >= 0 else { throw SyncError("bad chunk") }
                if size == 0 {
                    while let trailer = try await reader.readLine(), !trailer.isEmpty {}
                    finished = true
                    return Data()
                }
                left = size
            }
            let piece = try await reader.readSome(Int(min(Int64(max), left)))
            if piece.isEmpty { throw SyncError("connection closed") }
            left -= Int64(piece.count)
            if left == 0 { _ = try await reader.readLine() }
            return piece
        }
        if untilEnd {
            let piece = try await reader.readSome(max)
            if piece.isEmpty { finished = true }
            return piece
        }
        if left <= 0 { finished = true; return Data() }
        let piece = try await reader.readSome(Int(min(Int64(max), left)))
        if piece.isEmpty { throw SyncError("connection closed") }
        left -= Int64(piece.count)
        return piece
    }

    func readAll(limit: Int = maxJSON) async throws -> Data {
        var all = Data()
        while true {
            let piece = try await read(64 * 1024)
            if piece.isEmpty { return all }
            all.append(piece)
            if all.count > limit { throw SyncError("too large") }
        }
    }

    func drain() async throws {
        while !(try await read(64 * 1024)).isEmpty {}
    }
}

struct HttpRequest {
    let method: String, path: String, query: [String: String], headers: [String: String], body: HttpBody, remoteAddress: String
}

struct HttpResponse {
    let status: Int
    var body = Data()
    static func json(_ object: [String: Any], status: Int = 200) -> HttpResponse {
        HttpResponse(status: status, body: (try? JSONSerialization.data(withJSONObject: object)) ?? Data())
    }
    static func empty(_ status: Int) -> HttpResponse { HttpResponse(status: status) }
}

private func reason(_ status: Int) -> String {
    switch status {
    case 200: return "OK"; case 204: return "No Content"; case 400: return "Bad Request"; case 403: return "Forbidden"
    case 404: return "Not Found"; case 409: return "Conflict"; default: return "Error"
    }
}

private func parseQuery(_ raw: Substring?) -> [String: String] {
    var query: [String: String] = [:]
    for pair in (raw ?? "").split(separator: "&") {
        let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
        let key = String(parts[0]).removingPercentEncoding ?? String(parts[0])
        query[key] = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? String(parts[1])) : ""
    }
    return query
}

/// A small HTTP/1.1 server: one task for each connection, which may carry several requests.
final class HttpServer {
    typealias Handler = (HttpRequest) async -> HttpResponse
    private let handler: Handler
    private var listener: NWListener?
    private let lock = NSLock()
    private var active: [ObjectIdentifier: SyncConnection] = [:]
    private var closed = false
    private(set) var port: UInt16 = 0

    init(handler: @escaping Handler) { self.handler = handler }

    /// Listens on `preferred`, or on any free port when that is taken. Returns the port.
    func start(preferred: UInt16 = localSendPort) async throws -> UInt16 {
        func make(_ port: NWEndpoint.Port?) throws -> NWListener {
            if let port { return try NWListener(using: .tcp, on: port) }
            return try NWListener(using: .tcp)
        }
        var started: NWListener
        var result: UInt16
        do {
            started = try make(NWEndpoint.Port(rawValue: preferred))
            result = try await ready(started)
        } catch {
            started = try make(nil)
            result = try await ready(started)
        }
        listener = started
        port = result
        return result
    }

    private func ready(_ listener: NWListener) async throws -> UInt16 {
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            let wrapped = SyncConnection(connection)
            lock.lock()
            let full = active.count >= 64 || closed
            if !full { active[ObjectIdentifier(wrapped)] = wrapped }
            lock.unlock()
            guard !full else { connection.cancel(); return }
            Task {
                do {
                    try await wrapped.start()
                    try await self.serve(wrapped)
                } catch {}
                wrapped.close()
                self.lock.lock(); self.active[ObjectIdentifier(wrapped)] = nil; self.lock.unlock()
            }
        }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            var resumed = false
            var lastWait: Error?
            let queue = DispatchQueue(label: "sync.listener")
            listener.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready: resumed = true; continuation.resume(returning: listener.port?.rawValue ?? 0)
                case .failed(let error): resumed = true; listener.cancel(); continuation.resume(throwing: error)
                case .cancelled: resumed = true; continuation.resume(throwing: SyncError("listener stopped"))
                case .waiting(let error): lastWait = error
                default: break
                }
            }
            listener.start(queue: queue)
            // A listener with no usable network stays waiting for a path for ever: the Sync screen would wait with it,
            // and a start that finally came up after a newer one would leave both listening.
            queue.asyncAfter(deadline: .now() + 10) {
                guard !resumed else { return }
                resumed = true
                // Given up on, so it is also shut down: left running it would take the port the moment a network appears,
                // with nothing behind it to answer, and every later start would have to use another port.
                listener.cancel()
                continuation.resume(throwing: SyncError("timed out: \(lastWait?.localizedDescription ?? "no network")"))
            }
        }
    }

    func stop() {
        lock.lock()
        closed = true
        let running = Array(active.values)
        lock.unlock()
        listener?.cancel()
        listener = nil
        running.forEach { $0.close() }
    }

    private func serve(_ connection: SyncConnection) async throws {
        let reader = HttpReader(connection)
        let remote = connection.remoteAddress
        while true {
            lock.lock(); let stopped = closed; lock.unlock()
            if stopped { return }
            guard let requestLine = try await reader.readLine() else { return }
            let parts = requestLine.split(separator: " ")
            guard parts.count >= 2 else { return }
            let headers = try await reader.readHeaders()
            let target = parts[1]
            let body = HttpBody(reader, headers: headers)
            let request = HttpRequest(method: parts[0].uppercased(), path: String(target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]),
                                      query: parseQuery(target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).dropFirst().first),
                                      headers: headers, body: body, remoteAddress: remote)
            let response = await handler(request)
            // What the handler left unread must still be read, or the next request would start in the middle of it.
            try await body.drain()
            let close = headers["connection"]?.lowercased() == "close"
            var head = "HTTP/1.1 \(response.status) \(reason(response.status))\r\n"
            if response.status != 204 { head += "Content-Type: application/json\r\n" }
            head += "Content-Length: \(response.body.count)\r\nConnection: \(close ? "close" : "keep-alive")\r\n\r\n"
            var out = Data(head.utf8)
            out.append(response.body)
            try await connection.write(out)
            if close { return }
        }
    }
}

/// One request to another device, over a connection of its own. `writeBody` hands the body over in pieces.
func httpRequest(
    host: String, port: Int, method: String, path: String, contentType: String = "application/json", length: Int64 = 0,
    connectTimeout: TimeInterval = 3, idleLimit: TimeInterval = 150,
    onConnection: ((SyncConnection?) -> Void)? = nil,
    writeBody: ((Data) async throws -> Void) async throws -> Void = { _ in },
) async throws -> (status: Int, body: Data) {
    guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { throw SyncError("bad port") }
    let connection = SyncConnection(NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp))
    connection.idleLimit = idleLimit
    try await connection.start(timeout: connectTimeout)
    onConnection?(connection)
    defer { connection.close(); onConnection?(nil) }
    let head = "\(method) \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\nContent-Type: \(contentType)\r\nContent-Length: \(length)\r\nConnection: close\r\n\r\n"
    try await connection.write(Data(head.utf8))
    try await writeBody { try await connection.write($0) }
    let reader = HttpReader(connection)
    guard let statusLine = try await reader.readLine(), let status = statusLine.split(separator: " ").dropFirst().first.flatMap({ Int($0) }) else { throw SyncError("no answer") }
    let headers = try await reader.readHeaders()
    let body = try await HttpBody(reader, headers: headers, defaultToEnd: true).readAll()
    return (status, body)
}

// MARK: Receiving

/// What the receiving side does with what arrives; the app implements it.
protocol ReceiveListener: AnyObject {
    func myInfo() -> DeviceInfo
    /// Another copy of this app announced itself (or answered a search): it is a device to show.
    func registered(_ device: DeviceInfo, address: String)
    /// Returns when the person has said yes (true) or no (false).
    func offered(from: DeviceInfo, address: String, files: [FileInfo]) async -> Bool
    /// Before the person is asked: the files worth sending, so what this device already has is never uploaded.
    func wanted(_ files: [FileInfo]) async -> [FileInfo]
    func progress(received: Int, total: Int)
    /// How far the file now arriving has come: `done` of `total` files are finished, and this one is at `fraction`.
    func fileProgress(done: Int, total: Int, name: String, fraction: Double)
    /// Everything arrived: the files are in `dir`, each named by its file id.
    func finished(from: DeviceInfo, dir: URL, files: [FileInfo])
    func cancelled()
}

/// The receiving half of LocalSend's protocol. Only one offer is dealt with at a time.
final class LocalSendReceiver {
    private final class Session {
        let id: String, from: DeviceInfo, address: String, dir: URL, files: [String: FileInfo], tokens: [String: String]
        var received = Set<String>()
        var lastActive = Date()
        init(id: String, from: DeviceInfo, address: String, dir: URL, files: [String: FileInfo], tokens: [String: String]) {
            self.id = id; self.from = from; self.address = address; self.dir = dir; self.files = files; self.tokens = tokens
        }
    }

    private weak var listener: ReceiveListener?
    private let cacheDir: URL
    private let lock = NSLock()
    private var session: Session?
    private var asking = false

    private var watchdog: Task<Void, Never>?

    init(cacheDir: URL, listener: ReceiveListener) {
        self.cacheDir = cacheDir
        self.listener = listener
        // Folders left by an earlier run that was killed mid-transfer.
        if let old = try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil) {
            for url in old where url.lastPathComponent.hasPrefix("sync-") { try? FileManager.default.removeItem(at: url) }
        }
        // A sender that vanished mid-transfer would otherwise keep this device "busy" until the next request came in.
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                _ = self?.current()
            }
        }
    }

    deinit { watchdog?.cancel() }

    private func current() -> Session? {
        lock.lock()
        var ended = false
        if let s = session, Date().timeIntervalSince(s.lastActive) > sessionIdleSeconds { drop(s); ended = true }
        let now = session
        lock.unlock()
        if ended { listener?.cancelled() }
        return now
    }

    private func drop(_ s: Session) {
        if session === s { session = nil }
        try? FileManager.default.removeItem(at: s.dir)
    }

    func stop() {
        watchdog?.cancel()
        lock.lock(); defer { lock.unlock() }
        if let s = session { drop(s) }
    }

    func handle(_ request: HttpRequest) async -> HttpResponse {
        guard request.path.hasPrefix(api), let listener else { return .empty(404) }
        switch (request.method, String(request.path.dropFirst(api.count))) {
        case ("GET", "/info"): return .json(listener.myInfo().json())
        case ("POST", "/register"): return await register(request, listener)
        case ("POST", "/prepare-upload"): return await prepare(request, listener)
        case ("POST", "/upload"): return await upload(request, listener)
        case ("POST", "/cancel"): return cancel(request, listener)
        default: return .empty(404)
        }
    }

    private func readJSON(_ request: HttpRequest) async throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: try await request.body.readAll()) as? [String: Any] else { throw SyncError("not an object") }
        return object
    }

    private func register(_ request: HttpRequest, _ listener: ReceiveListener) async -> HttpResponse {
        guard let json = try? await readJSON(request) else { return .empty(400) }
        let device = DeviceInfo(json: json)
        if device.isGalleryDl && device.fingerprint != listener.myInfo().fingerprint { listener.registered(device, address: request.remoteAddress) }
        return .json(listener.myInfo().json())
    }

    /// The largest file one offer may state (1 TiB), and the most an upload may send when it stated no size.
    private static let maxFileSize: Int64 = 1 << 40
    private static let maxUnstatedUpload: Int64 = 16 * 1024 * 1024
    /// A links or favorites manifest is a small JSON file; anything bigger is not one of ours.
    static let maxManifestSize: Int64 = 4 << 20

    private func prepare(_ request: HttpRequest, _ listener: ReceiveListener) async -> HttpResponse {
        guard let body = try? await readJSON(request), let infoJSON = body["info"] as? [String: Any], let filesJSON = body["files"] as? [String: Any] else { return .empty(400) }
        let from = DeviceInfo(json: infoJSON)
        var offeredList: [FileInfo] = []
        for (key, value) in filesJSON {
            guard let f = value as? [String: Any], let name = f["fileName"] as? String else { return .empty(400) }
            // The sender states the size; it is never trusted to be sane, so a sum of sizes cannot overflow.
            let size = min(max((f["size"] as? NSNumber)?.int64Value ?? 0, 0), Self.maxFileSize)
            // A manifest is read whole once it has arrived, so one announced as larger than any manifest is refused here.
            if name == linksFileName || name == favoritesFileName, size > Self.maxManifestSize { return .empty(413) }
            offeredList.append(FileInfo(id: f["id"] as? String ?? key, fileName: name, size: size,
                                 fileType: f["fileType"] as? String ?? "application/octet-stream", preview: f["preview"] as? String))
        }
        // Two entries may carry the same id (it is a field of the entry, not its key): the first one counts.
        var seenIds = Set<String>()
        offeredList = offeredList.filter { seenIds.insert($0.id).inserted }
        // Only another copy of this app can send here: what arrives is read as this app's own links and videos.
        guard from.isGalleryDl else { return .empty(403) }
        if offeredList.isEmpty { return .empty(204) }
        lock.lock()
        if session != nil || asking { lock.unlock(); return .empty(409) }
        asking = true
        lock.unlock()
        let list = await listener.wanted(offeredList)
        if list.isEmpty { lock.lock(); asking = false; lock.unlock(); return .empty(204) }
        let accepted = await listener.offered(from: from, address: request.remoteAddress, files: list)
        guard accepted else { lock.lock(); asking = false; lock.unlock(); return .empty(403) }
        let id = UUID().uuidString
        let dir = cacheDir.appendingPathComponent("sync-\(id)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var tokens: [String: String] = [:]
        for file in list { tokens[file.id] = UUID().uuidString }
        // The offer stays "being asked" until the session exists, so a second offer cannot slip in between.
        lock.lock()
        session = Session(id: id, from: from, address: request.remoteAddress, dir: dir, files: Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) }), tokens: tokens)
        asking = false
        lock.unlock()
        return .json(["sessionId": id, "files": tokens])
    }

    private func safe(_ id: String) -> String { id.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? String($0) : "_" }.joined() }

    private func upload(_ request: HttpRequest, _ listener: ReceiveListener) async -> HttpResponse {
        guard let s = current() else { return .empty(409) }
        let q = request.query
        guard let fileId = q["fileId"] else { return .empty(400) }
        guard q["sessionId"] == s.id, let token = q["token"], s.tokens[fileId] == token, request.remoteAddress == s.address else { return .empty(403) }
        guard s.files[fileId] != nil else { return .empty(400) }
        s.lastActive = Date()
        let target = s.dir.appendingPathComponent(safe(fileId))
        FileManager.default.createFile(atPath: target.path, contents: nil)
        do {
            let handle = try FileHandle(forWritingTo: target)
            defer { try? handle.close() }
            var written: Int64 = 0
            var lastReport = Date.distantPast
            let info = s.files[fileId]
            let stated = info?.size ?? 0
            let limit = stated > 0 ? stated : Self.maxUnstatedUpload
            while true {
                let piece = try await request.body.read(256 * 1024)
                if piece.isEmpty { break }
                try handle.write(contentsOf: piece)
                written += Int64(piece.count)
                // More than the offer stated is never stored: an endless body would fill the device.
                if written > limit { throw SyncError("more than offered") }
                if let info, info.size > 0, Date().timeIntervalSince(lastReport) > 0.25 {
                    lastReport = Date()
                    lock.lock(); let finished = s.received.count; lock.unlock()
                    listener.fileProgress(done: finished, total: s.files.count, name: info.fileName, fraction: min(1, Double(written) / Double(info.size)))
                }
                // A long file keeps the session alive for as long as it keeps arriving.
                lock.lock(); s.lastActive = Date(); lock.unlock()
            }
        } catch {
            try? FileManager.default.removeItem(at: target)
            return .empty(500)
        }
        // A sender that dropped half way leaves a short file; a half video must never be taken as the whole one.
        let expected = s.files[fileId]?.size ?? 0
        let actual = ((try? FileManager.default.attributesOfItem(atPath: target.path))?[.size] as? NSNumber)?.int64Value ?? 0
        if expected > 0 && actual != expected {
            try? FileManager.default.removeItem(at: target)
            return .empty(400)
        }
        lock.lock()
        s.received.insert(fileId)
        let done = (s.received.count, s.files.count)
        s.lastActive = Date()
        lock.unlock()
        listener.progress(received: done.0, total: done.1)
        if done.0 == done.1 {
            lock.lock(); if session === s { session = nil }; lock.unlock()
            listener.finished(from: s.from, dir: s.dir, files: Array(s.files.values))
        }
        return .empty(200)
    }

    private func cancel(_ request: HttpRequest, _ listener: ReceiveListener) -> HttpResponse {
        if let s = current(), request.query["sessionId"] == s.id {
            lock.lock(); drop(s); lock.unlock()
            listener.cancelled()
        }
        return .empty(200)
    }
}

// MARK: Sending

enum SendOutcome { case sent, declined, busy, nothing }

final class LocalSendClient {
    private let me: () -> DeviceInfo
    init(me: @escaping () -> DeviceInfo) { self.me = me }

    /// Announces this device to the one at `host`:`port`; returns that device when it is a copy of this app.
    func register(host: String, port: Int = Int(localSendPort), connectTimeout: TimeInterval = 1.0) async -> DeviceInfo? {
        guard let body = try? JSONSerialization.data(withJSONObject: me().json()) else { return nil }
        guard let (status, answer) = try? await httpRequest(host: host, port: port, method: "POST", path: "\(api)/register", length: Int64(body.count),
                                                            connectTimeout: connectTimeout, idleLimit: 5, writeBody: { try await $0(body) }),
              status == 200, let json = try? JSONSerialization.jsonObject(with: answer) as? [String: Any] else { return nil }
        let info = DeviceInfo(json: json, defaultPort: port)
        return info.isGalleryDl && info.fingerprint != me().fingerprint ? info : nil
    }

    /// Offers `files` to the device at `host`:`port` and, once its person says yes, sends them. `progress` hears about each file
    /// done; `cancelled` is asked between pieces; `connection` gets the open connection so a cancel can close it.
    func send(host: String, port: Int, files: [OutgoingFile], progress: (Int, Int) -> Void = { _, _ in }, fileProgress: (Int, Int, String, Double) -> Void = { _, _, _, _ in }, cancelled: () -> Bool = { false },
              connection: @escaping (SyncConnection?) -> Void = { _ in }) async throws -> SendOutcome {
        var filesJSON: [String: Any] = [:]
        for file in files { filesJSON[file.info.id] = file.info.json() }
        let offer = try JSONSerialization.data(withJSONObject: ["info": me().json(), "files": filesJSON])
        // The receiver's person is asked, so this answer can take a while.
        let (status, answer) = try await httpRequest(host: host, port: port, method: "POST", path: "\(api)/prepare-upload", length: Int64(offer.count),
                                                     idleLimit: 120, onConnection: connection, writeBody: { try await $0(offer) })
        switch status {
        case 200: break
        case 204: return .nothing
        case 403: return .declined
        case 409: return .busy
        default: throw SyncError("the other device answered \(status)")
        }
        guard let reply = try JSONSerialization.jsonObject(with: answer) as? [String: Any], let sessionId = reply["sessionId"] as? String,
              let tokens = reply["files"] as? [String: String] else { throw SyncError("unexpected answer") }
        var done = 0
        // The receiver may ask for fewer files than were offered: it leaves out what it already has.
        let total = files.filter { tokens[$0.info.id] != nil }.count
        do {
            for file in files {
                if cancelled() { throw SyncError("cancelled") }
                guard let token = tokens[file.info.id] else { continue }
                let query = "\(api)/upload?sessionId=\(enc(sessionId))&fileId=\(enc(file.info.id))&token=\(enc(token))"
                let source = try file.open()
                defer { source.close() }
                let (code, _) = try await httpRequest(host: host, port: port, method: "POST", path: query, contentType: "application/octet-stream", length: file.info.size,
                                                      idleLimit: 120, onConnection: connection, writeBody: { write in
                    var sent: Int64 = 0
                    var last = Date.distantPast
                    while true {
                        let piece = try source.read(256 * 1024)
                        if piece.isEmpty { break }
                        try await write(piece)
                        sent += Int64(piece.count)
                        if file.info.size > 0, Date().timeIntervalSince(last) > 0.25 {
                            last = Date()
                            fileProgress(done, total, file.info.fileName, min(1, Double(sent) / Double(file.info.size)))
                        }
                    }
                })
                guard code == 200 else { throw SyncError("the other device answered \(code)") }
                done += 1
                progress(done, total)
            }
        } catch {
            _ = try? await httpRequest(host: host, port: port, method: "POST", path: "\(api)/cancel?sessionId=\(enc(sessionId))", connectTimeout: 1.5, idleLimit: 5)
            throw error
        }
        return .sent
    }

    private func enc(_ text: String) -> String { text.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? text }
}

/// The manifests that tell a receiving copy what the videos are; plain JSON files.
func linksJSON(_ links: [SyncLink]) -> Data {
    (try? JSONSerialization.data(withJSONObject: ["links": links.map { ["url": $0.url, "site": $0.site, "folder": $0.folder] }])) ?? Data()
}

func favoritesJSON(_ items: [(id: String, favorite: SyncFavorite)]) -> Data {
    (try? JSONSerialization.data(withJSONObject: ["items": items.map {
        ["id": $0.id, "parent": $0.favorite.parent, "video": $0.favorite.video, "name": $0.favorite.name, "mime": $0.favorite.mime]
    }])) ?? Data()
}
