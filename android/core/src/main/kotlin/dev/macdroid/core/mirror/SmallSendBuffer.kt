package dev.macdroid.core.mirror

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Sockets for the media stream (final review I4): a small kernel send buffer, so a slow network backs up in OkHttp's queue,
 * where [SendPolicy] can see it and drop to a key frame, and no Nagle delay.
 */
class SmallSendBuffer(private val bytes: Int, private val base: SocketFactory = SocketFactory.getDefault()) : SocketFactory() {
    private fun Socket.tuned(): Socket = apply {
        sendBufferSize = bytes
        tcpNoDelay = true
    }

    override fun createSocket(): Socket = base.createSocket().tuned()
    override fun createSocket(host: String, port: Int): Socket = base.createSocket(host, port).tuned()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        base.createSocket(host, port, localHost, localPort).tuned()
    override fun createSocket(host: InetAddress, port: Int): Socket = base.createSocket(host, port).tuned()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        base.createSocket(address, port, localAddress, localPort).tuned()
}
