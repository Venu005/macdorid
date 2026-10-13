package dev.macdroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.macdroid.app.platform.KeystoreIdentity
import dev.macdroid.core.tls.Fingerprint
import dev.macdroid.core.tls.TlsClientFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Proves a Keystore-backed EC key can authenticate a TLS 1.3 client handshake on this device. */
@RunWith(AndroidJUnit4::class)
class KeystoreTlsTest {
    @Test fun keystoreIdentityAuthenticatesOverTls13() {
        val identity = KeystoreIdentity.getOrCreate()
        val serverCert = HeldCertificate.Builder().ecdsa256().commonName("mac").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(identity.certificate).build().sslSocketFactory(),
        )
        server.requireClientAuth()
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.start()
        try {
            val client = TlsClientFactory.pinned(identity, Fingerprint.of(serverCert.certificate))
            client.newCall(Request.Builder().url(server.url("/v1/info")).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(identity.certificate, server.takeRequest().handshake?.peerCertificates?.first())
        } finally {
            server.close()
        }
    }

    @Test fun identityIsStable() {
        assertEquals(KeystoreIdentity.getOrCreate().fingerprint, KeystoreIdentity.getOrCreate().fingerprint)
    }
}
