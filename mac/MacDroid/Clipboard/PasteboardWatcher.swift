import AppKit
import MacDroidCore

/// Polls the general pasteboard twice a second (spec §4.1) and reports changes the rules allow.
/// Reading is cheap: only `changeCount` is read until it changes.
@MainActor
final class PasteboardWatcher {
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?
    private var onChange: ((ClipboardContent, Int) -> Void)?

    func start(onChange: @escaping (ClipboardContent, Int) -> Void) {
        guard timer == nil else { return }
        self.onChange = onChange
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    private func poll() {
        let pasteboard = NSPasteboard.general
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        switch ClipboardRules.kind(of: (pasteboard.types ?? []).map(\.rawValue)) {
        case .ignored:
            return
        case .text:
            if let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) { onChange?(.text(text), count) }
        case .image:
            if let png = Self.png(from: pasteboard) { onChange?(.image(png), count) }
        }
    }

    static func png(from pasteboard: NSPasteboard) -> Data? {
        if let png = pasteboard.data(forType: .png) { return png }
        guard let tiff = pasteboard.data(forType: .tiff), let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }

    /// Puts an item on the pasteboard and returns the change count it produced (echo prevention, spec §8.2).
    static func write(_ content: ClipboardContent) -> Int {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        switch content {
        case .text(let text): pasteboard.setString(text, forType: .string)
        case .image(let png): pasteboard.setData(png, forType: .png)
        }
        return pasteboard.changeCount
    }
}
