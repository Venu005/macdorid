package dev.macdroid.app

import dev.macdroid.app.platform.ShareSource
import org.junit.Assert.assertEquals
import org.junit.Test

// Review Focus #4
class ShareSourceTest {
    private val limit = 100L * 1024 * 1024

    @Test fun persistableUrisAreKept() = assertEquals(ShareSource.Keep, ShareSource.plan(persisted = true, size = 5, copyLimit = limit))
    @Test fun smallUnpersistableFilesAreCopied() = assertEquals(ShareSource.Copy, ShareSource.plan(persisted = false, size = limit, copyLimit = limit))
    @Test fun largeUnpersistableFilesAreReadInPlace() = assertEquals(ShareSource.Keep, ShareSource.plan(persisted = false, size = limit + 1, copyLimit = limit))
}
