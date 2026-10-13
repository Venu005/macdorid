import ServiceManagement
import SwiftUI

struct GeneralSettingsView: View {
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginMessage: String?
    @State private var reverting = false
    @AppStorage(DockPolicy.showInDockKey) private var showInDock = true

    var body: some View {
        Form {
            Section {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, enabled in
                        if reverting { reverting = false; return }
                        apply(enabled)
                    }
                if let loginMessage {
                    HStack {
                        Text(loginMessage).font(.callout).foregroundStyle(.red)
                        Spacer()
                        if SMAppService.mainApp.status == .requiresApproval {
                            Button("Open Login Items") { SMAppService.openSystemSettingsLoginItems() }
                        }
                    }
                }
                Toggle("Show MacDroid in the Dock while its window is open", isOn: $showInDock)
                    .onChange(of: showInDock) { _, _ in DockPolicy.windowDidAppear() }
            } footer: {
                Text("MacDroid always keeps running in the menu bar.").foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    /// Applies the toggle; if the system refuses, shows why and puts the toggle back to the real state.
    private func apply(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginMessage = SMAppService.mainApp.status == .requiresApproval
                ? "Approve MacDroid in System Settings → General → Login Items."
                : nil
        } catch {
            loginMessage = "Couldn't change the login item: \(error.localizedDescription)"
        }
        let actual = SMAppService.mainApp.status == .enabled
        if actual != launchAtLogin {
            reverting = true
            launchAtLogin = actual
        }
    }
}
