import AVFoundation
import MacDroidCore

/// Shows the phone's video as it arrives (spec §6.1–6.2): hardware decode, display immediately, and drop to the next key frame
/// instead of queueing. `deliver` is called on the server's thread; decoding work runs on one serial queue.
final class MirrorRenderer: MirrorFrameSink, @unchecked Sendable {
    let layer = AVSampleBufferDisplayLayer()
    private let renderer: AVSampleBufferVideoRenderer
    private let queue = DispatchQueue(label: "dev.macdroid.mirror.video", qos: .userInteractive)
    private let lock = NSLock()
    private var waiting = 0 // lock
    private var format: CMVideoFormatDescription? // queue
    private var backlog = VideoBacklog() // queue
    private let meter: LatencyMeter
    private let audio: MirrorAudioPlayer
    private let requestKeyFrame: @Sendable () -> Void

    @MainActor init(meter: LatencyMeter, audio: MirrorAudioPlayer, requestKeyFrame: @escaping @Sendable () -> Void) {
        self.meter = meter
        self.audio = audio
        self.requestKeyFrame = requestKeyFrame
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor.black
        renderer = layer.sampleBufferRenderer
    }

    func deliver(_ frame: MirrorFrame) {
        switch frame.type {
        case .audio:
            audio.play(frame.payload)
        case .config, .video:
            lock.withLock { waiting += 1 }
            queue.async { [self] in
                let behind = lock.withLock {
                    waiting -= 1
                    return waiting
                }
                show(frame, waiting: behind)
            }
        }
    }

    /// A new session: forget the old format and picture.
    func reset() {
        queue.async { [self] in
            format = nil
            backlog = VideoBacklog()
            renderer.flush(removingDisplayedImage: true, completionHandler: nil)
        }
    }

    private func show(_ frame: MirrorFrame, waiting: Int) {
        if frame.type == .config {
            guard let sets = H264.parameterSets(in: frame.payload), let next = try? H264.formatDescription(sps: sets.sps, pps: sets.pps) else { return }
            format = next
            backlog.resync() // the new format starts at its key frame (Review Focus #3)
            return
        }
        guard let format else { return }
        switch backlog.admit(keyFrame: frame.keyFrame, waiting: waiting) {
        case .drop:
            return
        case .dropAndRequestKeyFrame:
            requestKeyFrame()
            return
        case .show:
            break
        }
        if renderer.status == .failed {
            renderer.flush()
            backlog.resync()
            requestKeyFrame()
            return
        }
        guard let sample = try? H264.sampleBuffer(accessUnit: frame.payload, format: format) else { return }
        renderer.enqueue(sample)
        meter.record(captureMicros: frame.captureMicros, displayedMicros: MonotonicClock.micros())
    }
}
