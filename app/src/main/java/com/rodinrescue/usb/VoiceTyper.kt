package com.rodinrescue.usb

/**
 * "Type on PC": the user writes, pastes or dictates (phone keyboard mic) a note, checks it, then taps Send and it is
 * typed on the PC's focused window. Pure logic (the keyboard is passed in) so it can be tested without a phone.
 *
 * The note is typed in small pieces so the user sees progress and can stop a long note; on stop or failure the part
 * not yet typed stays in the note, so nothing is lost and nothing is typed twice.
 */
object VoiceTyper {
    const val PIECE = 24

    sealed interface Outcome {
        /** Nothing sent: [chars] can't be typed on a US layout. */
        /** [shown]: the offending characters as a person sees them (a whole emoji, not its two UTF-16 halves). */
        data class Refused(val chars: List<Char>, val arabic: Boolean, val shown: List<String> = chars.map { it.toString() }) : Outcome
        data class Done(val typed: Int) : Outcome
        /** Stopped by the user or by an error; [left] is the part of the note that was not typed. */
        data class Stopped(val typed: Int, val left: String, val error: String?) : Outcome
    }

    /** What would be typed for [note] (punctuation folded), or the characters that make it untypable. */
    fun prepare(note: String): Pair<String, List<Char>> {
        val text = HidKeyboard.foldPunctuation(note)
        return text to HidKeyboard.untypable(text)
    }

    /**
     * The characters in [text] a US keyboard can't type, as whole user-visible characters (grapheme clusters): an emoji
     * is two UTF-16 halves (and may carry a skin tone or joiners), and listing the halves shows "�".
     */
    fun untypableShown(text: String): List<String> = clusters(text)
        .filter { g -> g.isNotBlank() && g.any { HidKeyboard.forChar(it) == null } }.distinct()

    /**
     * Splits [text] into user-visible characters by code point: a skin tone, variation selector, combining mark or
     * tag joins the character before it; a zero-width joiner glues the next one on (👩‍💻); two regional indicators make
     * one flag. Same result on the phone and in JVM tests (java.text.BreakIterator's rules differ between them).
     */
    fun clusters(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        var glueNext = false; var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val n = Character.charCount(cp)
            val type = Character.getType(cp)
            val extends = cp in 0x1F3FB..0x1F3FF || cp == 0xFE0E || cp == 0xFE0F || cp in 0xE0020..0xE007F ||
                type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || cp == 0x200D
            val flagPair = cp in 0x1F1E6..0x1F1FF && out.isNotEmpty() && out.last().let { l ->
                l.length == 2 && l.codePointAt(0) in 0x1F1E6..0x1F1FF }
            if (out.isNotEmpty() && (extends || glueNext || flagPair)) out.last().appendCodePoint(cp) else out += StringBuilder().appendCodePoint(cp)
            glueNext = cp == 0x200D
            i += n
        }
        return out.map { it.toString() }
    }

    fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    fun refusal(o: Outcome.Refused): String {
        val shown = o.shown.take(8).joinToString(" ") + if (o.shown.size > 8) " …" else ""
        return (if (o.arabic) "Arabic can't be typed yet: the phone acts as a US keyboard. " else "A US keyboard can't type $shown. ") +
            "Nothing was sent. Tap \"Send without them\" to type the rest."
    }

    /**
     * [note] made typable, for "Send without them": typographic punctuation folded, accented letters reduced to their
     * base letter (é → e, ñ → n), everything else a US keyboard can't type dropped (emoji, arrows, CJK, Arabic).
     * A space left doubled by a dropped character is collapsed.
     */
    fun makeTypable(note: String): String {
        val text = HidKeyboard.foldPunctuation(note)
        val out = StringBuilder(); var dropped = false
        for (c in text) {
            val k = when {
                HidKeyboard.forChar(c) != null -> c
                else -> java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull()
                    ?.takeIf { HidKeyboard.forChar(it) != null && it.isLetter() }
            }
            if (k == null) { dropped = true; continue }
            if (k == ' ' && dropped && (out.isEmpty() || out.last() == ' ' || out.last() == '\n')) continue
            if (k == '\n' && dropped && out.endsWith(' ')) out.setLength(out.length - 1)
            out.append(k); if (k != ' ') dropped = false
        }
        if (dropped && out.endsWith(' ')) out.setLength(out.length - 1)
        return out.toString()
    }

    /**
     * Types [note] through [type] (which returns the HID result). [stopped] is checked between pieces;
     * [progress] gets (typed, total).
     */
    suspend fun send(note: String, type: suspend (String) -> HidController.Result, stopped: () -> Boolean = { false },
                     progress: (Int, Int) -> Unit = { _, _ -> }): Outcome {
        val (text, bad) = prepare(note)
        if (bad.isNotEmpty()) return Outcome.Refused(bad, bad.any(::isArabic), untypableShown(text))
        var done = 0
        progress(0, text.length)
        while (done < text.length) {
            if (stopped()) return Outcome.Stopped(done, text.substring(done), null)
            val piece = text.substring(done, minOf(text.length, done + PIECE))
            val r = type(piece)
            if (r.state != HidController.State.ON) {
                val typed = done + r.keys.coerceIn(0, piece.length)
                return Outcome.Stopped(typed, text.substring(typed), r.message)
            }
            done += piece.length
            progress(done, text.length)
        }
        return Outcome.Done(done)
    }
}
