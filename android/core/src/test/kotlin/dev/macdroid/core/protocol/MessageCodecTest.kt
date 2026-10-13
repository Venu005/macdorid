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

    @Test fun supportedFeaturesIncludeClipboard() {
        assertEquals(listOf("files", "notifications", "clipboard"), Features.SUPPORTED)
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
}
