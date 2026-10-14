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
        "device.ring.json", "device.ringState.json", "link.open.json", "media.state.json", "media.state.idle.json",
        "media.art.json", "media.command.json",
        "photos.albums.json", "photos.albumList.json", "photos.query.json", "photos.page.json", "photos.thumbs.json",
        "photos.thumb.json", "photos.fetch.json", "photos.fetchFailed.json", "photos.changed.json", "transfer.offer.photo.json",
        "mirror.start.json", "mirror.state.json", "mirror.state.stopped.json", "mirror.stop.json", "mirror.keyframe.json",
        "mirror.ping.json", "mirror.pong.json",
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

    @Test func supportedFeaturesIncludeMirror() {
        #expect(ProtocolInfo.features == [Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media, Feature.photos, Feature.mirror])
    }

    @Test func mirrorFixturesDecode() throws {
        let s = "5e55a0b1-1111-4222-8333-444455556666"
        #expect(try MessageCodec.decode(Fixtures.text("mirror.start.json")) == .mirrorStart(MirrorStart(sessionId: s, maxSize: 1920, fps: 60, audio: true)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.state.json")) == .mirrorState(MirrorStateUpdate(
            sessionId: s, state: .streaming, width: 864, height: 1920, rotation: 0, audio: true, control: false)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.state.stopped.json")) == .mirrorState(MirrorStateUpdate(sessionId: s, state: .stopped, reason: .consentTimeout)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.stop.json")) == .mirrorStop(MirrorSessionRef(sessionId: s)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.keyframe.json")) == .mirrorKeyframe(MirrorSessionRef(sessionId: s)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.ping.json")) == .mirrorPing(MirrorPing(t0: 1_000_000)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.pong.json")) == .mirrorPong(MirrorPong(t0: 1_000_000, t1: 9_000_500, t2: 9_000_600)))
    }

    @Test func photosFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("photos.albums.json")) == .photosAlbums(PhotosAlbumsRequest(requestId: "r1")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.albumList.json")) == .photosAlbumList(PhotosAlbumList(requestId: "r1", access: .full, albums: [
            PhotoAlbum(id: "all", name: "All Photos", count: 4812, coverId: "1001"),
            PhotoAlbum(id: "-1739773001", name: "Camera", count: 2301, coverId: "1001"),
        ])))
        #expect(try MessageCodec.decode(Fixtures.text("photos.query.json")) == .photosQuery(PhotosQuery(requestId: "r2", albumId: "all", kind: .all, cursor: "1791892800:1001", limit: 200)))
        #expect(try MessageCodec.decode(Fixtures.text("photos.page.json")) == .photosPage(PhotosPage(requestId: "r2", access: .partial, items: [
            PhotoItem(id: "1000", kind: .video, name: "VID_2041.mp4", mime: "video/mp4", takenAt: 1_791_892_700_000, size: 52_428_800, width: 1920, height: 1080, durationMs: 42_000),
            PhotoItem(id: "999", kind: .photo, name: "IMG_2040.jpg", mime: "image/jpeg", takenAt: 1_791_892_600_000, size: 3_145_728, width: 4000, height: 3000),
        ], nextCursor: "1791892600:999")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.thumbs.json")) == .photosThumbs(PhotosThumbs(ids: ["1000", "999"])))
        #expect(try MessageCodec.decode(Fixtures.text("photos.thumb.json")) == .photosThumb(PhotosThumb(id: "1000", jpeg: "/9j/4AAQSkZJRg==")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.fetch.json")) == .photosFetch(PhotosFetch(id: "1000", transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.fetchFailed.json")) == .photosFetchFailed(PhotosFetchFailed(transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", reason: .gone)))
        #expect(try MessageCodec.decode(Fixtures.text("photos.changed.json")) == .photosChanged)
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.photo.json")) == .transferOffer(TransferOffer(
            transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", name: "VID_2041.mp4", size: 52_428_800, mime: "video/mp4", purpose: .photo
        )))
    }

    @Test func smallWinsFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("device.ring.json")) == .deviceRing(DeviceRing(action: .start)))
        #expect(try MessageCodec.decode(Fixtures.text("device.ringState.json")) == .ringState(RingState(ringing: true)))
        #expect(try MessageCodec.decode(Fixtures.text("link.open.json")) == .linkOpen(LinkOpen(url: "https://github.com/Venu005/macdroid")))
        #expect(try MessageCodec.decode(Fixtures.text("media.state.json")) == .mediaState(MediaState(
            active: true, packageName: "com.spotify.music", appName: "Spotify", title: "Song", artist: "Artist",
            durationMs: 215_000, positionMs: 61_000, positionAt: 1_791_892_800_000, playing: true,
            canPrevious: true, canNext: true, canSeek: true, artHash: "0123456789abcdef"
        )))
        #expect(try MessageCodec.decode(Fixtures.text("media.state.idle.json")) == .mediaState(.idle))
        #expect(try MessageCodec.decode(Fixtures.text("media.art.json")) == .mediaArt(MediaArt(hash: "0123456789abcdef", jpeg: "/9j/4AAQSkZJRg==")))
        #expect(try MessageCodec.decode(Fixtures.text("media.command.json")) == .mediaCommand(MediaCommand(command: .seek, positionMs: 93_000)))
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
