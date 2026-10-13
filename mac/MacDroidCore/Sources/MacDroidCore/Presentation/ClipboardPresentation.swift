import Foundation

/// Text for the Clipboard page and its notification (spec §4.4).
public enum ClipboardPresentation {
    public static func preview(_ item: ClipboardItem) -> String {
        item.kind == .image ? "Image" : oneLine(item.text ?? "", limit: 120)
    }

    public static func notificationBody(_ item: ClipboardItem) -> String {
        item.kind == .image ? "Image" : oneLine(item.text ?? "", limit: 100)
    }

    public static func directionLabel(_ direction: ClipboardItem.Direction) -> String {
        direction == .toPhone ? "→ phone" : "← from phone"
    }

    public static func filter(_ items: [ClipboardItem], query: String) -> [ClipboardItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return items }
        return items.filter { $0.text?.localizedCaseInsensitiveContains(q) ?? false }
    }

    public static func pageNotice(_ state: ClipboardState) -> String? {
        state.connected && !state.phoneSupportsClipboard ? "Update MacDroid on your phone to use this." : nil
    }

    static func oneLine(_ text: String, limit: Int) -> String {
        let flat = text.split(whereSeparator: \.isNewline).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return flat.count <= limit ? flat : String(flat.prefix(limit - 1)) + "…"
    }
}
