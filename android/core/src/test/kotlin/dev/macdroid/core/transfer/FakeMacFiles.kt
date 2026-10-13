package dev.macdroid.core.transfer

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** An in-memory Mac for engine tests: serves files for downloads and keeps uploads, with injectable failures. */
class FakeMacFiles : FileTransport {
    val served = ConcurrentHashMap<String, ByteArray>()
    val uploaded = ConcurrentHashMap<String, ByteArrayOutputStream>()
    val downloadOffsets = CopyOnWriteArrayList<Long>()
    val uploadOffsets = CopyOnWriteArrayList<Long>()
    /** The next byte transfer throws a network IOException after this many bytes. */
    @Volatile var failNextAfter: Int? = null
    /** Downloads wait for this before sending bytes. */
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var headStatus: Int? = null
    /** The next download blocks this thread (ignoring coroutine cancellation) until counted down, like a write stuck in the kernel. */
    @Volatile var blockNextDownload: java.util.concurrent.CountDownLatch? = null

    override suspend fun uploadOffset(id: String): Long {
        headStatus?.let { throw TransferHttpException(it) }
        return uploaded.getOrPut(id) { ByteArrayOutputStream() }.size().toLong()
    }

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        uploadOffsets += offset
        val sink = uploaded.getOrPut(id) { ByteArrayOutputStream() }
        if (sink.size().toLong() != offset) throw TransferHttpException(409, sink.size().toLong())
        val limit = failNextAfter.also { failNextAfter = null }
        val buffer = ByteArray(64 * 1024)
        var have = offset
        while (have < size) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - have).toInt())
            if (count < 0) throw LocalIoException(EOFException())
            if (limit != null && have - offset + count > limit) {
                sink.write(buffer, 0, (limit - (have - offset)).toInt())
                throw IOException("connection dropped")
            }
            sink.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            have += count
            onProgress(have)
        }
        return have
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        downloadOffsets += offset
        gate?.await()
        blockNextDownload?.let { latch -> blockNextDownload = null; latch.await() }
        val bytes = served[id] ?: throw TransferHttpException(404)
        val limit = failNextAfter.also { failNextAfter = null }
        var have = offset
        while (have < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - have.toInt())
            if (limit != null && have - offset + count > limit) {
                val partial = (limit - (have - offset)).toInt()
                output.write(bytes, have.toInt(), partial)
                throw IOException("connection dropped")
            }
            try {
                output.write(bytes, have.toInt(), count)
            } catch (e: IOException) {
                throw LocalIoException(e)
            }
            digest.update(bytes, have.toInt(), count)
            have += count
            onProgress(have)
        }
        output.flush()
        return have
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").apply { update(bytes) }.hex()

fun testBytes(count: Int, seed: Int = 7) = ByteArray(count) { (it * 31 + seed).toByte() }
