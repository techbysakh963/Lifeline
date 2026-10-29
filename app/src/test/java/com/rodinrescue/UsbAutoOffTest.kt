package com.rodinrescue

import com.rodinrescue.usb.UsbAutoOff
import com.rodinrescue.usb.UsbAutoOff.IDLE_MS
import com.rodinrescue.usb.UsbAutoOff.LEAVE_GRACE_MS
import org.junit.Assert.*
import org.junit.Test

class UsbAutoOffTest {
    private val t = 1_000_000_000L

    @Test fun keyboardTurnsOffWhenIdleOrAfterLeavingItsScreens() {
        // 10 minutes unused → off, even on a keyboard screen
        assertTrue(UsbAutoOff.shouldTurnOffKeyboard(t, t - IDLE_MS, 1, 0, false))
        assertFalse(UsbAutoOff.shouldTurnOffKeyboard(t, t - IDLE_MS + 1, 1, 0, false))
        // left the keyboard screens 30 s ago and nothing typed since → off
        assertTrue(UsbAutoOff.shouldTurnOffKeyboard(t, t - LEAVE_GRACE_MS, 0, t - LEAVE_GRACE_MS, false))
        // just moved between keyboard screens → stays on
        assertFalse(UsbAutoOff.shouldTurnOffKeyboard(t, t - LEAVE_GRACE_MS, 0, t - 5_000, false))
        // the keyboard-rescue AI is working → never
        assertFalse(UsbAutoOff.shouldTurnOffKeyboard(t, t - 2 * IDLE_MS, 0, t - IDLE_MS, true))
    }

    @Test fun fileTransferIsPutBackOnlyIfItWasOnBefore() {
        assertEquals("svc usb setFunctions mtp", UsbAutoOff.restoreCommand("mtp,adb", "adb"))
        assertNull(UsbAutoOff.restoreCommand("mtp,adb", "mtp,adb"))      // already back
        assertNull(UsbAutoOff.restoreCommand("adb", "adb"))              // never had file transfer: don't expose files
        assertNull(UsbAutoOff.restoreCommand("rndis,adb", "adb"))        // tethering: the app's own button
        assertNull(UsbAutoOff.restoreCommand(null, "adb"))
    }
}
