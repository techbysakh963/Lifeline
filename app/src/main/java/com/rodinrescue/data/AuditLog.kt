package com.rodinrescue.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AuditEntry(
    val ts: Long,
    val kind: String,        // user | assistant | tool_call | command | approval | verify | error | usage | stop
    val summary: String,
    val detail: String = "",
    val exitCode: Int? = null,
    val risk: String? = null
)

/** Append-only local JSONL audit log; one file per diagnostic session. Contents are redacted before being written. */
class AuditLog(dir: File) {
    private val root = File(dir, "audit").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var sessionId: String = newId()
        private set

    fun newSession(): String { sessionId = newId(); return sessionId }
    /** Continue appending to an existing session (resumed conversation). */
    fun resume(id: String) { sessionId = id }
    fun delete(id: String) { File(root, "$id.jsonl").delete() }

    fun append(e: AuditEntry) {
        synchronized(this) {
            File(root, "$sessionId.jsonl").appendText(json.encodeToString(e) + "\n")
        }
    }

    fun sessions(): List<String> = root.listFiles { f -> f.extension == "jsonl" }?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()

    fun read(id: String): List<AuditEntry> {
        val f = File(root, "$id.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { runCatching { json.decodeFromString<AuditEntry>(it) }.getOrNull() }
    }

    fun export(id: String): String = read(id).joinToString("\n") { "[${it.ts}] ${it.kind}: ${it.summary}\n${it.detail}" }

    private fun newId() = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
}
