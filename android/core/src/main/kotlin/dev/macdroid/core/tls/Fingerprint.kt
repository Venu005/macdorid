package dev.macdroid.core.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

object Fingerprint {
    /** Lowercase hex SHA-256 — the value both apps pin. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun of(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /** First 16 hex chars, as published in the Mac's Bonjour TXT record. */
    fun short(fingerprint: String): String = fingerprint.take(16)

    /** Grouped form both apps show for manual comparison, e.g. "8A3B A983 C438 8481". */
    fun display(fingerprint: String): String = short(fingerprint).uppercase().chunked(4).joinToString(" ")
}
