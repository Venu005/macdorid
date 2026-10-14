package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoKindFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQueriesTest {
    @Test fun cursorRoundTrips() {
        assertEquals("1791892800:1001", PhotoQueries.encodeCursor(1_791_892_800, 1001))
        assertEquals(1_791_892_800L to 1001L, PhotoQueries.decodeCursor("1791892800:1001"))
        assertNull(PhotoQueries.decodeCursor("nonsense"))
        assertNull(PhotoQueries.decodeCursor(null))
        assertNull(PhotoQueries.decodeCursor("1:2:3"))
    }

    // Review Focus #2
    @Test fun cursorSelectsStrictlyOlderItems() {
        val (sql, args) = PhotoQueries.selection("all", PhotoKindFilter.All, "1791892800:1001")
        assertEquals("media_type IN (1,3) AND (date_added < ? OR (date_added = ? AND _id < ?))", sql)
        assertArrayEquals(arrayOf("1791892800", "1791892800", "1001"), args)
    }

    @Test fun albumAndKindFilters() {
        val (sql, args) = PhotoQueries.selection("-1739773001", PhotoKindFilter.Videos, null)
        assertEquals("media_type IN (3) AND bucket_id = ?", sql)
        assertArrayEquals(arrayOf("-1739773001"), args)
        assertEquals("media_type IN (1)", PhotoQueries.selection("all", PhotoKindFilter.Photos, null).first)
    }

    @Test fun limitsAndIds() {
        assertEquals(200, PhotoQueries.pageLimit(10_000))
        assertEquals(1, PhotoQueries.pageLimit(0))
        val ids = (1..80).map { it.toString() } + listOf("../x", "12/../3", "", "5")
        assertEquals((1..60).map { it.toString() }, PhotoQueries.thumbIds(ids))
        assertTrue(PhotoQueries.isValidId("1000"))
        assertFalse(PhotoQueries.isValidId("../x"))
        assertFalse(PhotoQueries.isValidId("1".repeat(21)))
    }

    @Test fun accessLevels() {
        assertEquals(PhotoAccess.Full, PhotoQueries.access(api = 36, images = true, video = true, selectedOnly = false, legacyStorage = false))
        assertEquals(PhotoAccess.Partial, PhotoQueries.access(api = 36, images = false, video = false, selectedOnly = true, legacyStorage = false))
        assertEquals(PhotoAccess.None, PhotoQueries.access(api = 36, images = false, video = false, selectedOnly = false, legacyStorage = false))
        assertEquals(PhotoAccess.Full, PhotoQueries.access(api = 31, images = false, video = false, selectedOnly = false, legacyStorage = true))
        assertEquals(PhotoAccess.None, PhotoQueries.access(api = 31, images = false, video = false, selectedOnly = false, legacyStorage = false))
    }
}
