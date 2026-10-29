package com.rodinrescue.usb

import com.rodinrescue.usb.RescueDrive.q

/**
 * The Boot tab's image library: a visible folder on the phone (Internal storage › Lifeline › Boot) holding ISOs and
 * disk images, plus a hidden ".saved" folder with each image's optional saved space (persistence). Pure script
 * builders and parsers; scripts run as root (the app can't list shared storage by itself).
 *
 * /data/media/0 is the same storage as /sdcard, under the path the kernel's USB-drive thread may read (SELinux).
 */
object BootLibrary {
    const val DIR = "/data/media/0/Lifeline/Boot"
    const val SAVED = "$DIR/.saved"
    /** What the user sees for [DIR]. */
    const val SHOWN_AS = "Internal storage › Lifeline › Boot"
    private val IMAGE_EXT = setOf("iso", "img", "raw")

    /** /storage/emulated/0/… and /sdcard/… → /data/media/0/… (same file, path the kernel may read). */
    fun kernelPath(p: String): String = when {
        p.startsWith("/storage/emulated/0/") -> "/data/media/0/" + p.removePrefix("/storage/emulated/0/")
        p.startsWith("/sdcard/") -> "/data/media/0/" + p.removePrefix("/sdcard/")
        else -> p
    }

    /** [dir]: the library folder (tests use a temp folder). */
    data class Image(val name: String, val bytes: Long, val savedBytes: Long?, val dir: String = DIR) {
        val path get() = "$dir/$name"
        val savedPath get() = "$dir/.saved/$name.saved"
    }

    /** Lists images: one "size<TAB>name" line per image, "#saved<TAB>size<TAB>name" per saved space. */
    fun listScript(dir: String = DIR) = """mkdir -p ${q("$dir/.saved")} 2>/dev/null && chown 1023:1023 ${q(dir.substringBeforeLast('/'))} ${q(dir)} ${q("$dir/.saved")} 2>/dev/null
cd ${q(dir)} 2>/dev/null || exit 0
for f in *; do [ -f "${'$'}f" ] && printf '%s\t%s\n' "${'$'}(stat -c %s "${'$'}f")" "${'$'}f"; done
cd ${q("$dir/.saved")} 2>/dev/null && for f in *.saved; do [ -f "${'$'}f" ] && printf '#saved\t%s\t%s\n' "${'$'}(stat -c %s "${'$'}f")" "${'$'}{f%.saved}"; done
true
"""

    fun parseList(out: String, dir: String = DIR): List<Image> {
        val saved = HashMap<String, Long>(); val imgs = mutableListOf<Pair<String, Long>>()
        for (line in out.lines()) {
            val p = line.split('\t')
            when {
                p.size == 3 && p[0] == "#saved" -> p[1].toLongOrNull()?.let { saved[p[2]] = it }
                p.size == 2 -> p[0].toLongOrNull()?.let { if (p[1].substringAfterLast('.', "").lowercase() in IMAGE_EXT) imgs += p[1] to it }
            }
        }
        return imgs.sortedBy { it.first.lowercase() }.map { (n, b) -> Image(n, b, saved[n], dir) }
    }

    /** How the image should be shown to the PC. */
    enum class Mode { DISK, CD }

    /**
     * From the image's first 34 KB: a hybrid ISO or disk image (MBR with a partition, or GPT) boots as a USB disk;
     * a plain ISO 9660 image (e.g. a Windows installer) needs CD mode. Anything else: disk.
     */
    fun detectMode(head: ByteArray): Mode {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        val mbr = at(510) == 0x55 && at(511) == 0xAA && (0 until 4).any { at(446 + 16 * it + 4) != 0 }
        val gpt = head.size >= 520 && String(head, 512, 8, Charsets.US_ASCII) == "EFI PART"
        val iso = head.size >= 32774 && String(head, 32769, 5, Charsets.US_ASCII) == "CD001"
        return if (mbr || gpt || !iso) Mode.DISK else Mode.CD
    }

    /** Prints the first 34816 bytes base64-encoded (enough for MBR, GPT header and the ISO 9660 volume descriptor). */
    fun headScript(image: Image) = "head -c 34816 ${q(image.path)} | base64 -w0"

    /**
     * Moves [source] (anywhere in the user's storage) into the library. Same storage → a rename, instant even for
     * a 6 GB ISO. Refuses to overwrite. Prints MOVED <name> or an error.
     */
    fun moveInScript(source: String, dir: String = DIR): String {
        RescueDrive.checkPath(source)
        val src = kernelPath(source); val name = src.substringAfterLast('/')
        return """mkdir -p ${q(dir)} || { echo ERR mkdir; exit 1; }
[ -f ${q(src)} ] || { echo ERR missing; exit 1; }
[ -e ${q("$dir/$name")} ] && { echo ERR exists; exit 1; }
mv ${q(src)} ${q("$dir/$name")} && echo "MOVED ${'$'}(basename ${q(name)})" || echo ERR move
"""
    }

    /**
     * Creates [image]'s saved space: a sparse ext4 file of [gb] GB labelled "persistence" with persistence.conf
     * "/ union" — what Debian/Kali/Ubuntu live-boot looks for on any attached drive. Prints SAVED_OK.
     */
    fun createSavedScript(image: Image, gb: Int): String {
        require(gb in 1..256) { "size out of range" }
        val f = image.savedPath; val sd = "${image.dir}/.saved"
        return """mkdir -p ${q(sd)} && chown 1023:1023 ${q(image.dir)} ${q(sd)} 2>/dev/null || true
[ -d ${q(sd)} ] || { echo ERR mkdir; exit 1; }
[ -e ${q(f)} ] && { echo ERR exists; exit 1; }
T=${'$'}(mktemp -d) || { echo ERR tmp; exit 1; }
printf '/ union\n' > "${'$'}T/persistence.conf"
truncate -s ${gb}G ${q(f)} && mke2fs -q -t ext4 -L persistence -d "${'$'}T" ${q(f)} >/dev/null 2>&1 \
  && { rm -rf "${'$'}T"; chown 1023:1023 ${q(f)} 2>/dev/null; echo SAVED_OK; } || { rm -rf "${'$'}T"; rm -f ${q(f)}; echo ERR mke2fs; }
"""
    }

    fun deleteScript(path: String, dir: String = DIR): String {
        RescueDrive.checkPath(path); require(path.startsWith("$dir/")) { "outside the library" }
        return "rm -f ${q(path)} && echo DELETED"
    }
}
