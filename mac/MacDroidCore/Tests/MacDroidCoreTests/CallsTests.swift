import Foundation
import Testing
@testable import MacDroidCore

@Suite struct CallsTests {
    final class Harness: Sendable {
        let center: CallCenter
        let sent: Collector<Message>
        init(showIncoming: Bool = true) {
            let sent = Collector<Message>()
            center = CallCenter(now: { Date(timeIntervalSince1970: 1_000) }, send: { await sent.append($0) }, showIncoming: showIncoming)
            self.sent = sent
        }
        func connect(features: [String] = ProtocolInfo.features) async {
            await center.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.10.0", features: features)))
        }
        func phone(_ id: String, _ phase: CallPhase, name: String? = "Mom", number: String? = "+15551234567") async {
            await center.handle(.callState(CallState(callId: id, state: phase, name: name, number: number)))
        }
    }

    @Test func ringingShowsThePanelAndAnsweringSendsTheAction() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        guard case .ringing(let call) = await h.center.state.panel else { Issue.record("no ringing panel"); return }
        #expect(call.name == "Mom")
        await h.center.answer(callId: "k1")
        #expect(await h.sent.items == [.callAction(CallAction(callId: "k1", action: .answer))])
        await h.phone("k1", .active)
        guard case .active(let active) = await h.center.state.panel else { Issue.record("no active panel"); return }
        #expect(active.since == Date(timeIntervalSince1970: 1_000))
        #expect(await h.center.state.activeCall?.callId == "k1")
        await h.phone("k1", .ended)
        #expect(await h.center.state.panel == .none)
    }

    // Review Focus #1
    @Test func ringingWinsOverActiveAndEndedCallsLeave() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .active)
        await h.phone("k2", .ringing, name: "Dad")
        guard case .ringing(let ringing) = await h.center.state.panel else { Issue.record("no ringing panel"); return }
        #expect(ringing.callId == "k2")
        await h.phone("k2", .ended)
        guard case .active(let active) = await h.center.state.panel else { Issue.record("k1 lost"); return }
        #expect(active.callId == "k1")
    }

    // Review Focus #2
    @Test func errorsBecomeNoticesAndDropTheCall() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        await h.center.answer(callId: "k1")
        await h.center.handle(.callError(CallError(reason: .callGone, callId: "k1")))
        #expect(await h.center.state.panel == .notice("Couldn't answer — use your phone"))
        #expect(await h.center.state.calls.isEmpty)
        await h.center.clearNotice()
        #expect(await h.center.state.panel == .none)
        await h.center.handle(.callError(CallError(reason: .noPermission)))
        #expect(await h.center.state.panel == .notice("Allow MacDroid to make calls on your phone (phone Settings → Calls)"))
    }

    // Review Focus #5
    @Test func disconnectClearsCalls() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        await h.center.connectionChanged(.disconnected)
        #expect(await h.center.state.panel == .none)
        #expect(await h.center.state.calls.isEmpty)
    }

    @Test func hidingIncomingCallsHidesThePanelButKeepsTheCall() async {
        let h = Harness(showIncoming: false)
        await h.connect()
        await h.phone("k1", .active)
        #expect(await h.center.state.panel == .none)
        #expect(await h.center.state.activeCall?.callId == "k1")
    }

    @Test func dialling() async {
        let h = Harness()
        #expect(await h.center.dial("+1 555 123 4567") == .notConnected)
        await h.connect(features: [Feature.files])
        #expect(await h.center.dial("+1 555 123 4567") == .unsupported)
        await h.connect()
        #expect(await h.center.dial("hello") == .invalidNumber)
        #expect(await h.center.dial("+1 (555) 123-4567") == .sent)
        #expect(await h.sent.items == [.callDial(CallDial(number: "+15551234567"))])
    }

    // Review Focus #4
    @Test func phoneNumbersAreCleaned() {
        #expect(PhoneNumbers.clean(" +1 (555) 123-4567 ") == "+15551234567")
        #expect(PhoneNumbers.clean("1-800-FLOWERS") == "1800")
        #expect(PhoneNumbers.clean("*#06#") == "*#06#")
        #expect(PhoneNumbers.clean("1+5") == "15")
        #expect(PhoneNumbers.clean("call me") == nil)
        #expect(PhoneNumbers.clean("+") == nil)
        #expect(PhoneNumbers.clean(String(repeating: "1", count: 33)) == nil)
    }

    // Review Focus #4
    @Test func telLinksAreParsed() {
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:+15551234567")!) == "+15551234567")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:%2B1%20555%20123%204567")!) == "+15551234567")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel://555-0100")!) == "5550100")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:555-0100;ext=12")!) == "5550100")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:")!) == nil)
        #expect(PhoneNumbers.fromTelURL(URL(string: "https://example.com")!) == nil)
    }

    @Test func presentation() {
        let both = CallInfo(callId: "k", name: "Mom", number: "+15551234567", phase: .ringing, since: nil)
        let numberOnly = CallInfo(callId: "k", name: nil, number: "+15551234567", phase: .ringing, since: nil)
        let nothing = CallInfo(callId: "k", name: nil, number: nil, phase: .ringing, since: nil)
        #expect(CallPresentation.title(both) == "Mom")
        #expect(CallPresentation.detail(both) == "+15551234567")
        #expect(CallPresentation.title(numberOnly) == "+15551234567")
        #expect(CallPresentation.detail(numberOnly) == nil)
        #expect(CallPresentation.title(nothing) == "Unknown caller")
        let start = Date(timeIntervalSince1970: 0)
        #expect(CallPresentation.duration(from: start, to: start.addingTimeInterval(65)) == "1:05")
        #expect(CallPresentation.duration(from: start, to: start.addingTimeInterval(3_725)) == "1:02:05")
        #expect(CallPresentation.errorNotice(.callGone, action: .decline) == "Couldn't decline — use your phone")
        #expect(CallPresentation.errorNotice(.notSupported, action: .hangUp) == "Couldn't hang up — use your phone")
        #expect(CallPresentation.errorNotice(.callGone, action: nil) == "Couldn't answer — use your phone")
        #expect(CallPresentation.confirmTitle(number: "+15551234567") == "Call +15551234567 on your phone?")
        #expect(CallPresentation.dialProblem(.notConnected) == "Your phone isn't connected")
        #expect(CallPresentation.dialProblem(.unsupported) == "Update MacDroid on your phone to use calls")
        #expect(CallPresentation.dialProblem(.invalidNumber) == "That doesn't look like a phone number")
        #expect(CallPresentation.dialProblem(.sent) == nil)
    }

    // Final review I1: only a call that's gone is dropped; a refused action keeps the live call.
    @Test func notSupportedKeepsTheCall() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .active)
        await h.center.answer(callId: "k1")
        await h.center.handle(.callError(CallError(reason: .notSupported, callId: "k1")))
        #expect(await h.center.state.calls.map(\.callId) == ["k1"])
        await h.center.clearNotice()
        guard case .active = await h.center.state.panel else { Issue.record("live call lost"); return }
    }

    // Final review I3: a web page's tel: link must not run a phone code (call forwarding, USSD).
    @Test func phoneCodesInLinksAreRefused() {
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:**21*+15551234%23")!) == nil)
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:*%2306%23")!) == nil)
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:+15551234567")!) == "+15551234567")
        #expect(PhoneNumbers.clean("*#06#") == "*#06#") // the number pad still allows codes
    }
}
