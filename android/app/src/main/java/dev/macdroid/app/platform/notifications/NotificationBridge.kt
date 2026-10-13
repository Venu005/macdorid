package dev.macdroid.app.platform.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.notifications.NotificationCommand
import dev.macdroid.core.notifications.NotificationForwarder
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.NotificationErrorReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Connects the notification listener, the connection and the forwarder (spec §3.1).
 * Every callback runs in order on one worker, off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationBridge(
    private val context: Context,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) {
    val forwarder = NotificationForwarder(
        ownPackage = context.packageName,
        store = SharedPrefsNotificationSettings(context),
        send = connection::send,
        iconFor = AppIcons(context)::icon,
    )
    private val worker = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var listener: NotificationListenerService? = null
    @Volatile private var macReady = false

    fun start() {
        scope.launch(worker) { refreshAccess() }
        scope.launch(worker) {
            connection.state.map { it.macSupports(Features.NOTIFICATIONS) }.distinctUntilChanged().collect { ready ->
                macReady = ready
                if (ready) forwarder.connected(activeKeys()) else forwarder.disconnected()
            }
        }
        scope.launch(worker) {
            connection.incoming.collect { message ->
                val command = forwarder.handle(message) ?: return@collect
                execute(command)?.let { forwarder.reportError(command.key, it) }
            }
        }
    }

    fun refreshAccess() {
        forwarder.accessChanged(Permissions.hasNotificationAccess(context))
    }

    fun listenerConnected(service: NotificationListenerService) {
        listener = service
        scope.launch(worker) {
            refreshAccess()
            if (macReady) forwarder.connected(activeKeys()) // a sync now that the active list is readable
        }
    }

    fun listenerDisconnected() {
        listener = null
        scope.launch(worker) { refreshAccess() }
    }

    fun posted(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?) {
        scope.launch(worker) { forwarder.posted(NotificationReader.read(context, sbn, ranking)) }
    }

    fun removed(key: String) {
        scope.launch(worker) { forwarder.removed(key) }
    }

    private fun activeKeys(): Set<String> =
        runCatching { listener?.activeNotifications?.map { it.key }?.toSet() }.getOrNull().orEmpty()

    /** Carries out a Mac request. Returns the error to report, or null on success. */
    private fun execute(command: NotificationCommand): NotificationErrorReason? {
        val service = listener ?: return NotificationErrorReason.Failed
        val sbn = runCatching { service.activeNotifications }.getOrNull()?.firstOrNull { it.key == command.key }
        if (sbn == null) return if (command is NotificationCommand.Dismiss) null else NotificationErrorReason.Gone
        return try {
            when (command) {
                is NotificationCommand.Dismiss -> service.cancelNotification(command.key)
                is NotificationCommand.Action -> {
                    val action = sbn.notification.actions?.getOrNull(command.actionIndex) ?: return NotificationErrorReason.Gone
                    action.actionIntent.send()
                }
                is NotificationCommand.Reply -> {
                    val action = NotificationReader.replyAction(sbn.notification) ?: return NotificationErrorReason.Failed
                    val inputs = action.remoteInputs
                    val freeForm = inputs.first { it.allowFreeFormInput }
                    val results = Bundle().apply { putCharSequence(freeForm.resultKey, command.text) }
                    val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    RemoteInput.addResultsToIntent(inputs, fillIn, results)
                    action.actionIntent.send(service, 0, fillIn)
                }
            }
            null
        } catch (e: PendingIntent.CanceledException) {
            NotificationErrorReason.Failed
        } catch (e: SecurityException) {
            NotificationErrorReason.Failed
        }
    }
}
