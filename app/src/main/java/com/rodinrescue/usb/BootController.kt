package com.rodinrescue.usb

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.rodinrescue.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Boot tab actions on the phone (root). One mutex: the drive's configfs is never changed by two flows at once
 * (same lesson as the keyboard, D5). Scripts come from [RescueDrive] and [BootLibrary].
 */
object BootController {
    data class Status(val supported: Boolean, val on: Boolean, val image: String?, val writable: String?)

    private val lock = Mutex()
    private suspend fun su(cmd: String, timeoutSec: Long = 30) = RootShell.run(cmd, timeoutSec, mountMaster = true)

    suspend fun status(): Status = lock.withLock {
        val lines = su(RescueDrive.statusScript()).output.lines()
        when (lines.firstOrNull()?.trim()) {
            "MS_UNSUPPORTED" -> Status(false, false, null, null)
            "MS_ON" -> Status(true, true, lines.getOrNull(1)?.trim()?.ifEmpty { null }, lines.getOrNull(2)?.trim()?.ifEmpty { null })
            else -> Status(true, false, null, null)
        }
    }

    suspend fun list(): List<BootLibrary.Image> = BootLibrary.parseList(su(BootLibrary.listScript()).output)

    suspend fun mode(img: BootLibrary.Image): BootLibrary.Mode {
        val b64 = su(BootLibrary.headScript(img)).output.trim()
        val head = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrDefault(ByteArray(0))
        return BootLibrary.detectMode(head)
    }

    /** Shows [img] to the PC. Returns null on success, or a message the user can act on. */
    suspend fun start(img: BootLibrary.Image, mode: BootLibrary.Mode, withSaved: Boolean): String? = lock.withLock {
        // Leftover of the removed Lifeline boot menu (debug builds of 2026-10-16 kept its 10 MB drive here).
        su("rm -rf /data/media/0/Lifeline/.menu")
        val luns = listOfNotNull(RescueDrive.Lun(img.path, cdrom = mode == BootLibrary.Mode.CD, name = RescueDrive.driveName(img.name)),
            img.savedPath.takeIf { withSaved && img.savedBytes != null }?.let { RescueDrive.Lun(it, readOnly = false, name = "Saved space") })
        enable(luns)
    }

    /** Shows [luns] to the PC (lun.0 = the one it boots). null on success, else a message the user can act on. */
    suspend fun startLuns(luns: List<RescueDrive.Lun>): String? = lock.withLock { enable(luns) }

    private suspend fun enable(luns: List<RescueDrive.Lun>): String? {
        val out = su(RescueDrive.enableScript(luns), 45).output
        return when {
            "MS_ON" in out -> null
            "MS_UNSUPPORTED" in out -> "This phone can't act as a USB drive: its kernel has no USB storage function."
            "MS_NO_IMAGE" in out -> "The image file is gone. Pull down to refresh the list."
            "MS_NO_UDC" in out -> "Plug the phone into the PC first."
            else -> "Couldn't start the drive: " + out.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        }
    }

    suspend fun stop(): String = lock.withLock { su(RescueDrive.disableScript(), 45).output }

    suspend fun createSaved(img: BootLibrary.Image, gb: Int): String? {
        val out = su(BootLibrary.createSavedScript(img, gb), 120).output
        return if ("SAVED_OK" in out) null else when {
            "ERR exists" in out -> "This image already has saved space."
            else -> "Couldn't create the saved space (${out.lines().lastOrNull { it.isNotBlank() }})."
        }
    }

    suspend fun delete(path: String): Boolean = "DELETED" in su(BootLibrary.deleteScript(path)).output

    /**
     * Adds a picked file to the library. A file in the phone's own storage is MOVED (a rename: instant, no extra
     * space); anything else (a cloud or SD-card file) is copied in. Returns null on success or a message.
     */
    suspend fun add(ctx: Context, uri: Uri, progress: (Long, Long) -> Unit): String? {
        val path = withContext(Dispatchers.IO) { realPath(ctx.contentResolver, uri) } ?: rootPath(uri)
        if (path != null && (path.startsWith("/storage/emulated/0/") || path.startsWith("/sdcard/"))) {
            val out = su(BootLibrary.moveInScript(path), 60).output
            return when {
                "MOVED" in out -> null
                "ERR exists" in out -> "An image with this name is already in the library."
                else -> "Couldn't move it (${out.trim()})."
            }
        }
        return copyIn(ctx, uri, progress)
    }

    /** Best effort: the real file path behind a picker URI (Downloads, Files, media). */
    private fun realPath(cr: ContentResolver, uri: Uri): String? = runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            "com.android.externalstorage.documents" ->
                id.split(':', limit = 2).takeIf { it.size == 2 && it[0] == "primary" }?.let { "/storage/emulated/0/" + it[1] }
            "com.android.providers.downloads.documents" -> when {
                id.startsWith("raw:") -> id.removePrefix("raw:")
                id.startsWith("msf:") -> dataColumn(cr, MediaStore.Files.getContentUri("external"), id.removePrefix("msf:"))
                else -> null
            }
            "com.android.providers.media.documents" -> dataColumn(cr, MediaStore.Files.getContentUri("external"), id.substringAfter(':'))
            else -> null
        }
    }.getOrNull()

    /**
     * The app may not look up other apps' files in MediaStore (scoped storage), so Downloads' "msf:123" ids don't
     * resolve above; root's `content query` can. Found on the POCO: without this, ISOs were copied, not moved.
     */
    private suspend fun rootPath(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.substringAfter(':') ?: return null
        if (!id.all(Char::isDigit) || id.isEmpty()) return null
        val out = su("content query --uri content://media/external/file --projection _data --where \"_id=$id\"").output
        return Regex("_data=(/.+)$", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.trim()
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(cr: ContentResolver, base: Uri, id: String): String? =
        cr.query(base, arrayOf(MediaStore.MediaColumns.DATA), "${MediaStore.MediaColumns._ID}=?", arrayOf(id), null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private suspend fun copyIn(ctx: Context, uri: Uri, progress: (Long, Long) -> Unit): String? = withContext(Dispatchers.IO) {
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null } ?: return@withContext "Couldn't read the picked file."
        val (display, size) = name
        if (display.substringAfterLast('.', "").lowercase() !in setOf("iso", "img", "raw")) return@withContext "Pick an .iso or .img file."
        val dest = "${BootLibrary.DIR}/${display.replace('/', '_')}"
        runCatching { RescueDrive.checkPath(dest) }.getOrElse { return@withContext "This file name can't be used." }
        val tmp = "$dest.part"
        val p = ProcessBuilder("su", "-M", "-c", "mkdir -p ${RescueDrive.q(BootLibrary.DIR)} && [ ! -e ${RescueDrive.q(dest)} ] && cat > ${RescueDrive.q(tmp)}")
            .redirectErrorStream(true).start()
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                p.outputStream.use { out ->
                    val buf = ByteArray(1 shl 20); var done = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, size) }
                }
            }
            if (p.waitFor() != 0) return@withContext "An image with this name is already in the library."
            val fin = RootShell.run("mv ${RescueDrive.q(tmp)} ${RescueDrive.q(dest)} && chown 1023:1023 ${RescueDrive.q(dest)} && echo OK", 30, mountMaster = true).output
            if ("OK" in fin) null else "Couldn't finish the copy."
        } catch (e: Exception) {
            p.destroy(); RootShell.run("rm -f ${RescueDrive.q(tmp)}", 10, mountMaster = true)
            "Copy failed: ${e.message}"
        }
    }
}
