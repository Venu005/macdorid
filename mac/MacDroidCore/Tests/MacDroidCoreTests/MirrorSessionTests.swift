import Foundation
import NIOConcurrencyHelpers
import Testing
@testable import MacDroidCore

@Suite struct MirrorSessionTests {
    final class Harness: Sendable {
        let session: MirrorSession
        let sent: Collector<Message>

        init(hint: Duration = .seconds(20), timeout: Duration = .seconds(60), ping: Duration = .seconds(2)) {
            let sent = Collector<Message>()
            let counter = NIOLockedValueBox(0)
            session = MirrorSession(
                now: { 2_000 },
                makeId: { counter.withLockedValue { $0 += 1; return "s\($0)" } },
                consentHint: hint, consentTimeout: timeout, pingEvery: ping,
                send: { await sent.append($0) }
            )
            self.sent = sent
        }

        func connect(features: [String] = ProtocolInfo.features) async {
            await session.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.7.0", features: features)))
        }

        func streaming(_ id: String = "s1", width: Int = 864, height: Int = 1920, rotation: Int = 0) async {
            await session.handle(.mirrorState(MirrorStateUpdate(sessionId: id, state: .streaming, width: width, height: height, rotation: rotation, audio: true, control: false)))
        }
    }

    @Test func startSendsTheRequestAndGatesTheStream() async {
        let h = Harness()
        await h.connect()
        #expect(await h.session.start(MirrorOptions()))
        #expect(await h.sent.items == [.mirrorStart(MirrorStart(sessionId: "s1", maxSize: 1920, fps: 60, audio: true))])
        #expect(await h.session.state.phase == .starting(waitingForTap: false))
        #expect(await h.session.accepts(session: "s1"))
        #expect(await !h.session.accepts(session: "s0"))
        let token = await h.session.streamOpened(session: "s1")
        #expect(token != nil)
        #expect(await h.session.streamOpened(session: "nope") == nil)
    }

    @Test func aPhoneWithoutMirrorIsUnsupported() async {
        let h = Harness()
        await h.connect(features: ["files"])
        #expect(await !h.session.start(MirrorOptions()))
        #expect(await h.sent.items.isEmpty)
    }

    // Review Focus #4
    @Test func consentHintThenTimeout() async throws {
        let h = Harness(hint: .milliseconds(50), timeout: .milliseconds(150))
        await h.connect()
        _ = await h.session.start(MirrorOptions(maxSize: 1280, fps: 30, audio: false))
        try await eventually { await h.session.state.phase == .starting(waitingForTap: true) }
        try await eventually { await h.session.state.phase == .stopped(.consentTimeout) }
        #expect(await h.sent.items.last == .mirrorStop(MirrorSessionRef(sessionId: "s1")))
        #expect(await !h.session.accepts(session: "s1"))
    }

    @Test func streamingCancelsTheTimeoutAndPings() async throws {
        let h = Harness(hint: .milliseconds(50), timeout: .milliseconds(150), ping: .milliseconds(40))
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        #expect(await h.session.state.phase == .streaming(width: 864, height: 1920, rotation: 0))
        #expect(await h.session.state.audio == true)
        try await eventually { await h.sent.items.contains(.mirrorPing(MirrorPing(t0: 2_000))) }
        try await Task.sleep(for: .milliseconds(200))
        #expect(await h.session.state.phase == .streaming(width: 864, height: 1920, rotation: 0)) // no timeout once streaming
        // phone = mac + 5_000: t1 = 1_000 + 300 + 5_000, t2 = t1 + 100, t3 (now) = 2_000 → ((6_300 − 1_000) + (6_400 − 2_000)) / 2
        await h.session.handle(.mirrorPong(MirrorPong(t0: 1_000, t1: 6_300, t2: 6_400)))
        h.session.meter.record(captureMicros: 105_000, displayedMicros: 150_000)
        #expect(h.session.meter.averageMillis == 49) // offset 4_850 µs → 49.85 ms
    }

    // Review Focus #3
    @Test func rotationUpdatesTheSize() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        await h.streaming(width: 1920, height: 864, rotation: 90)
        #expect(await h.session.state.phase == .streaming(width: 1920, height: 864, rotation: 90))
    }

    @Test func stopsCarryTheirReason() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.session.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .stopped, reason: .denied)))
        #expect(await h.session.state.phase == .stopped(.declined))
        _ = await h.session.start(MirrorOptions())
        await h.streaming("s2")
        await h.session.handle(.mirrorStop(MirrorSessionRef(sessionId: "s2")))
        #expect(await h.session.state.phase == .stopped(.user))
        _ = await h.session.start(MirrorOptions())
        await h.session.stop()
        #expect(await h.session.state.phase == .stopped(.mac))
        #expect(await h.sent.items.last == .mirrorStop(MirrorSessionRef(sessionId: "s3")))
    }

    @Test func messagesForAnOldSessionAreIgnored() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        _ = await h.session.start(MirrorOptions()) // replaces s1 (which is stopped first)
        #expect(await h.sent.items.contains(.mirrorStop(MirrorSessionRef(sessionId: "s1"))))
        await h.streaming("s1")
        #expect(await h.session.state.phase == .starting(waitingForTap: false))
        #expect(await h.session.state.sessionId == "s2")
    }

    // Review Focus #5
    @Test func disconnectStopsTheSession() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        await h.session.connectionChanged(.disconnected)
        #expect(await h.session.state.phase == .stopped(.disconnected))
        #expect(await !h.session.accepts(session: "s1"))
        #expect(await !h.session.state.supported)
    }
}
