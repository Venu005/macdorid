import AVFoundation
import MacDroidCore

/// Plays the phone's PCM (48 kHz stereo s16le) with a 40 ms cushion and skip-ahead (spec §6.4). All work on one queue.
final class MirrorAudioPlayer: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false)!
    private let queue = DispatchQueue(label: "dev.macdroid.mirror.audio", qos: .userInteractive)
    private var jitter = AudioJitter() // queue
    private var bufferedFrames = 0 // queue
    private var muted = false // queue
    private var active = false // queue
    /// Bumped on every start, stop and restart, so completions from an older schedule can't touch `bufferedFrames`.
    private var epoch = 0 // queue
    private var configObserver: NSObjectProtocol?

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        // The output device changed (AirPods connected, display audio unplugged): the engine has stopped; rebuild and restart.
        configObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            guard let self else { return }
            queue.async { self.restartAfterConfigurationChange() }
        }
    }

    deinit {
        if let configObserver { NotificationCenter.default.removeObserver(configObserver) }
    }

    /// 0…1, applied to the main mixer.
    var volume: Float = 1 {
        didSet {
            let level = volume
            queue.async { [self] in engine.mainMixerNode.outputVolume = level }
        }
    }

    func start(muted: Bool) {
        queue.async { [self] in
            self.muted = muted
            active = true
            reset()
            if !muted { try? engine.start() }
        }
    }

    func stop() {
        queue.async { [self] in
            active = false
            node.stop()
            engine.stop()
            reset()
        }
    }

    private func reset() {
        epoch += 1
        jitter = AudioJitter()
        bufferedFrames = 0
    }

    private func restartAfterConfigurationChange() {
        guard active, !muted else { return }
        node.stop()
        engine.connect(node, to: engine.mainMixerNode, format: format)
        reset()
        try? engine.start()
    }

    func play(_ pcm: Data) {
        queue.async { [self] in
            guard !muted, engine.isRunning else { return }
            let frames = pcm.count / 4
            guard frames > 0 else { return }
            let decision = jitter.decide(bufferedMillis: Double(bufferedFrames) / 48, chunkMillis: Double(frames) / 48)
            guard case .schedule(let startPlayback) = decision else { return }
            if !jitter.playing && node.isPlaying { node.pause() } // ran dry: rebuild the cushion
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)) else { return }
            buffer.frameLength = AVAudioFrameCount(frames)
            pcm.withUnsafeBytes { raw in
                let samples = raw.bindMemory(to: Int16.self)
                let left = buffer.floatChannelData![0], right = buffer.floatChannelData![1]
                for i in 0..<frames {
                    left[i] = Float(Int16(littleEndian: samples[2 * i])) / 32_768
                    right[i] = Float(Int16(littleEndian: samples[2 * i + 1])) / 32_768
                }
            }
            bufferedFrames += frames
            let scheduledIn = epoch
            node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                guard let self else { return }
                queue.async { if self.epoch == scheduledIn { self.bufferedFrames -= frames } }
            }
            if startPlayback { node.play() }
        }
    }
}
