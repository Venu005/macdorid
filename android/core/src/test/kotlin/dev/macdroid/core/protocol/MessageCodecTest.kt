package dev.macdroid.core.protocol

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
