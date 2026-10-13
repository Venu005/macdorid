package dev.macdroid.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permission checks shared by the UI, the connection service and the boot receiver. */
object Permissions {
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    val needsLocalNetwork: Boolean get() = Build.VERSION.SDK_INT >= 37
    val needsNotifications: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocalNetwork(context: Context) = !needsLocalNetwork || granted(context, ACCESS_LOCAL_NETWORK)
    fun hasNotifications(context: Context) = !needsNotifications || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** The user granted MacDroid "Notification access" (spec §3.1). */
    fun hasNotificationAccess(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
