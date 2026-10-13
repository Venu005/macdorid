import Foundation
import HummingbirdTesting
import Logging
import NIOCore
import NIOSSL
import Testing
import WSClient
@testable import MacDroidCore

struct ServerHarness {
    let port: Int
    let pairing: PairingManager
    let hub: ConnectionHub

    static func run(
        pairedPhone: Identity? = nil,
        _ body: (ServerHarness) async throws -> Void
    ) async throws {
        let store = InMemoryPairedDeviceStore()
        if let pairedPhone {
            store.save(PairedDevice(fingerprint: pairedPhone.fingerprint, name: "Phone", model: "Test", pairedAt: Date()))
        }
        let pairing = PairingManager(store: store)
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let server = MacDroidServer(
            identity: try Identity.generate(commonName: "Test Mac"),
            pairing: pairing, hub: hub, macName: "Test Mac",
            configuration: ServerConfiguration(host: "127.0.0.1", port: 0)
        )
        let (ports, portContinuation) = AsyncStream.makeStream(of: Int.self)
        let task = Task { try await server.run { port in portContinuation.yield(port) } }
        var iterator = ports.makeAsyncIterator()
        let port = try #require(await iterator.next())
        do {
            try await body(ServerHarness(port: port, pairing: pairing, hub: hub))
        } catch {
            task.cancel(); _ = await task.result
            throw error
        }
        task.cancel(); _ = await task.result
    }

    func client(_ phone: Identity) throws -> TestClient {
        let client = TestClient(host: "localhost", port: port, configuration: .init(tlsConfiguration: try phone.clientTLS(), serverName: "localhost"))
        client.connect()
        return client
    }
}

extension Identity {
    func clientTLS() throws -> TLSConfiguration {
        var tls = TLSConfiguration.makeClientConfiguration()
        tls.certificateVerification = .none // the Android client pins; these tests exercise the server side
        tls.minimumTLSVersion = .tlsv13
        tls.certificateChain = [.certificate(try nioCertificate())]
        tls.privateKey = .privateKey(try nioPrivateKey())
        return tls
    }
}

func pairBody(code: String) -> ByteBuffer {
    ByteBuffer(string: #"{"code":"\#(code)","deviceName":"Phone","model":"Nothing Phone (3)"}"#)
}

@Suite struct ServerTests {
    @Test func pairsWithCorrectCodeWhileWindowOpen() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run { harness in
            let window = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            let response = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            #expect(response.status == .ok)
            let decoded = try JSONDecoder().decode(PairResponse.self, from: Data(String(buffer: try #require(response.body)).utf8))
            #expect(decoded == PairResponse(macName: "Test Mac"))
            #expect(await harness.pairing.isTrusted(fingerprint: phone.fingerprint))
            try await client.shutdown()
        }
    }

    @Test func wrongCodeIsForbidden() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            let response = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: "WRONG"))
            #expect(response.status == .forbidden)
            #expect(await !harness.pairing.isTrusted(fingerprint: phone.fingerprint))
            try await client.shutdown()
        }
    }

    // Review Focus #2
    @Test func pairingRejectedAfterWindowCloses() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let intruder = try Identity.generate(commonName: "Intruder")
        try await ServerHarness.run { harness in
            let window = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            _ = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            try await client.shutdown()
            // Window is now closed, so an unknown certificate fails the TLS handshake.
            await #expect(throws: (any Error).self) {
                let other = try harness.client(intruder)
                _ = try await other.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            }
            #expect(await harness.pairing.isTrusted(fingerprint: phone.fingerprint))
        }
    }

    @Test func unpairedCertificateCannotReachOtherRoutes() async throws {
        let stranger = try Identity.generate(commonName: "Stranger")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow() // handshake allowed, routes are not
            let client = try harness.client(stranger)
            #expect(try await client.get("/v1/info").status == .forbidden)
            try await client.shutdown()
        }
    }

    @Test func pairedPhoneGetsInfo() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run(pairedPhone: phone) { harness in
            let client = try harness.client(phone)
            let response = try await client.get("/v1/info")
            #expect(response.status == .ok)
            #expect(try JSONDecoder().decode(ServerInfo.self, from: Data(String(buffer: try #require(response.body)).utf8)) == ServerInfo(macName: "Test Mac"))
            try await client.shutdown()
        }
    }

    @Test func pairedPhoneOpensWebSocketAndExchangesHello() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run(pairedPhone: phone) { harness in
            try await WebSocketClient.connect(
                url: "wss://localhost:\(harness.port)/v1/ws",
                tlsConfiguration: try phone.clientTLS(),
                logger: Logger(label: "test")
            ) { inbound, outbound, _ in
                var messages = inbound.messages(maxSize: 1 << 20).makeAsyncIterator()
                guard case .text(let first)? = try await messages.next() else { Issue.record("no hello"); return }
                #expect(try MessageCodec.decode(first) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: [])))
                try await outbound.write(.text(MessageCodec.encode(.hello(Hello(app: "0.1.0", device: "Phone", features: [])))))
                try await eventually { if case .connected = await harness.hub.status { return true }; return false }
                try await outbound.close(.normalClosure, reason: nil)
            }
            try await eventually { await harness.hub.status == .disconnected }
        }
    }

    @Test func unpairedPhoneCannotUpgrade() async throws {
        let stranger = try Identity.generate(commonName: "Stranger")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow()
            await #expect(throws: (any Error).self) {
                try await WebSocketClient.connect(
                    url: "wss://localhost:\(harness.port)/v1/ws",
                    tlsConfiguration: try stranger.clientTLS(),
                    logger: Logger(label: "test")
                ) { _, _, _ in Issue.record("upgrade should be refused") }
            }
        }
    }
}
