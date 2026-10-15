package dev.macdroid.core.mirror

import java.nio.ByteBuffer

/** One binary message on the media WebSocket (spec §5.2): a 12-byte big-endian header, then the payload. */
object MirrorFrame {
    const val HEADER_SIZE = 12
    const val MAX_SIZE = 4 * 1024 * 1024
    const val TYPE_CONFIG = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO = 3
    const val FLAG_KEY = 1

    fun header(type: Int, keyFrame: Boolean, captureMicros: Long): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(type.toByte()).put((if (keyFrame) FLAG_KEY else 0).toByte()).putShort(0).putLong(captureMicros).array()

    fun encode(type: Int, keyFrame: Boolean, captureMicros: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER_SIZE + length)
        header(type, keyFrame, captureMicros).copyInto(out)
        payload.copyInto(out, HEADER_SIZE, offset, offset + length)
        return out
    }
}
