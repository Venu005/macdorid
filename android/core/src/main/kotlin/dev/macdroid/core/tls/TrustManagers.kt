package dev.macdroid.core.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Trusts exactly one server certificate, identified by its SHA-256 fingerprint. */
class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        if (Fingerprint.of(leaf) != expectedFingerprint) throw CertificateException("certificate fingerprint mismatch")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts any server certificate but records its fingerprint. Only for the manual-pairing probe,
 * whose result the user must confirm against the fingerprint shown on the Mac.
 */
class CapturingTrustManager : X509TrustManager {
    @Volatile var captured: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        captured = Fingerprint.of(chain.firstOrNull() ?: throw CertificateException("empty certificate chain"))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
