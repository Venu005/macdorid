package dev.macdroid.app

import dev.macdroid.app.ui.presentation.SharedImageSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Final review: "Copy to Mac" is exported, so it must only read images other apps shared with it.
class SharedImageSourceTest {
    private val own = "dev.macdroid.app.clipboard"

    @Test fun otherAppsContentUrisAreAccepted() {
        assertTrue(SharedImageSource.allowed("content", "com.android.providers.media.documents", own))
        assertTrue(SharedImageSource.allowed("CONTENT", "media", own))
    }

    @Test fun fileUrisAndOurOwnProviderAreRefused() {
        assertFalse(SharedImageSource.allowed("file", null, own))
        assertFalse(SharedImageSource.allowed("content", own, own))
        assertFalse(SharedImageSource.allowed("content", null, own))
        assertFalse(SharedImageSource.allowed(null, "media", own))
    }
}
