package dev.macdroid.app

import dev.macdroid.app.ui.presentation.NotificationsPresentation
import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.notifications.SeenApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsPresentationTest {
    private val on = NotificationsState(forwarding = true, access = true, apps = emptyList())

    @Test fun forwardingCardNamesTheMac() {
        val card = NotificationsPresentation.topCard(on, "Venusai's MacBook Air", macSupports = true, connected = true)
        assertEquals("Show on your Mac", card.title)
        assertEquals("Forwarding to Venusai's MacBook Air", card.subtitle)
        assertTrue(card.showsSwitch)
    }

    @Test fun offCardSaysNotificationsStayHere() {
        val card = NotificationsPresentation.topCard(on.copy(forwarding = false), "Mac", macSupports = true, connected = false)
        assertEquals("Off — notifications stay on this phone", card.subtitle)
    }

    @Test fun accessOffAsksToTurnItOn() {
        val card = NotificationsPresentation.topCard(on.copy(access = false), "Mac", macSupports = true, connected = true)
        assertEquals("Notification access is off", card.title)
        assertEquals("Turn on", card.actionLabel)
        assertFalse(card.showsSwitch)
    }

    @Test fun oldMacAsksForAnUpdate() {
        val card = NotificationsPresentation.topCard(on, "Mac", macSupports = false, connected = true)
        assertEquals("Update MacDroid on your Mac to use this", card.title)
        assertFalse(card.showsSwitch)
    }

    @Test fun appSubtitles() {
        val today = 20_374L
        assertEquals("Muted", NotificationsPresentation.appSubtitle(SeenApp("a", "A", muted = true, countDay = today, count = 4), today))
        assertEquals("12 today", NotificationsPresentation.appSubtitle(SeenApp("a", "A", countDay = today, count = 12), today))
        assertEquals("Shown on your Mac", NotificationsPresentation.appSubtitle(SeenApp("a", "A", countDay = today - 1, count = 3), today))
    }
}
