import SwiftUI
import UniformTypeIdentifiers

struct ExtractorPluginsSettings: View {
    @EnvironmentObject private var ui: UIState
    @State private var state: Snapshot?
    @State private var importing = false
    @State private var busy = false
    @State private var error: String?

    private struct Plugin: Decodable, Identifiable {
        let id: String
        let name: String
        let version: String
        let domains: [String]
        let enabled: Bool
        let active: Bool
        let error: String?

        var status: String {
            if enabled { return active ? "Active" : (error == nil ? "Enables after restart" : "Not loaded") }
            return active ? "Disables after restart" : "Disabled"
        }
    }

    private struct Snapshot: Decodable {
        let plugins: [Plugin]
        let restartRequired: Bool
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.md) {
            Text("Plugins can access files and data with the same permissions as Gallery DL. Enable only plugins you trust.")
                .textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            if state?.restartRequired == true {
                Text(Platform.isMac ? "Quit and reopen Gallery DL to apply your plugin changes."
                     : "Close Gallery DL from the app switcher, then reopen it to apply your plugin changes.")
                    .textStyle(.labelLarge, color: Brand.primary)
            }
            if let error {
                Text(error).textStyle(.bodySmall, color: Brand.error).textSelection(.enabled)
            }
            if state?.plugins.isEmpty == true {
                Text("No extractor plugins installed.").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            ForEach(state?.plugins ?? []) { plugin in
                Rectangle().fill(Brand.outlineVariant).frame(height: 1)
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(plugin.name).textStyle(.titleSmall)
                    Text([plugin.version, plugin.status].filter { !$0.isEmpty }.joined(separator: " · "))
                        .textStyle(.labelMedium, color: Brand.onSurfaceVariant)
                    if !plugin.domains.isEmpty {
                        Text(plugin.domains.joined(separator: ", "))
                            .textStyle(.bodySmall, color: Brand.onSurfaceVariant).textSelection(.enabled)
                    }
                    if let message = plugin.error {
                        Text(message).textStyle(.bodySmall, color: Brand.error)
                    }
                    HStack(spacing: Spacing.sm) {
                        OutlinedPill(title: plugin.enabled ? "Disable" : "Enable") { change(plugin) }
                            .disabled(!plugin.enabled && plugin.error != nil)
                        TextPill(title: S.actionRemove, color: ActionColors.negative) {
                            ui.present(ConfirmDialog(
                                title: "Remove \(plugin.name)?",
                                message: "This removes the installed plugin, not your links or downloads.",
                                confirmLabel: S.actionRemove
                            ) { run("remove", ["id": plugin.id, "enabled": plugin.enabled]) })
                        }
                    }
                }
            }
            HStack(spacing: Spacing.sm) {
                OutlinedPill(title: "Import plugin", symbol: "square.and.arrow.down") { importing = true }
                if busy { ProgressView().controlSize(.small).accessibilityLabel("Updating extractor plugins") }
            }
        }
        .disabled(busy)
        .task { run("list") }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(importedAs: "app.gallerydl.extractor-plugin")]) { result in
            switch result {
            case .success(let url): run("import", ["path": url.path], file: url)
            case .failure(let failure): error = failure.localizedDescription
            }
        }
    }

    private func change(_ plugin: Plugin) {
        if plugin.enabled {
            run("disable", ["id": plugin.id])
        } else {
            ui.present(ConfirmDialog(
                title: "Trust and enable \(plugin.name)?",
                message: "This plugin can run code with the same permissions as Gallery DL. It will load the next time you open the app.",
                confirmLabel: "Enable plugin",
                destructive: false
            ) { run("enable", ["id": plugin.id]) })
        }
    }

    private func run(_ action: String, _ arguments: [String: Any] = [:], file: URL? = nil) {
        guard !busy else { return }
        busy = true
        error = nil
        Task { @MainActor in
            let access = file?.startAccessingSecurityScopedResource() ?? false
            defer {
                if access { file?.stopAccessingSecurityScopedResource() }
                busy = false
            }
            do {
                let reply = try await PythonRuntime.shared.call("plugins", arguments.merging(["action": action]) { _, action in action })
                let decoder = JSONDecoder()
                decoder.keyDecodingStrategy = .convertFromSnakeCase
                state = try decoder.decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: reply))
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
