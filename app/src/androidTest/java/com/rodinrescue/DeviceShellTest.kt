package com.rodinrescue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rodinrescue.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** On-device: Terminal shell open/close/reopen against a real OpenSSH (args: host, port, user, key). */
@RunWith(AndroidJUnit4::class)
class DeviceShellTest {
    @Test fun shellCloseKeepsConnection() = runBlocking {
        val a = InstrumentationRegistry.getArguments()
        val host = a.getString("host") ?: return@runBlocking; val port = a.getString("port")!!.toInt(); val user = a.getString("user")!!
        val key = String(android.util.Base64.decode(a.getString("key")!!, android.util.Base64.DEFAULT))
        val store = object : HostKeyStore { val m = mutableMapOf<String, String>(); override fun pinnedHostKey(hostPort: String) = m[hostPort]; override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }; override fun forgetHostKey(hostPort: String) {} }
        store.pinHostKey("$host:$port", SshTransport.probeHostKey(host, port))
        val t = SshTransport(SshCredentials(host, port, user, privateKeyPem = key), store)
        t.connect()
        val bg = CoroutineScope(Dispatchers.IO)
        repeat(3) { round ->
            val h = withContext(Dispatchers.IO) { t.openShell() }
            val buf = StringBuffer()
            val job = bg.launch { try { h.output.collect { buf.append(it) } } catch (e: Exception) { android.util.Log.w("DeviceShellTest", "collector: $e") } }
            withContext(Dispatchers.IO) { h.send("echo marker$round\n") }
            withTimeout(5000) { while (!buf.contains("marker$round\r\n")) delay(50) }
            job.cancel()
            withContext(Dispatchers.IO) { h.close() }
            delay(500)
            android.util.Log.i("DeviceShellTest", "round $round connected=${t.isConnected}")
            assertTrue("round $round: connection dropped after closing the shell", t.isConnected)
            assertEquals("ok\n", t.exec("echo ok", 5000).stdout)
        }
        t.close(); bg.cancel()
    }

    /** Same as the app's old sendShell: every send in its own IO coroutine, so writes can overlap. */
    @Test fun concurrentShellWritesKeepConnection() = runBlocking {
        val a = InstrumentationRegistry.getArguments()
        val host = a.getString("host") ?: return@runBlocking; val port = a.getString("port")!!.toInt(); val user = a.getString("user")!!
        val key = String(android.util.Base64.decode(a.getString("key")!!, android.util.Base64.DEFAULT))
        val store = object : HostKeyStore { val m = mutableMapOf<String, String>(); override fun pinnedHostKey(hostPort: String) = m[hostPort]; override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }; override fun forgetHostKey(hostPort: String) {} }
        store.pinHostKey("$host:$port", SshTransport.probeHostKey(host, port))
        val t = SshTransport(SshCredentials(host, port, user, privateKeyPem = key), store)
        t.connect()
        val bg = CoroutineScope(Dispatchers.IO)
        val h = withContext(Dispatchers.IO) { t.openShell() }
        val job = bg.launch { try { h.output.collect { } } catch (_: Exception) {} }
        (1..200).map { i -> bg.launch { runCatching { h.send("echo concurrent-$i\n") } } }.joinAll()
        bg.launch { runCatching { h.send("\u0003") } }
        delay(300); job.cancel(); withContext(Dispatchers.IO) { h.close() }
        delay(500)
        assertTrue("connection dropped after concurrent shell writes", t.isConnected)
        assertEquals("ok\n", t.exec("echo ok", 5000).stdout)
        t.close(); bg.cancel()
    }

    /** What the Terminal's Close button and STOP do: close from the MAIN thread. Must not break the connection. */
    @Test fun closingFromMainThreadKeepsConnection() = runBlocking {
        val a = InstrumentationRegistry.getArguments()
        val host = a.getString("host") ?: return@runBlocking; val port = a.getString("port")!!.toInt(); val user = a.getString("user")!!
        val key = String(android.util.Base64.decode(a.getString("key")!!, android.util.Base64.DEFAULT))
        val store = object : HostKeyStore { val m = mutableMapOf<String, String>(); override fun pinnedHostKey(hostPort: String) = m[hostPort]; override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }; override fun forgetHostKey(hostPort: String) {} }
        store.pinHostKey("$host:$port", SshTransport.probeHostKey(host, port))
        val t = SshTransport(SshCredentials(host, port, user, privateKeyPem = key), store)
        t.connect()
        val h = withContext(Dispatchers.IO) { t.openShell() }
        withContext(Dispatchers.Main) { h.close() }              // Terminal → Close
        delay(300)
        assertEquals("exec after closing the shell on the main thread", "ok\n", t.exec("echo ok", 5000).stdout)
        val running = async(Dispatchers.IO) { t.exec("sleep 5", 10_000) }
        delay(500); withContext(Dispatchers.Main) { t.cancelAll() } // STOP during a command
        running.await(); delay(300)
        assertEquals("exec after STOP on the main thread", "ok\n", t.exec("echo ok", 5000).stdout)
        withContext(Dispatchers.Main) { t.close() }
    }
}
