package dev.macdroid.app.platform.calls

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.calls.CallCommand
import dev.macdroid.core.calls.CallTracker
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.Features
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Calls on the Mac, phone side (Plan 10 spec §2): call notifications → the Mac; the Mac's requests → the phone app. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBridge(private val context: Context, private val connection: ConnectionManager, private val scope: CoroutineScope) {
    val tracker = CallTracker(connection::send)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var listener: NotificationListenerService? = null

    fun start() {
        scope.launch(worker) {
            connection.state.map { it.macSupports(Features.CALLS) }.distinctUntilChanged().collect { ready ->
                if (ready) tracker.connected() else tracker.disconnected()
            }
        }
        scope.launch(worker) {
            connection.incoming.collect { message -> tracker.handle(message)?.let(::execute) }
        }
    }

    fun listenerConnected(service: NotificationListenerService) {
        listener = service
        scope.launch(worker) {
            val active = runCatching { service.activeNotifications }.getOrNull() ?: return@launch
            active.forEach { sbn -> CallReader.read(sbn)?.let(tracker::posted) }
            tracker.sync(active.map { it.key }.toSet())
        }
    }

    fun listenerDisconnected() { listener = null }

    /** True when [sbn] is a call notification: it goes to the Mac as a call, not as a mirrored notification. */
    fun posted(sbn: StatusBarNotification): Boolean {
        val call = CallReader.read(sbn) ?: return false
        scope.launch(worker) { tracker.posted(call) }
        return true
    }

    fun removed(key: String) {
        scope.launch(worker) { tracker.removed(key) }
    }

    /** Never lets an unexpected error kill the app's process; the Mac hears "not supported" instead (minor 5). */
    private fun execute(command: CallCommand) {
        try {
            run(command)
        } catch (e: Exception) {
            if (command is CallCommand.Fire) tracker.failed(command.key, CallErrorReason.NotSupported)
            else tracker.report(CallErrorReason.NotSupported, null)
        }
    }

    private fun run(command: CallCommand) {
        when (command) {
            is CallCommand.Fire -> {
                val sbn = runCatching { listener?.activeNotifications }.getOrNull()?.firstOrNull { it.key == command.key }
                    ?: return tracker.failed(command.key, CallErrorReason.CallGone)
                val intent = CallReader.intent(sbn.notification, command.action)
                    ?: return tracker.failed(command.key, CallErrorReason.NotSupported)
                try {
                    intent.send()
                } catch (e: PendingIntent.CanceledException) {
                    tracker.failed(command.key, CallErrorReason.CallGone)
                }
            }
            is CallCommand.Dial -> {
                if (!Permissions.granted(context, Manifest.permission.CALL_PHONE)) return tracker.report(CallErrorReason.NoPermission, null)
                try {
                    context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", command.number, null), null)
                } catch (e: SecurityException) {
                    tracker.report(CallErrorReason.NoPermission, null)
                }
            }
        }
    }
}
