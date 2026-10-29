package com.rodinrescue

import com.rodinrescue.vision.ScreenSignature
import com.rodinrescue.vision.ScreenSignature.H
import com.rodinrescue.vision.ScreenSignature.W
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ScreenSignatureTest {
    /** A blue BIOS-like screen with a light highlight bar on [row]. */
    private fun bios(row: Int, noise: Int = 0, exposure: Int = 0, seed: Int = 1): IntArray {
        val r = Random(seed)
        return IntArray(W * H) { i -> (if (i / W == row) 200 else 40) + exposure + if (noise > 0) r.nextInt(-noise, noise + 1) else 0 }
    }

    @Test fun cameraNoiseAndExposureAreNotAChange() {
        assertFalse(ScreenSignature.changed(bios(5), bios(5, noise = 8, seed = 2)))
        assertFalse(ScreenSignature.changed(bios(5), bios(5, noise = 6, exposure = 20, seed = 3)))
    }

    @Test fun movedHighlightIsAChange() {
        assertTrue(ScreenSignature.changed(bios(5), bios(6, noise = 6, seed = 4)))
    }

    @Test fun oneNewLineOfOutputIsAChange() {
        val before = IntArray(W * H) { 20 }
        val after = before.copyOf().also { for (x in 0 until 10) it[12 * W + x] = 180 }   // text appears on one row
        assertTrue(ScreenSignature.changed(before, after))
    }

    @Test fun missingSignatureCountsAsChanged() {
        assertTrue(ScreenSignature.changed(null, bios(1)))
    }
}
