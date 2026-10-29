package com.rodinrescue.transport

@kotlinx.serialization.Serializable
/** Result of a remote command. [exitCode] is null when the command was cancelled or timed out. */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    val durationMs: Long = 0
) {
    val ok get() = exitCode == 0 && !timedOut && !cancelled
}

/** A channel to the target machine over which commands can be executed. SSH is the only MVP implementation. */
interface CommandTransport {
    val description: String
    suspend fun connect()
    /** Execute [command] with bounded time/output. [stdin] (e.g. a sudo password) is written then closed. */
    suspend fun exec(command: String, timeoutMs: Long = 30_000, maxOutputBytes: Int = 64 * 1024, stdin: String? = null): ExecResult
    /** Abort any in-flight command. Safe to call from any thread. */
    fun cancelAll()
    fun close()
    val isConnected: Boolean
}

class HostKeyUnknownException(val hostPort: String, val fingerprint: String) : Exception("Host key for $hostPort is not trusted yet: $fingerprint")
class HostKeyMismatchException(val hostPort: String, val expected: String, val actual: String) :
    Exception("HOST KEY CHANGED for $hostPort! expected $expected but got $actual")

/** Persistent store of trusted SSH host-key fingerprints (trust-on-first-use, with explicit user confirmation). */
interface HostKeyStore {
    fun pinnedHostKey(hostPort: String): String?
    fun pinHostKey(hostPort: String, fingerprint: String)
    fun forgetHostKey(hostPort: String)
}
