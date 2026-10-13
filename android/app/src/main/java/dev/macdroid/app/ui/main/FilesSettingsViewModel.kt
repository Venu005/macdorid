package dev.macdroid.app.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.FolderLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FilesSettingsUi(val folderLabel: String, val customFolder: Boolean, val askEachTime: Boolean, val folderProblem: Boolean)

class FilesSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    val ui: StateFlow<FilesSettingsUi> = combine(graph.filesPrefs.treeUriFlow, graph.filesPrefs.askEachTimeFlow, graph.saveTargets.folderProblem) { tree, ask, problem ->
        FilesSettingsUi(FolderLabel.of(tree), tree != null, ask, problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesSettingsUi(FolderLabel.of(graph.filesPrefs.treeUri), graph.filesPrefs.treeUri != null, graph.filesPrefs.askEachTime, false))

    fun refresh() {
        graph.saveTargets.current()
    }

    fun chooseFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        graph.filesPrefs.treeUri?.takeIf { it != uri.toString() }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        graph.filesPrefs.treeUri = uri.toString()
        refresh()
    }

    fun useDefaultFolder() {
        val resolver = getApplication<Application>().contentResolver
        graph.filesPrefs.treeUri?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        graph.filesPrefs.treeUri = null
        refresh()
    }

    fun setAskEachTime(on: Boolean) {
        graph.filesPrefs.askEachTime = on
        refresh()
    }
}
