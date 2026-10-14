import Foundation
import Testing
@testable import MacDroidCore

private struct FrameFixture: Decodable { let type: UInt8; let key: Bool; let captureMicros: Int64; let payload: String; let hex: String }
private struct FrameFixtures: Decodable { let frames: [FrameFixture] }

extension Data {
    init(hexString: String) {
        self.init()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            append(UInt8(hexString[index..<next], radix: 16)!)
            index = next
        }
    }
}

@Suite struct MirrorFrameTests {
    @Test func framesMatchTheSharedFixture() throws {
        let fixtures = try JSONDecoder().decode(FrameFixtures.self, from: Data(Fixtures.text("mirror-frames.json").utf8))
        for f in fixtures.frames {
            let frame = MirrorFrame(type: try #require(MirrorFrameType(rawValue: f.type)), keyFrame: f.key, captureMicros: f.captureMicros, payload: Data(hexString: f.payload))
            #expect(frame.encoded() == Data(hexString: f.hex))
            #expect(try MirrorFrame.decode(Data(hexString: f.hex)) == frame)
        }
    }

    @Test func badFramesAreRejected() {
        #expect(throws: MirrorFrameError.tooShort) { try MirrorFrame.decode(Data([1, 0, 0])) }
        #expect(throws: MirrorFrameError.unknownType(9)) { try MirrorFrame.decode(Data([9] + Array(repeating: 0, count: 11))) }
        #expect(throws: MirrorFrameError.tooLarge) { try MirrorFrame.decode(Data(count: MirrorFrame.maxSize + 1)) }
    }
}

@Suite struct MirrorTimingTests {
    @Test func clockOffsetIsTheMedianOfTheLastFive() {
        var offset = ClockOffset()
        #expect(offset.micros == nil)
        // phone = mac + 5_000; one-way delay 300 µs each way.
        offset.add(t0: 1_000, t1: 6_300, t2: 6_400, t3: 1_700)
        #expect(offset.micros == 5_000)
        offset.add(t0: 2_000, t1: 9_000, t2: 9_000, t3: 2_000) // an outlier: 7_000
        offset.add(t0: 3_000, t1: 8_300, t2: 8_400, t3: 3_700)
        #expect(offset.micros == 5_000)
        for i in 0..<5 { offset.add(t0: Int64(i), t1: Int64(i) + 6_000, t2: Int64(i) + 6_000, t3: Int64(i)) }
        #expect(offset.micros == 6_000) // only the last five count
    }

    // Final review I5: no frame for over a second while streaming means stalled, not live.
    @Test func noFrameForASecondIsAStall() {
        let meter = LatencyMeter()
        #expect(!meter.isStalled(now: 5_000_000)) // nothing shown yet: not a stall
        meter.setOffset(0)
        meter.record(captureMicros: 90_000, displayedMicros: 100_000)
        #expect(!meter.isStalled(now: 1_000_000))
        #expect(meter.freshAverageMillis(now: 1_000_000) == 10)
        #expect(meter.isStalled(now: 1_100_001))
        #expect(meter.freshAverageMillis(now: 1_100_001) == nil)
    }

    @Test func latencyUsesTheOffsetAndAverages() {
        let meter = LatencyMeter()
        meter.record(captureMicros: 10_000, displayedMicros: 20_000)
        #expect(meter.averageMillis == nil) // no offset yet
        meter.setOffset(5_000) // phone = mac + 5 ms
        meter.record(captureMicros: 105_000, displayedMicros: 150_000) // captured at mac 100 ms, shown at 150 → 50 ms
        meter.record(captureMicros: 205_000, displayedMicros: 270_000) // 70 ms
        #expect(meter.averageMillis == 60)
        meter.reset()
        #expect(meter.averageMillis == nil)
    }
}

@Suite struct VideoBacklogTests {
    // Review Focus #2 (Mac side)
    @Test func backlogDropsToTheNextKeyFrame() {
        var backlog = VideoBacklog()
        #expect(backlog.admit(keyFrame: false, waiting: 3) == .show)
        #expect(backlog.admit(keyFrame: false, waiting: 4) == .dropAndRequestKeyFrame)
        #expect(backlog.admit(keyFrame: false, waiting: 0) == .drop) // still waiting for a key frame
        #expect(backlog.admit(keyFrame: true, waiting: 0) == .show)
        #expect(backlog.admit(keyFrame: false, waiting: 1) == .show)
    }

    @Test func aKeyFrameIsShownEvenWithABacklog() {
        var backlog = VideoBacklog()
        #expect(backlog.admit(keyFrame: true, waiting: 10) == .show)
    }

    // Final review I3: if the requested key frame is lost, ask again instead of waiting for the phone's periodic one.
    @Test func aLongDropAsksAgainEveryThirtyFrames() {
        var backlog = VideoBacklog()
        #expect(backlog.admit(keyFrame: false, waiting: 4) == .dropAndRequestKeyFrame)
        for _ in 1..<VideoBacklog.reaskEvery { #expect(backlog.admit(keyFrame: false, waiting: 0) == .drop) }
        #expect(backlog.admit(keyFrame: false, waiting: 0) == .dropAndRequestKeyFrame)
        #expect(backlog.admit(keyFrame: true, waiting: 0) == .show)
    }

    @Test func resyncWaitsForAKeyFrame() {
        var backlog = VideoBacklog()
        backlog.resync()
        #expect(backlog.admit(keyFrame: false, waiting: 0) == .drop)
        #expect(backlog.admit(keyFrame: true, waiting: 0) == .show)
    }
}

@Suite struct AudioJitterTests {
    @Test func startsOnceFortyMillisecondsAreQueuedAndSkipsAheadOverAHundred() {
        var jitter = AudioJitter()
        #expect(jitter.decide(bufferedMillis: 0, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 20, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 30, chunkMillis: 10) == .schedule(startPlayback: true))
        #expect(jitter.playing)
        #expect(jitter.decide(bufferedMillis: 50, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 81, chunkMillis: 10) == .drop)
    }

    // Owner report: sound lagged; the queue sat near 100 ms instead of 40. Past 80 ms, drop back down to 40 ms.
    @Test func aFullQueueCatchesUpToTheTarget() {
        var jitter = AudioJitter()
        _ = jitter.decide(bufferedMillis: 40, chunkMillis: 10)
        #expect(jitter.decide(bufferedMillis: 70, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 81, chunkMillis: 10) == .drop)
        #expect(jitter.decide(bufferedMillis: 60, chunkMillis: 10) == .drop) // still catching up
        #expect(jitter.decide(bufferedMillis: 40, chunkMillis: 10) == .schedule(startPlayback: false)) // back at the target
        #expect(jitter.decide(bufferedMillis: 60, chunkMillis: 10) == .schedule(startPlayback: false))
    }

    @Test func runningDryBuildsTheCushionAgain() {
        var jitter = AudioJitter()
        _ = jitter.decide(bufferedMillis: 40, chunkMillis: 10)
        #expect(jitter.playing)
        #expect(jitter.decide(bufferedMillis: 0, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(!jitter.playing)
    }
}

@Suite struct MirrorPresentationTests {
    func state(_ phase: MirrorPhase, audio: Bool? = nil) -> MirrorState {
        var s = MirrorState()
        s.phase = phase
        s.audio = audio
        return s
    }

    @Test func statusLines() {
        #expect(MirrorPresentation.status(state(.idle)) == nil)
        #expect(MirrorPresentation.status(state(.starting(waitingForTap: false))) == "Starting…")
        #expect(MirrorPresentation.status(state(.starting(waitingForTap: true))) == "Tap Start on your phone to begin mirroring.")
        #expect(MirrorPresentation.status(state(.streaming(width: 1, height: 1, rotation: 0))) == nil)
        #expect(MirrorPresentation.status(state(.stopped(.declined))) == "Mirroring was declined on the phone.")
        #expect(MirrorPresentation.status(state(.stopped(.consentTimeout))) == "Mirroring didn't start")
        #expect(MirrorPresentation.status(state(.stopped(.noPermission))) == "Mirroring didn't start")
        #expect(MirrorPresentation.status(state(.stopped(.disconnected))) == "Phone not connected")
        #expect(MirrorPresentation.status(state(.stopped(.error))) == "Mirroring stopped")
        #expect(MirrorPresentation.status(state(.stopped(.user))) == "Mirroring stopped")
    }

    // Final review I5
    @Test func aStalledStreamSaysReconnecting() {
        let streaming = state(.streaming(width: 1, height: 1, rotation: 0))
        #expect(MirrorPresentation.status(streaming, stalled: true) == "Reconnecting…")
        #expect(MirrorPresentation.status(streaming, stalled: false) == nil)
        #expect(MirrorPresentation.status(state(.stopped(.user)), stalled: true) == "Mirroring stopped")
    }

    @Test func audioNoticeOnlyWhenAskedForButMissing() {
        let streaming = MirrorPhase.streaming(width: 1, height: 1, rotation: 0)
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: false), requested: true) == "Phone audio is off: allow the microphone permission for MacDroid on your phone")
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: true), requested: true) == nil)
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: false), requested: false) == nil)
    }

    @Test func latencyTones() {
        #expect(MirrorPresentation.latencyTone(60) == .good)
        #expect(MirrorPresentation.latencyTone(120) == .fair)
        #expect(MirrorPresentation.latencyTone(150) == .poor)
    }
}
