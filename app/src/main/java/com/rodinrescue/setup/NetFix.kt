package com.rodinrescue.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rodinrescue.root.RootResult
import com.rodinrescue.root.RootShell
import com.rodinrescue.transport.UsbIface

/**
 * Makes the USB link work while a VPN is active.
 * Android policy-routes every app's traffic into the VPN (rule `uidrange … lookup tun0`), including the tether subnet.
 * One extra rule sends ONLY the USB subnet to the `local_network` table (which already holds the rndis0 route).
 * It touches nothing else, vanishes on reboot, and is removed by [remove] / "Restore normal USB mode".
 */
object NetFix {
    const val PREF = 10500

    fun isVpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Network address in CIDR form, e.g. 10.89.173.142/24 -> 10.89.173.0/24. */
    fun subnetOf(i: UsbIface): String {
        val ip = i.phoneIp.split(".").map { it.toInt() }
        var v = (ip[0] shl 24) or (ip[1] shl 16) or (ip[2] shl 8) or ip[3]
        val mask = if (i.prefix == 0) 0 else (-1 shl (32 - i.prefix))
        v = v and mask
        return "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}/${i.prefix}"
    }

    fun applyCommand(i: UsbIface) =
        "while ip rule del pref $PREF 2>/dev/null; do :; done; ip rule add to ${subnetOf(i)} lookup local_network pref $PREF"

    const val REMOVE_COMMAND = "while ip rule del pref $PREF 2>/dev/null; do :; done; true"

    suspend fun isApplied(i: UsbIface): Boolean =
        RootShell.run("ip rule | grep -c \"^$PREF:.*to ${subnetOf(i)}\"").output.trim() == "1"

    suspend fun apply(i: UsbIface): RootResult = RootShell.run(applyCommand(i))
    suspend fun remove(): RootResult = RootShell.run(REMOVE_COMMAND)
}
