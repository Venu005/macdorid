import Logging
import SwiftUI

@main
struct MacDroidApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel

    init() {
        LoggingSystem.bootstrap(OSLogHandler.init)
        let model = AppModel()
        model.start()
        // Keep the page last asked for: clicking a notification banner also sends a reopen, which must not
        // replace the Notifications page it just asked for.
        AppDelegate.onReopen = { model.showWindow(model.selectedSection ?? .phone) }
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
