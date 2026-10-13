import SwiftUI

@main
struct MacDroidApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel

    init() {
        let model = AppModel()
        model.start()
        AppDelegate.onReopen = { model.showWindow(.phone) }
        AppDelegate.onLaunch = {
            if AppDelegate.shouldOpenWindowAtLaunch(paired: model.pairedDevice != nil) { model.showWindow(.phone) }
        }
        _model = State(initialValue: model)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarPopover(model: model)
        } label: {
            MenuBarLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("MacDroid", id: MainWindow.id) {
            MainWindow(model: model)
        }
        .defaultSize(width: 720, height: 480)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") { model.showWindow(.general) }
                    .keyboardShortcut(",", modifiers: .command)
            }
        }
    }
}
