package com.rodinrescue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rodinrescue.ai.*
import com.rodinrescue.runtime.Ubuntu

/**
 * Debug builds only. Runs a built-in CLI's real connection test (sign-in + a long-session tool round trip + a follow-up
 * from memory) inside the app, where the CLI has the network, and logs the result under the tag LifelineSessionTest:
 *   adb shell am broadcast -a com.rodinrescue.DEBUG_SESSION_TEST -p com.rodinrescue --es cli codex
 * cli = claude | codex | geminicli; uses the model and effort chosen in the app. Spends a little of the user's plan.
 */
class DebugSessionTest : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // --es rescue <ip>: SSH into the rescue system like the app does (key drive's host key, phone key, root relay); log the result.
        intent.getStringExtra("rescue")?.let { ip ->
            Thread {
                val r = runCatching { kotlinx.coroutines.runBlocking {
                    val drive = java.io.File(app.filesDir, "rescue/key.img").readBytes()
                    val pub = com.rodinrescue.usb.RescueSystem.hostPublicOf(drive) ?: error("no host_pub on the key drive")
                    val fp = com.rodinrescue.transport.fingerprintOf(pub)
                    val id = com.rodinrescue.setup.KeyManager((app as RodinApp).secrets).identity()
                    Log.i(TAG, "rescue: expecting host key $fp, phone key ${id.publicLine.take(40)}…")
                    val store = object : com.rodinrescue.transport.HostKeyStore {
                        override fun pinnedHostKey(hostPort: String) = fp
                        override fun pinHostKey(hostPort: String, fingerprint: String) {}
                        override fun forgetHostKey(hostPort: String) {}
                    }
                    val t = com.rodinrescue.transport.SshTransport(com.rodinrescue.transport.SshCredentials(ip, 22, "root", null, id.privatePem, null), store,
                        socketFactory = com.rodinrescue.transport.RootRelay.socketFactory)
                    t.connect(); val out = t.exec("hostname; uname -r; lsblk -dn -o NAME,SIZE | head -5", 10_000).stdout; t.close(); out
                } }
                Log.i(TAG, "rescue $ip -> " + r.fold({ "OK ${it.lines().joinToString(" | ")}" }, { "${it.javaClass.simpleName}: ${it.message} / cause: ${it.cause}" }))
            }.start()
            return
        }
        // --es net <ip>: open a TCP connection to <ip>:22 from inside the app and log what happens (network debugging).
        intent.getStringExtra("net")?.let { ip ->
            Thread {
                val r = runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(ip, 22), 3000); s.getInputStream().bufferedReader().readLine() } }
                Log.i(TAG, "net $ip:22 -> " + r.fold({ "OK $it" }, { "${it.javaClass.simpleName}: ${it.message}" }))
                Log.i(TAG, "net ifaces: " + com.rodinrescue.transport.UsbNet.detect())
            }.start()
            return
        }
        val cli = Ubuntu.Cli.of(intent.getStringExtra("cli") ?: "claude") ?: run { Log.w(TAG, "unknown cli"); return }
        val settings = com.rodinrescue.data.Settings(app)
        val ubuntu = Ubuntu(app, cli)
        val stateless: CliProvider = when (cli) { Ubuntu.Cli.CLAUDE -> ClaudeCliProvider(ubuntu); Ubuntu.Cli.CODEX -> CodexCliProvider(ubuntu); Ubuntu.Cli.AGY -> AgyCliProvider(ubuntu) }
        stateless.model = { settings.cliModel(cli.id) }; stateless.effort = { settings.cliEffort(cli.id) }
        val session = when (stateless) { is ClaudeCliProvider -> ClaudeSessionProvider(stateless, ubuntu); is CodexCliProvider -> CodexSessionProvider(stateless, ubuntu); is AgyCliProvider -> AgySessionProvider(stateless, ubuntu); else -> return }
        Log.i(TAG, "${cli.id}: starting (model '${settings.cliModel(cli.id)}', effort '${settings.cliEffort(cli.id)}')")
        com.rodinrescue.runtime.RuntimeService.hold(app, "Testing ${cli.title}…")
        Thread {
            val r = runCatching { kotlinx.coroutines.runBlocking { session.testConnection() } }
            Log.i(TAG, "${cli.id}: " + r.fold({ "OK $it" }, { "FAILED ${it.message}" }) + " | status: ${session.status} | used: ${stateless.usedModel} ${stateless.usedEffort}")
            session.trace.forEach { Log.i(TAG, "  | $it") }
            com.rodinrescue.runtime.RuntimeService.release(app)
        }.start()
    }
    private companion object { const val TAG = "LifelineSessionTest" }
}
