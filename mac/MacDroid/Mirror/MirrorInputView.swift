import AppKit
import MacDroidCore
import SwiftUI

/// Catches mouse, scroll and keys over the mirrored picture and turns them into `input.*` (spec §6.5).
final class MirrorInputNSView: NSView, @MainActor NSTextInputClient {
    var picture: CGSize = .zero
    var enabled = false {
        didSet {
            // Control turned off mid-drag: lift the finger rather than leave it down (Plan 8 minor #6).
            if !enabled, down {
                down = false
                send(.inputPointer(InputPointer(action: .cancel, x: 0, y: 0, t: Int64(ProcessInfo.processInfo.systemUptime * 1000))))
            }
        }
    }
    var send: (Message) -> Void = { _ in }
    var key: (InputKeyName) -> Void = { _ in }
    var type: (String) -> Void = { _ in }
    var paste: () -> Void = {}
    private var throttle = MoveThrottle()
    private var scroll: (x: Double, y: Double, dx: Double, dy: Double)?
    private var scrollTimer: Timer?
    private var down = false

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.makeFirstResponder(self)
    }

    /// The dot shows only where a click reaches the phone (Plan 8 minor #5).
    override func resetCursorRects() {
        guard enabled else { return }
        addCursorRect(InputMapper.pictureRect(in: bounds.size, picture: picture), cursor: Self.dot)
    }

    override func mouseDown(with event: NSEvent) { pointer(.down, event) }
    override func mouseDragged(with event: NSEvent) { pointer(.move, event) }
    override func mouseUp(with event: NSEvent) { pointer(.up, event) }
    override func rightMouseDown(with event: NSEvent) { if enabled { key(.back) } }

    override func scrollWheel(with event: NSEvent) {
        guard enabled, let at = fraction(event) else { return }
        let delta = InputMapper.scroll(deltaX: event.scrollingDeltaX, deltaY: event.scrollingDeltaY,
                                       precise: event.hasPreciseScrollingDeltas, in: bounds.size, picture: picture)
        if let pending = scroll {
            scroll = (pending.x, pending.y, pending.dx + delta.dx, pending.dy + delta.dy)
        } else {
            scroll = (at.x, at.y, delta.dx, delta.dy)
        }
        guard scrollTimer == nil else { return }
        // Accumulated and sent every 33 ms (one swipe per batch).
        scrollTimer = Timer.scheduledTimer(withTimeInterval: 0.033, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let s = self.scroll else { return }
                self.scroll = nil
                self.scrollTimer = nil
                self.send(.inputScroll(InputScroll(x: s.x, y: s.y, dx: s.dx, dy: s.dy)))
            }
        }
    }

    override func keyDown(with event: NSEvent) {
        guard enabled else {
            super.keyDown(with: event)
            return
        }
        let composing = marked != nil
        let action = MirrorKeys.action(keyCode: event.keyCode, characters: event.characters,
                                       command: event.modifierFlags.contains(.command),
                                       fullScreen: window?.styleMask.contains(.fullScreen) == true)
        switch action {
        case .key, .leaveFullScreen, .swallow, .paste, .text("\n"):
            // While an input method is composing, its own keys (Return to commit, Esc to cancel, arrows) go to it.
            if composing, action != .paste {
                _ = inputContext?.handleEvent(event)
                return
            }
        case .text, nil:
            // Printable text goes through the input method, so dead keys (⌥E E → é) and IMEs compose first (Plan 8 minor #7).
            if action == nil, event.modifierFlags.contains(.command) {
                super.keyDown(with: event) // other ⌘ shortcuts: the menu
                return
            }
            _ = inputContext?.handleEvent(event)
            return
        }
        switch action {
        case .text(let text): type(text)
        case .key(let name): key(name)
        case .paste: paste()
        case .leaveFullScreen: window?.toggleFullScreen(nil)
        case .swallow, nil: break
        }
    }

    // MARK: NSTextInputClient — only committed text is sent; marked (composing) text stays here.

    private var marked: NSAttributedString?

    func insertText(_ string: Any, replacementRange: NSRange) {
        marked = nil
        let text = (string as? NSAttributedString)?.string ?? (string as? String) ?? ""
        guard enabled, !text.isEmpty else { return }
        if case .text(let typed)? = MirrorKeys.action(keyCode: UInt16.max, characters: text, command: false) { type(typed) }
    }

    func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        let text = (string as? NSAttributedString) ?? NSAttributedString(string: (string as? String) ?? "")
        marked = text.length > 0 ? text : nil
    }

    func unmarkText() { marked = nil }
    func hasMarkedText() -> Bool { marked != nil }
    func markedRange() -> NSRange { marked.map { NSRange(location: 0, length: $0.length) } ?? NSRange(location: NSNotFound, length: 0) }
    func selectedRange() -> NSRange { NSRange(location: marked?.length ?? 0, length: 0) }
    func validAttributesForMarkedText() -> [NSAttributedString.Key] { [] }
    func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? { nil }
    func characterIndex(for point: NSPoint) -> Int { NSNotFound }

    /// Where the input method's candidate window goes: just below the picture's centre.
    func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect {
        let rect = InputMapper.pictureRect(in: bounds.size, picture: picture)
        let local = NSRect(x: rect.midX, y: rect.midY, width: 1, height: 20)
        guard let window else { return .zero }
        return window.convertToScreen(convert(local, to: nil))
    }

    override func doCommand(by selector: Selector) {} // no beeps for keys the input method passes back

    private func pointer(_ action: PointerAction, _ event: NSEvent) {
        guard enabled else { return }
        window?.makeFirstResponder(self)
        let millis = Int64(event.timestamp * 1000)
        guard let at = fraction(event) else {
            // Left the picture mid-drag: lift the finger rather than leave it down.
            if down, action != .down {
                down = false
                send(.inputPointer(InputPointer(action: .cancel, x: 0, y: 0, t: millis)))
            }
            return
        }
        switch action {
        case .down: down = true
        case .up, .cancel: down = false
        case .move:
            guard down, throttle.allows(atMillis: millis) else { return }
        }
        send(.inputPointer(InputPointer(action: action, x: at.x, y: at.y, t: millis)))
    }

    private func fraction(_ event: NSEvent) -> CGPoint? {
        InputMapper.fraction(of: convert(event.locationInWindow, from: nil), in: bounds.size, picture: picture)
    }

    /// A small white dot with a dark ring: "you're touching the phone here".
    static let dot: NSCursor = {
        let size = NSSize(width: 14, height: 14)
        let image = NSImage(size: size, flipped: false) { rect in
            let circle = NSBezierPath(ovalIn: rect.insetBy(dx: 2, dy: 2))
            NSColor.white.withAlphaComponent(0.9).setFill()
            circle.fill()
            NSColor.black.withAlphaComponent(0.6).setStroke()
            circle.lineWidth = 1.5
            circle.stroke()
            return true
        }
        return NSCursor(image: image, hotSpot: NSPoint(x: 7, y: 7))
    }()
}

/// SwiftUI wrapper over the picture.
struct MirrorInputView: NSViewRepresentable {
    let model: AppModel

    func makeNSView(context: Context) -> MirrorInputNSView {
        let view = MirrorInputNSView()
        update(view)
        return view
    }

    func updateNSView(_ view: MirrorInputNSView, context: Context) { update(view) }

    private func update(_ view: MirrorInputNSView) {
        if case .streaming(let width, let height, _) = model.mirror.phase {
            view.picture = CGSize(width: width, height: height)
        }
        view.send = { [model] in model.sendMirrorInput($0) }
        view.enabled = model.mirror.controlAvailable
        view.key = { [model] in model.sendMirrorKey($0) }
        view.type = { [model] in model.typeOnPhone($0) }
        view.paste = { [model] in model.pasteOnPhone() }
        view.window?.invalidateCursorRects(for: view)
    }
}
