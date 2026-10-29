package com.rodinrescue

import com.rodinrescue.transport.*
import kotlinx.coroutines.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.*
import org.junit.Assert.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.Security

/** Real SSH protocol round-trips (sshj client -> in-process Apache MINA sshd that runs commands with /bin/sh). */
class SshTransportTest {
    private class Store : HostKeyStore {
        val m = mutableMapOf<String, String>()
        override fun pinnedHostKey(hostPort: String) = m[hostPort]
        override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }
        override fun forgetHostKey(hostPort: String) { m.remove(hostPort) }
    }

    private class ShCommand(val cmd: String) : Command {
        private var ins: InputStream? = null; private var out: OutputStream? = null; private var err: OutputStream? = null; private var cb: ExitCallback? = null
        private var proc: Process? = null
        override fun setInputStream(i: InputStream) { ins = i }
        override fun setOutputStream(o: OutputStream) { out = o }
        override fun setErrorStream(e: OutputStream) { err = e }
        override fun setExitCallback(c: ExitCallback) { cb = c }
        override fun start(channel: ChannelSession, env: Environment) {
            val p = ProcessBuilder("sh", "-c", cmd).start(); proc = p
            Thread { runCatching { ins!!.copyTo(p.outputStream) }; runCatching { p.outputStream.close() } }.start()
            val t1 = Thread { runCatching { p.inputStream.copyTo(out!!); out!!.flush() } }.apply { start() }
            val t2 = Thread { runCatching { p.errorStream.copyTo(err!!); err!!.flush() } }.apply { start() }
            Thread { val code = p.waitFor(); t1.join(); t2.join(); cb!!.onExit(code) }.start()
        }
        override fun destroy(channel: ChannelSession) { proc?.destroyForcibly() }
    }

    /** Interactive echo "shell": writes back every chunk immediately, exits when the client closes the channel. */
    private class EchoShell : Command {
        private var ins: InputStream? = null; private var out: OutputStream? = null; private var cb: ExitCallback? = null
        override fun setInputStream(i: InputStream) { ins = i }
        override fun setOutputStream(o: OutputStream) { out = o }
        override fun setErrorStream(e: OutputStream) {}
        override fun setExitCallback(c: ExitCallback) { cb = c }
        override fun start(channel: ChannelSession, env: Environment) {
            Thread { runCatching { val b = ByteArray(1024); while (true) { val n = ins!!.read(b); if (n < 0) break; out!!.write(b, 0, n); out!!.flush() } }; cb!!.onExit(0) }.start()
        }
        override fun destroy(channel: ChannelSession) {}
    }

    private lateinit var server: SshServer
    private val store = Store()
    private fun creds(pw: String = "secret") = SshCredentials("127.0.0.1", server.port, "tester", pw)

    @Before fun start() {
        Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hk", ".ser"))
            setPasswordAuthenticator { _, pw, _ -> pw == "secret" }
            commandFactory = CommandFactory { _, c -> ShCommand(c) }
            shellFactory = org.apache.sshd.server.shell.ShellFactory { EchoShell() }
            start()
        }
    }
    @After fun stop() { server.stop(true) }

    @Test fun unknownHostKeyIsRefusedUntilPinned_thenExecWorks() = runBlocking {
        val t = SshTransport(creds(), store)
        val e = try { t.connect(); null } catch (e: HostKeyUnknownException) { e }
        assertNotNull("must refuse unpinned host key", e); assertTrue(e!!.fingerprint.startsWith("SHA256:"))
        assertEquals(e.fingerprint, SshTransport.probeHostKey("127.0.0.1", server.port))
        store.pinHostKey("127.0.0.1:${server.port}", e.fingerprint)
        t.connect()
        val r = t.exec("echo out; echo err 1>&2; exit 3")
        assertEquals("out\n", r.stdout); assertEquals("err\n", r.stderr); assertEquals(3, r.exitCode)
        t.close()
    }

    @Test fun changedHostKeyIsRejected() = runBlocking {
        store.pinHostKey("127.0.0.1:${server.port}", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val t = SshTransport(creds(), store)
        val e = try { t.connect(); null } catch (e: HostKeyMismatchException) { e }
        assertNotNull(e)
    }

    @Test fun wrongPasswordFails() = runBlocking {
        store.pinHostKey("127.0.0.1:${server.port}", SshTransport.probeHostKey("127.0.0.1", server.port))
        val t = SshTransport(creds("nope"), store)
        assertTrue(runCatching { t.connect() }.isFailure)
    }

    private suspend fun connected(): SshTransport {
        store.pinHostKey("127.0.0.1:${server.port}", SshTransport.probeHostKey("127.0.0.1", server.port))
        return SshTransport(creds(), store).also { it.connect() }
    }

    @Test fun stdinIsDelivered() = runBlocking {
        val t = connected()
        val r = t.exec("head -n1", stdin = "hello\n")
        assertEquals("hello\n", r.stdout); assertEquals(0, r.exitCode); t.close()
    }

    @Test fun timeoutKillsCommandAndReports() = runBlocking {
        val t = connected()
        val r = t.exec("sleep 30", timeoutMs = 700)
        assertTrue(r.timedOut); assertNull(r.exitCode)
        assertTrue(r.durationMs < 10_000)
        val again = t.exec("echo still-alive")   // connection survives a timed-out command
        assertEquals("still-alive\n", again.stdout); t.close()
    }

    @Test fun outputIsCappedWithHeadAndTail() = runBlocking {
        val t = connected()
        val r = t.exec("seq 1 200000", maxOutputBytes = 4096)
        assertTrue(r.truncated); assertTrue(r.stdout.length < 6000)
        assertTrue(r.stdout.startsWith("1\n")); assertTrue(r.stdout.trimEnd().endsWith("200000")); t.close()
    }

    @Test fun cancelAllAbortsRunningCommand() = runBlocking {
        val t = connected()
        val d = async(Dispatchers.Default) { t.exec("sleep 30", timeoutMs = 60_000) }
        delay(500); t.cancelAll()
        val r = withTimeout(8000) { d.await() }
        assertTrue(r.cancelled); assertNull(r.exitCode); t.close()
    }

    /** Regression: closing the Terminal's shell must not kill the SSH connection (re-open + exec must work). */
    @Test(timeout = 30_000) fun shellCanBeClosedAndReopened() = runBlocking {
        val t = SshTransport(creds(), store.also { it.pinHostKey("127.0.0.1:${server.port}", SshTransport.probeHostKey("127.0.0.1", server.port)) })
        t.connect()
        val bg = CoroutineScope(Dispatchers.IO)   // the collector may block in read(); keep it out of runBlocking's children
        repeat(2) { round ->
            val h = withContext(Dispatchers.IO) { t.openShell() }
            val got = CompletableDeferred<String>()
            val job = bg.launch { h.output.collect { if (!got.isCompleted) got.complete(it) } }
            withContext(Dispatchers.IO) { h.send("hello$round\n") }
            assertTrue(withTimeout(5000) { got.await() }.contains("hello$round"))
            job.cancel()
            System.err.println("R$round closing")
            withContext(Dispatchers.IO) { h.close() }
            System.err.println("R$round closed; connected=${t.isConnected}")
            assertTrue("round $round: still connected after closing the shell", t.isConnected)
            assertEquals("round $round: exec after closing the shell", "ok\n", t.exec("echo ok", 5000).stdout)
        }
        t.close(); bg.cancel()
    }
}
