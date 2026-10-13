package dev.macdroid.app.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.ReceivedFiles
import dev.macdroid.app.platform.SendFiles
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.transfer.files
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [deleted]: ids of received files that are no longer on the phone. */
data class FilesUi(val snapshot: TransferSnapshot, val connected: Boolean, val folderProblem: Boolean, val deleted: Set<String> = emptySet())

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    private val recheck = MutableStateFlow(0)

    /** Received files that were deleted since they arrived, checked off the main thread and again on [refresh]. */
    private val deleted = combine(graph.transferEngine.snapshot, recheck) { snapshot, _ -> snapshot.history }
        .map { history ->
            history.filter { it.direction == Direction.FromMac && it.phase == Phase.Done && it.savedUri != null }
                .filterNot { ReceivedFiles.exists(getApplication(), it.savedUri!!) }
                .map { it.id }
                .toSet()
        }
        .flowOn(Dispatchers.IO)

    val ui: StateFlow<FilesUi> = combine(graph.transferEngine.snapshot, graph.connectionManager.state, graph.saveTargets.folderProblem, deleted) { snapshot, state, problem, gone ->
        FilesUi(snapshot.files(), state is ConnectionState.Connected, problem, gone)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FilesUi(graph.transferEngine.snapshot.value.files(), graph.connectionManager.state.value is ConnectionState.Connected, graph.saveTargets.folderProblem.value),
    )

    init {
        graph.saveTargets.current() // refresh the folder-problem flag when the screen opens
    }

    /** Call when the screen comes back: files may have been deleted in another app meanwhile. */
    fun refresh() {
        recheck.value++
    }

    fun send(uris: List<Uri>) {
        viewModelScope.launch { SendFiles.enqueue(getApplication(), uris) }
    }

    fun cancel(id: String) = graph.transferEngine.cancel(id)

    fun retry(id: String) {
        graph.transferEngine.retry(id)
    }
}
