package dev.macdroid.core.pairing

import java.net.URI
import java.net.URLDecoder

/** Contents of the Mac's pairing QR code (see protocol/fixtures/pairing-uri.txt). */
data class PairingInvite(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val name: String,
) {
    companion object {
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        fun parse(uri: String): PairingInvite? {
            val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (parsed.scheme != "macdroid" || parsed.host != "pair") return null
            val params = parsed.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate { part ->
                val split = part.indexOf('=')
                if (split < 0) part to "" else part.substring(0, split) to decode(part.substring(split + 1))
            }
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = params["fp"]?.takeIf { HEX_64.matches(it) } ?: return null
            val code = normalizeCode(params["code"].orEmpty()).takeIf { it.isNotEmpty() } ?: return null
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: "Mac"
            return PairingInvite(host, port, fingerprint, code, name)
        }

        /** Accepts codes typed with spaces, dashes or lowercase. */
        /** Upper-cased, keeping only the base32 alphabet (A–Z, 2–7) the Mac's codes use. */
        fun normalizeCode(raw: String): String = raw.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }

        // The String-charset overload works on every Android version (the Charset one needs API 33).
        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}
