import AppKit

/// MacDroid lives in the menu bar; it shows in the Dock and ⌘-Tab only while its window is open.
@MainActor
enum DockPolicy {
    static let showInDockKey = "showInDock"

    static var showInDock: Bool {
        UserDefaults.standard.object(forKey: showInDockKey) as? Bool ?? true
    }

    static func windowDidAppear() {
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.activate()
    }

    static func windowDidDisappear() {
        NSApp.setActivationPolicy(.accessory)
    }
}
