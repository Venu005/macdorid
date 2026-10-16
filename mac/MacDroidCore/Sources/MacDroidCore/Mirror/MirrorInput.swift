import CoreGraphics

/// Mac view coordinates → the phone picture (spec §6.5). Points are top-left origin (the input view is flipped).
public enum InputMapper {
    /// Where the picture is drawn aspect-fit and centred in the view; `.zero` when either size is empty.
    public static func pictureRect(in view: CGSize, picture: CGSize) -> CGRect {
        guard view.width > 0, view.height > 0, picture.width > 0, picture.height > 0 else { return .zero }
        let scale = min(view.width / picture.width, view.height / picture.height)
        let shown = CGSize(width: picture.width * scale, height: picture.height * scale)
        return CGRect(x: (view.width - shown.width) / 2, y: (view.height - shown.height) / 2, width: shown.width, height: shown.height)
    }

    /// The point as 0–1 fractions of the picture drawn aspect-fit in the view, or nil in the black bars (Review Focus #4).
    public static func fraction(of point: CGPoint, in view: CGSize, picture: CGSize) -> CGPoint? {
        let rect = pictureRect(in: view, picture: picture)
        guard !rect.isEmpty else { return nil }
        let x = (point.x - rect.minX) / rect.width
        let y = (point.y - rect.minY) / rect.height
        guard (0...1).contains(x), (0...1).contains(y) else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// How far one mouse-wheel line scrolls, in view points (final review C1: one notch is a real swipe on the phone).
    public static let pointsPerLine: CGFloat = 40

    /// Scroll deltas (content direction, as macOS reports them with natural scrolling) as fractions of the picture as
    /// drawn (the whole view when `picture` is nil). `precise` is false for a mouse wheel, which reports lines rather than points.
    public static func scroll(deltaX: CGFloat, deltaY: CGFloat, precise: Bool = true, in view: CGSize, picture: CGSize? = nil) -> (dx: Double, dy: Double) {
        let area = picture.map { pictureRect(in: view, picture: $0).size } ?? view
        guard area.width > 0, area.height > 0 else { return (0, 0) }
        let scale = precise ? 1 : pointsPerLine
        return (Double(deltaX * scale / area.width), Double(deltaY * scale / area.height))
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

/// The mirror window's content size for a picture shape (Plan 7 minor #4): turning between portrait and landscape keeps
/// the picture's long edge, so a portrait window doesn't become a short strip; otherwise the width stays.
public enum MirrorWindowFit {
    public static func content(current: CGSize, aspect: CGSize, barHeight: CGFloat) -> CGSize {
        guard aspect.width > 0, aspect.height > 0 else { return current }
        let picture = CGSize(width: current.width, height: max(current.height - barHeight, 1))
        let wasLandscape = picture.width > picture.height
        let isLandscape = aspect.width > aspect.height
        let longEdge = max(picture.width, picture.height)
        let width: CGFloat
        if wasLandscape == isLandscape {
            width = current.width
        } else if isLandscape {
            width = longEdge
        } else {
            width = longEdge * aspect.width / aspect.height // the long edge becomes the height
        }
        return CGSize(width: width, height: (width * aspect.height / aspect.width).rounded() + barHeight)
    }
}
