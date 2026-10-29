package com.rodinrescue

import com.rodinrescue.usb.HidKeyboard
import com.rodinrescue.usb.HidKeyboard.L_ALT
import com.rodinrescue.usb.HidKeyboard.L_CTRL
import com.rodinrescue.usb.HidKeyboard.L_SHIFT
import com.rodinrescue.usb.HidKeyboard.Report
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HidKeyboardTest {
    @Test fun descriptorIsTheStandardBootKeyboard() {
        assertEquals(63, HidKeyboard.REPORT_DESCRIPTOR.size)
        // Logical/usage maximum 0x65: anything above is dropped by the host (F13 was, on the real laptop).
        assertTrue(HidKeyboard.KEYS.values.all { it in 1..HidKeyboard.MAX_USAGE })
    }

    @Test fun typesUsLayout() {
        assertEquals(Report(0, 0x0f), HidKeyboard.forChar('l'))
        assertEquals(Report(L_SHIFT, 0x0f), HidKeyboard.forChar('L'))
        assertEquals(Report(0, 0x27), HidKeyboard.forChar('0'))
        assertEquals(Report(L_SHIFT, 0x1f), HidKeyboard.forChar('@'))
        assertEquals(Report(L_SHIFT, 0x27), HidKeyboard.forChar(')'))
        assertEquals(Report(L_SHIFT, 0x31), HidKeyboard.forChar('|'))
        assertEquals(Report(0, 0x28), HidKeyboard.forChar('\n'))
        // Every printable ASCII char is typable.
        assertTrue((32..126).all { HidKeyboard.forChar(it.toChar()) != null })
    }

    @Test fun textBecomesPressReleasePairsAndRefusesUntypable() {
        val r = HidKeyboard.typeReports("ls -la\n")
        assertEquals(14, r.size)
        assertTrue(r.indices.filter { it % 2 == 1 }.all { r[it] == HidKeyboard.RELEASE })
        assertEquals(listOf('م', 'é'), HidKeyboard.untypable("aمbéم"))
        val e = runCatching { HidKeyboard.typeReports("sudo مرحبا") }.exceptionOrNull()
        assertTrue(e?.message, e is IllegalArgumentException && e.message!!.contains("م"))
    }

    @Test fun parsesCombos() {
        assertEquals(Report(0, 0x3b), HidKeyboard.parseCombo("F2"))
        assertEquals(Report(L_CTRL or L_ALT, 0x4c), HidKeyboard.parseCombo("Ctrl+Alt+Del"))
        assertEquals(Report(L_CTRL or L_ALT, 0x3c), HidKeyboard.parseCombo(" ctrl + alt + f3 "))
        assertEquals(Report(L_SHIFT, 0x2b), HidKeyboard.parseCombo("shift+tab"))
        assertEquals(Report(L_CTRL, 0), HidKeyboard.parseCombo("ctrl"))
        for (bad in listOf("", "ctrl+", "f13", "ctrl+a+b", "hyper+x")) assertTrue(bad, runCatching { HidKeyboard.parseCombo(bad) }.isFailure)
    }

    @Test fun octalLinesAreSafeAndExact() {
        val line = HidKeyboard.octal(Report(L_SHIFT, 0x0f).bytes())
        assertEquals("\\002\\000\\017\\000\\000\\000\\000\\000", line)
        assertTrue(HidKeyboard.LINE.matches(line))
        assertFalse(HidKeyboard.LINE.matches("\\002%s\\000"))
    }

    // ── Scripts, run with the real `sh` against a fake configfs tree ──
    private fun sh(script: String, env: Map<String, String>): String {
        val f = File.createTempFile("hid", ".sh"); f.writeText(script)
        val p = ProcessBuilder("sh", f.path).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        return p.inputStream.bufferedReader().readText().also { f.delete() }
    }

    private fun fakeGadget(): Pair<File, Map<String, String>> {
        val g = Files.createTempDirectory("g1").toFile()
        File(g, "functions/hid.gs0").mkdirs(); File(g, "functions/ffs.adb").mkdirs(); File(g, "configs/b.1").mkdirs()
        File(g, "UDC").writeText("11201000.usb0\n")
        Files.createSymbolicLink(File(g, "configs/b.1/f1").toPath(), File(g, "functions/ffs.adb").toPath())
        val hidg = File(g, "hidg0")
        return g to mapOf("LIFELINE_GADGET" to g.path, "LIFELINE_HIDG" to hidg.path)
    }

    @Test fun enableLinksKeyboardKeepsOtherFunctionsAndRebinds() {
        val (g, env) = fakeGadget()
        File(env.getValue("LIFELINE_HIDG")).writeText("")  // the kernel would create it after binding
        assertTrue(sh(HidKeyboard.enableScript(), env).contains("HID_ON"))
        val link = File(g, "configs/b.1/${HidKeyboard.LINK}")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/f1").toPath()))  // ADB untouched
        assertEquals("1", File(g, "functions/hid.gs0/protocol").readText().trim())
        assertEquals("8", File(g, "functions/hid.gs0/report_length").readText().trim())
        assertArrayEquals(HidKeyboard.REPORT_DESCRIPTOR, File(g, "functions/hid.gs0/report_desc").readBytes())
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())
        // Idempotent: a second enable doesn't unbind again.
        File(g, "UDC").writeText("marker\n")
        assertTrue(sh(HidKeyboard.enableScript(), env).contains("HID_ON"))
        assertEquals("marker", File(g, "UDC").readText().trim())
        assertTrue(sh(HidKeyboard.statusScript(), env).contains("HID_ON"))
    }

    @Test fun disableRemovesOnlyOurLink() {
        val (g, env) = fakeGadget()
        File(env.getValue("LIFELINE_HIDG")).writeText("")
        sh(HidKeyboard.enableScript(), env)
        assertTrue(sh(HidKeyboard.disableScript(), env).contains("HID_OFF"))
        assertFalse(File(g, "configs/b.1/${HidKeyboard.LINK}").exists())
        assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/f1").toPath()))
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())
        assertTrue(sh(HidKeyboard.disableScript(), env).contains("HID_OFF"))  // idempotent
        assertTrue(sh(HidKeyboard.statusScript(), env).contains("HID_OFF"))
    }

    @Test fun reportsUnsupportedAndMissingDevice() {
        val g = Files.createTempDirectory("nohid").toFile(); File(g, "configs/b.1").mkdirs()
        assertTrue(sh(HidKeyboard.enableScript(), mapOf("LIFELINE_GADGET" to g.path)).contains("HID_UNSUPPORTED"))
        val (_, env) = fakeGadget()  // no hidg0 file → kernel never created the device
        assertTrue(sh(HidKeyboard.enableScript(), env).contains("HID_NO_DEVICE"))
    }

    @Test fun helperWritesExactlyOneReportPerLine() {
        val dir = Files.createTempDirectory("helper").toFile(); val out = File(dir, "hidg0")
        val f = File(dir, "h.sh"); f.writeText(HidKeyboard.helperScript())
        val p = ProcessBuilder("sh", f.path).apply { environment()["LIFELINE_HIDG"] = out.path }.start()
        val w = p.outputStream.bufferedWriter(); val r = p.inputStream.bufferedReader()
        val reports = HidKeyboard.typeReports("Hi")
        val got = reports.map { rep -> w.write(HidKeyboard.octal(rep.bytes()) + "\n"); w.flush(); assertEquals("OK", r.readLine()); out.readBytes() }
        w.close(); p.waitFor(5, TimeUnit.SECONDS)
        assertEquals(reports.map { it.bytes().toList() }, got.map { it.toList() })
    }

    @Test fun combosAcceptPunctuationKeys() {
        assertEquals(HidKeyboard.Report(HidKeyboard.L_CTRL or HidKeyboard.L_SHIFT, 0x2e), HidKeyboard.parseCombo("ctrl+shift+="))  // terminal zoom in
        assertEquals(HidKeyboard.Report(HidKeyboard.L_CTRL, 0x2d), HidKeyboard.parseCombo("ctrl+-"))
        assertEquals(HidKeyboard.Report(HidKeyboard.L_CTRL or HidKeyboard.L_SHIFT, 0x2e), HidKeyboard.parseCombo("ctrl+shift++".replace("++", "+=")))
    }
}
