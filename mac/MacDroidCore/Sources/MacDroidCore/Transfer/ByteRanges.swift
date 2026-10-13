/// Parses the two range headers MacDroid uses (spec §2.2).
public enum ByteRanges {
    /// `Range: bytes=N-` → N. Other range forms aren't used by MacDroid and return nil.
    public static func parseRangeStart(_ header: String) -> Int64? {
        guard header.hasPrefix("bytes="), header.hasSuffix("-") else { return nil }
        let number = header.dropFirst("bytes=".count).dropLast()
        guard let start = Int64(number), start >= 0 else { return nil }
        return start
    }

    /// `Content-Range: bytes N-M/T` → (N, M, T), requiring 0 ≤ N ≤ M < T.
    public static func parseContentRange(_ header: String) -> (start: Int64, end: Int64, total: Int64)? {
        guard header.hasPrefix("bytes ") else { return nil }
        let parts = header.dropFirst("bytes ".count).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let total = Int64(parts[1]) else { return nil }
        let bounds = parts[0].split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              start >= 0, start <= end, end < total else { return nil }
        return (start, end, total)
    }
}
