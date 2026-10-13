package dev.macdroid.app.platform

/** Where files from the Mac go: MediaStore Downloads, a SAF folder the user chose, or a prompt for each file. */
enum class SaveMode {
    Default, Folder, Ask;

    companion object {
        /** A chosen folder whose grant is gone falls back to asking, and flags the problem for a banner (spec §5). */
        fun choose(askEachTime: Boolean, treeUri: String?, treeWritable: Boolean): SaveChoice = when {
            askEachTime -> SaveChoice(Ask, folderProblem = false)
            treeUri != null && treeWritable -> SaveChoice(Folder, folderProblem = false)
            treeUri != null -> SaveChoice(Ask, folderProblem = true)
            else -> SaveChoice(Default, folderProblem = false)
        }
    }
}

data class SaveChoice(val mode: SaveMode, val folderProblem: Boolean)
