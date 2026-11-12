package com.meridian11.books.bentobar.bar

import androidx.compose.ui.graphics.Color

/**
 * The status bar's colours, worked out from the pixels of its own window around the clock. No
 * Android types, so the cases the devices showed (a see-through bar, a black one, a bar caught
 * while it fades) are unit-tested.
 */
object BarPixels {
    /** Fewer opaque pixels than this is no reading: an empty box, or a bar caught while it fades in. */
    private const val MIN_OPAQUE = 12
    private const val OPAQUE_ALPHA = 230
    /** A translucent glyph's alpha at least (a light bar's dark icons are black at about 60%, alpha 153). */
    private const val INK_ALPHA = 96
    /** Below this alpha a pixel is the bar's clear background. */
    private const val CLEAR_ALPHA = 16
    /** Summed channel difference from the background below which nothing in the box counts as text. */
    private const val MIN_TEXT_DISTANCE = 60

    /**
     * The text colour and, when the bar is opaque there, the bar's own colour, from the ARGB pixels
     * [px] of a box [width] wide (rows top to bottom). Null when the box holds nothing readable.
     *
     * A see-through bar has only its glyphs opaque, so they are the text colour. On an opaque bar
     * the background is the commonest colour along the box's edge, where a text box has no ink
     * (the commonest colour anywhere could be the solid inside of the digits when the background is
     * a gradient), and the text is the colour of the glyphs' cores: their edges blend into the
     * background, and averaging them in drew the strip a shade grayer than the system's clock
     * (#EFEFEF beside white on a black bar).
     */
    fun colors(px: IntArray, width: Int): BarColors? {
        if (width <= 0 || px.isEmpty()) return null
        val height = px.size / width
        val opaque = px.filter { (it ushr 24) >= OPAQUE_ALPHA }
        if (opaque.size < MIN_OPAQUE) return translucentInk(px)
        if (opaque.size < px.size * 0.8) return BarColors(average(opaque), null)

        val edge = ArrayList<Int>()
        for (y in 0 until height) for (x in 0 until width) {
            if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) px[y * width + x].let { if ((it ushr 24) >= OPAQUE_ALPHA) edge += it }
        }
        val bg = commonest(if (edge.size >= MIN_OPAQUE) edge else opaque)
        val far = opaque.maxOf { distance(it, bg) }
        if (far < MIN_TEXT_DISTANCE) return BarColors(null, color(bg)) // nothing readable in the box
        val glyphs = opaque.filter { distance(it, bg) >= far * 0.6 }.sortedByDescending { distance(it, bg) }
        return BarColors(average(glyphs.take(maxOf(8, glyphs.size / 5))), color(bg))
    }

    /**
     * Dark glyphs on a see-through bar: Android draws a light bar's dark icons and clock as translucent black (about
     * 60%), where its white ones are opaque, so none of them counts as opaque. Read only when everything else in the
     * box is clear: a whole bar caught fading in is translucent all over, and that is still no reading. The pixels
     * come unpremultiplied, so a translucent glyph's colour is its own.
     */
    private fun translucentInk(px: IntArray): BarColors? {
        if (px.count { (it ushr 24) < CLEAR_ALPHA } < px.size * 0.6) return null
        val ink = px.filter { (it ushr 24) >= INK_ALPHA }
        if (ink.size < MIN_OPAQUE) return null
        return BarColors(average(ink.sortedByDescending { it ushr 24 }.take(maxOf(8, ink.size / 3))), null)
    }

    /** The commonest colour of [px], as RGB: colours are grouped 16 levels to a channel, so a dithered or slightly graded background still counts as one. */
    private fun commonest(px: List<Int>): Int = averageRgb(px.groupBy { it and 0xF0F0F0 }.maxBy { it.value.size }.value)

    private fun averageRgb(px: List<Int>): Int {
        var rr = 0L; var gg = 0L; var bb = 0L
        for (p in px) { rr += (p shr 16) and 0xFF; gg += (p shr 8) and 0xFF; bb += p and 0xFF }
        return ((rr / px.size).toInt() shl 16) or ((gg / px.size).toInt() shl 8) or (bb / px.size).toInt()
    }

    private fun average(px: List<Int>) = color(averageRgb(px))

    private fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + kotlin.math.abs((a and 0xFF) - (b and 0xFF))
}
