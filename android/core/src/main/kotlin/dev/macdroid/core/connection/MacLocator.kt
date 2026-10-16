package dev.macdroid.core.connection

import dev.macdroid.core.tls.Fingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MacAddress(val host: String, val port: Int)

/**
 * Finds the paired Mac on the network. Cold flow: collecting starts discovery, cancelling stops it. It is collected
 * once for as long as the phone wants the Mac, and each emission is a fresh sighting (first seen, or found again after
 * being lost), possibly at the same address: the connection manager retries on every sighting.
 */
fun interface MacLocator {
    fun appearances(fingerprint: String): Flow<MacAddress>

    companion object {
        val None = MacLocator { emptyFlow() }

        /** The Mac advertises `fp=<first 16 hex of its fingerprint>` in its Bonjour TXT record. */
        fun txtMatches(attributes: Map<String, ByteArray?>, fingerprint: String): Boolean =
            attributes["fp"]?.toString(Charsets.UTF_8) == Fingerprint.short(fingerprint)
    }
}
