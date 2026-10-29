package com.rodinrescue.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The AI's commands over USB serial (Epic B4): a logged-in shell on the PC's serial port (/dev/ttyACM0 there).
 * There is no protocol, just a terminal, so each command is framed by two marker lines the app waits for:
 *
 *   printf '%s\n' "__LL""<id>B"; { <command> } </dev/null 2>&1; printf '%s %s\n' "__LL""<id>E" "$?"
 *
 * The markers are written split ("__LL""…"), so the terminal echoing the command line can't fake them; the
 * command sits on its own lines, so a trailing "# comment" can't swallow the end marker; stdin is /dev/null, so
 * nothing waits for typing. A sudo password ([exec]'s stdin) goes through `read` with echo off, and is sent only
 * after the PC prints a "ready" marker: sent earlier, bash's line editor reads it as typeahead and echoes it
 * (caught by the test against a real terminal). It never shows on the PC's screen or in shell history.
 * stdout and stderr arrive merged (one terminal).
 *
 * [write] sends to the PC; feed what the PC sends to [onText] (from any thread).
 */
class SerialTransport(private val write: (String) -> Unit, private val alive: () -> Boolean) : CommandTransport {
    override val description = "USB serial (the PC's /dev/ttyACM0)"
    private val buf = StringBuilder()
    private val mutex = Mutex()
    @Volatile private var cancelled = false

    fun onText(t: String) = synchronized(buf) { buf.append(t); if (buf.length > MAX_BUFFER) buf.delete(0, buf.length - MAX_BUFFER) }

    override val isConnected get() = alive()

    /**
     * Makes the session quiet and plain (no echo, no pager, no colours, no history), then checks a shell answers.
     * stty must be pointed at the terminal: inside the frame its stdin is /dev/null (it failed silently before).
     */
    override suspend fun connect() {
        val r = exec("stty -echo </dev/tty 2>/dev/null; export TERM=dumb PAGER=cat GIT_PAGER=cat SYSTEMD_PAGER= SYSTEMD_COLORS=0; unset HISTFILE; PS1=''; PS2=''; id -u", 8_000)
        if (r.exitCode == null) throw java.io.IOException("No shell answered on the serial port. Log in on the PC first (USB tools › USB serial).")
        rootUser = r.stdout.trim().lines().lastOrNull()?.trim() == "0"
    }
    /** The serial login is root (then commands need no sudo). Set by [connect]. */
    var rootUser = false; private set

    override suspend fun exec(command: String, timeoutMs: Long, maxOutputBytes: Int, stdin: String?): ExecResult = mutex.withLock {
        cancelled = false
        val id = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        val begin = "__LL${id}B"; val end = "__LL${id}E"
        val text = frame(command, id, stdin != null)
        synchronized(buf) { buf.setLength(0) }
        val t0 = System.currentTimeMillis()
        write(text)
        var stdinPending = stdin
        while (true) {
            if (stdinPending != null && synchronized(buf) { clean(buf.toString()).split('\n').any { it.trim() == "__LL${id}R" } }) {
                write(stdinPending.trimEnd('\n') + "\n"); stdinPending = null
            }
            parse(synchronized(buf) { buf.toString() }, begin, end)?.let { (out, code) ->
                val (o, cut) = clip(out, maxOutputBytes)
                return@withLock ExecResult(o, "", code, truncated = cut, durationMs = System.currentTimeMillis() - t0)
            }
            val late = System.currentTimeMillis() - t0 > timeoutMs
            if (late || cancelled || !alive()) {
                val partial = clean(synchronized(buf) { buf.toString() }).substringAfter(begin + "\n", "")
                write("\u0003"); resync(id)
                val (o, cut) = clip(partial, maxOutputBytes)
                return@withLock ExecResult(o, "", null, timedOut = late, cancelled = !late, truncated = cut, durationMs = System.currentTimeMillis() - t0)
            }
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** After Ctrl+C: wait (briefly) until the shell answers again, so the next command starts clean. */
    private suspend fun resync(id: String) {
        val mark = "__LL${id}S"
        delay(200); synchronized(buf) { buf.setLength(0) }
        write("printf '%s\\n' \"__LL\"\"${id}S\"\n")
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 3_000) { if (synchronized(buf) { mark in clean(buf.toString()) }) return; delay(25) }
    }

    override fun cancelAll() { cancelled = true }
    override fun close() { cancelAll() }

    companion object {
        private const val MAX_BUFFER = 4 * 1024 * 1024

        /** The text typed into the PC's shell for [command]. */
        fun frame(command: String, id: String, withStdin: Boolean): String {
            val b = "printf '%s\\n' \"__LL\"\"${id}B\""; val e = "printf '%s %s\\n' \"__LL\"\"${id}E\""
            return if (!withStdin) "$b; {\n$command\n} </dev/null 2>&1; $e \"\$?\"\n"
            else "printf '%s\\n' \"__LL\"\"${id}R\"; IFS= read -r __LLIN; $b; { printf '%s\\n' \"\$__LLIN\" | {\n$command\n}; } 2>&1; __LLRC=\$?; unset __LLIN; $e \"\$__LLRC\"\n"
        }

        /** Output between the markers and the exit code, once the end marker has arrived. */
        fun parse(raw: String, begin: String, end: String): Pair<String, Int>? {
            val lines = clean(raw).split('\n')
            val b = lines.indexOfFirst { it.trim() == begin }.takeIf { it >= 0 } ?: return null
            val e = (b + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("$end ") } ?: return null
            val code = lines[e].trim().removePrefix("$end ").trim().toIntOrNull() ?: return null
            return lines.subList(b + 1, e).joinToString("\n") to code
        }

        private val ANSI = Regex("\u001B(\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|[@-Z\\\\-_])")
        /** Terminal output → plain text: no escape sequences, CR LF → LF. */
        fun clean(s: String) = ANSI.replace(s, "").replace("\r\n", "\n").replace("\r", "")

        private fun clip(s: String, max: Int): Pair<String, Boolean> =
            if (s.toByteArray().size <= max) s to false else String(s.toByteArray().copyOf(max)).trimEnd('�') to true
    }
}
