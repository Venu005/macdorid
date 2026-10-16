package dev.macdroid.core.protocol

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageCodecTest {
    @Test fun helloFixtureDecodes() {
        assertEquals(
            Message.Hello(HelloBody(protocol = 1, app = "0.1.0", device = "Nothing Phone (3)", features = emptyList())),
            MessageCodec.decode(Fixtures.text("hello.json")),
        )
    }

    @Test fun deviceStatusFixtureDecodes() {
        assertEquals(Message.DeviceStatus(DeviceStatusBody(82, true)), MessageCodec.decode(Fixtures.text("device.status.json")))
    }

    private val messageFixtures = listOf(
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
        "input.pointer.json", "input.scroll.json", "input.key.json", "input.text.json", "input.notice.json",
        "call.state.json", "call.state.ended.json", "call.action.json", "call.dial.json", "call.error.json",
    )

    @Test fun fixturesRoundTrip() {
        for (name in messageFixtures) {
            val text = Fixtures.text(name)
            val reencoded = MessageCodec.encode(MessageCodec.decode(text), id = Fixtures.id(text))
            assertEquals(name, Json.parseToJsonElement(text), Json.parseToJsonElement(reencoded))
        }
    }

    @Test fun transferFixturesDecode() {
        val id = "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90"
        assertEquals(Message.TransferOffer(TransferOfferBody(id, "IMG_2041.jpg", 3_145_728, "image/jpeg")), MessageCodec.decode(Fixtures.text("transfer.offer.json")))
        assertEquals(Message.TransferAccept(TransferIdBody(id)), MessageCodec.decode(Fixtures.text("transfer.accept.json")))
        assertEquals(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.NoSpace)), MessageCodec.decode(Fixtures.text("transfer.reject.json")))
        assertEquals(Message.TransferFinish(TransferFinishBody(id, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")), MessageCodec.decode(Fixtures.text("transfer.finish.json")))
        assertEquals(Message.TransferResult(TransferResultBody(id, ok = true)), MessageCodec.decode(Fixtures.text("transfer.result.json")))
        assertEquals(Message.TransferResult(TransferResultBody(id, ok = false, error = TransferResultError.ChecksumMismatch)), MessageCodec.decode(Fixtures.text("transfer.result.failed.json")))
        assertEquals(Message.TransferCancel(TransferIdBody(id)), MessageCodec.decode(Fixtures.text("transfer.cancel.json")))
    }

    @Test fun unknownRejectReasonIsMalformed() {
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode("""{"v":1,"type":"transfer.reject","id":"x","body":{"transferId":"t","reason":"bored"}}""")
        }
    }

    @Test fun unknownTypeIsReported() {
        assertEquals(Message.Unknown("future.thing"), MessageCodec.decode(Fixtures.text("unknown-type.json")))
    }

    @Test fun malformedFramesThrow() {
        for (text in listOf("not json", "{}", """{"type":"hello","body":{"protocol":"x"}}""", "[1,2]", """{"type":"hello"}""")) {
            assertThrows(text, MalformedMessageException::class.java) { MessageCodec.decode(text) }
        }
    }

    @Test fun encodingUnknownThrows() {
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.encode(Message.Unknown("x")) }
    }

    @Test fun pairBodiesMatchFixtures() {
        assertEquals(
            PairRequest("MZXW6YTBOI2DGNBVGY3TQOJQGE", "My Phone", "Nothing Phone (3)"),
            MessageCodec.json.decodeFromString(PairRequest.serializer(), Fixtures.text("pair.request.json")),
        )
        assertEquals(PairResponse("MacBook Pro"), MessageCodec.json.decodeFromString(PairResponse.serializer(), Fixtures.text("pair.response.json")))
    }
    @Test fun notificationFixturesDecode() {
        val key = "0|com.whatsapp|1|chat|10123"
        assertEquals(
            Message.NotificationPosted(NotificationPostedBody(key, "com.whatsapp", "WhatsApp", "Mom", "Did you eat?", 1_791_892_800_000,
                listOf(NotificationButton(1, "Mark as read")), canReply = true, iconHash = "3f2a9c1d0b7e4f52", isUpdate = false)),
            MessageCodec.decode(Fixtures.text("notification.posted.json")),
        )
        assertEquals(Message.NotificationRemoved(NotificationKeyBody(key)), MessageCodec.decode(Fixtures.text("notification.removed.json")))
        assertEquals(Message.NotificationSync(NotificationSyncBody(listOf(key, "0|com.google.android.gm|7|null|10140"))), MessageCodec.decode(Fixtures.text("notification.sync.json")))
        assertEquals(Message.NotificationIcon(NotificationIconBody("3f2a9c1d0b7e4f52", "iVBORw0KGgo=")), MessageCodec.decode(Fixtures.text("notification.icon.json")))
        assertEquals(
            Message.NotificationApps(NotificationAppsBody(forwarding = true, access = true, apps = listOf(
                PhoneAppBody("com.whatsapp", "WhatsApp", "3f2a9c1d0b7e4f52", muted = false),
                PhoneAppBody("com.discord", "Discord", "", muted = true),
            ))),
            MessageCodec.decode(Fixtures.text("notification.apps.json")),
        )
        assertEquals(Message.NotificationReply(NotificationReplyBody(key, "On my way")), MessageCodec.decode(Fixtures.text("notification.reply.json")))
        assertEquals(Message.NotificationAction(NotificationActionBody(key, 1)), MessageCodec.decode(Fixtures.text("notification.action.json")))
        assertEquals(Message.NotificationDismiss(NotificationKeyBody(key)), MessageCodec.decode(Fixtures.text("notification.dismiss.json")))
        assertEquals(Message.NotificationMute(NotificationMuteBody("com.discord", muted = true)), MessageCodec.decode(Fixtures.text("notification.mute.json")))
        assertEquals(Message.NotificationError(NotificationErrorBody(key, NotificationErrorReason.Gone)), MessageCodec.decode(Fixtures.text("notification.error.json")))
    }

    @Test fun helloWithoutFeaturesDecodesAsEmpty() {
        assertEquals(
            Message.Hello(HelloBody(protocol = 1, app = "0.3.0", device = "Nothing Phone (3)", features = emptyList())),
            MessageCodec.decode(Fixtures.text("hello.no-features.json")),
        )
    }

    @Test fun unknownNotificationErrorReasonIsMalformed() {
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode("""{"v":1,"type":"notification.error","id":"x","body":{"key":"k","reason":"bored"}}""")
        }
    }

    @Test fun supportedFeaturesIncludeCalls() {
        assertEquals(listOf("files", "notifications", "clipboard", "ring", "links", "media", "photos", "mirror", "control", "calls"), Features.SUPPORTED)
    }

    @Test fun inputFixturesDecode() {
        assertEquals(Message.InputPointer(InputPointerBody(PointerAction.Down, 0.25, 0.5, 123_456)), MessageCodec.decode(Fixtures.text("input.pointer.json")))
        assertEquals(Message.InputScroll(InputScrollBody(0.5, 0.6, 0.0, -0.125)), MessageCodec.decode(Fixtures.text("input.scroll.json")))
        assertEquals(Message.InputKey(InputKeyBody(InputKeyName.VolumeUp)), MessageCodec.decode(Fixtures.text("input.key.json")))
        assertEquals(Message.InputText(InputTextBody("On my way 🙂\n")), MessageCodec.decode(Fixtures.text("input.text.json")))
        assertEquals(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField)), MessageCodec.decode(Fixtures.text("input.notice.json")))
    }

    @Test fun mirrorFixturesDecode() {
        val s = "5e55a0b1-1111-4222-8333-444455556666"
        assertEquals(Message.MirrorStart(MirrorStartBody(s, 1920, 60, true)), MessageCodec.decode(Fixtures.text("mirror.start.json")))
        assertEquals(Message.MirrorState(MirrorStateBody(s, MirrorPhaseName.Streaming, width = 864, height = 1920, rotation = 0, audio = true, control = false)),
            MessageCodec.decode(Fixtures.text("mirror.state.json")))
        assertEquals(Message.MirrorState(MirrorStateBody(s, MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)),
            MessageCodec.decode(Fixtures.text("mirror.state.stopped.json")))
        assertEquals(Message.MirrorStop(MirrorSessionBody(s)), MessageCodec.decode(Fixtures.text("mirror.stop.json")))
        assertEquals(Message.MirrorKeyframe(MirrorSessionBody(s)), MessageCodec.decode(Fixtures.text("mirror.keyframe.json")))
        assertEquals(Message.MirrorPing(MirrorPingBody(1_000_000)), MessageCodec.decode(Fixtures.text("mirror.ping.json")))
        assertEquals(Message.MirrorPong(MirrorPongBody(1_000_000, 9_000_500, 9_000_600)), MessageCodec.decode(Fixtures.text("mirror.pong.json")))
    }

    @Test fun photosFixturesDecode() {
        assertEquals(Message.PhotosAlbums(PhotosAlbumsBody("r1")), MessageCodec.decode(Fixtures.text("photos.albums.json")))
        assertEquals(
            Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.Full, listOf(
                PhotoAlbumBody("all", "All Photos", 4812, "1001"), PhotoAlbumBody("-1739773001", "Camera", 2301, "1001")))),
            MessageCodec.decode(Fixtures.text("photos.albumList.json")),
        )
        assertEquals(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.All, "1791892800:1001", 200)), MessageCodec.decode(Fixtures.text("photos.query.json")))
        assertEquals(
            Message.PhotosPage(PhotosPageBody("r2", PhotoAccess.Partial, listOf(
                PhotoItemBody("1000", PhotoKind.Video, "VID_2041.mp4", "video/mp4", 1_791_892_700_000, 52_428_800, 1920, 1080, 42_000),
                PhotoItemBody("999", PhotoKind.Photo, "IMG_2040.jpg", "image/jpeg", 1_791_892_600_000, 3_145_728, 4000, 3000)), "1791892600:999")),
            MessageCodec.decode(Fixtures.text("photos.page.json")),
        )
        assertEquals(Message.PhotosThumbs(PhotosThumbsBody(listOf("1000", "999"))), MessageCodec.decode(Fixtures.text("photos.thumbs.json")))
        assertEquals(Message.PhotosThumb(PhotosThumbBody("1000", "/9j/4AAQSkZJRg==")), MessageCodec.decode(Fixtures.text("photos.thumb.json")))
        assertEquals(Message.PhotosFetch(PhotosFetchBody("1000", "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")), MessageCodec.decode(Fixtures.text("photos.fetch.json")))
        assertEquals(Message.PhotosFetchFailed(PhotosFetchFailedBody("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", PhotoFetchFailure.Gone)), MessageCodec.decode(Fixtures.text("photos.fetchFailed.json")))
        assertEquals(Message.PhotosChanged, MessageCodec.decode(Fixtures.text("photos.changed.json")))
        assertEquals(
            Message.TransferOffer(TransferOfferBody("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", "VID_2041.mp4", 52_428_800, "video/mp4", TransferPurpose.Photo)),
            MessageCodec.decode(Fixtures.text("transfer.offer.photo.json")),
        )
    }

    @Test fun smallWinsFixturesDecode() {
        assertEquals(Message.DeviceRing(DeviceRingBody(RingAction.Start)), MessageCodec.decode(Fixtures.text("device.ring.json")))
        assertEquals(Message.RingState(RingStateBody(true)), MessageCodec.decode(Fixtures.text("device.ringState.json")))
        assertEquals(Message.LinkOpen(LinkOpenBody("https://github.com/Venu005/macdroid")), MessageCodec.decode(Fixtures.text("link.open.json")))
        assertEquals(
            Message.MediaState(MediaStateBody(active = true, packageName = "com.spotify.music", appName = "Spotify", title = "Song", artist = "Artist",
                durationMs = 215_000, positionMs = 61_000, positionAt = 1_791_892_800_000, playing = true,
                canPrevious = true, canNext = true, canSeek = true, speed = 1.5, artHash = "0123456789abcdef")),
            MessageCodec.decode(Fixtures.text("media.state.json")),
        )
        assertEquals(Message.MediaState(MediaStateBody(active = false)), MessageCodec.decode(Fixtures.text("media.state.idle.json")))
        assertEquals(Message.MediaArt(MediaArtBody("0123456789abcdef", "/9j/4AAQSkZJRg==")), MessageCodec.decode(Fixtures.text("media.art.json")))
        assertEquals(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Seek, 93_000)), MessageCodec.decode(Fixtures.text("media.command.json")))
    }

    @Test fun clipboardFixturesDecode() {
        assertEquals(
            Message.ClipboardSet(ClipboardSetBody("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", ClipboardKind.Text, "https://github.com/Venu005/macdroid")),
            MessageCodec.decode(Fixtures.text("clipboard.set.json")),
        )
        assertEquals(
            Message.TransferOffer(TransferOfferBody("5c1d9e2a-3b4f-4a6c-8d7e-0f1a2b3c4d5e", "clipboard.png", 48_213, "image/png", TransferPurpose.Clipboard)),
            MessageCodec.decode(Fixtures.text("transfer.offer.clipboard.json")),
        )
        assertFalse(MessageCodec.encode(Message.TransferOffer(TransferOfferBody("t", "a", 1, "x/y"))).contains("purpose"))
        assertEquals(1_048_576, ClipboardLimits.MAX_FRAME_BYTES)
    }

    @Test fun callFixturesDecode() {
        val id = "0|com.google.android.dialer|1|null|10123"
        assertEquals(Message.CallState(CallStateBody(id, CallPhase.Ringing, "Mom", "+15551234567")), MessageCodec.decode(Fixtures.text("call.state.json")))
        assertEquals(Message.CallState(CallStateBody(id, CallPhase.Ended)), MessageCodec.decode(Fixtures.text("call.state.ended.json")))
        assertEquals(Message.CallAction(CallActionBody(id, CallActionKind.HangUp)), MessageCodec.decode(Fixtures.text("call.action.json")))
        assertEquals(Message.CallDial(CallDialBody("+15551234567")), MessageCodec.decode(Fixtures.text("call.dial.json")))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.CallGone, id)), MessageCodec.decode(Fixtures.text("call.error.json")))
        assertFalse(MessageCodec.encode(Message.CallState(CallStateBody("c", CallPhase.Ended))).contains("name"))
    }
}
