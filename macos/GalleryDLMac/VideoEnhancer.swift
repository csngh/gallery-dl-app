import AVFoundation
import CoreImage
import CoreVideo
import Metal
import simd
import VideoToolbox

struct EnhanceError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

enum EnhanceProgress {
    case downloadingModel(Double)
    case starting
    case frames(done: Int, total: Int, secondsLeft: Double?)
}

struct EnhanceSource {
    let width: Int
    let height: Int
    let duration: Double
    let frames: Int
    /// Frames a second, as the video is stored.
    let fps: Double
    /// Whether the video is HDR (PQ or HLG): its brightness is not adjusted, even when asked.
    var isHDR = false
}

struct EnhanceResult {
    let url: URL
    let width: Int
    let height: Int
    let bytes: Int64
}

/// Makes a video sharper at up to 1080p with Apple's machine-learning upscaler (VideoToolbox, macOS 26 and newer): every frame
/// goes four times larger, is scaled to fit 1920×1080, and is written as high-bitrate HEVC with the original sound, frame
/// rate, timing and color tags (HDR included). Nothing else is done to the picture. The original is never touched; the result
/// is a new file in the temporary folder.
///
/// An upscaler session that has already loaded its model, kept ready while the person decides, so the work does not start
/// with several seconds of loading.
@available(macOS 26.0, *)
final class WarmUpscaler {
    fileprivate let config: VTSuperResolutionScalerConfiguration
    fileprivate let processor = VTFrameProcessor()
    fileprivate var ended = false

    fileprivate init(config: VTSuperResolutionScalerConfiguration) { self.config = config }

    func end() {
        guard !ended else { return }
        ended = true
        processor.endSession()
    }
}

/// Sets the transparency of a half-float picture to fully opaque on the graphics processor, in place. The upscaler leaves
/// that channel undefined; doing the fix there means no processor core ever has to go over a 33-million-pixel picture.
private final class OpaqueOnGPU {
    private let queue: MTLCommandQueue
    private let pipeline: MTLComputePipelineState
    private var cache: CVMetalTextureCache?
    private let lock = NSLock()

    init?() {
        guard let device = MTLCreateSystemDefaultDevice(), device.readWriteTextureSupport != .tierNone, let queue = device.makeCommandQueue() else { return nil }
        let source = """
        #include <metal_stdlib>
        using namespace metal;
        kernel void make_opaque(texture2d<half, access::read_write> image [[texture(0)]], uint2 position [[thread_position_in_grid]]) {
            if (position.x >= image.get_width() || position.y >= image.get_height()) { return; }
            half4 color = image.read(position);
            image.write(half4(color.rgb, 1.0h), position);
        }
        """
        guard let library = try? device.makeLibrary(source: source, options: nil), let function = library.makeFunction(name: "make_opaque"),
              let pipeline = try? device.makeComputePipelineState(function: function) else { return nil }
        var made: CVMetalTextureCache?
        guard CVMetalTextureCacheCreate(nil, nil, device, nil, &made) == kCVReturnSuccess else { return nil }
        self.queue = queue
        self.pipeline = pipeline
        self.cache = made
    }

    /// False when the graphics processor could not do it; the caller then does it another way.
    func fix(_ buffer: CVPixelBuffer) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let cache else { return false }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        let usage = MTLTextureUsage([.shaderRead, .shaderWrite]).rawValue
        var texture: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(nil, cache, buffer, [kCVMetalTextureUsage as String: usage] as CFDictionary, .rgba16Float, width, height, 0, &texture) == kCVReturnSuccess,
              let texture, let image = CVMetalTextureGetTexture(texture),
              let command = queue.makeCommandBuffer(), let encoder = command.makeComputeCommandEncoder() else { return false }
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(image, index: 0)
        let columns = pipeline.threadExecutionWidth, rows = max(1, pipeline.maxTotalThreadsPerThreadgroup / columns)
        encoder.dispatchThreads(MTLSize(width: width, height: height, depth: 1), threadsPerThreadgroup: MTLSize(width: columns, height: rows, depth: 1))
        encoder.endEncoding()
        command.commit()
        command.waitUntilCompleted()
        CVMetalTextureCacheFlush(cache, 0)
        return command.status == .completed
    }
}

/// What the background finishing of one picture keeps: its color correction. Only touched by one finishing task at a time,
/// which the tasks' order guarantees.
private final class FinishState: @unchecked Sendable {
    var corrections = [SIMD3<Float>](repeating: .zero, count: 2)
}

/// What the video file says about its own colors, which the new file repeats exactly.
private struct SourceColors {
    var primaries: String?, transfer: String?, matrix: String?
    /// The HDR10 side data (mastering display and content light level), when the file has it.
    var mastering: Data?, lightLevel: Data?
    var tenBit = false
    var isHDR: Bool { transfer == "SMPTE_ST_2084_PQ" || transfer == "ITU_R_2100_HLG" }
}

@available(macOS 26.0, *)
enum VideoEnhancer {
    /// Short clips only: the work takes about a second a frame.
    static let maxSeconds = 25.0
    /// The size the result is fitted into (long side, short side).
    private static let targetLong = 1920.0, targetShort = 1080.0

    static var isSupported: Bool { VTSuperResolutionScalerConfiguration.isSupported }

    // MARK: How long it takes

    /// A rough time for the whole job, from measurements of the shipping app on an Apple-silicon Mac: about 0.7 seconds a
    /// frame for 720p and 1.3 for 1080p (it grows more slowly than the number of pixels), plus a few seconds to start, and a
    /// margin because other work on the Mac slows it. The real figure
    /// replaces this once the work is running.
    static func estimateSeconds(_ source: EnhanceSource) -> Double {
        let megapixels = Double(source.width * source.height) / 1_000_000
        let perFrame = 0.78 * pow(megapixels / 0.92, 0.72) + 0.05
        return Double(source.frames) * perFrame + 8
    }

    // MARK: Looking at the video first

    static func inspect(_ item: LibraryItem) async throws -> EnhanceSource {
        if item.isWebM {
            guard let file = WebMFile(url: item.fileURL), let video = file.video else { throw EnhanceError(message: "This video could not be read.") }
            // The usual gap between pictures, not the average, so a pause in the middle does not change the answer.
            var gaps: [Double] = []
            let count = min(file.videoCount, 240)
            if count > 2 { for i in 1..<count { let gap = file.video(i).time - file.video(i - 1).time; if gap > 0 { gaps.append(gap) } } }
            let usual = gaps.sorted()[safe: gaps.count / 2] ?? 0
            return EnhanceSource(width: video.width, height: video.height, duration: file.duration, frames: file.videoCount, fps: usual > 0 ? 1 / usual : Double(file.videoCount) / max(0.1, file.duration))
        }
        let asset = AVURLAsset(url: item.fileURL)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw EnhanceError(message: "This file has no video.") }
        let size = try await track.load(.naturalSize)
        let seconds = try await asset.load(.duration).seconds
        let rate = try await track.load(.nominalFrameRate)
        let fps = Double(rate > 0 ? rate : 30)
        var hdr = false
        if let description = try await track.load(.formatDescriptions).first,
           let transfer = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String {
            hdr = transfer == "SMPTE_ST_2084_PQ" || transfer == "ITU_R_2100_HLG"
        }
        var source = EnhanceSource(width: Int(size.width), height: Int(size.height), duration: seconds, frames: Int(seconds * fps), fps: fps)
        source.isHDR = hdr
        return source
    }

    /// Why this video cannot be enhanced, or nil when it can.
    static func problem(with source: EnhanceSource) -> String? {
        if !isSupported { return "This Mac does not have the video enhancer. It needs macOS 26 or newer on a Mac with Apple silicon." }
        if source.duration > maxSeconds { return "Enhancing works on videos up to \(Int(maxSeconds)) seconds. This one is \(Int(source.duration.rounded())) seconds." }
        let (w, h) = source.width >= source.height ? (source.width, source.height) : (source.height, source.width)
        // The upscaler takes pictures up to 1920×1080 (after a portrait one is turned to lie down). A video of exactly that size is
        // enhanced too: it is made four times larger and brought back down to 1080p, which gives a cleaner picture, not a bigger one.
        if w > Int(targetLong) || h > Int(targetShort) {
            return "Enhancing works on videos up to 1920×1080. This one is \(source.width)×\(source.height)."
        }
        if w < 96 || h < 96 { return "This video is too small to enhance." }
        return nil
    }

    /// A still picture near `time` at full size, for comparing before and after.
    static func still(of url: URL, isWebM: Bool, at time: Double) async -> CGImage? {
        if isWebM { return WebMFrames.image(of: url, near: time, edge: 4096) }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        return try? await generator.image(at: CMTime(seconds: time, preferredTimescale: 600)).image
    }

    // MARK: Where the frames come from

    private final class Feed {
        let width: Int, height: Int
        let frames: Int
        let transform: CGAffineTransform
        let next: () throws -> (CVPixelBuffer, CMTime)?
        var audio: AudioFeed?
        var keepAlive: [Any] = []
        /// What the video track states about its colors.
        var colors = SourceColors()
        /// The metadata of the file itself (title, date, place and so on), repeated in the new file.
        var metadata: [AVMetadataItem] = []
        /// The units the video's times are counted in (30000 a second for a 29.97 fps video).
        var timeScale: Int32 = 90_000
        init(width: Int, height: Int, frames: Int, transform: CGAffineTransform, next: @escaping () throws -> (CVPixelBuffer, CMTime)?) {
            self.width = width; self.height = height; self.frames = frames; self.transform = transform; self.next = next
        }
    }

    private struct AudioFeed {
        let channels: Int
        let rate: Double
        let next: () -> CMSampleBuffer?
    }

    private static func makeFeed(_ item: LibraryItem) async throws -> Feed {
        if item.isWebM {
            guard let file = WebMFile(url: item.fileURL), let video = file.video, let decoder = WebMDecoder(file: file) else {
                throw EnhanceError(message: "This video could not be read.")
            }
            var index = 0
            let feed = Feed(width: video.width, height: video.height, frames: file.videoCount, transform: .identity) {
                while index < file.videoCount {
                    let i = index
                    index += 1
                    if let buffer = decoder.decode(i) { return (buffer, CMTime(seconds: file.video(i).time, preferredTimescale: 90_000)) }
                }
                return nil
            }
            if let track = file.audio, let sound = WebMAudioDecoder(track: track) {
                var next = 0
                feed.audio = AudioFeed(channels: sound.channels, rate: sound.sampleRate) {
                    while next < file.audioCount {
                        let packet = file.audio(next)
                        next += 1
                        if let sample = sound.decode(file.bytes(of: packet), time: packet.time) { return sample }
                    }
                    return nil
                }
                feed.keepAlive = [sound]
            }
            feed.keepAlive.append(contentsOf: [file, decoder])
            return feed
        }

        let asset = AVURLAsset(url: item.fileURL)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw EnhanceError(message: "This file has no video.") }
        let size = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let rate = try await track.load(.nominalFrameRate)
        let seconds = try await asset.load(.duration).seconds
        var colors = SourceColors()
        if let description = try await track.load(.formatDescriptions).first {
            func text(_ key: CFString) -> String? { CMFormatDescriptionGetExtension(description, extensionKey: key) as? String }
            colors.primaries = text(kCMFormatDescriptionExtension_ColorPrimaries)
            colors.transfer = text(kCMFormatDescriptionExtension_TransferFunction)
            colors.matrix = text(kCMFormatDescriptionExtension_YCbCrMatrix)
            colors.mastering = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_MasteringDisplayColorVolume) as? Data
            colors.lightLevel = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_ContentLightLevelInfo) as? Data
            let bits = (CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_BitsPerComponent) as? NSNumber)?.intValue ?? 8
            colors.tenBit = bits > 8 || colors.isHDR
        }
        let reader = try AVAssetReader(asset: asset)
        // A ten-bit or HDR video is read as ten-bit, so nothing is squeezed to eight bits on the way in.
        let readFormat = colors.tenBit ? kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange : kCVPixelFormatType_32BGRA
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: readFormat])
        reader.add(output)

        // The sound has a reader of its own: if it shared the picture's, reading the sound ahead would make that reader hold
        // decoded pictures in memory until they are asked for.
        var audioReader: AVAssetReader?
        var audioOutput: AVAssetReaderTrackOutput?
        var channels = 2, sampleRate = 48_000.0
        if let sound = try await asset.loadTracks(withMediaType: .audio).first {
            if let description = try await sound.load(.formatDescriptions).first, let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee {
                channels = Int(basic.mChannelsPerFrame); sampleRate = basic.mSampleRate
            }
            let pcm: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
            ]
            let out = AVAssetReaderTrackOutput(track: sound, outputSettings: pcm)
            let soundReader = try AVAssetReader(asset: asset)
            if soundReader.canAdd(out) {
                soundReader.add(out)
                if soundReader.startReading() { audioReader = soundReader; audioOutput = out }
            }
        }
        guard reader.startReading() else { throw EnhanceError(message: reader.error?.localizedDescription ?? "This video could not be read.") }

        let feed = Feed(width: Int(size.width), height: Int(size.height), frames: Int(seconds * Double(rate > 0 ? rate : 30)), transform: transform) {
            while let sample = output.copyNextSampleBuffer() {
                guard let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
                return (buffer, CMSampleBufferGetPresentationTimeStamp(sample))
            }
            if reader.status == .failed { throw reader.error ?? EnhanceError(message: "This video could not be read.") }
            return nil
        }
        if let audioOutput { feed.audio = AudioFeed(channels: channels, rate: sampleRate) { audioOutput.copyNextSampleBuffer() } }
        feed.colors = colors
        feed.timeScale = max(1, try await track.load(.naturalTimeScale))
        // Every group of metadata the file has (iTunes tags, QuickTime tags, user data such as the place), not only the first.
        var items = (try? await asset.load(.metadata)) ?? []
        for format in (try? await asset.load(.availableMetadataFormats)) ?? [] {
            for item in (try? await asset.loadMetadata(for: format)) ?? [] where !items.contains(where: { $0.identifier == item.identifier && $0.identifier != nil }) {
                items.append(item)
            }
        }
        feed.metadata = items
        feed.keepAlive = [reader, audioReader as Any]
        return feed
    }

    // MARK: Keeping the colors

    /// The average color of a picture, as red, green and blue between 0 and 1.
    private static func averageColor(of image: CIImage, in context: CIContext) -> SIMD3<Float> {
        let average = image.applyingFilter("CIAreaAverage", parameters: [kCIInputExtentKey: CIVector(cgRect: image.extent)])
        var pixel = [Float](repeating: 0, count: 4)
        context.render(average, toBitmap: &pixel, rowBytes: 16, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBAf, colorSpace: nil)
        return SIMD3(pixel[0], pixel[1], pixel[2])
    }

    /// What to add to every color of the upscaled picture so its average is the original's again. An upscaler can move the
    /// whole picture a little brighter or toward a color; this cancels that and nothing else. Never more than about 20 levels.
    private static func colorCorrection(source: CVPixelBuffer, result: CVPixelBuffer, in context: CIContext) -> SIMD3<Float> {
        let options: [CIImageOption: Any] = [.colorSpace: NSNull()]
        let wanted = averageColor(of: CIImage(cvPixelBuffer: source, options: options), in: context)
        let got = averageColor(of: CIImage(cvPixelBuffer: result, options: options), in: context)
        let limit: Float = 0.08
        return simd_clamp(wanted - got, SIMD3(repeating: -limit), SIMD3(repeating: limit))
    }

    /// The picture with `correction` added to its colors.
    private static func corrected(_ image: CIImage, by correction: SIMD3<Float>) -> CIImage {
        guard abs(correction.x) + abs(correction.y) + abs(correction.z) > 0.0005 else { return image }
        return image.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: 1, y: 0, z: 0, w: 0), "inputGVector": CIVector(x: 0, y: 1, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: 1, w: 0), "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: CGFloat(correction.x), y: CGFloat(correction.y), z: CGFloat(correction.z), w: 0),
        ])
    }

    /// The color tags the new file carries: the source's own, exactly, and the usual rule only where the file says nothing
    /// (HD is 709, smaller is 601). That rule is what the app's own player uses for the original.
    private struct OutputColors {
        let primaries: String, transfer: String, matrix: String
        let mastering: Data?, lightLevel: Data?
        let tenBit: Bool
    }

    private static func outputColors(_ source: SourceColors, picture: CVPixelBuffer, height: Int) -> OutputColors {
        let matrices = ["ITU_R_709_2", "ITU_R_601_4", "ITU_R_2020"]
        let attached = CVBufferCopyAttachment(picture, kCVImageBufferYCbCrMatrixKey, nil) as? String
        let named = [attached, source.matrix].compactMap { $0 }.first { matrices.contains($0) }
        let matrix = named ?? (source.isHDR ? "ITU_R_2020" : height >= 720 ? "ITU_R_709_2" : "ITU_R_601_4")
        let known = ["ITU_R_709_2", "SMPTE_C", "EBU_3213", "ITU_R_2020", "P3_D65"]
        let primaries = source.primaries.flatMap { known.contains($0) ? $0 : nil }
            ?? (matrix == "ITU_R_2020" ? "ITU_R_2020" : matrix == "ITU_R_601_4" ? "SMPTE_C" : "ITU_R_709_2")
        let transfer = ["ITU_R_709_2", "SMPTE_ST_2084_PQ", "ITU_R_2100_HLG"].contains(source.transfer ?? "") ? source.transfer! : "ITU_R_709_2"
        // The decoder also hands the HDR10 side data over on the pictures when it was in the stream rather than the file's header.
        let mastering = source.mastering ?? (CVBufferCopyAttachment(picture, kCVImageBufferMasteringDisplayColorVolumeKey, nil) as? Data)
        let light = source.lightLevel ?? (CVBufferCopyAttachment(picture, kCVImageBufferContentLightLevelInfoKey, nil) as? Data)
        return OutputColors(primaries: primaries, transfer: transfer, matrix: matrix, mastering: mastering, lightLevel: light, tenBit: source.tenBit)
    }

    // MARK: Making it

    /// Loads the upscaler for this video in advance. Nil when the model is not on this Mac yet (it is fetched, with progress
    /// shown, when the work starts) or when anything goes wrong; the work then simply loads it itself.
    static func warmUp(_ source: EnhanceSource) async -> WarmUpscaler? {
        guard problem(with: source) == nil else { return nil }
        let rotate = source.height > source.width
        let wide = (width: rotate ? source.height : source.width, height: rotate ? source.width : source.height)
        guard let config = VTSuperResolutionScalerConfiguration(
            frameWidth: wide.width, frameHeight: wide.height, scaleFactor: 4, inputType: .video, usePrecomputedFlow: false,
            qualityPrioritization: .normal, revision: .revision1),
              config.configurationModelStatus == .ready else { return nil }
        let warm = WarmUpscaler(config: config)
        do { try warm.processor.startSession(configuration: config) } catch { return nil }
        if Task.isCancelled { warm.end(); return nil }
        return warm
    }

    /// The upscaler leaves the alpha of its picture undefined (not a number), which turns the whole frame black as soon as
    /// anything draws it. The picture has no transparency, so every alpha is set to fully opaque.
    private static let opaqueOnGPU = OpaqueOnGPU()

    private static func makeOpaque(_ buffer: CVPixelBuffer) {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_64RGBAHalf else { return }
        if opaqueOnGPU?.fix(buffer) == true { return }
        // No graphics processor for it: every core of the processor goes over the picture instead.
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        // An 8K picture has 33 million pixels, and one core takes a quarter of a second to go over them; every core together
        // takes a small fraction of that.
        let band = 64
        DispatchQueue.concurrentPerform(iterations: (height + band - 1) / band) { index in
            for y in (index * band)..<min(height, (index + 1) * band) {
                let row = (base + y * stride).assumingMemoryBound(to: UInt16.self)
                var x = 3
                while x < width * 4 { row[x] = 0x3c00; x += 4 }   // 1.0 as a half-precision number
            }
        }
    }

    static func enhance(_ item: LibraryItem, source: EnhanceSource, warm: WarmUpscaler? = nil, progress: @escaping @Sendable (EnhanceProgress) -> Void) async throws -> EnhanceResult {
        // Without this the Mac slows or stops a window that is not in front, and a long enhance would crawl while the person
        // does something else.
        let activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical, .idleSystemSleepDisabled], reason: "Enhancing a video")
        defer { ProcessInfo.processInfo.endActivity(activity) }
        progress(.starting)
        let feed = try await makeFeed(item)
        // A portrait picture is turned to lie down for the upscaler, which wants width at least height, and turned back after.
        let rotate = feed.height > feed.width
        // The upscaler always sees the video at its full size.
        let srcW = rotate ? feed.height : feed.width, srcH = rotate ? feed.width : feed.height

        // A session loaded in advance is used when it is for this very size; otherwise it is let go.
        let ready = warm.flatMap { $0.config.frameWidth == srcW && $0.config.frameHeight == srcH && !$0.ended ? $0 : nil }
        if ready == nil { warm?.end() }
        guard let config = ready?.config ?? VTSuperResolutionScalerConfiguration(
            frameWidth: srcW, frameHeight: srcH, scaleFactor: 4, inputType: .video, usePrecomputedFlow: false,
            qualityPrioritization: .normal, revision: .revision1,
        ) else { throw EnhanceError(message: "The enhancer cannot take a video of this size.") }

        if config.configurationModelStatus != .ready {
            // Apple's enhancement model is fetched by the system the first time, and kept.
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                config.downloadConfigurationModel { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                }
                Task {
                    while config.configurationModelStatus != .ready, !Task.isCancelled {
                        progress(.downloadingModel(Double(config.configurationModelPercentageAvailable)))
                        try? await Task.sleep(nanoseconds: 500_000_000)
                    }
                }
            }
        }
        try Task.checkCancellation()
        progress(.starting)

        // Four times larger, then down to fit 1920×1080 (or kept as it is when that is already smaller).
        let bigW = srcW * 4, bigH = srcH * 4
        let shrink = min(1.0, targetLong / Double(max(bigW, bigH)), targetShort / Double(min(bigW, bigH)))
        func even(_ value: Double) -> Int { max(2, Int(value.rounded()) / 2 * 2) }
        let fitW = even(Double(bigW) * shrink), fitH = even(Double(bigH) * shrink)
        let outW = rotate ? fitH : fitW, outH = rotate ? fitW : fitH

        // The video keeps its own frame rate and the exact time of every picture: nothing is added, dropped or retimed.
        let fps = max(1.0, source.fps)
        // The first picture is read now, because what the file says about its colors goes into the new file's settings.
        var held = try feed.next()
        guard let firstPicture = held?.0 else { throw EnhanceError(message: "No picture could be read from this video.") }
        // Decoding the next pictures goes on in the background while the upscaler works on this one. The pictures are decoded
        // one at a time and in order (each waits for the one before), and only a couple ahead, so memory stays small.
        var readAhead: [Task<(CVPixelBuffer, CMTime)?, Error>] = []
        var streamEnded = false
        func fillReadAhead() {
            while !streamEnded, readAhead.count < 2 {
                let before = readAhead.last
                readAhead.append(Task.detached(priority: .userInitiated) {
                    _ = try? await before?.value
                    return try feed.next()
                })
            }
        }
        func nextFrame() async throws -> (CVPixelBuffer, CMTime)? {
            if let first = held {
                held = nil
                fillReadAhead()
                return first
            }
            guard !readAhead.isEmpty else { return nil }
            let frame = try await readAhead.removeFirst().value
            if frame == nil {
                streamEnded = true
                readAhead.forEach { $0.cancel() }
                readAhead.removeAll()
            } else {
                fillReadAhead()
            }
            return frame
        }
        let colors = outputColors(feed.colors, picture: firstPicture, height: feed.height)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("enhance-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("enhanced.mp4")

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        // The original's own metadata (title, creation date, place, and so on) goes into the new file.
        if !feed.metadata.isEmpty { writer.metadata = feed.metadata }
        // A high bitrate (about 0.4 bits per pixel, at least 20 Mbit/s), so the encoder adds no blur or blocking of its own.
        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: Int(min(100_000_000, max(20_000_000, Double(outW * outH) * fps * 0.4))),
            AVVideoExpectedSourceFrameRateKey: Int(fps.rounded()),
        ]
        if colors.tenBit { compression[AVVideoProfileLevelKey] = kVTProfileLevel_HEVC_Main10_AutoLevel as String }
        // The HDR10 side data goes into the encoder itself, so it is written into the new file as in the original.
        if let data = colors.mastering { compression[kVTCompressionPropertyKey_MasteringDisplayColorVolume as String] = data }
        if let data = colors.lightLevel { compression[kVTCompressionPropertyKey_ContentLightLevelInfo as String] = data }
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.hevc, AVVideoWidthKey: outW, AVVideoHeightKey: outH,
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: colors.primaries,
                AVVideoTransferFunctionKey: colors.transfer,
                AVVideoYCbCrMatrixKey: colors.matrix,
            ],
            AVVideoCompressionPropertiesKey: compression,
        ])
        video.expectsMediaDataInRealTime = false
        // Time is kept in the source's own units (30000 per second for a 29.97 fps video), not the writer's default of 600,
        // which would round every picture's time and bend the frame rate. A common multiple with 600 keeps whole seconds exact too.
        func gcd(_ a: Int64, _ b: Int64) -> Int64 { b == 0 ? a : gcd(b, a % b) }
        let units = Int64(feed.timeScale), common = units / gcd(units, 600) * 600
        video.mediaTimeScale = Int32(clamping: common <= 1_000_000 ? common : units)
        video.transform = feed.transform
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: colors.tenBit ? kCVPixelFormatType_ARGB2101010LEPacked : kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: outW, kCVPixelBufferHeightKey as String: outH,
            kCVPixelBufferMetalCompatibilityKey as String: true,
        ])
        writer.add(video)
        var sound: AVAssetWriterInput?
        if let audio = feed.audio {
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: audio.channels, AVSampleRateKey: audio.rate, AVEncoderBitRateKey: 192_000,
            ])
            input.expectsMediaDataInRealTime = false
            if writer.canAdd(input) { writer.add(input); sound = input }
        }
        guard writer.startWriting() else { throw writer.error ?? EnhanceError(message: "The new video could not be started.") }
        writer.startSession(atSourceTime: .zero)

        let cleanup = { try? FileManager.default.removeItem(at: directory) }
        let processor = ready?.processor ?? VTFrameProcessor()
        do {
            if ready == nil { try processor.startSession(configuration: config) }
            defer { if let ready { ready.end() } else { processor.endSession() } }

            // No color management: the picture's numbers pass through untouched, so the only conversion is the plain YCbCr to
            // RGB matrix the file itself uses (and back when it is written). Converting between brightness curves as well
            // lifted the middle tones and took the contrast out of the picture.
            let context = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
            let raw: [CIImageOption: Any] = [.colorSpace: NSNull()]
            func make(_ attributes: [String: Any], _ givenWidth: Int? = nil, _ givenHeight: Int? = nil) throws -> CVPixelBuffer {
                var buffer: CVPixelBuffer?
                let formats = attributes[kCVPixelBufferPixelFormatTypeKey as String] as? [NSNumber]
                let width = givenWidth ?? attributes[kCVPixelBufferWidthKey as String] as? Int ?? 0, height = givenHeight ?? attributes[kCVPixelBufferHeightKey as String] as? Int ?? 0
                var attributes = attributes
                attributes[kCVPixelBufferMetalCompatibilityKey as String] = true
                attributes[kCVPixelBufferWidthKey as String] = width
                attributes[kCVPixelBufferHeightKey as String] = height
                guard CVPixelBufferCreate(nil, width, height, formats?.first?.uint32Value ?? kCVPixelFormatType_64RGBAHalf, attributes as CFDictionary, &buffer) == kCVReturnSuccess, let buffer else {
                    throw EnhanceError(message: "There is not enough memory to enhance this video.")
                }
                return buffer
            }
            // Two of each, because the upscaler looks at the previous picture and the previous result as well.
            let sources = try [make(config.sourcePixelBufferAttributes), make(config.sourcePixelBufferAttributes)]
            let results = try [make(config.destinationPixelBufferAttributes), make(config.destinationPixelBufferAttributes)]

            // The sound is handed over by its own input, whenever the writer asks for more, on its own queue; that is what keeps
            // the writer's two inputs in step. The picture below waits for its own turn the same way.
            let soundQueue = DispatchQueue(label: "app.gallerydl.enhance.sound")
            let soundFinished = DispatchSemaphore(value: 0)
            if let input = sound, let audio = feed.audio {
                input.requestMediaDataWhenReady(on: soundQueue) {
                    while input.isReadyForMoreMediaData {
                        if let sample = audio.next() {
                            input.append(sample)
                        } else {
                            input.markAsFinished()
                            soundFinished.signal()
                            return
                        }
                    }
                }
            }

            var done = 0
            var previousSource: VTFrameProcessorFrame?, previousResult: VTFrameProcessorFrame?

            /// Scales a finished picture to the final size, turns a portrait one upright, and hands it to the writer.
            func emit(_ buffer: CVPixelBuffer, at time: CMTime, correction: SIMD3<Float>) async throws {
                var big = CIImage(cvPixelBuffer: buffer, options: raw)
                if shrink < 1 {
                    big = big.applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: shrink, kCIInputAspectRatioKey: 1.0])
                }
                big = corrected(big, by: correction)
                if rotate { big = big.oriented(.left) }
                guard let pool = adaptor.pixelBufferPool else { throw EnhanceError(message: "The new video could not be written.") }
                var made: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &made) == kCVReturnSuccess, let output = made else {
                    throw EnhanceError(message: "There is not enough memory to enhance this video.")
                }
                context.render(big.clampedToExtent(), to: output, bounds: CGRect(x: 0, y: 0, width: outW, height: outH), colorSpace: nil)
                // Say what the numbers in this picture mean, so the encoder does not guess a different brightness curve, and
                // repeat the HDR side data of the source.
                CVBufferSetAttachment(output, kCVImageBufferColorPrimariesKey, colors.primaries as CFString, .shouldPropagate)
                CVBufferSetAttachment(output, kCVImageBufferTransferFunctionKey, colors.transfer as CFString, .shouldPropagate)
                CVBufferSetAttachment(output, kCVImageBufferYCbCrMatrixKey, colors.matrix as CFString, .shouldPropagate)
                if let data = colors.mastering { CVBufferSetAttachment(output, kCVImageBufferMasteringDisplayColorVolumeKey, data as CFData, .shouldPropagate) }
                if let data = colors.lightLevel { CVBufferSetAttachment(output, kCVImageBufferContentLightLevelInfoKey, data as CFData, .shouldPropagate) }
                while !video.isReadyForMoreMediaData { try Task.checkCancellation(); try await Task.sleep(nanoseconds: 5_000_000) }
                guard adaptor.append(output, withPresentationTime: time) else { throw writer.error ?? EnhanceError(message: "The new video could not be written.") }
            }

            let started = Date()
            let state = FinishState()
            // Finishing a picture (its color check, scaling and encoding) goes on in the background while the upscaler is
            // already working on the next one. The finishing tasks follow one another in order, so the file is written in order.
            var lastFinish: Task<Void, Error>?
            // Who may still be reading each pair of buffers; the upscaler waits for them before it writes into a pair.
            var users: [Task<Void, Error>?] = [nil, nil]
            defer { lastFinish?.cancel() }

            // Every picture of the video is made once, at its own time, in order.
            while let (picture, time) = try await nextFrame() {
                try Task.checkCancellation()

                let k = done % 2
                try await users[k]?.value
                var image = CIImage(cvPixelBuffer: picture, options: raw)
                if rotate { image = image.oriented(.right) }
                context.render(image, to: sources[k], bounds: CGRect(x: 0, y: 0, width: srcW, height: srcH), colorSpace: nil)

                guard let sourceFrame = VTFrameProcessorFrame(buffer: sources[k], presentationTimeStamp: time),
                      let resultFrame = VTFrameProcessorFrame(buffer: results[k], presentationTimeStamp: time),
                      let parameters = VTSuperResolutionScalerParameters(
                        sourceFrame: sourceFrame, previousFrame: previousSource, previousOutputFrame: previousResult, opticalFlow: nil,
                        submissionMode: done == 0 ? .random : .sequential, destinationFrame: resultFrame)
                else { throw EnhanceError(message: "The enhancer could not take this frame.") }
                #if DEBUG
                // `GDL_ENHANCE_SKIP_SR=1` leaves the upscaler out and scales with a filter instead, to check everything around it
                // (colors, timing, writing) in seconds instead of minutes.
                if ProcessInfo.processInfo.environment["GDL_ENHANCE_SKIP_SR"] != nil {
                    let big = CIImage(cvPixelBuffer: sources[k], options: raw).applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: 4.0, kCIInputAspectRatioKey: 1.0])
                    context.render(big, to: results[k], bounds: CGRect(x: 0, y: 0, width: srcW * 4, height: srcH * 4), colorSpace: nil)
                } else {
                    try await processor.process(parameters: parameters)
                }
                #else
                try await processor.process(parameters: parameters)
                #endif
                makeOpaque(results[k])
                previousSource = sourceFrame
                previousResult = resultFrame

                let before = lastFinish
                let task = Task {
                    try await before?.value
                    try Task.checkCancellation()
                    // Keep the average color of the picture as it was (see colorCorrection).
                    let correction = colorCorrection(source: sources[k], result: results[k], in: context)
                    state.corrections[k] = correction
                    try await emit(results[k], at: time, correction: correction)
                }
                lastFinish = task
                users[k] = task

                done += 1
                let left = done >= 2 ? Date().timeIntervalSince(started) / Double(done) * Double(max(0, feed.frames - done)) : nil
                progress(.frames(done: done, total: max(feed.frames, done), secondsLeft: left))
            }
            try await lastFinish?.value
            guard done > 0 else { throw EnhanceError(message: "No picture could be read from this video.") }
            video.markAsFinished()
            if sound != nil {
                // The writer can only finish when the sound has been handed over too.
                while soundFinished.wait(timeout: .now()) != .success {
                    try Task.checkCancellation()
                    try await Task.sleep(nanoseconds: 20_000_000)
                }
            }
            await writer.finishWriting()
        } catch {
            writer.cancelWriting()
            cleanup()
            throw error
        }
        guard writer.status == .completed else {
            let reason = writer.error?.localizedDescription ?? "The new video could not be finished."
            cleanup()
            throw EnhanceError(message: reason)
        }
        // The movie's own creation and modification times are the original's, not the time of this work.
        if !item.isWebM { MovieDates.copy(from: item.fileURL, to: url) }
        let bytes = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
        return EnhanceResult(url: url, width: outW, height: outH, bytes: bytes)
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
