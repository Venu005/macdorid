import MacDroidCore
import SwiftUI

struct PairedDeviceView: View {
    let model: AppModel
    @State private var confirmForget = false

    var body: some View {
        let status = model.phoneStatus
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 30))
                        .frame(width: 64, height: 64)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 14))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(status.title).font(.title2.weight(.semibold))
                        HStack(spacing: 6) {
                            StatusDot(tone: status.tone)
                            Text(status.tone == .connected ? "Connected over Wi-Fi" : "Not connected — waiting for your phone")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 6)
            }
            Section("Details") {
                LabeledContent("Battery", value: StatusPresentation.batteryText(model.status))
                if let line = NotificationPresentation.phonePageLine(model.notifications) {
                    LabeledContent("Notifications", value: line)
                }
                if model.mirror.connected && !model.mirror.supported {
                    LabeledContent("Mirroring", value: "Update MacDroid on your phone to use this.")
                }
                if model.photos.supported, let line = PhotosPresentation.phoneLine(model.photos.access) {
                    LabeledContent("Photos", value: line)
                }
                if model.notifications.phoneAccess == false {
                    Text("Media controls need notification access on your phone.").font(.callout).foregroundStyle(.secondary)
                }
                if let device = model.pairedDevice {
                    LabeledContent("Model", value: device.model)
                    LabeledContent("Paired", value: device.pairedAt.formatted(date: .abbreviated, time: .omitted))
                }
            }
            Section {
                HStack {
                    if model.extras.supportsRing {
                        Button(model.extras.ringing ? "Stop Ringing" : "Ring Phone") {
                            model.extras.ringing ? model.stopRingingPhone() : model.ringPhone()
                        }
                    }
                    if model.mirror.supported {
                        Button("Mirror") { model.startMirroring() }
                    }
                    if model.calls.supported {
                        Button("Call…") { model.showDialer() }
                    }
                    Button("Pair a Different Phone…") { Task { await model.startRepair() } }
                    Spacer()
                    Button("Forget This Phone…", role: .destructive) { confirmForget = true }
                }
            }
        }
        .formStyle(.grouped)
        .alert("Forget \(model.pairedDevice?.name ?? "this phone")?", isPresented: $confirmForget) {
            Button("Forget", role: .destructive) { Task { await model.forgetPhone() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The phone will be disconnected and will need to be paired again.")
        }
    }
}
