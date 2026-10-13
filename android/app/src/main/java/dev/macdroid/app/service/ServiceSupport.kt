package dev.macdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.R
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.ui.MainActivity
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.DeviceStatusBody
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object StatusText {
    const val LOCAL_NETWORK_NEEDED = "Local network permission needed — open MacDroid"

    fun title(state: ConnectionState): String = dev.macdroid.app.ui.presentation.StatusPresentation.notificationTitle(state)
}

object Notifications {
    const val CHANNEL_ID = "connection"
    const val SERVICE_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun status(context: Context, state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            context, 1,
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle(StatusText.title(state))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Shown instead of a connection status when the service can't run without the local network permission. */
    fun localNetworkNeeded(context: Context): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle(StatusText.LOCAL_NETWORK_NEEDED)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/** Calls [onAvailable] whenever the default network (re)appears, e.g. Wi-Fi reconnects. */
class NetworkWatcher(context: Context, private val onAvailable: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)
    fun stop() { runCatching { connectivity.unregisterNetworkCallback(callback) } }
}

object BatteryMonitor {
    fun statusFlow(context: Context): Flow<DeviceStatusBody> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { trySend(parse(intent)) }
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { trySend(parse(it)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun parse(intent: Intent): DeviceStatusBody {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceStatusBody(percent, charging)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            context.graph.uiPrefs.keepConnected &&
            context.graph.pairingStore.paired.value != null &&
            Permissions.hasLocalNetwork(context)
        ) {
            ConnectionService.start(context)
        }
    }
}
