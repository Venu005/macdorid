package dev.macdroid.core.mirror

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorFrameTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test fun framesMatchTheSharedFixture() {
        val frames = Json.parseToJsonElement(Fixtures.text("mirror-frames.json")).jsonObject["frames"]!!.jsonArray
        for (f in frames.map { it.jsonObject }) {
            val payload = hex(f["payload"]!!.jsonPrimitive.content)
            val encoded = MirrorFrame.encode(f["type"]!!.jsonPrimitive.int, f["key"]!!.jsonPrimitive.boolean, f["captureMicros"]!!.jsonPrimitive.long, payload)
            assertEquals(f["hex"]!!.jsonPrimitive.content, encoded.toHex())
        }
    }

    @Test fun encodesASliceOfABuffer() {
        val buffer = byteArrayOf(9, 9, 1, 2, 9)
        assertEquals("0300000000000000000000050102", MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, 5, buffer, offset = 2, length = 2).toHex())
    }
}
