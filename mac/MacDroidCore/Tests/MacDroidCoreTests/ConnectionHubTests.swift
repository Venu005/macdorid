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

/// Records frames; close() suspends so a session handover stays in flight for a while.
actor SlowCloseTransport: MessageTransport {
    private(set) var closed = false
    func send(_ text: String) async throws {}
    func close() async { try? await Task.sleep(for: .milliseconds(150)); closed = true }
}

@Suite struct ConnectionHubTests {
    let phoneHello = Message.hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: []))

    func start(_ hub: ConnectionHub, _ transport: FakeTransport, fingerprint: String = "aa") -> Task<Void, Never> {
        Task { await hub.run(transport: transport, incoming: transport.incoming, peerFingerprint: fingerprint) }
    }

    @Test func sendsHelloFirst() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try await eventually { await !transport.sent.isEmpty }
        #expect(try MessageCodec.decode(await transport.sent[0]) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: ProtocolInfo.features)))
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

    @Test func helloIsFirstFrameEvenWithConcurrentSend() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let (oldStream, oldContinuation) = AsyncThrowingStream.makeStream(of: String.self)
        let oldTask = Task { await hub.run(transport: SlowCloseTransport(), incoming: oldStream, peerFingerprint: "aa") }
        try await eventually { (try? await hub.send(.deviceStatus(DeviceStatus(battery: 0, charging: false)))) != nil }
        let second = FakeTransport()
        let secondTask = start(hub, second)
        for _ in 0..<50 {
            try? await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false)))
            await Task.yield()
        }
        try await eventually { await !second.sent.isEmpty }
        #expect(try MessageCodec.decode(await second.sent[0]) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: ProtocolInfo.features)))
        oldContinuation.finish(); await oldTask.value
        second.hangUp(); await secondTask.value
    }

    @Test func transportClosedWhenStreamFails() async throws {
        struct Boom: Error {}
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
        let transport = FakeTransport()
        let task = Task { await hub.run(transport: transport, incoming: stream, peerFingerprint: "aa") }
        try await eventually { await !transport.sent.isEmpty }
        continuation.finish(throwing: Boom())
        await task.value
        #expect(await transport.closed)
        #expect(await hub.status == .disconnected)
    }

    @Test func disconnectUnlessFingerprintKeepsMatchingSession() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport, fingerprint: "phone-a")
        try transport.receive(phoneHello)
        try await eventually { if case .connected = await hub.status { return true }; return false }
        await hub.disconnect(unlessFingerprint: "phone-a")
        try await Task.sleep(for: .milliseconds(50))
        #expect(await !transport.closed)
        if case .connected = await hub.status {} else { Issue.record("matching session was dropped") }
        transport.hangUp(); await task.value
    }

    @Test func disconnectUnlessFingerprintDropsOtherPhonesSession() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport, fingerprint: "old-phone")
        try transport.receive(phoneHello)
        try await eventually { if case .connected = await hub.status { return true }; return false }
        await hub.disconnect(unlessFingerprint: "new-phone")
        await task.value
        #expect(await transport.closed)
        #expect(await hub.status == .disconnected)
        await #expect(throws: HubError.notConnected) { try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false))) }
    }

    @Test func disconnectUnlessFingerprintStopsOtherPhoneStillSendingHello() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let gate = GatedHelloTransport()
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
        let task = Task { await hub.run(transport: gate, incoming: stream, peerFingerprint: "old-phone") }
        try await eventually { await gate.sendStarted }
        await hub.disconnect(unlessFingerprint: "new-phone")
        await gate.release()
        await task.value
        #expect(await gate.closed)
        await #expect(throws: HubError.notConnected) { try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false))) }
        continuation.finish()
    }

    @Test func twoStatusSubscribersBothSeeTheConnection() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let first = Collector<ConnectionStatus>(), second = Collector<ConnectionStatus>()
        let streamA = hub.statusUpdates, streamB = hub.statusUpdates
        let listeners = [
            Task { for await status in streamA { await first.append(status) } },
            Task { for await status in streamB { await second.append(status) } },
        ]
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        let connected = ConnectionStatus.connected(PeerInfo(device: "Nothing Phone (3)", app: "0.1.0", features: []))
        try await eventually {
            let (a, b) = (await first.items, await second.items)
            return a.contains(connected) && b.contains(connected)
        }
        #expect(await first.items.first == .disconnected) // each subscription starts with the current status
        transport.hangUp(); await task.value
        listeners.forEach { $0.cancel() }
    }

    @Test func transferMessagesReachTheMessagesStream() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let received = Collector<Message>()
        let stream = hub.messages
        let listener = Task { for await message in stream { await received.append(message) } }
        let transport = FakeTransport()
        let task = start(hub, transport)
        let offer = Message.transferOffer(TransferOffer(transferId: "t1", name: "a.txt", size: 3, mime: "text/plain"))
        try transport.receive(phoneHello)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 50, charging: false)))
        try transport.receive(offer)
        try await eventually { await received.items == [offer] } // hello and device.status are not forwarded
        transport.hangUp(); await task.value
        listener.cancel()
    }

    @Test func notificationMessagesReachTheMessagesStream() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let received = Collector<Message>()
        let stream = hub.messages
        let listener = Task { for await message in stream { await received.append(message) } }
        let transport = FakeTransport()
        let task = start(hub, transport)
        let posted = Message.notificationPosted(NotificationPosted(
            key: "k1", packageName: "com.whatsapp", appName: "WhatsApp", title: "Mom", text: "Hi", postedAt: 1,
            actions: [], canReply: true, iconHash: "", isUpdate: false
        ))
        let apps = Message.notificationApps(NotificationApps(forwarding: true, access: true, apps: []))
        try transport.receive(phoneHello)
        try transport.receive(posted)
        try transport.receive(apps)
        try await eventually { await received.items == [posted, apps] }
        transport.hangUp(); await task.value
        listener.cancel()
    }

    @Test func peerInfoSupportsListedFeaturesOnly() {
        let peer = PeerInfo(device: "Phone", app: "0.4.0", features: [Feature.files, Feature.notifications])
        #expect(peer.supports(Feature.notifications))
        #expect(!peer.supports(Feature.clipboard))
    }
}

/// Holds the hello send until released, so a session can be caught before it is published.
actor GatedHelloTransport: MessageTransport {
    private(set) var sendStarted = false
    private(set) var closed = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var released = false

    func send(_ text: String) async throws {
        sendStarted = true
        if !released { await withCheckedContinuation { waiter = $0 } }
    }
    func release() { released = true; waiter?.resume(); waiter = nil }
    func close() async { closed = true }
}

extension ConnectionHubTests {
    // Calls minor 7: status changes and messages arrive in one ordered stream, so a late "disconnected" from an old
    // session can't wipe what the new session already sent.
    @Test func eventsKeepStatusAndMessagesInOrder() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let events = Collector<HubEvent>()
        let stream = hub.events
        let listener = Task { for await event in stream { await events.append(event) } }
        let first = FakeTransport()
        let firstTask = start(hub, first)
        try first.receive(phoneHello)
        try first.receive(.callState(CallState(callId: "k1", state: .ringing)))
        try await eventually { await events.items.count == 2 }
        let second = FakeTransport()
        let secondTask = start(hub, second)
        try await eventually { await second.sent.count == 1 }
        try second.receive(phoneHello)
        try second.receive(.callState(CallState(callId: "k2", state: .active)))
        try await eventually { await events.items.count == 5 }
        let peer = PeerInfo(device: "Nothing Phone (3)", app: "0.1.0", features: [])
        #expect(await events.items == [
            .status(.connected(peer)), .message(.callState(CallState(callId: "k1", state: .ringing))),
            .status(.disconnected),
            .status(.connected(peer)), .message(.callState(CallState(callId: "k2", state: .active))),
        ])
        second.hangUp()
        await firstTask.value
        await secondTask.value
        listener.cancel()
    }
}
