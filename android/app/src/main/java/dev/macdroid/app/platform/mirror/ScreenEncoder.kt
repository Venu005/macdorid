package dev.macdroid.app.platform.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface
import kotlin.concurrent.thread

/**
 * Screen → VirtualDisplay → the hardware H.264 encoder's input surface, with no copies (spec §3.2).
 * [output] runs on the encoder thread: (codec config?, key frame?, capture µs, bytes).
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val output: (config: Boolean, keyFrame: Boolean, micros: Long, data: ByteArray) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var drain: Thread? = null
    @Volatile private var running = false

    fun start(width: Int, height: Int) {
        val (codec, surface) = newCodec(width, height)
        display = projection.createVirtualDisplay("MacDroid mirror", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        begin(codec)
    }

    /** Rotation or a display change: a new encoder at the new size (spec §3.2). */
    fun resize(width: Int, height: Int) {
        stopCodec()
        val (codec, surface) = newCodec(width, height)
        display?.resize(width, height, dpi)
        display?.surface = surface
        begin(codec)
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        stopCodec()
        display?.release()
        display = null
    }

    private fun newCodec(width: Int, height: Int): Pair<MediaCodec, Surface> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format(width, height, high = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset() // High profile not offered: let the encoder choose
            codec.configure(format(width, height, high = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec to codec.createInputSurface()
    }

    private fun format(width: Int, height: Int, high: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // a still screen sends almost nothing
        setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        if (high) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
        }
    }

    private fun begin(codec: MediaCodec) {
        this.codec = codec
        codec.start()
        running = true
        drain = thread(name = "mirror-encoder", priority = Thread.MAX_PRIORITY) { drainLoop(codec) }
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                output(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs, bytes)
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
        }
    }

    private fun stopCodec() {
        running = false
        drain?.join(500)
        drain = null
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
    }
}
