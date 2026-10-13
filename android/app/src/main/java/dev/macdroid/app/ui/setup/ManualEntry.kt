package dev.macdroid.app.ui.setup

import dev.macdroid.core.pairing.PairingInvite

object ManualEntry {
    private val BASE32_CODE = Regex("^[A-Z2-7]{26}$")

    /** The code the Mac shows is 26 base32 characters; spaces, dashes and case are ignored. */
    fun normalizedCodeOrNull(raw: String): String? = PairingInvite.normalizeCode(raw).takeIf { BASE32_CODE.matches(it) }
}
