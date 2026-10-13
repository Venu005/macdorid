package dev.macdroid.core.tls

import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Distinguishes "wrong Mac / Mac doesn't know this phone" from ordinary network failures. */
object TrustFailures {
    private val alertMarkers = listOf("certificate_unknown", "bad_certificate", "certificate_required", "unknown_ca", "access_denied")

    /**
     * OkHttp records a failed TLS attempt on one route as a suppressed exception when a later route
     * (e.g. the IPv4 address after a refused IPv6 one) then also fails, so suppressed errors are inspected too.
     */
    fun isTrustFailure(error: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        val pending = ArrayDeque<Throwable>().apply { add(error) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is CertificateException || current is SSLPeerUnverifiedException) return true
            val message = current.message?.lowercase()
            if (current is SSLException && message != null && alertMarkers.any { it in message }) return true
            current.cause?.let { pending.add(it) }
            pending.addAll(current.suppressed)
        }
        return false
    }
}
