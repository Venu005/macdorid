package dev.macdroid.core.tls

import dev.macdroid.core.MockMac
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTest {
    @Test fun fingerprintMatchesSwiftVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Fingerprint.sha256Hex("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea", Fingerprint.short("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertEquals("BA78 16BF 8F01 CFEA", Fingerprint.display("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test fun pinnedTrustManagerChecksLeafFingerprint() {
        val cert = HeldCertificate.Builder().ecdsa256().build().certificate
        PinnedTrustManager(Fingerprint.of(cert)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) { PinnedTrustManager("00".repeat(32)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { PinnedTrustManager(Fingerprint.of(cert)).checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
    }

    @Test fun capturingTrustManagerRecordsFingerprint() {
        val cert = HeldCertificate.Builder().ecdsa256().build().certificate
        val capture = CapturingTrustManager()
        capture.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertEquals(Fingerprint.of(cert), capture.captured)
    }

    @Test fun pinnedClientPresentsIdentityOverTls13() {
        MockMac().use { mac ->
            mac.enqueueJson(200, "{}")
            mac.start()
            val client = TlsClientFactory.pinned(mac.phoneIdentity, mac.fingerprint)
            client.newCall(Request.Builder().url(mac.server.url("/v1/info")).build()).execute().use { assertEquals(200, it.code) }
            val handshake = mac.server.takeRequest().handshake
            assertEquals(mac.phoneIdentity.certificate, handshake?.peerCertificates?.first())
            assertEquals(TlsVersion.TLS_1_3, handshake?.tlsVersion)
        }
    }

    @Test fun wrongPinIsATrustFailure() {
        MockMac().use { mac ->
            mac.start()
            val client = TlsClientFactory.pinned(mac.phoneIdentity, "00".repeat(32))
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(mac.server.url("/v1/info")).build()).execute()
            }
            assertTrue(TrustFailures.isTrustFailure(error))
        }
    }

    @Test fun trustFailureClassification() {
        assertTrue(TrustFailures.isTrustFailure(SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertTrue(TrustFailures.isTrustFailure(IOException("wrapped", CertificateException("fingerprint mismatch"))))
        assertTrue(TrustFailures.isTrustFailure(javax.net.ssl.SSLException("error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN")))
        assertFalse(TrustFailures.isTrustFailure(IOException("Connection refused")))
        assertFalse(TrustFailures.isTrustFailure(java.net.ProtocolException("Expected HTTP 101 response but was '500 Server Error'")))
    }
}
