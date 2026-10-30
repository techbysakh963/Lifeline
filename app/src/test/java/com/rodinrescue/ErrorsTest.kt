package com.rodinrescue

import com.rodinrescue.ai.Errors
import com.rodinrescue.ai.Fix
import com.rodinrescue.ai.ProviderException
import com.rodinrescue.ui.isProblem
import com.rodinrescue.data.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat shows errors in plain words with the one button that fixes them; the raw text stays in the log. */
class ErrorsTest {
    private val claude = "Claude — API key (Anthropic Console)"
    private fun f(msg: String, code: Int? = null, photo: Boolean = false, name: String = claude) = Errors.provider(ProviderException(msg, code), name, photo)

    @Test fun keyProblemsPointToAiBrain() {
        assertEquals(Fix.AI_BRAIN, f("No Claude API key configured").fix)
        val bad = f("Authentication failed — check your Claude API key: invalid x-api-key", 401)
        assertEquals(Fix.AI_BRAIN, bad.fix); assertTrue(bad.text.startsWith("Claude didn't accept the API key"))
        assertEquals(Fix.AI_BRAIN, f("Authentication failed", 403, name = "Gemini — API key (Google AI Studio)").fix)
        assertEquals(Fix.AI_BRAIN, f("Model not found", 404).fix)
    }

    @Test fun passingTroubleOffersTryAgain() {
        for (code in listOf(429, 500, 529)) assertEquals(Fix.RETRY, f("x", code).fix)
        val net = f("Network error: Failed to connect to /127.0.0.1:8765")
        assertEquals(Fix.RETRY, net.fix); assertFalse(net.text.contains("127.0.0.1"))
        assertTrue(net.text.contains("internet"))
    }

    @Test fun photoRejectedOnlyWhenAPhotoWasSent() {
        assertTrue(f("Invalid request", 400, photo = true).text.contains("can't read the screen photo"))
        assertFalse(f("Invalid request", 400, photo = false).text.contains("photo"))
    }

    @Test fun rawCliOutputStaysOutOfTheChat() {
        val e = f("claude exited 1: Traceback (most recent call last) …", name = "Claude — subscription (built in)")
        assertFalse(e.text.contains("Traceback")); assertEquals(Fix.RETRY, e.fix)
        // Already-plain messages from the app pass through.
        assertEquals("Codex isn't signed in with a subscription. Open AI brain and tap Sign in.", f("Codex isn't signed in with a subscription. Open AI brain and tap Sign in.").text)
    }

    @Test fun logProblemsFilter() {
        assertTrue(isProblem(AuditEntry(0, "error", "provider: boom")))
        assertTrue(isProblem(AuditEntry(0, "approval", "p1 REJECTED")))
        assertFalse(isProblem(AuditEntry(0, "command", "ls")))
    }
}
