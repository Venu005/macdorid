package dev.macdroid.app.platform.mirror

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager

/**
 * The default display's size as currently shown, in the logical pixels gestures use (it follows a lowered "screen
 * resolution" setting and rotation; final review I6), and its rotation in degrees.
 */
object ScreenSize {
    fun current(context: Context): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.createDisplayContext(display).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION") display.getRealSize(size)
            size.x to size.y
        }
        return Triple(w, h, rotation)
    }
}
