package com.rodinrescue

import com.rodinrescue.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NewProvidersTest {
    private val tools = listOf(ToolDef("check_disk_space", "df", schema()))
    private val history = listOf(
        Msg.User("screen is black"),
        Msg.Assistant("checking", listOf(ToolCall("c1", "check_disk_space", "{}"))),
        Msg.Tool("c1", "check_disk_space", "/ 99% full")
    )

    @Test fun openAiRequestShape() {
        val r = OpenAiProvider({ "k" }, { "gpt-4.1" }).buildRequest("sys", history, tools)
        assertEquals("gpt-4.1", r["model"]!!.jsonPrimitive.content)
        val msgs = r["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("c1", msgs[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertNull("no DeepSeek-only fields", r["thinking"])
    }

    @Test fun geminiRequestShape() {
        val r = GeminiProvider({ "k" }, { "gemini-2.5-pro" }).buildRequest("sys", history, tools)
        assertEquals("sys", r["system_instruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val c = r["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "function"), c.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val fc = c[1].jsonObject["parts"]!!.jsonArray[1].jsonObject["functionCall"]!!.jsonObject
        assertEquals("check_disk_space", fc["name"]!!.jsonPrimitive.content)
        assertTrue(fc["args"] is JsonObject)
        val fr = c[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("check_disk_space", fr["name"]!!.jsonPrimitive.content)
        assertEquals(1, r["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.size)
    }

    @Test fun geminiParsesTextAndCalls() {
        val raw = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Disk is full. "},{"functionCall":{"name":"check_disk_space","args":{"path":"/"}}}]}}],
            "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
        val t = GeminiProvider({ "k" }, { "m" }).parseResponse(raw)
        assertEquals("Disk is full. ", t.text)
        assertEquals(1, t.toolCalls.size)
        assertEquals("check_disk_space", t.toolCalls[0].name)
        assertEquals("/", Json.parseToJsonElement(t.toolCalls[0].argumentsJson).jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(15, t.usage!!.total)
    }

    @Test(expected = ProviderException::class) fun geminiBlockedPromptThrows() {
        GeminiProvider({ "k" }, { "m" }).parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }

    /** Records every CLI call; answers with [reply] (by first argv after the binary) and lets a test drop answer files. */
    private class FakeHost(val reply: (List<String>, String?) -> String, val signedIn: Boolean = true) : CliHost {
        val root = java.nio.file.Files.createTempDirectory("rootfs").toFile()
        val calls = mutableListOf<Pair<List<String>, String?>>()
        override fun ready() = true
        override fun file(guestPath: String) = java.io.File(root, guestPath.removePrefix("/"))
        override suspend fun run(argv: List<String>, input: String?, timeoutSec: Long, cwd: String): com.rodinrescue.runtime.Ubuntu.Output {
            calls += argv to input
            return when {
                argv.drop(1).take(2) == listOf("auth", "status") -> com.rodinrescue.runtime.Ubuntu.Output(0, if (signedIn) """{"loggedIn":true,"authMethod":"claude.ai"}""" else """{"loggedIn":true,"authMethod":"api_key"}""")
                argv.drop(1).take(2) == listOf("login", "status") -> com.rodinrescue.runtime.Ubuntu.Output(0, if (signedIn) "Logged in using ChatGPT" else "Logged in using an API key")
                else -> com.rodinrescue.runtime.Ubuntu.Output(0, reply(argv, input))
            }
        }
    }

    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val withPhoto = listOf(Msg.User("help"), Msg.Tool("1", "look_at_screen", "photo taken", jpeg = photo))

    @Test fun builtInClaudeRunsTheCliWithNoToolsAndParsesItsAnswer() = kotlinx.coroutines.runBlocking {
        val h = FakeHost({ _, _ -> "Warning: something on stderr\n" + """{"type":"result","num_turns":1,"structured_output":{"say":"Looking","tool_calls":[{"name":"look_at_screen","arguments":{"purpose":"p"}}]},"usage":{"input_tokens":3,"cache_read_input_tokens":100,"output_tokens":9}}""" })
        val t = ClaudeCliProvider(h).complete("sys", listOf(Msg.User("hi")), KeyboardAgent.TOOLS)
        assertEquals("Looking", t.text); assertEquals("look_at_screen", t.toolCalls.single().name); assertTrue(t.toolCalls[0].id.startsWith("claude_"))
        assertEquals(103, t.usage!!.promptTokens)
        val (argv, input) = h.calls.last()
        assertEquals(com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE.path, argv[0])
        assertEquals("", argv[argv.indexOf("--tools") + 1]); assertTrue("--strict-mcp-config" in argv); assertTrue("--no-session-persistence" in argv)
        assertTrue(input!!.contains("[USER]\nhi"))
    }

    @Test fun builtInClaudeSendsThePhotoAsAnImageBlock() = kotlinx.coroutines.runBlocking {
        val h = FakeHost({ _, _ -> """{"type":"assistant"}""" + "\n" + """{"type":"result","structured_output":{"say":"GRUB","tool_calls":[]}}""" })
        assertEquals("GRUB", ClaudeCliProvider(h).complete("sys", withPhoto, KeyboardAgent.TOOLS).text)
        val (argv, input) = h.calls.last()
        assertEquals("stream-json", argv[argv.indexOf("--input-format") + 1])
        val msg = Json.parseToJsonElement(input!!.trim()).jsonObject["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(java.util.Base64.getEncoder().encodeToString(photo), msg[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun anApiKeyLoginIsRefusedBeforeAnyTurn() = kotlinx.coroutines.runBlocking {
        for (p in listOf(ClaudeCliProvider(FakeHost({ _, _ -> error("must not run a turn") }, signedIn = false)), CodexCliProvider(FakeHost({ _, _ -> error("must not run a turn") }, signedIn = false)))) {
            val e = runCatching { p.complete("s", listOf(Msg.User("x")), emptyList()) }.exceptionOrNull()
            assertTrue("${p.id}: $e", e is ProviderException && e.message!!.contains("subscription"))
        }
    }

    @Test fun builtInCodexIsReadOnlyAndReadsItsAnswerFile() = kotlinx.coroutines.runBlocking {
        lateinit var h: FakeHost
        h = FakeHost({ argv, _ ->
            assertTrue(java.io.File(h.file(argv[argv.indexOf("-i") + 1]).path).readBytes().contentEquals(photo))
            h.file(argv[argv.indexOf("-o") + 1]).writeText("""{"say":"ok","tool_calls":[{"name":"press_keys","arguments_json":"{\"keys\":\"down\"}"}]}""")
            "done"
        })
        val t = CodexCliProvider(h).complete("sys", withPhoto, KeyboardAgent.TOOLS)
        assertEquals("press_keys", t.toolCalls.single().name); assertEquals("""{"keys":"down"}""", t.toolCalls[0].argumentsJson)
        val argv = h.calls.last().first
        assertEquals(listOf("exec", "-s", "read-only"), argv.subList(1, 4))
        assertTrue(h.calls.last().second!!.contains("arguments_json"))
        assertTrue("its temp folder is removed", h.file("/tmp").listFiles().orEmpty().none { it.name.startsWith("lifeline-codex-") })
    }

    @Test fun builtInAgyPutsFlagsBeforeThePromptAndFindsTheAnswer() = kotlinx.coroutines.runBlocking {
        lateinit var h: FakeHost
        h = FakeHost({ argv, _ ->
            assertTrue("the photo waits in agy's folder", h.file("/root/work/screen.jpg").isFile)
            """{"result":"{\"say\":\"hi\",\"tool_calls\":[]}"}"""
        })
        h.file(com.rodinrescue.runtime.CliLogin.AGY_TOKEN).apply { parentFile!!.mkdirs(); writeText("t") }
        assertEquals("hi", AgyCliProvider(h).complete("sys", withPhoto, emptyList()).text)
        val argv = h.calls.last().first
        assertEquals("-p", argv[argv.size - 2]); assertTrue(argv.indexOf("--sandbox") < argv.indexOf("-p"))
        assertFalse("the photo is removed after the turn", h.file("/root/work/screen.jpg").exists())
        assertTrue(AgyCliProvider.capPrompt("x".repeat(200), 100).startsWith("[earlier conversation trimmed"))
    }

    @Test fun builtInProvidersHaveTheOldIds() {
        val h = FakeHost({ _, _ -> "" })
        assertEquals(listOf("claude", "codex", "geminicli"), listOf(ClaudeCliProvider(h).id, CodexCliProvider(h).id, AgyCliProvider(h).id))
    }
    @Test fun chosenModelAndEffortReachEachCli() = kotlinx.coroutines.runBlocking {
        val ok = """{"type":"result","structured_output":{"say":"ok","tool_calls":[]},"modelUsage":{"claude-opus-5-5":{}}}"""
        val h = FakeHost({ _, _ -> ok })
        val c = ClaudeCliProvider(h).apply { model = { "opus" }; effort = { "high" } }
        c.complete("s", listOf(Msg.User("x")), emptyList())
        h.calls.last().first.let { assertEquals("opus", it[it.indexOf("--model") + 1]); assertEquals("high", it[it.indexOf("--effort") + 1]) }
        assertEquals("claude-opus-5-5", c.usedModel)
        // a "model" that looks like a flag (or anything odd) is never passed on
        c.model = { "--dangerously-skip-permissions" }; c.effort = { "high; rm" }
        c.complete("s", listOf(Msg.User("x")), emptyList())
        h.calls.last().first.let { assertFalse("--model" in it); assertFalse("--effort" in it); assertFalse("--dangerously-skip-permissions" in it) }

        lateinit var hx: FakeHost
        hx = FakeHost({ argv, _ -> hx.file(argv[argv.indexOf("-o") + 1]).writeText("""{"say":"ok","tool_calls":[]}"""); "model: gpt-6-astra\nprovider: openai\nreasoning effort: xhigh\n" })
        val x = CodexCliProvider(hx).apply { model = { "gpt-6-astra" }; effort = { "xhigh" } }
        x.complete("s", listOf(Msg.User("x")), emptyList())
        hx.calls.last().first.let { assertEquals("gpt-6-astra", it[it.indexOf("-m") + 1]); assertTrue("model_reasoning_effort=\"xhigh\"" in it) }
        assertEquals("gpt-6-astra" to "xhigh", x.usedModel to x.usedEffort)

        val ha = FakeHost({ _, _ -> """{"say":"ok","tool_calls":[]}""" })
        ha.file(com.rodinrescue.runtime.CliLogin.AGY_TOKEN).apply { parentFile!!.mkdirs(); writeText("t") }
        AgyCliProvider(ha).apply { model = { "gemini-4-pro" }; effort = { "low" } }.complete("s", listOf(Msg.User("x")), emptyList())
        ha.calls.last().first.let { a -> assertTrue(a.indexOf("--model") < a.indexOf("-p")); assertEquals("gemini-4-pro", a[a.indexOf("--model") + 1]); assertEquals("low", a[a.indexOf("--effort") + 1]) }
        // agy 1.4.9 refuses --effort when the model name already has one (seen on the POCO, 2026-10-28)
        AgyCliProvider(ha).apply { model = { "gemini-3.8-flash-high" }; effort = { "low" } }.complete("s", listOf(Msg.User("x")), emptyList())
        ha.calls.last().first.let { a -> assertEquals("gemini-3.8-flash-high", a[a.indexOf("--model") + 1]); assertFalse("--effort" in a); assertFalse("--mode" in a) }
    }

    @Test fun readsEachCliModelList() {
        val cat = """{"models":[{"slug":"gpt-6-astra","display_name":"GPT-6-Astra","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"visibility":"list"},
            {"slug":"gpt-reserve","display_name":"GPT-Reserve","visibility":"hide"}]}"""
        assertEquals(listOf(CliProvider.ModelChoice("gpt-6-astra", "GPT-6-Astra", listOf("low", "high"))), CodexCliProvider.parseCatalog("warning: x\n$cat"))
        assertEquals("gpt-6-astra" to "high", CodexCliProvider.banner("OpenAI Codex v0.165.0\n--------\nmodel: gpt-6-astra\nreasoning effort: high\n"))
        assertEquals(listOf(CliProvider.ModelChoice("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"), CliProvider.ModelChoice("claude-sonnet-4-6", "Claude Sonnet 4.6 (Thinking)")),
            AgyCliProvider.parseModels("Fetching available models...\ngemini-3.8-flash-high\tGemini 3.8 Flash (High)\nclaude-sonnet-4-6\tClaude Sonnet 4.6 (Thinking)\n"))
    }
}
