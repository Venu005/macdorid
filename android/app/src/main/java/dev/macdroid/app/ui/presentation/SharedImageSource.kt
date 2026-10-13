package dev.macdroid.app.ui.presentation

/**
 * "Copy to Mac" is exported, so it reads only images another app shared through its own content provider:
 * never `file:` URIs, and never MacDroid's own FileProvider (which serves its private clipboard files).
 */
object SharedImageSource {
    fun allowed(scheme: String?, authority: String?, ownAuthority: String): Boolean =
        scheme.equals("content", ignoreCase = true) && authority != null && authority != ownAuthority
}
