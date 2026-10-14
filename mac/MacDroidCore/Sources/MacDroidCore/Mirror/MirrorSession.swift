import Foundation

/// Where media frames from `/v1/mirror` go (the app's renderer). Called off the main thread, without awaiting.
public protocol MirrorFrameSink: Sendable {
    func deliver(_ frame: MirrorFrame)
}

public struct MirrorOptions: Equatable, Sendable {
    public static let sharp = 1920
    public static let balanced = 1280
    public var maxSize: Int
    public var fps: Int
    public var audio: Bool
    public init(maxSize: Int = MirrorOptions.sharp, fps: Int = 60, audio: Bool = true) {
        self.maxSize = maxSize
        self.fps = fps
        self.audio = audio
    }
}

public enum MirrorEndReason: Equatable, Sendable { case user, mac, declined, consentTimeout, noPermission, ended, error, disconnected }

public enum MirrorPhase: Equatable, Sendable {
    case idle
    case starting(waitingForTap: Bool)
    case streaming(width: Int, height: Int, rotation: Int)
    case stopped(MirrorEndReason)
}

public struct MirrorState: Equatable, Sendable {
    public var phase: MirrorPhase = .idle
    public var sessionId: String?
    /// The phone is actually capturing audio.
    public var audio: Bool?
    /// The phone's control service is on (Plan 8).
    public var control: Bool?
    public var connected = false
    public var phoneFeatures: Set<String> = []

    public var supported: Bool { connected && phoneFeatures.contains(Feature.mirror) }
    public var isActive: Bool {
        switch phase {
        case .starting, .streaming: true
        default: false
        }
    }
    public init() {}
}

/// Which media stream may still deliver frames; read for every frame without an actor hop (final review I2).
public final class MirrorStreamGate: @unchecked Sendable {
    private let lock = NSLock()
    private var open: Int?
    func set(_ token: Int?) { lock.withLock { open = token } }
    public func isOpen(_ token: Int) -> Bool { lock.withLock { open == token } }
}

/// One mirroring session at a time (spec §6.1): start/stop over the control WebSocket, the consent hint and timeout,
/// the `/v1/mirror` gate, and the ping/pong clock offset for the latency readout.
public actor MirrorSession {
    public nonisolated var states: AsyncStream<MirrorState> { broadcast.stream() }
    public nonisolated let meter = LatencyMeter()
    public nonisolated let gate = MirrorStreamGate()
    public private(set) var state = MirrorState()

    private let broadcast = Broadcast<MirrorState>(replayLatest: true, initial: MirrorState())
    private let now: @Sendable () -> Int64
    private let makeId: @Sendable () -> String
    private let consentHint: Duration
    private let consentTimeout: Duration
    private let pingEvery: Duration
    private let send: @Sendable (Message) async throws -> Void
    private var sink: (any MirrorFrameSink)?
    private var timers: [Task<Void, Never>] = []
    private var tasks: [Task<Void, Never>] = []
    private var clock = ClockOffset()
    private var nextToken = 0
    private var openToken: Int? { didSet { gate.set(openToken) } }

    public init(
        now: @escaping @Sendable () -> Int64 = MonotonicClock.micros,
        makeId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        consentHint: Duration = .seconds(20),
        consentTimeout: Duration = .seconds(60),
        pingEvery: Duration = .seconds(2),
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.now = now
        self.makeId = makeId
        self.consentHint = consentHint
        self.consentTimeout = consentTimeout
        self.pingEvery = pingEvery
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

    public func setSink(_ sink: (any MirrorFrameSink)?) { self.sink = sink }
    func currentSink() -> (any MirrorFrameSink)? { sink }

    // MARK: Control

    public func start(_ options: MirrorOptions) async -> Bool {
        guard state.supported else { return false }
        if state.isActive { await stop() }
        let id = makeId()
        clock = ClockOffset()
        meter.reset()
        update {
            $0.sessionId = id
            $0.phase = .starting(waitingForTap: false)
            $0.audio = nil
            $0.control = nil
        }
        do {
            try await send(.mirrorStart(MirrorStart(sessionId: id, maxSize: options.maxSize, fps: options.fps, audio: options.audio)))
        } catch {
            finish(.disconnected)
            return false
        }
        let hint = consentHint, timeout = consentTimeout
        timers.append(Task { [weak self] in
            try? await Task.sleep(for: hint)
            await self?.consentHintDue(id)
        })
        timers.append(Task { [weak self] in
            try? await Task.sleep(for: timeout)
            await self?.consentTimedOut(id)
        })
        return true
    }

    public func stop() async {
        guard let id = state.sessionId, state.isActive else { return }
        finish(.mac)
        try? await send(.mirrorStop(MirrorSessionRef(sessionId: id)))
    }

    public func requestKeyFrame() async {
        guard let id = state.sessionId, case .streaming = state.phase else { return }
        try? await send(.mirrorKeyframe(MirrorSessionRef(sessionId: id)))
    }

    // MARK: Media stream gate (spec §5.3)

    public func accepts(session: String) -> Bool { state.isActive && session == state.sessionId }

    func streamOpened(session: String) -> Int? {
        guard accepts(session: session) else { return nil }
        nextToken += 1
        openToken = nextToken
        return nextToken
    }

    func streamClosed(token: Int) {
        if openToken == token { openToken = nil }
    }

    // MARK: Incoming

    func handle(_ message: Message) async {
        switch message {
        case .mirrorState(let body):
            guard body.sessionId == state.sessionId, state.isActive else { return }
            switch body.state {
            case .starting:
                break
            case .streaming:
                let first: Bool
                if case .streaming = state.phase { first = false } else { first = true }
                update {
                    $0.phase = .streaming(width: body.width ?? 0, height: body.height ?? 0, rotation: body.rotation ?? 0)
                    $0.audio = body.audio
                    $0.control = body.control
                }
                if first {
                    cancelTimers()
                    startPinging(body.sessionId)
                }
            case .stopped:
                finish(Self.reason(body.reason))
            }
        case .mirrorStop(let body):
            if body.sessionId == state.sessionId, state.isActive { finish(.user) }
        case .mirrorPong(let body):
            clock.add(t0: body.t0, t1: body.t1, t2: body.t2, t3: now())
            meter.setOffset(clock.micros)
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
            if state.isActive { finish(.disconnected) }
            update {
                $0.connected = false
                $0.phoneFeatures = []
            }
        }
    }

    // MARK: Helpers

    private func consentHintDue(_ id: String) {
        guard state.sessionId == id, case .starting = state.phase else { return }
        update { $0.phase = .starting(waitingForTap: true) }
    }

    private func consentTimedOut(_ id: String) async {
        guard state.sessionId == id, case .starting = state.phase else { return }
        finish(.consentTimeout)
        try? await send(.mirrorStop(MirrorSessionRef(sessionId: id)))
    }

    private func startPinging(_ id: String) {
        let every = pingEvery
        timers.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: every)
                guard let self, await self.isStreaming(id) else { return }
                await self.sendPing()
            }
        })
    }

    private func isStreaming(_ id: String) -> Bool {
        guard state.sessionId == id, case .streaming = state.phase else { return false }
        return true
    }

    private func sendPing() async {
        try? await send(.mirrorPing(MirrorPing(t0: now())))
    }

    private func finish(_ reason: MirrorEndReason) {
        cancelTimers()
        openToken = nil
        update { $0.phase = .stopped(reason) }
    }

    private func cancelTimers() {
        for timer in timers { timer.cancel() }
        timers = []
    }

    static func reason(_ reason: MirrorStopReason?) -> MirrorEndReason {
        switch reason {
        case .user: .user
        case .mac: .mac
        case .denied: .declined
        case .consentTimeout: .consentTimeout
        case .noPermission: .noPermission
        case .error: .error
        case .ended, nil: .ended
        }
    }

    private func update(_ change: (inout MirrorState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        broadcast.yield(state)
    }
}
