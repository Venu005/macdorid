import Foundation
import Testing
@testable import MacDroidCore

@Suite struct ProtocolFixtureTests {
    @Test func helloFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("hello.json"))
        #expect(message == .hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: [])))
    }

    @Test func deviceStatusFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("device.status.json"))
        #expect(message == .deviceStatus(DeviceStatus(battery: 82, charging: true)))
    }

    static let messageFixtures = [
        "hello.json", "device.status.json",
        "transfer.offer.json", "transfer.accept.json", "transfer.reject.json", "transfer.finish.json",
        "transfer.result.json", "transfer.result.failed.json", "transfer.cancel.json",
        "notification.posted.json", "notification.removed.json", "notification.sync.json", "notification.icon.json",
        "notification.apps.json", "notification.reply.json", "notification.action.json", "notification.dismiss.json",
        "notification.mute.json", "notification.error.json",
        "clipboard.set.json", "transfer.offer.clipboard.json",
    ]

    @Test(arguments: messageFixtures)
    func fixturesRoundTrip(name: String) throws {
        let text = try Fixtures.text(name)
        let reencoded = try MessageCodec.encode(try MessageCodec.decode(text), id: try Fixtures.id(of: text))
        #expect(try Fixtures.jsonObject(reencoded) == Fixtures.jsonObject(text))
    }

    @Test func transferFixturesDecode() throws {
        let id = "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90"
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.json")) == .transferOffer(TransferOffer(transferId: id, name: "IMG_2041.jpg", size: 3_145_728, mime: "image/jpeg")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.accept.json")) == .transferAccept(TransferID(transferId: id)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.reject.json")) == .transferReject(TransferReject(transferId: id, reason: .noSpace)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.finish.json")) == .transferFinish(TransferFinish(transferId: id, sha256: "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.json")) == .transferResult(TransferResult(transferId: id, ok: true)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.failed.json")) == .transferResult(TransferResult(transferId: id, ok: false, error: .checksumMismatch)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.cancel.json")) == .transferCancel(TransferID(transferId: id)))
    }

    @Test func unknownRejectReasonIsMalformed() {
        let text = #"{"v":1,"type":"transfer.reject","id":"x","body":{"transferId":"t","reason":"bored"}}"#
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func unknownTypeIsReportedNotRejected() throws {
        #expect(try MessageCodec.decode(try Fixtures.text("unknown-type.json")) == .unknown(type: "future.thing"))
    }

    @Test(arguments: ["not json", "{}", #"{"type":"hello","body":{"protocol":"x"}}"#, "[1,2]"])
    func malformedFramesThrow(text: String) {
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func encodingUnknownThrows() {
        #expect(throws: MessageCodecError.cannotEncodeUnknown("x")) { try MessageCodec.encode(.unknown(type: "x")) }
    }

    @Test func pairBodiesMatchFixtures() throws {
        let request = try JSONDecoder().decode(PairRequest.self, from: Data(try Fixtures.text("pair.request.json").utf8))
        #expect(request == PairRequest(code: "MZXW6YTBOI2DGNBVGY3TQOJQGE", deviceName: "My Phone", model: "Nothing Phone (3)"))
        let response = try JSONDecoder().decode(PairResponse.self, from: Data(try Fixtures.text("pair.response.json").utf8))
        #expect(response == PairResponse(macName: "MacBook Pro"))
    }
    @Test func notificationFixturesDecode() throws {
        let key = "0|com.whatsapp|1|chat|10123"
        #expect(try MessageCodec.decode(Fixtures.text("notification.posted.json")) == .notificationPosted(NotificationPosted(
            key: key, packageName: "com.whatsapp", appName: "WhatsApp", title: "Mom", text: "Did you eat?",
            postedAt: 1_791_892_800_000, actions: [NotificationButton(index: 1, title: "Mark as read")],
            canReply: true, iconHash: "3f2a9c1d0b7e4f52", isUpdate: false
        )))
        #expect(try MessageCodec.decode(Fixtures.text("notification.removed.json")) == .notificationRemoved(NotificationKey(key: key)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.sync.json")) == .notificationSync(NotificationSync(keys: [key, "0|com.google.android.gm|7|null|10140"])))
        #expect(try MessageCodec.decode(Fixtures.text("notification.icon.json")) == .notificationIcon(NotificationIcon(hash: "3f2a9c1d0b7e4f52", png: "iVBORw0KGgo=")))
        #expect(try MessageCodec.decode(Fixtures.text("notification.apps.json")) == .notificationApps(NotificationApps(forwarding: true, access: true, apps: [
            PhoneApp(packageName: "com.whatsapp", appName: "WhatsApp", iconHash: "3f2a9c1d0b7e4f52", muted: false),
            PhoneApp(packageName: "com.discord", appName: "Discord", iconHash: "", muted: true),
        ])))
        #expect(try MessageCodec.decode(Fixtures.text("notification.reply.json")) == .notificationReply(NotificationReply(key: key, text: "On my way")))
        #expect(try MessageCodec.decode(Fixtures.text("notification.action.json")) == .notificationAction(NotificationActionRequest(key: key, actionIndex: 1)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.dismiss.json")) == .notificationDismiss(NotificationKey(key: key)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.mute.json")) == .notificationMute(NotificationMute(packageName: "com.discord", muted: true)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.error.json")) == .notificationError(NotificationError(key: key, reason: .gone)))
    }

    @Test func helloWithoutFeaturesDecodesAsEmpty() throws {
        #expect(try MessageCodec.decode(Fixtures.text("hello.no-features.json")) == .hello(Hello(app: "0.3.0", device: "Nothing Phone (3)", features: [])))
    }

    @Test func unknownNotificationErrorReasonIsMalformed() {
        let text = #"{"v":1,"type":"notification.error","id":"x","body":{"key":"k","reason":"bored"}}"#
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func supportedFeaturesIncludeClipboard() {
        #expect(ProtocolInfo.features == [Feature.files, Feature.notifications, Feature.clipboard])
    }

    @Test func clipboardFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("clipboard.set.json")) == .clipboardSet(ClipboardSet(
            itemId: "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", kind: .text, text: "https://github.com/Venu005/macdroid"
        )))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.clipboard.json")) == .transferOffer(TransferOffer(
            transferId: "5c1d9e2a-3b4f-4a6c-8d7e-0f1a2b3c4d5e", name: "clipboard.png", size: 48_213, mime: "image/png", purpose: .clipboard
        )))
        // A plain file offer has no purpose on the wire (spec §8.9).
        let encoded = try MessageCodec.encode(.transferOffer(TransferOffer(transferId: "t", name: "a", size: 1, mime: "x/y")))
        #expect(!encoded.contains("purpose"))
    }

    @Test func clipboardLimitMatchesTheServerFrameLimit() {
        #expect(ClipboardLimits.maxFrameBytes == MacDroidServer.maxMessageSize)
        #expect(ClipboardLimits.maxImageBytes == 10 * 1024 * 1024)
    }
}
