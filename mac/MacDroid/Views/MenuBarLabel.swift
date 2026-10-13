import AppKit
import SwiftUI

/// The menu-bar icon. Lives for the whole app session, so it also owns opening the main window.
struct MenuBarLabel: View {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Image(systemName: model.phoneStatus.symbol)
            .task {
                if AppDelegate.shouldOpenWindowAtLaunch(paired: model.pairedDevice != nil) {
                    openWindow(id: MainWindow.id)
                }
            }
            .onChange(of: model.windowRequest) { _, request in
                guard request != nil else { return }
                openWindow(id: MainWindow.id)
                NSApp.activate()
            }
    }
}
