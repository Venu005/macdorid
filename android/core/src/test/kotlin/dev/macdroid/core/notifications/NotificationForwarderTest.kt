package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.NotificationActionBody
import dev.macdroid.core.protocol.NotificationButton
import dev.macdroid.core.protocol.NotificationErrorBody
import dev.macdroid.core.protocol.NotificationErrorReason
import dev.macdroid.core.protocol.NotificationKeyBody
import dev.macdroid.core.protocol.NotificationMuteBody
import dev.macdroid.core.protocol.NotificationReplyBody
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwarderTest {
    private val sent = mutableListOf<Message>()
    private var now = 1_791_892_800_000L // 2026-10-13T00:00Z
    private val store = InMemoryNotificationSettingsStore()
    private val icons = mapOf("com.whatsapp" to AppIcon("3f2a9c1d0b7e4f52", "iVBORw0KGgo="))

    private fun forwarder(s: NotificationSettingsStore = store) =
        NotificationForwarder("dev.macdroid.app", s, { sent.add(it); true }, { icons[it] }, clock = { now }, zone = ZoneOffset.UTC)

    private fun chat(key: String = "k1", text: String = "Did you eat?", packageName: String = "com.whatsapp", appName: String = "WhatsApp") =
        PhoneNotification(key, packageName, appName, "Mom", text, now, listOf(NotificationButton(1, "Mark as read")), canReply = true)

    private inline fun <reified T : Message> sentOf(): List<T> = sent.filterIsInstance<T>()

    @Test fun forwardsWhileConnectedWithTheIconFirst() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.posted(chat())
        val posted = sentOf<Message.NotificationPosted>().single().body
        assertEquals("k1", posted.key)
        assertEquals("3f2a9c1d0b7e4f52", posted.iconHash)
        assertFalse(posted.isUpdate)
        assertTrue(sent.indexOfFirst { it is Message.NotificationIcon } < sent.indexOfFirst { it is Message.NotificationPosted })
    }

    @Test fun nothingIsSentWhileDisconnectedButTheAppIsRemembered() {
        val f = forwarder()
        f.posted(chat())
        assertTrue(sent.isEmpty())
        assertEquals(listOf("com.whatsapp"), f.state.value.apps.map { it.packageName })
    }

    @Test fun excludedNotificationsAreNeverSent() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.posted(chat(packageName = "dev.macdroid.app"))
        f.posted(chat(key = "a").copy(ongoing = true))
        f.posted(chat(key = "b").copy(groupSummary = true))
        f.posted(chat(key = "c").copy(minImportance = true))
        f.posted(chat(key = "d").copy(title = " ", text = ""))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
    }

    @Test fun mutedAppsAndTheMasterSwitchStopForwarding() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.setMuted("com.whatsapp", true)
        sent.clear()
        f.posted(chat(key = "k2"))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
        f.setMuted("com.whatsapp", false)
        f.setForwarding(false)
        f.posted(chat(key = "k3"))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
    }

    @Test fun iconIsSentOncePerConnection() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k2"))
        assertEquals(1, sentOf<Message.NotificationIcon>().size)
        f.disconnected()
        f.connected(setOf("k1", "k2"))
        f.posted(chat(key = "k3"))
        assertEquals(2, sentOf<Message.NotificationIcon>().size)
    }

    @Test fun identicalRepostIsNotSentAgain() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.posted(chat())
        assertEquals(1, sentOf<Message.NotificationPosted>().size)
    }

    @Test fun changedRepostIsSentAsAnUpdate() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.posted(chat(text = "Did you eat? Call me"))
        val bodies = sentOf<Message.NotificationPosted>().map { it.body }
        assertEquals(listOf(false, true), bodies.map { it.isUpdate })
    }

    @Test fun removingAForwardedNotificationTellsTheMac() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.removed("k1")
        f.removed("never-sent")
        assertEquals(listOf(Message.NotificationRemoved(NotificationKeyBody("k1"))), sentOf<Message.NotificationRemoved>())
    }

    @Test fun connectingSendsAppsThenSyncOfStillActiveKeys() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k2"))
        f.disconnected()
        sent.clear()
        f.connected(setOf("k2", "unrelated"))
        assertTrue(sent.first { it !is Message.NotificationIcon } is Message.NotificationApps)
        assertEquals(listOf("k2"), sentOf<Message.NotificationSync>().single().body.keys)
    }

    @Test fun afterARestartSyncIsEmpty() {
        forwarder().apply { connected(emptySet()); posted(chat()) }
        sent.clear()
        val restarted = forwarder() // same store, fresh process memory
        restarted.connected(setOf("k1"))
        assertEquals(emptyList<String>(), sentOf<Message.NotificationSync>().single().body.keys)
    }

    @Test fun muteFromTheMacIsAppliedAndConfirmed() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        sent.clear()
        assertNull(f.handle(Message.NotificationMute(NotificationMuteBody("com.whatsapp", true))))
        assertTrue(f.state.value.apps.single().muted)
        assertTrue(sentOf<Message.NotificationApps>().single().body.apps.single().muted)
    }

    @Test fun replyActionAndDismissBecomeCommands() {
        val f = forwarder()
        assertEquals(NotificationCommand.Reply("k1", "On my way"), f.handle(Message.NotificationReply(NotificationReplyBody("k1", "On my way"))))
        assertEquals(NotificationCommand.Action("k1", 1), f.handle(Message.NotificationAction(NotificationActionBody("k1", 1))))
        assertEquals(NotificationCommand.Dismiss("k1"), f.handle(Message.NotificationDismiss(NotificationKeyBody("k1"))))
    }

    @Test fun errorsAreReportedToTheMac() {
        forwarder().reportError("k1", NotificationErrorReason.Gone)
        assertEquals(listOf(Message.NotificationError(NotificationErrorBody("k1", NotificationErrorReason.Gone))), sent)
    }

    @Test fun titleAndTextAreClippedAndButtonsLimitedToThree() {
        val f = forwarder()
        f.connected(emptySet())
        val buttons = (0..4).map { NotificationButton(it, "B$it") }
        f.posted(chat().copy(title = "t".repeat(250), text = "x".repeat(2_500), actions = buttons))
        val body = sentOf<Message.NotificationPosted>().single().body
        assertEquals(200, body.title.length)
        assertTrue(body.title.endsWith("…"))
        assertEquals(2_000, body.text.length)
        assertEquals(listOf(0, 1, 2), body.actions.map { it.index })
    }

    @Test fun clipNeverSplitsASurrogatePair() {
        val value = "a".repeat(198) + "😀" + "tail" // the emoji occupies chars 198–199
        val clipped = NotificationRules.clip(value, 200)
        assertTrue(clipped.endsWith("…"))
        assertFalse(clipped.dropLast(1).last().isHighSurrogate())
    }

    @Test fun countsDistinctNotificationsPerDay() {
        val f = forwarder()
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k1", text = "again")) // same notification, updated
        f.posted(chat(key = "k2"))
        assertEquals(2, f.state.value.apps.single().count)
        now += 24 * 60 * 60 * 1000L
        f.posted(chat(key = "k3"))
        assertEquals(1, f.state.value.apps.single().count)
    }

    @Test fun settingsSurviveARestart() {
        forwarder().apply { posted(chat()); setMuted("com.whatsapp", true); setForwarding(false) }
        val state = forwarder().state.value
        assertFalse(state.forwarding)
        assertTrue(state.apps.single().muted)
    }

    @Test fun accessChangesAreSentToTheMac() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.accessChanged(true)
        assertTrue(sentOf<Message.NotificationApps>().single().body.access)
        assertTrue(f.state.value.access)
    }
}
