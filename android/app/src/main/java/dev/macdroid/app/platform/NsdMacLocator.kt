package dev.macdroid.app.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.macdroid.core.connection.MacAddress
import dev.macdroid.core.connection.MacLocator
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Finds the paired Mac's `_macdroid._tcp` advertisement and resolves it to an address. */
class NsdMacLocator(context: Context) : MacLocator {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun appearances(fingerprint: String): Flow<MacAddress> = callbackFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        // resolveService handles one request at a time, so resolve sequentially.
        val resolver = launch {
            for (info in found) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) } ?: continue
                // IPv4 only: alternating v4/v6 results would look like "new addresses" and keep resetting backoff.
                val host = pickIpv4(addressesOf(resolved)) ?: continue
                if (MacLocator.txtMatches(resolved.attributes, fingerprint)) send(MacAddress(host, resolved.port))
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(serviceInfo)
            }
        }
        continuation.invokeOnCancellation {
            if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
        }
        nsd.resolveService(info, listener)
    }

    companion object {
        private const val SERVICE_TYPE = "_macdroid._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L

        /** First IPv4 address, or null. IPv6 is skipped so v4/v6 alternation can't reset reconnect backoff. */
        fun pickIpv4(addresses: List<InetAddress>): String? =
            addresses.firstOrNull { it is Inet4Address }?.hostAddress
    }
}
