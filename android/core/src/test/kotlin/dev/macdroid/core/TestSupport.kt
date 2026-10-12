package dev.macdroid.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

object Fixtures {
    private val dir = File(System.getProperty("fixturesDir") ?: error("fixturesDir system property not set"))
    fun text(name: String): String = File(dir, name).readText()
    fun id(text: String): String = Json.parseToJsonElement(text).jsonObject.getValue("id").jsonPrimitive.content
}

/** Polls [condition] until true or fails after [timeoutMs]. */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        Thread.sleep(20)
    }
    fail("condition not met within ${timeoutMs}ms")
}

// --- MockMac: an mTLS MockWebServer that behaves like the Mac ---

/**
 * A MockWebServer with its own self-signed certificate that requires the phone's client certificate,
 * mirroring the real Mac server's mutual TLS.
 */
class MockMac(val macName: String = "Test Mac") : java.io.Closeable {
    private val serverCert = okhttp3.tls.HeldCertificate.Builder().ecdsa256().commonName("mac").build()
    private val phoneCert = okhttp3.tls.HeldCertificate.Builder().ecdsa256().commonName("phone").build()
    val phoneIdentity = dev.macdroid.core.tls.ClientIdentity(phoneCert.keyPair.private, phoneCert.certificate)
    val fingerprint: String = dev.macdroid.core.tls.Fingerprint.of(serverCert.certificate)
    val received = java.util.concurrent.LinkedBlockingQueue<dev.macdroid.core.protocol.Message>()
    val server = mockwebserver3.MockWebServer().apply {
        useHttps(
            okhttp3.tls.HandshakeCertificates.Builder()
                .heldCertificate(serverCert)
                .addTrustedCertificate(phoneCert.certificate)
                .build()
                .sslSocketFactory(),
        )
        requireClientAuth()
    }

    fun start() = server.start()

    fun pairedMac() = dev.macdroid.core.pairing.PairedMac(fingerprint, macName, server.hostName, server.port)

    fun enqueueJson(code: Int, body: String) {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(code).body(body).build())
    }

    /** Next request upgrades to a WebSocket that answers the phone's hello with the Mac's hello. */
    fun enqueueSession(closeAfterHello: Boolean = false) {
        server.enqueue(
            mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val message = dev.macdroid.core.protocol.MessageCodec.decode(text)
                    received.add(message)
                    if (message is dev.macdroid.core.protocol.Message.Hello) {
                        webSocket.send(
                            dev.macdroid.core.protocol.MessageCodec.encode(
                                dev.macdroid.core.protocol.Message.Hello(
                                    dev.macdroid.core.protocol.HelloBody(app = "0.1.0", device = macName),
                                ),
                            ),
                        )
                        if (closeAfterHello) webSocket.close(1000, "bye")
                    }
                }
            }).build(),
        )
    }

    override fun close() = server.close()
}
