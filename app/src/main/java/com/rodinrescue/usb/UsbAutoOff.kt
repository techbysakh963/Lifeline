package com.rodinrescue.usb

/**
 * Keeps Lifeline's USB extras (keyboard, boot drive) from lingering. Found 2026-10-15: the keyboard stayed on after
 * a test, and when Android later rebuilt its own USB functions they landed after ours, so file transfer was no
 * longer interface 0 and the PC stopped showing the phone's files.
 *
 * Rules: the keyboard turns off after [IDLE_MS] unused, or [LEAVE_GRACE_MS] after the last keyboard screen closes,
 * never while the keyboard-rescue AI is working. The boot drive is never turned off on its own (the user started
 * it on purpose, maybe for a PC that is booting right now); Home shows it until Stop. When both are off again,
 * Android's own mode from before is put back (file transfer).
 */
object UsbAutoOff {
    const val IDLE_MS = 10 * 60_000L
    const val LEAVE_GRACE_MS = 30_000L

    fun shouldTurnOffKeyboard(now: Long, lastUse: Long, screensOpen: Int, leftAt: Long, agentBusy: Boolean): Boolean {
        if (agentBusy) return false
        if (now - lastUse >= IDLE_MS) return true
        return screensOpen == 0 && leftAt > 0 && now - leftAt >= LEAVE_GRACE_MS && now - lastUse >= LEAVE_GRACE_MS
    }

    /**
     * The command that puts Android's USB mode back, or null if nothing is needed. [before] is sys.usb.config from
     * before Lifeline added anything; [now] is the current one. Only file transfer is restored: it's what the user
     * had chosen; tethering is switched by Android's tethering service (the app's own button handles that).
     */
    fun restoreCommand(before: String?, now: String): String? {
        val b = before?.split(',')?.map { it.trim() } ?: return null
        val n = now.split(',').map { it.trim() }
        return if ("mtp" in b && "mtp" !in n) "svc usb setFunctions mtp" else null
    }
}
