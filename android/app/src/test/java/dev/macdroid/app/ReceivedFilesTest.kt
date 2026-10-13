package dev.macdroid.app

import dev.macdroid.app.platform.ReceivedFiles
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Final review: only a missing file counts as deleted; losing access (e.g. after changing folders) doesn't.
class ReceivedFilesTest {
    @Test fun openableFileExists() = assertTrue(ReceivedFiles.exists { })
    @Test fun missingFileIsDeleted() = assertFalse(ReceivedFiles.exists { throw FileNotFoundException("gone") })
    @Test fun noAccessIsNotDeleted() = assertTrue(ReceivedFiles.exists { throw SecurityException("no grant") })
}
