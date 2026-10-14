enum SidebarSection: String, Hashable, CaseIterable, Identifiable {
    case phone, notifications, clipboard, files, photos, general, security, about

    var id: String { rawValue }

    static let features: [SidebarSection] = [.phone, .notifications, .clipboard, .files, .photos]
    static let settings: [SidebarSection] = [.general, .security, .about]

    var title: String {
        switch self {
        case .phone: "Phone"
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        case .files: "Files"
        case .photos: "Photos"
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
        case .photos: "photo.on.rectangle"
        case .general: "gearshape"
        case .security: "lock.shield"
        case .about: "info.circle"
        }
    }

    /// Features not built yet show a "Coming in a later update" page.
    var isAvailable: Bool { comingSoonDescription == nil }

    var comingSoonDescription: String? { nil }
}
