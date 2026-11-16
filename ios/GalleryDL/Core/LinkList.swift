import Foundation

/// The plain-text list of links used to move them between devices: one link per line, blank lines and lines starting
/// with "#" ignored. The same format is used by the Android app.
enum LinkList {
    enum Line: Equatable { case link(String), notALink }

    /// The first http or https link in a line of text, without the punctuation that often follows it.
    static func firstLink(in text: String) -> String? {
        guard let range = text.range(of: #"https?://[^\s<>"']+"#, options: [.regularExpression, .caseInsensitive]) else { return nil }
        var link = String(text[range])
        while let last = link.last, ".,;:!?)]}".contains(last) { link.removeLast() }
        return link.count > "https://".count ? link : nil
    }

    static func parse(_ text: String) -> [Line] {
        text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\u{FEFF}", with: "") }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { line in firstLink(in: line).map(Line.link) ?? .notALink }
    }

    static func format(_ urls: [String]) -> String {
        var seen = Set<String>()
        return urls.filter { seen.insert($0).inserted }.map { $0 + "\n" }.joined()
    }

    /// The sheet written by the Favorites export, a CSV that Excel opens: one row per video, with the link it came from
    /// and the video's own link. A row repeated by two copies of the same video is written once.
    static func formatFavoriteVideos(_ rows: [FavoriteRow]) -> String {
        func cell(_ text: String) -> String { "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var seen = Set<String>()
        let lines = rows.filter { seen.insert($0.parent + "\n" + $0.video + "\n" + $0.name).inserted }
            .map { cell($0.parent) + "," + cell($0.video) + "," + cell($0.name) }
        return "\u{FEFF}\"Parent link\",\"Video link\",\"File name\"\r\n" + lines.map { $0 + "\r\n" }.joined()
    }

    /// The videos in a sheet written by `formatFavoriteVideos`, as (parent link, video link). The header row and rows
    /// without a video link are skipped, quotes are optional, and a file with only a video link per line works too (the
    /// parent link is then empty).
    static func parseFavoriteVideos(_ text: String) -> [FavoriteRow] {
        var rows: [[String]] = []
        var row: [String] = []
        var cell = ""
        var quoted = false
        func endCell() { row.append(cell.trimmingCharacters(in: .whitespaces)); cell = "" }
        func endRow() {
            endCell()
            if row.contains(where: { !$0.isEmpty }) { rows.append(row) }
            row = []
        }
        let chars = Array(text.replacingOccurrences(of: "\u{FEFF}", with: ""))
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if quoted {
                if c == "\"" {
                    if i + 1 < chars.count, chars[i + 1] == "\"" { cell.append("\""); i += 1 } else { quoted = false }
                } else {
                    cell.append(c)
                }
            } else if c == "\"" {
                quoted = true
            } else if c == "," {
                endCell()
            } else if c == "\n" || c == "\r\n" || c == "\r" {
                endRow()
            } else {
                cell.append(c)
            }
            i += 1
        }
        if !cell.isEmpty || !row.isEmpty { endRow() }
        func isLink(_ s: String) -> Bool { s.lowercased().hasPrefix("http://") || s.lowercased().hasPrefix("https://") }
        var seen = Set<String>()
        var found: [FavoriteRow] = []
        for cells in rows {
            var row: FavoriteRow?
            if cells.count >= 2, isLink(cells[1]) { row = FavoriteRow(parent: cells[0], video: cells[1], name: cells.count > 2 ? cells[2] : "") }
            // A favorite saved before the app kept video addresses has none; its link and name still find it.
            else if cells.count >= 3, isLink(cells[0]), cells[1].isEmpty, !cells[2].isEmpty { row = FavoriteRow(parent: cells[0], video: "", name: cells[2]) }
            else if cells.count == 1, isLink(cells[0]) { row = FavoriteRow(parent: "", video: cells[0]) }
            if let r = row, seen.insert(r.parent + "\n" + r.video + "\n" + r.name).inserted { found.append(r) }
        }
        return found
    }
}

/// One video of a favorites sheet: the link it came from, its own address, and the name it is saved under. The address can
/// go dead (a signature that runs out), so the name is what finds the file again; older sheets have no name column.
struct FavoriteRow: Equatable {
    var parent: String
    var video: String
    var name = ""
}

/// What an import of favorite videos did with the rows it found.
struct FavoritesImportResult {
    /// Copied from files the library already had.
    var copied = 0
    /// Fetched from their video links.
    var downloaded = 0
    /// Already in Favorites, so left alone.
    var duplicates = 0
    /// Not in the library and could not be fetched (an expired link, a site that refused).
    var failed = 0
    /// The last few files that could not be added, each with the reason.
    var failures: [(name: String, reason: String)] = []
    var added: Int { copied + downloaded }
}

/// What the favorites import dialog shows.
/// What the favorites import is doing right now: `read` of `length` bytes of the video `name` (length is -1 when the site does
/// not say), or `lookingUp` the current address of it from its original link.
struct FavoriteStep: Equatable {
    var name: String
    var read: Int64
    var length: Int64
    var lookingUp = false
}

enum FavoritesTransfer {
    /// `stopping` is true after the user pressed Cancel, until the video being fetched right now is done.
    case importing(done: Int, total: Int, stopping: Bool)
    /// `stopped` is true when the user ended the import early.
    case imported(FavoritesImportResult, stopped: Bool)
}

struct ImportResult {
    var added = 0, duplicates = 0, unsupported = 0, notLinks = 0
    var found: Int { added + duplicates + unsupported + notLinks }
}

/// What the export and import dialogs show.
enum Transfer {
    case importing(done: Int, total: Int)
    case imported(ImportResult)
    case failed
}
