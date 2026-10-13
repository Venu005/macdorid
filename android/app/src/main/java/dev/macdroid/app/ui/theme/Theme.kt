package dev.macdroid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material You colours from the wallpaper on Android 12+, a warm fallback palette before that. */
@Composable
fun MacDroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBD),
    onPrimaryContainer = Color(0xFF2C1600),
    surface = Color(0xFFFFF8F4),
    background = Color(0xFFFFF8F4),
    surfaceVariant = Color(0xFFF3E4D7),
    onSurfaceVariant = Color(0xFF51443A),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFFFB86E),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6A3B00),
    onPrimaryContainer = Color(0xFFFFDCBD),
    surface = Color(0xFF19120C),
    background = Color(0xFF19120C),
    surfaceVariant = Color(0xFF51443A),
    onSurfaceVariant = Color(0xFFD5C3B5),
)
