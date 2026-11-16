import Foundation

/// One file download that reports how much has arrived. It can be cancelled with the task that awaits it.
final class ProgressDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let onProgress: @Sendable (_ written: Int64, _ expected: Int64) -> Void
    private var continuation: CheckedContinuation<(URL, URLResponse), Error>?
    private var task: URLSessionDownloadTask?
    private var session: URLSession?
    private var movedTo: URL?
    private var moveError: Error?

    /// `expected` is -1 when the site does not say how big the file is.
    init(onProgress: @escaping @Sendable (_ written: Int64, _ expected: Int64) -> Void) {
        self.onProgress = onProgress
    }

    /// The downloaded file (in a temporary place, the caller removes it) and the response.
    func run(_ request: URLRequest) async throws -> (URL, URLResponse) {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(URL, URLResponse), Error>) in
                let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
                let task = session.downloadTask(with: request)
                self.session = session
                self.task = task
                self.continuation = cont
                if Task.isCancelled { task.cancel() } else { task.resume() }
            }
        } onCancel: { [weak self] in
            self?.task?.cancel()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        onProgress(totalBytesWritten, totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : -1)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The system removes the file when this returns, so it is moved at once.
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("favorite-dl-\(UUID().uuidString)")
        do { try FileManager.default.moveItem(at: location, to: target); movedTo = target } catch { moveError = error }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        defer { session.finishTasksAndInvalidate() }
        guard let cont = continuation else { return }
        continuation = nil
        if let error { cont.resume(throwing: error) }
        else if let moveError { cont.resume(throwing: moveError) }
        else if let movedTo, let response = task.response { cont.resume(returning: (movedTo, response)) }
        else { cont.resume(throwing: URLError(.unknown)) }
    }
}
