import Foundation

/// Text for the Notifications page, banners and the Phone page (spec §3.3, §5).
public enum NotificationPresentation {
    public static func relativeTime(_ date: Date, now: Date) -> String {
        let seconds = max(0, now.timeIntervalSince(date))
        if seconds < 60 { return "now" }
        if seconds < 3_600 { return "\(Int(seconds / 60)) min ago" }
        if seconds < 86_400 { return "\(Int(seconds / 3_600)) h ago" }
        return "\(Int(seconds / 86_400)) d ago"
    }

    public static func subtitle(appName: String, phoneName: String) -> String { "\(appName) · \(phoneName)" }

    /// A notification with no title shows the app's name instead.
    public static func bannerTitle(_ posted: NotificationPosted) -> String {
        posted.title.trimmingCharacters(in: .whitespaces).isEmpty ? posted.appName : posted.title
    }

    /// The banner at the top of the Notifications page, if the phone can't send notifications right now.
    public static func pageNotice(_ state: PhoneNotificationsState) -> String? {
        guard state.connected else { return nil }
        if !state.phoneSupportsNotifications { return "Update MacDroid on your phone to use this." }
        if state.phoneAccess == false { return "Notification access is off on your phone. Open MacDroid on your phone to turn it on." }
        if state.phoneForwarding == false { return "“Show on your Mac” is off in MacDroid on your phone." }
        return nil
    }

    /// The Phone page's line when the phone isn't sending notifications.
    public static func phonePageLine(_ state: PhoneNotificationsState) -> String? {
        state.phoneAccess == false || state.phoneForwarding == false ? "Notifications off on phone" : nil
    }

    public static func errorText(_ reason: NotificationErrorReason, appName: String, wasReply: Bool) -> String {
        switch reason {
        case .gone: "This notification is no longer on your phone"
        case .failed: wasReply ? "Couldn't send reply to \(appName)" : "Couldn't do that in \(appName)"
        }
    }
}
