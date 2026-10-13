package dev.macdroid.core.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
