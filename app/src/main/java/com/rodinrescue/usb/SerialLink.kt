package com.rodinrescue.usb

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The phone end of the USB serial port, open: a root process running [UsbSerial.bridgeScript]. [write] goes to
 * the PC; [onText] gets what the PC sends (UTF-8 decoded across reads, so split characters survive).
 */
class SerialLink(private val tty: String, private val onText: (String) -> Unit, private val onClosed: () -> Unit) {
    @Volatile private var p: Process? = null
    val isOpen get() = p?.isAlive == true

    fun open() {
        if (isOpen) return
        val proc = ProcessBuilder("su", "-M", "-c", UsbSerial.bridgeScript(tty)).redirectErrorStream(true).start()
        p = proc
        Thread({
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buf = ByteArray(4096); val bb = ByteBuffer.allocate(8192); val cb = CharBuffer.allocate(8192)
            runCatching {
                val input = proc.inputStream
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    bb.put(buf, 0, n); bb.flip(); dec.decode(bb, cb, false); bb.compact()
                    cb.flip(); if (cb.hasRemaining()) onText(cb.toString()); cb.clear()
                }
            }
            onClosed()
        }, "serial-read").apply { isDaemon = true }.start()
    }

    fun write(s: String) { runCatching { p?.outputStream?.apply { write(s.toByteArray()); flush() } } }

    fun close() { p?.let { runCatching { it.outputStream.close() }; it.destroy() }; p = null }
}

/**
 * Turns raw terminal output into plain lines for the phone's screen: drops ANSI/VT escape sequences (colours,
 * bracketed-paste switches "ESC[?2004h", titles), CR LF → LF, a lone CR rewinds the line, backspace erases.
 */
class TermText {
    private val done = mutableListOf<String>()
    private val cur = StringBuilder()
    private var esc = 0          // 0 none, 1 after ESC, 2 in CSI, 3 in OSC
    private var pendingCr = false

    val lines: List<String> get() = done + cur.toString()

    fun feed(s: String) {
        for (ch in s) {
            when (esc) {
                1 -> { esc = when (ch) { '[' -> 2; ']' -> 3; else -> 0 }; continue }
                2 -> { if (ch in '@'..'~') esc = 0; continue }
                3 -> { if (ch == '\u0007') esc = 0 else if (ch == '\u001b') esc = 1; continue }
            }
            if (pendingCr) { pendingCr = false; if (ch != '\n') cur.setLength(0) }
            when (ch) {
                '\u001b' -> esc = 1
                '\r' -> pendingCr = true
                '\n' -> { done += cur.toString(); cur.setLength(0) }
                '\b', '\u007f' -> if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                '\t' -> cur.append("    ")
                else -> if (ch >= ' ') cur.append(ch)
            }
        }
        if (done.size > MAX_LINES) done.subList(0, done.size - MAX_LINES).clear()
    }

    fun clear() { done.clear(); cur.setLength(0) }
    fun text() = lines.joinToString("\n")

    companion object { const val MAX_LINES = 2000 }
}
