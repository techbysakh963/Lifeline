package com.rodinrescue.vision

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the PC's screen is in the camera's photo: four corners (top-left, top-right, bottom-right, bottom-left) as
 * fractions of the upright photo (0..1). Set by the user once per rescue on the photo card; the phone stands still, so
 * the next automatic photos are cut to this area and straightened. Measured on real photos: the
 * screen was only ~40% of the frame, and the whole frame was shrunk to 1280 px before sending, so cutting the screen
 * out of the full-resolution photo first gives the AI much sharper text.
 * Pure (no Android) so it is unit-tested; CameraEyes draws it.
 */
data class ScreenArea(val tl: P, val tr: P, val br: P, val bl: P) {
    data class P(val x: Float, val y: Float) { fun clamp() = P(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) }

    val corners get() = listOf(tl, tr, br, bl)

    /** Moves corner [i] (0 = tl … 3 = bl) and keeps it inside the photo. */
    fun with(i: Int, p: P) = when (i) { 0 -> copy(tl = p.clamp()); 1 -> copy(tr = p.clamp()); 2 -> copy(br = p.clamp()); else -> copy(bl = p.clamp()) }

    /** A real quadrilateral: convex, corners in order (clockwise), and not tiny. */
    fun valid(): Boolean {
        val c = corners
        val crosses = (0..3).map { i -> val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x) }
        return crosses.all { it > 0f } && area() > 0.02f
    }

    /** Area as a fraction of the photo (shoelace). */
    fun area(): Float { val c = corners; return kotlin.math.abs((0..3).sumOf { i -> (c[i].x * c[(i + 1) % 4].y - c[(i + 1) % 4].x * c[i].y).toDouble() }).toFloat() / 2f }

    /**
     * Size of the straightened screen for a [w]×[h] photo: the longer of each pair of opposite edges, scaled so the
     * longer side is at most [maxSide] (never enlarged).
     */
    fun outputSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        fun len(a: P, b: P) = hypot(((a.x - b.x) * w).toDouble(), ((a.y - b.y) * h).toDouble())
        val ow = max(len(tl, tr), len(bl, br)); val oh = max(len(tl, bl), len(tr, br))
        val s = minOf(1.0, maxSide / max(ow, oh))
        return (ow * s).roundToInt().coerceAtLeast(1) to (oh * s).roundToInt().coerceAtLeast(1)
    }

    /** Source corners in pixels, for Matrix.setPolyToPoly: [x0,y0, x1,y1, …] in tl, tr, br, bl order. */
    fun pixels(w: Int, h: Int) = corners.flatMap { listOf(it.x * w, it.y * h) }.toFloatArray()

    companion object {
        /** Starting frame on the card: most of the photo, the user drags the corners onto the screen. */
        val DEFAULT = ScreenArea(P(0.08f, 0.2f), P(0.92f, 0.2f), P(0.92f, 0.8f), P(0.08f, 0.8f))
    }
}

/**
 * A photo nobody can read the screen from: the lens is covered, the phone faces down, or the room is dark. Checked on
 * the WHOLE frame's brightness grid (a black terminal inside a lit room is fine), conservatively, so it only replaces a
 * useless photo with the photo card instead of interrupting a normal rescue. [grid]: 0..255 luminance cells.
 */
fun uselessPhoto(grid: IntArray): String? {
    if (grid.isEmpty()) return null
    val mean = grid.average()
    val sd = kotlin.math.sqrt(grid.sumOf { (it - mean) * (it - mean) } / grid.size)
    return when {
        mean < 12 -> "the photo is almost black (dark room, or the lens is covered)"
        sd < 3 -> "the photo shows nothing but one flat colour"
        else -> null
    }
}
