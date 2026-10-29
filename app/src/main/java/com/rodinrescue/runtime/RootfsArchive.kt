package com.rodinrescue.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * Unpacks a root filesystem tarball (Ubuntu Base) into a directory, safely:
 *  - an entry whose name is absolute or contains ".." is skipped;
 *  - nothing is written through a symlink (every parent inside [dest] must be a real directory);
 *  - symlinks are created as-is (they're resolved inside PRoot, against the guest root);
 *  - hard links become copies: Android refuses link() in app storage (seen on the POCO, 2026-10-28).
 * Device nodes and FIFOs are skipped (PRoot binds the real /dev).
 */
object RootfsArchive {
    data class Result(val files: Int, val links: Int, val copiedHardlinks: Int, val skipped: List<String>)

    fun extractTarGz(archive: File, dest: File): Result = GZIPInputStream(archive.inputStream().buffered(1 shl 16)).use { extractTar(it, dest) }

    fun extractTar(input: InputStream, dest: File): Result {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        var files = 0; var links = 0; var hard = 0; val skipped = mutableListOf<String>()
        val tar = TarArchiveInputStream(input)
        while (true) {
            val e = tar.nextEntry ?: break
            val target = resolve(root, e.name) ?: run { skipped += e.name; null } ?: continue
            if (target == root) continue
            if (!parentsAreRealDirs(root, target)) { skipped += e.name; continue }
            when {
                e.isDirectory -> { if (Files.isSymbolicLink(target)) { skipped += e.name; continue }; Files.createDirectories(target) }
                e.isSymbolicLink -> { Files.deleteIfExists(target); Files.createSymbolicLink(target, Paths.get(e.linkName)); links++; continue }
                e.isLink -> {
                    val from = resolve(root, e.linkName)
                    if (from == null || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || !parentsAreRealDirs(root, from)) { skipped += e.name; continue }
                    Files.deleteIfExists(target); Files.copy(from, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS); hard++
                    continue   // the copy keeps the original's mode
                }
                e.isFile -> {
                    Files.deleteIfExists(target)
                    Files.newOutputStream(target).use { tar.copyTo(it, 1 shl 16) }; files++
                }
                else -> { skipped += e.name; continue }   // char/block devices, FIFOs
            }
            setMode(target, e.mode)
        }
        return Result(files, links, hard, skipped)
    }

    /** Deletes [dir] and everything in it without following symlinks out of it. */
    fun deleteTree(dir: File) {
        val p = dir.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(p)) { Files.delete(p); return }
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                d.toFile().setWritable(true); Files.delete(d); return FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                d.toFile().setWritable(true); d.toFile().setExecutable(true); d.toFile().setReadable(true); return FileVisitResult.CONTINUE
            }
        })
    }

    internal fun resolve(root: Path, name: String): Path? {
        val clean = name.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean == ".") return root
        if (clean.startsWith("/") || clean.split('/').any { it == ".." }) return null
        val p = root.resolve(clean).normalize()
        return p.takeIf { it.startsWith(root) }
    }

    private fun parentsAreRealDirs(root: Path, target: Path): Boolean {
        var p = target.parent
        while (p != null && p != root) {
            if (Files.isSymbolicLink(p)) return false
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(p)
            p = p.parent
        }
        return true
    }

    /** Owner bits only matter here (one app user); keep exec bits and never drop the owner's read/write. */
    private fun setMode(p: Path, mode: Int) {
        if (Files.isSymbolicLink(p)) return
        val perms = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (mode and 0b001_000_000 != 0 || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) perms += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0b000_100_000 != 0) perms += PosixFilePermission.GROUP_READ
        if (mode and 0b000_001_000 != 0 || (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && mode and 0b000_100_000 != 0)) perms += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0b000_000_100 != 0) perms += PosixFilePermission.OTHERS_READ
        if (mode and 0b000_000_001 != 0 || (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && mode and 0b000_000_100 != 0)) perms += PosixFilePermission.OTHERS_EXECUTE
        runCatching { Files.setPosixFilePermissions(p, perms) }
    }
}
