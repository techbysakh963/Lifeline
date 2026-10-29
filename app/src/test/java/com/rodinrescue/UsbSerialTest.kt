package com.rodinrescue

import com.rodinrescue.usb.TermText
import com.rodinrescue.usb.UsbSerial
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class UsbSerialTest {
    @Test fun terminalTextDropsEscapesAndHandlesLineEndings() {
        val t = TermText()
        // what a real laptop shell sent back through the phone (bracketed-paste switches, CR LF)
        t.feed("name -ro\r\n\u001b[?2004l\rbash: name: command not found\r\n\u001b[?2004hbash-5.2\$ ")
        assertEquals(listOf("name -ro", "bash: name: command not found", "bash-5.2\$ "), t.lines)
        t.clear(); t.feed("\u001b[1;32mok\u001b[0m\r\nab\bc\r\n\u001b]0;title\u0007x\r\n")
        assertEquals(listOf("ok", "ac", "x", ""), t.lines)
        t.clear(); t.feed("50%\r60%\r100%\r\n")          // progress bars rewrite one line
        assertEquals(listOf("100%", ""), t.lines)
        t.clear(); t.feed("spl"); t.feed("it\r"); t.feed("\nline")   // pieces across reads
        assertEquals(listOf("split", "line"), t.lines)
    }

    private fun sh(script: String, g: File, dev: File): String {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply { environment()["LIFELINE_GADGET"] = g.path; environment()["LIFELINE_DEV"] = dev.path }.start()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS)); return p.inputStream.bufferedReader().readText()
    }

    @Test fun serialPortIsLinkedNextToEverythingElseAndRemovedAlone() {
        val g = Files.createTempDirectory("g1").toFile(); val dev = Files.createTempDirectory("dev").toFile()
        File(g, "functions/acm.gs0").mkdirs(); File(g, "functions/acm.gs0/port_num").writeText("0\n"); File(g, "functions/ffs.adb").mkdirs()
        File(g, "configs/b.1").mkdirs(); File(g, "UDC").writeText("11201000.usb0\n")
        Files.createSymbolicLink(File(g, "configs/b.1/f1").toPath(), File(g, "functions/ffs.adb").toPath())
        assertTrue(sh(UsbSerial.statusScript(), g, dev).contains("ACM_OFF"))
        assertTrue(sh(UsbSerial.enableScript(), g, dev).contains("ACM_ON ${dev.path}/ttyGS0"))
        assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/${UsbSerial.LINK}").toPath()))
        assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/f1").toPath()))
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())
        assertTrue(sh(UsbSerial.statusScript(), g, dev).contains("ACM_ON"))
        assertTrue(sh(UsbSerial.disableScript(), g, dev).contains("ACM_OFF"))
        assertFalse(File(g, "configs/b.1/${UsbSerial.LINK}").exists()); assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/f1").toPath()))
        // a kernel without the function
        File(g, "functions/acm.gs0").deleteRecursively()
        assertTrue(sh(UsbSerial.enableScript(), g, dev).contains("ACM_UNSUPPORTED"))
    }
}
