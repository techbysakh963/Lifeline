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
     * The live system inside an ISO, by its root folder: which boot word turns saved space on and which label it
     * looks for. Neither family uses saved space unless that word is on the boot line (their menus leave it out).
     */
    enum class Live(val word: String, val label: String) {
        /** Debian, Kali (live-boot: /live). */
        DEBIAN("persistence", "persistence"),
        /** Ubuntu, Mint and other Ubuntu-based systems (casper: /casper). */
        UBUNTU("persistent", "casper-rw"),
    }

    /** The ISO 9660 root folder's sector and size, from [head] (the primary volume descriptor at 32 KB), or null. */
    fun isoRoot(head: ByteArray): Pair<Long, Int>? {
        if (head.size < 32768 + 190 || head[32768].toInt() != 1 || String(head, 32769, 5, Charsets.US_ASCII) != "CD001") return null
        fun le(i: Int) = (0..3).fold(0L) { acc, k -> acc or ((head[32768 + i + k].toLong() and 0xFF) shl (8 * k)) }
        val lba = le(156 + 2); val size = le(156 + 10)
        return if (lba <= 0 || size !in 1..(1L shl 20)) null else lba to size.toInt()
    }

    fun rootDirScript(image: Image, root: Pair<Long, Int>) =
        "dd if=${q(image.path)} bs=2048 skip=${root.first} count=${(root.second + 2047) / 2048} 2>/dev/null | base64 -w0"

    /** [dir]: the ISO's root folder records. /live → Debian family, /casper → Ubuntu family, else null. */
    fun detectLive(dir: ByteArray): Live? {
        var i = 0
        while (i < dir.size) {
            val len = dir[i].toInt() and 0xFF
            if (len == 0) { i = (i / 2048 + 1) * 2048; continue }           // records never cross a sector
            if (i + 33 > dir.size || i + len > dir.size) break
            val isDir = (dir[i + 25].toInt() and 2) != 0
            val nameLen = dir[i + 32].toInt() and 0xFF
            val name = String(dir, i + 33, minOf(nameLen, dir.size - i - 33), Charsets.US_ASCII).substringBefore(';').uppercase()
            if (isDir && name == "LIVE") return Live.DEBIAN
            if (isDir && name == "CASPER") return Live.UBUNTU
            i += len
        }
        return null
    }

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

    /** GB the phone keeps free for itself when a saved space grows to its full size. */
    const val KEEP_FREE_GB = 2
    /** ext4's largest file with 4 KiB blocks (16 TiB). */
    const val MAX_SAVED_GB = 16 * 1024

    /**
     * The largest saved space for [availableBytes] free on the phone, in whole GB. The file is sparse, so this is how
     * far it may grow, not what it takes now; [KEEP_FREE_GB] stays free so a full saved space can't fill the phone.
     */
    fun maxSavedGb(availableBytes: Long): Int = ((availableBytes shr 30) - KEEP_FREE_GB).coerceIn(0, MAX_SAVED_GB.toLong()).toInt()

    /**
     * Creates [image]'s saved space: a sparse ext4 file of [gb] GB labelled for its [live] family ("persistence" with
     * persistence.conf "/ union" for Debian/Kali, "casper-rw" for Ubuntu/Mint; the conf is harmless there). Prints SAVED_OK. Lazy init leaves
     * the inode tables and journal unwritten (a sparse file reads them as zeros), so even a large one takes ~1 MB now.
     */
    fun createSavedScript(image: Image, gb: Int, live: Live = Live.DEBIAN): String {
        require(gb in 1..MAX_SAVED_GB) { "size out of range" }
        val f = image.savedPath; val sd = "${image.dir}/.saved"
        return """mkdir -p ${q(sd)} && chown 1023:1023 ${q(image.dir)} ${q(sd)} 2>/dev/null || true
[ -d ${q(sd)} ] || { echo ERR mkdir; exit 1; }
[ -e ${q(f)} ] && { echo ERR exists; exit 1; }
T=${'$'}(mktemp -d) || { echo ERR tmp; exit 1; }
printf '/ union\n' > "${'$'}T/persistence.conf"
truncate -s ${gb}G ${q(f)} && mke2fs -q -t ext4 -E lazy_itable_init=1,lazy_journal_init=1 -L ${live.label} -d "${'$'}T" ${q(f)} >/dev/null 2>&1 \
  && { rm -rf "${'$'}T"; chown 1023:1023 ${q(f)} 2>/dev/null; echo SAVED_OK; } || { rm -rf "${'$'}T"; rm -f ${q(f)}; echo ERR mke2fs; }
"""
    }

    fun deleteScript(path: String, dir: String = DIR): String {
        RescueDrive.checkPath(path); require(path.startsWith("$dir/")) { "outside the library" }
        return "rm -f ${q(path)} && echo DELETED"
    }
}
