package com.filtercam.frame

/** Photo/video framing. [aspect] is width/height as shown in the portrait viewfinder. */
enum class Frame(val label: String, private val fixedAspect: Float?) {
    R9_16("9:16", 9f / 16f),
    R3_4("3:4", 3f / 4f),
    R1_1("1:1", 1f),
    FULL("Full", null),
    CINEMA("2.39:1", 2.39f),
    IMAX("1.90:1", 1.90f);

    fun aspect(screenAspect: Float): Float = fixedAspect ?: screenAspect
}

/** Video output size for a frame aspect (w/h): even dimensions, 1080 wide (portrait/square) or 1920 wide (wide frames). */
fun videoSize(aspect: Float): Pair<Int, Int> {
    val w = if (aspect <= 1f) 1080 else 1920
    val h = (w / aspect).toInt()
    return (w and 1.inv()) to (h and 1.inv())
}

data class CropRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Largest centered rectangle of [aspect] (w/h) inside a [sw] x [sh] image. */
fun cropRect(sw: Int, sh: Int, aspect: Float): CropRect {
    val srcAspect = sw.toFloat() / sh
    return if (srcAspect > aspect) {
        val w = (sh * aspect).toInt().coerceIn(1, sw)
        CropRect((sw - w) / 2, 0, w, sh)
    } else {
        val h = (sw / aspect).toInt().coerceIn(1, sh)
        CropRect(0, (sh - h) / 2, sw, h)
    }
}
