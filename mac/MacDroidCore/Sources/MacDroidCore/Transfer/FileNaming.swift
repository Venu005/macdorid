import Foundation

/// Safe, collision-free names for received files (spec §2.3). Kotlin mirrors this; shared vectors: `file-naming.json`.
public enum FileNaming {
    public static let maxBytes = 200
    public static let partSuffix = ".macdroid-part"
    static let maxExtensionBytes = 20
    private static let unsafe: Set<Unicode.Scalar> = [":", "*", "?", "\"", "<", ">", "|"]

    public static func sanitize(_ raw: String) -> String {
        let last = raw.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        var scalars = String.UnicodeScalarView()
        for scalar in last.unicodeScalars where scalar.value >= 0x20 && scalar.value != 0x7F && !unsafe.contains(scalar) {
            scalars.append(scalar)
        }
        var name = String(scalars).trimmingCharacters(in: CharacterSet(charactersIn: " "))
        while name.hasPrefix(".") { name.removeFirst() }
        name = name.trimmingCharacters(in: CharacterSet(charactersIn: " "))
        if name.isEmpty { return "file" }
        if name.utf8.count <= maxBytes { return name }
        let (base, ext) = splitExtension(name)
        return truncateUTF8(base, maxBytes: maxBytes - ext.utf8.count) + ext
    }

    /// `name`, or `name (1)`, `name (2)`, … (before the extension), whichever `isTaken` rejects first.
    public static func unique(_ name: String, isTaken: (String) -> Bool) -> String {
        guard isTaken(name) else { return name }
        let (base, ext) = splitExtension(name)
        var counter = 1
        while true {
            let suffix = " (\(counter))"
            let candidate = truncateUTF8(base, maxBytes: maxBytes - suffix.utf8.count - ext.utf8.count) + suffix + ext
            if !isTaken(candidate) { return candidate }
            counter += 1
        }
    }

    /// Splits at the last dot. A leading dot or an extension over 20 bytes counts as no extension.
    static func splitExtension(_ name: String) -> (base: String, ext: String) {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return (name, "") }
        let ext = String(name[dot...])
        guard ext.utf8.count <= maxExtensionBytes else { return (name, "") }
        return (String(name[..<dot]), ext)
    }

    /// The longest prefix of whole Unicode scalars that fits in `maxBytes` UTF-8 bytes.
    static func truncateUTF8(_ text: String, maxBytes: Int) -> String {
        var scalars = String.UnicodeScalarView()
        var bytes = 0
        for scalar in text.unicodeScalars {
            let length = scalar.utf8.count
            if bytes + length > maxBytes { break }
            scalars.append(scalar)
            bytes += length
        }
        return String(scalars)
    }
}
