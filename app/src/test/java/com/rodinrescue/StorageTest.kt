package com.rodinrescue

import com.rodinrescue.data.Storage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class StorageTest {
    private fun sh(script: String): String {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        assertTrue("script finished", p.waitFor(30, TimeUnit.SECONDS))
        return p.inputStream.bufferedReader().readText()
    }

    /** files/conversations/<id>.json + <id>/NNN.jpg, files/audit/<id>.jsonl */
    private fun appFiles(): File {
        val f = Files.createTempDirectory("files").toFile()
        val c = File(f, "conversations").apply { mkdirs() }
        File(c, "a.json").writeText("x".repeat(100)); File(c, "a").mkdirs()
        File(c, "a/001.jpg").writeBytes(ByteArray(1000)); File(c, "a/002.jpg").writeBytes(ByteArray(500))
        File(c, "b.json").writeText("y".repeat(50))
        File(f, "audit").mkdirs(); File(f, "audit/a.jsonl").writeText("log"); File(f, "audit/b.jsonl").writeText("log")
        return f
    }

    @Test fun sizesPerConversationAndDeletingPhotosKeepsTheText() {
        val f = appFiles()
        val s = Storage.conversations(f).associateBy { it.id }
        assertEquals(Storage.ConvSize("a", 100, 1500, 2), s["a"]); assertEquals(Storage.ConvSize("b", 50, 0, 0), s["b"])
        assertEquals(1500L, Storage.deletePhotos(f, "a"))
        assertTrue(File(f, "conversations/a.json").exists()); assertFalse(File(f, "conversations/a").exists())
        assertEquals(0L, Storage.deletePhotos(f, "../audit"))   // ids are sanitised: never leaves conversations/
        assertTrue(File(f, "audit/a.jsonl").exists())
    }

    @Test fun pruneDeletesOnlyOldPhotosAndLogs() {
        val f = appFiles(); val now = System.currentTimeMillis(); val old = now - 40 * 86_400_000L
        File(f, "conversations/a/001.jpg").setLastModified(old); File(f, "audit/a.jsonl").setLastModified(old)
        File(f, "conversations/a.json").setLastModified(old)
        assertEquals(0L, Storage.prune(f, 0, now))                 // 0 = keep everything
        assertEquals(1003L, Storage.prune(f, 30, now))             // one photo (1000) + one log (3)
        assertFalse(File(f, "conversations/a/001.jpg").exists()); assertTrue(File(f, "conversations/a/002.jpg").exists())
        assertFalse(File(f, "audit/a.jsonl").exists()); assertTrue(File(f, "audit/b.jsonl").exists())
        assertTrue("text is kept", File(f, "conversations/a.json").exists())
        File(f, "conversations/a/002.jpg").setLastModified(old)
        Storage.prune(f, 30, now); assertFalse("empty photo dir removed", File(f, "conversations/a").exists())
    }

    @Test fun realUsageOfASparseSavedSpace() {
        val d = Files.createTempDirectory("boot").toFile()
        val sparse = File(d, "kali's.iso.saved").apply { RandomAccessFile(this, "rw").use { it.setLength(1L shl 30); it.seek(0); it.write(ByteArray(64 * 1024) { 1 }) } }
        val u = Storage.parseUsage(sh(Storage.usageScript(listOf(sparse.path, d.path + "/missing"))))
        assertTrue("counts what it holds, not 1 GB: ${u[sparse.path]}", (u[sparse.path] ?: 0) in (64L * 1024)..(1L shl 20))
        assertNull(u[d.path + "/missing"])
    }

    @Test fun measuresTheBuiltInUbuntuWithoutFollowingLinks() {
        val home = Files.createTempDirectory("runtime").toFile(); val r = File(home, "ubuntu")
        File(r, "root/.claude").mkdirs(); File(r, "root/.claude/creds.json").writeBytes(ByteArray(40 * 1024))
        File(r, "usr/local/bin").mkdirs(); File(r, "usr/local/bin/claude").writeBytes(ByteArray(100 * 1024))
        // a rootfs links to absolute host-looking paths; they must not be counted
        val outside = Files.createTempDirectory("big").toFile().apply { File(this, "x").writeBytes(ByteArray(500 * 1024)) }
        Files.createSymbolicLink(File(r, "proc").toPath(), outside.toPath())
        val m = Storage.builtin(listOf(r))
        assertEquals(140L * 1024, m["ubuntu"]); assertEquals(40L * 1024, m["logins"])
        assertEquals(mapOf("ubuntu" to 0L, "logins" to 0L), Storage.builtin(listOf(File(home, "missing/ubuntu"))))
    }
}
