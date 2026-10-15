package dev.macdroid.app.platform.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.macdroid.app.platform.control.MacDroidControlService
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.mirror.MirrorPhoneSession
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPongBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's end of mirroring (spec §3.1, §3.6): answers the Mac's `mirror.*`, asks for consent, and runs the capture service.
 * [session] is touched only on the main thread.
 */
class MirrorBridge(private val context: Context, private val connection: ConnectionManager, private val store: PairingStore, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    val session = MirrorPhoneSession(connection::send, SystemClock::elapsedRealtime) {
        MirrorNotifications.cancelRequest(context)
        MirrorService.stop()
    }

    fun start() {
        MirrorNotifications.createChannels(context)
        MirrorVolume.restoreIfLeft(context) // a session that was killed mid-way left the phone muted
        scope.launch {
            connection.incoming.collect { message ->
                // Pings are answered right here so the measured offset doesn't include a thread hop (spec §6.6).
                if (message is Message.MirrorPing) {
                    val received = System.nanoTime() / 1000
                    connection.send(Message.MirrorPong(MirrorPongBody(message.body.t0, received, System.nanoTime() / 1000)))
                } else {
                    main.post { handle(message) }
                }
            }
        }
        scope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { up ->
                if (!up) main.post { session.connectionLost() }
            }
        }
        scope.launch {
            MacDroidControlService.running.collect { on -> main.post { session.controlChanged(on) } }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                main.post { session.checkTimeout() }
            }
        }
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.MirrorStart -> {
                session.start(message.body)
                MirrorNotifications.showRequest(context, message.body, store.paired.value?.name ?: "your Mac")
                // Works when MacDroid is on screen; otherwise the notification is the way in (plan ruling; Plan 8 lifts this).
                runCatching { context.startActivity(MirrorConsentActivity.intent(context, message.body)) }
            }
            is Message.MirrorStop -> session.macStopped(message.body.sessionId)
            is Message.MirrorKeyframe -> if (message.body.sessionId == session.current) MirrorService.requestKeyFrame()
            is Message.InputPointer, is Message.InputScroll, is Message.InputKey, is Message.InputText ->
                // Only into a live session (Review Focus #2); nothing happens without the control service.
                if (session.isStreaming) MacDroidControlService.instance?.handle(message)
            else -> Unit
        }
    }

    /** From [MirrorConsentActivity], on the main thread. */
    fun consentResult(sessionId: String, resultCode: Int, data: Intent?) {
        MirrorNotifications.cancelRequest(context)
        val options = session.options
        if (sessionId != session.current || options == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            session.consentDenied()
            return
        }
        MirrorService.start(context, options, resultCode, data)
    }
}
