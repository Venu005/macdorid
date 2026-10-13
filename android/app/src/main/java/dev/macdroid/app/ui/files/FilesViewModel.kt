package dev.macdroid.app.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.SendFiles
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.transfer.TransferSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FilesUi(val snapshot: TransferSnapshot, val connected: Boolean, val folderProblem: Boolean)

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    val ui: StateFlow<FilesUi> = combine(graph.transferEngine.snapshot, graph.connectionManager.state, graph.saveTargets.folderProblem) { snapshot, state, problem ->
        FilesUi(snapshot, state is ConnectionState.Connected, problem)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FilesUi(graph.transferEngine.snapshot.value, graph.connectionManager.state.value is ConnectionState.Connected, graph.saveTargets.folderProblem.value),
    )

    init {
        graph.saveTargets.current() // refresh the folder-problem flag when the screen opens
    }

    fun send(uris: List<Uri>) {
        viewModelScope.launch { SendFiles.enqueue(getApplication(), uris) }
    }

    fun cancel(id: String) = graph.transferEngine.cancel(id)

    fun retry(id: String) {
        graph.transferEngine.retry(id)
    }
}
