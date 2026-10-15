package dev.macdroid.app.platform.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/** The default display's size as currently shown (natural size swapped when rotated) and its rotation in degrees. */
object ScreenSize {
    fun current(context: Context): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        return if (rotation == 90 || rotation == 270) Triple(long, short, rotation) else Triple(short, long, rotation)
    }
}
