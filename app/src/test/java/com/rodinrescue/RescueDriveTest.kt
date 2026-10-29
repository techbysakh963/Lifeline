package com.rodinrescue

import com.rodinrescue.usb.RescueDrive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** USB-drive scripts, run with the real `sh` against a fake configfs tree (like the keyboard's). */
class RescueDriveTest {
    private fun sh(script: String, g: File): String {
        val f = File.createTempFile("msd", ".sh"); f.writeText(script)
        val p = ProcessBuilder("sh", f.path).redirectErrorStream(true).apply { environment()["LIFELINE_GADGET"] = g.path }.start()
        if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        return p.inputStream.bufferedReader().readText().also { f.delete() }
    }

    /** MTK-like gadget: mass_storage.usb0 with lun.0, ADB + tethering + our keyboard already linked. */
    private fun fakeGadget(): File {
        val g = Files.createTempDirectory("g1").toFile()
        val lun = File(g, "functions/mass_storage.usb0/lun.0").apply { mkdirs() }
        for (a in listOf("file", "ro", "cdrom", "removable")) File(lun, a).writeText("")
        for (fn in listOf("ffs.adb", "mtk_rndis.gs4", "hid.gs0")) File(g, "functions/$fn").mkdirs()
        File(g, "configs/b.1").mkdirs(); File(g, "UDC").writeText("11201000.usb0\n")
        for ((l, fn) in listOf("f1" to "mtk_rndis.gs4", "f2" to "ffs.adb", "lifeline_hid" to "hid.gs0"))
            Files.createSymbolicLink(File(g, "configs/b.1/$l").toPath(), File(g, "functions/$fn").toPath())
        return g
    }

    private fun image(): String = File.createTempFile("rescue", ".iso").apply { writeText("ISO") }.path

    @Test fun showsTheImageAsAReadOnlyCdNextToEverythingElse() {
        val g = fakeGadget(); val img = image()
        assertTrue(sh(RescueDrive.enableScript(img, cdrom = true), g).contains("MS_ON"))
        val lun = File(g, "functions/mass_storage.usb0/lun.0")
        assertEquals(img, File(lun, "file").readText().trim())
        assertEquals("1", File(lun, "ro").readText().trim())
        assertEquals("1", File(lun, "cdrom").readText().trim())
        assertTrue(Files.isSymbolicLink(File(g, "configs/b.1/${RescueDrive.LINK}").toPath()))
        for (l in listOf("f1", "f2", "lifeline_hid")) assertTrue(l, Files.isSymbolicLink(File(g, "configs/b.1/$l").toPath()))  // untouched
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())
        assertTrue(sh(RescueDrive.statusScript(), g).lines().take(2) == listOf("MS_ON", img))
        // same image again: no unbind (the PC doesn't see the phone reconnect)
        File(g, "UDC").writeText("marker\n")
        assertTrue(sh(RescueDrive.enableScript(img, cdrom = true), g).contains("MS_ON"))
        assertEquals("marker", File(g, "UDC").readText().trim())
    }

    @Test fun offRemovesOnlyOurLinkAndEmptiesTheDrive() {
        val g = fakeGadget(); val img = image()
        sh(RescueDrive.enableScript(img, cdrom = false), g)
        assertTrue(sh(RescueDrive.disableScript(), g).contains("MS_OFF"))
        assertFalse(File(g, "configs/b.1/${RescueDrive.LINK}").exists())
        assertEquals("", File(g, "functions/mass_storage.usb0/lun.0/file").readText().trim())
        for (l in listOf("f1", "f2", "lifeline_hid")) assertTrue(l, Files.isSymbolicLink(File(g, "configs/b.1/$l").toPath()))
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())
        assertTrue(sh(RescueDrive.disableScript(), g).contains("MS_OFF"))   // idempotent
        assertTrue(sh(RescueDrive.statusScript(), g).contains("MS_OFF"))
    }

    @Test fun refusesWhatItCantDo() {
        val g = fakeGadget()
        assertTrue(sh(RescueDrive.enableScript("/no/such/file.iso", cdrom = true), g).contains("MS_NO_IMAGE"))
        assertEquals("11201000.usb0", File(g, "UDC").readText().trim())   // nothing was unbound
        val bare = Files.createTempDirectory("nostorage").toFile().apply { File(this, "configs/b.1").mkdirs() }
        assertTrue(sh(RescueDrive.enableScript(image(), cdrom = true), bare).contains("MS_UNSUPPORTED"))   // e.g. the S9
        assertTrue(sh(RescueDrive.statusScript(), bare).contains("MS_UNSUPPORTED"))
        for (bad in listOf("/tmp/../etc/x", "rel.iso", "/tmp/a\nb.iso"))
            assertThrows(bad, IllegalArgumentException::class.java) { RescueDrive.enableScript(bad, true) }
    }

    @Test fun namesWithSpacesAndQuotesAreServedExactlyAndCantInject() {
        val g = fakeGadget()
        val dir = Files.createTempDirectory("lib").toFile()
        val img = File(dir, "Hiren's BootCD PE x64 \$(touch PWNED).iso").apply { writeText("ISO") }.path
        assertTrue(sh(RescueDrive.enableScript(img, cdrom = false), g).contains("MS_ON"))
        assertEquals(img, File(g, "functions/mass_storage.usb0/lun.0/file").readText().trim())
        assertFalse(File("PWNED").exists() || File(dir, "PWNED").exists() || File(g, "PWNED").exists())
    }

    @Test fun aSecondWritableDriveForSavedSpaceAndItGoesAwayAgain() {
        val g = fakeGadget(); val img = image(); val saved = image()
        assertTrue(sh(RescueDrive.enableScript(img, cdrom = false, writable = saved), g).contains("MS_ON"))
        val lun1 = File(g, "functions/mass_storage.usb0/lun.1")
        assertEquals(saved, File(lun1, "file").readText().trim())
        assertEquals("0", File(lun1, "ro").readText().trim())                      // writable
        assertEquals("1", File(g, "functions/mass_storage.usb0/lun.0/ro").readText().trim())   // the image stays read-only
        assertEquals(listOf("MS_ON", img, saved), sh(RescueDrive.statusScript(), g).lines().take(3))
        // same image without saved space: the second drive is emptied (and its slot removed on a real kernel;
        // a plain folder can't imitate configfs's always-present attribute files)
        assertTrue(sh(RescueDrive.enableScript(img, cdrom = false), g).contains("MS_ON"))
        assertEquals("", File(lun1, "file").takeIf { it.exists() }?.readText()?.trim() ?: "")
    }

    @Test fun theMenuDrivePlusManyIsosEachAsItsOwnDrive() {
        val g = fakeGadget()
        val menu = image(); val isos = List(3) { image() } + File.createTempFile("100% ", ".iso").apply { writeText("x") }.path
        val luns = listOf(RescueDrive.Lun(menu)) + isos.mapIndexed { i, p -> RescueDrive.Lun(p, cdrom = i == 1) }
        assertTrue(sh(RescueDrive.enableScript(luns), g).contains("MS_ON"))
        val f = File(g, "functions/mass_storage.usb0")
        assertEquals(menu, File(f, "lun.0/file").readText().trim())
        for ((i, p) in isos.withIndex()) {
            assertEquals(p, File(f, "lun.${i + 1}/file").readText().trim())
            assertEquals("1", File(f, "lun.${i + 1}/ro").readText().trim())
            assertEquals(if (i == 1) "1" else "0", File(f, "lun.${i + 1}/cdrom").readText().trim())
        }
        // same set again → no reconnect, even with "%" in a name
        File(g, "UDC").writeText("marker\n")
        assertTrue(sh(RescueDrive.enableScript(luns), g).contains("MS_ON"))
        assertEquals("marker", File(g, "UDC").readText().trim())
        assertThrows(IllegalArgumentException::class.java) { RescueDrive.enableScript(List(17) { RescueDrive.Lun(menu) }) }
    }

    @Test fun drivesAreNamedLifelinePlusTheImage() {
        assertEquals("kali 2026.2", RescueDrive.driveName("kali-linux-2026.2-installer-amd64.iso"))
        assertEquals("ubuntu 26.04", RescueDrive.driveName("ubuntu-26.04.1-live-server-amd64.iso"))
        assertEquals("linuxmint 22", RescueDrive.driveName("linuxmint-22.3-cinnamon-64bit.iso"))
        assertEquals("Hirens.BootCD", RescueDrive.driveName("Hiren's.BootCD.15.2.iso"))
        assertTrue(RescueDrive.driveName("a-very-long-name-that-goes-on-and-on.iso").length <= 13)
        assertEquals("Boot drive", RescueDrive.driveName("\"\$`.iso"))

        val g = fakeGadget(); val img = image()
        sh(RescueDrive.enableScript(listOf(RescueDrive.Lun(img, name = "it's kali 2026.2 and more"))), g)
        val inq = File(g, "functions/mass_storage.usb0/lun.0/inquiry_string").readText().trimEnd('\n')
        assertEquals("Lifeline - it's kali 202", inq)   // vendor (8) + product (16), quotes survive the shell
    }
}
