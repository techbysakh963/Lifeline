package com.rodinrescue

import com.rodinrescue.runtime.RootfsArchive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class RootfsArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tar(build: TarArchiveOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { o ->
        TarArchiveOutputStream(o).use { it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); it.build() }
    }.toByteArray()

    private fun TarArchiveOutputStream.file(name: String, text: String, mode: Int = "644".toInt(8)) {
        val b = text.toByteArray(); putArchiveEntry(TarArchiveEntry(name).apply { size = b.size.toLong(); this.mode = mode }); write(b); closeArchiveEntry()
    }
    private fun TarArchiveOutputStream.dir(name: String) { putArchiveEntry(TarArchiveEntry(name.trimEnd('/') + "/")); closeArchiveEntry() }
    private fun TarArchiveOutputStream.link(name: String, to: String, hard: Boolean) {
        putArchiveEntry(TarArchiveEntry(name, if (hard) TarArchiveEntry.LF_LINK else TarArchiveEntry.LF_SYMLINK).apply { linkName = to }); closeArchiveEntry()
    }

    @Test fun unpacksFilesDirsSymlinksAndTurnsHardlinksIntoCopies() {
        val dest = tmp.newFolder("rootfs")
        val r = RootfsArchive.extractTar(ByteArrayInputStream(tar {
            dir("./usr/bin"); file("./usr/bin/perl", "#!perl", "755".toInt(8)); link("./usr/bin/perl5.38", "usr/bin/perl", hard = true)
            link("./bin", "usr/bin", hard = false); file("./etc/os-release", "NAME=Ubuntu")
        }), dest)
        assertEquals("#!perl", File(dest, "usr/bin/perl5.38").readText())
        assertFalse(Files.isSymbolicLink(File(dest, "usr/bin/perl5.38").toPath()))
        assertTrue(File(dest, "usr/bin/perl5.38").canExecute())
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertEquals("NAME=Ubuntu", File(dest, "etc/os-release").readText())
        assertEquals(1, r.copiedHardlinks); assertTrue(r.skipped.isEmpty())
    }

    @Test fun neverWritesOutsideTheRootfs() {
        val base = tmp.newFolder("base"); val dest = File(base, "rootfs"); val outside = File(base, "outside").apply { mkdirs() }
        val r = RootfsArchive.extractTar(ByteArrayInputStream(tar {
            file("../evil", "x"); file("./a/../../evil2", "x")
            file("/abs", "x")   // the tar reader itself strips a leading "/", so this lands inside
            // a symlink pointing out, then a file "inside" it: must not land in outside/
            link("./escape", outside.absolutePath, hard = false); file("./escape/planted", "x")
            // a hardlink to a file outside
            link("./copy", "../outside/secret", hard = true)
        }), dest)
        File(outside, "secret").writeText("s")
        assertFalse(File(base, "evil").exists()); assertFalse(File(base, "evil2").exists())
        assertFalse(File(outside, "planted").exists()); assertFalse(File(dest, "copy").exists())
        assertTrue(File(dest, "abs").isFile)
        assertEquals(4, r.skipped.size)
    }

    @Test fun deleteTreeDoesNotFollowSymlinks() {
        val base = tmp.newFolder("b"); val keep = File(base, "keep").apply { mkdirs(); File(this, "f").writeText("k") }
        val root = File(base, "rootfs").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), keep.toPath())
        File(root, "d/e").mkdirs(); File(root, "d/e/x").writeText("x"); File(root, "d/e").setWritable(false)
        RootfsArchive.deleteTree(root)
        assertFalse(root.exists()); assertEquals("k", File(keep, "f").readText())
    }
}
