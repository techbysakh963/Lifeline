package com.rodinrescue

import com.rodinrescue.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ToolsAndProvidersTest {
    @Test fun validationRejectsBadArgs() {
        val t = Tools.byName.getValue("read_system_logs")
        assertThrows(Tools.Invalid::class.java) { Tools.validate(t, Tools.parseArgs("""{"lines": 9999}""")) }
        assertThrows(Tools.Invalid::class.java) { Tools.validate(t, Tools.parseArgs("""{"priority": "loud"}""")) }
        assertThrows(Tools.Invalid::class.java) { Tools.validate(t, Tools.parseArgs("""{"bogus": 1}""")) }
        assertThrows(Tools.Invalid::class.java) { Tools.validate(Tools.byName.getValue("run_readonly_command"), Tools.parseArgs("{}")) }
        assertThrows(Tools.Invalid::class.java) { Tools.parseArgs("not json") }
        Tools.validate(t, Tools.parseArgs("""{"lines": 20, "priority": "err"}"""))
    }

    @Test fun deepseekStreamParserAssemblesToolCallsAndUsage() {
        val seen = StringBuilder()
        val p = DeepSeekStreamParser { seen.append(it) }
        listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":"Checking "}}]}""",
            """data: {"choices":[{"delta":{"content":"now."}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"check_disk_space","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"run_readonly_command","arguments":"{\"command\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\"df -h\",\"purpose\":\"x\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64}}}""",
            "data: [DONE]"
        ).forEach { p.feed(it) }
        val r = p.result()
        assertEquals("Checking now.", r.text); assertEquals("Checking now.", seen.toString())
        assertEquals(2, r.toolCalls.size)
        assertEquals("check_disk_space", r.toolCalls[0].name); assertEquals("{}", r.toolCalls[0].argumentsJson)
        assertEquals("""{"command":"df -h","purpose":"x"}""", r.toolCalls[1].argumentsJson)
        assertEquals(100, r.usage!!.promptTokens); assertEquals(64, r.usage!!.cachedTokens)
    }

    @Test fun deepseekProviderStreamsOverHttpAndMapsErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.start()
        val p = DeepSeekProvider({ "k" }, { "deepseek-flash" }, { false }, baseUrl = server.url("/").toString().trimEnd('/'))
        val t = p.complete("sys", listOf(Msg.User("yo")), Tools.defs)
        assertEquals("hi", t.text); assertEquals(5, t.usage!!.promptTokens)
        val req = server.takeRequest()
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(Tools.all.size, body["tools"]!!.jsonArray.size)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val e = try { p.complete("s", listOf(Msg.User("x")), emptyList()); null } catch (e: ProviderException) { e }
        assertEquals(401, e!!.httpCode); assertTrue(e.message!!.contains("API key")); assertFalse(e.retryable)
        server.shutdown()
    }

    @Test fun cliAnswerParsing() {
        val c = ClaudeCliProvider(object : CliHost {
            override fun ready() = true
            override suspend fun run(argv: List<String>, input: String?, timeoutSec: Long, cwd: String) = error("unused")
            override fun file(guestPath: String) = error("unused")
        })
        val t = c.parseTurn(Json.parseToJsonElement("""{"result":{"say":"Looking","tool_calls":[{"name":"check_disk_space","arguments":{}},{"name":"run_readonly_command","arguments":{"command":"df -h","purpose":"p"}}]},"usage":{"input_tokens":10,"output_tokens":3}}""").jsonObject)
        assertEquals("Looking", t.text); assertEquals(2, t.toolCalls.size)
        assertEquals("""{"command":"df -h","purpose":"p"}""", t.toolCalls[1].argumentsJson)
        assertEquals(10, t.usage!!.promptTokens)
        assertThrows(ProviderException::class.java) { c.parseTurn(Json.parseToJsonElement("""{"error":"x"}""").jsonObject) }
        // a broken arguments_json becomes {} rather than garbage
        assertEquals("{}", c.parseTurn(Json.parseToJsonElement("""{"result":{"say":"","tool_calls":[{"name":"x","arguments_json":"not json"}]}}""").jsonObject).toolCalls[0].argumentsJson)
    }
}
