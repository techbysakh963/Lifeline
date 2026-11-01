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
        assertTrue(sh("du -k '${f.path}'").trim().substringBefore('\t').toLong() < 8 * 1024)   // lazy init: barely any real space
        val info = sh("dumpe2fs -h '${f.path}' 2>/dev/null | grep -i 'volume name'")
        assertTrue(info, info.contains("persistence"))
        assertEquals("/ union", sh("debugfs -R 'cat /persistence.conf' '${f.path}' 2>/dev/null").trim())
        assertTrue(sh(BootLibrary.createSavedScript(img, 1)).contains("ERR exists"))   // never overwrites saved data
        assertThrows(IllegalArgumentException::class.java) { BootLibrary.createSavedScript(img, 0) }
        assertThrows(IllegalArgumentException::class.java) { BootLibrary.deleteScript("/etc/passwd", dir.path) }
    }
    @Test fun savedSpaceCanGrowToThePhonesFreeSpaceMinusAMargin() {
        val gb = 1L shl 30
        assertEquals(398, BootLibrary.maxSavedGb(400 * gb + 123))             // whole GB, 2 GB stay free
        assertEquals(0, BootLibrary.maxSavedGb(gb))                            // nearly full phone: none
        assertEquals(BootLibrary.MAX_SAVED_GB, BootLibrary.maxSavedGb(Long.MAX_VALUE))
        BootLibrary.createSavedScript(BootLibrary.Image("a.iso", 1, null), 500) // over the old 256 limit is fine
        assertThrows(IllegalArgumentException::class.java) { BootLibrary.createSavedScript(BootLibrary.Image("a.iso", 1, null), BootLibrary.MAX_SAVED_GB + 1) }
    }
    /** A small real ISO 9660 image with [dirs] in its root, made by xorriso. */
    private fun iso(vararg dirs: String): BootLibrary.Image {
        val root = Files.createTempDirectory("isoroot").toFile()
        for (d in dirs) File(root, d).apply { mkdirs(); File(this, "x").writeText("x") }
        File(root, "README.TXT").writeText("hi")
        val lib = Files.createTempDirectory("lib").toFile()
        sh("xorriso -as mkisofs -quiet -R -J -o '${lib.path}/t.iso' '${root.path}'")
        return BootLibrary.Image("t.iso", File(lib, "t.iso").length(), null, lib.path)
    }

    private fun liveOf(img: BootLibrary.Image): BootLibrary.Live? {
        val dec = java.util.Base64.getDecoder()
        val root = BootLibrary.isoRoot(dec.decode(sh(BootLibrary.headScript(img)).trim())) ?: return null
        return BootLibrary.detectLive(dec.decode(sh(BootLibrary.rootDirScript(img, root)).trim()))
    }

    @Test fun findsTheLiveSystemFromTheIsoRootFolder() {
        assumeTrue("needs xorriso", listOf("/usr/bin/xorriso", "/bin/xorriso").any { File(it).exists() })
        assertEquals(BootLibrary.Live.DEBIAN, liveOf(iso("boot", "live", "pool")))
        assertEquals(BootLibrary.Live.UBUNTU, liveOf(iso("boot", "casper", "pool")))
        assertNull(liveOf(iso("boot", "install", "sources")))                 // installers, Windows: no saved space
        assertNull(BootLibrary.isoRoot(ByteArray(40000)))                      // not ISO 9660
    }

    @Test fun ubuntuSavedSpaceIsLabelledCasperRw() {
        assumeTrue("needs mke2fs", listOf("/usr/sbin/mke2fs", "/sbin/mke2fs").any { File(it).exists() })
        val img = BootLibrary.Image("mint.iso", 1, null, Files.createTempDirectory("lib").toFile().path)
        assertTrue(sh(BootLibrary.createSavedScript(img, 1, BootLibrary.Live.UBUNTU)).contains("SAVED_OK"))
        assertTrue(sh("dumpe2fs -h '${img.savedPath}' 2>/dev/null | grep -i 'volume name'").contains("casper-rw"))
    }
}
