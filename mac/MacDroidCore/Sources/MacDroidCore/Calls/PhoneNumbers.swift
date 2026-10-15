import Foundation

/// Phone numbers for `call.dial` (spec §3.1, §4): a leading `+`, digits, `*` and `#`; 1–32 characters.
public enum PhoneNumbers {
    private static let keypad: [Character: Character] = {
        var map: [Character: Character] = [:]
        for (digit, letters) in [("2", "ABC"), ("3", "DEF"), ("4", "GHI"), ("5", "JKL"), ("6", "MNO"), ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")] {
            for letter in letters { map[letter] = Character(digit) }
        }
        return map
    }()

    public static func clean(_ raw: String) -> String? {
        var out = ""
        for ch in raw {
            if ch.isASCII, ch.isNumber || ch == "*" || ch == "#" { out.append(ch) }
            else if ch == "+", out.isEmpty { out.append(ch) }
        }
        guard out != "+", !out.isEmpty, out.count <= 32 else { return nil }
        return out
    }

    /// The number in a `tel:` link: `tel:+1…`, `tel://…`, percent-encoded, with `;params` dropped. Links carrying `*` or `#`
    /// are refused: a web page must not run a phone code such as call forwarding (final review I3).
    public static func fromTelURL(_ url: URL) -> String? {
        guard url.scheme?.lowercased() == "tel" else { return nil }
        var rest = String(url.absoluteString.dropFirst("tel:".count))
        if rest.hasPrefix("//") { rest.removeFirst(2) }
        rest = String(rest.prefix { $0 != ";" && $0 != "?" })
        let decoded = rest.removingPercentEncoding ?? rest
        guard !decoded.contains("*"), !decoded.contains("#") else { return nil }
        // A link is all number, so letters are a vanity number (1-800-FLOWERS): spell them on the keypad (minor 4).
        return clean(String(decoded.map { keypad[Character($0.uppercased())] ?? $0 }))
    }
}
