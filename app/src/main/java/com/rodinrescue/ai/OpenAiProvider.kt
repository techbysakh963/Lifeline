package com.rodinrescue.ai

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Official OpenAI API (/chat/completions) with streaming + tool calls, using the user's own API key. https://platform.openai.com/docs/ */
class OpenAiProvider(
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
) : AiProvider {
    override val id = "openai"
    override val displayName = "ChatGPT — API key (OpenAI)"
    private val json = Json { ignoreUnknownKeys = true }

    fun buildRequest(system: String, messages: List<Msg>, tools: List<ToolDef>): JsonObject = buildJsonObject {
        put("model", model())
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            // Photos go after the whole group of tool results: the API wants tool results right after their tool_calls.
            val photos = mutableListOf<JsonObject>()
            for ((i, m) in messages.withIndex()) { add(when (m) {
                is Msg.User -> buildJsonObject { put("role", "user"); put("content", openAiUserContent(m)) }
                is Msg.Assistant -> buildJsonObject {
                    put("role", "assistant")
                    put("content", m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            m.toolCalls.forEach { tc -> add(buildJsonObject {
                                put("id", tc.id); put("type", "function")
                                put("function", buildJsonObject { put("name", tc.name); put("arguments", tc.argumentsJson) })
                            }) }
                        })
                    }
                }
                is Msg.Tool -> buildJsonObject { put("role", "tool"); put("tool_call_id", m.callId); put("content", m.content) }
            })
                if (m is Msg.Tool) openAiImageMessage(m)?.let { photos.add(it) }
                if (messages.getOrNull(i + 1) !is Msg.Tool) { photos.forEach { add(it) }; photos.clear() }
            }
        })
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t -> add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) })
                }) }
            })
            put("tool_choice", "auto")
        }
    }

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolDef>, onTextDelta: (String) -> Unit): AssistantTurn {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No OpenAI API key configured")
        val body = json.encodeToString(JsonObject.serializer(), buildRequest(system, messages, tools)).toRequestBody("application/json".toMediaType())
        var attempt = 0
        while (true) {
            attempt++
            try { return withContext(Dispatchers.IO) { streamOnce(key, body, onTextDelta) } }
            catch (e: ProviderException) {
                if (!e.retryable || attempt >= 3) throw e
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun streamOnce(key: String, body: RequestBody, onTextDelta: (String) -> Unit): AssistantTurn {
        val req = Request.Builder().url("$baseUrl/chat/completions").header("Authorization", "Bearer $key").post(body).build()
        val call = client.newCall(req)
        val job = currentCoroutineContext().job
        val handle = job.invokeOnCompletion { call.cancel() }
        try {
            val resp = try { call.execute() } catch (e: java.io.IOException) { throw ProviderException("Network error: ${e.message}", null, retryable = !call.isCanceled()) }
            resp.use { r ->
                if (!r.isSuccessful) throw httpError(r.code, r.body?.string())
                val src = r.body!!.source()
                val parser = DeepSeekStreamParser(onTextDelta) // OpenAI-compatible SSE chunk shape
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = src.readUtf8Line() ?: break
                        if (!parser.feed(line)) break
                    }
                } catch (e: java.io.IOException) { if (call.isCanceled()) throw CancellationException("cancelled") else throw ProviderException("Stream interrupted: ${e.message}", null, retryable = false) }
                return parser.result()
            }
        } finally { handle.dispose() }
    }

    private fun httpError(code: Int, bodyText: String?): ProviderException {
        val apiMsg = runCatching { json.parseToJsonElement(bodyText ?: "").jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
        val meaning = when (code) {
            400 -> "Invalid request format"; 401 -> "Authentication failed — check your OpenAI API key"
            403 -> "Forbidden — check your OpenAI account/project access"; 404 -> "Model not found — check the model name"
            429 -> "Rate limit or quota reached"; 500 -> "OpenAI server error"; 503 -> "OpenAI server overloaded"
            else -> "HTTP $code"
        }
        return ProviderException("$meaning${apiMsg?.let { ": $it" } ?: ""}", code, retryable = code == 429 || code == 500 || code == 503)
    }

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No OpenAI API key configured")
        val req = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $key").get().build()
        val r = try { client.newCall(req).execute() } catch (e: java.io.IOException) { throw ProviderException("Network error: ${e.message}") }
        r.use {
            val text = it.body?.string()
            if (!it.isSuccessful) throw httpError(it.code, text)
            val ids = runCatching { json.parseToJsonElement(text!!).jsonObject["data"]!!.jsonArray.map { m -> m.jsonObject["id"]!!.jsonPrimitive.content } }.getOrDefault(emptyList())
            "Key accepted. Models available: ${ids.size}"
        }
    }
}
