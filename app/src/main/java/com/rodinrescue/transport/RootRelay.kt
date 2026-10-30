package com.rodinrescue.transport

import com.rodinrescue.root.RootShell
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Reaching the USB link when a VPN is on. Found on the POCO, 2026-10-28 (WireGuard, not bypassable): Lifeline's SYN
 * left on rndis0 and the PC's SYN-ACK came back on rndis0, but Android never delivered it to the app (apps under a
 * non-bypassable VPN only receive through the VPN), so the routing rule ([com.rodinrescue.setup.NetFix]) wasn't enough.
 * Root and shell aren't filtered. So, only while a VPN is on: the scan runs as root, and SSH talks through a root
 * `nc` over that process's own stdin/stdout ([PipeSocket]): a private pipe, no listener any other app could reach
 * (security review 2026-10-29; a first version used a one-shot loopback listener). The laptop setup server is reached
 * the same way, through a root listener ([listenCommand]); found 2026-10-30 when the laptop's `curl` timed out.
 */
object RootRelay {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** The root scan: hosts on [iface]'s /24 that accept TCP on [port]. */
    suspend fun scan(iface: UsbIface, port: Int = 22): List<Candidate> {
        // The whole address is checked: it goes into a root command.
        require(IPV4.matches(iface.phoneIp) && port in 1..65535)
        val base = iface.phoneIp.substringBeforeLast('.')
        val out = RootShell.run(scanScript(base, iface.phoneIp, port), timeoutSec = 30).output
        return out.lines().map { it.trim() }.filter { it.startsWith("OPEN ") }.map { Candidate(it.removePrefix("OPEN "), true) }.sortedBy { it.ip.substringAfterLast('.').toInt() }
    }

    internal fun scanScript(base: String, self: String, port: Int) =
        "for i in \$(seq 1 254); do ip=$base.\$i; [ \"\$ip\" = \"$self\" ] && continue; " +
        "(toybox nc -z -w 1 \$ip $port >/dev/null 2>&1 && echo \"OPEN \$ip\") & done; wait"

    /** The root command that carries one connection to [ip]:[port] over its stdin/stdout. */
    internal fun pipeCommand(ip: String, port: Int): List<String> {
        require(IPV4.matches(ip) && port in 1..65535)
        return listOf("su", "-c", "exec toybox nc -w 10 $ip $port")
    }

    private val PRIVATE_PATH = Regex("""^/data/[A-Za-z0-9._/-]+$""")

    /**
     * The root listener for the laptop setup server ([com.rodinrescue.setup.SetupServer]): holds [ip]:[port] until it is
     * killed and passes each connection to the app's private Unix socket [endpoint]. Holding the port the whole time
     * means no other app can take it between connections. One left behind (the app died) ends after an hour, and until
     * then only forwards to a socket nobody listens on.
     */
    fun listenCommand(ip: String, port: Int, endpoint: String): List<String> {
        require(IPV4.matches(ip) && port in 1..65535 && PRIVATE_PATH.matches(endpoint) && ".." !in endpoint)
        return listOf("su", "-c", "exec timeout 3600 toybox nc -L -s $ip -p $port toybox nc -U $endpoint")
    }

    /** Ends listeners from [listenCommand] on [ip]:[port]. The ^ keeps the shell running pkill from matching itself. */
    fun killListenersCommand(ip: String, port: Int): List<String> {
        require(IPV4.matches(ip) && port in 1..65535)
        return listOf("su", "-c", "pkill -f '^toybox nc -L -s $ip -p $port '")
    }

    /** Whether a root-owned socket listens on [ip]:[port] (read from /proc/net/tcp as root). */
    fun rootHolds(ip: String, port: Int): Boolean {
        require(IPV4.matches(ip) && port in 1..65535)
        val out = runCatching {
            val p = ProcessBuilder("su", "-c", "cat /proc/net/tcp").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
        }.getOrDefault("")
        return rootListens(out, ip, port)
    }

    /** /proc/net/tcp parser: a LISTEN (0A) row for [ip]:[port] owned by uid 0. Addresses are little-endian hex. */
    internal fun rootListens(procNetTcp: String, ip: String, port: Int): Boolean {
        val addr = ip.split('.').reversed().joinToString("") { "%02X".format(it.toInt()) } + ":%04X".format(port)
        return procNetTcp.lines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .any { f -> f.size > 7 && f[1].equals(addr, true) && f[3] == "0A" && f[7] == "0" }
    }

    /** Sockets that go through a root `nc` (for sshj's `socketFactory`). */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket() = PipeSocket { a -> pipeCommand(a.address.hostAddress!!, a.port) }
        private fun unsupported(): Nothing = throw UnsupportedOperationException("connect through createSocket()")
        override fun createSocket(host: String?, port: Int) = unsupported()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = unsupported()
        override fun createSocket(host: InetAddress?, port: Int) = unsupported()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = unsupported()
    }
}

/**
 * A "socket" whose bytes go through a child process's stdin/stdout ([command] for the address being connected to).
 * Only what an SSH client needs: connect, the two streams, close. Timeouts are left to the SSH layer (keepalives).
 */
class PipeSocket(private val command: (InetSocketAddress) -> List<String>) : Socket() {
    private var process: Process? = null
    private var target: InetSocketAddress? = null
    @Volatile private var closed = false

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val t = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("an IP address and port")
        target = t
        process = ProcessBuilder(command(t)).start().also { p ->
            // Drain stderr so the child never blocks on it.
            Thread { runCatching { p.errorStream.readBytes() } }.apply { isDaemon = true; start() }
        }
    }
    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun getInputStream(): InputStream = process?.inputStream ?: throw java.net.SocketException("not connected")
    override fun getOutputStream(): OutputStream = process?.outputStream ?: throw java.net.SocketException("not connected")
    override fun isConnected() = process != null
    override fun isClosed() = closed
    override fun close() { closed = true; process?.destroy() }
    override fun getInetAddress(): InetAddress? = target?.address
    override fun getPort() = target?.port ?: 0
    override fun getRemoteSocketAddress(): SocketAddress? = target
    override fun setSoTimeout(timeout: Int) {}
    override fun getSoTimeout() = 0
    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}
}
