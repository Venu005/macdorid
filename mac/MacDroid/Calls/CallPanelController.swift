import AppKit
import SwiftUI

/// The floating call panel (spec §3.2): top right, above other windows, never takes focus from the app you're in.
@MainActor
final class CallPanelController {
    private var panel: NSPanel?
    private static let size = NSSize(width: 320, height: 104)

    /// The view is set once: it observes the model, and replacing it mid-click could drop the click (minor 6).
    func show(_ content: @autoclosure () -> some View) {
        let panel = panel ?? makePanel()
        if panel.contentView == nil || self.panel == nil { panel.contentView = NSHostingView(rootView: content()) }
        if let screen = NSScreen.main?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: screen.maxX - Self.size.width - 16, y: screen.maxY - Self.size.height - 16))
        }
        panel.orderFrontRegardless()
        self.panel = panel
    }

    func hide() { panel?.orderOut(nil) }

    private func makePanel() -> NSPanel {
        let panel = CallPanel(contentRect: NSRect(origin: .zero, size: Self.size),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        return panel
    }
}

/// A borderless panel can't become key by default; this one can (without activating MacDroid), so its buttons always
/// take clicks and show their colours (minor 6).
private final class CallPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}
