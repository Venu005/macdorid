package dev.macdroid.core.connection

import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.MalformedMessageException
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import dev.macdroid.core.tls.TrustFailures
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data object NotPaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val macName: String) : ConnectionState
    data class Waiting(val retryInMs: Long) : ConnectionState
    /** The Mac's certificate isn't the pinned one, or the Mac rejected ours. The user must re-pair. */
    data class TrustFailure(val detail: String) : ConnectionState
}

/** Keeps one WebSocket to the paired Mac alive, reconnecting with backoff. */
class ConnectionManager(
    private val store: PairingStore,
    private val identity: () -> ClientIdentity,
    private val locator: MacLocator,
    private val hello: () -> HelloBody,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val backoff: Backoff = Backoff(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Message> = _incoming.asSharedFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null
    /** Identifies the live loop; a loop whose generation is stale must not touch shared state. */
    private var generation = 0

    @Synchronized fun start() {
        if (job?.isActive == true) return
        val gen = ++generation
        job = CoroutineScope(SupervisorJob() + dispatcher).launch { runLoop(gen) }
    }

    @Synchronized fun stop() {
        generation++
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
        _state.value = ConnectionState.Stopped
    }

    /** Retry now (network changed, permission granted, …). */
    fun nudge() {
        nudges.trySend(Unit)
    }

    fun send(message: Message): Boolean = socket?.send(MessageCodec.encode(message)) ?: false

    /** Only the live loop may publish state, so a cancelled loop can't overwrite `Stopped`. */
    private fun setState(gen: Int, value: ConnectionState) {
        synchronized(this) { if (gen == generation) _state.value = value }
    }

    /**
     * Only the live loop may persist, under the same monitor as [stop]: once `stop()` returns, no save from an
     * older loop can land, so Forget's `stop()` + `clear()` can't be undone by a cancelled loop.
     */
    private fun saveIfCurrent(gen: Int, mac: PairedMac) {
        synchronized(this) { if (gen == generation) store.save(mac) }
    }

    private suspend fun runLoop(gen: Int) {
        while (currentCoroutineContext().isActive) {
            val mac = store.paired.value
            if (mac == null) {
                setState(gen, ConnectionState.NotPaired)
                store.paired.first { it != null }
                backoff.reset()
                continue
            }
            if (state.value !is ConnectionState.TrustFailure) setState(gen, ConnectionState.Connecting)
            val outcome = try {
                connectOnce(mac, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed
            }
            when (outcome) {
                is Outcome.Closed -> if (outcome.wasOpen) backoff.reset()
                is Outcome.Trust -> setState(gen, ConnectionState.TrustFailure(outcome.detail))
                Outcome.Failed -> Unit
            }
            val delayMs = backoff.next()
            if (state.value !is ConnectionState.TrustFailure) setState(gen, ConnectionState.Waiting(delayMs))
            val wake = try {
                waitForRetry(mac, delayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (wake) {
                is Wake.Found -> {
                    try {
                        saveIfCurrent(gen, mac.copy(host = wake.address.host, port = wake.address.port))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Couldn't persist the new address; the loop keeps retrying the stored one.
                    }
                    backoff.reset()
                }
                Wake.Nudged -> backoff.reset()
                null -> Unit
            }
        }
    }

    private sealed interface Wake {
        data object Nudged : Wake
        data class Found(val address: MacAddress) : Wake
    }

    /** Sleeps up to [delayMs], waking early on a nudge or when the Mac shows up at a new address. */
    private suspend fun waitForRetry(mac: PairedMac, delayMs: Long): Wake? {
        val current = MacAddress(mac.host, mac.port)
        return withTimeoutOrNull(delayMs) {
            merge<Wake>(
                nudges.receiveAsFlow().map { Wake.Nudged },
                locator.appearances(mac.fingerprint).catch { }.filter { it != current }.map { Wake.Found(it) },
            ).first()
        }
    }

    private sealed interface Outcome {
        data class Closed(val wasOpen: Boolean) : Outcome
        data class Trust(val detail: String) : Outcome
        data object Failed : Outcome
    }

    private sealed interface SocketEvent {
        data class Open(val socket: WebSocket) : SocketEvent
        data class Text(val text: String) : SocketEvent
        data object Closed : SocketEvent
        data class Failure(val error: Throwable) : SocketEvent
    }

    private suspend fun connectOnce(mac: PairedMac, gen: Int): Outcome {
        val client = TlsClientFactory.pinned(identity(), mac.fingerprint, baseClient).newBuilder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val url = HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).addPathSegments("v1/ws").build()
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(SocketEvent.Open(webSocket)) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(SocketEvent.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(SocketEvent.Closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(SocketEvent.Failure(t)) }
        })
        var open = false
        try {
            for (event in events) {
                when (event) {
                    is SocketEvent.Open -> {
                        open = true
                        val owned = synchronized(this) { if (gen == generation) { socket = event.socket; true } else false }
                        if (!owned) return Outcome.Failed
                        event.socket.send(MessageCodec.encode(Message.Hello(hello())))
                    }
                    is SocketEvent.Text -> handleText(event.text, mac, gen)
                    SocketEvent.Closed -> return Outcome.Closed(open)
                    is SocketEvent.Failure -> return when {
                        open -> Outcome.Closed(true)
                        TrustFailures.isTrustFailure(event.error) -> Outcome.Trust(event.error.message ?: "certificate rejected")
                        else -> Outcome.Failed
                    }
                }
            }
            return Outcome.Failed
        } finally {
            synchronized(this) { if (socket === webSocket) socket = null }
            webSocket.cancel()
        }
    }

    private suspend fun handleText(text: String, mac: PairedMac, gen: Int) {
        val message = try {
            MessageCodec.decode(text)
        } catch (e: MalformedMessageException) {
            return
        }
        if (message is Message.Hello) {
            setState(gen, ConnectionState.Connected(message.body.device))
            if (message.body.device != mac.name) saveIfCurrent(gen, mac.copy(name = message.body.device))
        }
        _incoming.emit(message)
    }
}
