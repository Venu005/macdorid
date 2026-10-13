package dev.macdroid.core.transfer

import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** The paired Mac's file endpoint: a pinned mTLS client for file calls, rebuilt only when the Mac's fingerprint changes. */
class FileEndpoint(
    private val store: PairingStore,
    private val identity: () -> ClientIdentity,
    private val base: OkHttpClient = OkHttpClient(),
) {
    private var cached: Pair<String, OkHttpClient>? = null

    @Synchronized
    fun current(): TransferClient.Endpoint? {
        val mac = store.paired.value ?: return null
        val http = cached?.takeIf { it.first == mac.fingerprint }?.second
            ?: TransferClient.forFiles(TlsClientFactory.pinned(identity(), mac.fingerprint, base)).also { cached = mac.fingerprint to it }
        return TransferClient.Endpoint(http, HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).build())
    }
}
