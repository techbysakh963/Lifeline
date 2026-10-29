package com.rodinrescue.data

import com.rodinrescue.ChatItem
import com.rodinrescue.ai.ApprovalRequest
import com.rodinrescue.ai.Msg
import com.rodinrescue.security.Risk
import com.rodinrescue.transport.ExecResult

/**
 * Versioned, ordered, run-once upgrades of the data an older Lifeline / Rodin Rescue build left on the phone.
 *
 * The data version (stored in Settings, separate from the app's versionName) says which steps already ran.
 * On start, every step above it runs in order; the version is saved after EACH successful step, so a crash or a
 * failing step is retried on the next launch and never re-runs steps that finished. New steps go at the end with
 * the next number; never renumber or edit a released step.
 *
 *  1 → 2  pre-profile "last laptop" (Settings host/user + pinned key) → a device profile
 *  2 → 3  audit-only sessions from before saved conversations → read-only-titled "Older sessions" conversations
 *  3 → 4  drop trust (auto-trusted fingerprints, pinned host keys) that belongs to no saved device
 */
class Migrations(private val settings: Settings, private val devices: DeviceStore, private val conversations: ConversationStore, private val audit: AuditLog) {
    fun interface Step { fun run() }

    private val steps: List<Pair<Int, Step>> = listOf(
        2 to Step { legacyLaptopToProfile() },
        3 to Step { importAuditSessions() },
        4 to Step { pruneOrphanTrust() },
    )

    /** Runs pending steps; returns the data version reached. */
    fun run(): Int {
        var v = settings.dataVersion
        for ((target, step) in steps) {
            if (v >= target) continue
            try { step.run() } catch (e: Exception) { android.util.Log.w("Migrations", "step to v$target failed; retrying next launch", e); break }
            v = target; settings.dataVersion = v
        }
        return v
    }

    private fun legacyLaptopToProfile() {
        if (devices.all().isNotEmpty() || settings.user.isBlank() || settings.host.isBlank()) return
        val fps = settings.pinnedHostKey("${settings.host}:${settings.port}")?.split(",")?.filter { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        devices.save(DeviceProfile(DeviceStore.newId(), "${settings.user}'s computer", settings.user, settings.host, settings.port, "", fps, now, now))
    }

    private fun importAuditSessions() {
        audit.sessions().forEach { id ->
            if (conversations.load(id) != null) return@forEach
            fromAudit(id, audit.read(id))?.let { conversations.save(it) }
        }
    }

    private fun pruneOrphanTrust() {
        val all = devices.all()
        settings.pruneTrust(all.flatMap { it.fingerprints }.toSet())
        if (all.isEmpty() && settings.knownLaptopFps().isEmpty() && settings.host.isBlank()) settings.user = ""
    }

    companion object {
        const val CURRENT = 4

        /**
         * Rebuilds a conversation from an old audit log. The audit log is the only record those builds kept:
         * user/assistant text, tool calls, commands (logged as "cmd" then "result: <exit> in <ms>ms" + output),
         * approvals and errors. The model history keeps only user/assistant turns, which is enough to resume.
         */
        fun fromAudit(id: String, entries: List<AuditEntry>): Conversation? {
            if (entries.none { it.kind == "user" || it.kind == "assistant" }) return null
            val chat = mutableListOf<ChatItem>(ChatItem.Notice("Imported from an older version: commands and replies only."))
            val history = mutableListOf<Msg>()
            var pendingCmd: String? = null
            val resultRe = Regex("""^result: (\S+) in (\d+)ms$""")
            for (e in entries) when (e.kind) {
                "user" -> { chat += ChatItem.UserMsg(e.summary); history += Msg.User(e.summary) }
                "assistant" -> { chat += ChatItem.AssistantMsg(e.summary); history += Msg.Assistant(e.summary) }
                "tool_call" -> chat += ChatItem.ToolCallItem(e.summary, e.detail)
                "command", "verify" -> {
                    val m = resultRe.matchEntire(e.summary)
                    if (m == null && e.kind == "command") pendingCmd = e.summary
                    else {
                        val raw = if (e.kind == "verify") e.summary else pendingCmd ?: "(command)"
                        val priv = raw.startsWith("[sudo] ")
                        val exit = e.exitCode ?: m?.groupValues?.get(1)?.toIntOrNull()
                        chat += ChatItem.Cmd(raw.removePrefix("[sudo] "), priv,
                            ExecResult(e.detail, "", exit, timedOut = m?.groupValues?.get(1) == "timeout", cancelled = m?.groupValues?.get(1) == "cancelled",
                                durationMs = m?.groupValues?.get(2)?.toLongOrNull() ?: 0))
                        pendingCmd = null
                    }
                }
                "approval" -> {
                    val approved = e.summary.contains("APPROVED")
                    val risk = runCatching { Risk.valueOf(e.risk ?: "") }.getOrDefault(Risk.MODIFYING)
                    val cmd = e.detail.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    if (approved || e.summary.contains("REJECTED")) chat += ChatItem.ApprovalItem(ApprovalRequest(e.summary.substringBefore(' '), "Repair", cmd, risk, "", "", "", null), approved)
                    else chat += ChatItem.Notice("Blocked: $cmd (${e.summary})", error = true)
                }
                "error" -> chat += ChatItem.Notice(e.summary, error = true)
                "stop" -> chat += ChatItem.Notice("Stopped by user.")
                else -> {}  // usage
            }
            val first = entries.first().ts; val last = entries.last().ts
            return Conversation(id, null, ConversationStore.titleFrom(chat), first, last, chat, history)
        }
    }
}
