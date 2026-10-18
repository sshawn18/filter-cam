package com.filtercam.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object PhotoSaver {
    fun save(context: Context, bitmap: Bitmap, quality: Int = 95): Uri {
        val name = "FilterCam_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilterCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
        try {
            resolver.openOutputStream(uri).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), checkNotNull(out))) { "JPEG compress failed" }
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
