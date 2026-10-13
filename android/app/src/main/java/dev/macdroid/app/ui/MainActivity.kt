package dev.macdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.macdroid.app.graph
import dev.macdroid.app.ui.navigation.AppNavHost
import dev.macdroid.app.ui.theme.MacDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph
        setContent {
            MacDroidTheme {
                Surface(Modifier.fillMaxSize()) { AppNavHost(graph) }
            }
        }
    }
}
