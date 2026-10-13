import SwiftUI

@main
struct MacDroidApp: App {
    @State private var model: AppModel

    init() {
        let model = AppModel()
        model.start()
        _model = State(initialValue: model)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContentView(model: model)
        } label: {
            Image(systemName: model.statusSymbol)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
        }
    }
}
