import Foundation

public struct PhoneNotificationsState: Equatable, Sendable {
    public var groups: [NotificationGroup]
    public var apps: [PhoneApp]
    /// The phone's "Show on your Mac" switch, from its last `notification.apps` (nil until one arrives).
    public var phoneForwarding: Bool?
    public var phoneAccess: Bool?
    public var connected: Bool
    /// The connected phone listed "notifications" in its hello.
    public var phoneSupportsNotifications: Bool
    public var count: Int

    public init(
        groups: [NotificationGroup] = [], apps: [PhoneApp] = [], phoneForwarding: Bool? = nil, phoneAccess: Bool? = nil,
        connected: Bool = false, phoneSupportsNotifications: Bool = false, count: Int = 0
    ) {
        self.groups = groups
        self.apps = apps
        self.phoneForwarding = phoneForwarding
        self.phoneAccess = phoneAccess
        self.connected = connected
        self.phoneSupportsNotifications = phoneSupportsNotifications
        self.count = count
    }

    public static let empty = PhoneNotificationsState()
}

public enum PhoneNotificationEvent: Equatable, Sendable {
    /// Post or replace a macOS notification. `banner == false`: the page only (flood gate).
    case show(NotificationPosted, banner: Bool)
    /// Remove delivered macOS notifications.
    case remove(keys: [String])
    /// Something the user should read on the Notifications page.
    case problem(String)
}

/// The Mac side of notification mirroring (spec §3.2): keeps the page's entries and the phone's app list,
/// and turns the user's replies, actions, dismissals and mutes into messages.
public actor PhoneNotifications {
    public nonisolated var states: AsyncStream<PhoneNotificationsState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhoneNotificationEvent> { eventBroadcast.stream() }
    public private(set) var state = PhoneNotificationsState.empty

    private let stateBroadcast = Broadcast<PhoneNotificationsState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhoneNotificationEvent>(replayLatest: false)
    private let icons: IconCache
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    private var enabled: Bool
    private var store = NotificationStore()
    private var flood = FloodGate()
    private var apps: [PhoneApp] = []
    private var phoneForwarding: Bool?
    private var phoneAccess: Bool?
    private var connected = false
    private var phoneSupports = false
    /// Keys with a reply in flight, so an error names the right thing.
    private var replying: Set<String> = []
    private var tasks: [Task<Void, Never>] = []

    public init(
        icons: IconCache, enabled: Bool = true, now: @escaping @Sendable () -> Date = { Date() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.icons = icons
        self.enabled = enabled
        self.now = now
        self.send = send
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    public func entry(key: String) -> NotificationEntry? { store.entry(key: key) }

    /// The Mac's "Show phone notifications" setting. Off: incoming notifications are ignored.
    public func setEnabled(_ on: Bool) { enabled = on }

    func handle(_ message: Message) {
        switch message {
        case .notificationPosted(let posted):
            guard enabled else { return }
            let date = now()
            store.upsert(posted, at: date)
            let banner = flood.allowsBanner(packageName: posted.packageName, at: date)
            publish()
            eventBroadcast.yield(.show(posted, banner: banner))
        case .notificationRemoved(let body):
            store.remove(key: body.key)
            publish()
            eventBroadcast.yield(.remove(keys: [body.key]))
        case .notificationSync(let body):
            let removed = store.retain(keys: Set(body.keys))
            guard !removed.isEmpty else { return }
            publish()
            eventBroadcast.yield(.remove(keys: removed))
        case .notificationIcon(let body):
            icons.save(hash: body.hash, pngBase64: body.png)
        case .notificationApps(let body):
            apps = body.apps
            phoneForwarding = body.forwarding
            phoneAccess = body.access
            publish()
        case .notificationError(let body):
            let appName = store.entry(key: body.key)?.posted.appName ?? "the app"
            let wasReply = replying.remove(body.key) != nil
            if body.reason == .gone {
                store.remove(key: body.key)
                publish()
                eventBroadcast.yield(.remove(keys: [body.key]))
            }
            eventBroadcast.yield(.problem(NotificationPresentation.errorText(body.reason, appName: appName, wasReply: wasReply)))
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        let (isConnected, supports): (Bool, Bool) = switch status {
        case .connected(let peer): (true, peer.supports(Feature.notifications))
        case .disconnected: (false, phoneSupports)
        }
        guard isConnected != connected || supports != phoneSupports else { return }
        connected = isConnected
        phoneSupports = supports
        publish()
    }

    public func reply(key: String, text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        replying.insert(key)
        do {
            try await send(.notificationReply(NotificationReply(key: key, text: trimmed)))
        } catch {
            replying.remove(key)
            eventBroadcast.yield(.problem("Your phone isn't connected, so the reply wasn't sent"))
        }
    }

    public func perform(key: String, actionIndex: Int) async {
        do {
            try await send(.notificationAction(NotificationActionRequest(key: key, actionIndex: actionIndex)))
        } catch {
            eventBroadcast.yield(.problem("Your phone isn't connected"))
        }
    }

    public func dismiss(key: String) async {
        store.remove(key: key)
        publish()
        eventBroadcast.yield(.remove(keys: [key]))
        try? await send(.notificationDismiss(NotificationKey(key: key)))
    }

    public func clearAll() async {
        let keys = store.clear()
        guard !keys.isEmpty else { return }
        publish()
        eventBroadcast.yield(.remove(keys: keys))
        for key in keys { try? await send(.notificationDismiss(NotificationKey(key: key))) }
    }

    /// Asks the phone (which owns the list, spec §2.2) to mute or unmute; shows the change right away.
    public func setMuted(packageName: String, muted: Bool) async {
        guard let index = apps.firstIndex(where: { $0.packageName == packageName }) else { return }
        let before = apps[index].muted
        apps[index].muted = muted
        publish()
        do {
            try await send(.notificationMute(NotificationMute(packageName: packageName, muted: muted)))
        } catch {
            if let i = apps.firstIndex(where: { $0.packageName == packageName }) { apps[i].muted = before }
            publish()
            eventBroadcast.yield(.problem("Your phone isn't connected, so the change wasn't saved"))
        }
    }

    private func publish() {
        state = PhoneNotificationsState(
            groups: store.groups, apps: apps, phoneForwarding: phoneForwarding, phoneAccess: phoneAccess,
            connected: connected, phoneSupportsNotifications: phoneSupports, count: store.entries.count
        )
        stateBroadcast.yield(state)
    }
}
