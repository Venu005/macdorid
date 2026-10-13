package dev.macdroid.app.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.macdroid.app.AppGraph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.main.MainScreen
import dev.macdroid.app.ui.setup.SetupScreen
import dev.macdroid.app.ui.setup.SetupViewModel

object Routes {
    const val SETUP = "setup"
    const val MAIN = "main"
}

/** Top level: the setup wizard whenever something required is missing, otherwise the main app. */
@Composable
fun AppNavHost(graph: AppGraph) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val setupViewModel: SetupViewModel = viewModel()
    val setup by setupViewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setupViewModel.refresh() }

    val needsSetup = setup.step != null
    val start = remember { if (needsSetup) Routes.SETUP else Routes.MAIN }
    LaunchedEffect(needsSetup) {
        val target = if (needsSetup) Routes.SETUP else Routes.MAIN
        if (nav.currentDestination?.route != target) {
            nav.navigate(target) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(setup.readyToConnect) {
        if (setup.readyToConnect) {
            ConnectionService.start(context)
            graph.connectionManager.nudge()
        }
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.SETUP) { SetupScreen(setupViewModel) }
        composable(Routes.MAIN) {
            MainScreen(onForget = {
                forgetMac(context, graph)
                setupViewModel.refresh()
            })
        }
    }
}

/** Stops the connection and forgets the Mac; setup then shows only the pairing step. */
fun forgetMac(context: Context, graph: AppGraph) {
    ConnectionService.stop(context)
    graph.connectionManager.stop()
    graph.pairingStore.clear()
}
