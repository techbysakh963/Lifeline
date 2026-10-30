package com.rodinrescue.setup

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the laptop script changed (null = unknown, e.g. a laptop set up by an older version). */
data class LaptopChanges(val stateRecorded: Boolean, val installedSshd: Boolean?, val sshdWasEnabled: Boolean?, val sshdWasActive: Boolean?, val ufwIface: String?)

data class Checkin(val user: String, val ip: String, val port: Int, val fingerprints: List<String>, val hostname: String,
                   val changes: LaptopChanges = LaptopChanges(false, null, null, null, null))

/** One accepted connection's two streams. */
class Conn(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/** Where the server takes connections from. [close] must make a blocked [accept] throw. */
interface Acceptor : Closeable { fun accept(): Conn }

/**
 * Tiny HTTP server for the laptop setup. Serves the laptop script at /s/<token> and receives the laptop's check-in at
 * /r/<token> (once: the token is spent by the first check-in). The token is random per setup; the server is stopped
 * after the check-in.
 *
 * Normally it listens on the phone's USB-tether address only. Under a VPN that isn't bypassable, Android drops the USB
 * link's packets to the app, so a [Relay] is used: a root listener holds the port for the whole setup and passes each
 * connection to a Unix socket in the app's private folder, which only the app (and root) can open. The port is never
 * released between connections, so no other app can take it and answer the laptop's `curl … | sudo sh`.
 */
class SetupServer(private val scope: CoroutineScope, private val scriptProvider: () -> String, private val onCheckin: (Checkin) -> Unit) {
    /**
     * [openPrivate] opens the app's private endpoint and returns the address root connects to; [listen] is the root
     * listener for ip:port that forwards to that address; [kill] ends such listeners; [rootHolds] reads whether root
     * owns a listener on ip:port (checked before the laptop command is shown).
     */
    class Relay(
        val openPrivate: () -> Pair<String, Acceptor>,
        val listen: (ip: String, port: Int, endpoint: String) -> List<String>,
        val kill: (ip: String, port: Int) -> List<String>,
        val rootHolds: (ip: String, port: Int) -> Boolean,
    )

    private val lock = Any()
    private var gen = 0                      // bumped by every start and stop: a start that was stopped meanwhile undoes itself
    private var acceptor: Acceptor? = null
    private var job: Job? = null
    private var watch: Job? = null
    private var relayProcess: Process? = null
    private var relayKill: List<String>? = null
    private val checkedIn = AtomicBoolean(false)
    var boundPort: Int = 0; private set
    /** Called when a relayed setup ends by itself (the root listener is gone, or the setup expired): withdraw the command. */
    var onRelayLost: ((String) -> Unit)? = null

    /** Blocking (it may run root commands): call it off the main thread. Throws when no port could be opened. */
    fun start(bindIp: String, token: String, port: Int = 8787, relay: Relay? = null): Int {
        stop()
        // One setup server per app: a second screen once started its own and the first kept a root listener alive.
        synchronized(SetupServer) { running?.takeIf { it !== this }?.stop(); running = this }
        val my = synchronized(lock) { ++gen }
        checkedIn.set(false)
        val acc = if (relay != null) openRelayed(bindIp, port, relay, my) else {
            val ss = try { ServerSocket(port, 5, InetAddress.getByName(bindIp)) } catch (e: Exception) { ServerSocket(0, 5, InetAddress.getByName(bindIp)) }
            boundPort = ss.localPort
            object : Acceptor {
                override fun accept() = ss.accept().let { c -> c.soTimeout = 5000; Conn(c.getInputStream(), c.getOutputStream()) { c.close() } }
                override fun close() = ss.close()
            }
        }
        synchronized(lock) {
            if (gen != my) { acc.close(); throw IOException("Setup was cancelled") }   // stop() ran meanwhile; it ended the listener
            acceptor = acc
            job = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val c = try { acc.accept() } catch (e: Exception) { break }
                    launch { c.use { runCatching { handle(it.input, it.output, token) } } }
                }
            }
            if (relay != null) watch = scope.launch(Dispatchers.IO) { watchRelay(bindIp, boundPort, relay, my) }
        }
        return boundPort
    }

    private fun openRelayed(bindIp: String, port: Int, relay: Relay, my: Int): Acceptor {
        runCatching { ProcessBuilder(relay.kill(bindIp, port)).start().waitFor(5, TimeUnit.SECONDS) }   // left from an earlier run
        val (endpoint, acc) = relay.openPrivate()
        for (p in port..port + 10) {
            val proc = try { ProcessBuilder(relay.listen(bindIp, p, endpoint)).redirectErrorStream(true).start() } catch (e: Exception) { break }
            val kill = relay.kill(bindIp, p)
            // Registered at once, so a stop() from now on ends it too.
            val current = synchronized(lock) { if (gen == my) { relayProcess = proc; relayKill = kill; true } else false }
            if (!current) { endRelay(proc, kill); acc.close(); throw IOException("Setup was cancelled") }
            // Up to 3 s for root to bind. A listener that exits lost the port (or root was refused): try the next one.
            var holds = false
            for (i in 1..15) { Thread.sleep(200); if (!proc.isAlive) break; if (relay.rootHolds(bindIp, p)) { holds = true; break } }
            if (holds) { boundPort = p; return acc }
            synchronized(lock) { if (relayProcess === proc) { relayProcess = null; relayKill = null } }
            endRelay(proc, kill)
        }
        acc.close()
        throw IOException("Couldn't open the setup port on $bindIp as root")
    }

    /** Ends the setup when the root listener stops holding the port, and before the listener's own one-hour limit. */
    private suspend fun watchRelay(ip: String, port: Int, relay: Relay, my: Int) {
        val deadline = System.currentTimeMillis() + RELAY_LIFETIME_MS
        while (true) {
            delay(3000)
            val proc = synchronized(lock) { if (gen != my) return; relayProcess }
            val reason = when {
                proc == null || !proc.isAlive || !relay.rootHolds(ip, port) -> "The setup link closed"
                System.currentTimeMillis() > deadline -> "The setup link expired"
                else -> continue
            }
            if (synchronized(lock) { gen == my }) { stop(); onRelayLost?.invoke(reason) }
            return
        }
    }

    fun stop() {
        val proc: Process?; val kill: List<String>?
        synchronized(lock) {
            gen++
            runCatching { acceptor?.close() }; acceptor = null
            job?.cancel(); job = null; watch?.cancel(); watch = null
            proc = relayProcess; relayProcess = null
            kill = relayKill; relayKill = null
        }
        if (proc != null || kill != null) Thread { endRelay(proc, kill) }.apply { isDaemon = true; start() }
        synchronized(SetupServer) { if (running === this) running = null }
    }

    /** The root listener may not be our child (a root manager can start it for us): also end it by its command line. */
    private fun endRelay(proc: Process?, kill: List<String>?) {
        proc?.destroy()
        kill?.let { k -> runCatching { ProcessBuilder(k).start().waitFor(10, TimeUnit.SECONDS) } }
    }

    private companion object {
        var running: SetupServer? = null
        /** Shorter than the root listener's `timeout 3600`, so the command is never shown longer than the port is held. */
        const val RELAY_LIFETIME_MS = 50 * 60_000L
    }

    /** One request on [input]/[output]. */
    private fun handle(input: InputStream, out: OutputStream, token: String) {
        val ins = input.buffered()
        fun line(): String? { val b = ByteArrayOutputStream(); while (true) { val x = ins.read(); if (x < 0) return if (b.size() == 0) null else b.toString(); if (x == '\n'.code) return b.toString().trimEnd('\r'); if (b.size() > 4096) return null; b.write(x) } }
        val req = line() ?: return
        var len = 0
        while (true) { val h = line() ?: break; if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val parts = req.split(" ")
        val method = parts.getOrNull(0); val path = parts.getOrNull(1) ?: ""
        fun respond(code: Int, body: String, type: String = "text/plain") {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code X\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b); out.flush()
        }
        when {
            method == "GET" && path == "/s/$token" -> respond(200, scriptProvider(), "text/x-shellscript")
            method == "POST" && path == "/r/$token" && len in 1..4096 -> {
                val body = ByteArray(len); var n = 0; while (n < len) { val r = ins.read(body, n, len - n); if (r < 0) break; n += r }
                val kv = String(body, 0, n).split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
                val ip = kv["ip"].orEmpty()
                if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip) || !Regex("^[A-Za-z0-9._-]{1,64}$").matches(kv["user"].orEmpty())) { respond(400, "bad"); return }
                if (!checkedIn.compareAndSet(false, true)) { respond(409, "already checked in"); return }
                val fps = kv["fps"].orEmpty().split(",").filter { Regex("^SHA256:[A-Za-z0-9+/=]{20,60}$").matches(it) }
                respond(200, "ok")
                fun flag(k: String) = when (kv[k]) { "1" -> true; "0" -> false; else -> null }
                val changes = LaptopChanges(kv["state"] == "1", flag("inst"), flag("en"), flag("act"),
                    kv["ufw"]?.takeIf { Regex("^[A-Za-z0-9_.-]{1,15}$").matches(it) })
                onCheckin(Checkin(kv["user"]!!, ip, kv["port"]?.toIntOrNull() ?: 22, fps, kv["host"].orEmpty().take(64), changes))
            }
            else -> respond(404, "not found")
        }
    }
}
