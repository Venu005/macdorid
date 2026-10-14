import Foundation

public struct PhotoDay: Identifiable, Equatable, Sendable {
    /// yyyy-MM-dd in the calendar's time zone.
    public let id: String
    public let title: String
    public var items: [PhotoItem]
}

/// Text and grouping for the Mac Photos page (spec §5).
public enum PhotosPresentation {
    /// Groups newest-first items by day: "Today", "Yesterday", "12 Oct", or "12 Oct 2025" for another year.
    public static func days(_ items: [PhotoItem], now: Date, calendar: Calendar = .current, locale: Locale = .current) -> [PhotoDay] {
        let titles = DayTitles(now: now, calendar: calendar, locale: locale)
        var result: [PhotoDay] = []
        for item in items {
            let date = Date(timeIntervalSince1970: TimeInterval(item.takenAt) / 1000)
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            let key = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
            if result.last?.id == key {
                result[result.count - 1].items.append(item)
            } else {
                result.append(PhotoDay(id: key, title: titles.title(for: date), items: [item]))
            }
        }
        return result
    }

    /// Two formatters per call, not one per day: the grid regroups on every update.
    private struct DayTitles {
        let now: Date
        let calendar: Calendar
        let sameYear: DateFormatter
        let otherYear: DateFormatter

        init(now: Date, calendar: Calendar, locale: Locale) {
            self.now = now
            self.calendar = calendar
            func formatter(_ template: String) -> DateFormatter {
                let f = DateFormatter()
                f.calendar = calendar
                f.timeZone = calendar.timeZone
                f.locale = locale
                f.setLocalizedDateFormatFromTemplate(template)
                return f
            }
            sameYear = formatter("dMMM")
            otherYear = formatter("dMMMyyyy")
        }

        func title(for date: Date) -> String {
            if calendar.isDate(date, inSameDayAs: now) { return "Today" }
            if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) { return "Yesterday" }
            let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
            return (sameYear ? self.sameYear : otherYear).string(from: date)
        }
    }

    /// "0:42", or "1:02:03" past an hour.
    public static func duration(ms: Int64) -> String {
        let total = max(0, ms / 1000)
        let hours = total / 3600, minutes = (total % 3600) / 60, seconds = total % 60
        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds) : String(format: "%d:%02d", minutes, seconds)
    }

    public static func selection(count: Int, bytes: Int64) -> String {
        "\(count) selected · \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))"
    }

    public static func banner(access: PhotoAccess?, visibleCount: Int) -> String? {
        switch access {
        case .partial:
            "MacDroid can see \(visibleCount) selected \(visibleCount == 1 ? "photo" : "photos"). To see all, open MacDroid on your phone → Settings → Photos."
        case .some(PhotoAccess.none):
            "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos."
        default:
            nil
        }
    }

    /// The Phone page's "Photos" line.
    public static func phoneLine(_ access: PhotoAccess?) -> String? {
        switch access {
        case .full: "All"
        case .partial: "Selected"
        case .some(PhotoAccess.none): "Off on phone"
        default: nil
        }
    }

    public static func message(_ error: PhotoFetchError) -> String {
        switch error {
        case .gone: "This photo is no longer on your phone"
        case .denied: "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos."
        case .noResponse: "Your phone didn't respond"
        case .notConnected: "Phone not connected"
        case .unsupported: "Update MacDroid on your phone to use this."
        case .failed: "Couldn't get this photo from your phone"
        }
    }
}

/// Finder-style selection over the loaded items (spec §5.2): click, ⌘-click and ⇧-click.
public enum PhotoSelection {
    /// `order` is the grid's order (newest first); `anchor` is where the last plain or ⌘-click landed.
    public static func click(_ id: String, command: Bool, shift: Bool, selection: Set<String>, anchor: String?, order: [String])
        -> (selection: Set<String>, anchor: String?) {
        if shift, let anchor, let from = order.firstIndex(of: anchor), let to = order.firstIndex(of: id) {
            return (Set(order[min(from, to)...max(from, to)]), anchor)
        }
        if command {
            var next = selection
            if next.remove(id) == nil { next.insert(id) }
            return (next, id)
        }
        return ([id], id)
    }
}
