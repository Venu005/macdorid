package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
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
        val writable = tree != null && context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }
        val choice = SaveMode.choose(prefs.askEachTime, tree, writable)
        _folderProblem.value = choice.folderProblem
        return when (choice.mode) {
            SaveMode.Default -> MediaStoreSaveTarget(context)
            SaveMode.Folder -> TreeSaveTarget(context, Uri.parse(tree))
            SaveMode.Ask -> AskSaveTarget(context, prompter)
        }
    }

    fun reopen(handle: String): PartFile? = when {
        handle.startsWith(MediaStorePart.PREFIX) -> MediaStorePart.reopen(context, handle)
        handle.startsWith(TreePart.PREFIX) -> TreePart.reopen(context, handle)
        handle.startsWith(DocumentPart.PREFIX) -> DocumentPart.reopen(context, handle)
        else -> null
    }
}
