package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoAccessWatcherTest {
    private var granted = PhotoAccess.None
    private var reloads = 0
    private val watcher = PhotoAccessWatcher({ granted }, { reloads++ })

    @Test fun resumeReloadsOnlyWhenTheLevelChanges() {
        watcher.refresh()
        assertEquals(0, reloads)
        granted = PhotoAccess.Full
        watcher.refresh()
        assertEquals(1, reloads)
        assertEquals(PhotoAccess.Full, watcher.access.value)
        watcher.refresh()
        assertEquals(1, reloads)
    }

    // Follow-up M4: picking a different set of photos keeps the level at Partial, but the Mac must still reload.
    @Test fun aPromptWithLimitedAccessAlwaysReloads() {
        granted = PhotoAccess.Partial
        watcher.refresh()
        assertEquals(1, reloads)
        watcher.afterPrompt()
        assertEquals(2, reloads)
        granted = PhotoAccess.None
        watcher.afterPrompt()
        assertEquals(3, reloads)
        watcher.afterPrompt() // still none: nothing to reload
        assertEquals(3, reloads)
    }
}
