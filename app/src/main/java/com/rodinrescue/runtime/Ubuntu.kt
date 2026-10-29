package com.rodinrescue.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lifeline's built-in Ubuntu for ONE subscription CLI ([cli]): a root filesystem in the app's private storage, run with
 * PRoot (no root, no Termux). Each CLI gets its own copy (files/runtime/<id>/ubuntu): Codex and agy keep shell and file
 * tools of their own, so a prompt injected through the PC's screen must not be able to read another CLI's sign-in or
 * change files another CLI runs (security review, 2026-10-28). About 80 MB per extra copy. PRoot is not an OS boundary
 * (same app user; /proc is the host's), so two CLIs never run at the same time either ([Live]).
 * PRoot and its loader are built from source (app/src/main/cpp) and shipped as lib*.so, so Android extracts them to
 * nativeLibraryDir, the one app folder it lets us run programs from. Programs run as the app's own user, so they have
 * the app's network (KernelSU's `su` has none). Measured on the POCO X7 Pro, 2026-10-28: start ~0.2 s.
 */
class Ubuntu(context: Context, val cli: Cli) : com.rodinrescue.ai.CliHost {
    private val lib = context.applicationInfo.nativeLibraryDir
    private val tmp = File(context.cacheDir, "proot-tmp-${cli.id}")
    val home: File = File(File(context.filesDir, "runtime"), cli.id)
    val rootfs: File = File(home, "ubuntu")

    init {
        // 2026-10-28: Claude Code was first installed in a shared files/runtime/ubuntu; it becomes Claude's own copy.
        val shared = File(context.filesDir, "runtime/ubuntu")
        if (cli == Cli.CLAUDE && shared.isDirectory && !rootfs.exists()) {
            home.mkdirs()
            if (shared.renameTo(rootfs)) {
                // Only Claude Code was ever installed there; drop anything of the other CLIs anyway, before Claude runs.
                (listOf("/home/codex", "/home/agy", "/root/.codex", "/root/.gemini", "/root/.lifeline-agy", "/root/.lifeline-codex") +
                    Cli.entries.filter { it != Cli.CLAUDE }.flatMap { listOf(it.path, "/" + it.marker) })
                    .forEach { runCatching { RootfsArchive.deleteTree(host(it)) } }
            }
        }
        listOf("runtime/empty", "runtime/downloads", "runtime/ubuntu.partial").forEach { runCatching { RootfsArchive.deleteTree(File(context.filesDir, it)) } }
    }

    val installed get() = File(rootfs, MARKER).isFile
    /** The installed version of [cli], or null when it isn't installed. */
    val version: String? get() = File(rootfs, cli.marker).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        ?.takeIf { host(cli.path).canExecute() }
    override fun ready() = installed && version != null
    override suspend fun run(argv: List<String>, input: String?, timeoutSec: Long, cwd: String) = runArgs(argv, input, timeoutSec, cwd = cwd)
    override fun file(guestPath: String) = host(guestPath)
    override fun spawn(argv: List<String>, guestEnv: Map<String, String>) = exec(argv, guestEnv)

    /** Runs [argv] directly inside Ubuntu (no shell, so no quoting). Only [BASE_ENV] + [guestEnv] reach it: no API keys. */
    fun exec(argv: List<String>, guestEnv: Map<String, String> = emptyMap(), cwd: String = "/root"): Process {
        check(installed) { "Built-in Ubuntu isn't installed" }
        tmp.mkdirs(); host(cwd).mkdirs()
        // Never two CLIs at once: /proc is the host's, and a running process of another CLI is a way into its files.
        Live.stopOthers(cli)
        return Live.add(cli, ProcessBuilder(prootArgs(lib, rootfs.path, cwd, guestEnv, argv)).redirectErrorStream(true).apply {
            environment().apply {
                clear()
                put("LD_LIBRARY_PATH", lib); put("PROOT_LOADER", "$lib/libprootloader.so")
                put("PROOT_TMP_DIR", tmp.path); put("PROOT_NO_SECCOMP", "1")
            }
        }.start())
    }

    /** Live CLI processes, so starting one CLI stops every process of the others (PRoot --kill-on-exit ends their children). */
    internal object Live {
        private val procs = mutableListOf<Pair<Cli, Process>>()
        @Synchronized fun add(cli: Cli, p: Process): Process { procs.removeAll { !it.second.isAlive }; procs += cli to p; return p }
        @Synchronized fun stopOthers(cli: Cli) {
            procs.filter { it.first != cli && it.second.isAlive }.forEach { (_, p) -> p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
            procs.removeAll { it.first != cli || !it.second.isAlive }
        }
        @Synchronized fun running(cli: Cli) = procs.count { it.first == cli && it.second.isAlive }
    }

    /** The host path of a guest path (e.g. "/tmp/x" -> files/runtime/<id>/ubuntu/tmp/x). */
    fun host(guestPath: String) = File(rootfs, guestPath.removePrefix("/"))

    data class Output(val code: Int, val text: String)

    /** Runs [argv] to the end with [input] on stdin. Cancelling the coroutine kills the process. */
    suspend fun runArgs(argv: List<String>, input: String? = null, timeoutSec: Long = 120, guestEnv: Map<String, String> = emptyMap(),
                        cwd: String = "/root"): Output = withContext(Dispatchers.IO) {
        val p = exec(argv, guestEnv, cwd)
        Thread { runCatching { p.outputStream.use { o -> if (input != null) o.write(input.toByteArray()) } } }.apply { isDaemon = true; start() }
        val out = StringBuffer()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 2_000_000) out.append(it).append('\n') } } }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        try {
            while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                ensureActive()
                if (System.currentTimeMillis() > deadline) { p.destroyForcibly(); reader.join(2000); return@withContext Output(124, out.toString()) }
            }
        } finally { if (p.isAlive) p.destroyForcibly() }
        reader.join(2000)
        Output(p.exitValue(), out.toString())
    }

    /** The official subscription CLIs Lifeline can run here. [id] matches the AI-brain provider id. */
    enum class Cli(val id: String, val title: String, val path: String, val marker: String) {
        CLAUDE("claude", "Claude Code", "/usr/local/bin/claude", ".lifeline-claude-version"),
        CODEX("codex", "Codex", "/usr/local/bin/codex", ".lifeline-codex-version"),
        AGY("geminicli", "Antigravity (agy)", "/usr/local/bin/agy", ".lifeline-agy-version");
        companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
    }

    companion object {
        /** The PRoot command line for [argv] (pure, so it's testable). Nothing of the host is bound but /dev, /proc and /sys. */
        fun prootArgs(lib: String, rootfs: String, cwd: String, guestEnv: Map<String, String>, argv: List<String>): List<String> =
            listOf("$lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", rootfs, "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", cwd, "/usr/bin/env", "-i") + (BASE_ENV + guestEnv).map { (k, v) -> "$k=$v" } + argv

        const val MARKER = ".lifeline-rootfs"
        val BASE_ENV = linkedMapOf(
            "HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8", "TERM" to "dumb", "TMPDIR" to "/tmp",
            // The CLIs can't open Android's browser; Lifeline shows the sign-in link itself.
            "BROWSER" to "true",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt", "NODE_EXTRA_CA_CERTS" to "/etc/ssl/certs/ca-certificates.crt",
            // Mobile-Harness: glibc's rseq registration fails under PRoot on Android kernels.
            "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
        )
    }
}
