package dev.macdroid.core.transfer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Saves into a plain folder: `<name>.macdroid-part` while receiving, then renamed to a free final name. */
class FolderSaveTarget(private val folder: File) : SaveTarget {
    private val lock = Any()

    override fun freeBytes(): Long? = folder.takeIf { it.exists() }?.usableSpace

    // Choosing the name and creating the part happen under one lock, so two offers can't claim the same name.
    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = synchronized(lock) {
        if (!folder.isDirectory && !folder.mkdirs()) return@synchronized null
        val finalName = FileNaming.unique(name) { taken(it) }
        val part = File(folder, finalName + FileNaming.PART_SUFFIX)
        if (part.createNewFile()) FolderPart(part) else null
    }

    private fun taken(name: String) = File(folder, name).exists() || File(folder, name + FileNaming.PART_SUFFIX).exists()

    companion object {
        fun reopen(handle: String): PartFile? = File(handle).takeIf { it.isFile }?.let(::FolderPart)
    }
}

private class FolderPart(private val file: File) : PartFile {
    override val handle: String = file.path
    override fun length() = file.length()
    override fun openRead() = file.inputStream()
    override fun openAppend() = FileOutputStream(file, true)

    override fun finish(): String {
        val folder = file.parentFile
        val wanted = file.name.removeSuffix(FileNaming.PART_SUFFIX)
        val name = FileNaming.unique(wanted) { File(folder, it).exists() }
        val target = File(folder, name)
        if (!file.renameTo(target)) throw IOException("couldn't rename ${file.name} to $name")
        return target.toURI().toString()
    }

    override fun delete() {
        file.delete()
    }
}
