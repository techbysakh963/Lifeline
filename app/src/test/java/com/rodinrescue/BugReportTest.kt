package com.rodinrescue

import com.rodinrescue.report.BugReport
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** The bug report leaves the phone, so nothing personal or secret may be in it. */
class BugReportTest {
    private fun info(recent: List<String>) = BugReport.Info("0.7.1", 10, "Xiaomi 2412DPC0AG", "16", 36, "KernelSU", true, "on", true, true,
        true, "on, routed around", true, "ChatGPT (subscription) · OK", recent)

    @Test fun reportHasWhatATriageNeeds() {
        val t = BugReport.text(info(listOf("2/5 USB tethering…", "✗ Couldn't open the setup port")))
        for (want in listOf("Lifeline 0.7.1 (10)", "Android 16 (SDK 36)", "Root: KernelSU", "USB keyboard: on", "VPN: on, routed around",
            "AI brain: ChatGPT (subscription)", "✗ Couldn't open the setup port")) assertTrue(want, want in t)
    }

    @Test fun personalAndSecretTextIsRemoved() {
        val t = BugReport.text(info(listOf(
            "✓ Laptop checked in: alice@10.32.213.37 (alice-ThinkPad), 1 host key(s)",
            "✓ Saved as device “alice-ThinkPad”",
            "Connected to ssh alice@10.32.213.37:22",
            "Sign-in failed for bob@example.com",
            "api_key=sk-abcdefghijklmnopqrstuvwx",
            "password: hunter22")))
        for (bad in listOf("alice", "ThinkPad", "bob@example.com", "sk-abcdef", "hunter22")) assertFalse("$bad leaked:\n$t", bad in t)
        assertTrue(t, "Connected to ssh <user>@<host>:22" in t)
        assertTrue(t, "Sign-in failed for <user>@<host>" in t)
    }

    @Test fun issueUrlOpensTheBugFormFilledIn() {
        val r = BugReport.text(info(emptyList()))
        val url = BugReport.issueUrl(r)
        assertTrue(url.startsWith("https://github.com/techbysakh963/Lifeline/issues/new?template=bug_report.yml&diagnostics="))
        assertEquals(r, URLDecoder.decode(url.substringAfter("diagnostics="), "UTF-8"))
        assertTrue("stays under GitHub's URL limit", BugReport.issueUrl("x".repeat(20_000)).length < 8000)
    }
}
