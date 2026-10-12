import Foundation
import Testing
@testable import MacDroidCore

/// A transport whose incoming stream ends when closed, like a real WebSocket.
actor FakeTransport: MessageTransport {
    private(set) var sent: [String] = []
    private(set) var closed = false
    let incoming: AsyncThrowingStream<String, any Error>
    private let continuation: AsyncThrowingStream<String, any Error>.Continuation

    init() {
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
        self.incoming = stream
        self.continuation = continuation
    }

    func send(_ text: String) async throws { sent.append(text) }
    func close() async { closed = true; continuation.finish() }
    nonisolated func receive(_ message: Message) throws { continuation.yield(try MessageCodec.encode(message)) }
    nonisolated func receiveRaw(_ text: String) { continuation.yield(text) }
    nonisolated func hangUp() { continuation.finish() }
}

@Suite struct ConnectionHubTests {
    let phoneHello = Message.hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: []))

    func start(_ hub: ConnectionHub, _ transport: FakeTransport) -> Task<Void, Never> {
        Task { await hub.run(transport: transport, incoming: transport.incoming) }
    }

    @Test func sendsHelloFirst() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try await eventually { await !transport.sent.isEmpty }
        #expect(try MessageCodec.decode(await transport.sent[0]) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: [])))
        transport.hangUp(); await task.value
    }

    @Test func connectedAfterPhoneHelloAndBatteryUpdates() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 64, charging: true)))
        try await eventually {
            await hub.status == .connected(PeerInfo(device: "Nothing Phone (3)", app: "0.1.0", features: [], battery: 64, charging: true))
        }
        transport.hangUp(); await task.value
        #expect(await hub.status == .disconnected)
    }

    @Test func malformedAndUnknownFramesAreIgnored() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        transport.receiveRaw("garbage")
        transport.receiveRaw(#"{"v":1,"type":"future.thing","id":"x","body":{}}"#)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 10, charging: false)))
        try await eventually {
            if case .connected(let peer) = await hub.status { return peer.battery == 10 }
            return false
        }
        transport.hangUp(); await task.value
    }

    // Review Focus #5
    @Test func newSessionReplacesOldWithoutFlicker() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let first = FakeTransport()
        let firstTask = start(hub, first)
        try first.receive(phoneHello)
        try await eventually { if case .connected = await hub.status { return true }; return false }

        let second = FakeTransport()
        let secondTask = start(hub, second)
        try await eventually { await first.closed }
        try second.receive(.hello(Hello(app: "0.1.1", device: "Nothing Phone (3)", features: [])))
        await firstTask.value
        try await eventually {
            if case .connected(let peer) = await hub.status { return peer.app == "0.1.1" }
            return false
        }
        try await Task.sleep(for: .milliseconds(50))
        if case .connected(let peer) = await hub.status { #expect(peer.app == "0.1.1") } else { Issue.record("status flipped") }
        second.hangUp(); await secondTask.value
    }

    @Test func sendRequiresSession() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        await #expect(throws: HubError.notConnected) { try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false))) }
        let transport = FakeTransport()
        let task = start(hub, transport)
        try await eventually { await !transport.sent.isEmpty }
        try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false)))
        #expect(await transport.sent.count == 2)
        await hub.disconnect()
        await task.value
        #expect(await transport.closed)
    }
}
