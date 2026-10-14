package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks photo access and tells the Mac to reload when what it can see changed (photos spec §4). */
class PhotoAccessWatcher(private val read: () -> PhotoAccess, private val changed: () -> Unit) {
    private val _access = MutableStateFlow(read())
    val access: StateFlow<PhotoAccess> = _access.asStateFlow()

    /** On resume: only a different level means the Mac sees something else. */
    fun refresh() {
        val now = read()
        if (now == _access.value) return
        _access.value = now
        changed()
    }

    /** After a permission prompt: with limited access the user may have picked a different set at the same level (follow-up M4). */
    fun afterPrompt() {
        val before = _access.value
        val now = read()
        _access.value = now
        if (now != before || now == PhotoAccess.Partial) changed()
    }
}
