import AppKit
import MacDroidCore
import UserNotifications

/// "Received <name>" notifications with Show in Finder and Open (file-transfer spec §3.2).
/// Permission, categories and responses go through `NotificationRouter`, the app's only delegate.
@MainActor
final class ReceivedNotifier {
    nonisolated static let category = "dev.macdroid.received"
    nonisolated static let showAction = "show"
    nonisolated static let openAction = "open"
    nonisolated static let pathKey = "path"
    private let router: NotificationRouter

    init(router: NotificationRouter) { self.router = router }

    static func makeCategory() -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: category,
            actions: [
                UNNotificationAction(identifier: showAction, title: "Show in Finder"),
                UNNotificationAction(identifier: openAction, title: "Open"),
            ],
            intentIdentifiers: []
        )
    }

    func notifyReceived(_ record: TransferRecord, from phoneName: String) async {
        guard let url = record.savedURL, await router.ensureAuthorized() else { return }
        let content = UNMutableNotificationContent()
        content.title = "Received \(record.name)"
        content.body = "From \(phoneName)"
        content.categoryIdentifier = Self.category
        content.userInfo = [Self.pathKey: url.path(percentEncoded: false)]
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil))
    }

    /// Open, or Show in Finder (the default click and "Show in Finder").
    nonisolated static func respond(to response: UNNotificationResponse) async {
        guard let path = response.notification.request.content.userInfo[pathKey] as? String else { return }
        let open = response.actionIdentifier == openAction
        await MainActor.run {
            let url = URL(fileURLWithPath: path)
            guard FileManager.default.fileExists(atPath: path) else {
                // Moved or deleted since it arrived: say so rather than doing nothing (Plan 11).
                let alert = NSAlert()
                alert.messageText = "The file was moved or deleted"
                alert.informativeText = url.lastPathComponent
                alert.runModal()
                return
            }
            if open { NSWorkspace.shared.open(url) } else { NSWorkspace.shared.activateFileViewerSelecting([url]) }
        }
    }
}
