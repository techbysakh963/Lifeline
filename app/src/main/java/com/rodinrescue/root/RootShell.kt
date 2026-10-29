package com.rodinrescue.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class RootResult(val ok: Boolean, val output: String)

/** Minimal `su` wrapper (KernelSU grants root per-app; the user approves the prompt in the KernelSU manager). Never modifies KernelSU policy. */
object RootShell {
    /**
     * [mountMaster] adds KernelSU's `-M` so the command runs in the GLOBAL mount namespace. Needed when an
     * app-granted root process must see another app's files (e.g. Termux's data dir); without it the app's
     * su may run in a namespace where those mounts aren't visible.
     */
    suspend fun run(command: String, timeoutSec: Long = 20, mountMaster: Boolean = false): RootResult = withContext(Dispatchers.IO) {
        val argv = if (mountMaster) arrayOf("su", "-M", "-c", command) else arrayOf("su", "-c", command)
        try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return@withContext RootResult(false, out + "\n[timeout]") }
            RootResult(p.exitValue() == 0, out.trim())
        } catch (e: Exception) { RootResult(false, "su unavailable: ${e.message}") }
    }

    suspend fun available(): RootResult = run("id")

    /** Which root manager answers `su -v` ("28.1:MAGISKSU", "…:KernelSU", APatch): for labels and hints only. */
    suspend fun managerName(): String = withContext(Dispatchers.IO) {
        val v = runCatching { ProcessBuilder("su", "-v").redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText().also { p.waitFor(5, TimeUnit.SECONDS) } } }.getOrDefault("")
        managerFrom(v)
    }

    fun managerFrom(suVersion: String): String = when {
        suVersion.contains("magisk", ignoreCase = true) -> "Magisk"
        suVersion.contains("kernelsu", ignoreCase = true) || suVersion.contains("ksu", ignoreCase = true) -> "KernelSU"
        suVersion.contains("apatch", ignoreCase = true) -> "APatch"
        else -> "your root manager"
    }
}

/** Capability probes + the two user-initiated USB mode actions. Inspection is strictly read-only. */
class UsbToolkit(private val readScript: () -> String) {
    suspend fun inspect(): RootResult {
        // Feed the bundled read-only script on stdin of a root shell; no file written.
        return withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
                p.outputStream.use { it.write(readScript().toByteArray()) }
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(30, TimeUnit.SECONDS)
                RootResult(true, out)
            } catch (e: Exception) { RootResult(false, "Root inspection failed: ${e.message}") }
        }
    }

    /** Enables USB tethering (RNDIS/NCM per vendor). USER-INITIATED ONLY. May interrupt MTP/file transfer. */
    suspend fun enableUsbTethering() = RootShell.run("svc usb setFunctions rndis")

    /** Restores Android's default USB function (the one selected in system settings). */
    suspend fun restoreNormalUsb() = RootShell.run("svc usb setFunctions")

    suspend fun currentUsbFunctions() = RootShell.run("getprop sys.usb.config; getprop sys.usb.state; ip -br addr")
}
