import CoreGraphics

/// Mac view coordinates → the phone picture (spec §6.5). Points are top-left origin (the input view is flipped).
public enum InputMapper {
    /// The point as 0–1 fractions of the picture drawn aspect-fit in the view, or nil in the black bars (Review Focus #4).
    public static func fraction(of point: CGPoint, in view: CGSize, picture: CGSize) -> CGPoint? {
        guard view.width > 0, view.height > 0, picture.width > 0, picture.height > 0 else { return nil }
        let scale = min(view.width / picture.width, view.height / picture.height)
        let shown = CGSize(width: picture.width * scale, height: picture.height * scale)
        let origin = CGPoint(x: (view.width - shown.width) / 2, y: (view.height - shown.height) / 2)
        let x = (point.x - origin.x) / shown.width
        let y = (point.y - origin.y) / shown.height
        guard (0...1).contains(x), (0...1).contains(y) else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// How far one mouse-wheel line scrolls, in view points (final review C1: one notch is a real swipe on the phone).
    public static let pointsPerLine: CGFloat = 40

    /// Scroll deltas (content direction, as macOS reports them with natural scrolling) as fractions of the view.
    /// `precise` is false for a mouse wheel, which reports lines rather than points.
    public static func scroll(deltaX: CGFloat, deltaY: CGFloat, precise: Bool = true, in view: CGSize) -> (dx: Double, dy: Double) {
        guard view.width > 0, view.height > 0 else { return (0, 0) }
        let scale = precise ? 1 : pointsPerLine
        return (Double(deltaX * scale / view.width), Double(deltaY * scale / view.height))
    }
}

/// Drags are sent at most 120 times a second; down and up always go (spec §6.5).
public struct MoveThrottle: Sendable {
    public static let intervalMillis: Int64 = 8
    private var last: Int64?
    public init() {}

    public mutating func allows(atMillis now: Int64) -> Bool {
        if let last, now - last < Self.intervalMillis { return false }
        last = now
        return true
    }
}

public enum MirrorKeyAction: Equatable, Sendable {
    case text(String)
    case key(InputKeyName)
    case paste
}

/// Mac keys → phone input (spec §6.5). Other ⌘ shortcuts and arrow/function keys stay on the Mac.
public enum MirrorKeys {
    public static func action(keyCode: UInt16, characters: String?, command: Bool) -> MirrorKeyAction? {
        if command { return keyCode == 9 ? .paste : nil } // ⌘V
        switch keyCode {
        case 36, 76: return .text("\n") // Return, Enter
        case 51: return .key(.backspace) // Delete
        case 53: return .key(.back) // Esc
        default: break
        }
        guard let characters, !characters.isEmpty,
              characters.unicodeScalars.allSatisfy({ !(0xF700...0xF8FF).contains($0.value) && $0.value >= 0x20 && $0.value != 0x7F })
        else { return nil }
        return .text(characters)
    }
}
