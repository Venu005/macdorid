import MacDroidCore
import SwiftUI

struct PairingView: View {
    let model: AppModel
    @State private var showManual = false

    var body: some View {
        VStack(spacing: 14) {
            if let error = model.transientError { BannerView(kind: .error, message: error) }
            Spacer(minLength: 0)
            Text(model.isRepairing ? "Pair a different phone" : "Pair your Android phone")
                .font(.title2.weight(.semibold))
            Text("Open MacDroid on your phone and scan this code")
                .foregroundStyle(.secondary)
            codeArea
            if model.isRepairing {
                Button("Cancel") { Task { await model.cancelPairing() } }
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Re-runs once the server has a port, so a window opened during startup still gets a code.
        .task(id: model.listeningPort) { await model.ensurePairingCode() }
    }

    @ViewBuilder
    private var codeArea: some View {
        if model.usingTemporaryIdentity {
            unavailable
        } else if let invite = model.invite, let expires = model.inviteExpiresAt {
            activeCode(invite, expires: expires)
        } else if model.awaitingPairRequest {
            VStack(spacing: 10) {
                Image(systemName: "iphone.gen3").font(.system(size: 44)).foregroundStyle(.secondary)
                Text("Phone forgotten").font(.headline)
                Button("Pair a Phone") { Task { await model.newCode() } }
                    .buttonStyle(.borderedProminent)
            }
            .frame(width: 240, height: 240)
        } else if model.pairingExpired {
            expired
        } else if model.transientError != nil {
            VStack(spacing: 10) {
                Image(systemName: "wifi.exclamationmark").font(.system(size: 44)).foregroundStyle(.secondary)
                Button("Try again") { Task { await model.newCode() } }
                    .buttonStyle(.borderedProminent)
            }
            .frame(width: 240, height: 240)
        } else {
            ProgressView().controlSize(.large).frame(width: 240, height: 240)
        }
    }

    private func activeCode(_ invite: PairingInvite, expires: Date) -> some View {
        VStack(spacing: 10) {
            QRCodeImage(text: invite.uri).frame(width: 240, height: 240)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                HStack(spacing: 8) {
                    Text("Expires in \(StatusPresentation.countdown(remaining: expires.timeIntervalSince(context.date)))")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                    Button("New code") { Task { await model.newCode() } }
                        .buttonStyle(.link)
                }
            }
            DisclosureGroup("Can't scan?", isExpanded: $showManual) {
                Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                    GridRow {
                        Text("Address").foregroundStyle(.secondary)
                        Text("\(invite.host):\(String(invite.port))").textSelection(.enabled)
                    }
                    GridRow {
                        Text("Code").foregroundStyle(.secondary)
                        Text(invite.displayCode).textSelection(.enabled)
                    }
                    GridRow {
                        Text("Fingerprint").foregroundStyle(.secondary)
                        Text(Fingerprint.display(invite.fingerprint))
                    }
                }
                .font(.system(.body, design: .monospaced))
                .padding(.top, 6)
            }
            .frame(width: 360)
        }
    }

    private var expired: some View {
        VStack(spacing: 10) {
            Image(systemName: "clock.badge.xmark").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("Code expired").font(.headline)
            Button("New code") { Task { await model.newCode() } }
                .buttonStyle(.borderedProminent)
        }
        .frame(width: 240, height: 240)
    }

    private var unavailable: some View {
        VStack(spacing: 10) {
            Image(systemName: "lock.trianglebadge.exclamationmark").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("Pairing unavailable").font(.headline)
            Text("MacDroid couldn't open its keychain, so a pairing wouldn't survive a restart. Quit and reopen MacDroid to try again.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 360)
        }
        .frame(minHeight: 240)
    }
}
