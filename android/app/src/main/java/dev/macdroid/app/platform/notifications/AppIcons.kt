package dev.macdroid.app.platform.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.macdroid.core.notifications.AppIcon
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** App icons as 96×96 PNGs with a 16-hex hash (spec §2.2), rendered once per app per process. */
class AppIcons(private val context: Context) {
    private data class Entry(val icon: AppIcon?)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun icon(packageName: String): AppIcon? =
        cache.getOrPut(packageName) { Entry(runCatching { render(packageName) }.getOrNull()) }.icon

    private fun render(packageName: String): AppIcon {
        val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(png).take(8).joinToString("") { "%02x".format(it) }
        return AppIcon(hash, Base64.getEncoder().encodeToString(png))
    }

    private companion object {
        const val SIZE = 96
    }
}
