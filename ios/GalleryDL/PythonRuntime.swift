import Foundation

struct PythonError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Owns the embedded interpreter. Calls block, so they run on a background queue.
final class PythonRuntime {
    static let shared = PythonRuntime()

    private let queue = DispatchQueue(label: "app.gallerydl.python", attributes: .concurrent)
    /// Guards `started` and `failure`: `start()` runs on one thread and every `call()` reads them from another.
    private let state = NSLock()
    private var started = false
    private var failure: String?

    func start() throws {
        state.lock()
        let isStarted = started
        state.unlock()
        guard !isStarted else { return }
        var error: NSString?
        let ok = GDLStartPython(Bundle.main.bundlePath, &error)
        if ok {
            // Register trusted plugins before checks or download jobs can reach the interpreter.
            var directory = MediaFiles.support.appendingPathComponent("Extractor Plugins", isDirectory: true)
            #if os(iOS)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? directory.setResourceValues(values)
            #endif
            var config = ["directory": directory.path, "temporary": FileManager.default.temporaryDirectory.path]
            #if os(iOS)
            config["target"] = "ios"
            #endif
            let data = try JSONSerialization.data(withJSONObject: config)
            _ = GDLCall("gdl_ios", "start_plugins", String(decoding: data, as: UTF8.self))
        }
        state.lock()
        if ok { started = true } else { failure = (error as String?) ?? S.errorEngineUnavailable }
        let message = failure
        state.unlock()
        if !ok { throw PythonError(message: message ?? S.errorEngineUnavailable) }
    }

    /// Calls `gdl_ios.<function>` with a JSON argument and decodes the JSON it returns.
    ///
    /// Waits until the interpreter is up first. A link restored from the store can be started a moment after launch,
    /// and calling into an interpreter that does not exist yet is a crash, not a failure; if it could not start at
    /// all, the call fails with the reason instead of waiting for ever.
    func call(_ function: String, _ argument: [String: Any] = [:]) async throws -> [String: Any] {
        try await waitUntilStarted()
        let data = try JSONSerialization.data(withJSONObject: argument)
        let json = String(decoding: data, as: UTF8.self)
        let reply: String = await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: GDLCall("gdl_ios", function, json)) }
        }
        guard let object = try JSONSerialization.jsonObject(with: Data(reply.utf8)) as? [String: Any] else {
            throw PythonError(message: "Unexpected reply from Python")
        }
        if let message = object["error"] as? String { throw PythonError(message: message) }
        return object
    }

    /// Waits for `start()`, which `boot()` runs once, to finish: a call that arrives first waits for it instead of
    /// reaching an interpreter that is not there yet. Ten seconds is long past any start, so a call that has waited
    /// that long is one whose start failed silently, and it fails rather than hang.
    private func waitUntilStarted() async throws {
        let deadline = Date().addingTimeInterval(10)
        while true {
            state.lock()
            let isStarted = started
            let failed = failure
            state.unlock()
            if let failed { throw PythonError(message: failed) }
            if isStarted { return }
            if Date() >= deadline { throw PythonError(message: S.errorEngineUnavailable) }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }
}
