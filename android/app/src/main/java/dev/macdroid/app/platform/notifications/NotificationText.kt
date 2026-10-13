package dev.macdroid.app.platform.notifications

/** Picks what the Mac shows (spec §8.5). */
object NotificationText {
    /** Title: the conversation title, else EXTRA_TITLE. Text: the newest MessagingStyle message, else EXTRA_BIG_TEXT, else EXTRA_TEXT. */
    fun pick(
        conversationTitle: CharSequence?,
        title: CharSequence?,
        lastMessage: CharSequence?,
        bigText: CharSequence?,
        text: CharSequence?,
    ): Pair<String, String> {
        val chosenTitle = listOf(conversationTitle, title).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        val chosenText = listOf(lastMessage, bigText, text).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        return chosenTitle to chosenText
    }
}
