import MacDroidCore
import SwiftUI

struct PairingView: View {
    let model: AppModel

    var body: some View {
        VStack(spacing: 16) {
            if let device = model.pairedDevice {
                Label("Paired with \(device.name) (\(device.model))", systemImage: "checkmark.seal")
                Button("Forget This Phone", role: .destructive) { Task { await model.forgetPhone() } }
                Divider()
            }
            if let invite = model.invite {
                if let image = QRCode.image(for: invite.uri) {
                    Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                }
                Text("Scan with the MacDroid app on your phone").font(.callout)
                if let expires = model.inviteExpiresAt, expires > Date() {
                    Text(timerInterval: Date()...expires, countsDown: true)
                        .monospacedDigit().foregroundStyle(.secondary)
                }
                GroupBox("Can't scan? Enter these on the phone") {
                    Grid(alignment: .leading, verticalSpacing: 6) {
                        GridRow { Text("Address"); Text("\(invite.host):\(String(invite.port))").textSelection(.enabled) }
                        GridRow { Text("Code"); Text(invite.displayCode).textSelection(.enabled) }
                        GridRow { Text("Fingerprint"); Text(Fingerprint.display(invite.fingerprint)) }
                    }
                    .font(.system(.body, design: .monospaced))
                }
                Button("Cancel") { Task { await model.cancelPairing() } }
            } else {
                Button(model.pairedDevice == nil ? "Pair a Phone" : "Pair a Different Phone") {
                    Task { await model.beginPairing() }
                }
                .buttonStyle(.borderedProminent)
            }
            if let error = model.errorMessage {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
