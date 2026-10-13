package dev.macdroid.core.transfer

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StoredTransfers(val active: List<TransferRecord> = emptyList(), val history: List<TransferRecord> = emptyList())

/** Persists transfer state across process death (spec §4.1). */
interface TransferStore {
    fun load(): StoredTransfers
    fun save(state: StoredTransfers)
}

class InMemoryTransferStore(@Volatile var state: StoredTransfers = StoredTransfers()) : TransferStore {
    override fun load() = state
    override fun save(state: StoredTransfers) { this.state = state }
}

/** JSON in one file, replaced atomically. A missing or unreadable file loads as empty. */
class JsonFileTransferStore(private val file: File) : TransferStore {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun load(): StoredTransfers =
        runCatching { json.decodeFromString(StoredTransfers.serializer(), file.readText()) }.getOrDefault(StoredTransfers())

    override fun save(state: StoredTransfers) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(StoredTransfers.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
