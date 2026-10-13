import ServiceManagement
import SwiftUI

struct GeneralSettingsView: View {
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginMessage: String?
    @State private var reverting = false
    @AppStorage(DockPolicy.showInDockKey) private var showInDock = true
    let model: AppModel
    @State private var showingMutedApps = false

    var body: some View {
        @Bindable var files = model.files
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
            Section {
                LabeledContent("Save received files to") {
                    HStack {
                        Text(files.folder.lastPathComponent).foregroundStyle(.secondary)
                        Button("Choose…") { files.chooseFolder() }
                        if !files.usesDefaultFolder { Button("Use Downloads") { files.useDefaultFolder() } }
                    }
                }
                Toggle("Ask where to save each time", isOn: $files.askEachTime)
                if files.folderProblem {
                    Label("MacDroid can't reach the folder you chose for received files. Choose it again in Settings.",
                          systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("Received files")
            } footer: {
                Text("Files from your phone save automatically. Partly received files end in “.macdroid-part” until they're checked.")
                    .foregroundStyle(.secondary)
            }
            Section {
                Toggle("Show phone notifications", isOn: Binding(get: { model.showPhoneNotifications }, set: { model.setShowPhoneNotifications($0) }))
                LabeledContent("Apps") {
                    Button("Muted apps…") { showingMutedApps = true }
                }
            } header: {
                Text("Notifications")
            } footer: {
                Text("Notifications from your phone appear in Notification Center. Android hides one-time codes from apps like MacDroid.")
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showingMutedApps) { MutedAppsSheet(model: model) }
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
