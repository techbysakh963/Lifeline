package com.rodinrescue

import com.rodinrescue.usb.HidController.Result
import com.rodinrescue.usb.HidController.State
import com.rodinrescue.usb.HidKeyboard
import com.rodinrescue.usb.VoiceTyper
import com.rodinrescue.usb.VoiceTyper.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceTyperTest {
    /** A fake PC: records what was typed; can fail after [failAfter] characters. */
    private class Pc(val failAfter: Int = Int.MAX_VALUE) {
        val typed = StringBuilder()
        val pieces = mutableListOf<String>()
        suspend fun type(s: String): Result {
            pieces.add(s)
            val room = failAfter - typed.length
            if (s.length > room) { typed.append(s.take(room)); return Result(State.ERROR, "write to /dev/hidg0 failed", room) }
            typed.append(s); return Result(State.ON, "ok", s.length)
        }
    }

    @Test fun dictationPunctuationIsTypedAsPlainKeys() {
        val (text, bad) = VoiceTyper.prepare("It’s “fine” — really…\r\nnext line")
        assertEquals("It's \"fine\" - really...\nnext line", text)
        assertTrue(bad.isEmpty())
        assertTrue(HidKeyboard.typeReports(text).isNotEmpty())
    }

    @Test fun aWholeNoteIsTypedInPiecesWithNewLinesAsEnter() = runBlocking {
        val pc = Pc(); val seen = mutableListOf<Pair<Int, Int>>()
        val note = "Hello team,\n" + "a".repeat(60)
        val o = VoiceTyper.send(note, pc::type) { d, t -> seen.add(d to t) }
        assertEquals(Outcome.Done(note.length), o)
        assertEquals(note, pc.typed.toString())
        assertTrue(pc.pieces.all { it.length <= VoiceTyper.PIECE })
        assertEquals(note.length to note.length, seen.last())
    }

    @Test fun lettersItCantTypeAreRefusedAndNothingIsSent() = runBlocking {
        val pc = Pc()
        val o = VoiceTyper.send("café ok", pc::type) as Outcome.Refused
        assertEquals(listOf('é'), o.chars); assertFalse(o.arabic)
        assertTrue(VoiceTyper.refusal(o).contains("Nothing was sent"))
        val ar = VoiceTyper.send("مرحبا hi", pc::type) as Outcome.Refused
        assertTrue(ar.arabic); assertTrue(VoiceTyper.refusal(ar).startsWith("Arabic"))
        assertTrue(pc.pieces.isEmpty())
    }

    @Test fun stopKeepsTheUntypedRestInTheNote() = runBlocking {
        val pc = Pc(); var calls = 0
        val note = "x".repeat(100)
        val o = VoiceTyper.send(note, { calls++; pc.type(it) }, stopped = { calls >= 2 }) as Outcome.Stopped
        assertEquals(2 * VoiceTyper.PIECE, o.typed)
        assertEquals(pc.typed.toString() + o.left, note)
        assertNull(o.error)
    }

    @Test fun aFailureMidPieceNeitherLosesNorRepeatsText() = runBlocking {
        val pc = Pc(failAfter = 30)
        val note = "The quick brown fox jumps over the lazy dog"
        val o = VoiceTyper.send(note, pc::type) as Outcome.Stopped
        assertEquals(30, o.typed)
        assertEquals(note, pc.typed.toString() + o.left)     // what reached the PC + what is left = the note, exactly
        assertTrue(o.error!!.contains("hidg0"))
    }

    @Test fun rolloverSkipsReleasesExceptBetweenTheSameKey() {
        val r = HidKeyboard.typeReports("Hello", rollover = true)
        // H e l [release] l o [release]: 7 reports instead of 10
        assertEquals(listOf(0x0b, 0x08, 0x0f, 0, 0x0f, 0x12, 0), r.map { it.key })
        assertEquals(HidKeyboard.L_SHIFT, r[0].modifiers); assertEquals(0, r[1].modifiers)
        // a then A: same key, different shift: still released in between, so the PC sees two presses
        assertEquals(listOf(0x04, 0, 0x04, 0), HidKeyboard.typeReports("aA", rollover = true).map { it.key })
        // default (BIOS / AI path) unchanged: press + release per character
        assertEquals(10, HidKeyboard.typeReports("Hello").size)
        assertTrue(HidKeyboard.typeReports("", rollover = true).isEmpty())
    }

    @Test fun sendWithoutThemKeepsWhatAUsKeyboardCanType() {
        assertEquals("Hi there, cafe ok!", VoiceTyper.makeTypable("Hi \uD83D\uDE00 there, caf\u00E9 \u2192 ok!"))
        assertEquals("Ma\u00F1ana".let(VoiceTyper::makeTypable), "Manana")
        assertEquals("It's fine\nline 2", VoiceTyper.makeTypable("It\u2019s fine \u2764\uFE0F\nline 2"))
        assertEquals("hi", VoiceTyper.makeTypable("\u0645\u0631\u062D\u0628\u0627 hi"))
        val t = VoiceTyper.makeTypable("Emoji \uD83D\uDC4D\uD83C\uDFFD and \u4E2D\u6587 text")
        assertEquals("Emoji and text", t)
        assertTrue(HidKeyboard.untypable(t).isEmpty())
        assertTrue(VoiceTyper.refusal(VoiceTyper.Outcome.Refused(listOf('\u00E9'), false)).contains("Send without them"))
    }

    @Test fun theRefusalShowsWholeEmojiNotBrokenHalves() = runBlocking {
        val o = VoiceTyper.send("me \uD83D\uDE14\uD83D\uDE14 ok \u2713 \u00B0 \uD83D\uDC4D\uD83C\uDFFD", Pc()::type) as Outcome.Refused
        assertEquals(listOf("\uD83D\uDE14", "\u2713", "\u00B0", "\uD83D\uDC4D\uD83C\uDFFD"), o.shown)   // 😔 once, ✓, °, 👍🏽 whole
        val msg = VoiceTyper.refusal(o)
        assertFalse(msg, msg.contains('\uFFFD'))
        assertTrue(msg.contains("\uD83D\uDE14"))
        // no lone surrogate anywhere in the message
        // joined emoji and flags stay whole too
        assertEquals(listOf("\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDF8\uD83C\uDDFE"), VoiceTyper.untypableShown("a \uD83D\uDC69\u200D\uD83D\uDCBB b \uD83C\uDDF8\uD83C\uDDFE"))
        assertTrue(msg.indices.all { i -> !msg[i].isSurrogate() || (msg[i].isHighSurrogate() && i + 1 < msg.length && msg[i + 1].isLowSurrogate()) || (msg[i].isLowSurrogate() && i > 0 && msg[i - 1].isHighSurrogate()) })
    }
}
