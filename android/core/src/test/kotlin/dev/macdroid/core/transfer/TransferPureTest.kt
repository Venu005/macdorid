package dev.macdroid.core.transfer

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferPureTest {
    private val naming = Json.parseToJsonElement(Fixtures.text("file-naming.json")).jsonObject
    private val text = Json.parseToJsonElement(Fixtures.text("transfer-text.json")).jsonObject

    @Test fun sharedSanitizeVectors() {
        for (vector in naming.getValue("sanitize").jsonArray) {
            val input = vector.jsonObject.getValue("in").jsonPrimitive.content
            assertEquals("sanitize($input)", vector.jsonObject.getValue("out").jsonPrimitive.content, FileNaming.sanitize(input))
        }
    }

    @Test fun sharedUniqueVectors() {
        for (vector in naming.getValue("unique").jsonArray) {
            val v = vector.jsonObject
            val taken = v.getValue("taken").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(v.getValue("out").jsonPrimitive.content, FileNaming.unique(v.getValue("name").jsonPrimitive.content) { it in taken })
        }
    }

    @Test fun longNamesKeepTheirExtensionWithin200Bytes() {
        assertEquals("a".repeat(196) + ".txt", FileNaming.sanitize("a".repeat(250) + ".txt"))
        val multibyte = FileNaming.sanitize("é".repeat(150) + ".jpg")
        assertEquals("é".repeat(98) + ".jpg", multibyte)
        assertEquals(200, multibyte.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun uniqueStaysWithin200Bytes() {
        val long = "b".repeat(196) + ".txt"
        assertEquals("b".repeat(192) + " (1).txt", FileNaming.unique(long) { it == long })
    }

    @Test fun sharedTextVectors() {
        for (vector in text.getValue("bytes").jsonArray) {
            val v = vector.jsonObject
            assertEquals(v.getValue("out").jsonPrimitive.content, TransferText.bytes(v.getValue("in").jsonPrimitive.long))
        }
        for (vector in text.getValue("lines").jsonArray) {
            val v = vector.jsonObject
            assertEquals(
                v.getValue("out").jsonPrimitive.content,
                TransferText.line(v.getValue("done").jsonPrimitive.long, v.getValue("total").jsonPrimitive.long, v.getValue("rate").jsonPrimitive.doubleOrNull),
            )
        }
    }

    @Test fun rateIsSmoothedAndSampledAtMostTwiceASecond() {
        val rate = TransferRate()
        rate.record(0, 0)
        assertNull(rate.bytesPerSecond)
        rate.record(500_000, 200_000_000) // 0.2 s later: ignored
        assertNull(rate.bytesPerSecond)
        rate.record(1_000_000, 1_000_000_000)
        assertEquals(1_000_000.0, rate.bytesPerSecond!!, 0.001)
        rate.record(3_000_000, 2_000_000_000)
        assertEquals(1_300_000.0, rate.bytesPerSecond!!, 0.001)
    }
}
