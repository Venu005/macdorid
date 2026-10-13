package dev.macdroid.core.connection

import dev.macdroid.core.tls.Fingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MacAddress(val host: String, val port: Int)

/** Finds the paired Mac on the network. Cold flow: collecting starts discovery, cancelling stops it. */
fun interface MacLocator {
    fun appearances(fingerprint: String): Flow<MacAddress>

    companion object {
        val None = MacLocator { emptyFlow() }

        /** The Mac advertises `fp=<first 16 hex of its fingerprint>` in its Bonjour TXT record. */
        fun txtMatches(attributes: Map<String, ByteArray?>, fingerprint: String): Boolean =
            attributes["fp"]?.toString(Charsets.UTF_8) == Fingerprint.short(fingerprint)
    }
}
