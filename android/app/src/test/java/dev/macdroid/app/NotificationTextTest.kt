package dev.macdroid.app

import dev.macdroid.app.platform.notifications.NotificationText
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test fun conversationTitleAndNewestMessageWin() {
        assertEquals("Family" to "Dinner at 8", NotificationText.pick("Family", "Mom", "Dinner at 8", "old big text", "2 new messages"))
    }

    @Test fun fallsBackToTitleBigTextThenText() {
        assertEquals("Amazon" to "Your order shipped and arrives Tuesday", NotificationText.pick(null, "Amazon", null, "Your order shipped and arrives Tuesday", "Your order shipped"))
        assertEquals("Amazon" to "Your order shipped", NotificationText.pick("  ", "Amazon", "", null, "Your order shipped"))
    }

    @Test fun missingEverythingIsEmptyAndTrimmed() {
        assertEquals("" to "", NotificationText.pick(null, null, null, null, null))
        assertEquals("Mom" to "Hi", NotificationText.pick(null, " Mom ", null, null, " Hi\n"))
    }
}
