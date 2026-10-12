package dev.macdroid.core.pairing

import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.protocol.PairRequest
import dev.macdroid.core.protocol.PairResponse
import dev.macdroid.core.tls.CapturingTrustManager
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.PinnedTrustManager
import dev.macdroid.core.tls.RecordingTrustManager
import dev.macdroid.core.tls.TlsClientFactory
import dev.macdroid.core.tls.TrustFailures
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface PairResult {
    data class Success(val mac: PairedMac) : PairResult
    /**
     * Wrong code or the pairing window closed: the Mac answered 403, or it was the pinned Mac but its TLS
     * layer refused our (unpaired) certificate, which it does once the window is closed.
     */
    data object CodeRejected : PairResult
    /** The server's certificate is not the one in the QR code. */
    data object FingerprintMismatch : PairResult
    data class Failed(val reason: String) : PairResult
}

class PairingClient(private val identity: ClientIdentity, private val base: OkHttpClient = OkHttpClient()) {

    suspend fun pair(invite: PairingInvite, deviceName: String, model: String): PairResult = withContext(Dispatchers.IO) {
        val trust = RecordingTrustManager(PinnedTrustManager(invite.fingerprint))
        var responded = false
        try {
            val client = TlsClientFactory.create(identity, trust, base)
            val body = MessageCodec.json.encodeToString(PairRequest.serializer(), PairRequest(invite.code, deviceName, model))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url(invite.host, invite.port, "v1/pair")).post(body).build()
            client.newCall(request).execute().use { response ->
                responded = true
                when {
                    response.isSuccessful -> {
                        val reply = MessageCodec.json.decodeFromString(PairResponse.serializer(), response.body.string())
                        PairResult.Success(PairedMac(invite.fingerprint, reply.macName, invite.host, invite.port))
                    }
                    response.code == 403 -> PairResult.CodeRejected
                    else -> PairResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            when {
                // Only a server certificate that failed the pin means "not the Mac that showed the code".
                trust.serverRejected -> PairResult.FingerprintMismatch
                // The pinned Mac hung up before replying: it refused our certificate. With TLS 1.3 that refusal
                // arrives after our side of the handshake, so it surfaces as an alert, a reset or a closed stream.
                trust.serverAccepted && !responded -> PairResult.CodeRejected
                TrustFailures.isTrustFailure(e) -> PairResult.FingerprintMismatch
                else -> PairResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        } catch (e: SerializationException) {
            PairResult.Failed("unexpected reply from Mac")
        } catch (e: IllegalArgumentException) {
            PairResult.Failed("invalid address")
        }
    }

    /**
     * Manual pairing: completes a TLS handshake without trusting the server, returning its
     * fingerprint for the user to compare with the Mac. Null if the Mac can't be reached.
     */
    suspend fun probeFingerprint(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        val capture = CapturingTrustManager()
        runCatching {
            TlsClientFactory.create(identity, capture, base)
                .newCall(Request.Builder().url(url(host, port, "v1/info")).build())
                .execute()
                .close()
        }
        capture.captured
    }

    private fun url(host: String, port: Int, path: String) =
        HttpUrl.Builder().scheme("https").host(host).port(port).addPathSegments(path).build()
}
