package com.rodinrescue

import com.rodinrescue.transport.PipeSocket
import com.rodinrescue.transport.RootRelay
import com.rodinrescue.transport.UsbIface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class RootRelayTest {
    @Test fun onlyPlainAddressesAndPortsReachTheRootCommand() {
        assertEquals(listOf("su", "-c", "exec toybox nc -w 10 10.32.213.25 22"), RootRelay.pipeCommand("10.32.213.25", 22))
        assertThrows(IllegalArgumentException::class.java) { RootRelay.pipeCommand("10.0.0.1; rm -rf /", 22) }
        assertThrows(IllegalArgumentException::class.java) { RootRelay.pipeCommand("10.0.0.1", 0) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { RootRelay.scan(UsbIface("rndis0", "10.0.0.\$(reboot)", 24)) } }
        val s = RootRelay.scanScript("10.32.213", "10.32.213.199", 22)
        assertTrue(s.contains("ip=10.32.213.\$i") && s.contains("toybox nc -z -w 1 \$ip 22") && s.endsWith("wait"))
    }

    /** The SSH bytes go through the child's stdin/stdout: no listener, nothing another app could reach (review 2026-10-29). */
    @Test fun aPipeSocketCarriesBytesThroughTheProcess() {
        val s = PipeSocket { listOf("cat") }
        s.connect(InetSocketAddress("10.32.213.25", 22), 1000)
        assertTrue(s.isConnected); assertEquals(22, s.port)
        s.getOutputStream().apply { write("SSH-2.0-test\r\n".toByteArray()); flush() }
        assertEquals("SSH-2.0-test", s.getInputStream().bufferedReader().readLine())
        s.close(); assertTrue(s.isClosed)
    }
}
