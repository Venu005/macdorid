import AppKit
import SwiftUI

/// The menu-bar icon. Lives for the whole app session, so it also owns opening the main window.
struct MenuBarLabel: View {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Image(systemName: model.phoneStatus.symbol)
            .task {
                // A request made before this label appeared (at launch) must not be lost.
                if model.windowRequest != nil {
                    DockPolicy.prepareToShowWindow()
                    openWindow(id: MainWindow.id)
                    NSApp.activate()
                }
            }
            .onChange(of: model.windowRequest) { _, request in
                guard request != nil else { return }
                DockPolicy.prepareToShowWindow()
                openWindow(id: MainWindow.id)
                NSApp.activate()
            }
    }
}
