package com.rodinrescue.usb

/**
 * The phone as a USB drive (configfs mass_storage): a boot image (ISO / disk image) the PC can boot from, plus an
 * optional second, writable drive (a live system's saved space, or a plain USB stick).
 * Same pattern as the keyboard ([HidKeyboard]): our own link name ([LINK]) next to whatever Android has (MTP,
 * tethering, ADB, the keyboard), so Android's own reconfig of f1..f5 leaves it alone, and disable removes only ours.
 * Pure script builders; run them as root. G is overridable so tests can use a fake configfs tree.
 *
 * Found on the POCO (2026-10-15): the kernel's file-storage thread can read the app's files dir and /data/media/0
 * (the user's storage) but NOT /data/local/tmp (SELinux), and UEFI ignored CD mode for a hybrid ISO (use disk mode).
 */
object RescueDrive {
    const val LINK = "lifeline_ms"

    /** Absolute, no "..", no control characters. Spaces and quotes are fine: every path is shell-quoted. */
    fun checkPath(p: String) =
        require(p.startsWith("/") && "/../" !in "$p/" && p.none { it.code < 32 || it.code == 127 }) { "unsafe path" }

    /** Single-quotes [s] for sh: 'it'\''s' — safe for any text without control characters. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private const val HEAD = """
G=${'$'}{LIFELINE_GADGET:-/config/usb_gadget/g1}
C=${'$'}(ls -d ${'$'}G/configs/* 2>/dev/null | head -1)
F=${'$'}(ls -d ${'$'}G/functions/mass_storage.* 2>/dev/null | head -1)
udc() { u=${'$'}(cat ${'$'}G/UDC 2>/dev/null); [ -n "${'$'}u" ] || u=${'$'}(getprop sys.usb.controller 2>/dev/null); echo "${'$'}u"; }
"""

    /**
     * One drive the PC sees: an image file, read-only or writable, as a disk or a CD. [name]: what the PC's boot
     * menu (F12) lists, after the vendor "Lifeline" (≤16 ASCII characters, see [driveName]); null = kernel default.
     */
    data class Lun(val path: String, val readOnly: Boolean = true, val cdrom: Boolean = false, val name: String? = null)

    /**
     * The product part of the drive's SCSI name for an image file: "kali-linux-2026.2-installer-amd64.iso" →
     * "kali 2026.2". The PC's boot menu then shows "Lifeline kali 2026.2". Words like linux/live/amd64 are dropped
     * to fit 13 characters (SCSI inquiry: 8 vendor + 16 product, which starts with " - ").
     */
    fun driveName(fileName: String): String {
        val noise = setOf("linux", "live", "amd64", "x86", "64", "x64", "64bit", "x86_64", "iso", "desktop", "installer", "server", "dvd", "netinst", "std")
        val words = fileName.substringBeforeLast('.').split('-', '_', ' ', '+').map { w -> w.filter { it.isLetterOrDigit() && it.code < 128 || it == '.' } }.filter { it.isNotEmpty() }
        val kept = words.filter { it.lowercase() !in noise }.ifEmpty { words }
        val out = StringBuilder()
        for (w in kept) {
            var add = if (out.isEmpty()) w else " $w"
            while (out.length + add.length > 13 && '.' in add) add = add.substringBeforeLast('.')   // "26.04.1" → "26.04"
            if (out.length + add.length > 13) break
            out.append(add)
        }
        return out.toString().ifEmpty { kept.firstOrNull()?.take(13) ?: "Boot drive" }
    }

    /**
     * Vendor (8) + product (16): the kernel pads the rest; only printable ASCII, so it is safe inside q(). The
     * product starts with " - ": some firmware (seen on an ASUS) shows vendor and product glued together ("UEFI: Lifelinekali").
     */
    private fun inquiry(name: String) = "Lifeline" + (" - " + name.filter { it.code in 32..126 }).take(16).padEnd(16)
    /** lun.0..lun.15 on the POCO's kernel (measured 2026-10-15). */
    const val MAX_LUNS = 16

    /**
     * Shows [image] to the PC as a read-only USB drive; [cdrom] presents it as a CD (ISO-only images such as Windows
     * installers). [writable]: a second LUN, read-write (saved space / USB stick); null removes it.
     */
    fun enableScript(image: String, cdrom: Boolean, writable: String? = null): String =
        enableScript(listOfNotNull(Lun(image, true, cdrom), writable?.let { Lun(it, readOnly = false) }))

    /**
     * Shows each of [luns] as its own USB drive (lun.0 first: the PC boots from it by default), removes any other
     * LUNs of ours. Prints MS_ON, or MS_UNSUPPORTED / MS_NO_IMAGE / MS_NO_UDC / MS_*_FAILED.
     */
    fun enableScript(luns: List<Lun>): String {
        require(luns.size in 1..MAX_LUNS) { "1..$MAX_LUNS drives" }
        luns.forEach { checkPath(it.path) }
        val want = luns.withIndex().joinToString("") { (i, l) -> "${i}:${if (l.readOnly) 1 else 0}:${if (l.cdrom) 1 else 0}:${l.path}\n" }
        val sets = luns.withIndex().joinToString("\n") { (i, l) -> """
[ -d ${'$'}F/lun.$i ] || mkdir ${'$'}F/lun.$i || fail MS_LUN_FAILED
echo "" > ${'$'}F/lun.$i/file 2>/dev/null
echo ${if (l.readOnly) 1 else 0} > ${'$'}F/lun.$i/ro && echo ${if (l.cdrom) 1 else 0} > ${'$'}F/lun.$i/cdrom && echo 1 > ${'$'}F/lun.$i/removable || fail MS_ATTR_FAILED
echo ${l.name?.let { q(inquiry(it)) } ?: "\"\""} > ${'$'}F/lun.$i/inquiry_string 2>/dev/null
echo ${q(l.path)} > ${'$'}F/lun.$i/file || fail MS_FILE_FAILED""" }
        return HEAD + """
if [ -z "${'$'}F" ]; then mkdir ${'$'}G/functions/mass_storage.lifeline 2>/dev/null && F=${'$'}G/functions/mass_storage.lifeline; fi
[ -n "${'$'}F" ] && [ -d "${'$'}F/lun.0" ] && [ -n "${'$'}C" ] || { echo MS_UNSUPPORTED; exit 2; }
for p in ${luns.joinToString(" ") { q(it.path) }}; do [ -f "${'$'}p" ] || { echo MS_NO_IMAGE; exit 5; }; done
WANT=${q(want)}
have() { i=0; while [ -d ${'$'}F/lun.${'$'}i ]; do printf '%s:%s:%s:%s\n' ${'$'}i "${'$'}(cat ${'$'}F/lun.${'$'}i/ro)" "${'$'}(cat ${'$'}F/lun.${'$'}i/cdrom)" "${'$'}(cat ${'$'}F/lun.${'$'}i/file)"; i=${'$'}((i+1)); done; }
if [ -L "${'$'}C/$LINK" ] && [ "${'$'}(have)" = "${'$'}(printf '%b' "${'$'}WANT")" ]; then echo MS_ON; exit 0; fi
UDC=${'$'}(udc); [ -n "${'$'}UDC" ] || { echo MS_NO_UDC; exit 4; }
echo "" > ${'$'}G/UDC
fail() { i=0; while [ -d ${'$'}F/lun.${'$'}i ]; do echo "" > ${'$'}F/lun.${'$'}i/file 2>/dev/null; i=${'$'}((i+1)); done; rm -f ${'$'}C/$LINK; echo "${'$'}UDC" > ${'$'}G/UDC; echo "${'$'}1"; exit 3; }
$sets
i=${luns.size}; while [ ${'$'}i -lt $MAX_LUNS ]; do [ -d ${'$'}F/lun.${'$'}i ] && { echo "" > ${'$'}F/lun.${'$'}i/file 2>/dev/null; rmdir ${'$'}F/lun.${'$'}i 2>/dev/null; }; i=${'$'}((i+1)); done
[ -L "${'$'}C/$LINK" ] || ln -s ${'$'}F ${'$'}C/$LINK || fail MS_LINK_FAILED
sleep 1; echo "${'$'}UDC" > ${'$'}G/UDC
echo MS_ON
"""
    }

    /** Removes the drive (only our link), empties both LUNs and re-binds. Prints MS_OFF. */
    fun disableScript(): String = HEAD + """
if [ -z "${'$'}C" ] || [ ! -L "${'$'}C/$LINK" ]; then echo MS_OFF; exit 0; fi
UDC=${'$'}(udc)
echo "" > ${'$'}G/UDC; rm -f ${'$'}C/$LINK
if [ -n "${'$'}F" ]; then echo "" > ${'$'}F/lun.0/file 2>/dev/null; i=1; while [ ${'$'}i -lt $MAX_LUNS ]; do [ -d ${'$'}F/lun.${'$'}i ] && { echo "" > ${'$'}F/lun.${'$'}i/file 2>/dev/null; rmdir ${'$'}F/lun.${'$'}i 2>/dev/null; }; i=${'$'}((i+1)); done; fi
sleep 1; [ -n "${'$'}UDC" ] && echo "${'$'}UDC" > ${'$'}G/UDC
echo MS_OFF
"""

    /** "MS_ON\n<image>\n<writable or empty>" when shown, MS_OFF otherwise, MS_UNSUPPORTED without mass_storage. */
    fun statusScript(): String = HEAD + """
[ -n "${'$'}F" ] && [ -d "${'$'}F/lun.0" ] || { echo MS_UNSUPPORTED; exit 0; }
if [ -n "${'$'}C" ] && [ -L "${'$'}C/$LINK" ]; then echo MS_ON; cat ${'$'}F/lun.0/file 2>/dev/null; cat ${'$'}F/lun.1/file 2>/dev/null || echo; else echo MS_OFF; fi
"""
}
