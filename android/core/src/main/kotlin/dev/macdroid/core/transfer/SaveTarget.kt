package dev.macdroid.core.transfer

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Where files from the Mac go. The app has MediaStore, SAF-folder and ask-each-time versions (Plan 2b). */
interface SaveTarget {
    /** Free bytes at the destination, or null if unknown. */
    fun freeBytes(): Long?

    /**
     * Creates the part file for [name]. May suspend while the user picks a place ("Ask where to save").
     * Null declines the file (no place chosen, or the destination is unavailable).
     */
    suspend fun createPart(name: String, mime: String, transferId: String): PartFile?
}

/** An incoming file until it's verified. Every call may block on I/O. */
interface PartFile {
    /** Survives restarts; the engine's `parts` function turns it back into a PartFile. */
    val handle: String
    fun length(): Long
    fun openRead(): InputStream
    fun openAppend(): OutputStream
    /** Makes the verified file visible under its final name; returns a URI string to open it. */
    fun finish(): String
    fun delete()
}

/** Opens files the user chose to send. */
fun interface SourceOpener {
    fun open(source: String): InputStream
}

/** Opens absolute paths (tests and the cross-platform run; the app adds content URIs). */
class FileSourceOpener : SourceOpener {
    override fun open(source: String): InputStream = File(source).inputStream()
}
