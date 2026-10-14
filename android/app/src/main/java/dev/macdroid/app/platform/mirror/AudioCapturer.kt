package dev.macdroid.app.platform.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/** What apps play, as 48 kHz stereo PCM in 10 ms chunks (spec §3.3). [output] runs on the audio thread. */
class AudioCapturer(private val projection: MediaProjection, private val output: (micros: Long, data: ByteArray, length: Int) -> Unit) {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    /** The caller has checked RECORD_AUDIO. False when capture isn't available. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        // As small as Android allows (at least two chunks): sound waiting here is delay the Mac can't take back (owner report).
        val minimum = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).setBufferSizeInBytes(maxOf(minimum, CHUNK * 2)).build()
        }.getOrNull() ?: return false
        this.record = record
        record.startRecording()
        running = true
        reader = thread(name = "mirror-audio", priority = Thread.MAX_PRIORITY) {
            val buffer = ByteArray(CHUNK)
            while (running) {
                val n = record.read(buffer, 0, CHUNK, AudioRecord.READ_BLOCKING)
                if (n > 0) output(System.nanoTime() / 1000, buffer, n)
            }
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        reader?.join(200)
        record?.release()
        record = null
    }

    companion object {
        /** 10 ms of 48 kHz stereo 16-bit. */
        const val CHUNK = 1_920
    }
}
