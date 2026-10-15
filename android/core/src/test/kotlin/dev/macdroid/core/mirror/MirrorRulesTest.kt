package dev.macdroid.core.mirror

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPhaseName
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStateBody
import dev.macdroid.core.protocol.MirrorStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRulesTest {
    @Test fun sizesFitAndAreMultiplesOfSixteen() {
        assertEquals(864 to 1920, MirrorSize.fit(1080, 2400, 1920))
        assertEquals(576 to 1280, MirrorSize.fit(1080, 2400, 1280))
        assertEquals(1920 to 864, MirrorSize.fit(2400, 1080, 1920))
        assertEquals(720 to 1280, MirrorSize.fit(720, 1280, 1920)) // never upscaled
        assertEquals(8_000_000, MirrorSize.bitrate(1920))
        assertEquals(4_000_000, MirrorSize.bitrate(1280))
    }

    // Review Focus #2 (phone side)
    @Test fun senderDropsVideoUntilAKeyFrameWhenTheQueueIsFull() {
        val policy = SendPolicy(8_000_000)
        assertEquals(300_000L, policy.videoLimitBytes)
        assertEquals(200_000L, policy.audioLimitBytes)
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = false, queuedBytes = 300_000))
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 300_001))
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 0)) // drained: ask again once
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = true, queuedBytes = 400_000)) // still backed up
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = true, queuedBytes = 10))
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = false, queuedBytes = 10))
        assertTrue(policy.audio(200_000))
        assertFalse(policy.audio(200_001))
    }

    private val sent = mutableListOf<Message>()
    private val ends = mutableListOf<MirrorStopReason>()
    private var now = 0L
    private var inputEnds = 0
    private fun session() = MirrorPhoneSession({ sent += it; true }, { now }, { ends += it }, onInputEnd = { inputEnds++ })
    private val start = MirrorStartBody("s1", 1920, 60, true)

    @Test fun aSessionStartsStreamsAndEnds() {
        val s = session()
        assertTrue(s.start(start))
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Starting)), sent.last())
        s.streaming(864, 1920, 0, audio = true, control = false)
        assertTrue(s.isStreaming)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Streaming, 864, 1920, 0, audio = true, control = false)), sent.last())
        s.ended(MirrorStopReason.User)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Stopped, reason = MirrorStopReason.User)), sent.last())
        assertNull(s.current)
        assertTrue(ends.isEmpty()) // the capture side ended it itself
    }

    @Test fun anewStartReplacesTheOldSession() {
        val s = session()
        s.start(start)
        s.streaming(864, 1920, 0, audio = true, control = false)
        assertTrue(s.start(start.copy(sessionId = "s2")))
        assertEquals(listOf(MirrorStopReason.Mac), ends)
        assertEquals("s2", s.current)
    }

    @Test fun consentTimesOutAfterSixtySeconds() {
        val s = session()
        s.start(start)
        now = 59_999
        s.checkTimeout()
        assertEquals("s1", s.current)
        now = 60_000
        s.checkTimeout()
        assertNull(s.current)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)), sent.last())
        assertEquals(listOf(MirrorStopReason.ConsentTimeout), ends)
    }

    @Test fun deniedAndMacStops() {
        val s = session()
        s.start(start)
        s.consentDenied()
        assertEquals(MirrorStopReason.Denied, (sent.last() as Message.MirrorState).body.reason)
        s.start(start.copy(sessionId = "s2"))
        s.macStopped("other")
        assertEquals("s2", s.current)
        s.macStopped("s2")
        assertNull(s.current)
        assertEquals(MirrorStopReason.Mac, ends.last())
    }

    // Review Focus #5
    @Test fun phoneSessionEndsWhenTheMacGoes() {
        val s = session()
        s.start(start)
        s.streaming(864, 1920, 0, audio = false, control = false)
        val before = sent.size
        s.connectionLost()
        assertNull(s.current)
        assertEquals(listOf(MirrorStopReason.Mac), ends)
        assertEquals(before, sent.size) // nothing to send to: the Mac is gone
    }

    // Final review I3: the key frame asked for while backed up is usually dropped too; ask again once the queue drains.
    @Test fun aDrainedQueueAsksForAKeyFrameAgainOnce() {
        val policy = SendPolicy(8_000_000)
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 400_000))
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = true, queuedBytes = 400_000)) // the requested one, still backed up
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 1_000)) // drained: ask again
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = false, queuedBytes = 1_000)) // only once
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = false, queuedBytes = 400_000)) // backed up again
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 1_000)) // drained again: ask again
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = true, queuedBytes = 1_000))
    }

    // Review Focus #1 (arming) and #2 (gating)
    @Test fun notArmedWithoutAWaitingSession() {
        val s = session()
        assertFalse(s.awaitingConsent)
        s.start(start)
        assertTrue(s.awaitingConsent)
        s.streaming(864, 1920, 0, audio = false, control = true)
        assertFalse(s.awaitingConsent)
        s.ended(MirrorStopReason.User)
        assertFalse(s.awaitingConsent)
    }

    @Test fun controlChangeResendsTheState() {
        val s = session()
        s.controlChanged(true) // no session: nothing
        assertTrue(sent.isEmpty())
        s.start(start)
        s.streaming(864, 1920, 90, audio = true, control = false)
        s.controlChanged(true)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Streaming, 864, 1920, 90, audio = true, control = true)), sent.last())
    }

    @Test fun phoneIgnoresInputWhenNotStreaming() {
        val s = session()
        assertFalse(s.isStreaming)
        s.start(start)
        assertFalse(s.isStreaming) // waiting for consent: no input yet
    }

    // Final review I2: leaving streaming, however it happens, lifts any finger the Mac left down.
    @Test fun leavingStreamingEndsInput() {
        val s = session()
        s.start(start)
        s.macStopped("s1") // never streamed: nothing to lift
        assertEquals(0, inputEnds)
        s.start(start)
        s.streaming(864, 1920, 0, audio = false, control = true)
        s.macStopped("s1")
        assertEquals(1, inputEnds)
        s.start(MirrorStartBody("s2", 1920, 60, true))
        s.streaming(864, 1920, 0, audio = false, control = true)
        s.connectionLost()
        assertEquals(2, inputEnds)
        s.start(MirrorStartBody("s3", 1920, 60, true))
        s.streaming(864, 1920, 0, audio = false, control = true)
        s.ended(MirrorStopReason.User)
        assertEquals(3, inputEnds)
    }
}
