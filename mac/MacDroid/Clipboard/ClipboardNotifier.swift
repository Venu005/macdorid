import Foundation
import MacDroidCore
import UserNotifications

/// "Clipboard from <phone>" after a phone item lands on the Mac clipboard (spec §4.3). Replaces the previous one.
@MainActor
final class ClipboardNotifier {
    private let router: NotificationRouter

    init(router: NotificationRouter) { self.router = router }

    func notify(_ item: ClipboardItem, from phoneName: String) async {
        guard await router.ensureAuthorized() else { return }
        let content = UNMutableNotificationContent()
        content.title = "Clipboard from \(phoneName)"
        content.body = ClipboardPresentation.notificationBody(item)
        content.threadIdentifier = "dev.macdroid.clipboard"
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "dev.macdroid.clipboard", content: content, trigger: nil))
    }
}
