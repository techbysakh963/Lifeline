package com.rodinrescue

import com.rodinrescue.transport.SerialTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The AI's serial transport against a real terminal: `script` gives bash a pseudo-terminal with echo ON, like a
 * fresh login on the PC's /dev/ttyACM0 (verified there by hand 2026-10-16).
 */
class SerialTransportTest {
    private fun withShell(block: suspend (SerialTransport, StringBuilder) -> Unit) {
        assumeTrue("needs util-linux script", File("/usr/bin/script").exists())
        val p = ProcessBuilder("script", "-qfc", "bash --norc --noprofile -i", "/dev/null").redirectErrorStream(true).start()
        val seen = StringBuilder()
        val t = SerialTransport({ s -> p.outputStream.write(s.toByteArray()); p.outputStream.flush() }, { p.isAlive })
        Thread {
            val b = ByteArray(4096)
            while (true) { val n = p.inputStream.read(b); if (n < 0) break; val s = String(b, 0, n); synchronized(seen) { seen.append(s) }; t.onText(s) }
        }.apply { isDaemon = true }.start()
        try { runBlocking { block(t, seen) } } finally { p.destroyForcibly() }
    }

    @Test fun runsCommandsWithExitCodesOverATerminal() = withShell { t, _ ->
        t.connect()
        assertFalse(t.rootUser)
        val r = t.exec("echo hello; echo oops >&2; false", 5_000)
        assertEquals(1, r.exitCode); assertEquals("hello\noops", r.stdout.trim())
        assertEquals(0, t.exec("echo a # a trailing comment", 5_000).exitCode)
        val m = t.exec("for i in 1 2 3; do\n  echo line\$i\ndone", 5_000)
        assertEquals(listOf("line1", "line2", "line3"), m.stdout.trim().lines())
        assertEquals("", t.exec("cat", 5_000).stdout.trim())   // stdin is /dev/null: never waits for typing
    }

    @Test fun passwordGoesThroughStdinWithoutShowingOnTheTerminal() = withShell { t, seen ->
        t.connect()
        val r = t.exec("IFS= read -r x; echo len=\${#x}", 5_000, stdin = "s3cret-pw\n")
        assertEquals(0, r.exitCode); assertEquals("len=9", r.stdout.trim())
        assertFalse("never echoed", synchronized(seen) { seen.toString() }.contains("s3cret-pw"))
    }

    @Test fun timeoutInterruptsAndTheNextCommandWorks() = withShell { t, _ ->
        t.connect()
        val r = t.exec("echo started; sleep 20", 1_500)
        assertTrue(r.timedOut); assertNull(r.exitCode); assertTrue(r.stdout.contains("started"))
        val n = t.exec("echo after", 5_000)
        assertEquals(0, n.exitCode); assertEquals("after", n.stdout.trim())
    }

    /** The rescue chat's agent, unchanged, driving the PC through the serial login. */
    @Test fun theDiagnosticAgentWorksOverSerial() = withShell { t, _ ->
        t.connect()
        val turns = listOf(
            com.rodinrescue.ai.AssistantTurn("checking", listOf(com.rodinrescue.ai.ToolCall("1", "run_readonly_command", """{"command":"uname -s","purpose":"which OS"}""")), null),
            com.rodinrescue.ai.AssistantTurn("It is Linux.", emptyList(), null))
        var i = 0
        val brain = object : com.rodinrescue.ai.AiProvider {
            override val id = "fake"; override val displayName = "fake"
            override suspend fun complete(system: String, messages: List<com.rodinrescue.ai.Msg>, tools: List<com.rodinrescue.ai.ToolDef>, onTextDelta: (String) -> Unit) = turns[minOf(i++, 1)]
            override suspend fun testConnection() = "ok"
        }
        val hist = mutableListOf<com.rodinrescue.ai.Msg>()
        com.rodinrescue.ai.DiagnosticAgent(brain, t, com.rodinrescue.data.AuditLog(java.nio.file.Files.createTempDirectory("a").toFile()), { false }, { null }, t.rootUser, 5) {}
            .run(hist, "what OS?")
        val out = hist.filterIsInstance<com.rodinrescue.ai.Msg.Tool>().single().content
        assertTrue(out, out.contains("Linux") && out.contains("exit=\"0\""))
    }

    @Test fun markersInTheEchoedCommandAreNotMistakenForOutput() {
        val f = SerialTransport.frame("echo x", "abcd1234", false)
        assertFalse("split marker", f.contains("__LLabcd1234B"))
        val raw = f.replace("\n", "\r\n") + "__LLabcd1234B\r\nx\r\n__LLabcd1234E 0\r\n"
        assertEquals("x" to 0, SerialTransport.parse(raw, "__LLabcd1234B", "__LLabcd1234E"))
        assertNull(SerialTransport.parse(f, "__LLabcd1234B", "__LLabcd1234E"))
    }
}
