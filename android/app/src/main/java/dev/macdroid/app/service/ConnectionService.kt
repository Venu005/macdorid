package dev.macdroid.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps the connection to the Mac alive while the app is in the background. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkWatcher: NetworkWatcher? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        // Without the permission sockets and NSD fail, so don't run (e.g. a sticky restart after it was revoked).
        if (!Permissions.hasLocalNetwork(this)) {
            stopForPermission()
            return
        }
        running = true
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_ID,
            Notifications.status(this, ConnectionState.Connecting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val manager = graph.connectionManager
        manager.start()
        networkWatcher = NetworkWatcher(this) { manager.nudge() }.also { it.start() }

        val notifications = getSystemService(NotificationManager::class.java)
        scope.launch {
            manager.state.collect { notifications.notify(Notifications.SERVICE_ID, Notifications.status(this@ConnectionService, it)) }
        }
        scope.launch {
            combine(manager.state, BatteryMonitor.statusFlow(this@ConnectionService)) { state, battery -> state to battery }
                .collect { (state, battery) -> if (state is ConnectionState.Connected) manager.send(Message.DeviceStatus(battery)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY // onCreate already posted the notice and stopped
        if (!Permissions.hasLocalNetwork(this)) {
            stopForPermission()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        networkWatcher?.stop()
        scope.cancel()
        if (running) graph.connectionManager.stop()
        super.onDestroy()
    }

    /** A foreground-service start must call startForeground, so show the permission notice, keep it, and stop. */
    private fun stopForPermission() {
        val notice = Notifications.localNetworkNeeded(this)
        try {
            ServiceCompat.startForeground(this, Notifications.SERVICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (e: RuntimeException) {
            // The platform may refuse this foreground type without the permission; post the notice directly instead.
            getSystemService(NotificationManager::class.java).notify(Notifications.SERVICE_ID, notice)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_DISCONNECT = "dev.macdroid.app.DISCONNECT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
