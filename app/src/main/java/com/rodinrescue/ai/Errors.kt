package com.rodinrescue.ai

/** The one step that fixes an error, shown as a button under it in the chat. */
@kotlinx.serialization.Serializable
enum class Fix(val label: String) { AI_BRAIN("Open AI brain"), RETRY("Try again"), HOME("Open Home") }

/** What the chat shows for an error: what's wrong and what to do. The raw message goes to the log only. */
data class Friendly(val text: String, val fix: Fix? = null)

object Errors {
    /** "Claude — API key (Anthropic Console)" → "Claude". */
    fun brainName(displayName: String) = displayName.substringBefore(" —").trim().ifBlank { "The AI" }

    /**
     * Turns a provider failure into plain words. [hadPhoto]: the conversation includes a screen photo, so a 400/415/422
     * most likely means this brain can't take images (keyboard rescue sees only through photos).
     */
    fun provider(e: ProviderException, displayName: String, hadPhoto: Boolean = false): Friendly {
        val name = brainName(displayName)
        val raw = e.message.orEmpty()
        val code = e.httpCode
        return when {
            raw.startsWith("No ") && raw.endsWith("API key configured") ->
                Friendly("$name has no API key yet. Add one in AI brain, then tap Try again.", Fix.AI_BRAIN)
            // The built-in subscription CLIs already say what to tap.
            raw.contains("isn't installed") || raw.contains("isn't signed in") -> Friendly(raw, Fix.AI_BRAIN)
            code in setOf(400, 415, 422) && hadPhoto ->
                Friendly("$name can't read the screen photo. Keyboard rescue needs a brain that can see images: pick another in AI brain.", Fix.AI_BRAIN)
            code == 401 || (code == 403 && name == "Gemini") ->
                Friendly("$name didn't accept the API key. Check it or paste a new one in AI brain.", Fix.AI_BRAIN)
            code == 403 -> Friendly("This $name key isn't allowed to use the chosen model. Pick another model in AI brain, or check the key's access.", Fix.AI_BRAIN)
            code == 404 -> Friendly("$name doesn't know the chosen model. Pick another model in AI brain.", Fix.AI_BRAIN)
            code == 413 -> Friendly("This conversation is too long for $name. Start a new rescue, or tap Try again.", Fix.RETRY)
            code == 429 -> Friendly("$name is limiting requests, or the account is out of credit. Wait a minute and tap Try again, or pick another brain.", Fix.RETRY)
            code != null && code >= 500 -> Friendly("$name is having trouble on its side. Wait a minute, then tap Try again.", Fix.RETRY)
            raw.startsWith("Network error") || raw.startsWith("Stream interrupted") ->
                Friendly("Couldn't reach $name. Check the phone's internet (Wi-Fi or mobile data), then tap Try again.", Fix.RETRY)
            raw.contains("didn't answer for") -> Friendly("$name stopped answering. Tap Try again.", Fix.RETRY)
            code != null -> Friendly("$name couldn't handle this request. Tap Try again; if it keeps failing, the details are in the log.", Fix.RETRY)
            raw.contains(" exited ") || raw.contains("gave no answer") -> Friendly("$name stopped with an error. Tap Try again; the details are in the log.", Fix.RETRY)
            // Our own messages that already say what to do (declined, reply cut off, session test failed…).
            else -> Friendly(raw.lineSequence().first().take(300).ifBlank { "$name stopped with an error." }, Fix.RETRY)
        }
    }
}
