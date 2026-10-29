package com.rodinrescue.usb

import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Lifeline's own rescue system (B2): a small Debian live system (rescue-image/) that the PC boots from the phone.
 * The phone shows two read-only USB drives: the system itself, and a tiny raw "key drive" with the phone's SSH key and
 * this session's host key. The system copies itself into the PC's memory, gets an address from the phone's USB
 * tethering, reads the key drive and starts SSH, so the normal SSH rescue works on a PC whose own OS won't start.
 *
 * Why the phone makes the host key: it knows the fingerprint before connecting (pinned; no "trust this computer?"),
 * and the public image holds no secret, so one image fits every phone. Pure (no Android) so it is unit-tested.
 */
object RescueSystem {
    /** The image built by rescue-image/build.sh, published as a Lifeline GitHub release asset. */
    const val ISO_NAME = "lifeline-rescue.iso"
    const val VERSION = "20261029"
    const val URL = "https://github.com/techbysakh963/Lifeline/releases/download/rescue-20261029/lifeline-rescue-20261029.iso"
    /** Pinned in the app: a changed or swapped release file is refused even if the download site is. */
    const val SHA256 = "7ad66efa898a2b88e90ea130b2ebaafb273b4a4d23f66df65abff74e0917a621"
    val published get() = VERSION.isNotEmpty() && URL.startsWith("https://github.com/techbysakh963/Lifeline/releases/download/") && SHA256.length == 64

    /** Downloads the image to [dest] (via a .part file), checking it against [SHA256]; [progress] 0..1. */
    fun download(dest: java.io.File, progress: (Float) -> Unit) {
        check(published) { "the rescue system isn't published yet" }
        dest.parentFile?.mkdirs()
        val part = java.io.File(dest.path + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val c = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        try {
            // The release is on Lifeline's repo, which stays private until it's public: then this works without a login.
            check(c.responseCode != 404) { "it isn't downloadable yet (Lifeline's repository isn't public yet)" }
            check(c.responseCode == 200) { "the server answered ${c.responseCode}" }
            val total = c.contentLengthLong
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != SHA256) { part.delete(); error("the download doesn't match Lifeline's checksum (got $got)") }
            check(part.renameTo(dest)) { "couldn't save it" }
        } finally { c.disconnect(); part.delete() }
    }

    /** First line of the key drive; the image's lifeline-key script looks for exactly this. */
    const val MAGIC = "LIFELINE-RESCUE-KEY v1"
    /** 64 KiB: a whole number of 512-byte sectors, and far below the 1 MiB the image accepts as "tiny". */
    const val KEY_DRIVE_SIZE = 65536

    /** A host key for one session: the private key for the key drive (PEM, PKCS#8) and the public key to pin. */
    class HostKey(val privatePem: String, val public: PublicKey)

    /** ECDSA P-256: quick to make on a phone; OpenSSH reads PKCS#8 PEM host keys. */
    fun newHostKey(): HostKey {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded)
        return HostKey("-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n", kp.public)
    }

    /**
     * The key drive's bytes: the text lines, then zeros up to [KEY_DRIVE_SIZE]. [hostPublic] (public, so safe there) lets
     * the app resume a session after it was closed (2026-10-28: Back during the wait lost it); the image ignores that line.
     */
    fun keyDrive(authorizedKey: String, hostKeyPem: String, hostPublic: PublicKey? = null): ByteArray {
        require(authorizedKey.isNotBlank() && '\n' !in authorizedKey.trim()) { "one authorized key line" }
        val enc = Base64.getEncoder()
        val text = "$MAGIC\nauthorized_key ${enc.encodeToString((authorizedKey.trim() + "\n").toByteArray())}\n" +
            "host_key ${enc.encodeToString(hostKeyPem.toByteArray())}\n" +
            (hostPublic?.let { "host_pub ${it.algorithm} ${enc.encodeToString(it.encoded)}\n" } ?: "") + "end\n"
        val bytes = text.toByteArray()
        require(bytes.size < KEY_DRIVE_SIZE) { "key drive too big" }
        return bytes.copyOf(KEY_DRIVE_SIZE)
    }

    /** The session's host public key from a key drive written by [keyDrive], or null. */
    fun hostPublicOf(drive: ByteArray): PublicKey? = runCatching {
        val text = String(drive.takeWhile { it != 0.toByte() }.toByteArray())
        if (text.lineSequence().firstOrNull() != MAGIC) return null
        val (alg, b64) = text.lineSequence().first { it.startsWith("host_pub ") }.removePrefix("host_pub ").split(' ', limit = 2)
        java.security.KeyFactory.getInstance(alg).generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())))
    }.getOrNull()

    /** Added to the AI's instructions while it works inside the rescue system. */
    const val PROMPT_NOTE = "\n\nRESCUE SYSTEM: you are connected to the Lifeline rescue system, which the PC booted from the phone (it runs from the PC's memory, as root). " +
        "It is NOT the PC's own operating system: the PC's own disks are not mounted. Find them with `lsblk -f`, mount them READ-ONLY first " +
        "(`mount -o ro`) to look, and remount read-write only for an approved repair. To repair the PC's own Linux (fstab, GRUB, initramfs, " +
        "packages), mount its root (and /boot, /boot/efi) under /mnt and use `chroot /mnt` with /dev, /proc, /sys and /run bind-mounted. " +
        "Windows disks: ntfs-3g (read-only first), chntpw for its registry. Nothing you do here persists in the rescue system itself; " +
        "only changes on the PC's disks stay. When finished, tell the user to stop the drive in the phone's Boot tab and restart the PC."
}
