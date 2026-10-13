package dev.macdroid.app.platform

import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord

/**
 * What to tidy up after sends finish. Only completed sends are cleaned up: a failed or cancelled one keeps its source,
 * so Retry still works.
 * - Cache copies are deleted.
 * - Persistable read grants are released. Android caps them per app and prunes the oldest first, which would
 *   otherwise cost the chosen save folder its grant.
 */
object FinishedSends {
    data class Cleanup(val copiesToDelete: List<String>, val grantsToRelease: List<String>)

    fun cleanup(history: List<TransferRecord>, cacheRoot: String): Cleanup {
        val sources = history.filter { it.direction == Direction.ToMac && it.phase == Phase.Done }.mapNotNull { it.source }
        return Cleanup(
            copiesToDelete = sources.filter { it.startsWith(cacheRoot) },
            grantsToRelease = sources.filter { it.startsWith("content:") },
        )
    }
}
