package com.rodinrescue.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SshCredentials(
    val host: String,
    val port: Int,
    val user: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val keyPassphrase: String? = null
)

/** OpenSSH-style SHA256 fingerprint of a public host key. */
fun fingerprintOf(key: PublicKey): String {
    val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(wire)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

class SshTransport(
    private val creds: SshCredentials,
    private val hostKeys: HostKeyStore,
    private val connectTimeoutMs: Int = 8000,
    /** How to reach [creds] (e.g. [RootRelay.socketFactory] under a VPN); null = a normal socket. Host keys stay pinned to [creds]. */
    private val socketFactory: javax.net.SocketFactory? = null
) : CommandTransport {

    private var ssh: SSHClient? = null
    private val active = ConcurrentHashMap.newKeySet<Session>()
    @Volatile private var cancelFlag = false

    private val hostPort get() = "${creds.host}:${creds.port}"
    override val description get() = "ssh ${creds.user}@$hostPort"
    override val isConnected get() = ssh?.isConnected == true && ssh?.isAuthenticated == true

    private class MismatchHolder { var actual: String? = null; var expected: String? = null; var unknown: String? = null }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        close()
        val holder = MismatchHolder()
        val client = SSHClient(DefaultConfig())
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val fp = fingerprintOf(key)
                val pinned = hostKeys.pinnedHostKey(hostPort)
                return when {
                    pinned == null -> { holder.unknown = fp; false }
                    pinned.split(',').contains(fp) -> true
                    else -> { holder.expected = pinned; holder.actual = fp; false }
                }
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        client.connectTimeout = connectTimeoutMs
        client.timeout = 30_000
        try {
            socketFactory?.let { client.socketFactory = it }
            client.connect(creds.host, creds.port)
        } catch (e: Exception) {
            runCatching { client.close() }
            holder.unknown?.let { throw HostKeyUnknownException(hostPort, it) }
            if (holder.actual != null) throw HostKeyMismatchException(hostPort, holder.expected!!, holder.actual!!)
            throw e
        }
        try {
            client.connection.keepAlive.keepAliveInterval = 15
            if (creds.privateKeyPem != null) {
                val finder = creds.keyPassphrase?.let {
                    object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>?) = it.toCharArray()
                        override fun shouldRetry(resource: Resource<*>?) = false
                    }
                }
                val kp = if (finder != null) client.loadKeys(creds.privateKeyPem, null, finder) else client.loadKeys(creds.privateKeyPem, null, null)
                client.authPublickey(creds.user, kp)
            } else {
                client.authPassword(creds.user, creds.password ?: "")
            }
        } catch (e: Exception) { runCatching { client.close() }; throw e }
        ssh = client
    }

    override suspend fun exec(command: String, timeoutMs: Long, maxOutputBytes: Int, stdin: String?): ExecResult = coroutineScope {
        val client = ssh?.takeIf { it.isConnected } ?: throw IllegalStateException("SSH not connected")
        cancelFlag = false
        val start = System.currentTimeMillis()
        val session = withContext(Dispatchers.IO) { client.startSession() }
        active.add(session)
        val out = CappedBuffer(maxOutputBytes)
        val err = CappedBuffer(maxOutputBytes)
        var timedOut = false
        var exit: Int? = null
        try {
            val cmd = withContext(Dispatchers.IO) { session.exec(command) }
            withContext(Dispatchers.IO) {
                runCatching {
                    stdin?.let { cmd.outputStream.write(it.toByteArray()) }
                    cmd.outputStream.flush(); cmd.outputStream.close()
                }
            }
            val readers = listOf(
                async(Dispatchers.IO) { pump(cmd.inputStream, out) },
                async(Dispatchers.IO) { pump(cmd.errorStream, err) }
            )
            try {
                withTimeout(timeoutMs) { readers.awaitAll() }
                withContext(Dispatchers.IO) { cmd.join(3, TimeUnit.SECONDS) }
                exit = cmd.exitStatus
            } catch (e: TimeoutCancellationException) {
                timedOut = true
            }
        } catch (e: CancellationException) {
            runCatching { session.close() }
            throw e
        } catch (e: Exception) {
            if (!cancelFlag) { active.remove(session); runCatching { session.close() }; throw e }
        } finally {
            active.remove(session)
            withContext(NonCancellable + Dispatchers.IO) { runCatching { session.close() } }
        }
        ExecResult(
            out.text(), err.text(), if (timedOut || cancelFlag) null else exit,
            timedOut = timedOut, cancelled = cancelFlag, truncated = out.truncated || err.truncated,
            durationMs = System.currentTimeMillis() - start
        )
    }

    private fun pump(input: InputStream, sink: CappedBuffer) {
        val buf = ByteArray(4096)
        try { while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, n) } } catch (_: Exception) {}
    }

    override fun cancelAll() {
        cancelFlag = true
        val sessions = active.toList()
        offMain { sessions.forEach { runCatching { it.close() } } }
    }

    override fun close() {
        cancelAll(); cancelFlag = false
        val c = ssh; ssh = null
        offMain { runCatching { c?.disconnect() } }
    }

    /** Interactive PTY shell for the Terminal screen. */
    fun openShell(): ShellHandle {
        val client = ssh ?: throw IllegalStateException("SSH not connected")
        val session = client.startSession()
        session.allocatePTY("xterm", 100, 40, 0, 0, emptyMap())
        val shell = session.startShell()
        return ShellHandle(session, shell)
    }

    class ShellHandle(private val session: Session, private val shell: Session.Shell) {
        val output: Flow<String> = flow {
            val buf = ByteArray(4096)
            val ins = shell.inputStream
            while (true) { val n = ins.read(buf); if (n < 0) break; emit(String(buf, 0, n, Charsets.UTF_8)) }
        }.flowOn(Dispatchers.IO)
        fun send(text: String) = offMain { shell.outputStream.write(text.toByteArray()); shell.outputStream.flush() }
        fun close() = offMain { runCatching { session.close() } }
    }

    companion object {
        /**
         * Every non-suspending call that writes to the socket (close/cancel/shell send) runs on this one background thread.
         * On Android's main thread a socket write throws NetworkOnMainThreadException AFTER sshj has already encrypted the
         * packet and advanced its cipher sequence; the packet is never sent, the next one is encrypted for the wrong
         * sequence number, and the server drops the whole connection ("Bad packet length … Connection corrupted").
         * Running here also serializes these writes. Waits up to 10 s so callers keep their ordering guarantees.
         */
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-offmain").apply { isDaemon = true } }
        private fun <T> offMain(block: () -> T): T =
            if (Thread.currentThread().name == "ssh-offmain") block()
            else writer.submit<T> { block() }.let { f ->
                try { f.get(10, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            }

        /** Connects only to read the server's host key fingerprint, then disconnects. Never authenticates. */
        suspend fun probeHostKey(host: String, port: Int): String = withContext(Dispatchers.IO) {
            var fp: String? = null
            val c = SSHClient(DefaultConfig())
            c.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String?, p: Int, key: PublicKey): Boolean { fp = fingerprintOf(key); return false }
                override fun findExistingAlgorithms(hostname: String?, p: Int): List<String> = emptyList()
            })
            c.connectTimeout = 6000
            try { c.connect(host, port) } catch (_: Exception) {} finally { runCatching { c.close() } }
            fp ?: throw IllegalStateException("Could not read host key from $host:$port (is sshd running?)")
        }
    }
}

/** Byte-capped sink that remembers whether it dropped data. Keeps the head and a tail window. */
class CappedBuffer(private val cap: Int) {
    private val head = ByteArrayOutputStream()
    private val headCap = cap * 2 / 3
    private val tail = ArrayDeque<Byte>()
    private val tailCap = cap - headCap
    var truncated = false; private set
    @Synchronized fun write(b: ByteArray, n: Int) {
        for (i in 0 until n) {
            if (head.size() < headCap) head.write(b[i].toInt())
            else { tail.addLast(b[i]); if (tail.size > tailCap) { tail.removeFirst(); truncated = true } }
        }
    }
    @Synchronized fun text(): String {
        val h = String(head.toByteArray(), Charsets.UTF_8)
        if (tail.isEmpty()) return h
        val t = String(tail.toByteArray(), Charsets.UTF_8)
        return if (truncated) "$h\n…[output truncated: middle omitted]…\n$t" else h + t
    }
}
