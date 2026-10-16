import AppKit
import MacDroidCore
import SwiftUI

/// Design A: device card with battery ring, Send/Open tiles, up to two transfer rows, and small Settings/Quit buttons.
/// The whole popover accepts dropped files.
struct MenuBarPopover: View {
    let model: AppModel
    @State private var drop = DropPreview()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.pairedDevice == nil {
                notPaired
            } else {
                deviceCard
                tiles
                if let call = model.calls.activeCall { activeCallRow(call) }
                if model.extras.supportsMedia && model.extras.media.active { NowPlayingCard(model: model, extras: model.extras) }
                transfers
            }
            ForEach(model.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            footer
        }
        .padding(12)
        .frame(width: 300)
        .onAppear { model.recheckReceivedFiles() } // its rows' "file is gone" state is fresh each time it opens
        .overlay { if drop.isTargeted { dropOverlay } }
        .onDrop(of: [.fileURL], delegate: PopoverDropDelegate(model: model, preview: $drop))
    }

    private var notPaired: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("No phone paired").font(.headline)
            Text("Pair your Android phone to get started.").font(.callout).foregroundStyle(.secondary)
            Button {
                model.requestPairing()
            } label: {
                Text("Pair a Phone").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var deviceCard: some View {
        let status = model.phoneStatus
        let peer: PeerInfo? = if case .connected(let peer) = model.status { peer } else { nil }
        return HStack(spacing: 12) {
            BatteryRing(level: peer?.battery, charging: peer?.charging ?? false)
            VStack(alignment: .leading, spacing: 2) {
                Text(status.title).font(.headline)
                HStack(spacing: 5) {
                    StatusDot(tone: status.tone)
                    Text(status.subtitle).font(.callout).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            if model.extras.supportsRing {
                let ringing = model.extras.ringing
                Button { ringing ? model.stopRingingPhone() : model.ringPhone() } label: {
                    Image(systemName: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                        .font(.system(size: 15))
                }
                .buttonStyle(.borderless)
                .foregroundStyle(ringing ? Color.red : Color.secondary)
                .help(ringing ? "Stop ringing" : "Ring your phone")
            }
            if model.mirror.supported {
                Button { model.startMirroring() } label: { Image(systemName: "rectangle.on.rectangle").font(.system(size: 15)) }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Mirror your phone")
            }
            if model.calls.supported {
                Button { model.showDialer() } label: { Image(systemName: "phone").font(.system(size: 15)) }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Call from your phone")
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.1)))
    }

    private var tiles: some View {
        HStack(spacing: 8) {
            PopoverTile(symbol: "arrow.up.doc", title: "Send Files", hint: model.canSendFiles ? "or drop here" : "Phone not connected",
                        enabled: model.canSendFiles) { model.pickFilesToSend() }
            PopoverTile(symbol: "macwindow", title: "Open MacDroid", hint: "⌘O") { model.showWindow(.phone) }
                .keyboardShortcut("o")
        }
    }

    private func activeCallRow(_ call: CallInfo) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "phone.fill").foregroundStyle(.green)
            Text("On a call with \(CallPresentation.title(call))").font(.callout).lineLimit(1)
            Spacer(minLength: 0)
            if let since = call.since {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(CallPresentation.duration(from: since, to: context.date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            Button("Hang up") { model.hangUpCall(call.callId) }.buttonStyle(.bordered).tint(.red).controlSize(.small)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.1)))
    }

    @ViewBuilder
    private var transfers: some View {
        let items = TransferPresentation.popoverItems(model.transfers)
        if !items.isEmpty {
            VStack(spacing: 4) {
                ForEach(items) { TransferRow(record: $0, rate: model.transfers.rates[$0.id], model: model, compact: true) }
            }
        }
    }

    private var footer: some View {
        HStack {
            Button { model.showWindow(.general) } label: { Image(systemName: "gearshape") }
                .help("Settings")
                .keyboardShortcut(",")
            Spacer()
            Button { NSApp.terminate(nil) } label: { Image(systemName: "power") }
                .help("Quit MacDroid")
                .keyboardShortcut("q")
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.secondary)
    }

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: 12)
            .fill(Color.accentColor.opacity(0.9))
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "arrow.down.doc.fill").font(.system(size: 28))
                    Text("Drop to send to \(model.phoneName)").font(.headline)
                    Text(TransferPresentation.dropSummary(count: drop.count, totalBytes: nil)).font(.callout)
                }
                .foregroundStyle(.white)
            }
            .padding(4)
            .allowsHitTesting(false)
    }
}
