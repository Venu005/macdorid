package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoKindFilter

/**
 * Pure rules for the phone's photo library (spec §3–4). Ordering, paging and grouping use DATE_ADDED then _ID
 * (plan ordering ruling): DATE_TAKEN can be null, and MediaProvider rejects SQL functions in selections.
 */
object PhotoQueries {
    const val MAX_PAGE = 200
    const val MAX_THUMBS = 60
    const val SORT = "date_added DESC, _id DESC"
    private const val IMAGE = 1 // MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private const val VIDEO = 3 // MEDIA_TYPE_VIDEO

    fun pageLimit(requested: Int): Int = requested.coerceIn(1, MAX_PAGE)

    fun isValidId(id: String): Boolean = id.length in 1..20 && id.all(Char::isDigit)

    fun thumbIds(ids: List<String>): List<String> = ids.filter(::isValidId).distinct().take(MAX_THUMBS)

    fun encodeCursor(dateAddedSeconds: Long, id: Long): String = "$dateAddedSeconds:$id"

    fun decodeCursor(cursor: String?): Pair<Long, Long>? {
        val parts = cursor?.split(':') ?: return null
        if (parts.size != 2) return null
        val added = parts[0].toLongOrNull() ?: return null
        val id = parts[1].toLongOrNull() ?: return null
        return added to id
    }

    /** SQL selection and its arguments for one page (newest first, strictly older than [cursor]). */
    fun selection(albumId: String, kind: PhotoKindFilter, cursor: String?): Pair<String, Array<String>> {
        val types = when (kind) {
            PhotoKindFilter.All -> "$IMAGE,$VIDEO"
            PhotoKindFilter.Photos -> "$IMAGE"
            PhotoKindFilter.Videos -> "$VIDEO"
        }
        val clauses = mutableListOf("media_type IN ($types)")
        val args = mutableListOf<String>()
        if (albumId != "all") {
            clauses += "bucket_id = ?"
            args += albumId
        }
        decodeCursor(cursor)?.let { (added, id) ->
            clauses += "(date_added < ? OR (date_added = ? AND _id < ?))"
            args += listOf(added.toString(), added.toString(), id.toString())
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /** spec §2: what the user granted, by Android version. */
    fun access(api: Int, images: Boolean, video: Boolean, selectedOnly: Boolean, legacyStorage: Boolean): PhotoAccess = when {
        api >= 33 && (images || video) -> PhotoAccess.Full
        api >= 34 && selectedOnly -> PhotoAccess.Partial
        api < 33 && legacyStorage -> PhotoAccess.Full
        else -> PhotoAccess.None
    }
}
