package com.rodinrescue.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the built-in runtime from OFFICIAL sources only, each checked against the publisher's own checksum:
 *  - Ubuntu Base (cdimage.ubuntu.com, SHA256SUMS);
 *  - Claude Code's linux-arm64 binary (downloads.claude.ai, the checksum in Anthropic's release manifest; version from npm).
 * Nothing third-party is bundled or downloaded. No apt step: the CA bundle comes from Android's own trusted certificates.
 */
class UbuntuSetup(private val ubuntu: Ubuntu, private val http: OkHttpClient = defaultHttp()) {
    /** [fraction] is 0..1 for the current step, or null when unknown. */
    fun interface Progress { fun update(step: String, fraction: Float?) }

    suspend fun installUbuntu(progress: Progress) = withContext(Dispatchers.IO) {
        val dl = File(ubuntu.home, "downloads").apply { mkdirs() }
        progress.update("Checking Ubuntu's checksum list", null)
        val sums = text(UBUNTU_BASE + "SHA256SUMS")
        val (file, want) = newestUbuntuBase(sums) ?: error("no arm64 Ubuntu Base in Ubuntu's SHA256SUMS")
        val tgz = File(dl, file)
        download(UBUNTU_BASE + file, tgz, want) { progress.update("Downloading Ubuntu 24.04 (about 30 MB)", it) }
        progress.update("Unpacking Ubuntu", null)
        val partial = File(ubuntu.home, "ubuntu.partial")
        RootfsArchive.deleteTree(partial)
        val r = RootfsArchive.extractTarGz(tgz, partial)
        check(File(partial, "usr/bin/env").isFile) { "Ubuntu didn't unpack correctly (${r.skipped.size} entries skipped)" }
        configure(partial)
        File(partial, Ubuntu.MARKER).writeText("$file $want\n")
        RootfsArchive.deleteTree(ubuntu.rootfs)
        check(partial.renameTo(ubuntu.rootfs)) { "couldn't move Ubuntu into place" }
        tgz.delete()
        progress.update("Ubuntu is ready", 1f)
    }

    /** Installs (or updates) this Ubuntu's own CLI. */
    suspend fun install(progress: Progress) = when (ubuntu.cli) {
        Ubuntu.Cli.CLAUDE -> installClaude(progress)
        Ubuntu.Cli.CODEX -> installCodex(progress)
        Ubuntu.Cli.AGY -> installAgy(progress)
    }

    /** Claude Code: version from npm, binary + checksum from Anthropic's own release manifest. */
    private suspend fun installClaude(progress: Progress) = withContext(Dispatchers.IO) {
        check(ubuntu.installed) { "Install Ubuntu first" }
        progress.update("Finding the latest Claude Code", null)
        val version = Json.parseToJsonElement(text(NPM_CLAUDE_LATEST)).jsonObject["version"]?.jsonPrimitive?.content
            ?.takeIf { VERSION.matches(it) } ?: error("couldn't read Claude Code's latest version")
        val base = "$CLAUDE_RELEASES$version"
        val sha = parseClaudeChecksum(text("$base/manifest.json")) ?: error("Anthropic's manifest has no linux-arm64 checksum")
        val staged = staging(Ubuntu.Cli.CLAUDE, version)
        download("$base/linux-arm64/claude", staged, sha, "SHA-256") { progress.update("Downloading Claude Code $version (about 280 MB)", it) }
        place(Ubuntu.Cli.CLAUDE, staged, version, progress)
    }

    /** Codex: OpenAI's latest GitHub release, the musl arm64 build; checksum = GitHub's sha256 digest of the asset. */
    private suspend fun installCodex(progress: Progress) = withContext(Dispatchers.IO) {
        check(ubuntu.installed) { "Install Ubuntu first" }
        progress.update("Finding the latest Codex", null)
        val release = text(CODEX_LATEST)
        val (version, url, sha) = parseCodexRelease(release) ?: error("OpenAI's latest Codex release has no checked arm64 build")
        // Codex runs every tool call (MCP too) through this helper; without it the long session's tools fail (POCO, 2026-10-28).
        val (_, hostUrl, hostSha) = parseCodexRelease(release, CODEX_HOST_ENTRY) ?: error("OpenAI's latest Codex release has no checked arm64 code-mode host")
        val hostTgz = File(ubuntu.home, "downloads/codex-host-$version.tar.gz").apply { parentFile?.mkdirs() }
        download(hostUrl, hostTgz, hostSha, "SHA-256") { progress.update("Downloading Codex's tool runner (about 27 MB)", it) }
        val hostBin = ubuntu.host(CODEX_HOST_PATH)
        val hostStaged = File(hostBin.parentFile!!.apply { mkdirs() }, ".${hostBin.name}-$version.part")
        try { check(extractOne(hostTgz, CODEX_HOST_ENTRY, hostStaged)) { "the archive has no $CODEX_HOST_ENTRY" } } finally { hostTgz.delete() }
        hostStaged.setExecutable(true, false); hostStaged.setReadable(true, false)
        check(hostStaged.renameTo(hostBin)) { "couldn't move Codex's tool runner into place" }
        val tgz = File(ubuntu.home, "downloads/codex-$version.tar.gz").apply { parentFile?.mkdirs() }
        download(url, tgz, sha, "SHA-256") { progress.update("Downloading Codex $version (about 100 MB)", it) }
        val staged = staging(Ubuntu.Cli.CODEX, version)
        progress.update("Unpacking Codex", null)
        try { check(extractOne(tgz, CODEX_ENTRY, staged)) { "the Codex archive has no $CODEX_ENTRY" } } finally { tgz.delete() }
        place(Ubuntu.Cli.CODEX, staged, version, progress)
    }

    /** Antigravity (agy): Google's own updater manifest (download URL + SHA-512), linux arm64 build. */
    private suspend fun installAgy(progress: Progress) = withContext(Dispatchers.IO) {
        check(ubuntu.installed) { "Install Ubuntu first" }
        progress.update("Finding the latest Antigravity CLI", null)
        val (version, url, sha512) = parseAgyManifest(text(AGY_MANIFEST)) ?: error("Google's Antigravity manifest couldn't be read")
        val tgz = File(ubuntu.home, "downloads/agy-$version.tar.gz").apply { parentFile?.mkdirs() }
        download(url, tgz, sha512, "SHA-512") { progress.update("Downloading Antigravity $version (about 40 MB)", it) }
        val staged = staging(Ubuntu.Cli.AGY, version)
        progress.update("Unpacking Antigravity", null)
        try { check(extractOne(tgz, "antigravity", staged)) { "the Antigravity archive has no 'antigravity'" } } finally { tgz.delete() }
        place(Ubuntu.Cli.AGY, staged, version, progress)
    }

    private fun staging(cli: Ubuntu.Cli, version: String) = ubuntu.host(cli.path).let { File(it.parentFile!!.apply { mkdirs() }, ".${it.name}-$version.part") }

    /** Moves the checked binary into place and confirms it starts. */
    private suspend fun place(cli: Ubuntu.Cli, staged: File, version: String, progress: Progress) {
        staged.setExecutable(true, false); staged.setReadable(true, false)
        check(staged.renameTo(ubuntu.host(cli.path))) { "couldn't move ${cli.title} into place" }
        progress.update("Checking ${cli.title} runs", null)
        val out = ubuntu.runArgs(listOf(cli.path, "--version"), timeoutSec = 60)
        check(out.code == 0 && version in out.text) { "${cli.title} didn't start: ${out.text.take(300)}" }
        File(ubuntu.rootfs, cli.marker).writeText(version)
        progress.update("${cli.title} $version is ready", 1f)
    }

    /** Copies the single regular file [name] out of a .tar.gz into [to]. */
    private fun extractOne(tgz: File, name: String, to: File): Boolean =
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(java.util.zip.GZIPInputStream(tgz.inputStream().buffered(1 shl 16))).use { t ->
            while (true) {
                val e = t.nextEntry ?: return false
                if (e.isFile && e.name.removePrefix("./") == name) { to.outputStream().use { t.copyTo(it, 1 shl 16) }; return true }
            }
            @Suppress("UNREACHABLE_CODE") false
        }

    /** DNS, hosts and a CA bundle made from Android's own trusted certificates. */
    private fun configure(root: File) {
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        val certs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts").map(::File).firstOrNull { (it.list()?.size ?: 0) > 0 }
            ?: error("Android's trusted certificates weren't found")
        val bundle = File(root, "etc/ssl/certs/ca-certificates.crt").apply { parentFile?.mkdirs() }
        bundle.writeText(certs.listFiles()!!.sortedBy { it.name }.joinToString("") { pemOnly(it.readText()) })
        File(root, "tmp").apply { mkdirs(); setWritable(true, false) }
    }

    private fun text(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code} for $url" }
        r.body!!.string()
    }

    private suspend fun download(url: String, to: File, sha256: String, onFraction: (Float?) -> Unit) = download(url, to, sha256, "SHA-256", onFraction)

    private suspend fun download(url: String, to: File, digest: String, algorithm: String, onFraction: (Float?) -> Unit) {
        val ctx = currentCoroutineContext()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} for $url" }
            val body = r.body!!; val total = body.contentLength()
            val md = MessageDigest.getInstance(algorithm)
            var got = 0L; var last = 0L
            body.byteStream().use { i -> to.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    ctx.ensureActive()
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); md.update(buf, 0, n); got += n
                    if (got - last > 1_000_000) { last = got; onFraction(if (total > 0) got.toFloat() / total else null) }
                }
            } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(digest, ignoreCase = true)) { to.delete(); error("checksum mismatch for $url") }
        }
    }

    companion object {
        const val UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
        const val NPM_CLAUDE_LATEST = "https://registry.npmjs.org/@anthropic-ai/claude-code/latest"
        const val CLAUDE_RELEASES = "https://downloads.claude.ai/claude-code-releases/"
        const val CODEX_LATEST = "https://api.github.com/repos/openai/codex/releases/latest"
        const val CODEX_ENTRY = "codex-aarch64-unknown-linux-musl"
        const val CODEX_HOST_ENTRY = "codex-code-mode-host-aarch64-unknown-linux-musl"
        /** Where codex looks for its code-mode host: next to itself. */
        const val CODEX_HOST_PATH = "/usr/local/bin/codex-code-mode-host"
        const val AGY_MANIFEST = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"
        private val VERSION = Regex("""^\d+\.\d+\.\d+$""")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        private val BASE_NAME = Regex("""^ubuntu-base-24\.04(?:\.(\d+))?-base-arm64\.tar\.gz$""")

        /** The newest 24.04 point release for arm64 in Ubuntu's SHA256SUMS, as (file name, sha256). */
        fun newestUbuntuBase(sums: String): Pair<String, String>? = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 2 && it[0].matches(Regex("^[0-9a-fA-F]{64}$")) }
            .mapNotNull { (sha, name) -> val f = name.removePrefix("*"); BASE_NAME.find(f)?.let { Triple(f, sha.lowercase(), it.groupValues[1].toIntOrNull() ?: 0) } }
            .maxByOrNull { it.third }?.let { it.first to it.second }

        fun parseClaudeChecksum(manifest: String): String? = runCatching {
            Json.parseToJsonElement(manifest).jsonObject["platforms"]!!.jsonObject["linux-arm64"]!!.jsonObject["checksum"]!!.jsonPrimitive.content
        }.getOrNull()?.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }?.lowercase()

        /** (version, download URL, sha256) of the arm64 musl Codex in OpenAI's latest GitHub release. */
        fun parseCodexRelease(json: String, entry: String = CODEX_ENTRY): Triple<String, String, String>? = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            val tag = o["tag_name"]!!.jsonPrimitive.content
            val version = tag.removePrefix("rust-v").takeIf { VERSION.matches(it) } ?: return null
            val a = o["assets"]!!.jsonArray.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == "$entry.tar.gz" }
            val url = a["browser_download_url"]!!.jsonPrimitive.content
            val sha = a["digest"]!!.jsonPrimitive.content.removePrefix("sha256:")
            if (!url.startsWith("https://github.com/openai/codex/releases/download/") || !sha.matches(Regex("^[0-9a-f]{64}$"))) return null
            Triple(version, url, sha)
        }.getOrNull()

        /** (version, download URL, sha512) from Google's Antigravity updater manifest. */
        fun parseAgyManifest(json: String): Triple<String, String, String>? = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            val v = o["version"]!!.jsonPrimitive.content.takeIf { VERSION.matches(it) } ?: return null
            val url = o["url"]!!.jsonPrimitive.content
            val sha = o["sha512"]!!.jsonPrimitive.content.lowercase()
            if (!url.startsWith("https://storage.googleapis.com/antigravity-public/") || !sha.matches(Regex("^[0-9a-f]{128}$"))) return null
            Triple(v, url, sha)
        }.getOrNull()

        /** Android's cacerts files are a PEM block followed by a text dump; keep only the PEM blocks. */
        fun pemOnly(file: String): String = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(file).joinToString("") { it.value + "\n" }
    }
}
