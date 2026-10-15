import AppKit
import SwiftUI

/// The floating call panel (spec §3.2): top right, above other windows, never takes focus from the app you're in.
@MainActor
final class CallPanelController {
    private var panel: NSPanel?
    private static let size = NSSize(width: 320, height: 104)

    func show(_ content: some View) {
        let panel = panel ?? makePanel()
        panel.contentView = NSHostingView(rootView: content)
        if let screen = NSScreen.main?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: screen.maxX - Self.size.width - 16, y: screen.maxY - Self.size.height - 16))
        }
        panel.orderFrontRegardless()
        self.panel = panel
    }

    func hide() { panel?.orderOut(nil) }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: Self.size),
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
