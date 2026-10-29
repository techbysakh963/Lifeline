package com.rodinrescue.vision

import kotlin.math.abs

/**
 * A tiny fingerprint of a screen photo: mean brightness (0..255) of each cell of a [W]×[H] grid. Used only to ask
 * "did that keypress change anything on the screen?" (the stuck guard), never sent to the AI.
 *
 * Two photos of an unchanged screen differ by camera noise and auto-exposure (small, spread out); a real change —
 * a moved menu highlight, a new line of output, a different page — makes a few cells differ a lot.
 */
object ScreenSignature {
    const val W = 32
    const val H = 18

    /** True when at least [minCells] cells differ by more than [cellDelta] after removing the overall exposure shift. */
    fun changed(a: IntArray?, b: IntArray?, cellDelta: Int = 24, minCells: Int = 2): Boolean {
        if (a == null || b == null || a.size != b.size) return true
        val shift = (b.sum() - a.sum()) / a.size   // auto-exposure brightens/darkens everything a little
        return a.indices.count { abs(b[it] - shift - a[it]) > cellDelta } >= minCells
    }
}

/**
 * Decides when the PC's screen has reacted to an action and settled, from a stream of camera frames' fingerprints (the
 * phone watches; no AI turn). Measured: a photo costs 0.4 s but every extra AI turn 2-5 s, so the photo
 * should come once, when the screen is ready, not after a fixed wait.
 *  - nothing changed for [quietMs] after the action → done (the action showed nothing, or it's slow: the AI can wait);
 *  - it changed → done once it has stayed still for [stableMs];
 *  - never longer than [maxMs].
 * [base]: the frame before the action (null = unknown: wait only for stillness).
 */
class Settle(private val base: IntArray?, private val start: Long, val quietMs: Long = 1500, val stableMs: Long = 600, val maxMs: Long = 8000) {
    private var last: IntArray? = null
    private var stillSince = 0L
    /** The screen changed after the action. */
    var changed = false; private set

    /** Feeds one frame (null = none arrived lately) at time [t]; true = take the photo now. */
    fun feed(sig: IntArray?, t: Long): Boolean {
        if (t - start >= maxMs) return true
        if (sig == null) return false
        if (!changed) {
            if (base == null || ScreenSignature.changed(base, sig)) { changed = true; last = sig; stillSince = t; return false }
            return t - start >= quietMs
        }
        if (ScreenSignature.changed(last, sig)) stillSince = t
        last = sig
        return t - stillSince >= stableMs
    }
}
