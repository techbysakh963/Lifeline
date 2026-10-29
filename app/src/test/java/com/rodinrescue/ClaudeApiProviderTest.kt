package com.rodinrescue

import com.rodinrescue.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Claude API brain against a fake Anthropic server that streams like the real one (SSE). */
class ClaudeApiProviderTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)

    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }

    /** thinking (signed) → text → tool_use(look_at_screen), stop_reason tool_use. */
    private val lookReply = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":100,"cache_creation_input_tokens":0}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need to see the screen."}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG-abc"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Looking."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"look_at_screen","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"purpose\":\"see\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":30}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun doneReply(stop: String = "end_turn") = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"GRUB is showing."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":4}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun <T> withServer(vararg bodies: String, block: suspend (MockWebServer, ClaudeApiProvider) -> T): T = runBlocking {
        val server = MockWebServer()
        bodies.forEach { server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(it)) }
        server.start()
        try { block(server, ClaudeApiProvider({ "sk-test" }, { "claude-opus-5-5" }, server.url("/").toString().trimEnd('/'))) }
        finally { server.shutdown() }
    }

    @Test fun firstTurnParsesStreamAndSendsTheRightRequest() = withServer(lookReply) { server, p ->
        val deltas = StringBuilder()
        val turn = p.complete("SYS", listOf(Msg.User("PC won't boot", jpeg = jpeg)), KeyboardAgent.TOOLS) { deltas.append(it) }
        assertEquals("Looking.", turn.text); assertEquals("Looking.", deltas.toString())
        assertEquals(listOf(ToolCall("toolu_1", "look_at_screen", """{"purpose":"see"}""")), turn.toolCalls)
        assertEquals("Need to see the screen.", turn.reasoning)
        assertEquals(100, turn.usage!!.cachedTokens)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test", req.getHeader("x-api-key"))
        val betas = req.getHeader("anthropic-beta")!!
        assertTrue(betas, "thinking-binding-controls-2026-08-01" in betas && "server-side-fallback-2026-07-01" in betas)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("SYS", body["system"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("drop_block", thinking["block_binding"]!!.jsonObject["prefix_mismatch_behavior"]!!.jsonPrimitive.content)
        // tools keep their schema
        val look = body["tools"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "look_at_screen" }
        assertTrue(look["input_schema"]!!.jsonObject["properties"]!!.jsonObject.containsKey("purpose"))
        // the user's photo goes first, as a base64 JPEG, then the text
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), content[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("PC won't boot", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun claudesReplyIsReplayedUnchangedAndPhotosRideInsideToolResults() = withServer(lookReply, doneReply()) { server, p ->
        val first = p.complete("SYS", listOf(Msg.User("help")), KeyboardAgent.TOOLS)
        server.takeRequest()
        val history = listOf(Msg.User("help"), Msg.Assistant(first.text, first.toolCalls, first.reasoning, first.raw),
            Msg.Tool("toolu_1", "look_at_screen", "Screen photo attached.", jpeg = jpeg))
        assertEquals("GRUB is showing.", p.complete("SYS", history, KeyboardAgent.TOOLS).text)
        val msgs = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        // assistant turn = exactly what Claude sent: signed thinking, text, tool_use
        val a = msgs[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), a.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("SIG-abc", a[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("Need to see the screen.", a[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("see", a[2]["input"]!!.jsonObject["purpose"]!!.jsonPrimitive.content)
        // tool result carries its text and the photo
        val r = msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", r["type"]!!.jsonPrimitive.content); assertEquals("toolu_1", r["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("text", "image"), r["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test fun repliesFromOtherBrainsAreRebuilt() {
        val p = ClaudeApiProvider({ "k" }, { "claude-haiku-5-5" })
        val req = p.buildRequest("s", listOf(Msg.User("hi"), Msg.Assistant("", listOf(ToolCall("c1", "press_keys", """{"keys":"down","times":2}""")))), emptyList())
        val json = Json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(req._body())).jsonObject
        val tu = json["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", tu["type"]!!.jsonPrimitive.content)
        assertEquals(2, tu["input"]!!.jsonObject["times"]!!.jsonPrimitive.int)
        assertNull("Haiku 5.5 has no server-side fallback", json["fallbacks"])
    }

    @Test fun keyboardAgentKeepsEveryPhotoForAppendOnlyBrains() = runBlocking {
        val seen = mutableListOf<List<Msg>>()
        val p = object : AiProvider {
            override val id = "f"; override val displayName = "f"; override val appendOnlyHistory = true; var i = 0
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolDef>, onTextDelta: (String) -> Unit): AssistantTurn {
                seen += messages.toList()
                return when (i++) {   // a 2nd look in a row is refused, so the 2nd photo comes from wait_for_screen
                    0 -> AssistantTurn("", listOf(ToolCall("l0", "look_at_screen", """{"purpose":"p"}""")), null)
                    1 -> AssistantTurn("", listOf(ToolCall("l1", "wait_for_screen", """{"seconds":1,"purpose":"p"}""")), null)
                    else -> AssistantTurn("done", emptyList(), null)
                }
            }
            override suspend fun testConnection() = "ok"
        }
        val hands = object : Hands { override suspend fun press(combo: String, times: Int): String? = null; override suspend fun type(text: String): String? = null }
        KeyboardAgent(p, { Screen(jpeg.copyOf()) }, hands,   // a real camera returns a new array per shot
            com.rodinrescue.data.AuditLog(java.nio.file.Files.createTempDirectory("c").toFile()), { true }, settleMs = 0) {}
            .run(mutableListOf(), "help")
        // the last request still has BOTH looks' photos: earlier messages never change
        assertEquals(2, seen.last().count { it.photo != null })
        // and every request starts with the previous one, unchanged (append-only)
        for (k in 1 until seen.size) assertEquals(seen[k - 1].map { it.toString() }, seen[k].take(seen[k - 1].size).map { it.toString() })
    }

    @Test fun refusalIsAClearError() = withServer(doneReply("refusal")) { _, p ->
        val e = runCatching { p.complete("s", listOf(Msg.User("x")), emptyList()) }.exceptionOrNull()
        assertTrue(e is ProviderException && e.message!!.startsWith("Claude declined"))
    }
}
