import Foundation

/// What's typed in the dialer window: digits, `*`, `#`, and `+` only at the start; at most 32 characters (spec §4).
public struct DialInput: Equatable, Sendable {
    public static let maxLength = 32
    public private(set) var number: String

    public init(_ number: String = "") { self.number = number }

    /// Keys or keyboard typing; anything that isn't a dial character is ignored.
    public mutating func type(_ text: String) {
        for ch in text where number.count < Self.maxLength {
            if ch.isASCII, ch.isNumber || ch == "*" || ch == "#" { number.append(ch) }
            else if ch == "+", number.isEmpty { number.append(ch) }
        }
    }

    /// ⌘V: the number found in the pasted text replaces what's there; nothing usable leaves it alone.
    public mutating func paste(_ text: String) {
        if let cleaned = PhoneNumbers.clean(text) { number = cleaned }
    }

    public mutating func deleteLast() {
        if !number.isEmpty { number.removeLast() }
    }

    public mutating func clear() { number = "" }

    public var canCall: Bool { PhoneNumbers.clean(number) != nil }
}
