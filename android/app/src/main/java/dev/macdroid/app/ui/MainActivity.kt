package dev.macdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.AppGraph
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppRoot(graph) }
            }
        }
    }
}

@Composable
fun AppRoot(graph: AppGraph) {
    val context = LocalContext.current
    val paired by graph.pairingStore.paired.collectAsStateWithLifecycle()
    var permissionCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionCheck++ }
    val localNetworkOk = remember(permissionCheck) { Permissions.hasLocalNetwork(context) }
    var setupDone by remember { mutableStateOf(graph.uiPrefs.setupDone) }

    LaunchedEffect(paired != null, localNetworkOk) {
        if (paired != null && localNetworkOk) {
            ConnectionService.start(context)
            graph.connectionManager.nudge()
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            !localNetworkOk -> LocalNetworkScreen(onResult = { permissionCheck++ })
            paired == null -> PairScreen()
            !setupDone -> FinishSetupScreen(onDone = {
                graph.uiPrefs.setupDone = true
                setupDone = true
            })
            else -> HomeScreen(graph, onForget = {
                ConnectionService.stop(context)
                graph.connectionManager.stop()
                graph.pairingStore.clear()
                graph.uiPrefs.setupDone = false
                setupDone = false
            })
        }
    }
}
