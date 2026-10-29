package com.rodinrescue

import com.rodinrescue.transport.*
import kotlinx.coroutines.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.Security

/**
 * Opt-in: same shell open/close/reopen sequence as the Terminal screen, against a REAL OpenSSH server.
 * Runs only when app/build/real-sshd.txt exists ("host port user /path/to/private_key").
 */
class RealSshdShellTest {
    @Test(timeout = 60_000) fun shellCloseKeepsConnectionOnRealOpenSsh() = runBlocking {
        val cfg = File("build/real-sshd.txt"); assumeTrue(cfg.exists())
        val (host, port, user, keyPath) = cfg.readText().trim().split(" ")
        Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)
        val store = object : HostKeyStore { val m = mutableMapOf<String, String>(); override fun pinnedHostKey(hostPort: String) = m[hostPort]; override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }; override fun forgetHostKey(hostPort: String) {} }
        store.pinHostKey("$host:$port", SshTransport.probeHostKey(host, port.toInt()))
        val t = SshTransport(SshCredentials(host, port.toInt(), user, privateKeyPem = File(keyPath).readText()), store)
        t.connect()
        val bg = CoroutineScope(Dispatchers.IO)
        repeat(3) { round ->
            val h = withContext(Dispatchers.IO) { t.openShell() }
            val buf = StringBuffer()
            val job = bg.launch { try { h.output.collect { buf.append(it) } } catch (e: Exception) { System.err.println("R$round collector ended: $e") } }
            withContext(Dispatchers.IO) { h.send("echo marker$round\n") }
            withTimeout(5000) { while (!buf.contains("marker$round\r\n") && !buf.contains("marker$round\n")) delay(50) }
            job.cancel()
            System.err.println("R$round closing")
            withContext(Dispatchers.IO) { h.close() }
            delay(500)
            System.err.println("R$round closed; connected=${t.isConnected}")
            assertTrue("round $round: connection dropped after closing the shell", t.isConnected)
            assertEquals("ok\n", t.exec("echo ok", 5000).stdout)
        }
        t.close(); bg.cancel()
    }
}
