package com.rodinrescue.setup

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** What the laptop script changed (null = unknown, e.g. a laptop set up by an older version). */
data class LaptopChanges(val stateRecorded: Boolean, val installedSshd: Boolean?, val sshdWasEnabled: Boolean?, val sshdWasActive: Boolean?, val ufwIface: String?)

data class Checkin(val user: String, val ip: String, val port: Int, val fingerprints: List<String>, val hostname: String,
                   val changes: LaptopChanges = LaptopChanges(false, null, null, null, null))

/**
 * Tiny HTTP server bound to the phone's USB-tether address ONLY. Serves the laptop script at /s/<token> and receives the
 * laptop's check-in at /r/<token>. The token is random per setup; the server is stopped after the check-in.
 */
class SetupServer(private val scope: CoroutineScope, private val scriptProvider: () -> String, private val onCheckin: (Checkin) -> Unit) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    var boundPort: Int = 0; private set

    fun start(bindIp: String, token: String, port: Int = 8787): Int {
        stop()
        val ss = try { ServerSocket(port, 5, InetAddress.getByName(bindIp)) } catch (e: Exception) { ServerSocket(0, 5, InetAddress.getByName(bindIp)) }
        server = ss; boundPort = ss.localPort
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                launch { runCatching { handle(c, token) }; runCatching { c.close() } }
            }
        }
        return boundPort
    }

    fun stop() { runCatching { server?.close() }; job?.cancel(); server = null }

    private fun handle(c: Socket, token: String) {
        c.soTimeout = 5000
        val ins = c.getInputStream().buffered()
        fun line(): String? { val b = ByteArrayOutputStream(); while (true) { val x = ins.read(); if (x < 0) return if (b.size() == 0) null else b.toString(); if (x == '\n'.code) return b.toString().trimEnd('\r'); if (b.size() > 4096) return null; b.write(x) } }
        val req = line() ?: return
        var len = 0
        while (true) { val h = line() ?: break; if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val parts = req.split(" ")
        val method = parts.getOrNull(0); val path = parts.getOrNull(1) ?: ""
        val out = c.getOutputStream()
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
