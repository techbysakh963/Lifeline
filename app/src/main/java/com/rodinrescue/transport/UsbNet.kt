package com.rodinrescue.transport

import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class UsbIface(val name: String, val phoneIp: String, val prefix: Int)
data class Candidate(val ip: String, val sshOpen: Boolean)

/** Detects the USB network interface created by Android USB tethering (RNDIS/NCM) and finds SSH servers on it. */
object UsbNet {
    private val usbNames = Regex("^(rndis|usb|ncm|eth|ap)\\d*$")

    fun detect(): List<UsbIface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && usbNames.matches(it.name) }
            .flatMap { ni -> ni.interfaceAddresses.filter { it.address is Inet4Address }.map { UsbIface(ni.name, it.address.hostAddress!!, it.networkPrefixLength.toInt()) } }
    } catch (e: Exception) { emptyList() }

    /** Scan the /24 (or smaller) of the USB interface for hosts accepting TCP on [port]. */
    suspend fun scan(iface: UsbIface, port: Int = 22, timeoutMs: Int = 500): List<Candidate> = coroutineScope {
        val parts = iface.phoneIp.split(".").map { it.toInt() }
        val base = parts.take(3).joinToString(".")
        (1..254).map { i ->
            val ip = "$base.$i"
            async(Dispatchers.IO) {
                if (ip == iface.phoneIp) null else
                    try { Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }; Candidate(ip, true) } catch (_: Exception) { null }
            }
        }.awaitAll().filterNotNull()
    }
}
