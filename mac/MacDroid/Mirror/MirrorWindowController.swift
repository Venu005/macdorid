import AppKit
import MacDroidCore
import SwiftUI

/// The mirror's own window (spec §6.3): phone-shaped, resizable, remembers its frame, optional "Keep on top".
@MainActor
final class MirrorWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var aspect = CGSize(width: 9, height: 20)
    private let onClose: () -> Void

    init(onClose: @escaping () -> Void) {
        self.onClose = onClose
    }

    func show(content: some View, title: String) {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 800 + MirrorContentView.barHeight),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        window.title = title
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isReleasedWhenClosed = false
        window.contentMinSize = NSSize(width: 200, height: 300)
        window.contentView = NSHostingView(rootView: content)
        window.delegate = self
        if !window.setFrameUsingName("MacDroidMirror") { window.center() }
        window.setFrameAutosaveName("MacDroidMirror")
        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    /// The phone's picture size (e.g. after rotation): turning keeps the long edge, then the window stays on screen.
    func setAspect(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        aspect = CGSize(width: width, height: height)
        guard let window else { return }
        let content = window.contentRect(forFrameRect: window.frame).size
        let next = MirrorWindowFit.content(current: content, aspect: aspect, barHeight: MirrorContentView.barHeight)
        var frame = window.frameRect(forContentRect: NSRect(origin: .zero, size: next))
        frame.origin = NSPoint(x: window.frame.minX, y: window.frame.maxY - frame.height) // keep the top-left corner
        if let visible = window.screen?.visibleFrame {
            if frame.width > visible.width || frame.height > visible.height {
                let scale = min(visible.width / frame.width, visible.height / frame.height)
                let shrunk = NSSize(width: (next.width * scale).rounded(), height: 0)
                frame = window.frameRect(forContentRect: NSRect(origin: .zero, size: fitted(shrunk)))
                frame.origin = NSPoint(x: window.frame.minX, y: window.frame.maxY - frame.height)
            }
            frame.origin.x = min(max(frame.minX, visible.minX), visible.maxX - frame.width)
            frame.origin.y = min(max(frame.minY, visible.minY), visible.maxY - frame.height)
        }
        window.setFrame(frame, display: true, animate: false)
    }

    func setKeepOnTop(_ on: Bool) { window?.level = on ? .floating : .normal }

    func close() { window?.close() }

    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        let content = sender.contentRect(forFrameRect: NSRect(origin: .zero, size: frameSize)).size
        return sender.frameRect(forContentRect: NSRect(origin: .zero, size: fitted(content))).size
    }

    func windowWillClose(_ notification: Notification) {
        window = nil
        onClose()
    }

    private func fitted(_ content: NSSize) -> NSSize {
        NSSize(width: content.width, height: (content.width * aspect.height / aspect.width).rounded() + MirrorContentView.barHeight)
    }
}
