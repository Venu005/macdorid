package dev.macdroid.app.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Files: ask-each-time and the chosen SAF folder (null means Download/MacDroid). */
class FilesPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("files", Context.MODE_PRIVATE)
    private val _askEachTime = MutableStateFlow(prefs.getBoolean("askEachTime", false))
    private val _treeUri = MutableStateFlow(prefs.getString("treeUri", null))
    val askEachTimeFlow: StateFlow<Boolean> = _askEachTime.asStateFlow()
    val treeUriFlow: StateFlow<String?> = _treeUri.asStateFlow()

    var askEachTime: Boolean
        get() = _askEachTime.value
        set(value) {
            prefs.edit().putBoolean("askEachTime", value).apply()
            _askEachTime.value = value
        }

    var treeUri: String?
        get() = _treeUri.value
        set(value) {
            prefs.edit().putString("treeUri", value).apply()
            _treeUri.value = value
        }

    /** The optional setup step "Where should files from your Mac go?" was answered or skipped. */
    var filesStepDone: Boolean
        get() = prefs.getBoolean("filesStepDone", false)
        set(value) { prefs.edit().putBoolean("filesStepDone", value).apply() }
}
