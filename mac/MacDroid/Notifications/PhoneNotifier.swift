import Foundation
import MacDroidCore
import UserNotifications

/// What the user did with a mirrored notification, read out of the system response.
struct PhoneNotificationResponse: Sendable, Equatable {
    enum Kind: Sendable, Equatable {
        case open
        case dismiss
        case reply(String)
        /// The Android action index.
        case button(Int)
    }

    let key: String
    let kind: Kind
}

/// Posts mirrored phone notifications as macOS notifications (spec §3.2).
@MainActor
final class PhoneNotifier {
    nonisolated static let keyInfo = "phoneKey"
    nonisolated static let indicesInfo = "actionIndices"
    private let router: NotificationRouter
    private let icons: IconCache

    init(router: NotificationRouter, icons: IconCache) {
        self.router = router
        self.icons = icons
    }

    /// `banner == false` (the flood gate said no) posts nothing; the entry is still on the page.
    func show(_ posted: NotificationPosted, banner: Bool, phoneName: String) async {
        guard banner, await router.ensureAuthorized() else { return }
        let spec = NotificationCategories.spec(for: posted)
        router.register(Self.category(for: spec))
        let content = UNMutableNotificationContent()
        content.title = NotificationPresentation.bannerTitle(posted)
        content.subtitle = NotificationPresentation.subtitle(appName: posted.appName, phoneName: phoneName)
        content.body = posted.text
        content.threadIdentifier = posted.packageName
        content.categoryIdentifier = spec.identifier
        content.sound = .default
        content.userInfo = [Self.keyInfo: posted.key, Self.indicesInfo: NotificationCategories.actionIndices(for: posted)]
        if let attachment = iconAttachment(posted.iconHash) { content.attachments = [attachment] }
        // Same identifier as before: an update replaces the delivered notification.
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: posted.key, content: content, trigger: nil))
    }

    func remove(_ keys: [String]) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: keys)
    }

    /// macOS moves attachment files into its own store (spec §8.8), so attach a copy of the cached icon.
    private func iconAttachment(_ hash: String) -> UNNotificationAttachment? {
        guard let icon = icons.url(for: hash) else { return nil }
        let copy = FileManager.default.temporaryDirectory.appending(path: "macdroid-icon-\(UUID().uuidString).png")
        do {
            try FileManager.default.copyItem(at: icon, to: copy)
            return try UNNotificationAttachment(identifier: "icon", url: copy)
        } catch {
            return nil
        }
    }

    static func category(for spec: NotificationCategorySpec) -> UNNotificationCategory {
        var actions: [UNNotificationAction] = []
        if spec.canReply {
            actions.append(UNTextInputNotificationAction(
                identifier: NotificationCategories.replyAction, title: "Reply", options: [],
                textInputButtonTitle: "Send", textInputPlaceholder: "Reply"
            ))
        }
        for (position, title) in spec.buttons.enumerated() {
            actions.append(UNNotificationAction(identifier: NotificationCategories.actionIdentifier(position: position), title: title))
        }
        // .customDismissAction: closing the banner reports UNNotificationDismissActionIdentifier (dismiss sync).
        return UNNotificationCategory(identifier: spec.identifier, actions: actions, intentIdentifiers: [], options: [.customDismissAction])
    }

    nonisolated static func parse(_ response: UNNotificationResponse) -> PhoneNotificationResponse? {
        let content = response.notification.request.content
        guard content.categoryIdentifier.hasPrefix(NotificationCategories.prefix),
              let key = content.userInfo[keyInfo] as? String else { return nil }
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            return PhoneNotificationResponse(key: key, kind: .open)
        case UNNotificationDismissActionIdentifier:
            return PhoneNotificationResponse(key: key, kind: .dismiss)
        case NotificationCategories.replyAction:
            let text = (response as? UNTextInputNotificationResponse)?.userText ?? ""
            return PhoneNotificationResponse(key: key, kind: .reply(text))
        default:
            let indices = content.userInfo[indicesInfo] as? [Int] ?? []
            guard let position = NotificationCategories.position(ofAction: response.actionIdentifier),
                  indices.indices.contains(position) else { return nil }
            return PhoneNotificationResponse(key: key, kind: .button(indices[position]))
        }
    }
}
