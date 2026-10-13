import AppKit
import MacDroidCore
import UserNotifications

/// "Received <name>" notifications with Show in Finder and Open (spec §3.2). Asks for permission the first time a file arrives.
@MainActor
final class ReceivedNotifier: NSObject, UNUserNotificationCenterDelegate {
    nonisolated static let category = "dev.macdroid.received"
    nonisolated static let showAction = "show"
    nonisolated static let openAction = "open"
    nonisolated static let pathKey = "path"
    private var authorized: Bool?

    func setUp() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.category,
                actions: [
                    UNNotificationAction(identifier: Self.showAction, title: "Show in Finder"),
                    UNNotificationAction(identifier: Self.openAction, title: "Open"),
                ],
                intentIdentifiers: []
            ),
        ])
    }

    func notifyReceived(_ record: TransferRecord, from phoneName: String) async {
        guard let url = record.savedURL else { return }
        let center = UNUserNotificationCenter.current()
        if authorized == nil {
            authorized = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        }
        guard authorized == true else { return }
        let content = UNMutableNotificationContent()
        content.title = "Received \(record.name)"
        content.body = "From \(phoneName)"
        content.categoryIdentifier = Self.category
        content.userInfo = [Self.pathKey: url.path(percentEncoded: false)]
        try? await center.add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let path = response.notification.request.content.userInfo[Self.pathKey] as? String else { return }
        let open = response.actionIdentifier == Self.openAction
        await MainActor.run {
            let url = URL(fileURLWithPath: path)
            if open { NSWorkspace.shared.open(url) } else { NSWorkspace.shared.activateFileViewerSelecting([url]) }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
