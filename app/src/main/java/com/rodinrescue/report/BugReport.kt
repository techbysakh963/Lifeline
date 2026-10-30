package com.rodinrescue.report

import com.rodinrescue.security.Redactor
import java.net.URLEncoder

/**
 * Settings › Report a bug: a short diagnostic report the user sees before anything leaves the phone, and the GitHub
 * bug form opened with it filled in. It says what Lifeline and the phone can do and the last messages on screen;
 * never keys, passwords, conversations, the PC's data, or any name@address.
 */
object BugReport {
    const val REPO = "https://github.com/techbysakh963/Lifeline"

    data class Info(
        val appVersion: String, val appCode: Long, val device: String, val android: String, val sdk: Int,
        val rootManager: String?, val rootOk: Boolean?, val keyboard: String, val serial: Boolean?, val bootDrive: Boolean?,
        val usbLink: Boolean, val vpn: String, val connected: Boolean, val aiBrain: String,
        val recent: List<String>,
    )

    private val USER_AT = Regex("""[\w.+-]+@[\w.-]+""")   // user@10.0.0.2, user@laptop, name@mail.com
    private val PERSONAL = listOf("checked in", "Saved as device")  // these name the laptop and its user

    /** One line made safe to share. */
    fun clean(line: String): String = USER_AT.replace(Redactor.redact(line), "<user>@<host>").trim()

    fun text(i: Info): String = buildString {
        fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "unknown" }
        appendLine("Lifeline ${i.appVersion} (${i.appCode})")
        appendLine("Phone: ${i.device}, Android ${i.android} (SDK ${i.sdk})")
        appendLine("Root: ${if (i.rootOk == true) i.rootManager ?: "yes" else yn(i.rootOk)}")
        appendLine("USB keyboard: ${i.keyboard} · USB serial: ${yn(i.serial)} · Boot drive: ${yn(i.bootDrive)}")
        appendLine("USB link: ${if (i.usbLink) "on" else "off"} · VPN: ${i.vpn} · SSH connected: ${yn(i.connected)}")
        appendLine("AI brain: ${i.aiBrain}")
        val recent = i.recent.filter { l -> l.isNotBlank() && PERSONAL.none { it in l } }.map(::clean).distinct().takeLast(12)
        if (recent.isNotEmpty()) { appendLine("Recent messages:"); recent.forEach { appendLine("  $it") } }
    }.trimEnd()

    /** The GitHub bug form with the report filled in (kept under GitHub's URL limit). */
    fun issueUrl(report: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "$REPO/issues/new?template=bug_report.yml&diagnostics=${enc(report.take(5000))}"
    }
}
