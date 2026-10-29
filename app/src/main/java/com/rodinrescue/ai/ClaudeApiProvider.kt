package com.rodinrescue.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Claude via the official Anthropic API (the user's own API key), through the official Java SDK.
 *
 * Claude thinks on every turn, and each thinking block is signed against the exact conversation before it. So:
 *  - the reply is kept verbatim ([AssistantTurn.raw]) and replayed unchanged;
 *  - [appendOnlyHistory] tells the agents not to prune old photos (that would edit earlier messages);
 *  - prompt caching makes re-sending the growing history cheap;
 *  - `drop_block` is the safety net for edits we can't avoid (a resumed chat: photos aren't saved), so such a
 *    request still works, just without the earlier reasoning.
 */
class ClaudeApiProvider(
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val baseUrl: String? = null,
) : AiProvider {
    override val id = "claudeapi"
    override val displayName = "Claude — API key (Anthropic Console)"
    override val appendOnlyHistory = true

    private val mapper = jsonMapper()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized private fun client(key: String): AnthropicClient {
        client?.let { if (clientKey == key) return it }
        client?.close()
        return AnthropicOkHttpClient.builder().apiKey(key).apply { baseUrl?.let { baseUrl(it) } }.build()
            .also { client = it; clientKey = key }
    }

    /** Pure request builder (tested without a network). */
    fun buildRequest(system: String, messages: List<Msg>, tools: List<ToolDef>): MessageCreateParams {
        val m = model()
        val b = MessageCreateParams.builder()
            .model(m)
            .maxTokens(64000L)
            .system(system)
            // Thinking is always on for current models; summarized so the user can read why it acts.
            // drop_block: an earlier edit costs that request its old reasoning instead of failing it.
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                .putAdditionalProperty("block_binding", JsonValue.from(mapOf("prefix_mismatch_behavior" to "drop_block"))).build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
            .cacheControl(CacheControlEphemeral.builder().build())   // cache the whole prefix: the history only grows
        val betas = mutableListOf(BETA_BLOCK_BINDING)
        if (m in SERVER_FALLBACK_MODELS) {
            // A safety classifier may decline (e.g. repair commands read as "cyber"); let the API retry on a model it routes to.
            betas += BETA_FALLBACK; b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        b.putAdditionalHeader("anthropic-beta", betas.joinToString(","))
        tools.forEach { b.addTool(tool(it)) }
        toParams(messages).forEach { b.addMessage(it) }
        return b.build()
    }

    private fun tool(t: ToolDef): Tool {
        val props = Tool.InputSchema.Properties.builder()
        (t.parameters["properties"] as? JsonObject)?.forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(plain(v))) }
        val required = (t.parameters["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        return Tool.builder().name(t.name).description(t.description)
            .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build()).build()
    }

    private fun image(jpeg: ByteArray) = ImageBlockParam.builder()
        .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(jpeg.base64()).build()).build()

    private fun text(s: String) = TextBlockParam.builder().text(s).build()

    /** Our history → API messages. Consecutive tool results go in one user message (with their photos inside). */
    internal fun toParams(messages: List<Msg>): List<MessageParam> {
        val out = mutableListOf<MessageParam>()
        var results = mutableListOf<ContentBlockParam>()
        fun flush() { if (results.isNotEmpty()) { out += user(results); results = mutableListOf() } }
        for (m in messages) when (m) {
            is Msg.Tool -> results += ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(m.callId)
                .contentOfBlocks(buildList {
                    add(ToolResultBlockParam.Content.Block.ofText(text(m.content.ifEmpty { "(empty)" })))
                    m.jpeg?.let { add(ToolResultBlockParam.Content.Block.ofImage(image(it))) }
                }).build())
            is Msg.User -> { flush(); out += user(buildList {
                m.jpeg?.let { add(ContentBlockParam.ofImage(image(it))) }
                add(ContentBlockParam.ofText(text(m.text.ifEmpty { "(photo)" })))
            }) }
            is Msg.Assistant -> { flush(); out += assistant(m) }
        }
        flush()
        return out
    }

    private fun user(blocks: List<ContentBlockParam>) = MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()

    private fun assistant(m: Msg.Assistant): MessageParam {
        // Claude's own reply (with its signed thinking) goes back exactly as it came.
        m.raw?.let { raw -> runCatching { return mapper.readValue(raw, MessageParam::class.java) } }
        // Otherwise (a reply from another brain, an old chat): rebuild text + tool calls.
        val blocks = buildList {
            if (m.text.isNotBlank()) add(ContentBlockParam.ofText(text(m.text)))
            m.toolCalls.forEach { tc ->
                val input = ToolUseBlockParam.Input.builder()
                (runCatching { Json.parseToJsonElement(tc.argumentsJson) }.getOrNull() as? JsonObject)
                    ?.forEach { (k, v) -> input.putAdditionalProperty(k, JsonValue.from(plain(v))) }
                add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(tc.id).name(tc.name).input(input.build()).build()))
            }
            if (isEmpty()) add(ContentBlockParam.ofText(text("(no reply)")))
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build()
    }

    /** Response → our turn; [Message.toParam] is what gets replayed next time. */
    internal fun toTurn(msg: Message): AssistantTurn {
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) {
            val why = msg.stopDetails().flatMap { it.explanation() }.orElse(null)
            throw ProviderException("Claude declined this request" + (why?.let { ": $it" } ?: ".") + " Rephrase it, or pick another brain.")
        }
        val text = StringBuilder(); val reasoning = StringBuilder(); val calls = mutableListOf<ToolCall>()
        for (block in msg.content()) {
            block.text().ifPresent { text.append(it.text()) }
            block.thinking().ifPresent { if (it.thinking().isNotBlank()) reasoning.append(it.thinking()).append('\n') }
            block.toolUse().ifPresent { calls += ToolCall(it.id(), it.name(), mapper.writeValueAsString(it._input())) }
        }
        if (msg.stopReason().orElse(null) == StopReason.MAX_TOKENS && calls.isNotEmpty())
            throw ProviderException("Claude's reply was cut off mid-action (output limit). Try again.")
        val u = msg.usage()
        val cacheRead = u.cacheReadInputTokens().orElse(0L); val cacheWrite = u.cacheCreationInputTokens().orElse(0L)
        val usage = TokenUsage(promptTokens = (u.inputTokens() + cacheRead + cacheWrite).toInt(), completionTokens = u.outputTokens().toInt(), cachedTokens = cacheRead.toInt())
        val raw = runCatching { mapper.writeValueAsString(msg.toParam()) }.getOrNull()
        return AssistantTurn(text.toString(), calls, usage, reasoning.toString().trim().ifEmpty { null }, raw)
    }

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolDef>, onTextDelta: (String) -> Unit): AssistantTurn {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No Claude API key configured")
        val params = buildRequest(system, messages, tools)
        val job = currentCoroutineContext().job
        return withContext(Dispatchers.IO) {
            try {
                val acc = MessageAccumulator.create()
                val stream: StreamResponse<RawMessageStreamEvent> = client(key).messages().createStreaming(params)
                stream.use {
                    val h = job.invokeOnCompletion { stream.close() }   // Stop button: close the connection
                    try {
                        stream.stream().forEach { ev: RawMessageStreamEvent ->
                            acc.accumulate(ev)
                            ev.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onTextDelta(it.text()) }
                        }
                    } finally { h.dispose() }
                }
                toTurn(acc.message())
            } catch (e: AnthropicServiceException) { throw httpError(e) }
            catch (e: AnthropicIoException) { throw ProviderException("Network error: ${e.message}", null, retryable = true) }
        }
    }

    private fun httpError(e: AnthropicServiceException): ProviderException {
        val code = e.statusCode()
        val meaning = when (code) {
            400 -> "Invalid request"; 401 -> "Authentication failed — check your Claude API key"
            403 -> "This key isn't allowed to do that"; 404 -> "Model not found — check the model id"
            413 -> "Request too large"; 429 -> "Rate limit reached"; 529 -> "Claude is overloaded, try again shortly"
            else -> if (code >= 500) "Claude server error" else "HTTP $code"
        }
        return ProviderException("$meaning: ${e.message?.take(300)}", code, retryable = code == 429 || code >= 500)
    }

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No Claude API key configured")
        try {
            val info = client(key).models().retrieve(model())
            "Key accepted. Model: ${info.displayName()} (${info.id()})"
        } catch (e: AnthropicServiceException) { throw httpError(e) }
        catch (e: AnthropicIoException) { throw ProviderException("Network error: ${e.message}") }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
        const val BETA_BLOCK_BINDING = "thinking-binding-controls-2026-08-01"
        const val BETA_FALLBACK = "server-side-fallback-2026-07-01"
        /** Models that take server-side `fallbacks: "default"` on the Claude API. */
        val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        /** kotlinx JSON → plain Kotlin values (for the SDK's JsonValue.from). */
        fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        }
    }
}
