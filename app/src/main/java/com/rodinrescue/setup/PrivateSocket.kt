package com.rodinrescue.setup

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.IOException

/**
 * A Unix socket file in the app's private folder, for the setup server's root relay ([SetupServer.Relay]). Other apps
 * can't open it (the folder is the app's own); root can. Not the abstract namespace, which any app can connect to.
 */
class PrivateUnixAcceptor(private val path: String) : Acceptor {
    private val bound: LocalSocket
    private val server: LocalServerSocket
    @Volatile private var closed = false

    init {
        File(path).delete()   // left by an earlier run: bind fails on an existing file
        bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        bound.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        server = LocalServerSocket(bound.fileDescriptor)
    }

    override fun accept(): Conn {
        val c = server.accept()
        if (closed) { c.close(); throw IOException("closed") }
        c.soTimeout = 5000
        return Conn(c.inputStream, c.outputStream) { c.close() }
    }

    override fun close() {
        closed = true
        // close() alone doesn't wake a blocked accept() on Android: connect once so it returns, then close.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) } }
        runCatching { server.close() }; runCatching { bound.close() }
        File(path).delete()
    }
}
