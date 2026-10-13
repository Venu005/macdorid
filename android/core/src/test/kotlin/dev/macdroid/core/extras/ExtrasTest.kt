package dev.macdroid.core.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import dev.macdroid.core.protocol.MediaCommandBody
import dev.macdroid.core.protocol.MediaCommandKind
import dev.macdroid.core.protocol.Message
import org.junit.Test

class LinkExtractorTest {
    @Test fun findsTheFirstHttpLink() {
        assertEquals("http://y.com/p", LinkExtractor.firstLink("ftp://x and then http://y.com/p and https://z.com"))
        assertNull(LinkExtractor.firstLink("no link here"))
        assertNull(LinkExtractor.firstLink(null))
        assertEquals("HTTPS://Example.com/A", LinkExtractor.firstLink("HTTPS://Example.com/A"))
    }

    // Review Focus #1
    @Test fun trimsTrailingPunctuation() {
        assertEquals("https://x.com/a", LinkExtractor.firstLink("(see https://x.com/a)."))
        assertEquals("https://x.com/a?b=c", LinkExtractor.firstLink("go to https://x.com/a?b=c!"))
        assertEquals("https://x.com/q", LinkExtractor.firstLink("\"https://x.com/q\""))
    }

    // Review Focus #2
    @Test fun onlyHttpAndHttpsWithAHostAreAllowed() {
        assertTrue(LinkExtractor.isAllowed("https://ok.com/x"))
        assertFalse(LinkExtractor.isAllowed("javascript:alert(1)"))
        assertFalse(LinkExtractor.isAllowed("file:///etc/passwd"))
        assertFalse(LinkExtractor.isAllowed("http://"))
        assertFalse(LinkExtractor.isAllowed("https://x.com/" + "a".repeat(8_200)))
    }

    @Test fun oversizedLinksAreNotFound() {
        assertNull(LinkExtractor.firstLink("https://x.com/" + "a".repeat(8_200)))
    }
}

class RingSessionTest {
    @Test fun startsOnceAndStopsOnce() {
        val ring = RingSession()
        assertTrue(ring.start(0))
        assertFalse(ring.start(10)) // already ringing
        assertTrue(ring.stop())
        assertFalse(ring.stop())
    }

    // Review Focus #3
    @Test fun timesOutAfterThirtySeconds() {
        val ring = RingSession()
        ring.start(1_000)
        assertFalse(ring.timedOut(30_999))
        assertTrue(ring.timedOut(31_000))
    }

    // Review Focus #3
    @Test fun stopReportsOnlyWhenRinging() {
        val ring = RingSession()
        assertFalse(ring.stop()) // e.g. a disconnect while idle sends nothing
        assertFalse(ring.timedOut(999_999))
    }
}

class MediaTrackerTest {
    private val sent = mutableListOf<Message>()
    private var now = 1_000_000L
    private val tracker = MediaTracker({ sent.add(it); true }, clock = { now })
    private val art = MediaArtwork("0123456789abcdef", "/9j/4AAQ")

    private fun song(
        pkg: String = "com.spotify.music", title: String = "Song", playing: Boolean = true, positionMs: Long = 10_000,
        positionAt: Long = now, lastActiveAt: Long = now, artwork: MediaArtwork? = art,
    ) = MediaSnapshot(pkg, "Spotify", title, "Artist", 200_000, positionMs, positionAt, playing,
        canPrevious = true, canNext = true, canSeek = true, art = artwork, lastActiveAt = lastActiveAt)

    private inline fun <reified T : Message> sentOf() = sent.filterIsInstance<T>()

    @Test fun sendsArtThenStateForThePlayingSession() {
        tracker.connected()
        tracker.update(listOf(song(pkg = "a", playing = false), song(pkg = "b")))
        assertTrue(sent.indexOfFirst { it is Message.MediaArt } < sent.indexOfLast { it is Message.MediaState })
        assertEquals("b", sentOf<Message.MediaState>().last().body.packageName)
    }

    @Test fun recentlyPausedIsShownStaleIsNot() {
        tracker.connected()
        tracker.update(listOf(song(playing = false, lastActiveAt = now - 60_000)))
        assertEquals(true, sentOf<Message.MediaState>().last().body.active)
        tracker.update(listOf(song(playing = false, lastActiveAt = now - 400_000)))
        assertEquals(false, sentOf<Message.MediaState>().last().body.active)
    }

    // Review Focus #4
    @Test fun normalPlaybackIsNotResent() {
        tracker.connected()
        tracker.update(listOf(song(positionMs = 10_000, positionAt = now)))
        val count = sentOf<Message.MediaState>().size
        now += 5_000
        tracker.update(listOf(song(positionMs = 15_000, positionAt = now)))
        assertEquals(count, sentOf<Message.MediaState>().size)
    }

    // Review Focus #4
    @Test fun aSeekIsResent() {
        tracker.connected()
        tracker.update(listOf(song(positionMs = 10_000, positionAt = now)))
        val count = sentOf<Message.MediaState>().size
        tracker.update(listOf(song(positionMs = 90_000, positionAt = now)))
        assertEquals(count + 1, sentOf<Message.MediaState>().size)
    }

    @Test fun pauseAndTrackChangesAreResent() {
        tracker.connected()
        tracker.update(listOf(song()))
        tracker.update(listOf(song(playing = false)))
        tracker.update(listOf(song(playing = false, title = "Next")))
        assertEquals(listOf(true, false, false), sentOf<Message.MediaState>().filter { it.body.active }.map { it.body.playing })
        assertEquals("Next", sentOf<Message.MediaState>().last().body.title)
    }

    @Test fun artIsSentOncePerConnection() {
        tracker.connected()
        tracker.update(listOf(song()))
        tracker.update(listOf(song(playing = false)))
        assertEquals(1, sentOf<Message.MediaArt>().size)
        tracker.disconnected()
        tracker.connected() // resends the current state, and its art
        assertEquals(2, sentOf<Message.MediaArt>().size)
        assertEquals(false, sentOf<Message.MediaState>().last().body.playing)
    }

    @Test fun nothingIsSentWhileDisconnectedAndIdleIsSentOnce() {
        tracker.update(listOf(song()))
        assertTrue(sent.isEmpty())
        tracker.connected()
        tracker.update(emptyList())
        tracker.update(emptyList())
        assertEquals(1, sentOf<Message.MediaState>().count { !it.body.active })
    }

    @Test fun commandsTargetTheShownSession() {
        tracker.connected()
        tracker.update(listOf(song(pkg = "com.spotify.music")))
        assertEquals(MediaControl("com.spotify.music", MediaCommandKind.Seek, 60_000),
            tracker.handle(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Seek, 60_000))))
        tracker.update(emptyList())
        assertNull(tracker.handle(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Play))))
    }
}
