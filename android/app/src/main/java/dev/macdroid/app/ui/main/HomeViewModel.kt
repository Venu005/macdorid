package dev.macdroid.app.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusPresentation
import dev.macdroid.core.connection.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUi(val status: StatusModel, val macName: String?)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    /** Each state with the moment it was entered, so "retrying in N s" can count down live. */
    private val stateSince = graph.connectionManager.state.map { it to SystemClock.elapsedRealtime() }
    private val ticker = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }

    val ui: StateFlow<HomeUi> = combine(stateSince, graph.pairingStore.paired, ticker) { since, mac, now ->
        val (state, enteredAt) = since
        val remaining = (state as? ConnectionState.Waiting)?.let { it.retryInMs - (now - enteredAt) }
        HomeUi(StatusPresentation.home(state, mac?.name ?: "Your Mac", remaining), mac?.name)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUi(
            StatusPresentation.home(graph.connectionManager.state.value, graph.pairingStore.paired.value?.name ?: "Your Mac"),
            graph.pairingStore.paired.value?.name,
        ),
    )

    fun retryNow() = graph.connectionManager.nudge()

    fun connect() {
        graph.uiPrefs.keepConnected = true
        ConnectionService.start(getApplication())
        graph.connectionManager.nudge()
    }
}
