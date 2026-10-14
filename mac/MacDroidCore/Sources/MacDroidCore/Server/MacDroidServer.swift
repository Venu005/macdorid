import Foundation
import HTTPTypes
import Hummingbird
import HummingbirdTLS
import HummingbirdWebSocket
import Logging
import NIOCore
import NIOSSL

public struct ServerConfiguration: Sendable {
    public var host: String
    public var port: Int

    public init(host: String = "0.0.0.0", port: Int = 0) {
        self.host = host
        self.port = port
    }
}

public struct MacDroidRequestContext: RequestContext {
    public var coreContext: CoreRequestContextStorage
    public let channel: any Channel

    public init(source: ApplicationRequestContextSource) {
        self.coreContext = .init(source: source)
        self.channel = source.channel
    }

    /// Fingerprint of the client certificate presented in this TLS session.
    func peerFingerprint() async throws -> String? {
        guard let certificate = try await channel.nioSSL_peerCertificate().get() else { return nil }
        return try Fingerprint.of(certificate)
    }
}

extension PairResponse: ResponseEncodable {}
extension ServerInfo: ResponseEncodable {}

/// Rejects requests whose client certificate is not the paired phone.
struct TrustedPeerMiddleware: RouterMiddleware {
    typealias Context = MacDroidRequestContext
    let pairing: PairingManager

    func handle(_ request: Request, context: Context, next: (Request, Context) async throws -> Response) async throws -> Response {
        guard let fingerprint = try await context.peerFingerprint(),
              await pairing.isTrusted(fingerprint: fingerprint) else {
            throw HTTPError(.forbidden)
        }
        return try await next(request, context)
    }
}

struct WebSocketTransport: MessageTransport {
    let outbound: WebSocketOutboundWriter

    func send(_ text: String) async throws { try await outbound.write(.text(text)) }
    func close() async { try? await outbound.close(.normalClosure, reason: nil) }
}

public final class MacDroidServer: Sendable {
    public static let maxMessageSize = 1 << 20
    /// The mirroring session id on `/v1/mirror` (spec §5.3).
    public static let sessionHeader = HTTPField.Name("X-MacDroid-Session")!

    private let identity: Identity
    private let pairing: PairingManager
    private let hub: ConnectionHub
    private let macName: String
    private let configuration: ServerConfiguration
    private let transfers: TransferManager?
    private let mirror: MirrorSession?
    private let logger = Logger(label: "dev.macdroid.server")

    public init(identity: Identity, pairing: PairingManager, hub: ConnectionHub, macName: String, configuration: ServerConfiguration = .init(), transfers: TransferManager? = nil, mirror: MirrorSession? = nil) {
        self.identity = identity
        self.pairing = pairing
        self.hub = hub
        self.macName = macName
        self.configuration = configuration
        self.transfers = transfers
        self.mirror = mirror
    }

    /// Runs until the calling task is cancelled. `onRunning` receives the bound port.
    public func run(onRunning: @escaping @Sendable (Int) async -> Void = { _ in }) async throws {
        var tls = TLSConfiguration.makeServerConfiguration(
            certificateChain: [.certificate(try identity.nioCertificate())],
            privateKey: .privateKey(try identity.nioPrivateKey())
        )
        tls.minimumTLSVersion = .tlsv13
        tls.certificateVerification = .noHostnameVerification // require a client certificate

        let pairing = self.pairing
        let hub = self.hub
        let mirror = self.mirror
        let app = Application(
            router: makeRouter(),
            server: try .tls(
                .http1WebSocketUpgrade(
                    configuration: .init(ws: .init(maxFrameSize: MirrorFrame.maxSize, autoPing: .enabled(timePeriod: .seconds(15))))
                ) { request, channel, _ in
                    guard let certificate = try await channel.nioSSL_peerCertificate().get() else { return .dontUpgrade }
                    let fingerprint = try Fingerprint.of(certificate)
                    guard await pairing.isTrusted(fingerprint: fingerprint) else { return .dontUpgrade }
                    switch request.path {
                    case "/v1/ws":
                        return .upgrade([:]) { inbound, outbound, _ in
                            let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
                            let session = Task {
                                await hub.run(transport: WebSocketTransport(outbound: outbound), incoming: stream, peerFingerprint: fingerprint)
                            }
                            do {
                                for try await message in inbound.messages(maxSize: Self.maxMessageSize) {
                                    if case .text(let text) = message { continuation.yield(text) }
                                }
                                continuation.finish()
                            } catch {
                                continuation.finish(throwing: error)
                            }
                            await session.value
                        }
                    case "/v1/mirror":
                        // Only the session the Mac started, while it is starting or streaming (spec §5.3).
                        guard let mirror, let session = request.headerFields[Self.sessionHeader], await mirror.accepts(session: session) else {
                            return .dontUpgrade
                        }
                        return .upgrade([:]) { inbound, _, _ in
                            await Self.receiveMirror(inbound.messages(maxSize: MirrorFrame.maxSize), session: session, mirror: mirror)
                        }
                    default:
                        return .dontUpgrade
                    }
                },
                configuration: TLSChannelConfiguration(
                    tlsConfiguration: tls,
                    customAsyncVerificationCallback: { certificates in
                        guard let leaf = certificates.first else { return .failed }
                        return await pairing.acceptsHandshake(fingerprint: try Fingerprint.of(leaf)) ? .certificateVerified : .failed
                    }
                )
            ),
            configuration: .init(address: .hostname(configuration.host, port: configuration.port)),
            onServerRunning: { channel in await onRunning(channel.localAddress?.port ?? 0) },
            logger: logger
        )
        try await app.runService(gracefulShutdownSignals: [])
    }

    /// Media frames go straight to the sink without awaiting the UI; an invalid frame ends the stream (spec §5.2).
    static func receiveMirror(_ messages: WebSocketInboundMessageStream, session: String, mirror: MirrorSession) async {
        guard let token = await mirror.streamOpened(session: session) else { return }
        let sink = await mirror.currentSink()
        do {
            for try await message in messages {
                guard case .binary(let buffer) = message else { continue }
                sink?.deliver(try MirrorFrame.decode(Data(buffer.readableBytesView)))
            }
        } catch {
            // closed, or a bad frame: the phone reconnects once or reports the error (spec §8)
        }
        await mirror.streamClosed(token: token)
    }

    private func makeRouter() -> Router<MacDroidRequestContext> {
        let router = Router(context: MacDroidRequestContext.self)
        let pairing = self.pairing
        let macName = self.macName

        router.post("/v1/pair") { request, context -> PairResponse in
            let body = try await request.decode(as: PairRequest.self, context: context)
            guard let fingerprint = try await context.peerFingerprint() else { throw HTTPError(.forbidden) }
            do {
                _ = try await pairing.completePairing(code: body.code, fingerprint: fingerprint, deviceName: body.deviceName, model: body.model)
            } catch is PairingError {
                throw HTTPError(.forbidden)
            }
            return PairResponse(macName: macName)
        }

        // Every route registered after this line, and the not-found responder, requires the paired phone.
        // TLS 1.3 session resumption skips the handshake callback, so this app-layer check is what keeps strangers out.
        router.add(middleware: TrustedPeerMiddleware(pairing: pairing))
        router.get("/v1/info") { _, _ -> ServerInfo in ServerInfo(macName: macName) }
        if let transfers { Self.addFileRoutes(to: router, transfers: transfers) }

        return router
    }
}
