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
