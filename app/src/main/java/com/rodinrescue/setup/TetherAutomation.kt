package com.rodinrescue.setup

import com.rodinrescue.root.RootShell
import com.rodinrescue.transport.UsbNet
import kotlinx.coroutines.delay

/**
 * Turns on Android's own "USB tethering" switch by driving the Settings screen as root
 * (`svc usb setFunctions` is killed by HyperOS, and no public API lets a normal app do it).
 * Best effort: if the screen layout/language is different, the caller shows manual instructions instead.
 */
object TetherAutomation {
    data class SwitchNode(val desc: String, val checked: Boolean, val cx: Int, val cy: Int)

    private val nodeRe = Regex("""<node [^>]*>""")
    private fun attr(n: String, a: String) = Regex("""\b$a="([^"]*)"""").find(n)?.groupValues?.get(1) ?: ""

    /** Pure parser (unit-tested): finds the clickable switch row whose description mentions USB tethering. */
    fun findUsbSwitch(xml: String): SwitchNode? =
        nodeRe.findAll(xml).map { it.value }.firstNotNullOfOrNull { n ->
            val desc = attr(n, "content-desc").replace("&amp;", "&")
            if (attr(n, "class").endsWith("Switch") && attr(n, "checkable") == "true" && attr(n, "clickable") == "true" &&
                desc.contains("usb", ignoreCase = true) && !desc.contains("hotspot", ignoreCase = true) && attr(n, "enabled") == "true") {
                val b = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(attr(n, "bounds")) ?: return@firstNotNullOfOrNull null
                val (l, t, r, bt) = b.groupValues.drop(1).map { it.toInt() }
                SwitchNode(desc, attr(n, "checked") == "true", (l + r) / 2, (t + bt) / 2)
            } else null
        }

    /** Back to the same Lifeline screen: a plain `am start` stacked a second one (own view model) on top of the one running setup. */
    const val BACK_TO_APP = "am start --activity-reorder-to-front -n com.rodinrescue/.MainActivity"

    suspend fun enable(log: (String) -> Unit): Boolean {
        if (UsbNet.detect().isNotEmpty()) { log("USB tethering already on"); return true }
        log("Opening tethering settings…")
        RootShell.run("am start -a android.settings.TETHER_SETTINGS")
        var done = false
        for (attempt in 1..4) {
            delay(1800)
            val xml = RootShell.run("uiautomator dump /sdcard/.rodin_ui.xml >/dev/null 2>&1; cat /sdcard/.rodin_ui.xml; rm -f /sdcard/.rodin_ui.xml", 25).output
            val sw = findUsbSwitch(xml)
            if (sw == null) { log("Looking for the USB tethering switch ($attempt/4)…"); continue }
            if (sw.checked) { done = true; break }
            RootShell.run("input tap ${sw.cx} ${sw.cy}"); log("Tapped USB tethering"); done = true; break
        }
        if (!done) { RootShell.run(BACK_TO_APP); return false }
        repeat(12) { delay(1000); if (UsbNet.detect().isNotEmpty()) { RootShell.run(BACK_TO_APP); return true } }
        RootShell.run(BACK_TO_APP)
        return false
    }
}
