import AppKit
import SwiftUI

/// The dialer's own small window ("Call"): opened from the Phone page and the popover, remembers where it was put.
@MainActor
final class DialerWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?

    func show(_ content: some View) {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 300, height: 470),
                              styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "Call"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: content)
        window.delegate = self
        if !window.setFrameUsingName("MacDroidDialer") { window.center() }
        window.setFrameAutosaveName("MacDroidDialer")
        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    func close() { window?.close() }

    func windowWillClose(_ notification: Notification) { window = nil }
}
