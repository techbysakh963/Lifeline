package com.rodinrescue

import com.rodinrescue.runtime.CliLogin
import com.rodinrescue.runtime.Ubuntu
import com.rodinrescue.runtime.UbuntuSetup
import org.junit.Assert.*
import org.junit.Test

class RuntimeSetupTest {
    @Test fun picksTheNewestArm64UbuntuBase() {
        val sums = """
            7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048 *ubuntu-base-24.04.3-base-arm64.tar.gz
            1111111111111111111111111111111111111111111111111111111111111111 *ubuntu-base-24.04.5-base-amd64.tar.gz
            A91D5A93010193712D346D761372B7C9DB6DFCF093893161C64CA107F05914F2 *ubuntu-base-24.04.5-base-arm64.tar.gz
            04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2 *ubuntu-base-24.04.4-base-arm64.tar.gz
            nothex *ubuntu-base-24.04.9-base-arm64.tar.gz
        """.trimIndent()
        assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz" to "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", UbuntuSetup.newestUbuntuBase(sums))
        assertNull(UbuntuSetup.newestUbuntuBase("abc *ubuntu-base-24.04.5-base-arm64.tar.gz"))
    }

    @Test fun readsAnthropicsLinuxArm64Checksum() {
        // Shape of downloads.claude.ai/claude-code-releases/2.1.312/manifest.json (trimmed).
        val m = """{"version":"2.1.312","platforms":{"linux-x64":{"checksum":"${"a".repeat(64)}"},
            "linux-arm64":{"binary":"claude","checksum":"dc4af28917455e009d26704b82a438353a06149e825b6cdf782908b2d28661f5","size":293462200},
            "linux-arm64-musl":{"checksum":"${"b".repeat(64)}"}}}"""
        assertEquals("dc4af28917455e009d26704b82a438353a06149e825b6cdf782908b2d28661f5", UbuntuSetup.parseClaudeChecksum(m))
        assertNull(UbuntuSetup.parseClaudeChecksum("""{"platforms":{"linux-arm64":{"checksum":"short"}}}"""))
        assertNull(UbuntuSetup.parseClaudeChecksum("not json"))
    }

    @Test fun keepsOnlyPemBlocksFromAndroidCacerts() {
        val f = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\nCertificate:\n    Data:\n        Version: 3 (0x2)\n"
        assertEquals("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n", UbuntuSetup.pemOnly(f))
    }

    @Test fun findsTheSignInLinkAndKeepsNoAccountDetails() {
        val out = "Opening browser to sign in…\nIf the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y\nPaste code here if prompted > "
        assertEquals("https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y", CliLogin.linkIn(Ubuntu.Cli.CLAUDE, out)?.url)
        assertNull(CliLogin.linkIn(Ubuntu.Cli.CLAUDE, "Opening browser"))
        val s = CliLogin.parseClaudeStatus("""noise {"loggedIn": true, "authMethod": "claude.ai", "email": "a@b.c", "orgName": "X", "subscriptionType": "team"}""")!!
        assertEquals(CliLogin.Status(loggedIn = true, subscription = true, plan = "team"), s)
        assertFalse(s.toString().contains("a@b.c"))
        // an API-key (Console) login is not a subscription: Lifeline must not use it silently
        assertFalse(CliLogin.parseClaudeStatus("""{"loggedIn": true, "authMethod": "api_key"}""")!!.subscription)
        assertFalse(CliLogin.parseClaudeStatus("""{"loggedIn": false}""")!!.loggedIn)
    }

    @Test fun findsCodexsDeviceLinkAndCode() {
        val out = "Welcome to Codex [v\u001B[90m0.163.0\u001B[0m]\n1. Open this link in your browser and sign in to your account\n   \u001B[94mhttps://auth.openai.com/codex/device\u001B[0m\n\n" +
            "2. Enter this one-time code \u001B[90m(expires in 15 minutes)\u001B[0m\n   \u001B[94mAB12-CD34E\u001B[0m\n"
        assertEquals(CliLogin.Link("https://auth.openai.com/codex/device", "AB12-CD34E"), CliLogin.linkIn(Ubuntu.Cli.CODEX, out))
        assertNull("no code yet: wait", CliLogin.linkIn(Ubuntu.Cli.CODEX, "1. Open this link\n https://auth.openai.com/codex/device\n"))
        assertEquals(CliLogin.Status(true, true, null), CliLogin.parseCodexStatus("Logged in using ChatGPT\n"))
        assertFalse(CliLogin.parseCodexStatus("Logged in using an API key - sk-***").subscription)
        assertFalse(CliLogin.parseCodexStatus("Not logged in").loggedIn)
    }

    @Test fun takesAgysWholeLinkFromItsTerminalHyperlink() {
        val url = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=abc&code_challenge=x&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&state=s1"
        // the link is painted wrapped across rows, but the OSC 8 target holds it whole
        val out = "Open the URL below in your browser:\n\u001B]8;id=k1;$url\u0007https://accounts.google.com/o/oauth2/auth?access_ty\u001B]8;;\u0007\n"
        assertEquals(url, CliLogin.linkIn(Ubuntu.Cli.AGY, out)?.url)
        assertNull(CliLogin.linkIn(Ubuntu.Cli.AGY, "Select login method\n > 1. Google OAuth"))
    }

    @Test fun readsCodexAndAgyReleaseInfoFromTheirPublishers() {
        val gh = """{"tag_name":"rust-v0.165.0","assets":[{"name":"codex-aarch64-unknown-linux-musl.zst","browser_download_url":"https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-aarch64-unknown-linux-musl.zst","digest":"sha256:${"e".repeat(64)}"},
            {"name":"codex-aarch64-unknown-linux-musl.tar.gz","browser_download_url":"https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-aarch64-unknown-linux-musl.tar.gz","digest":"sha256:6522e056d0be7f531da915c9a81ae05bc70811b782542499cc0a9776d3abf5d3"},
            {"name":"codex-code-mode-host-aarch64-unknown-linux-musl.tar.gz","browser_download_url":"https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-code-mode-host-aarch64-unknown-linux-musl.tar.gz","digest":"sha256:400956c325a2118768029f235958b7d2fd2ea4e59c5466d336fb4fb7f0e94b61"}]}"""
        assertEquals("400956c325a2118768029f235958b7d2fd2ea4e59c5466d336fb4fb7f0e94b61", UbuntuSetup.parseCodexRelease(gh, UbuntuSetup.CODEX_HOST_ENTRY)!!.third)
        assertEquals(Triple("0.165.0", "https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-aarch64-unknown-linux-musl.tar.gz", "6522e056d0be7f531da915c9a81ae05bc70811b782542499cc0a9776d3abf5d3"), UbuntuSetup.parseCodexRelease(gh))
        assertNull("only OpenAI's own release URLs", UbuntuSetup.parseCodexRelease(gh.replace("https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-aarch64-unknown-linux-musl.tar.gz", "https://evil.example/codex.tar.gz")))
        val agy = """{"version":"1.4.9","url":"https://storage.googleapis.com/antigravity-public/antigravity-cli/1.4.9-4962164969177088/linux-arm/cli_linux_arm64.tar.gz","sha512":"${"A".repeat(128)}"}"""
        assertEquals("1.4.9", UbuntuSetup.parseAgyManifest(agy)!!.first); assertEquals("a".repeat(128), UbuntuSetup.parseAgyManifest(agy)!!.third)
        assertNull(UbuntuSetup.parseAgyManifest(agy.replace("storage.googleapis.com/antigravity-public", "evil.example")))
    }

    @Test fun prootBindsNothingOfTheHostButDevProcSys() {
        val a = Ubuntu.prootArgs("/lib", "/rt/codex/ubuntu", "/root", emptyMap(), listOf("codex", "exec"))
        assertEquals(listOf("/dev", "/proc", "/sys"), a.zipWithNext().filter { it.first == "-b" }.map { it.second })
        assertEquals("/rt/codex/ubuntu", a[a.indexOf("-r") + 1])
        assertEquals(listOf("codex", "exec"), a.takeLast(2))
        assertTrue("only the base environment reaches the CLI", a.none { it.startsWith("ANTHROPIC_") || it.startsWith("OPENAI_") })
    }

    @Test fun startingOneCliStopsTheOthersProcesses() {
        val claude = ProcessBuilder("sleep", "30").start(); val codex = ProcessBuilder("sleep", "30").start()
        Ubuntu.Live.add(Ubuntu.Cli.CLAUDE, claude)
        Ubuntu.Live.stopOthers(Ubuntu.Cli.CODEX)
        assertFalse("Claude's process is stopped before Codex runs", claude.isAlive)
        Ubuntu.Live.add(Ubuntu.Cli.CODEX, codex)
        Ubuntu.Live.stopOthers(Ubuntu.Cli.CODEX)
        assertTrue("a CLI's own processes keep running", codex.isAlive)
        assertEquals(1, Ubuntu.Live.running(Ubuntu.Cli.CODEX))
        codex.destroy()
    }
}
