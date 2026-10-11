package com.filtercam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.filtercam.filter.FilterParams
import com.filtercam.gl.PhotoProcessor
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException

class PhotoProcessorGoldenTest {
    private fun testInput(): Bitmap {
        val b = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        for (y in 0 until 400) { // foliage gradient, dark to mid green
            p.color = Color.rgb(20 + y / 20, 60 + y / 6, 30 + y / 20)
            c.drawLine(0f, y.toFloat(), 300f, y.toFloat(), p)
        }
        p.color = Color.rgb(224, 172, 140); c.drawRect(80f, 80f, 220f, 220f, p) // skin
        p.color = Color.rgb(170, 70, 70); c.drawRect(110f, 240f, 190f, 270f, p) // lips
        p.color = Color.rgb(250, 250, 240); c.drawRect(10f, 10f, 50f, 50f, p) // highlight
        return b
    }

    @Test fun jadeMatchesGolden() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val json = inst.targetContext.assets.open("filters/jade.json").bufferedReader().use { it.readText() }
        val out = PhotoProcessor().process(testInput(), FilterParams.fromJson(json), 1f)

        if (InstrumentationRegistry.getArguments().getString("record") == "true") {
            val f = File(inst.targetContext.getExternalFilesDir(null), "jade_golden.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val goldenExists = try {
            inst.context.assets.open("jade_golden.png").close()
            true
        } catch (e: IOException) {
            false
        }
        Assume.assumeTrue("jade_golden.png not recorded yet (record with -e record true, see plan Task 8)", goldenExists)
        val golden = BitmapFactory.decodeStream(inst.context.assets.open("jade_golden.png"))
        var sum = 0L
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val a = out.getPixel(x, y); val g = golden.getPixel(x, y)
            sum += Math.abs(Color.red(a) - Color.red(g)) + Math.abs(Color.green(a) - Color.green(g)) + Math.abs(Color.blue(a) - Color.blue(g))
        }
        val meanAbsDiff = sum / (out.width * out.height * 3.0)
        assertTrue("mean abs diff $meanAbsDiff > 2.0", meanAbsDiff <= 2.0)
    }
}
