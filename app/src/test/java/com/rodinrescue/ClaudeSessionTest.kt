package com.rodinrescue

import com.rodinrescue.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The long Claude session against a fake `claude`: it speaks stream-json on stdin/stdout and calls the app's MCP server
 * over real loopback HTTP with the token from the config file, like Claude Code does.
 */
class ClaudeSessionTest {
    /** A fake claude process; [script] handles one user message, calling tools through [Fake.tool]. */
    class Fake(val host: Host, val argv: List<String>, val env: Map<String, String>, val script: Fake.(JsonObject) -> Unit,
               val onEof: Fake.(String) -> Unit = {}) : Process() {
        private val toProc = PipedOutputStream(); private val procIn = PipedInputStream(toProc, 1 shl 20)
        private val procOut = PipedOutputStream(); private val fromProc = PipedInputStream(procOut, 1 shl 20)
        val received = mutableListOf<JsonObject>()
        @Volatile var dead = false
        var turns = 0
        /** Codex-style: keep writing after stdin closed, then [finish]. */
        @Volatile var keepOutput = false
        fun finish() { dead = true; runCatching { procOut.close() } }
        init {
            Thread {
                val all = StringBuilder()
                runCatching { procIn.bufferedReader().forEachLine { l -> all.appendLine(l)
                    runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull()?.let { m -> received += m; script(m) } } }
                onEof(all.toString().trimEnd())
                if (!keepOutput) { dead = true; runCatching { procOut.close() } }
            }.apply { isDaemon = true; start() }
        }
        // PipedInputStream calls the pipe broken once the thread that last wrote has ended: write from one long-lived thread.
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        fun emit(o: JsonObject) { writer.submit { procOut.write((o.toString() + "\n").toByteArray()); procOut.flush() }.get() }
        fun say(t: String) = emit(buildJsonObject { put("type", "assistant"); putJsonObject("message") { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", t) } } } })
        fun done(text: String = "") = emit(buildJsonObject { put("type", "result"); put("subtype", "success"); put("is_error", false); put("result", text)
            putJsonObject("usage") { put("input_tokens", 5); put("cache_read_input_tokens", 1000); put("output_tokens", 20) }; putJsonObject("modelUsage") { putJsonObject("claude-sonnet-5-5") {} } })
        /** One MCP tools/call over HTTP, as Claude Code makes it. */
        /** Where this CLI finds the MCP server and its token, as each real CLI does. */
        private fun endpoint(): Pair<String, String> = when {
            argv[0].endsWith("codex") -> argv.first { it.startsWith("mcp_servers.lifeline.url=") }.substringAfter("=").trim('"') to
                "Bearer " + env.getValue(CodexSessionProvider.TOKEN_ENV)
            else -> {
                val f = if (argv[0].endsWith("agy")) AgySessionProvider.MCP_CONFIG else ClaudeSessionProvider.CONFIG
                val cfg = Json.parseToJsonElement(host.file(f).readText()).jsonObject["mcpServers"]!!.jsonObject["lifeline"]!!.jsonObject
                (cfg["url"] ?: cfg["serverUrl"])!!.jsonPrimitive.content to cfg["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content
            }
        }
        fun tool(name: String, args: JsonObject): JsonObject {
            val (url, auth) = endpoint()
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.readTimeout = 0
            c.setRequestProperty("Authorization", auth)
            c.outputStream.write(buildJsonObject { put("jsonrpc", "2.0"); put("id", 7); put("method", "tools/call")
                putJsonObject("params") { put("name", name); put("arguments", args) } }.toString().toByteArray())
            return Json.parseToJsonElement(c.inputStream.readBytes().decodeToString()).jsonObject["result"]!!.jsonObject
        }
        override fun getOutputStream() = toProc
        override fun getInputStream() = fromProc
        override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (!dead) Thread.sleep(10); return 0 }
        override fun exitValue() = if (dead) 0 else throw IllegalThreadStateException()
        override fun destroy() { runCatching { toProc.close() }; runCatching { procOut.close() }; dead = true }
    }

    class Host(val onEof: Fake.(String) -> Unit = {}, val script: Fake.(JsonObject) -> Unit) : CliHost {
        val root = java.nio.file.Files.createTempDirectory("rootfs").toFile()
        val spawned = mutableListOf<Pair<List<String>, Map<String, String>>>()
        val procs = mutableListOf<Fake>()
        var failSpawn = false
        val runs = mutableListOf<List<String>>()
        override fun ready() = true
        override fun file(guestPath: String) = java.io.File(root, guestPath.removePrefix("/"))
        override suspend fun run(argv: List<String>, input: String?, timeoutSec: Long, cwd: String): com.rodinrescue.runtime.Ubuntu.Output {
            runs += argv
            if (argv.drop(1).take(2) == listOf("login", "status")) return com.rodinrescue.runtime.Ubuntu.Output(0, "Logged in using ChatGPT")
            return if (argv.drop(1).take(2) == listOf("auth", "status")) com.rodinrescue.runtime.Ubuntu.Output(0, """{"loggedIn":true,"authMethod":"claude.ai"}""")
            else com.rodinrescue.runtime.Ubuntu.Output(0, """{"type":"result","num_turns":1,"structured_output":{"say":"stateless answer","tool_calls":[]}}""")
        }
        override fun spawn(argv: List<String>, guestEnv: Map<String, String>): Process {
            if (failSpawn) throw java.io.IOException("no proot")
            spawned += argv to guestEnv
            return Fake(this, argv, guestEnv, script, onEof).also { procs += it }
        }
    }

    val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)
    private fun text(m: JsonObject) = m["message"]!!.jsonObject["content"]!!.jsonArray.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString()

    /** Looks at the screen once per message, then answers. */
    private val lookThenAnswer: Fake.(JsonObject) -> Unit = { m ->
        Thread {
            say("Let me look.")
            val r = tool("look_at_screen", buildJsonObject { put("purpose", "see") })
            val content = r["content"]!!.jsonArray
            say("I saw: " + content[0].jsonObject["text"]!!.jsonPrimitive.content + " +" + content.count { it.jsonObject["type"]!!.jsonPrimitive.content == "image" } + " image")
            done()
        }.start()
    }

    @Test fun oneSessionCarriesToolCallsBackAndForthAndKeepsGoingAcrossMessages() = runBlocking {
        val h = Host(script = lookThenAnswer)
        val p = ClaudeSessionProvider(ClaudeCliProvider(h), h)
        val history = mutableListOf<Msg>(Msg.User("what's on screen?"))
        val t1 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("Let me look.", t1.text)
        val call = t1.toolCalls.single(); assertEquals("look_at_screen", call.name); assertEquals("see", Json.parseToJsonElement(call.argumentsJson).jsonObject["purpose"]!!.jsonPrimitive.content)
        history += Msg.Assistant(t1.text, t1.toolCalls); history += Msg.Tool(call.id, call.name, "GRUB menu", jpeg = photo)
        val t2 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("I saw: GRUB menu +1 image", t2.text); assertTrue(t2.toolCalls.isEmpty())
        assertEquals(1005, t2.usage!!.promptTokens); assertEquals(20, t2.usage!!.completionTokens)
        history += Msg.Assistant(t2.text)

        // the next message goes to the SAME process, and only the new text is sent
        history += Msg.User("and now?")
        val t3 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals(1, h.spawned.size)
        assertEquals("and now?", text(h.procs[0].received.last()))
        assertEquals("look_at_screen", t3.toolCalls.single().name)
        p.close()

        val (argv, env) = h.spawned[0]
        assertEquals("", argv[argv.indexOf("--tools") + 1])
        assertEquals(KeyboardAgent.TOOLS.joinToString(",") { "mcp__lifeline__" + it.name }, argv[argv.indexOf("--allowedTools") + 1])
        assertTrue(argv[argv.indexOf("--system-prompt") + 1].startsWith("SYS") && "--strict-mcp-config" in argv && "--input-format" in argv)
        assertEquals("900000", env["MCP_TOOL_TIMEOUT"])
        assertFalse("config with the token is removed", h.file(ClaudeSessionProvider.CONFIG).exists())
    }

    @Test fun anUnfinishedTurnRestartsWithARecordOfTheConversation() = runBlocking {
        val h = Host(script = lookThenAnswer)
        val p = ClaudeSessionProvider(ClaudeCliProvider(h), h)
        val history = mutableListOf<Msg>(Msg.User("first"))
        val t1 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        history += Msg.Assistant(t1.text, t1.toolCalls)   // STOP: the agent never answered the call
        p.abandonTurn()
        history += Msg.User("second")
        p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals(2, h.spawned.size)
        val first = text(h.procs[1].received.first())
        assertTrue(first, "[USER] first" in first && "[USER, new message] second" in first && "you called look_at_screen" in first)
        p.close()
    }

    @Test fun changedDynamicLinesAreSentAsANoteAndOtherToolsRestart() = runBlocking {
        val h = Host { m -> Thread { say("ok"); done() }.start() }
        val p = ClaudeSessionProvider(ClaudeCliProvider(h), h)
        val base = "x".repeat(500)
        val history = mutableListOf<Msg>(Msg.User("a"))
        history += Msg.Assistant(p.complete(base + "\n\nPHOTOS: off", history, KeyboardAgent.TOOLS).text)
        history += Msg.User("b")
        p.complete(base + "\n\nPHOTOS: on", history, KeyboardAgent.TOOLS)
        assertEquals(1, h.spawned.size)
        assertTrue(text(h.procs[0].received.last()).startsWith("[Lifeline app: part of your instructions changed. From here on it reads:]\nPHOTOS: on"))
        history += Msg.Assistant("ok"); history += Msg.User("c")
        p.complete(base, history, Tools.defs)
        assertEquals("other tools = new session", 2, h.spawned.size)
        p.close()
    }

    @Test fun aSessionThatCantStartFallsBackToOneRunPerStep() = runBlocking {
        val h = Host { }.apply { failSpawn = true }
        val p = ClaudeSessionProvider(ClaudeCliProvider(h), h)
        val t = p.complete("SYS", listOf(Msg.User("hi")), KeyboardAgent.TOOLS)
        assertEquals("stateless answer", t.text)
        assertTrue(p.status.startsWith("Long session failed"))
        assertTrue(h.runs.any { "-p" in it && "--json-schema" in it })
    }

    @Test fun connectionTestDoesOneRealToolRoundTrip() = runBlocking {
        val h = Host { Thread { val r = tool("check_connection", buildJsonObject {}); say(r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content); done() }.start() }
        val msg = ClaudeSessionProvider(ClaudeCliProvider(h), h).testConnection()
        assertTrue(msg, "Long session works" in msg); assertEquals(1, h.spawned.size); assertTrue(h.procs[0].dead)
    }

    @Test fun offMeansOneRunPerStep() = runBlocking {
        val h = Host(script = lookThenAnswer)
        val t = ClaudeSessionProvider(ClaudeCliProvider(h), h) { false }.complete("SYS", listOf(Msg.User("hi")), KeyboardAgent.TOOLS)
        assertEquals("stateless answer", t.text); assertTrue(h.spawned.isEmpty())
    }

    @Test fun mcpServerRefusesCallsWithoutTheToken() {
        val s = McpServer(KeyboardAgent.TOOLS) { _, _ -> McpServer.text("ran") }
        try {
            val c = URL(s.url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Authorization", "Bearer wrong")
            c.outputStream.write("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"press_keys","arguments":{}}}""".toByteArray())
            assertEquals(401, c.responseCode)
            val list = s.handle(JsonPrimitive(1), buildJsonObject { put("method", "tools/list") })["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals(KeyboardAgent.TOOLS.map { it.name }, list.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            val unknown = s.handle(JsonPrimitive(2), buildJsonObject { put("method", "tools/call"); putJsonObject("params") { put("name", "rm_rf") } })
            assertEquals(true, unknown["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        } finally { s.close() }
    }

    // ── Codex: one `codex exec` per message, resumed by thread id ──
    @Test fun codexResumesItsThreadEachMessageAndToolsComeOverMcp() = runBlocking {
        val h = Host(onEof = { prompt ->
            keepOutput = true
            Thread {
                emit(buildJsonObject { put("type", "thread.started"); put("thread_id", "th-1") })
                if ("screen" in prompt) {
                    val r = tool("look_at_screen", buildJsonObject { put("purpose", "see") })
                    emit(buildJsonObject { put("type", "item.completed"); putJsonObject("item") { put("type", "agent_message")
                        put("text", "saw " + r["content"]!!.jsonArray.size + " parts") } })
                } else emit(buildJsonObject { put("type", "item.completed"); putJsonObject("item") { put("type", "agent_message"); put("text", "remembered: $prompt") } })
                emit(buildJsonObject { put("type", "turn.completed"); putJsonObject("usage") { put("input_tokens", 900); put("cached_input_tokens", 800); put("output_tokens", 7) } })
                finish()
            }.start()
        }) { }
        h.file(com.rodinrescue.runtime.CliLogin.AGY_TOKEN).apply { parentFile.mkdirs(); writeText("x") }
        h.file(com.rodinrescue.runtime.UbuntuSetup.CODEX_HOST_PATH).apply { parentFile.mkdirs(); writeText("#!"); setExecutable(true) }
        val p = CodexSessionProvider(CodexCliProvider(h), h)
        val history = mutableListOf<Msg>(Msg.User("what's on the screen?"))
        val t1 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        val call = t1.toolCalls.single(); assertEquals("look_at_screen", call.name)
        history += Msg.Assistant(t1.text, t1.toolCalls); history += Msg.Tool(call.id, call.name, "GRUB", jpeg = photo)
        val t2 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("saw 2 parts", t2.text); assertEquals(800, t2.usage!!.cachedTokens)
        history += Msg.Assistant(t2.text); history += Msg.User("which entry?")
        val t3 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("remembered: which entry?", t3.text)

        val (first, env) = h.spawned[0]; val second = h.spawned[1].first
        assertFalse("resume" in first); assertEquals(listOf("resume", "th-1", "-"), second.takeLast(3))
        CodexSessionProvider.OFF.forEach { assertTrue(it, it in first) }
        assertTrue(first.any { it.startsWith("developer_instructions=\"SYS") } && "read-only" in first)
        assertTrue(first.contains("mcp_servers.lifeline.default_tools_approval_mode=\"approve\""))
        assertFalse("token never on the command line", first.any { env.getValue(CodexSessionProvider.TOKEN_ENV) in it })
        // the saved thread is deleted with the session
        val saved = h.file("/root/.codex/sessions/2026/10/28/rollout-th-1.jsonl").apply { parentFile.mkdirs(); writeText("{}") }
        p.close(); assertFalse(saved.exists())
    }

    @Test fun tomlStringsAreEscaped() = assertEquals("\"a\\\"b\\nc\\\\\"", CodexSessionProvider.toml("a\"b\nc\\"))

    // ── agy: one stream-json process, its own event format ──
    @Test fun agyKeepsOneProcessSendsInstructionsOnceAndAllowsOnlyLifelinesTools() = runBlocking {
        val h = Host(script = { m ->
            val text = m["message"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            Thread {
                fun step(type: String, state: String, delta: String? = null) = emit(buildJsonObject { put("event", "step_update")
                    putJsonObject("step_update") { put("step_type", type); put("state", state); delta?.let { put("text_delta", it) } } })
                if ("screen" in text) { step("agent_response", "ACTIVE", "Look"); step("agent_response", "DONE", "ing."); tool("look_at_screen", buildJsonObject { put("purpose", "p") }) }
                step("agent_response", "DONE", "ok")
                // agy's usage is a running total for the session (seen on the POCO): 50 more input tokens per turn
                turns++
                emit(buildJsonObject { put("event", "result"); putJsonObject("result") { put("status", "SUCCESS"); put("response", "ok")
                    putJsonObject("usage") { put("input_tokens", 50 * turns); put("output_tokens", 3 * turns); put("cache_read_tokens", 40 * turns) } } })
            }.start()
        })
        h.file(com.rodinrescue.runtime.CliLogin.AGY_TOKEN).apply { parentFile.mkdirs(); writeText("x") }
        val agy = AgyCliProvider(h).apply { model = { "gemini-3.8-flash-high" }; effort = { "medium" } }
        val p = AgySessionProvider(agy, h)
        val history = mutableListOf<Msg>(Msg.User("what's on the screen?"))
        val t1 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("Looking.", t1.text); assertEquals("look_at_screen", t1.toolCalls.single().name)
        val perms = Json.parseToJsonElement(h.file(AgyCliProvider.SETTINGS).readText()).jsonObject["permissions"]!!.jsonObject
        assertEquals(KeyboardAgent.TOOLS.map { "mcp(lifeline/${it.name})" }, perms["allow"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(perms["deny"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("command(*)", "write_file(*)")))
        history += Msg.Assistant(t1.text, t1.toolCalls); history += Msg.Tool(t1.toolCalls[0].id, "look_at_screen", "photo")
        assertEquals("ok", p.complete("SYS", history, KeyboardAgent.TOOLS).text)
        history += Msg.Assistant("ok"); history += Msg.User("again")
        val t3 = p.complete("SYS", history, KeyboardAgent.TOOLS)
        assertEquals("only this turn's tokens", 50, t3.usage!!.promptTokens); assertEquals(40, t3.usage!!.cachedTokens)
        assertNull("effort not passed, so not shown as in use", agy.usedEffort); assertEquals("gemini-3.8-flash-high", agy.usedModel)
        assertEquals(1, h.spawned.size)
        val sent = h.procs[0].received.map { it["message"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(sent[0].startsWith("[Lifeline app: your instructions for this whole conversation]\nSYS")); assertEquals("again", sent[1])
        val argv = h.spawned[0].first
        assertTrue("--effort dropped: the model name has one", "--effort" !in argv && "gemini-3.8-flash-high" in argv)
        p.close()
        assertFalse("token removed", "Bearer" in h.file(AgySessionProvider.MCP_CONFIG).readText())
    }
}
