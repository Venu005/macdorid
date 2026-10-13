import Foundation

/// Progress text such as "1.3 of 2.1 GB · 48 MB/s · 17 s left" (decimal units). Kotlin mirrors this; vectors: `transfer-text.json`.
public enum TransferText {
    public static func bytes(_ count: Int64) -> String {
        guard let unit = unit(for: count) else { return "\(count) bytes" }
        return "\(oneDecimal(Double(count) / unit.divisor)) \(unit.label)"
    }

    public static func line(done: Int64, total: Int64, bytesPerSecond: Double?) -> String {
        var parts = [progress(done: done, total: total)]
        if let rate = bytesPerSecond, rate > 0 {
            parts.append(speed(rate))
            let remaining = total - done
            if remaining > 0 { parts.append(timeLeft(seconds: Double(remaining) / rate)) }
        }
        return parts.joined(separator: " · ")
    }

    static func progress(done: Int64, total: Int64) -> String {
        guard let unit = unit(for: total) else { return "\(done) of \(total) bytes" }
        return "\(oneDecimal(Double(done) / unit.divisor)) of \(oneDecimal(Double(total) / unit.divisor)) \(unit.label)"
    }

    static func speed(_ rate: Double) -> String {
        if rate >= 1_000_000 {
            let value = rate / 1_000_000
            return value >= 10 ? "\(String(format: "%.0f", value)) MB/s" : "\(oneDecimal(value)) MB/s"
        }
        if rate >= 1_000 { return "\(String(format: "%.0f", rate / 1_000)) KB/s" }
        return "\(Int64(rate)) bytes/s"
    }

    static func timeLeft(seconds: Double) -> String {
        let total = Int64(seconds.rounded(.up))
        if total < 60 { return "\(total) s left" }
        if total < 3600 { return "\(Int64((Double(total) / 60).rounded(.up))) min left" }
        let minutes = Int64((Double(total % 3600) / 60).rounded(.up))
        return "\(total / 3600) h \(minutes) min left"
    }

    private static func unit(for count: Int64) -> (divisor: Double, label: String)? {
        if count >= 1_000_000_000 { return (1_000_000_000, "GB") }
        if count >= 1_000_000 { return (1_000_000, "MB") }
        if count >= 1_000 { return (1_000, "KB") }
        return nil
    }

    private static func oneDecimal(_ value: Double) -> String { String(format: "%.1f", value) }
}
