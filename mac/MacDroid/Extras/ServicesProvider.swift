import AppKit

/// Right-click → Services → "Open on Phone" (spec §4). macOS calls `openOnPhone` with the selection.
@MainActor
final class ServicesProvider: NSObject {
    private let model: AppModel

    init(model: AppModel) { self.model = model }

    @objc func openOnPhone(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) ?? ""
        model.sendLinkToPhone(text)
    }
}
