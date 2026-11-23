import Foundation

/// Copies the creation and modification times stored inside an MP4 or MOV file (in its movie, track and media headers) from
/// one file to another. The system's file writer stamps those with the time it ran; this puts the original's back. Only the
/// date fields are overwritten, in place, so nothing else in the file moves.
enum MovieDates {
    private struct Box { let offset: UInt64, size: UInt64, type: String, header: UInt64 }

    /// The boxes one level inside the range `start..<end` of `handle`.
    private static func boxes(_ handle: FileHandle, from start: UInt64, to end: UInt64) -> [Box] {
        var result: [Box] = []
        var position = start
        while position + 8 <= end {
            guard (try? handle.seek(toOffset: position)) != nil, let head = try? handle.read(upToCount: 16), head.count >= 8 else { break }
            let bytes = [UInt8](head)
            var size = UInt64(bytes[0]) << 24 | UInt64(bytes[1]) << 16 | UInt64(bytes[2]) << 8 | UInt64(bytes[3])
            let type = String(bytes: bytes[4..<8], encoding: .isoLatin1) ?? ""
            var header: UInt64 = 8
            if size == 1, bytes.count >= 16 {
                size = bytes[8...15].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
                header = 16
            } else if size == 0 {
                size = end - position
            }
            // Compared as a remainder: the size comes from the file, and added to the position it could overflow.
            guard size >= header, size <= end - position else { break }
            result.append(Box(offset: position, size: size, type: type, header: header))
            position += size
        }
        return result
    }

    /// The offsets of every header that holds dates: movie (mvhd), track (tkhd), media (mdhd).
    private static func dateHeaders(in file: URL) -> [Box] {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return [] }
        defer { try? handle.close() }
        let length = (try? handle.seekToEnd()) ?? 0
        var found: [Box] = []
        func walk(_ box: Box) {
            for child in boxes(handle, from: box.offset + box.header, to: box.offset + box.size) {
                switch child.type {
                case "mvhd", "tkhd", "mdhd": found.append(child)
                case "trak", "mdia": walk(child)
                default: break
                }
            }
        }
        for top in boxes(handle, from: 0, to: length) where top.type == "moov" { walk(top) }
        return found
    }

    /// Reads the creation and modification time (seconds since 1904) of the movie header.
    private static func movieTimes(of file: URL) -> (created: UInt64, modified: UInt64)? {
        guard let header = dateHeaders(in: file).first(where: { $0.type == "mvhd" }),
              let handle = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: header.offset + header.header)) != nil, let data = try? handle.read(upToCount: 20), data.count >= 12 else { return nil }
        let bytes = [UInt8](data)
        func number(_ from: Int, _ count: Int) -> UInt64 { bytes[from..<(from + count)].reduce(UInt64(0)) { $0 << 8 | UInt64($1) } }
        if bytes[0] == 1, bytes.count >= 20 { return (number(4, 8), number(12, 8)) }
        return (number(4, 4), number(8, 4))
    }

    /// Gives every date header of `target` the times of `source`'s movie header. Quietly does nothing for a file it cannot read.
    static func copy(from source: URL, to target: URL) {
        guard let times = movieTimes(of: source), let handle = try? FileHandle(forUpdating: target) else { return }
        defer { try? handle.close() }
        for header in dateHeaders(in: target) {
            guard (try? handle.seek(toOffset: header.offset + header.header)) != nil, let head = try? handle.read(upToCount: 1), let version = head.first else { continue }
            func bytes(_ value: UInt64, _ count: Int) -> Data { Data((0..<count).map { UInt8((value >> UInt64(8 * (count - 1 - $0))) & 0xff) }) }
            var fields = Data()
            if version == 1 { fields = bytes(times.created, 8) + bytes(times.modified, 8) }
            else { fields = bytes(min(times.created, UInt64(UInt32.max)), 4) + bytes(min(times.modified, UInt64(UInt32.max)), 4) }
            // After the version byte and the three bytes of flags.
            guard (try? handle.seek(toOffset: header.offset + header.header + 4)) != nil else { continue }
            try? handle.write(contentsOf: fields)
        }
    }
}
