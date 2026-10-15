import CoreGraphics
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct MirrorInputTests {
    @Test func pointsMapToFractionsOfThePicture() {
        // A 1080×2400 picture shown in a 540×1200 view: exact fit.
        let p = InputMapper.fraction(of: CGPoint(x: 135, y: 600), in: CGSize(width: 540, height: 1200), picture: CGSize(width: 1080, height: 2400))
        #expect(p == CGPoint(x: 0.25, y: 0.5))
    }

    // Review Focus #4
    @Test func pointsOutsideThePictureAreIgnored() {
        // Portrait picture in a wide view: black bars left and right (picture is 450 wide, centred → 75…525).
        let view = CGSize(width: 600, height: 1000), picture = CGSize(width: 900, height: 2000)
        #expect(InputMapper.fraction(of: CGPoint(x: 50, y: 500), in: view, picture: picture) == nil)
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: 500), in: view, picture: picture) == CGPoint(x: 0.5, y: 0.5))
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: -1), in: view, picture: picture) == nil)
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: 500), in: view, picture: .zero) == nil)
    }

    @Test func scrollIsAFractionOfTheView() {
        let s = InputMapper.scroll(deltaX: 0, deltaY: -150, in: CGSize(width: 500, height: 1200))
        #expect(s.dx == 0)
        #expect(s.dy == -0.125)
    }

    @Test func movesAreThrottledTo120Hz() {
        var throttle = MoveThrottle()
        let first = throttle.allows(atMillis: 0)
        let tooSoon = throttle.allows(atMillis: 5)
        let later = throttle.allows(atMillis: 9)
        #expect(first)
        #expect(!tooSoon)
        #expect(later)
    }

    @Test func keysMapToActions() {
        #expect(MirrorKeys.action(keyCode: 0, characters: "a", command: false) == .text("a"))
        #expect(MirrorKeys.action(keyCode: 36, characters: "\r", command: false) == .text("\n"))
        #expect(MirrorKeys.action(keyCode: 51, characters: "\u{7F}", command: false) == .key(.backspace))
        #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false) == .key(.back))
        #expect(MirrorKeys.action(keyCode: 9, characters: "v", command: true) == .paste)
        #expect(MirrorKeys.action(keyCode: 0, characters: "a", command: true) == nil) // other shortcuts stay on the Mac
        #expect(MirrorKeys.action(keyCode: 123, characters: "\u{F702}", command: false) == nil) // arrows: not sent
    }

    func session(features: [String] = ProtocolInfo.features) async -> (MirrorSession, Collector<Message>) {
        let sent = Collector<Message>()
        let s = MirrorSession(makeId: { "s1" }, send: { await sent.append($0) })
        await s.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.8.0", features: features)))
        return (s, sent)
    }

    // Review Focus #2
    @Test func inputIsSentOnlyWhileStreamingWithControl() async {
        let (s, sent) = await session()
        let tap = Message.inputPointer(InputPointer(action: .down, x: 0.5, y: 0.5, t: 1))
        #expect(await !s.sendInput(tap)) // no session
        _ = await s.start(MirrorOptions())
        #expect(await !s.sendInput(tap)) // waiting for consent
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: false)))
        #expect(await !s.sendInput(tap)) // control service off
        #expect(await !s.state.controlAvailable)
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        #expect(await s.state.controlAvailable)
        #expect(await s.sendInput(tap))
        await s.stop()
        #expect(await !s.sendInput(tap)) // stopped
        #expect(await sent.items.filter { if case .inputPointer = $0 { true } else { false } }.count == 1)
    }

    @Test func aPhoneWithoutControlGetsNoInput() async {
        let (s, _) = await session(features: ["files", "mirror"])
        _ = await s.start(MirrorOptions())
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        #expect(await !s.state.controlAvailable)
        #expect(await !s.sendInput(.inputKey(InputKey(key: .home))))
    }

    @Test func noTextFieldNoticeShowsUntilTheNextText() async {
        let (s, _) = await session()
        _ = await s.start(MirrorOptions())
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        await s.handle(.inputNotice(InputNotice(reason: .noTextField)))
        #expect(await s.state.textFieldMissing)
        #expect(MirrorPresentation.textFieldNotice == "Click a text field on the phone first")
        _ = await s.sendInput(.inputText(InputText(text: "x")))
        #expect(await !s.state.textFieldMissing)
    }

    @Test func controlOffNotice() {
        var state = MirrorState()
        state.phase = .streaming(width: 1, height: 1, rotation: 0)
        state.connected = true
        state.phoneFeatures = [Feature.mirror, Feature.control]
        state.control = false
        #expect(MirrorPresentation.controlNotice(state) == "Turn on MacDroid control on your phone to use the mouse.")
        state.control = true
        #expect(MirrorPresentation.controlNotice(state) == nil)
    }
}
