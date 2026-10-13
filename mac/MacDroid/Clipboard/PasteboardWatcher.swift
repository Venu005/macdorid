import AppKit
import MacDroidCore

/// Polls the general pasteboard twice a second (spec §4.1) and reports changes the rules allow.
/// Reading is cheap: only `changeCount` is read until it changes.
@MainActor
final class PasteboardWatcher {
    private var tracker = PasteboardChangeTracker(current: NSPasteboard.general.changeCount)
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
        guard tracker.isNewChange(count) else { return }
        switch ClipboardRules.kind(of: (pasteboard.types ?? []).map(\.rawValue)) {
        case .ignored:
            return
        case .text:
            if let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) { onChange?(.text(text), count) }
        case .image:
            if let png = pasteboard.data(forType: .png) {
                onChange?(.image(png), count)
            } else if let tiff = pasteboard.data(forType: .tiff) {
                // Converting a large TIFF can take a while: do it off the main thread, then report.
                Task { [weak self] in
                    guard let png = await Task.detached(priority: .userInitiated, operation: { Self.png(fromTIFF: tiff) }).value else { return }
                    self?.onChange?(.image(png), count)
                }
            }
        }
    }

    nonisolated static func png(fromTIFF tiff: Data) -> Data? {
        NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
    }

    /// Puts an item on the pasteboard and returns the change count it produced (echo prevention, spec §8.2).
    /// The watcher records it at once, so it never reports this write as a copy.
    func write(_ content: ClipboardContent) -> Int {
        let count = Self.write(content)
        tracker.wrote(count)
        return count
    }

    private static func write(_ content: ClipboardContent) -> Int {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        switch content {
        case .text(let text): pasteboard.setString(text, forType: .string)
        case .image(let png): pasteboard.setData(png, forType: .png)
        }
        return pasteboard.changeCount
    }
}
