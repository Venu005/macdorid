import MacDroidCore
import ServiceManagement
import SwiftUI

struct SettingsView: View {
    let model: AppModel

    var body: some View {
        TabView {
            PairingView(model: model)
                .tabItem { Label("Pairing", systemImage: "qrcode") }
            GeneralSettingsView(model: model)
                .tabItem { Label("General", systemImage: "gearshape") }
        }
        .frame(width: 460, height: 560)
    }
}

struct GeneralSettingsView: View {
    let model: AppModel
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var errorText: String?

    var body: some View {
        Form {
            Toggle("Launch MacDroid at login", isOn: $launchAtLogin)
                .onChange(of: launchAtLogin) { _, enabled in
                    do {
                        if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                        errorText = nil
                    } catch {
                        errorText = error.localizedDescription
                    }
                }
            if let errorText { Text(errorText).foregroundStyle(.red) }
            LabeledContent("This Mac's fingerprint", value: Fingerprint.display(model.fingerprint))
                .font(.system(.body, design: .monospaced))
        }
        .padding()
    }
}
