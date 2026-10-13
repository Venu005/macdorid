import Foundation

public struct PhoneExtrasState: Equatable, Sendable {
    public var ringing: Bool
    public var media: MediaState
    /// When the Mac received `media` (the playback position is projected from this, see Task 4 ruling).
    public var mediaReceivedAt: Date?
    public var connected: Bool
    public var phoneFeatures: Set<String>

    public var supportsRing: Bool { connected && phoneFeatures.contains(Feature.ring) }
    public var supportsLinks: Bool { connected && phoneFeatures.contains(Feature.links) }
    public var supportsMedia: Bool { connected && phoneFeatures.contains(Feature.media) }

    public init(ringing: Bool = false, media: MediaState = .idle, mediaReceivedAt: Date? = nil, connected: Bool = false, phoneFeatures: Set<String> = []) {
        self.ringing = ringing
        self.media = media
        self.mediaReceivedAt = mediaReceivedAt
        self.connected = connected
        self.phoneFeatures = phoneFeatures
    }

    public static let empty = PhoneExtrasState()
}

public enum PhoneExtrasEvent: Equatable, Sendable {
    /// The phone asked the Mac to open this (already validated http/https) link.
    case openLink(URL)
}

public enum LinkSendResult: Equatable, Sendable {
    case sent, noLink, notConnected, unsupported
}

/// Ring, links and media controls on the Mac side (spec §4).
public actor PhoneExtras {
    public nonisolated var states: AsyncStream<PhoneExtrasState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhoneExtrasEvent> { eventBroadcast.stream() }
    public private(set) var state = PhoneExtrasState.empty

    private let stateBroadcast = Broadcast<PhoneExtrasState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhoneExtrasEvent>(replayLatest: false)
    private let art: IconCache
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    private var tasks: [Task<Void, Never>] = []

    public init(art: IconCache, now: @escaping @Sendable () -> Date = { Date() }, send: @escaping @Sendable (Message) async throws -> Void) {
        self.art = art
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

    func handle(_ message: Message) {
        switch message {
        case .ringState(let body):
            update { $0.ringing = body.ringing }
        case .mediaState(let body):
            let at = now()
            update {
                $0.media = body
                $0.mediaReceivedAt = at
            }
        case .mediaArt(let body):
            art.save(hash: body.hash, pngBase64: body.jpeg) // validated like app icons; the image format doesn't matter
        case .linkOpen(let body):
            if let url = LinkRules.allowedURL(body.url) { eventBroadcast.yield(.openLink(url)) }
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        switch status {
        case .connected(let peer):
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
        case .disconnected:
            update {
                $0.connected = false
                $0.ringing = false
                $0.media = .idle
                $0.mediaReceivedAt = nil
            }
        }
    }

    public func ring() async {
        try? await send(.deviceRing(DeviceRing(action: .start)))
    }

    public func stopRing() async {
        try? await send(.deviceRing(DeviceRing(action: .stop)))
    }

    public func command(_ kind: MediaCommandKind, positionMs: Int64? = nil) async {
        try? await send(.mediaCommand(MediaCommand(command: kind, positionMs: positionMs)))
    }

    /// "Open on Phone" (spec §4): the first http(s) link in `text`.
    public func sendLink(in text: String) async -> LinkSendResult {
        guard state.connected else { return .notConnected }
        guard state.phoneFeatures.contains(Feature.links) else { return .unsupported }
        guard let link = LinkRules.firstLink(in: text) else { return .noLink }
        do {
            try await send(.linkOpen(LinkOpen(url: link)))
            return .sent
        } catch {
            return .notConnected
        }
    }

    private func update(_ change: (inout PhoneExtrasState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        stateBroadcast.yield(state)
    }
}
