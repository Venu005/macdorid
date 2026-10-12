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

    @Test fun fixturesRoundTrip() {
        for (name in listOf("hello.json", "device.status.json")) {
            val text = Fixtures.text(name)
            val reencoded = MessageCodec.encode(MessageCodec.decode(text), id = Fixtures.id(text))
            assertEquals(name, Json.parseToJsonElement(text), Json.parseToJsonElement(reencoded))
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
