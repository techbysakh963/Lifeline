package com.rodinrescue.data

import com.rodinrescue.ChatItem
import com.rodinrescue.ai.Msg
import com.rodinrescue.ai.TokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A saved rescue conversation: what the user saw ([chat]) plus what the model saw ([history]) so it can be resumed. */
@Serializable
data class Conversation(
    val id: String,              // same id as the audit-log session
    val deviceId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val chat: List<ChatItem>,
    val history: List<Msg>,
    val usage: TokenUsage = TokenUsage(),
    /** "ssh" (USB network) or "keyboard" (camera + USB keyboard): resume reopens the right screen and tools. */
    val mode: String = "ssh"
)

class ConversationStore(dir: File) {
    private val root = File(dir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun f(id: String) = File(root, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    @Synchronized fun save(c: Conversation) { val tmp = File(root, "${c.id}.tmp"); tmp.writeText(json.encodeToString(Conversation.serializer(), c)); tmp.renameTo(f(c.id)) }
    @Synchronized fun load(id: String): Conversation? = f(id).takeIf { it.exists() }?.let { runCatching { json.decodeFromString(Conversation.serializer(), it.readText()) }.getOrNull() }
    @Synchronized fun delete(id: String) { f(id).delete(); photoDir(id).deleteRecursively() }

    // Screen photos of keyboard-rescue conversations stay on the phone, next to the conversation (deleted with it).
    private fun photoDir(id: String) = File(root, id.filter { it.isLetterOrDigit() || it == '-' })
    /** Saves a photo for conversation [id]; returns its name for [ChatItem]s. */
    @Synchronized fun savePhoto(id: String, jpeg: ByteArray): String {
        val d = photoDir(id).apply { mkdirs() }
        val name = "%03d.jpg".format((d.list()?.size ?: 0) + 1)
        File(d, name).writeBytes(jpeg); return name
    }
    fun photoFile(id: String, name: String): File = File(photoDir(id), name.filter { it.isLetterOrDigit() || it == '.' })

    /** Lightweight listing (loads each file; fine for the few hundred conversations a phone will hold). */
    @Synchronized fun list(deviceId: String? = null): List<Conversation> =
        (root.listFiles { x -> x.extension == "json" } ?: emptyArray()).mapNotNull { load(it.nameWithoutExtension) }
            .filter { deviceId == null || it.deviceId == deviceId }.sortedByDescending { it.updatedAt }

    @Synchronized fun reassign(fromDevice: String, toDevice: String?) =
        list(fromDevice).forEach { save(it.copy(deviceId = toDevice)) }

    companion object {
        /** Plain-text transcript for copy/share. */
        fun transcript(c: Conversation, deviceName: String?): String = buildString {
            appendLine("${c.title}${deviceName?.let { " — $it" } ?: ""}")
            appendLine(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(c.createdAt)))
            c.chat.forEach { i ->
                appendLine()
                when (i) {
                    is ChatItem.UserMsg -> appendLine("You: ${i.text}" + if (i.photo != null) " [photo ${i.photo}]" else "")
                    is ChatItem.AssistantMsg -> appendLine("Lifeline: ${i.text}")
                    is ChatItem.ToolCallItem -> appendLine("[tool] ${i.name} ${i.args}")
                    is ChatItem.Cmd -> { appendLine("${if (i.privileged) "#" else "$"} ${i.command}  (exit ${i.result.exitCode ?: "-"})"); appendLine(i.result.stdout.trimEnd()); if (i.result.stderr.isNotBlank()) appendLine(i.result.stderr.trimEnd()) }
                    is ChatItem.ApprovalItem -> appendLine("${if (i.auto) "Auto-approved" else if (i.approved) "Approved" else "Rejected"}: ${i.req.command}")
                    is ChatItem.Notice -> appendLine("— ${i.text}")
                    is ChatItem.ScreenItem -> appendLine("[screen photo ${i.photo ?: ""}]" + if (i.text.isNotBlank()) "\n" + i.text.trimEnd() else "")
                    is ChatItem.KeysItem -> appendLine("[keys] ${i.label}" + (i.error?.let { " — FAILED: $it" } ?: ""))
                }
            }
        }

        fun titleFrom(chat: List<ChatItem>) = chat.firstNotNullOfOrNull { (it as? ChatItem.UserMsg)?.text }?.lines()?.first()?.take(60) ?: "Rescue session"
    }
}
