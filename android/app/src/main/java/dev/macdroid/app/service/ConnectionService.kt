package dev.macdroid.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.platform.SendFiles
import dev.macdroid.app.platform.extras.Ringer
import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.extras.StopReason
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.transfer.TransferEvent
import dev.macdroid.core.transfer.files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground service that keeps the connection to the Mac alive while the app is in the background. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkWatcher: NetworkWatcher? = null
    private var running = false
    private var ringer: Ringer? = null
    /** Completed sends already tidied up (cache copy deleted, read grant released). */
    private val tidied = mutableSetOf<String>()

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
        TransferNotifications.createChannels(this)
        val engine = graph.transferEngine
        scope.launch {
            // At most one update a second; also clean up cache copies of finished transfers.
            engine.snapshot.collect { snapshot -> // a StateFlow: a slow collector always gets the latest value
                val summary = TransferPresentation.progressSummary(snapshot.files())
                if (summary == null) {
                    notifications.cancel(TransferNotifications.PROGRESS_ID)
                } else {
                    notifications.notify(TransferNotifications.PROGRESS_ID, TransferNotifications.progress(this@ConnectionService, summary))
                }
                withContext(Dispatchers.IO) { SendFiles.tidyUp(this@ConnectionService, snapshot.history, tidied) }
                delay(1_000)
            }
        }
        scope.launch {
            engine.events.collect { event ->
                if (event is TransferEvent.Received) TransferNotifications.received(this@ConnectionService, event.record)
            }
        }
        scope.launch {
            manager.state.collect { notifications.notify(Notifications.SERVICE_ID, Notifications.status(this@ConnectionService, it)) }
        }
        scope.launch {
            combine(manager.state, BatteryMonitor.statusFlow(this@ConnectionService)) { state, battery -> state to battery }
                .collect { (state, battery) -> if (state is ConnectionState.Connected) manager.send(Message.DeviceStatus(battery)) }
        }

        val ringer = Ringer(this, scope) { manager.send(it) }.also { this.ringer = it }
        scope.launch { manager.incoming.collect { ringer.handle(it) } }
        scope.launch {
            manager.state.collect { if (it !is ConnectionState.Connected) ringer.stop(StopReason.Disconnected) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY // onCreate already posted the notice and stopped
        if (!Permissions.hasLocalNetwork(this)) {
            stopForPermission()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CANCEL_TRANSFERS -> graph.transferEngine.cancelAll()
            ACTION_STOP_RING -> ringer?.stop(StopReason.Tapped)
            ACTION_SEND_FILES -> {
                // The share target's read grant came with this intent and lasts while the service runs.
                val clip = intent.clipData
                val uris = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                scope.launch { SendFiles.enqueue(this@ConnectionService, uris) }
            }
        }
        if (intent?.action == ACTION_DISCONNECT) {
            graph.uiPrefs.keepConnected = false // the app shows "Disconnected · Connect" instead of reconnecting on its own
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkWatcher?.stop()
        ringer?.stop(StopReason.Disconnected)
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
        const val ACTION_SEND_FILES = "dev.macdroid.app.SEND_FILES"
        const val ACTION_CANCEL_TRANSFERS = "dev.macdroid.app.CANCEL_TRANSFERS"
        const val ACTION_STOP_RING = "dev.macdroid.app.STOP_RING"

        /** Hands shared URIs to the service with a read grant (spec §4.2, ruling 2). */
        fun sendFiles(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("files", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_SEND_FILES)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
