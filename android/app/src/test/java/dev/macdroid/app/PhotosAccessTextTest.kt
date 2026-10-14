package dev.macdroid.app

import dev.macdroid.app.ui.presentation.PhotosAccessText
import dev.macdroid.core.protocol.PhotoAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotosAccessTextTest {
    @Test fun permissionsByVersion() {
        assertArrayEquals(
            arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            PhotosAccessText.permissions(36),
        )
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"), PhotosAccessText.permissions(33))
        assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), PhotosAccessText.permissions(29))
    }

    @Test fun labels() {
        assertEquals("All photos", PhotosAccessText.label(PhotoAccess.Full))
        assertEquals("Selected photos", PhotosAccessText.label(PhotoAccess.Partial))
        assertEquals("Off", PhotosAccessText.label(PhotoAccess.None))
    }

    // Follow-up M5: after a first "Don't allow" Android can still ask; only send the user to settings once it can't.
    @Test fun settingsOnlyWhenAndroidStopsAsking() {
        val denied = mapOf("android.permission.READ_MEDIA_IMAGES" to false, "android.permission.READ_MEDIA_VIDEO" to false)
        assertEquals(false, PhotosAccessText.openSettingsAfter(denied, canAskAgain = true))
        assertEquals(true, PhotosAccessText.openSettingsAfter(denied, canAskAgain = false))
        assertEquals(false, PhotosAccessText.openSettingsAfter(mapOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to true), canAskAgain = false))
    }
}
