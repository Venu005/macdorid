package dev.macdroid.app.platform.photos

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.photos.PhotoPageResult
import dev.macdroid.core.photos.PhotoQueries
import dev.macdroid.core.photos.PhotoSource
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKind
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.transfer.OutgoingFile
import java.io.ByteArrayOutputStream

/** The phone's photos and videos through MediaStore (spec §4). Blocking: call from a worker thread. */
class PhotoLibrary(private val context: Context) : PhotoSource {
    private val resolver = context.contentResolver
    private val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun access(): PhotoAccess {
        val api = Build.VERSION.SDK_INT
        return PhotoQueries.access(
            api = api,
            images = api >= 33 && Permissions.granted(context, Manifest.permission.READ_MEDIA_IMAGES),
            video = api >= 33 && Permissions.granted(context, Manifest.permission.READ_MEDIA_VIDEO),
            selectedOnly = api >= 34 && Permissions.granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacyStorage = api < 33 && Permissions.granted(context, Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    override fun albums(): List<PhotoAlbumBody> {
        data class Bucket(val name: String, var count: Int, val cover: String)
        val buckets = LinkedHashMap<String, Bucket>() // most recently added first
        var total = 0
        var cover: String? = null
        val (selection, args) = PhotoQueries.selection("all", PhotoKindFilter.All, null)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        query(projection, selection, args, limit = null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0).toString()
                total++
                if (cover == null) cover = id
                val bucket = c.getString(1) ?: continue
                buckets.getOrPut(bucket) { Bucket(c.getString(2) ?: "Other", 0, id) }.count++
            }
        }
        return listOf(PhotoAlbumBody("all", "All Photos", total, cover)) +
            buckets.map { (id, b) -> PhotoAlbumBody(id, b.name, b.count, b.cover) }
    }

    override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult {
        val (selection, args) = PhotoQueries.selection(albumId, kind, cursor)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
        )
        val items = mutableListOf<PhotoItemBody>()
        var lastAdded = 0L
        var lastId = 0L
        query(projection, selection, args, limit)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                lastAdded = c.getLong(4)
                lastId = id
                items += PhotoItemBody(
                    id = id.toString(),
                    kind = if (video) PhotoKind.Video else PhotoKind.Photo,
                    name = c.getString(2) ?: "$id",
                    mime = c.getString(3) ?: if (video) "video/mp4" else "image/jpeg",
                    takenAt = lastAdded * 1000,
                    size = c.getLong(5),
                    width = c.getInt(6),
                    height = c.getInt(7),
                    durationMs = if (video) c.getLong(8) else null,
                )
            }
        }
        val next = if (items.size == limit) PhotoQueries.encodeCursor(lastAdded, lastId) else null
        return PhotoPageResult(items, next)
    }

    override fun thumbnail(id: String): ByteArray? {
        val item = lookup(id) ?: return null
        return runCatching {
            val bitmap = resolver.loadThumbnail(item.uri, Size(THUMB_PX, THUMB_PX), null)
            val scaled = fit(bitmap)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    override fun original(id: String): OutgoingFile? {
        val item = lookup(id) ?: return null
        return OutgoingFile(item.uri.toString(), item.name, item.size, item.mime)
    }

    private data class Item(val uri: Uri, val name: String, val size: Long, val mime: String)

    /** Null when the id is gone or not visible under the current access. */
    private fun lookup(id: String): Item? {
        if (!PhotoQueries.isValidId(id)) return null
        val projection = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        return query(projection, "${MediaStore.MediaColumns._ID} = ? AND media_type IN (1,3)", arrayOf(id), limit = 1)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val video = c.getInt(0) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Item(ContentUris.withAppendedId(base, id.toLong()), c.getString(1) ?: id, c.getLong(2),
                c.getString(3) ?: if (video) "video/mp4" else "image/jpeg")
        }
    }

    private fun query(projection: Array<String>, selection: String, args: Array<String>, limit: Int?) = runCatching {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, PhotoQueries.SORT)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        resolver.query(files, projection, bundle, null)
    }.getOrNull() // SecurityException without access: nothing to show

    private fun fit(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= THUMB_PX) return bitmap
        val scale = THUMB_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val THUMB_PX = 256
        const val THUMB_QUALITY = 80
    }
}
