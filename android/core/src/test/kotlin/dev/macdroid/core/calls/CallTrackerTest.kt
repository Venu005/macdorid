package dev.macdroid.core.calls

import dev.macdroid.core.protocol.CallActionBody
import dev.macdroid.core.protocol.CallActionKind
import dev.macdroid.core.protocol.CallDialBody
import dev.macdroid.core.protocol.CallErrorBody
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.CallPhase
import dev.macdroid.core.protocol.CallStateBody
import dev.macdroid.core.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {
    private val sent = mutableListOf<Message>()
    private val tracker = CallTracker { sent += it; true }
    private val all = setOf(CallActionKind.Answer, CallActionKind.Decline, CallActionKind.HangUp)
    private fun ringing(key: String = "k1", name: String? = "Mom", number: String? = "+15551234567") =
        CallNotification(key, CallType.Incoming, name, number, setOf(CallActionKind.Answer, CallActionKind.Decline))
    private fun state(key: String, phase: CallPhase, name: String? = "Mom", number: String? = "+15551234567") =
        Message.CallState(CallStateBody(key, phase, name, number))

    @Test fun ringingThenActiveThenEnded() {
        tracker.connected()
        tracker.posted(ringing())
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", "+15551234567", setOf(CallActionKind.HangUp)))
        tracker.removed("k1")
        assertEquals(listOf(state("k1", CallPhase.Ringing), state("k1", CallPhase.Active), state("k1", CallPhase.Ended)), sent)
    }

    @Test fun onlyChangesAreSent() {
        tracker.connected()
        tracker.posted(ringing())
        tracker.posted(ringing()) // the dialer updates its notification while ringing
        tracker.posted(ringing(name = "Mom (mobile)"))
        assertEquals(listOf(state("k1", CallPhase.Ringing), state("k1", CallPhase.Ringing, name = "Mom (mobile)")), sent)
    }

    @Test fun screeningCountsAsRinging() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Screening, null, null, emptySet()))
        assertEquals(listOf(Message.CallState(CallStateBody("k1", CallPhase.Ringing))), sent)
    }

    @Test fun unknownRemovalsSendNothing() {
        tracker.connected()
        tracker.removed("not-a-call")
        assertTrue(sent.isEmpty())
    }

    // Review Focus #1
    @Test fun twoCallsAreTrackedSeparately() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", null, all))
        tracker.posted(ringing("k2", "Dad", null))
        tracker.removed("k2")
        assertEquals(listOf(
            state("k1", CallPhase.Active, number = null),
            state("k2", CallPhase.Ringing, "Dad", null),
            state("k2", CallPhase.Ended, "Dad", null),
        ), sent)
    }

    @Test fun nothingIsSentBeforeTheMacIsReady() {
        tracker.posted(ringing())
        assertTrue(sent.isEmpty())
        tracker.connected()
        assertEquals(listOf(state("k1", CallPhase.Ringing)), sent)
    }

    // Review Focus #3
    @Test fun reconnectingResendsCurrentCalls() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", null, all))
        tracker.disconnected()
        tracker.removed("gone-while-offline")
        tracker.connected()
        assertEquals(listOf(state("k1", CallPhase.Active, number = null), state("k1", CallPhase.Active, number = null)), sent)
    }

    @Test fun actionsResolveToTheNotification() {
        tracker.connected()
        tracker.posted(ringing())
        assertEquals(CallCommand.Fire("k1", CallActionKind.Answer), tracker.handle(Message.CallAction(CallActionBody("k1", CallActionKind.Answer))))
    }

    // Review Focus #2
    @Test fun actionsOnUnknownCallsAreCallGone() {
        tracker.connected()
        assertNull(tracker.handle(Message.CallAction(CallActionBody("old", CallActionKind.Answer))))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.CallGone, "old")), sent.last())
    }

    @Test fun missingActionsAreNotSupported() {
        tracker.connected()
        tracker.posted(ringing())
        assertNull(tracker.handle(Message.CallAction(CallActionBody("k1", CallActionKind.HangUp))))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.NotSupported, "k1")), sent.last())
    }

    // Review Focus #4
    @Test fun onlyCleanNumbersAreDialled() {
        assertEquals(CallCommand.Dial("+15551234567"), tracker.handle(Message.CallDial(CallDialBody("+15551234567"))))
        assertEquals(CallCommand.Dial("*#06#"), tracker.handle(Message.CallDial(CallDialBody("*#06#"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody(""))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("+1 555 123"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("1+5"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("1".repeat(33)))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("+"))))
    }

    @Test fun reportSendsAnError() {
        tracker.report(CallErrorReason.NoPermission, null)
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.NoPermission)), sent.single())
    }

    @Test fun numbersAndUris() {
        assertEquals("+15551234567", CallNumbers.clean(" +1 (555) 123-4567 "))
        assertEquals("15551234567", CallNumbers.clean("1-555-123-4567 ext"))
        assertNull(CallNumbers.clean("call me"))
        assertNull(CallNumbers.clean("+"))
        assertTrue(CallNumbers.isClean("+15551234567"))
        assertFalse(CallNumbers.isClean("+1 555"))
        assertEquals("+15551234567", CallNumbers.fromUri("tel:+15551234567"))
        assertEquals("+15551234567", CallNumbers.fromUri("tel:%2B1-555-123-4567"))
        assertNull(CallNumbers.fromUri("mailto:mom@example.com"))
        assertNull(CallNumbers.fromUri("content://com.android.contacts/contacts/lookup/123"))
        assertNull(CallNumbers.fromUri(null))
    }

    @Test fun buttonLabels() {
        assertEquals(CallActionKind.Answer, CallLabels.kindOf("Answer"))
        assertEquals(CallActionKind.Decline, CallLabels.kindOf("DECLINE"))
        assertEquals(CallActionKind.Decline, CallLabels.kindOf("Reject"))
        assertEquals(CallActionKind.HangUp, CallLabels.kindOf("Hang up"))
        assertEquals(CallActionKind.HangUp, CallLabels.kindOf("End call"))
        assertNull(CallLabels.kindOf("Speaker"))
        assertNull(CallLabels.kindOf(null))
    }
}
