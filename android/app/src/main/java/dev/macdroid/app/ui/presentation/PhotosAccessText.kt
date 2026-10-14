package dev.macdroid.app.ui.presentation

import dev.macdroid.core.protocol.PhotoAccess

/** Photos permission requests and labels (spec §2). */
object PhotosAccessText {
    fun permissions(api: Int): Array<String> = when {
        api >= 34 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        api >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    fun label(access: PhotoAccess): String = when (access) {
        PhotoAccess.Full -> "All photos"
        PhotoAccess.Partial -> "Selected photos"
        PhotoAccess.None -> "Off"
    }

    /**
     * After a prompt: send the user to the app's settings only when nothing was granted and Android won't show
     * the prompt again (follow-up M5). After a first "Don't allow" it still can.
     */
    fun openSettingsAfter(results: Map<String, Boolean>, canAskAgain: Boolean): Boolean = results.values.none { it } && !canAskAgain
}
