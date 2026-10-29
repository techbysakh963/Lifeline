package com.rodinrescue

import com.rodinrescue.ai.*
import com.rodinrescue.data.AuditLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

/** The screen photo reaches each kind of brain in the shape its API expects — and only the newest one is sent. */
class VisionRequestTest {
    private val old = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
    private val new = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    // assistant asks for two tools; the second result carries the newest photo; an older look had a photo too
    private val history = listOf(
        Msg.User("PC stuck"),
        Msg.Assistant("", listOf(ToolCall("a", "look_at_screen", "{}"))),
        Msg.Tool("a", "look_at_screen", "old screen", jpeg = old),
        Msg.Assistant("", listOf(ToolCall("b", "press_keys", "{}"), ToolCall("c", "look_at_screen", "{}"))),
        Msg.Tool("b", "press_keys", "done"),
        Msg.Tool("c", "look_at_screen", "new screen", jpeg = new),
    )

    @Test fun onlyTheNewestPhotoSurvives() {
        val pruned = latestImageOnly(history).filterIsInstance<Msg.Tool>()
        assertEquals(listOf(null, null, new.toList()), pruned.map { it.jpeg?.toList() })
    }

    private fun checkOpenAiShape(msgs: JsonArray) {
        val roles = msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        // system, user, assistant, tool, [photo], assistant, tool, tool, [photo] — photos never split a tool group
        assertEquals(listOf("system", "user", "assistant", "tool", "user", "assistant", "tool", "tool", "user"), roles)
        val photos = msgs.filter { m -> m.jsonObject["content"] is JsonArray }
        assertEquals(2, photos.size)
        val url = photos.last().jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64," + b64(new), url)
    }

    @Test fun openAiAndDeepSeekPutThePhotoAfterTheToolGroup() {
        val tools = KeyboardAgent.TOOLS
        // Providers send whatever photos they are given; pruning happens in the agent, so pass the unpruned history here.
        checkOpenAiShape(OpenAiProvider({ "k" }, { "gpt-4.1" }).buildRequest("sys", history, tools)["messages"]!!.jsonArray)
        checkOpenAiShape(DeepSeekProvider({ "k" }, { "deepseek-flash" }, { false }).buildRequest("sys", history, tools)["messages"]!!.jsonArray)
        val pruned = OpenAiProvider({ "k" }, { "gpt-4.1" }).buildRequest("sys", latestImageOnly(history), tools)["messages"]!!.jsonArray
        assertEquals(1, pruned.count { it.jsonObject["content"] is JsonArray })
    }

    @Test fun geminiSendsInlineJpegAfterTheFunctionResponses() {
        val c = GeminiProvider({ "k" }, { "gemini-2.5-pro" }).buildRequest("sys", latestImageOnly(history), KeyboardAgent.TOOLS)["contents"]!!.jsonArray
        val last = c.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        val inline = last["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(b64(new), inline["data"]!!.jsonPrimitive.content)
        assertEquals(1, c.count { it.toString().contains("inlineData") })
    }

    @Test fun cliBrainSendsTheNewestPhotoAndSaysSoInTheTranscript() = runBlocking {
        val root = Files.createTempDirectory("rootfs").toFile(); var seen: ByteArray? = null; var prompt = ""
        val host = object : CliHost {
            override fun ready() = true
            override fun file(guestPath: String) = java.io.File(root, guestPath.removePrefix("/"))
            override suspend fun run(argv: List<String>, input: String?, timeoutSec: Long, cwd: String): com.rodinrescue.runtime.Ubuntu.Output {
                if ("status" in argv) return com.rodinrescue.runtime.Ubuntu.Output(0, "Logged in using ChatGPT")
                seen = file(argv[argv.indexOf("-i") + 1]).readBytes(); prompt = input!!
                file(argv[argv.indexOf("-o") + 1]).writeText("""{"say":"I see GRUB","tool_calls":[]}""")
                return com.rodinrescue.runtime.Ubuntu.Output(0, "")
            }
        }
        assertEquals("I see GRUB", CodexCliProvider(host).complete("sys", latestImageOnly(history), KeyboardAgent.TOOLS).text)
        assertTrue(seen!!.contentEquals(new))
        assertTrue(prompt.contains("attached as screen.jpg"))
    }

    private class OneLook(val turns: List<AssistantTurn>) : AiProvider {
        override val id = "f"; override val displayName = "f"; var i = 0; val seen = mutableListOf<List<Msg>>()
        override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolDef>, onTextDelta: (String) -> Unit): AssistantTurn {
            seen.add(messages); return turns.getOrElse(i++) { AssistantTurn("done", emptyList(), null) }
        }
        override suspend fun testConnection() = "ok"
    }

    private fun agentRun(): OneLook = runBlocking {
        val look = ToolCall("1", "look_at_screen", """{"purpose":"p"}""")
        val p = OneLook(listOf(AssistantTurn("", listOf(look), null), AssistantTurn("", listOf(ToolCall("2", "wait_for_screen", """{"seconds":1,"purpose":"p"}""")), null)))   // a 2nd look in a row is refused
        var n = 0
        val eyes = Eyes { Screen(if (n++ == 0) old else new) }
        val hands = object : Hands { override suspend fun press(combo: String, times: Int): String? = null; override suspend fun type(text: String): String? = null }
        KeyboardAgent(p, eyes, hands, AuditLog(Files.createTempDirectory("v").toFile()), { true }, settleMs = 0) {}.run(mutableListOf(), "help")
        p
    }

    @Test fun brainThatRejectsPhotosStopsWithAClearMessage() = runBlocking {
        val look = ToolCall("1", "look_at_screen", """{"purpose":"p"}""")
        val p = object : AiProvider {
            override val id = "f"; override val displayName = "f"; var i = 0; val seen = mutableListOf<List<Msg>>()
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolDef>, onTextDelta: (String) -> Unit): AssistantTurn {
                seen.add(messages)
                if (messages.any { it is Msg.Tool && it.jpeg != null }) throw ProviderException("image input not supported", 400)
                return if (i++ == 0) AssistantTurn("", listOf(look), null) else AssistantTurn("text only is fine", emptyList(), null)
            }
            override suspend fun testConnection() = "ok"
        }
        val events = mutableListOf<AgentEvent>()
        val hands = object : Hands { override suspend fun press(combo: String, times: Int): String? = null; override suspend fun type(text: String): String? = null }
        KeyboardAgent(p, { Screen(new) }, hands, AuditLog(Files.createTempDirectory("v").toFile()), { true }, settleMs = 0) { events.add(it) }.run(mutableListOf(), "help")
        // Photos are its only eyes now: a brain that rejects images is told to the user plainly, and the run stops.
        assertTrue(events.any { it is AgentEvent.Error && it.message.contains("needs a brain that can see images") })
        assertTrue(events.any { it is AgentEvent.Finished && it.reason == "provider error" })
    }

    @Test fun agentAttachesEachLookAndProviderSeesOnlyTheNewest() {
        val tools = agentRun().seen.last().filterIsInstance<Msg.Tool>()
        assertEquals(listOf(null, new.toList()), tools.map { it.jpeg?.toList() })
    }

    @Test fun tokenSaverSendsEachPhotoOnlyOnce() {
        fun seen(once: Boolean): List<List<Msg>> = runBlocking {
            val look = ToolCall("1", "look_at_screen", """{"purpose":"p"}""")
            val down = ToolCall("2", "press_keys", """{"keys":"down","purpose":"p"}""")
            val p = OneLook(listOf(AssistantTurn("", listOf(look), null), AssistantTurn("", listOf(down), null)))
            val hands = object : Hands { override suspend fun press(combo: String, times: Int): String? = null; override suspend fun type(text: String): String? = null }
            KeyboardAgent(p, { Screen(new) }, hands, AuditLog(Files.createTempDirectory("v").toFile()), { true }, settleMs = 0, photoOnce = once) {}.run(mutableListOf(), "help")
            p.seen
        }
        fun photos(req: List<Msg>) = req.count { it.photo != null }
        // requests: 1) user text  2) after the look (photo is new)  3) after "down" (no new photo)
        assertEquals(listOf(0, 1, 1), seen(false).map(::photos))   // normal: the newest photo rides along
        assertEquals(listOf(0, 1, 0), seen(true).map(::photos))    // token saver: sent once
    }

    @Test fun aUserPhotoIsSentInEachApiShape() {
        val h = listOf(Msg.User("what is this?", jpeg = new))
        val oa = OpenAiProvider({ "k" }, { "gpt-4.1" }).buildRequest("s", h, emptyList())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("data:image/jpeg;base64," + b64(new), oa[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val ge = GeminiProvider({ "k" }, { "g" }).buildRequest("s", h, emptyList())["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(b64(new), ge[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }
}
