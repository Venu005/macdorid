import AppKit
import UserNotifications

/// The app's only `UNUserNotificationCenter` delegate (spec §8.7). macOS allows one delegate and one category set,
/// so received-file notifications and mirrored phone notifications share this router.
@MainActor
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    private var categories: [String: UNNotificationCategory] = [:]
    private var authorized: Bool?
    /// Responses to mirrored phone notifications, already read out of the system response.
    var onPhoneResponse: ((PhoneNotificationResponse) -> Void)?

    func setUp() {
        UNUserNotificationCenter.current().delegate = self
        register(ReceivedNotifier.makeCategory())
    }

    /// Adds a category. macOS replaces the whole set on every call, so the full set is always sent.
    func register(_ category: UNNotificationCategory) {
        guard categories[category.identifier] == nil else { return }
        categories[category.identifier] = category
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values))
    }

    /// Asks for permission the first time anything needs to notify.
    func ensureAuthorized() async -> Bool {
        if let authorized { return authorized }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        authorized = granted
        return granted
    }

    /// The user turned MacDroid's notifications off in System Settings.
    nonisolated static func isDenied() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.notification.request.content.categoryIdentifier == ReceivedNotifier.category {
            await ReceivedNotifier.respond(to: response)
        } else if let parsed = PhoneNotifier.parse(response) {
            await MainActor.run { onPhoneResponse?(parsed) }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
