package com.rodinrescue

import com.rodinrescue.setup.*
import com.rodinrescue.transport.*
import kotlinx.coroutines.*
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

class SetupTest {
    @Before fun bc() { Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1) }

    @Test fun generatedKeyAuthenticatesAgainstRealSshServer() = runBlocking {
        val id = KeyManager.generate()
        val authorized = AuthorizedKeyEntry.parseAuthorizedKeyEntry(id.publicLine).resolvePublicKey(null, null, null)
        val srv = SshServer.setUpDefaultServer().apply {
            port = 0; keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hk", ".ser"))
            setPublickeyAuthenticator { _, key, _ -> KeyUtils.compareKeys(key, authorized) }
            setCommandFactory { _, c -> object : org.apache.sshd.server.command.Command {
                lateinit var o: java.io.OutputStream; lateinit var cb: org.apache.sshd.server.ExitCallback
                override fun setInputStream(i: java.io.InputStream) {}; override fun setOutputStream(x: java.io.OutputStream) { o = x }
                override fun setErrorStream(e: java.io.OutputStream) {}; override fun setExitCallback(c: org.apache.sshd.server.ExitCallback) { cb = c }
                override fun start(ch: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) { o.write("key-auth-ok\n".toByteArray()); o.flush(); cb.onExit(0) }
                override fun destroy(ch: org.apache.sshd.server.channel.ChannelSession) {} } }
            start()
        }
        try {
            val store = object : HostKeyStore { val m = mutableMapOf<String, String>(); override fun pinnedHostKey(hostPort: String) = m[hostPort]; override fun pinHostKey(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }; override fun forgetHostKey(hostPort: String) {} }
            store.pinHostKey("127.0.0.1:${srv.port}", "SHA256:other," + SshTransport.probeHostKey("127.0.0.1", srv.port))   // multi-fingerprint pin
            val t = SshTransport(SshCredentials("127.0.0.1", srv.port, "anyone", privateKeyPem = id.privatePem), store)
            t.connect()
            assertEquals("key-auth-ok\n", t.exec("x").stdout); t.close()
        } finally { srv.stop(true) }
    }

    private fun runScript(url: String, env: Map<String, String>): Pair<Int, String> {
        val f = File.createTempFile("rodin", ".sh"); f.writeText(URL(url).readText())
        val pb = ProcessBuilder("sh", f.path).redirectErrorStream(true); pb.environment().putAll(env)
        val p = pb.start(); val out = p.inputStream.bufferedReader().readText(); p.waitFor(30, TimeUnit.SECONDS); return p.exitValue() to out
    }

    @Test fun laptopScriptServedAuthorizesKeyAndChecksIn() {
        val q = LinkedBlockingQueue<Checkin>()
        val id = KeyManager.generate()
        lateinit var srv: SetupServer
        srv = SetupServer(CoroutineScope(Dispatchers.IO), { LaptopScript.build("127.0.0.1", srv.boundPort, "tok123", id.publicLine) }) { q.put(it) }
        val port = srv.start("127.0.0.1", "tok123", 0)
        try {
            // wrong token is refused
            assertEquals(404, (URL("http://127.0.0.1:$port/s/wrong").openConnection() as HttpURLConnection).responseCode)
            val dir = Files.createTempDirectory("lap").toFile()
            File(dir, "ssh_host_ed25519_key.pub").also { Runtime.getRuntime().exec(arrayOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", File(dir, "ssh_host_ed25519_key").path)).waitFor() }
            val ak = File(dir, "authorized_keys")
            val (rc, out) = runScript("http://127.0.0.1:$port/s/tok123", mapOf("RODIN_SKIP_INSTALL" to "1", "RODIN_AUTHKEYS_FILE" to ak.path, "RODIN_HOSTKEY_GLOB" to "${dir.path}/ssh_host_*_key.pub"))
            assertEquals(out, 0, rc)
            val c = q.poll(5, TimeUnit.SECONDS)!!
            assertEquals(System.getProperty("user.name"), c.user); assertEquals("127.0.0.1", c.ip); assertEquals(1, c.fingerprints.size)
            assertTrue(c.fingerprints[0].startsWith("SHA256:"))
            assertEquals(1, ak.readLines().count { it == id.publicLine })
            runScript("http://127.0.0.1:$port/s/tok123", mapOf("RODIN_SKIP_INSTALL" to "1", "RODIN_AUTHKEYS_FILE" to ak.path, "RODIN_HOSTKEY_GLOB" to "${dir.path}/ssh_host_*_key.pub"))
            assertEquals("running the script twice must not duplicate the key", 1, ak.readLines().count { it == id.publicLine })
        } finally { srv.stop() }
    }

    @Test fun checkinRejectsGarbage() {
        val q = LinkedBlockingQueue<Checkin>()
        val srv = SetupServer(CoroutineScope(Dispatchers.IO), { "s" }) { q.put(it) }
        val port = srv.start("127.0.0.1", "t", 0)
        try {
            val c = URL("http://127.0.0.1:$port/r/t").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.outputStream.write("user=a;rm&ip=1.2.3&port=22&fps=x&host=h".toByteArray())
            assertEquals(400, c.responseCode); assertNull(q.poll(500, TimeUnit.MILLISECONDS))
        } finally { srv.stop() }
    }

    /**
     * Under a VPN: a root listener holds the port and forwards each connection to the app's private endpoint. Here a
     * plain loopback socket stands in for the Unix socket, and a small Python forwarder for root's `nc -L`.
     */
    @Test fun relayedServerTakesConnectionsFromThePrivateEndpoint() {
        val q = LinkedBlockingQueue<Checkin>()
        val priv = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = object : Acceptor {
            override fun accept() = priv.accept().let { c -> Conn(c.getInputStream(), c.getOutputStream()) { c.close() } }
            override fun close() = priv.close()
        }
        val fwd = File.createTempFile("fwd", ".py").apply { writeText("""
import socket, sys, threading
ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); ls.bind((sys.argv[1], int(sys.argv[2]))); ls.listen(5)
def pipe(a, b):
    while True:
        d = a.recv(65536)
        if not d: b.shutdown(socket.SHUT_WR); return
        b.sendall(d)
while True:
    c, _ = ls.accept(); u = socket.create_connection(("127.0.0.1", int(sys.argv[3])))
    threading.Thread(target=pipe, args=(c, u), daemon=True).start(); threading.Thread(target=pipe, args=(u, c), daemon=True).start()
""".trimIndent()) }
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        val relay = SetupServer.Relay(
            openPrivate = { priv.localPort.toString() to acceptor },
            listen = { ip, port, endpoint -> listOf("python3", "-I", fwd.path, ip, port.toString(), endpoint) },
            kill = { _, _ -> listOf("true") },
            rootHolds = { ip, port -> runCatching { java.net.Socket(ip, port).close() }.isSuccess })
        val srv = SetupServer(CoroutineScope(Dispatchers.IO), { "#!/bin/sh\necho script" }) { q.put(it) }
        val port = srv.start("127.0.0.1", "tok", freePort, relay)
        try {
            assertEquals(freePort, port)
            assertEquals("#!/bin/sh\necho script", URL("http://127.0.0.1:$port/s/tok").readText())
            fun post(): Int = (URL("http://127.0.0.1:$port/r/tok").openConnection() as HttpURLConnection).run {
                requestMethod = "POST"; doOutput = true
                outputStream.write("user=alice&ip=10.32.213.37&port=22&fps=SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&host=lap".toByteArray())
                responseCode
            }
            assertEquals(200, post())
            assertEquals("alice", q.poll(5, TimeUnit.SECONDS)!!.user)
            assertEquals("the token is spent by the first check-in", 409, post())
        } finally { srv.stop(); ProcessBuilder("pkill", "-f", "^python3 -I ${fwd.path}").start().waitFor() }
    }

    /** A listener that exits (port taken, root refused) is never shown as ready: the next port is tried, then an error. */
    @Test fun relayFailsClosedWhenRootCantHoldThePort() {
        var opened = 0; var closed = 0
        val acceptor = object : Acceptor { override fun accept(): Conn { Thread.sleep(60_000); error("unreachable") }; override fun close() { closed++ } }
        val relay = SetupServer.Relay({ opened++; "x" to acceptor }, { _, _, _ -> listOf("false") }, { _, _ -> listOf("true") }, { _, _ -> false })
        val srv = SetupServer(CoroutineScope(Dispatchers.IO), { "s" }) {}
        assertThrows(java.io.IOException::class.java) { srv.start("127.0.0.1", "t", 40000, relay) }
        assertEquals(1, opened); assertEquals(1, closed)
    }

    /** stop() while start() waits for root: the start undoes itself (closes its endpoint, ends its listener) and fails. */
    @Test fun aStopDuringStartLeavesNothingBehind() {
        var closed = 0; val killed = java.util.concurrent.atomic.AtomicInteger()
        val acceptor = object : Acceptor { override fun accept(): Conn { Thread.sleep(60_000); error("unreachable") }; override fun close() { closed++ } }
        lateinit var srv: SetupServer
        val relay = SetupServer.Relay({ "x" to acceptor }, { _, _, _ -> listOf("sleep", "30") },
            { _, _ -> killed.incrementAndGet(); listOf("true") }, { _, _ -> srv.stop(); true })
        srv = SetupServer(CoroutineScope(Dispatchers.IO), { "s" }) {}
        assertThrows(java.io.IOException::class.java) { srv.start("127.0.0.1", "t", 40100, relay) }
        assertEquals(1, closed)
        assertTrue("the listener started for the cancelled setup must be ended", killed.get() >= 2)
    }

    /** The root listener dies while the command is shown: the setup ends and the app is told to withdraw the command. */
    @Test fun aLostListenerEndsTheSetup() {
        val lost = LinkedBlockingQueue<String>()
        var closed = false
        val acceptor = object : Acceptor { override fun accept(): Conn { while (!closed) Thread.sleep(50); throw java.io.IOException("closed") }; override fun close() { closed = true } }
        val relay = SetupServer.Relay({ "x" to acceptor }, { _, _, _ -> listOf("sleep", "2") }, { _, _ -> listOf("true") }, { _, _ -> true })
        val srv = SetupServer(CoroutineScope(Dispatchers.IO), { "s" }) {}
        srv.onRelayLost = { lost.put(it) }
        srv.start("127.0.0.1", "t", 40200, relay)
        assertEquals("The setup link closed", lost.poll(10, TimeUnit.SECONDS))
        assertTrue(closed)
    }

    @Test fun rootListenerCommandsAreCheckedAndBounded() {
        assertEquals(listOf("su", "-c", "exec timeout 3600 toybox nc -L -s 10.32.213.199 -p 8787 toybox nc -U /data/user/0/com.rodinrescue/files/setup.sock"),
            RootRelay.listenCommand("10.32.213.199", 8787, "/data/user/0/com.rodinrescue/files/setup.sock"))
        assertThrows(IllegalArgumentException::class.java) { RootRelay.listenCommand("10.0.0.1;reboot", 8787, "/data/x/s.sock") }
        assertThrows(IllegalArgumentException::class.java) { RootRelay.listenCommand("10.0.0.1", 70000, "/data/x/s.sock") }
        assertThrows(IllegalArgumentException::class.java) { RootRelay.listenCommand("10.0.0.1", 8787, "/data/x/s.sock; reboot") }
        assertThrows(IllegalArgumentException::class.java) { RootRelay.listenCommand("10.0.0.1", 8787, "/data/../tmp/s.sock") }
        assertEquals(listOf("su", "-c", "pkill -f '^toybox nc -L -s 10.32.213.199 -p 8787 '"), RootRelay.killListenersCommand("10.32.213.199", 8787))
        assertThrows(IllegalArgumentException::class.java) { RootRelay.killListenersCommand("10.0.0.1'; reboot; '", 8787) }
    }

    /** Real rows from the phone: root's toybox on 10.32.213.199:8787, and an app (uid 10312) on another port. */
    @Test fun readsWhoListensFromProcNetTcp() {
        val rows = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: C7D5200A:2253 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4711 1 0000000000000000 100 0 0 10 0
   1: C7D5200A:2254 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10312        0 4712 1 0000000000000000 100 0 0 10 0
   2: C7D5200A:2255 0D20D50A:B1A0 01 00000000:00000000 00:00000000 00000000     0        0 4713 1 0000000000000000 100 0 0 10 0"""
        assertTrue(RootRelay.rootListens(rows, "10.32.213.199", 8787))
        assertFalse("an app's listener isn't root's", RootRelay.rootListens(rows, "10.32.213.199", 8788))
        assertFalse("not listening", RootRelay.rootListens(rows, "10.32.213.199", 8789))
        assertFalse(RootRelay.rootListens(rows, "10.32.213.198", 8787))
    }

    /** Starting a second setup server ends the first: a stray one kept a root listener on the USB link. */
    @Test fun aNewSetupServerStopsTheOldOne() {
        val a = SetupServer(CoroutineScope(Dispatchers.IO), { "a" }) {}
        val b = SetupServer(CoroutineScope(Dispatchers.IO), { "b" }) {}
        val pa = a.start("127.0.0.1", "t", 0)
        b.start("127.0.0.1", "t", 0)
        try {
            Thread.sleep(200)
            assertThrows(java.io.IOException::class.java) { (URL("http://127.0.0.1:$pa/s/t").openConnection() as HttpURLConnection).apply { connectTimeout = 1000 }.responseCode }
        } finally { b.stop() }
    }

    /** Tethering renames the USB interface every time: each setup drops Lifeline's recorded rules for vanished ones. */
    @Test fun setupDropsUfwRulesForVanishedUsbInterfaces() {
        val dir = Files.createTempDirectory("ufw").toFile()
        val log = File(dir, "log")
        val bin = File(dir, "bin").apply { mkdir() }
        File(bin, "ufw").apply { writeText("#!/bin/sh\necho \"ufw $*\" >> '${log.path}'\n"); setExecutable(true) }
        val net = File(dir, "net").apply { mkdir(); File(this, "enxlive").mkdir() }
        val state = File(dir, "state").apply { writeText("SVC=ssh\nWAS_ENABLED=0\nUFW=enxgone\nUFW=enxlive\nUFW=enxnow\nSSH_PORT=22\n") }
        val p = ProcessBuilder("sh", "-c", "set -e\n" + LaptopScript.PRUNE_UFW).apply {
            environment().putAll(mapOf("PATH" to "${bin.path}:${System.getenv("PATH")}", "STATE" to state.path, "IFACE" to "enxnow",
                "SSH_PORT" to "22", "RODIN_NET_DIR" to net.path))
        }.redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        assertEquals("ufw delete allow in on enxgone to any port 22 proto tcp", log.readText().trim())
        assertEquals("SVC=ssh\nWAS_ENABLED=0\nUFW=enxlive\nUFW=enxnow\nSSH_PORT=22\n", state.readText())
    }

    @Test fun scriptHasValidShellSyntax() {
        val f = File.createTempFile("rodin", ".sh"); f.writeText(LaptopScript.build("10.1.2.3", 8787, "tok", "ssh-rsa AAAA rodin-rescue"))
        for (sh in listOf("sh", "bash")) { val p = ProcessBuilder(sh, "-n", f.path).redirectErrorStream(true).start(); val o = p.inputStream.bufferedReader().readText(); assertEquals("$sh: $o", 0, p.waitFor()) }
    }

    @Test fun subnetMath() {
        assertEquals("10.89.173.0/24", NetFix.subnetOf(UsbIface("rndis0", "10.89.173.142", 24)))
        assertEquals("192.168.42.128/25", NetFix.subnetOf(UsbIface("rndis0", "192.168.42.129", 25)))
        assertTrue(NetFix.applyCommand(UsbIface("rndis0", "10.89.173.142", 24)).contains("ip rule add to 10.89.173.0/24 lookup local_network pref 10500"))
    }

    @Test fun findsUsbTetheringSwitchInRealPocoDump() {
        val xml = """<hierarchy><node index="1" text="" resource-id="" class="android.widget.Switch" package="com.android.settings" content-desc="Portable hotspot Turning on" checkable="true" checked="false" clickable="true" enabled="true" bounds="[37,488][1183,824]"/>
<node index="6" text="" resource-id="" class="android.widget.Switch" package="com.android.settings" content-desc="USB tethering USB connected, check to tether" checkable="true" checked="false" clickable="true" enabled="true" bounds="[37,1892][1183,2114]"/>
<node index="7" text="" class="android.widget.Switch" content-desc="Bluetooth tethering Sharing" checkable="true" checked="false" clickable="true" enabled="true" bounds="[37,2114][1183,2393]"/></hierarchy>"""
        val n = TetherAutomation.findUsbSwitch(xml)!!
        assertEquals(610, n.cx); assertEquals(2003, n.cy); assertFalse(n.checked)
        assertNull(TetherAutomation.findUsbSwitch("<hierarchy/>"))
    }
}
