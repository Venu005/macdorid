package dev.macdroid.app.platform.mirror

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * The second mutual-TLS WebSocket, `/v1/mirror`, gated by the session id (spec §3.4, §5.3).
 * One automatic reconnect; a second drop calls [onGone].
 */
class MediaStreamClient(
    private val client: OkHttpClient,
    private val host: String,
    private val port: Int,
    private val sessionId: String,
    private val onOpen: () -> Unit,
    private val onGone: () -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    /** True only between the socket's open and its loss: frames sent earlier would go out late (Plan 7 minor #7). */
    @Volatile var isOpen = false
        private set
    private var reconnected = false

    /** Bytes waiting to be sent; drives [dev.macdroid.core.mirror.SendPolicy]. */
    val queuedBytes: Long get() = socket?.queueSize() ?: Long.MAX_VALUE

    fun open() {
        val url = HttpUrl.Builder().scheme("https").host(host).port(port).addPathSegments("v1/mirror").build()
        val request = Request.Builder().url(url).header(SESSION_HEADER, sessionId).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                onOpen()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
        })
    }

    fun send(frame: ByteArray): Boolean = isOpen && socket?.send(frame.toByteString()) ?: false

    fun close() {
        closed = true
        isOpen = false
        socket?.close(1000, null)
        socket = null
    }

    @Synchronized private fun dropped() {
        isOpen = false
        if (closed) return
        socket = null
        if (!reconnected) {
            reconnected = true
            open()
        } else {
            onGone()
        }
    }

    companion object {
        const val SESSION_HEADER = "X-MacDroid-Session"
    }
}
