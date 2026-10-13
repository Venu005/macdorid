package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Picks the save target from the Files settings, flags an unreachable chosen folder, and reopens part files by handle. */
class SaveTargets(private val context: Context, private val prefs: FilesPrefs, private val prompter: SavePrompter) {
    private val _folderProblem = MutableStateFlow(false)
    val folderProblem: StateFlow<Boolean> = _folderProblem.asStateFlow()

    fun current(): SaveTarget {
        val tree = prefs.treeUri
        val writable = tree != null &&
            context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission } &&
            treeStillExists(Uri.parse(tree))
        val choice = SaveMode.choose(prefs.askEachTime, tree, writable)
        _folderProblem.value = choice.folderProblem
        return when (choice.mode) {
            SaveMode.Default -> MediaStoreSaveTarget(context)
            SaveMode.Folder -> TreeSaveTarget(context, Uri.parse(tree))
            SaveMode.Ask -> AskSaveTarget(context, prompter)
        }
    }

    /** The grant outlives a deleted folder, so check the folder itself too. */
    private fun treeStillExists(tree: Uri): Boolean = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    fun reopen(handle: String): PartFile? = when {
        handle.startsWith(MediaStorePart.PREFIX) -> MediaStorePart.reopen(context, handle)
        handle.startsWith(TreePart.PREFIX) -> TreePart.reopen(context, handle)
        handle.startsWith(DocumentPart.PREFIX) -> DocumentPart.reopen(context, handle)
        else -> null
    }
}
