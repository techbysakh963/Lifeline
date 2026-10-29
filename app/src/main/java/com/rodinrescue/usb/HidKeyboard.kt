package com.rodinrescue.usb

/**
 * USB boot-protocol keyboard: report encoding, US keymap, key-combo parsing, and the root scripts that add or
 * remove the phone's `hid.gs0` function. Pure (no Android) so it is unit-tested; [HidController] runs it.
 *
 * Boot protocol is what every BIOS/UEFI understands: 8-byte reports [modifiers, 0, key1..key6]. The standard
 * descriptor only allows usages 0x00..0x65, which covers everything firmware needs (F1–F12, Esc, Del, arrows).
 */
object HidKeyboard {
    /** One 8-byte input report. */
    data class Report(val modifiers: Int, val key: Int) {
        init { require(modifiers in 0..0xFF && key in 0..MAX_USAGE) { "bad report $modifiers/$key" } }
        fun bytes() = byteArrayOf(modifiers.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)
    }
    val RELEASE = Report(0, 0)
    const val MAX_USAGE = 0x65

    // Modifier bits.
    const val L_CTRL = 0x01; const val L_SHIFT = 0x02; const val L_ALT = 0x04; const val L_GUI = 0x08
    const val R_CTRL = 0x10; const val R_SHIFT = 0x20; const val R_ALT = 0x40; const val R_GUI = 0x80

    /** Standard boot keyboard report descriptor (HID 1.11 appendix B.1), 63 bytes. */
    val REPORT_DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x05, 0x07, 0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x03, 0x95, 0x05, 0x75, 0x01,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x03, 0x95, 0x06,
        0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0
    ).map { it.toByte() }.toByteArray()

    /** Named keys (lower-case). Usages from the HID Usage Tables, Keyboard/Keypad page 0x07. */
    val KEYS: Map<String, Int> = buildMap {
        put("enter", 0x28); put("return", 0x28); put("esc", 0x29); put("escape", 0x29); put("backspace", 0x2a)
        put("tab", 0x2b); put("space", 0x2c); put("capslock", 0x39)
        for (n in 1..12) put("f$n", 0x3a + n - 1)
        put("printscreen", 0x46); put("prtsc", 0x46); put("scrolllock", 0x47); put("pause", 0x48)
        put("insert", 0x49); put("ins", 0x49); put("home", 0x4a); put("pageup", 0x4b); put("pgup", 0x4b)
        put("delete", 0x4c); put("del", 0x4c); put("end", 0x4d); put("pagedown", 0x4e); put("pgdn", 0x4e)
        put("right", 0x4f); put("left", 0x50); put("down", 0x51); put("up", 0x52)
        for (c in 'a'..'z') put(c.toString(), 0x04 + (c - 'a'))
        for (d in 1..9) put(d.toString(), 0x1e + d - 1); put("0", 0x27)
    }
    private val MODS = mapOf("ctrl" to L_CTRL, "control" to L_CTRL, "shift" to L_SHIFT, "alt" to L_ALT,
        "win" to L_GUI, "super" to L_GUI, "meta" to L_GUI, "gui" to L_GUI, "altgr" to R_ALT)

    // US layout: unshifted / shifted character on each punctuation key.
    private val PUNCT: Map<Char, Report> = buildMap {
        fun k(plain: Char, shifted: Char, usage: Int) { put(plain, Report(0, usage)); put(shifted, Report(L_SHIFT, usage)) }
        k('-', '_', 0x2d); k('=', '+', 0x2e); k('[', '{', 0x2f); k(']', '}', 0x30); k('\\', '|', 0x31)
        k(';', ':', 0x33); k('\'', '"', 0x34); k('`', '~', 0x35); k(',', '<', 0x36); k('.', '>', 0x37); k('/', '?', 0x38)
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, Report(L_SHIFT, if (i == 9) 0x27 else 0x1e + i)) }
        put(' ', Report(0, 0x2c)); put('\n', Report(0, 0x28)); put('\t', Report(0, 0x2b))
    }

    /** Report for one character on a US layout, or null if it can't be typed (non-ASCII, control chars). */
    fun forChar(c: Char): Report? = when (c) {
        in 'a'..'z' -> Report(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> Report(L_SHIFT, 0x04 + (c - 'A'))
        in '1'..'9' -> Report(0, 0x1e + (c - '1'))
        '0' -> Report(0, 0x27)
        else -> PUNCT[c]
    }

    // Dictation (phone keyboards) writes typographic punctuation that looks the same as plain ASCII; map it so a
    // note isn't refused over one curly apostrophe. Real letters (é, Arabic) are NOT changed: they stay untypable.
    private val FOLD = mapOf('\u2018' to "'", '\u2019' to "'", '\u201A' to "'", '\u2032' to "'",
        '\u201C' to "\"", '\u201D' to "\"", '\u201E' to "\"", '\u2033' to "\"",
        '\u2010' to "-", '\u2011' to "-", '\u2013' to "-", '\u2014' to "-", '\u2212' to "-", '\u2026' to "...",
        '\u00A0' to " ", '\u2007' to " ", '\u2009' to " ", '\u202F' to " ", '\r' to "",
        '\u200B' to "", '\uFEFF' to "")

    /** [text] with typographic punctuation replaced by what a US keyboard can type; line breaks become plain \n. */
    fun foldPunctuation(text: String): String = buildString {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        for (c in t) append(FOLD[c] ?: c)
    }

    /** Characters in [text] that [forChar] can't type, in order of first appearance. */
    fun untypable(text: String): List<Char> = text.filter { forChar(it) == null }.toList().distinct()

    /**
     * Reports for [text]. Throws if anything is untypable, so nothing is ever half-typed.
     * Default: press + release per character (what firmware keyboard drivers in BIOS/GRUB expect).
     * [rollover]: go straight from one key to the next, like fingers rolling on a real keyboard; a release is sent
     * only between two presses of the same key and at the end. About half the reports, so ~2x faster. Used for
     * voice typing into a desktop OS.
     */
    fun typeReports(text: String, rollover: Boolean = false): List<Report> {
        val bad = untypable(text)
        require(bad.isEmpty()) { "can't type: " + bad.joinToString(" ") { if (it.code < 32) "\\u%04x".format(it.code) else it.toString() } }
        if (!rollover) return text.flatMap { listOf(forChar(it)!!, RELEASE) }
        val out = ArrayList<Report>(text.length + 1)
        for (c in text) {
            val r = forChar(c)!!
            if (out.isNotEmpty() && out.last().key == r.key) out.add(RELEASE)
            out.add(r)
        }
        if (out.isNotEmpty()) out.add(RELEASE)
        return out
    }

    /** Parses a combo like "F2", "ctrl+alt+del", "Shift+Tab", "win+r". Throws on unknown names. */
    fun parseCombo(combo: String): Report {
        val parts = combo.lowercase().split('+').map { it.trim() }
        require(parts.none { it.isEmpty() }) { "malformed key combo '$combo'" }
        var mods = 0; var key = 0
        for (p in parts) {
            val m = MODS[p]
            when {
                m != null -> mods = mods or m
                KEYS[p] != null -> { require(key == 0) { "only one non-modifier key per combo: $combo" }; key = KEYS.getValue(p) }
                // A single punctuation character names its key ("ctrl+=", "ctrl+-", "ctrl+/"); its own shift is kept.
                p.length == 1 && forChar(p[0]) != null -> {
                    require(key == 0) { "only one non-modifier key per combo: $combo" }
                    val r = forChar(p[0])!!; key = r.key; mods = mods or r.modifiers
                }
                else -> throw IllegalArgumentException("unknown key '$p' in '$combo'")
            }
        }
        return Report(mods, key)
    }

    fun comboReports(combo: String) = listOf(parseCombo(combo), RELEASE)

    /** Octal-escaped printf format for raw bytes (`\000\017…`): safe for `printf "$line"` (no %, no quotes). */
    fun octal(bytes: ByteArray): String = bytes.joinToString("") { "\\%03o".format(it.toInt() and 0xFF) }
    val LINE = Regex("^(\\\\[0-3][0-7]{2}){8}$")

    // ── Root scripts (configfs). G/HIDG are overridable so tests can run them against a fake tree. ──
    const val LINK = "lifeline_hid"
    private const val HEAD = """
G=${'$'}{LIFELINE_GADGET:-/config/usb_gadget/g1}; HIDG=${'$'}{LIFELINE_HIDG:-/dev/hidg0}; F=${'$'}G/functions/hid.gs0
C=${'$'}(ls -d ${'$'}G/configs/* 2>/dev/null | head -1)
[ -d "${'$'}F" ] && [ -n "${'$'}C" ] || { echo HID_UNSUPPORTED; exit 2; }
udc() { u=${'$'}(cat ${'$'}G/UDC 2>/dev/null); [ -n "${'$'}u" ] || u=${'$'}(getprop sys.usb.controller 2>/dev/null); echo "${'$'}u"; }
"""

    /** Adds the keyboard to the current USB config (whatever Android has: MTP, ADB, tethering). Prints HID_ON on success. */
    fun enableScript(): String = HEAD + """
if [ -L "${'$'}C/$LINK" ]; then echo HID_ON; exit 0; fi
UDC=${'$'}(udc); [ -n "${'$'}UDC" ] || { echo HID_NO_UDC; exit 4; }
echo 1 > ${'$'}F/protocol && echo 1 > ${'$'}F/subclass && echo 8 > ${'$'}F/report_length || { echo HID_ATTR_FAILED; exit 3; }
printf '${octal(REPORT_DESCRIPTOR)}' > ${'$'}F/report_desc || { echo HID_ATTR_FAILED; exit 3; }
echo "" > ${'$'}G/UDC
if ! ln -s ${'$'}F ${'$'}C/$LINK; then echo "${'$'}UDC" > ${'$'}G/UDC; echo HID_LINK_FAILED; exit 3; fi
sleep 1; echo "${'$'}UDC" > ${'$'}G/UDC
i=0; while [ ! -e "${'$'}HIDG" ] && [ ${'$'}i -lt 25 ]; do sleep 0.2; i=${'$'}((i+1)); done
[ -e "${'$'}HIDG" ] && echo HID_ON || echo HID_NO_DEVICE
"""

    /** Removes the keyboard and re-binds the USB controller. Prints HID_OFF. */
    fun disableScript(): String = HEAD + """
if [ ! -L "${'$'}C/$LINK" ]; then echo HID_OFF; exit 0; fi
UDC=${'$'}(udc)
echo "" > ${'$'}G/UDC; rm -f ${'$'}C/$LINK; sleep 1; [ -n "${'$'}UDC" ] && echo "${'$'}UDC" > ${'$'}G/UDC
echo HID_OFF
"""

    fun statusScript(): String = HEAD + """[ -L "${'$'}C/$LINK" ] && [ -e "${'$'}HIDG" ] && echo HID_ON || echo HID_OFF
"""

    /**
     * Long-lived root helper: one octal line per report, each becomes exactly one 8-byte write.
     * Android's shell (mksh) has a built-in `print` (octal as \0ooo): no process per report, half the time of the
     * external `printf` (measured: 2 vs 4 ms per report). Other shells (the JVM tests' sh) fall back to printf.
     */
    fun helperScript(): String = """HIDG=${'$'}{LIFELINE_HIDG:-/dev/hidg0}
if [ "${'$'}(print -n '\0101' 2>/dev/null)" = A ]; then eval 'w() { print -n "${'$'}{1//\\/\\0}"; }'
else w() { printf "${'$'}1"; }; fi
while IFS= read -r l; do w "${'$'}l" > "${'$'}HIDG" && echo OK || echo ERR; done
"""
}
