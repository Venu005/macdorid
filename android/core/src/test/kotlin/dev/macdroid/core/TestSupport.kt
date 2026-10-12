package dev.macdroid.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

object Fixtures {
    private val dir = File(System.getProperty("fixturesDir") ?: error("fixturesDir system property not set"))
    fun text(name: String): String = File(dir, name).readText()
    fun id(text: String): String = Json.parseToJsonElement(text).jsonObject.getValue("id").jsonPrimitive.content
}

/** Polls [condition] until true or fails after [timeoutMs]. */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        Thread.sleep(20)
    }
    fail("condition not met within ${timeoutMs}ms")
}
