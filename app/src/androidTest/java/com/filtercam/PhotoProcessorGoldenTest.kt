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

/**
 * Golden test for the Jade filter.
 *
 * The golden `app/src/androidTest/assets/jade_golden.png` is generated from the real
 * `assets/filters/jade.json`. Re-record it after ANY change to jade.json or the shader
 * (Shaders.kt) with:
 *
 *     .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.record=true"
 *     adb pull /sdcard/Android/data/com.filtercam/files/jade_golden.png app\src\androidTest\assets\jade_golden.png
 *
 * Until the golden is recorded, the comparison is skipped, not passed.
 *
 * A sanity check runs in both modes: the output must differ noticeably from the input, so a
 * pass-through shader can never be recorded as the golden or pass the comparison.
 */
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

    /** Mean absolute RGB difference per channel value. Both bitmaps must be the same size. */
    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        var sum = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
            sum += Math.abs(Color.red(pa) - Color.red(pb)) + Math.abs(Color.green(pa) - Color.green(pb)) + Math.abs(Color.blue(pa) - Color.blue(pb))
        }
        return sum / (a.width * a.height * 3.0)
    }

    @Test fun jadeMatchesGolden() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val json = inst.targetContext.assets.open("filters/jade.json").bufferedReader().use { it.readText() }
        val input = testInput()
        val out = PhotoProcessor().process(input, FilterParams.fromJson(json), 1f)

        // Runs in both modes, before recording or comparing.
        val d = meanAbsDiff(out, input)
        assertTrue("shader output is (nearly) identical to the input: diff=$d", d > 5.0)

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
        val meanDiff = meanAbsDiff(out, golden)
        assertTrue("mean abs diff $meanDiff > 2.0", meanDiff <= 2.0)
    }
}
