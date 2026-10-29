package com.rodinrescue.data

import com.rodinrescue.usb.RescueDrive.q
import java.io.File

/**
 * Storage & cleanup (Settings › Storage): what Lifeline uses on the phone and how to free it. Pure helpers over
 * the app's own files (JVM-testable with temp dirs) plus root scripts for the parts the app can't read itself:
 * the Boot library's real usage (saved spaces are sparse). The AI CLIs live in Lifeline's own Ubuntu (files/runtime).
 *
 * Measured on a POCO X7 Pro: app data 5 MB (conversations + photos 4.3 MB, audit 0.6 MB);
 * Boot library ~10 GB. (The old Termux Ubuntu for the AI CLIs was 2.7 GB; it was removed 2026-10-28.)
 */
object Storage {
    /** One conversation's footprint: its JSON (text) and its screen photos. */
    data class ConvSize(val id: String, val textBytes: Long, val photoBytes: Long, val photos: Int)

    fun sizeOf(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it) } ?: 0L else if (f.exists()) f.length() else 0L

    /** Sizes per conversation in [filesDir]/conversations (id = the JSON's name; photos in the dir of that name). */
    fun conversations(filesDir: File): List<ConvSize> {
        val root = File(filesDir, "conversations")
        return (root.listFiles { f -> f.extension == "json" } ?: emptyArray()).map { j ->
            val photos = File(root, j.nameWithoutExtension).listFiles { f -> f.extension == "jpg" } ?: emptyArray()
            ConvSize(j.nameWithoutExtension, j.length(), photos.sumOf { it.length() }, photos.size)
        }
    }

    /** Deletes conversation [id]'s screen photos (the chat shows "Photo not kept"); returns bytes freed. */
    fun deletePhotos(filesDir: File, id: String): Long {
        val d = File(File(filesDir, "conversations"), id.filter { it.isLetterOrDigit() || it == '-' })
        val freed = sizeOf(d); d.deleteRecursively(); return freed
    }

    /**
     * Auto-delete: screen photos and audit logs not changed for [days] days. Conversations' text is kept (it is
     * small and is what a resumed chat needs). Returns bytes freed. [now] for tests.
     */
    fun prune(filesDir: File, days: Int, now: Long = System.currentTimeMillis()): Long {
        if (days <= 0) return 0
        val cutoff = now - days * 86_400_000L
        var freed = 0L
        val photos = File(filesDir, "conversations").listFiles { f -> f.isDirectory }?.flatMap { it.listFiles()?.toList() ?: emptyList() } ?: emptyList()
        val audit = File(filesDir, "audit").listFiles { f -> f.extension == "jsonl" }?.toList() ?: emptyList()
        for (f in photos + audit) if (f.isFile && f.lastModified() < cutoff) { val n = f.length(); if (f.delete()) freed += n }
        File(filesDir, "conversations").listFiles { f -> f.isDirectory && f.list()?.isEmpty() == true }?.forEach { it.delete() }
        return freed
    }

    // ---------- root side ----------

    /** Real disk usage in KB (sparse saved spaces count only what they hold): one "<kb>\t<path>" line per file. */
    fun usageScript(paths: List<String>): String =
        paths.joinToString("\n") { p -> "printf '%s\\t%s\\n' \"${'$'}(du -k ${q(p)} 2>/dev/null | cut -f1)\" ${q(p)}" } + "\ntrue"

    /** [usageScript]'s output → path to bytes. */
    fun parseUsage(out: String): Map<String, Long> = out.lines().mapNotNull { l ->
        val t = l.split('\t', limit = 2); if (t.size == 2) t[0].trim().toLongOrNull()?.let { t[1] to it * 1024 } else null
    }.toMap()

    /** Bytes under [f] without following symlinks (a root filesystem links to /proc, /dev and absolute paths). */
    fun treeSize(f: File): Long {
        val p = f.toPath()
        if (!java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        java.nio.file.Files.walkFileTree(p, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size(); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        return total
    }

    /** The built-in Ubuntus (one root filesystem per CLI): "ubuntu" = everything, "logins" = the CLIs' own sign-in folders. */
    fun builtin(rootfss: List<File>): Map<String, Long> = mapOf(
        "ubuntu" to rootfss.sumOf { treeSize(it.parentFile ?: it) },
        "logins" to rootfss.sumOf { r -> listOf("root/.claude", "root/.claude.json", "root/.codex", "root/.gemini").sumOf { treeSize(File(r, it)) } })
}
