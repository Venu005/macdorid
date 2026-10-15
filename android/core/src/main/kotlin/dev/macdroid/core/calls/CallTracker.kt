package dev.macdroid.core.calls

import dev.macdroid.core.protocol.CallActionKind
import dev.macdroid.core.protocol.CallErrorBody
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.CallPhase
import dev.macdroid.core.protocol.CallStateBody
import dev.macdroid.core.protocol.Message
import java.net.URLDecoder

enum class CallType { Incoming, Ongoing, Screening }

/** A call notification, read out of Android's notification by the app (Plan 10 spec §2.1). [key] is the notification key. */
data class CallNotification(val key: String, val type: CallType, val name: String?, val number: String?, val actions: Set<CallActionKind>)

/** What the app must do for a Mac request. */
sealed interface CallCommand {
    data class Fire(val key: String, val action: CallActionKind) : CallCommand
    data class Dial(val number: String) : CallCommand
}

/**
 * The phone's calls as the Mac sees them (spec §2.1): `call.state` on every change, `ended` on removal, everything again
 * after a reconnect; the Mac's `call.action` resolved to the notification's own actions. Thread-safe.
 */
class CallTracker(private val send: (Message) -> Boolean) {
    private val calls = LinkedHashMap<String, CallNotification>()
    private val lastSent = HashMap<String, CallStateBody>()
    private var macReady = false

    @Synchronized fun connected() {
        macReady = true
        lastSent.clear()
        calls.values.forEach(::sendState)
    }

    @Synchronized fun disconnected() {
        macReady = false
        lastSent.clear()
    }

    @Synchronized fun posted(call: CallNotification) {
        calls[call.key] = call
        if (macReady) sendState(call)
    }

    @Synchronized fun removed(key: String) {
        val call = calls.remove(key) ?: return
        val last = lastSent.remove(key)
        if (macReady && last != null) send(Message.CallState(CallStateBody(key, CallPhase.Ended, call.name, call.number)))
    }

    /** A Mac request: what to do, or null (an error was already reported, or it isn't a call request). */
    @Synchronized fun handle(message: Message): CallCommand? = when (message) {
        is Message.CallAction -> {
            val call = calls[message.body.callId]
            when {
                call == null -> { report(CallErrorReason.CallGone, message.body.callId); null }
                message.body.action !in call.actions -> { report(CallErrorReason.NotSupported, call.key); null }
                else -> CallCommand.Fire(call.key, message.body.action)
            }
        }
        is Message.CallDial -> if (CallNumbers.isClean(message.body.number)) CallCommand.Dial(message.body.number) else null
        else -> null
    }

    fun report(reason: CallErrorReason, callId: String?) {
        send(Message.CallError(CallErrorBody(reason, callId)))
    }

    private fun sendState(call: CallNotification) {
        val phase = if (call.type == CallType.Ongoing) CallPhase.Active else CallPhase.Ringing
        val body = CallStateBody(call.key, phase, call.name, call.number)
        if (lastSent[call.key] == body) return
        if (send(Message.CallState(body))) lastSent[call.key] = body
    }
}

/** Phone numbers on the wire (spec §4): 1–32 characters of `+0-9*#`, `+` only first. */
object CallNumbers {
    private val CLEAN = Regex("^\\+?[0-9*#]{1,32}$")

    fun isClean(number: String): Boolean = number.length <= 32 && CLEAN.matches(number)

    /** Keeps a leading `+`, digits, `*` and `#`; null when nothing usable is left. */
    fun clean(raw: String): String? {
        val out = StringBuilder()
        for (c in raw.trim()) {
            if (c in '0'..'9' || c == '*' || c == '#') out.append(c)
            else if (c == '+' && out.isEmpty()) out.append(c)
        }
        return out.toString().takeIf { isClean(it) }
    }

    /** The number in a `tel:` URI (a call notification's person), cleaned; null for other schemes. */
    fun fromUri(uri: String?): String? {
        if (uri == null || !uri.startsWith("tel:", ignoreCase = true)) return null
        // URLDecoder turns a literal '+' into a space; keep it as the international prefix.
        val raw = runCatching { URLDecoder.decode(uri.substring(4).replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return clean(raw.substringBefore(';'))
    }
}

/** Older call notifications without the call-style intents: their buttons by label (spec §2.1). */
object CallLabels {
    fun kindOf(title: String?): CallActionKind? = when (title?.trim()?.lowercase()) {
        "answer", "accept" -> CallActionKind.Answer
        "decline", "reject", "dismiss" -> CallActionKind.Decline
        "hang up", "end call", "end" -> CallActionKind.HangUp
        else -> null
    }
}
