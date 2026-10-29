package com.rodinrescue

import com.rodinrescue.usb.BootLibrary
import com.rodinrescue.usb.BootLibrary.Mode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BootLibraryTest {
    private fun sh(script: String): String {
        val f = File.createTempFile("boot", ".sh"); f.writeText(script)
        val p = ProcessBuilder("sh", f.path).redirectErrorStream(true).apply {
            environment()["PATH"] = System.getenv("PATH") + ":/usr/sbin:/sbin" }.start()
        if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
        return p.inputStream.bufferedReader().readText().also { f.delete() }
    }

    private fun head(block: (ByteArray) -> Unit) = ByteArray(34816).also(block)

    @Test fun detectsHowEachKindOfImageMustBeShown() {
        // Hybrid ISO (Kali, Ubuntu, Alpine): ISO 9660 + an MBR with a partition → USB disk
        val hybrid = head { b -> b[510] = 0x55; b[511] = 0xAA.toByte(); b[446 + 4] = 0x17; "CD001".toByteArray().copyInto(b, 32769) }
        assertEquals(Mode.DISK, BootLibrary.detectMode(hybrid))
        // Plain ISO (Windows installer): ISO 9660, no partition table → CD
        val plain = head { b -> "CD001".toByteArray().copyInto(b, 32769) }
        assertEquals(Mode.CD, BootLibrary.detectMode(plain))
        // GPT disk image → disk; an MBR signature with no partitions doesn't count
        assertEquals(Mode.DISK, BootLibrary.detectMode(head { b -> "EFI PART".toByteArray().copyInto(b, 512); "CD001".toByteArray().copyInto(b, 32769) }))
        assertEquals(Mode.CD, BootLibrary.detectMode(head { b -> b[510] = 0x55; b[511] = 0xAA.toByte(); "CD001".toByteArray().copyInto(b, 32769) }))
        // unknown / tiny file → disk
        assertEquals(Mode.DISK, BootLibrary.detectMode(ByteArray(10)))
    }

    @Test fun listsImagesWithTheirSavedSpace() {
        val dir = Files.createTempDirectory("lib").toFile()
        File(dir, "kali-live.iso").writeText("x".repeat(10)); File(dir, "Hiren's BootCD.ISO").writeText("y")
        File(dir, "notes.txt").writeText("no"); File(dir, "disk.img").writeText("zz")
        File(dir, ".saved").mkdirs(); File(dir, ".saved/kali-live.iso.saved").writeText("s".repeat(7))
        val imgs = BootLibrary.parseList(sh(BootLibrary.listScript(dir.path)), dir.path)
        assertEquals(listOf("disk.img", "Hiren's BootCD.ISO", "kali-live.iso"), imgs.map { it.name })
        val kali = imgs.last()
        assertEquals(10L, kali.bytes); assertEquals(7L, kali.savedBytes); assertNull(imgs[0].savedBytes)
        assertEquals("${dir.path}/.saved/kali-live.iso.saved", kali.savedPath)
    }

    @Test fun movesAnImageInWithoutOverwriting() {
        val dl = Files.createTempDirectory("dl").toFile(); val dir = Files.createTempDirectory("lib").toFile()
        val src = File(dl, "kali linux 2026.iso").apply { writeText("ISO") }
        assertTrue(sh(BootLibrary.moveInScript(src.path, dir.path)).contains("MOVED kali linux 2026.iso"))
        assertFalse(src.exists()); assertEquals("ISO", File(dir, "kali linux 2026.iso").readText())
        val again = File(dl, "kali linux 2026.iso").apply { writeText("NEW") }
        assertTrue(sh(BootLibrary.moveInScript(again.path, dir.path)).contains("ERR exists"))
        assertTrue(again.exists()); assertEquals("ISO", File(dir, "kali linux 2026.iso").readText())
        assertEquals("/data/media/0/Download/a.iso", BootLibrary.kernelPath("/storage/emulated/0/Download/a.iso"))
        assertEquals("/data/media/0/Download/a.iso", BootLibrary.kernelPath("/sdcard/Download/a.iso"))
    }

    @Test fun savedSpaceIsAnExt4LabelledPersistenceWithTheConfFile() {
        assumeTrue("needs mke2fs + debugfs", listOf("/usr/sbin/mke2fs", "/sbin/mke2fs").any { File(it).exists() })
        val dir = Files.createTempDirectory("lib").toFile()
        val img = BootLibrary.Image("kali.iso", 1, null, dir.path)
        assertTrue(sh(BootLibrary.createSavedScript(img, 1)).contains("SAVED_OK"))
        val f = File(img.savedPath)
        assertEquals(1L shl 30, f.length())                                  // 1 GB, sparse
        val info = sh("dumpe2fs -h '${f.path}' 2>/dev/null | grep -i 'volume name'")
        assertTrue(info, info.contains("persistence"))
        assertEquals("/ union", sh("debugfs -R 'cat /persistence.conf' '${f.path}' 2>/dev/null").trim())
        assertTrue(sh(BootLibrary.createSavedScript(img, 1)).contains("ERR exists"))   // never overwrites saved data
        assertThrows(IllegalArgumentException::class.java) { BootLibrary.createSavedScript(img, 0) }
        assertThrows(IllegalArgumentException::class.java) { BootLibrary.deleteScript("/etc/passwd", dir.path) }
    }
}
