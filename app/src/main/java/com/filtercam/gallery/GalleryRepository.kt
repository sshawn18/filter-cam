package com.filtercam.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class Photo(val id: Long, val uri: Uri, val isVideo: Boolean = false, val durationMs: Long = 0, val added: Long = 0) {
    val key: String get() = uri.toString()
}

class GalleryRepository(private val context: Context) {
    fun list(): List<Photo> {
        val out = ArrayList<Photo>()
        val imgBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                imgBase, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/FilterCam%"), null,
            )?.use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); out += Photo(id, ContentUris.withAppendedId(imgBase, id), false, 0, c.getLong(1)) }
            }
        }
        val vidBase = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                vidBase, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/FilterCam%"), null,
            )?.use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); out += Photo(id, ContentUris.withAppendedId(vidBase, id), true, c.getLong(2), c.getLong(1)) }
            }
        }
        return out.sortedWith(compareByDescending<Photo> { it.added }.thenByDescending { it.id })
    }

    fun thumbnail(uri: Uri, w: Int, h: Int): ImageBitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(w, h), null).asImageBitmap() }.getOrNull()

    fun delete(photo: Photo): Boolean =
        runCatching { context.contentResolver.delete(photo.uri, null, null) > 0 }.getOrDefault(false)
}
