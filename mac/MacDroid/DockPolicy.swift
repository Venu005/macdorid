import AppKit

/// MacDroid lives in the menu bar; it shows in the Dock and ⌘-Tab only while its window is open.
@MainActor
enum DockPolicy {
    static let showInDockKey = "showInDock"

    static var showInDock: Bool {
        UserDefaults.standard.object(forKey: showInDockKey) as? Bool ?? true
    }

    /// Switch to a regular app *before* the window opens. Switching while the window is already on screen makes
    /// AppKit re-lay it out, and the split view can lose its title-bar insets (content slides under the toolbar).
    static func prepareToShowWindow() {
        if showInDock { NSApp.setActivationPolicy(.regular) }
    }

    static func windowDidAppear() {
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.activate()
    }

    static func windowDidDisappear() {
        NSApp.setActivationPolicy(.accessory)
    }
}
