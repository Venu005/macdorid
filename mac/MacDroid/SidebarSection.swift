enum SidebarSection: String, Hashable, CaseIterable, Identifiable {
    case phone, notifications, clipboard, files, general, security, about

    var id: String { rawValue }

    static let features: [SidebarSection] = [.phone, .notifications, .clipboard, .files]
    static let settings: [SidebarSection] = [.general, .security, .about]

    var title: String {
        switch self {
        case .phone: "Phone"
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        case .files: "Files"
        case .general: "General"
        case .security: "Security"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .phone: "iphone.gen3"
        case .notifications: "bell.badge"
        case .clipboard: "doc.on.clipboard"
        case .files: "folder"
        case .general: "gearshape"
        case .security: "lock.shield"
        case .about: "info.circle"
        }
    }

    /// Features not built yet show a "Coming in a later update" page.
    var isAvailable: Bool { comingSoonDescription == nil }

    var comingSoonDescription: String? {
        switch self {
        case .notifications: "See your Android notifications on your Mac, reply to messages, and dismiss them on both devices."
        case .clipboard: "Copy on your Mac and paste on your phone — and send your phone's clipboard back with one tap."
        case .files: "Drag files between your Mac and phone in either direction, with progress and history."
        default: nil
        }
    }
}
