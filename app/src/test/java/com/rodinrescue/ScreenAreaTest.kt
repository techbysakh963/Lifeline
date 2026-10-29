package com.rodinrescue

import com.rodinrescue.vision.ScreenArea
import com.rodinrescue.vision.ScreenArea.P
import com.rodinrescue.vision.uselessPhoto
import org.junit.Assert.*
import org.junit.Test

class ScreenAreaTest {
    /** The lid found in real test photo 002 (portrait 960×1280), as fractions. */
    private val lid = ScreenArea(P(0f, 388 / 1280f), P(958 / 960f, 371 / 1280f), P(958 / 960f, 1033 / 1280f), P(0f, 1043 / 1280f))

    @Test fun aRealScreenFrameIsValidAndKeepsItsFullResolutionUpToTheCap() {
        assertTrue(lid.valid())
        // From the full photo (3000 px long side, portrait 2250×3000) the screen is ~2245 px wide: capped to 1600, never enlarged.
        val (w, h) = lid.outputSize(2250, 3000, 1600)
        assertEquals(1600, w); assertTrue("landscape screen (lid incl. bezel): $h", h in 900..1200)
        val (sw, sh) = lid.outputSize(480, 640, 1600)
        assertTrue("a small photo isn't enlarged", sw <= 480 && sh <= 400)
    }

    @Test fun crossedTinyOrOutOfOrderFramesAreRejected() {
        assertFalse("corners swapped (bow tie)", lid.copy(tr = lid.br, br = lid.tr).valid())
        assertFalse("tiny", ScreenArea(P(.5f, .5f), P(.55f, .5f), P(.55f, .53f), P(.5f, .53f)).valid())
        assertFalse("counter-clockwise", ScreenArea(lid.tl, lid.bl, lid.br, lid.tr).valid())
        assertTrue(ScreenArea.DEFAULT.valid())
    }

    @Test fun draggingACornerStaysInsideThePhoto() {
        val a = ScreenArea.DEFAULT.with(2, P(1.4f, -0.2f))
        assertEquals(P(1f, 0f), a.br); assertEquals(ScreenArea.DEFAULT.tl, a.tl)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f), ScreenArea(P(0f, 0f), P(1f, 0f), P(1f, 1f), P(0f, 1f)).pixels(100, 50), 0.001f)
    }

    @Test fun onlyTrulyUselessPhotosAreReplacedByTheCard() {
        assertNotNull("lens covered", uselessPhoto(IntArray(576) { 4 + it % 3 }))
        assertNotNull("flat grey", uselessPhoto(IntArray(576) { 120 }))
        // Real photos of a PC screen: brightness ~126, spread ~66 (a lit room with a black terminal).
        val real = IntArray(576) { if (it % 2 == 0) 60 else 192 }
        assertNull(uselessPhoto(real))
        assertNull("a dark terminal filling the frame is still a photo", uselessPhoto(IntArray(576) { if (it % 9 == 0) 200 else 20 }))
    }
}
