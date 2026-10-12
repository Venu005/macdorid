package dev.macdroid.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.macdroid.core.tls.ClientIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The phone's TLS identity. The private key never leaves the Android Keystore. */
object KeystoreIdentity {
    private const val ALIAS = "macdroid-identity"
    private const val DAY_MS = 86_400_000L

    @Synchronized
    fun getOrCreate(): ClientIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate()
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return ClientIdentity(entry.privateKey, entry.certificate as X509Certificate)
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS stacks sign either pre-hashed (NONE) or with SHA-256 depending on version; allow both.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=MacDroid Phone"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 20 * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }
}
