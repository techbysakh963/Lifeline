package com.rodinrescue.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's own sign-in to an official CLI inside the built-in Ubuntu. Lifeline only shows the sign-in link (and,
 * for Codex, the one-time code to type on OpenAI's page) and pipes back the code the user pastes; it never stores or
 * logs either, and the login is kept by the CLI itself in the app's private storage. Flows (checked 2026-10-28):
 *  - claude: `claude auth login --claudeai` prints a link and reads Claude's code from stdin;
 *  - codex: `codex login --device-auth` prints a link + a code the user types on OpenAI's page, then exits by itself;
 *  - agy: a full-screen terminal app, so it runs under `script` (a pseudo-terminal) with SSH_CONNECTION set, which
 *    selects its "paste the code" flow; Lifeline answers its terminal queries and picks "Google OAuth" (as
 *    Mobile-Harness does, MIT). Signed in = its credential file exists.
 */
class CliLogin(private val ubuntu: Ubuntu) {
    val cli = ubuntu.cli
    private var process: Process? = null

    data class Link(val url: String, val userCode: String? = null)

    /** [onLink] once the sign-in page is known; [onDone] with true when the CLI reports success. */
    fun start(onLink: (Link) -> Unit, onDone: (Boolean, String) -> Unit) {
        cancel()
        val p = when (cli) {
            Ubuntu.Cli.CLAUDE -> ubuntu.exec(listOf(cli.path, "auth", "login", "--claudeai"))
            Ubuntu.Cli.CODEX -> ubuntu.exec(listOf(cli.path, "login", "--device-auth"), mapOf("NO_COLOR" to "1"))
            Ubuntu.Cli.AGY -> {
                agyToken().delete()
                ubuntu.exec(listOf("/usr/bin/script", "-qfc", cli.path, "/dev/null"),
                    mapOf("SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1", "TERM" to "xterm-256color", "NO_COLOR" to "1"), AGY_WORKDIR)
            }
        }
        process = p
        Thread {
            val seen = StringBuilder(); val buf = ByteArray(8192); var linked = false; var menuDone = false; var replies = 0; var lastReply = 0
            while (true) {
                val n = try { p.inputStream.read(buf) } catch (e: Exception) { -1 }
                if (n < 0) break
                seen.append(String(buf, 0, n)); if (seen.length > 200_000) seen.delete(0, 100_000).also { lastReply = 0 }
                if (cli == Ubuntu.Cli.AGY) {
                    if (replies < 5 && seen.length - lastReply > 200 && seen.indexOf("\u001B[?u", lastReply) >= 0) {
                        write(p, TERMINAL_HANDSHAKE_REPLY); replies++; lastReply = seen.length
                    }
                    val clean = clean(seen)
                    if (!menuDone && clean.contains("Select login method", true) && clean.contains("1. Google OAuth", true)) { write(p, "\r\n"); menuDone = true }
                    if (agyToken().isFile) { onDone(true, "Signed in."); p.destroy(); break }
                }
                if (!linked) linkIn(cli, seen)?.let { linked = true; onLink(it) }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) process = null
            if (cli == Ubuntu.Cli.AGY) { if (!agyToken().isFile) onDone(false, lastLine(clean(seen))); return@Thread }
            val text = clean(seen)
            val ok = code == 0 && (("Login successful" in text) || cli == Ubuntu.Cli.CODEX && "Successfully logged in" in text)
            onDone(ok, if (ok) "Signed in." else lastLine(text))
        }.apply { isDaemon = true; start() }
    }

    /** Sends the code from the sign-in page (Claude, agy). False when no login is waiting for one. */
    fun sendCode(code: String): Boolean {
        val p = process ?: return false
        if (cli == Ubuntu.Cli.CODEX) return false
        val c = code.trim().takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it } ?: return false
        return write(p, c + if (cli == Ubuntu.Cli.AGY) "\r\n" else "\n")
    }

    val waiting get() = process != null
    fun cancel() { process?.destroy(); process = null }

    data class Status(val loggedIn: Boolean, val subscription: Boolean, val plan: String?)

    /** Login state, keeping only what Lifeline needs (no email, no organisation). API-key logins never count. */
    suspend fun status(): Status = when (cli) {
        Ubuntu.Cli.CLAUDE -> parseClaudeStatus(ubuntu.runArgs(listOf(cli.path, "auth", "status"), timeoutSec = 60).text) ?: Status(false, false, null)
        Ubuntu.Cli.CODEX -> parseCodexStatus(ubuntu.runArgs(listOf(cli.path, "login", "status"), timeoutSec = 60).text)
        Ubuntu.Cli.AGY -> agyToken().isFile.let { Status(it, it, null) }
    }

    suspend fun logout(): Boolean = when (cli) {
        Ubuntu.Cli.CLAUDE -> ubuntu.runArgs(listOf(cli.path, "auth", "logout"), timeoutSec = 60).code == 0
        Ubuntu.Cli.CODEX -> ubuntu.runArgs(listOf(cli.path, "logout"), timeoutSec = 60).code == 0
        // Under PRoot agy keeps its login in this file instead of a keyring; deleting it is its /logout (never read).
        Ubuntu.Cli.AGY -> agyToken().let { !it.exists() || it.delete() }
    }

    private fun agyToken() = ubuntu.host(AGY_TOKEN)
    private fun write(p: Process, s: String) = runCatching { p.outputStream.write(s.toByteArray()); p.outputStream.flush() }.isSuccess

    companion object {
        const val AGY_WORKDIR = "/root/work"
        /** Under PRoot agy keeps its login in this file instead of a keyring (Mobile-Harness found this; never read here). */
        const val AGY_TOKEN = "/root/.gemini/antigravity-cli/antigravity-oauth-token"
        private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"
        private val ANSI = Regex("\\u001B(?:\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9]|[>=])")
        private val CLAUDE_LINK = Regex("""https://\S*oauth/authorize\?\S+""")
        private val CODEX_LINK = Regex("""https://auth\.openai\.com/\S+""")
        private val CODEX_CODE = Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4,6}\b""")
        // agy paints the link as an OSC 8 hyperlink; its target is the whole URL in one piece.
        private val OSC8_GOOGLE = Regex("\\u001B]8;[^;\\u0007]*;(https://accounts\\.google\\.com/[^\\u0007\\u001B]+)")

        fun clean(s: CharSequence) = ANSI.replace(s, "").filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        fun linkIn(cli: Ubuntu.Cli, raw: CharSequence): Link? = when (cli) {
            Ubuntu.Cli.CLAUDE -> CLAUDE_LINK.find(clean(raw))?.value?.let { Link(it) }
            Ubuntu.Cli.CODEX -> clean(raw).let { t -> val u = CODEX_LINK.find(t)?.value; val c = u?.let { CODEX_CODE.find(t, t.indexOf(it) + it.length)?.value }; if (u != null && c != null) Link(u, c) else null }
            Ubuntu.Cli.AGY -> OSC8_GOOGLE.find(raw)?.groupValues?.get(1)?.takeIf { "client_id=" in it && "state=" in it }?.let { Link(it) }
        }

        fun parseClaudeStatus(out: String): Status? = runCatching {
            val o = Json.parseToJsonElement(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)).jsonObject
            val logged = o["loggedIn"]?.jsonPrimitive?.booleanOrNull == true
            Status(logged, logged && o["authMethod"]?.jsonPrimitive?.contentOrNull == "claude.ai", o["subscriptionType"]?.jsonPrimitive?.contentOrNull)
        }.getOrNull()

        /** `codex login status`: "Logged in using ChatGPT" vs "Logged in using an API key" (fails closed). */
        fun parseCodexStatus(out: String): Status {
            val t = clean(out).lowercase()
            val logged = "logged in" in t && "not logged in" !in t
            return Status(logged, logged && "chatgpt" in t, null)
        }

        /** The CLI's last message, with any link removed (it carries one-time values). */
        private fun lastLine(s: String) = s.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.replace(Regex("""https://\S+"""), "<link>")?.take(200) ?: "Sign-in ended."
    }
}
