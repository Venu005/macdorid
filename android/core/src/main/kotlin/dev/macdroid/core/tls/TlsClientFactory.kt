package dev.macdroid.core.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion

/** The phone's TLS client identity. On Android the key lives in the Keystore. */
data class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String get() = Fingerprint.of(certificate)
}

/** Always offers the one client identity. */
internal class FixedKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object { const val ALIAS = "macdroid" }
}

object TlsClientFactory {
    private val TLS_1_3_ONLY = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .build()

    fun create(identity: ClientIdentity, trustManager: X509TrustManager, base: OkHttpClient = OkHttpClient()): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(identity)), arrayOf(trustManager), null)
        }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            // The Mac has no CA-issued name; its identity is the pinned fingerprint, so hostnames are not checked.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(TLS_1_3_ONLY))
            .build()
    }

    fun pinned(identity: ClientIdentity, fingerprint: String, base: OkHttpClient = OkHttpClient()): OkHttpClient =
        create(identity, PinnedTrustManager(fingerprint), base)
}
