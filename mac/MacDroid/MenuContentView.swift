import AppKit
import MacDroidCore
import SwiftUI

struct MenuContentView: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MacDroid").font(.headline)
            statusRow
            if let error = model.errorMessage {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            Divider()
            HStack {
                SettingsLink {
                    Text(model.pairedDevice == nil ? "Pair a Phone…" : "Settings…")
                }
                .simultaneousGesture(TapGesture().onEnded { NSApp.activate() })
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 300)
    }

    @ViewBuilder private var statusRow: some View {
        switch (model.pairedDevice, model.status) {
        case (nil, _):
            Label("No phone paired", systemImage: "iphone.slash")
        case (_, .connected(let peer)):
            Label {
                VStack(alignment: .leading) {
                    Text(peer.device)
                    if let battery = peer.battery {
                        Text("Battery \(battery)%\(peer.charging == true ? " · charging" : "")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            } icon: {
                Image(systemName: "iphone.radiowaves.left.and.right")
            }
        case (let device?, .disconnected):
            Label("\(device.name) — not connected", systemImage: "iphone")
        }
    }
}
