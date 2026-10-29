package com.rodinrescue.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A minimal MCP server (Streamable HTTP, plain JSON replies) on 127.0.0.1, for the CLI running in Lifeline's Ubuntu.
 * Random port, a fresh bearer token per session (other apps on the phone can reach loopback, but not the token, which
 * lives in the app's private storage). [call] runs on the connection's own thread and may wait as long as the user
 * takes to answer an approval card.
 */
class McpServer(private val tools: List<ToolDef>, private val call: suspend (name: String, args: JsonObject) -> JsonObject) {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val url: String get() = "http://127.0.0.1:$port/mcp"

    init {
        Thread({ while (!socket.isClosed) { val s = runCatching { socket.accept() }.getOrNull() ?: break; Thread({ serve(s) }, "mcp-conn").apply { isDaemon = true; start() } } }, "mcp-accept")
            .apply { isDaemon = true; start() }
    }

    fun close() { runCatching { socket.close() } }

    /** The `--mcp-config` file for Claude Code: one HTTP server named "lifeline". */
    fun claudeConfig(): String = buildJsonObject {
        putJsonObject("mcpServers") { putJsonObject(NAME) {
            put("type", "http"); put("url", url); putJsonObject("headers") { put("Authorization", "Bearer $token") }
        } }
    }.toString()

    private fun serve(s: Socket) = s.use {
        val inp = s.getInputStream().buffered()
        fun reply(code: Int, body: String? = null) {
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "X"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                (if (body != null) "Content-Type: application/json\r\n" else "") + "\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        }
        val request = line(inp) ?: return
        val headers = HashMap<String, String>()
        while (true) { val h = line(inp) ?: return; if (h.isEmpty()) break; if (headers.size < 64) headers[h.substringBefore(":").trim().lowercase()] = h.substringAfter(":").trim() }
        if (!same(headers["authorization"].orEmpty(), "Bearer $token")) return reply(401)
        if (!request.startsWith("POST ")) return reply(405)
        val len = headers["content-length"]?.toIntOrNull() ?: return reply(411)
        if (len !in 0..MAX_BODY) return reply(413)
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) return; o += n } }
        val m = runCatching { Json.parseToJsonElement(String(body)).jsonObject }.getOrNull() ?: return reply(400)
        val id = m["id"] ?: return reply(202)   // a notification
        reply(200, Json.encodeToString(JsonObject.serializer(), handle(id, m)))
    }

    internal fun handle(id: JsonElement, m: JsonObject): JsonObject {
        val params = m["params"] as? JsonObject
        fun ok(result: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
        return when (m["method"]?.jsonPrimitive?.contentOrNull) {
            "initialize" -> ok(buildJsonObject {
                put("protocolVersion", params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull ?: "2025-06-18")
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", NAME); put("version", "1") }
            })
            "ping" -> ok(buildJsonObject {})
            "tools/list" -> ok(buildJsonObject { putJsonArray("tools") { tools.forEach { t -> addJsonObject {
                put("name", t.name); put("description", t.description); put("inputSchema", t.parameters)
            } } } })
            "tools/call" -> {
                val name = params?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
                ok(if (tools.none { it.name == name }) text("Unknown tool '$name'.", error = true)
                   else runCatching { runBlocking { call(name, args) } }.getOrElse { text("Tool failed: ${it.message}", error = true) })
            }
            else -> buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", -32601); put("message", "Method not found") } }
        }
    }

    companion object {
        const val NAME = "lifeline"
        private const val MAX_BODY = 4 * 1024 * 1024

        /** The name Claude Code gives a tool of this server. */
        fun claudeName(tool: String) = "mcp__${NAME}__$tool"

        /** A tool result with text and, optionally, a JPEG photo. */
        fun text(s: String, jpeg: ByteArray? = null, error: Boolean = false) = buildJsonObject {
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", s) }
                jpeg?.let { addJsonObject { put("type", "image"); put("mimeType", "image/jpeg"); put("data", it.base64()) } }
            }
            if (error) put("isError", true)
        }

        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        /** One header line (CRLF or LF), at most 8 KB; null at end of stream. */
        private fun line(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 8192) return null
            }
        }
    }
}
