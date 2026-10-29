package com.rodinrescue.usb

import com.rodinrescue.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * The phone as a USB keyboard (root). Single owner of the HID gadget: every enable/disable/type goes through
 * one [Mutex], so two flows can never race on configfs (that race was hit in testing: an old auto-restore
 * unbound the keyboard mid-typing). Driven by the user (USB tab, voice typing) or by the keyboard-rescue AI,
 * whose every non-navigation key goes through the approval rules in KeyboardAgent.
 */
object HidController {
    enum class State { OFF, ON, UNSUPPORTED, ERROR }
    /** [keys] = keys fully pressed and released on the PC by this call (for typing: characters typed). */
    data class Result(val state: State, val message: String, val keys: Int = 0)

    private val lock = Mutex()
    /** Last time the keyboard was turned on or sent a key (for [UsbAutoOff]). */
    @Volatile var lastUseMs = 0L; private set
    private var helper: Process? = null
    private var toHelper: BufferedWriter? = null
    private var fromHelper: BufferedReader? = null

    private fun parse(out: String): Result = when {
        "HID_ON" in out -> Result(State.ON, "Keyboard on")
        "HID_OFF" in out -> Result(State.OFF, "Keyboard off")
        "HID_UNSUPPORTED" in out -> Result(State.UNSUPPORTED, "This phone can't act as a USB keyboard: its kernel was built without the keyboard (HID) function. Keyboard rescue and Type on PC need it; SSH rescue works normally.")
        "HID_NO_DEVICE" in out -> Result(State.ERROR, "Keyboard added but /dev/hidg0 never appeared. Is the cable plugged into the PC?")
        "HID_NO_UDC" in out -> Result(State.ERROR, "No USB controller bound. Plug the phone into the PC first")
        else -> Result(State.ERROR, out.lines().lastOrNull { it.isNotBlank() } ?: "root command failed")
    }

    suspend fun status(): Result = lock.withLock { parse(RootShell.run(HidKeyboard.statusScript()).output) }

    /** Adds the keyboard next to whatever USB functions are active. The PC sees the phone reconnect once. */
    suspend fun enable(): Result = lock.withLock { lastUseMs = System.currentTimeMillis(); parse(RootShell.run(HidKeyboard.enableScript(), timeoutSec = 30).output) }

    suspend fun disable(): Result = lock.withLock { stopHelper(); parse(RootShell.run(HidKeyboard.disableScript(), timeoutSec = 30).output) }

    /** Types [text] on a US layout. Refuses (sends nothing) if any character can't be typed. */
    suspend fun type(text: String, rollover: Boolean = false): Result {
        val reports = HidKeyboard.typeReports(text, rollover)
        // Characters typed = presses that reached the PC (every character's press has a key code; releases don't).
        return send(reports) { acked -> reports.take(acked).count { it.key != 0 } }
    }

    /** Presses one combo, e.g. "F2", "ctrl+alt+del". [times] repeats it (e.g. tapping F12 during POST). */
    suspend fun press(combo: String, times: Int = 1): Result = send((1..times.coerceIn(1, 30)).flatMap { HidKeyboard.comboReports(combo) })

    private suspend fun send(reports: List<HidKeyboard.Report>, keysFor: (Int) -> Int = { it / 2 }): Result = lock.withLock {
        lastUseMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            var acked = 0
            for (attempt in 0..1) {
                val ok = runCatching {
                    val (w, r) = ensureHelper()
                    for (rep in reports) {
                        val line = HidKeyboard.octal(rep.bytes()); check(HidKeyboard.LINE.matches(line))
                        w.write(line); w.newLine(); w.flush()
                        val ack = r.readLine() ?: error("keyboard helper exited")
                        check(ack == "OK") { "write to /dev/hidg0 failed. Is the keyboard still on?" }
                        acked++
                    }
                }
                if (ok.isSuccess) return@withContext keysFor(reports.size).let { Result(State.ON, "Sent $it key${if (it == 1) "" else "s"}", it) }
                stopHelper()
                // Retry only if nothing reached the PC: a retry after a partial send would type the start twice.
                if (attempt == 1 || acked > 0) return@withContext Result(State.ERROR, ok.exceptionOrNull()?.message ?: "send failed", keysFor(acked))
                delay(300)
            }
            Result(State.ERROR, "send failed")
        }
    }

    private fun ensureHelper(): Pair<BufferedWriter, BufferedReader> {
        val p = helper
        if (p != null && p.isAlive) return toHelper!! to fromHelper!!
        val np = ProcessBuilder("su", "-c", HidKeyboard.helperScript()).redirectErrorStream(true).start()
        helper = np; toHelper = np.outputStream.bufferedWriter(); fromHelper = np.inputStream.bufferedReader()
        return toHelper!! to fromHelper!!
    }

    private fun stopHelper() {
        runCatching { toHelper?.close() }; helper?.destroy()
        helper = null; toHelper = null; fromHelper = null
    }
}
