package dev.macdroid.app.ui.main

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.macdroid.app.ui.clipboard.ClipboardScreen
import dev.macdroid.app.ui.files.FilesScreen
import dev.macdroid.app.ui.notifications.NotificationsScreen

enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Rounded.Home),
    Notifications("notifications", "Notifications", Icons.Rounded.Notifications),
    Clipboard("clipboard", "Clipboard", Icons.Rounded.ContentPaste),
    Files("files", "Files", Icons.Rounded.Folder),
    Settings("settings", "Settings", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(onForget: () -> Unit) {
    val tabs = rememberNavController()
    val backStack by tabs.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            tabs.navigate(tab.route) {
                                popUpTo(tabs.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(tabs, startDestination = MainTab.Home.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(MainTab.Home.route) { HomeScreen(onPairAgain = onForget) }
            composable(MainTab.Notifications.route) { NotificationsScreen() }
            composable(MainTab.Clipboard.route) { ClipboardScreen() }
            composable(MainTab.Files.route) { FilesScreen() }
            composable(MainTab.Settings.route) { SettingsScreen(onForget = onForget) }
        }
    }
}
