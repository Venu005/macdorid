import Foundation

/// One call the phone told us about. `since` is when the Mac first saw it active (for the timer).
public struct CallInfo: Equatable, Sendable {
    public var callId: String
    public var name: String?
    public var number: String?
    public var phase: CallPhase
    public var since: Date?
    public init(callId: String, name: String?, number: String?, phase: CallPhase, since: Date?) {
        self.callId = callId
        self.name = name
        self.number = number
        self.phase = phase
        self.since = since
    }
}

public enum CallPanel: Equatable, Sendable {
    case none
    case ringing(CallInfo)
    case active(CallInfo)
    case notice(String)
}

public enum DialResult: Equatable, Sendable { case sent, invalidNumber, notConnected, unsupported }

public struct CallsState: Equatable, Sendable {
    /// Oldest first.
    public var calls: [CallInfo] = []
    public var connected = false
    public var phoneFeatures: Set<String> = []
    public var showIncoming = true
    public var notice: String?

    public var supported: Bool { connected && phoneFeatures.contains(Feature.calls) }
    /// The newest active call, for the popover line.
    public var activeCall: CallInfo? { calls.last { $0.phase == .active } }

    /// What the floating panel shows (spec §3.2): the newest ringing call, then a notice, then the newest active call.
    public var panel: CallPanel {
        // A ringing call is never hidden behind a notice (minor 2).
        if showIncoming, let ringing = calls.last(where: { $0.phase == .ringing }) { return .ringing(ringing) }
        if let notice { return .notice(notice) }
        guard showIncoming else { return .none }
        if let active = activeCall { return .active(active) }
        return .none
    }

    public init() {}
    public static let empty = CallsState()
}

/// Calls on the Mac (Plan 10 spec §3.1): the phone's `call.state`, Answer / Decline / Hang up, dialing, and error notices.
public actor CallCenter {
    public nonisolated var states: AsyncStream<CallsState> { broadcast.stream() }
    public private(set) var state = CallsState.empty

    private let broadcast = Broadcast<CallsState>(replayLatest: true, initial: .empty)
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    /// The last action asked for each call, to word an error.
    private var asked: [String: CallActionKind] = [:]
    private var tasks: [Task<Void, Never>] = []

    public init(now: @escaping @Sendable () -> Date = { Date() }, send: @escaping @Sendable (Message) async throws -> Void, showIncoming: Bool = true) {
        self.now = now
        self.send = send
        var initial = CallsState()
        initial.showIncoming = showIncoming
        state = initial
    }

    public func attach(to hub: ConnectionHub) async {
        guard tasks.isEmpty else { return }
        broadcast.yield(state)
        // One ordered stream: a late "disconnected" from an old session can't wipe the new session's calls (minor 7).
        let events = hub.events
        tasks.append(Task { [weak self] in
            for await event in events {
                switch event {
                case .status(let status): await self?.connectionChanged(status)
                case .message(let message): await self?.handle(message)
                }
            }
        })
        // The event stream doesn't replay: pick up a session that was already connected (subscribed first, so nothing is missed).
        let current = await hub.status
        if case .connected = current { connectionChanged(current) }
    }

    /// A `tel:` link that launched MacDroid: wait a little for the phone to connect before saying it isn't (minor 3).
    public func waitUntilSupported(timeout: Duration) async -> Bool {
        let deadline = ContinuousClock.now + timeout
        while !state.supported {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return true
    }

    func handle(_ message: Message) {
        switch message {
        case .callState(let body):
            update { s in
                let index = s.calls.firstIndex { $0.callId == body.callId }
                switch body.state {
                case .ended:
                    if let index { s.calls.remove(at: index) }
                case .ringing, .active:
                    let previous = index.map { s.calls[$0] }
                    let since = body.state == .active ? (previous?.since ?? now()) : nil
                    let info = CallInfo(callId: body.callId, name: body.name, number: body.number, phase: body.state, since: since)
                    if let index { s.calls[index] = info } else { s.calls.append(info) }
                }
            }
            // `asked` survives `ended`: the phone's error for an action pressed as the call ended comes after it (minor 1).
        case .callError(let body):
            let action = body.callId.flatMap { asked.removeValue(forKey: $0) }
            update { s in
                s.notice = CallPresentation.errorNotice(body.reason, action: action)
                // Only a call that's gone is dropped; after a refused action the phone sends the live call again (final review I1).
                if body.reason == .callGone, let id = body.callId { s.calls.removeAll { $0.callId == id } }
            }
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
            asked = [:]
            update {
                $0.connected = false
                $0.calls = []
                $0.notice = nil
            }
        }
    }

    public func answer(callId: String) async { await act(callId, .answer) }
    public func decline(callId: String) async { await act(callId, .decline) }
    public func hangUp(callId: String) async { await act(callId, .hangUp) }

    public func dial(_ raw: String) async -> DialResult {
        guard state.connected else { return .notConnected }
        guard state.supported else { return .unsupported }
        guard let number = PhoneNumbers.clean(raw) else { return .invalidNumber }
        do {
            try await send(.callDial(CallDial(number: number)))
            return .sent
        } catch {
            return .notConnected
        }
    }

    public func setShowIncoming(_ on: Bool) { update { $0.showIncoming = on } }
    public func clearNotice() { update { $0.notice = nil } }

    private func act(_ callId: String, _ action: CallActionKind) async {
        asked[callId] = action
        try? await send(.callAction(CallAction(callId: callId, action: action)))
    }

    private func update(_ change: (inout CallsState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        broadcast.yield(state)
    }
}
