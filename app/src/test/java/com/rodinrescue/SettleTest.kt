package com.rodinrescue

import com.rodinrescue.vision.ScreenSignature
import com.rodinrescue.vision.Settle
import org.junit.Assert.*
import org.junit.Test

class SettleTest {
    private val n = ScreenSignature.W * ScreenSignature.H
    private fun screen(v: Int, bright: Int = 0) = IntArray(n) { if (it < bright) 220 else v }

    @Test fun aScreenThatReactsIsPhotographedOnceItIsStill() {
        val s = Settle(screen(20), start = 0)
        assertFalse(s.feed(screen(20), 100))                  // not yet changed
        assertFalse(s.feed(screen(20, bright = 40), 400))     // a menu opens: changed
        assertTrue(s.changed)
        assertFalse(s.feed(screen(20, bright = 80), 600))     // still drawing
        assertFalse(s.feed(screen(20, bright = 80), 1000))    // still for 400 ms
        assertTrue(s.feed(screen(20, bright = 80), 1250))     // still for 650 ms: photo
    }

    @Test fun nothingChangingEndsAfterTheQuietTime() {
        val s = Settle(screen(20), start = 0, quietMs = 1500)
        assertFalse(s.feed(screen(21), 1000)); assertTrue(s.feed(screen(20), 1600)); assertFalse(s.changed)
    }

    @Test fun aScreenThatNeverStopsChangingIsCappedAndNoFramesWaits() {
        val s = Settle(screen(20), start = 0, maxMs = 3000)
        var t = 0L; var flip = false
        while (!s.feed(screen(20, bright = if (flip) 60 else 0), t)) { t += 200; flip = !flip; assertTrue(t <= 3000) }
        assertEquals(3000L, t)
        val none = Settle(null, start = 0)
        assertFalse("no frames yet", none.feed(null, 500))
        assertFalse(none.feed(screen(9), 600)); assertTrue(none.feed(screen(9), 1300))
    }
}
