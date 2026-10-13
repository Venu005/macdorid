package dev.macdroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.macdroid.app.platform.MediaStorePart
import dev.macdroid.app.platform.MediaStoreSaveTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The default save target on a real device: the part file must take appended bytes, resume, and finish. */
@RunWith(AndroidJUnit4::class)
class MediaStoreSaveTargetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun partFileLifecycle(): Unit = runBlocking {
        val bytes = ByteArray(10_000) { it.toByte() }
        val part = requireNotNull(MediaStoreSaveTarget(context).createPart("macdroid-test.bin", "application/octet-stream", "t1")) { "createPart returned null" }
        try {
            assertEquals("fresh part is empty", 0L, part.length())
            part.openRead().use { assertEquals(-1, it.read()) }
            part.openAppend().use { it.write(bytes, 0, 4_000) }
            val reopened = requireNotNull(MediaStorePart.reopen(context, part.handle)) { "reopen returned null" }
            assertEquals("bytes survive a reopen", 4_000L, reopened.length())
            reopened.openAppend().use { it.write(bytes, 4_000, 6_000) } // a resume appends, never truncates
            assertArrayEquals(bytes, reopened.openRead().use { it.readBytes() })
            reopened.finish()
        } finally {
            part.delete()
        }
    }
}
