package dev.macdroid.core.transfer

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** A local read or write failed (not the network), so the transfer fails instead of pausing. */
class LocalIoException(cause: Throwable) : IOException(cause.message, cause)

/** The Mac answered with an unexpected status. [uploadOffset] is its `Upload-Offset`, when it sent one. */
class TransferHttpException(val code: Int, val uploadOffset: Long? = null) : IOException("HTTP $code")

/** Moves file bytes over HTTPS (spec §2.2). The engine depends on this interface so it can be tested without a network. */
interface FileTransport {
    /** HEAD: how many bytes of [id] the Mac already has. */
    suspend fun uploadOffset(id: String): Long

    /**
     * PUT bytes `[offset, size)` read from [input], which is already positioned at [offset]. [digest] must already cover
     * the bytes before [offset]; it's updated with every byte sent. Returns the Mac's offset afterwards.
     */
    suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long

    /**
     * GET from [offset], appending to [output]. [digest] must already cover the bytes before [offset].
     * Returns the bytes now present (offset + received).
     */
    suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long
}

/** The phone always makes the request: PUT for phone → Mac, GET for Mac → phone. Cancelling the coroutine cancels the call. */
class TransferClient(private val endpoint: () -> Endpoint?) : FileTransport {
    data class Endpoint(val http: OkHttpClient, val baseUrl: HttpUrl)

    companion object {
        const val BUFFER_SIZE = 1 shl 20

        /** No call timeout (files can take minutes); 60 s per read or write, so a dead connection is still noticed. */
        fun forFiles(pinned: OkHttpClient): OkHttpClient = pinned.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    override suspend fun uploadOffset(id: String): Long {
        val (http, request) = request(id) { head() }
        return execute(http, request) { response ->
            if (!response.isSuccessful) throw TransferHttpException(response.code)
            response.header("Upload-Offset")?.toLongOrNull() ?: throw TransferHttpException(response.code)
        }
    }

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = size - offset
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(BUFFER_SIZE)
                var sent = offset
                while (sent < size) {
                    val count = try {
                        input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                    } catch (e: IOException) {
                        throw LocalIoException(e)
                    }
                    if (count < 0) throw LocalIoException(EOFException("source ended at $sent of $size bytes"))
                    sink.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    sent += count
                    onProgress(sent)
                }
            }
        }
        val (http, request) = request(id) {
            put(body)
            if (offset > 0) header("Content-Range", "bytes $offset-${size - 1}/$size")
        }
        return execute(http, request) { response ->
            val reported = response.header("Upload-Offset")?.toLongOrNull()
            if (!response.isSuccessful) throw TransferHttpException(response.code, reported)
            reported ?: size
        }
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        val (http, request) = request(id) {
            get()
            if (offset > 0) header("Range", "bytes=$offset-")
        }
        return execute(http, request) { response ->
            if (!response.isSuccessful) throw TransferHttpException(response.code)
            val source = response.body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var have = offset
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (have + count > size) throw TransferHttpException(response.code) // more than was offered
                try {
                    output.write(buffer, 0, count)
                } catch (e: IOException) {
                    throw LocalIoException(e)
                }
                digest.update(buffer, 0, count)
                have += count
                onProgress(have)
            }
            try {
                output.flush()
            } catch (e: IOException) {
                throw LocalIoException(e)
            }
            have
        }
    }

    private fun request(id: String, configure: Request.Builder.() -> Unit): Pair<OkHttpClient, Request> {
        val endpoint = endpoint() ?: throw IOException("not paired with a Mac")
        val url = endpoint.baseUrl.newBuilder().addPathSegments("v1/files").addPathSegment(id).build()
        return endpoint.http to Request.Builder().url(url).apply(configure).build()
    }

    /** Runs the blocking call on IO; cancelling the coroutine cancels the call, which unblocks any read or write. */
    private suspend fun <T> execute(http: OkHttpClient, request: Request, handle: (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(handle) }
        } catch (e: IOException) {
            ensureActive() // a cancelled call fails with an IOException; report it as the cancellation it is
            throw e
        } finally {
            canceller.cancel()
        }
    }
}

/** Lowercase hex of the digest (consumes it). */
internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
