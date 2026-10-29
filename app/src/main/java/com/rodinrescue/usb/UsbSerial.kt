package com.rodinrescue.usb

/**
 * USB serial (CDC-ACM, Epic B4): the phone adds a serial port to the USB connection, next to whatever Android has
 * (MTP, ADB, tethering, the keyboard). A Linux PC sees it as /dev/ttyACM0; with a login prompt there (getty, set
 * up by the laptop script) the phone gets a shell that doesn't depend on the PC's network or SSH server.
 *
 * Same configfs pattern as the keyboard ([HidKeyboard]): our own link name, and disable removes only ours.
 * The phone end is /dev/ttyGS<port_num> of the acm function (POCO: acm.gs0 → ttyGS0; the S9 has acm.0).
 * Pure script builders; run them as root. G and the tty dir are overridable for tests.
 */
object UsbSerial {
    const val LINK = "lifeline_acm"

    private const val HEAD = """
G=${'$'}{LIFELINE_GADGET:-/config/usb_gadget/g1}; DEV=${'$'}{LIFELINE_DEV:-/dev}
C=${'$'}(ls -d ${'$'}G/configs/* 2>/dev/null | head -1)
F=${'$'}(ls -d ${'$'}G/functions/acm.* 2>/dev/null | head -1)
[ -n "${'$'}F" ] && [ -n "${'$'}C" ] || { echo ACM_UNSUPPORTED; exit 2; }
TTY=${'$'}DEV/ttyGS${'$'}(cat ${'$'}F/port_num 2>/dev/null || echo 0)
udc() { u=${'$'}(cat ${'$'}G/UDC 2>/dev/null); [ -n "${'$'}u" ] || u=${'$'}(getprop sys.usb.controller 2>/dev/null); echo "${'$'}u"; }
"""

    /** Adds the serial port to the current USB config. Prints "ACM_ON <phone tty>". */
    fun enableScript(): String = HEAD + """
if [ -L "${'$'}C/$LINK" ]; then echo "ACM_ON ${'$'}TTY"; exit 0; fi
UDC=${'$'}(udc); [ -n "${'$'}UDC" ] || { echo ACM_NO_UDC; exit 4; }
echo "" > ${'$'}G/UDC
if ! ln -s ${'$'}F ${'$'}C/$LINK; then echo "${'$'}UDC" > ${'$'}G/UDC; echo ACM_LINK_FAILED; exit 3; fi
sleep 1; echo "${'$'}UDC" > ${'$'}G/UDC
echo "ACM_ON ${'$'}TTY"
"""

    /** Removes the serial port (only our link) and re-binds. Prints ACM_OFF. */
    fun disableScript(): String = HEAD + """
if [ ! -L "${'$'}C/$LINK" ]; then echo ACM_OFF; exit 0; fi
UDC=${'$'}(udc)
echo "" > ${'$'}G/UDC; rm -f ${'$'}C/$LINK; sleep 1; [ -n "${'$'}UDC" ] && echo "${'$'}UDC" > ${'$'}G/UDC
echo ACM_OFF
"""

    /** "ACM_ON <tty>", ACM_OFF, or ACM_UNSUPPORTED (no acm function in this kernel). */
    fun statusScript(): String = HEAD + """[ -L "${'$'}C/$LINK" ] && echo "ACM_ON ${'$'}TTY" || echo ACM_OFF
"""

    /**
     * Long-lived root bridge between the app and the phone's tty: our stdin goes to the PC, what the PC sends comes
     * out on our stdout. The tty is made raw with echo off first: Android leaves it cooked, so it would echo the
     * PC's output straight back to the PC (and mangle line endings).
     */
    fun bridgeScript(tty: String): String {
        RescueDrive.checkPath(tty)
        val t = RescueDrive.q(tty)
        return """stty -F $t raw -echo -echoe -echok -echoctl 2>/dev/null || stty raw -echo < $t
cat $t &
R=${'$'}!
cat > $t
kill ${'$'}R 2>/dev/null
"""
    }
}
