import AppKit
import MacDroidCore
import SwiftUI

struct SecuritySettingsView: View {
    let fingerprint: String

    var body: some View {
        Form {
            Section("This Mac's fingerprint") {
                HStack {
                    Text(Fingerprint.display(fingerprint))
                        .font(.system(.title3, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(fingerprint, forType: .string)
                    }
                }
                Text("Your phone checks this fingerprint every time it connects. If you pair manually, compare it with the one your phone shows.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}
