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

    // Final review C1: a mouse wheel reports lines, not points; one notch must be a real swipe, not a sub-slop tap.
    @Test func wheelLinesScaleToPoints() {
        let s = InputMapper.scroll(deltaX: 0, deltaY: -1, precise: false, in: CGSize(width: 500, height: 1200))
        #expect(abs(s.dy - (-InputMapper.pointsPerLine / 1200)) < 1e-9)
        #expect(InputMapper.pointsPerLine / 1200 >= 0.03)
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
        #expect(MirrorKeys.action(keyCode: 123, characters: "\u{F702}", command: false) == .swallow) // arrows: not sent, and focus stays on the picture
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

@Suite struct MirrorWindowFitTests {
    @Test func landscapeKeepsTheLongEdge() {
        // A portrait picture 400×889 (plus the bar) turns 20:9 landscape: the long edge, 889, becomes the width.
        let s = MirrorWindowFit.content(current: CGSize(width: 400, height: 889 + 44), aspect: CGSize(width: 20, height: 9), barHeight: 44)
        #expect(s.width == 889)
        #expect(s.height == (889.0 * 9 / 20).rounded() + 44)
    }

    @Test func backToPortraitKeepsTheLongEdgeAsTheHeight() {
        let s = MirrorWindowFit.content(current: CGSize(width: 889, height: 400 + 44), aspect: CGSize(width: 9, height: 20), barHeight: 44)
        #expect(s.width == 889.0 * 9 / 20)
        #expect(s.height == CGFloat(889 + 44))
    }

    @Test func portraitToPortraitKeepsTheWidth() {
        let s = MirrorWindowFit.content(current: CGSize(width: 400, height: 933), aspect: CGSize(width: 9, height: 20), barHeight: 44)
        #expect(s.width == 400)
        #expect(s.height == (400.0 * 20 / 9).rounded() + 44)
    }

    @Test func scrollIsAFractionOfThePictureNotTheView() {
        // View 400×400, picture 200×400 drawn 200 wide and 400 tall: 20 points is 0.1 of its width.
        let d = InputMapper.scroll(deltaX: 20, deltaY: 40, precise: true, in: CGSize(width: 400, height: 400), picture: CGSize(width: 200, height: 400))
        #expect(d.dx == 0.1)
        #expect(d.dy == 0.1)
    }

    @Test func pictureRectIsCentredAspectFit() {
        let r = InputMapper.pictureRect(in: CGSize(width: 400, height: 400), picture: CGSize(width: 200, height: 400))
        #expect(r == CGRect(x: 100, y: 0, width: 200, height: 400))
    }
}

@Suite struct MirrorKeysTests {
    @Test func escLeavesFullScreenFirst() {
        #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false, fullScreen: true) == .leaveFullScreen)
        #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false, fullScreen: false) == .key(.back))
    }

    @Test func tabAndArrowsStayOnThePicture() {
        #expect(MirrorKeys.action(keyCode: 48, characters: "\t", command: false, fullScreen: false) == .swallow)
        #expect(MirrorKeys.action(keyCode: 123, characters: "\u{F702}", command: false, fullScreen: false) == .swallow)
    }

    @Test func otherCommandShortcutsStillReachTheMenu() {
        #expect(MirrorKeys.action(keyCode: 12, characters: "q", command: true, fullScreen: false) == nil)
    }
}

@Suite struct InputTextCapTests {
    @Test func capsInUtf16UnitsWithoutSplittingAPair() {
        let text = String(repeating: "a", count: 4095) + "😀"
        #expect(InputText.capped(text).utf16.count == 4095)
        #expect(InputText.capped(String(repeating: "a", count: 5000)).utf16.count == 4096)
        #expect(InputText.capped("hi") == "hi")
    }
}
