import Foundation
import Logging

public protocol MessageTransport: Sendable {
    func send(_ text: String) async throws
    func close() async
}

public struct PeerInfo: Equatable, Sendable {
    public var device: String
    public var app: String
    public var features: [String]
    public var battery: Int?
    public var charging: Bool?

    public init(device: String, app: String, features: [String], battery: Int? = nil, charging: Bool? = nil) {
        self.device = device
        self.app = app
        self.features = features
        self.battery = battery
        self.charging = charging
    }
}

public enum ConnectionStatus: Equatable, Sendable {
    case disconnected
    case connected(PeerInfo)
}

public enum HubError: Error, Equatable {
    case notConnected
}

/// Holds the single active phone session. A newer session always replaces an older one.
public actor ConnectionHub {
    public nonisolated let statusUpdates: AsyncStream<ConnectionStatus>
    private let statusContinuation: AsyncStream<ConnectionStatus>.Continuation
    private let hello: Hello
    private let logger = Logger(label: "dev.macdroid.hub")
    private var current: (id: UUID, transport: any MessageTransport, fingerprint: String)?
    /// The most recently started session; an older `run` still sending its hello must back off.
    private var latest: (id: UUID, fingerprint: String)?

    public private(set) var status: ConnectionStatus = .disconnected {
        didSet { if status != oldValue { statusContinuation.yield(status) } }
    }

    public init(macName: String, appVersion: String, features: [String] = []) {
        let (stream, continuation) = AsyncStream.makeStream(of: ConnectionStatus.self)
        self.statusUpdates = stream
        self.statusContinuation = continuation
        self.hello = Hello(app: appVersion, device: macName, features: features)
    }

    /// Runs one session until `incoming` ends, then closes `transport`.
    /// The session is published to `send` only after its hello went out, so hello is always the first frame.
    /// `peerFingerprint` is the phone certificate the session was authenticated with.
    public func run(transport: any MessageTransport, incoming: AsyncThrowingStream<String, any Error>, peerFingerprint: String) async {
        let id = UUID()
        latest = (id, peerFingerprint)
        let previous = current
        current = nil
        if previous != nil { status = .disconnected }
        if let previous { await previous.transport.close() }

        do {
            try await transport.send(MessageCodec.encode(.hello(hello)))
            guard latest?.id == id else {
                await transport.close()
                return
            }
            current = (id, transport, peerFingerprint)
            for try await text in incoming { handle(text, session: id) }
        } catch {
            logger.debug("session ended: \(error)")
        }
        endSession(id)
        await transport.close()
    }

    public func send(_ message: Message) async throws {
        guard let transport = current?.transport else { throw HubError.notConnected }
        try await transport.send(MessageCodec.encode(message))
    }

    public func disconnect() async {
        latest = nil
        guard let session = current else { return }
        current = nil
        status = .disconnected
        await session.transport.close()
    }

    /// Ends any session that does not belong to `fingerprint` (e.g. after a different phone paired).
    public func disconnect(unlessFingerprint fingerprint: String) async {
        if let pending = latest, pending.fingerprint != fingerprint { latest = nil }
        guard let session = current, session.fingerprint != fingerprint else { return }
        current = nil
        status = .disconnected
        await session.transport.close()
    }

    private func handle(_ text: String, session: UUID) {
        guard current?.id == session else { return }
        let message: Message
        do {
            message = try MessageCodec.decode(text)
        } catch {
            logger.warning("dropping malformed frame")
            return
        }
        switch message {
        case .hello(let peer):
            status = .connected(PeerInfo(device: peer.device, app: peer.app, features: peer.features))
        case .deviceStatus(let update):
            if case .connected(var peer) = status {
                peer.battery = update.battery
                peer.charging = update.charging
                status = .connected(peer)
            }
        case .unknown(let type):
            logger.debug("ignoring message type \(type)")
        }
    }

    private func endSession(_ id: UUID) {
        guard current?.id == id else { return }
        current = nil
        status = .disconnected
    }
}
