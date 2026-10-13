package dev.macdroid.app

import dev.macdroid.app.ui.presentation.FolderLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLabelTest {
    @Test fun defaultFolder() = assertEquals("Download/MacDroid", FolderLabel.of(null))
    @Test fun externalStorageTree() =
        assertEquals("Documents/MacDroid", FolderLabel.of("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMacDroid"))
    @Test fun storageRoot() = assertEquals("Internal storage", FolderLabel.of("content://com.android.externalstorage.documents/tree/primary%3A"))
    @Test fun otherProvider() = assertEquals("Work", FolderLabel.of("content://com.example.docs/tree/Work"))
}
