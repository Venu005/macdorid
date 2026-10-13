package dev.macdroid.core.transfer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferStoreTest {
    private val dir: File = Files.createTempDirectory("store").toFile()

    @Test fun jsonFileStoreRoundTrips() {
        val store = JsonFileTransferStore(File(dir, "transfers.json"))
        assertEquals(StoredTransfers(), store.load())
        val record = TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 1, mime = "x", phase = Phase.Failed,
            failure = Failure.ChecksumMismatch, part = "/p", createdAt = 1, updatedAt = 2)
        store.save(StoredTransfers(active = listOf(record.copy(phase = Phase.Paused, failure = null)), history = listOf(record)))
        assertEquals(StoredTransfers(listOf(record.copy(phase = Phase.Paused, failure = null)), listOf(record)), store.load())
    }

    @Test fun corruptFileLoadsEmpty() {
        File(dir, "bad.json").writeText("{nope")
        assertEquals(StoredTransfers(), JsonFileTransferStore(File(dir, "bad.json")).load())
    }

    // Review Focus #4
    @Test fun folderTargetNeverReusesAName() = kotlinx.coroutines.runBlocking {
        val target = FolderSaveTarget(dir)
        File(dir, "a.txt").writeText("old")
        val first = target.createPart("a.txt", "text/plain", "t1")!!
        val second = target.createPart("a.txt", "text/plain", "t2")!!
        assertEquals(setOf("a (1).txt.macdroid-part", "a (2).txt.macdroid-part"), setOf(File(first.handle).name, File(second.handle).name))
        first.openAppend().use { it.write(byteArrayOf(1)) }
        File(dir, "a (1).txt").writeText("someone else")
        val saved = File(java.net.URI(first.finish()))
        assertEquals("a (1) (1).txt", saved.name) // the final name was taken meanwhile: pick another, never overwrite
    }
}
