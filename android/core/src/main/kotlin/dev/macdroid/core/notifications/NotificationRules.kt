package dev.macdroid.core.notifications

/** Which notifications go to the Mac, and how much of them (spec §3.1, §2.2). */
object NotificationRules {
    const val TITLE_LIMIT = 200
    const val TEXT_LIMIT = 2_000
    const val BUTTON_LIMIT = 3

    /** True for notifications that never go to the Mac. Mutes and the master switch are checked by the forwarder. */
    fun excluded(n: PhoneNotification, ownPackage: String): Boolean =
        n.packageName == ownPackage || n.ongoing || n.groupSummary || n.minImportance || (n.title.isBlank() && n.text.isBlank())

    /** Cuts [value] to at most [limit] chars ending in "…", never leaving half a surrogate pair. */
    fun clip(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var cut = value.take(limit - 1)
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return "$cut…"
    }
}
