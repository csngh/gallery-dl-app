import SwiftUI

/// Sync with nearby devices: find another Gallery DL on the same Wi-Fi, pair with it once, then receive, send or merge links
/// and favorite videos. The device is announced and listening only while this screen is open.
struct SyncScreen: View {
    @EnvironmentObject private var app: AppModel
    let onBack: () -> Void

    var body: some View { SyncContent(sync: app.sync, onBack: onBack) }
}

private struct SyncContent: View {
    @EnvironmentObject private var ui: UIState
    @ObservedObject var sync: SyncManager
    let onBack: () -> Void
    @State private var address = ""
    @State private var offerShown = false
    @State private var addressError: String?

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: S.syncTitle, leading: {
                // The Mac sidebar is the way back.
                if !Platform.isMac { IconButton(symbol: "chevron.left", color: Brand.onSurface, label: S.actionBack, action: onBack) }
            }, trailing: {})
            SyncStatusBanner(sync: sync)
            SoftScrollView {
                VStack(alignment: .leading, spacing: Spacing.lg) {
                    Text(S.syncIntro).textStyle(.bodyMedium, color: Brand.onSurfaceVariant)
                    if let problem = sync.problem { Text(problem).textStyle(.bodyMedium, color: Brand.error) }
                    thisDevice
                    nearby
                    addByAddress
                }
                .padding(Spacing.md)
            }
        }
        .task { await sync.start() }
        // The Mac keeps listening after the screen closes; the iPhone stops.
        .onDisappear { if Platform.isMac { sync.stopSearching() } else { sync.stop() } }
        .onChange(of: sync.offer?.id) { _ in
            // On the Mac the window around every screen shows the offer, so it is not shown here as well.
            guard !Platform.isMac else { return }
            if let offer = sync.offer {
                ui.present(SyncOfferDialog(sync: sync, offer: offer))
                offerShown = true
            } else if offerShown {
                // The offer ended without a tap (no answer in time): the question must not stay on screen.
                ui.dismissDialog()
                offerShown = false
            }
        }
    }

    private func heading(_ text: String) -> some View { Text(text).textStyle(.titleMedium) }

    private var thisDevice: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            heading(S.syncThisDevice)
            TextField(S.syncDeviceName, text: $sync.deviceName)
                .outlinedTextField()
            if sync.port > 0 { Text(S.syncAddressLine(sync.port)).textStyle(.bodySmall, color: Brand.onSurfaceVariant) }
        }
    }

    private var nearby: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack {
                heading(S.syncNearby)
                Spacer(minLength: 0)
                TextPill(title: S.syncSearchAgain, color: Brand.primary) { sync.searchAgain() }
            }
            if sync.devices.isEmpty {
                Text(sync.problem == nil ? S.syncNoDevices : "").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            ForEach(sync.devices) { device in deviceRow(device) }
        }
    }

    private func deviceRow(_ device: SyncDevice) -> some View {
        HStack(spacing: Spacing.md) {
            Image(systemName: device.platform == "desktop" ? "tv" : "iphone").font(.system(size: 22)).foregroundColor(Brand.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).textStyle(.titleSmall)
                Text(device.host).textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            FilledPill(title: S.syncSend) { ui.present(SyncChooseDialog(sync: sync, device: device)) }
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous).fill(Brand.surfaceContainerHigh))
    }

    private var addByAddress: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            heading(S.syncAddByAddress)
            HStack {
                TextField(S.syncAddressField, text: $address)
                    .outlinedTextField()
                    .keyboardType(.numbersAndPunctuation)
                    .autocapitalization(.none)
                OutlinedPill(title: S.syncAddAction) {
                    Task { addressError = await sync.add(address: address); if addressError == nil { address = "" } }
                }
            }
            if let addressError { Text(addressError).textStyle(.bodySmall, color: Brand.error) }
        }
    }
}

/// Progress of what is being moved, and what came of it afterwards.
private struct SyncStatusBanner: View {
    @ObservedObject var sync: SyncManager

    var body: some View {
        switch sync.status {
        case .running(let title, let done, let total, let file, let fraction):
            card {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).textStyle(.titleSmall)
                        if total > 0 { Text(S.syncFileOf(min(done + 1, total), total) + (file.map { " · \($0)" } ?? "")).textStyle(.bodySmall, color: Brand.onSurfaceVariant).lineLimit(1) }
                    }
                    Spacer(minLength: Spacing.sm)
                    TextPill(title: S.actionCancel, color: Brand.primary) { sync.cancel() }
                }
                if total > 0, let fraction {
                    Text("\(Int(fraction * 100))%").textStyle(.labelSmall, color: Brand.onSurfaceVariant)
                    ProgressView(value: fraction).tint(Brand.primary)
                }
                if total > 0 { ProgressView(value: Double(min(done, total)) / Double(total)).tint(Brand.onSurfaceVariant) } else { ProgressView().tint(Brand.primary) }
            }
        case .finished(let text, let details):
            resultCard(text, details)
        case .failed(let text):
            resultCard(text, [])
        case nil:
            EmptyView()
        }
    }

    /// The result of a sync, and below it what was left out and why, one line each.
    private func resultCard(_ text: String, _ details: [String]) -> some View {
        card {
            HStack(alignment: .top) {
                Text(text).textStyle(.bodyMedium)
                Spacer(minLength: Spacing.sm)
                TextPill(title: S.actionOk, color: Brand.primary) { sync.dismissStatus() }
            }
            ForEach(Array(details.prefix(40).enumerated()), id: \.offset) { _, line in
                Text("• \(line)").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            if details.count > 40 { Text("…and \(details.count - 40) more").textStyle(.bodySmall, color: Brand.onSurfaceVariant) }
        }
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) { content() }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous).fill(Brand.surfaceContainerHigh))
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.xs)
    }
}

/// Another device wants to send something: nothing is received until the person agrees.
struct SyncOfferDialog: View {
    @EnvironmentObject private var ui: UIState
    @ObservedObject var sync: SyncManager
    let offer: SyncOfferInfo

    var body: some View {
        DialogCard(title: S.syncOfferTitle(offer.from)) {
            Text(S.syncOfferBody(links: offer.links, videos: offer.videos, bytes: offer.bytes)).textStyle(.bodyMedium, color: Brand.onSurfaceVariant)
        } actions: {
            TextPill(title: S.syncDecline, color: Brand.onSurfaceVariant) { sync.decline(); ui.dismissDialog() }
            FilledPill(title: S.syncAccept) { sync.accept(); ui.dismissDialog() }
        }
    }
}

/// What to share with a device: links, favorites, or both. The counts say how much each holds on this device.
private struct SyncChooseDialog: View {
    @EnvironmentObject private var ui: UIState
    @ObservedObject var sync: SyncManager
    let device: SyncDevice
    @State private var links = true
    @State private var favorites = true

    var body: some View {
        let counts = sync.counts()
        let useLinks = links && counts.links > 0, useFavorites = favorites && counts.favorites > 0
        DialogCard(title: S.syncChooseTitle(device.name)) {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                Text(S.syncChooseWhat).textStyle(.bodyMedium, color: Brand.onSurfaceVariant)
                if counts.links == 0 && counts.favorites == 0 && counts.leftOut.isEmpty {
                    Text(S.syncChooseNothing).textStyle(.bodyMedium)
                } else {
                    choice(S.syncChooseLinks(counts.links), on: useLinks, enabled: counts.links > 0) { links.toggle() }
                    choice(S.syncChooseFavorites(counts.favorites, counts.bytes), on: useFavorites, enabled: counts.favorites > 0) { favorites.toggle() }
                    // Favorites that cannot be sent are named here, with the reason, before anything goes.
                    ForEach(Array(counts.leftOut.prefix(8).enumerated()), id: \.offset) { _, line in
                        Text("• \(line)").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
                    }
                    if counts.leftOut.count > 8 { Text("…and \(counts.leftOut.count - 8) more").textStyle(.bodySmall, color: Brand.onSurfaceVariant) }
                }
            }
        } actions: {
            TextPill(title: S.actionCancel, color: Brand.onSurfaceVariant) { ui.dismissDialog() }
            FilledPill(title: S.syncSend) {
                ui.dismissDialog()
                sync.send(to: device, links: useLinks, favorites: useFavorites)
            }
            .opacity(useLinks || useFavorites ? 1 : 0.4)
            .disabled(!(useLinks || useFavorites))
        }
    }

    private func choice(_ label: String, on: Bool, enabled: Bool, toggle: @escaping () -> Void) -> some View {
        Button(action: toggle) {
            HStack(spacing: Spacing.sm) {
                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? Brand.primary : Brand.onSurfaceVariant)
                Text(label).textStyle(.bodyLarge)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}
