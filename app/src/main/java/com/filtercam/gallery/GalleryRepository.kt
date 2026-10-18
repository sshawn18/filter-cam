package com.filtercam.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class Photo(val id: Long, val uri: Uri)

class GalleryRepository(private val context: Context) {
    fun list(): List<Photo> {
        val out = ArrayList<Photo>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                base, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/FilterCam%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); out += Photo(id, ContentUris.withAppendedId(base, id)) }
            }
        }
        return out
    }

    fun thumbnail(uri: Uri, w: Int, h: Int): ImageBitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(w, h), null).asImageBitmap() }.getOrNull()

    fun delete(photo: Photo): Boolean =
        runCatching { context.contentResolver.delete(photo.uri, null, null) > 0 }.getOrDefault(false)
}
