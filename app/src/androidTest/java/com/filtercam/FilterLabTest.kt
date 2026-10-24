package com.filtercam

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.platform.app.InstrumentationRegistry
import com.filtercam.filter.FilterParams
import com.filtercam.gl.PhotoProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tuning lab (not a regression test): applies every filter JSON in <app files>/lab/filters to every
 * image in <app files>/lab/in and writes JPEGs to <app files>/lab/out. Run with:
 *   adb shell am instrument -w -e class com.filtercam.FilterLabTest com.filtercam.test/androidx.test.runner.AndroidJUnitRunner
 * <app files> = /sdcard/Android/data/com.filtercam/files
 */
class FilterLabTest {
    @Test fun renderAll() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.getExternalFilesDir(null), "lab")
        val inputs = File(root, "in").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }
        val filters = File(root, "filters").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .map { FilterParams.parse(it.readText()).getOrThrow() }
        assertTrue("put images in ${root}/in and filters in ${root}/filters", inputs.isNotEmpty() && filters.isNotEmpty())
        val out = File(root, "out").apply { deleteRecursively(); mkdirs() }
        for (inp in inputs) {
            val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(inp)) { dec, info, _ ->
                val scale = 1400f / maxOf(info.size.width, info.size.height)
                if (scale < 1f) dec.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            for (f in filters) {
                val res = PhotoProcessor().process(src, f, 1f)
                File(out, "${inp.nameWithoutExtension}__${f.name.replace(' ', '_')}.jpg").outputStream().use {
                    res.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                res.recycle()
            }
            src.recycle()
        }
    }
}
