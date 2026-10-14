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

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func start(muted: Bool) {
        queue.async { [self] in
            self.muted = muted
            jitter = AudioJitter()
            bufferedFrames = 0
            if !muted { try? engine.start() }
        }
    }

    func stop() {
        queue.async { [self] in
            node.stop()
            engine.stop()
            jitter = AudioJitter()
            bufferedFrames = 0
        }
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
            node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                guard let self else { return }
                queue.async { self.bufferedFrames -= frames }
            }
            if startPlayback { node.play() }
        }
    }
}
