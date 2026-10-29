package com.rodinrescue

import com.rodinrescue.ai.Msg
import com.rodinrescue.ai.ToolCall
import com.rodinrescue.ai.ApprovalRequest
import com.rodinrescue.data.*
import com.rodinrescue.security.Risk
import com.rodinrescue.setup.*
import com.rodinrescue.transport.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DevicesAndCleanupTest {
    private fun sh(script: String, env: Map<String, String> = emptyMap(), path: File? = null): Pair<Int, String> {
        val f = File.createTempFile("lifeline", ".sh"); f.writeText(script)
        val pb = ProcessBuilder("sh", f.path).redirectErrorStream(true)
        pb.environment().putAll(env)
        if (path != null) pb.environment()["PATH"] = path.path + ":" + System.getenv("PATH")
        val p = pb.start(); val out = p.inputStream.bufferedReader().readText(); p.waitFor(30, TimeUnit.SECONDS); f.delete()
        return p.exitValue() to out
    }

    /** Fake systemctl/ufw that only log their arguments. */
    private fun fakeBin(dir: File, log: File): File = File(dir, "bin").apply {
        mkdirs()
        for (n in listOf("systemctl", "ufw")) File(this, n).apply { writeText("#!/bin/sh\necho \"$n $*\" >> '${log.path}'\n"); setExecutable(true) }
    }

    @Test fun setupRecordsOriginalStateOnceAndReportsIt() {
        val q = LinkedBlockingQueue<Checkin>()
        val id = KeyManager.generate()
        lateinit var srv: SetupServer
        srv = SetupServer(CoroutineScope(Dispatchers.IO), { LaptopScript.build("127.0.0.1", srv.boundPort, "tok9", id.publicLine) }) { q.put(it) }
        val port = srv.start("127.0.0.1", "tok9", 0)
        try {
            val dir = Files.createTempDirectory("lap").toFile()
            Runtime.getRuntime().exec(arrayOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", File(dir, "ssh_host_ed25519_key").path)).waitFor()
            val stateDir = File(dir, "state")
            val env = mapOf("RODIN_SKIP_INSTALL" to "1", "RODIN_AUTHKEYS_FILE" to File(dir, "ak").path,
                "RODIN_HOSTKEY_GLOB" to "${dir.path}/ssh_host_*_key.pub", "RODIN_STATE_DIR" to stateDir.path)
            val (rc, out) = sh(URL("http://127.0.0.1:$port/s/tok9").readText(), env)
            assertEquals(out, 0, rc)
            val state = File(stateDir, "state").readText()
            assertTrue(state, state.contains("INSTALLED_BY_LIFELINE=0") && state.contains("WAS_ENABLED=1") && state.contains("SVC="))
            val c = q.poll(5, TimeUnit.SECONDS)!!
            assertTrue(c.changes.stateRecorded); assertEquals(false, c.changes.installedSshd); assertEquals(true, c.changes.sshdWasEnabled)
            // A re-run must keep the first record (so removal restores what was there before Lifeline).
            File(stateDir, "state").writeText(state.replace("WAS_ENABLED=1", "WAS_ENABLED=0"))
            sh(URL("http://127.0.0.1:$port/s/tok9").readText(), env)
            assertTrue(File(stateDir, "state").readText().contains("WAS_ENABLED=0"))
        } finally { srv.stop() }
    }

    @Test fun userCleanupRemovesOnlyThePhoneKeyAnyComment() {
        val id = KeyManager.generate()
        val dir = Files.createTempDirectory("ak").toFile(); val ak = File(dir, "authorized_keys")
        val other = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOtherKeyOtherKeyOtherKeyOtherKeyOtherK me@laptop"
        ak.writeText("$other\n${id.publicLine}\n${id.publicLine.replace("rodin-rescue", "old-comment")}\n")
        val env = mapOf("LIFELINE_AUTHKEYS_FILE" to ak.path)
        val (_, out) = sh(LaptopCleanup.userScript(id.publicLine), env)
        assertTrue(out, out.contains("KEY_REMOVED"))
        assertEquals(listOf(other), ak.readLines())
        assertTrue(sh(LaptopCleanup.userScript(id.publicLine), env).second.contains("KEY_ABSENT"))
    }

    @Test fun rootCleanupRevertsOnlyRecordedChanges() {
        val dir = Files.createTempDirectory("st").toFile(); val log = File(dir, "log")
        val bin = fakeBin(dir, log)
        File(dir, "state").writeText("SVC=ssh\nINSTALLED_BY_LIFELINE=1\nWAS_ENABLED=0\nWAS_ACTIVE=0\nSOCK_WAS_ENABLED=0\nSSH_PORT=22\nUFW=usb0\n")
        val (_, out) = sh(LaptopCleanup.rootScript(), mapOf("LIFELINE_STATE_DIR" to dir.path, "LIFELINE_NO_STOP" to "1"), bin)
        val r = LaptopCleanup.parse("KEY_REMOVED", out)
        assertTrue(out, r.done && r.stateFound && r.sshdDisabled && r.keyRemoved)
        assertEquals(listOf("usb0"), r.ufwRemoved)
        val calls = log.readText()
        assertTrue(calls, calls.contains("ufw delete allow in on usb0 to any port 22 proto tcp"))
        assertTrue(calls, calls.contains("systemctl disable ssh") && calls.contains("systemctl disable ssh.socket"))
        assertFalse("state file removed", File(dir, "state").exists())

        // SSH was already on before Lifeline: leave it alone.
        val dir2 = Files.createTempDirectory("st2").toFile(); val log2 = File(dir2, "log")
        File(dir2, "state").writeText("SVC=ssh\nINSTALLED_BY_LIFELINE=0\nWAS_ENABLED=1\nWAS_ACTIVE=1\nSOCK_WAS_ENABLED=1\nSSH_PORT=22\n")
        val (_, out2) = sh(LaptopCleanup.rootScript(), mapOf("LIFELINE_STATE_DIR" to dir2.path), fakeBin(dir2, log2))
        assertFalse(out2, LaptopCleanup.parse("", out2).sshdDisabled)
        assertFalse(log2.exists() && log2.readText().contains("disable"))
        // No record at all (older setup): nothing touched.
        assertTrue(LaptopCleanup.parse("", sh(LaptopCleanup.rootScript(), mapOf("LIFELINE_STATE_DIR" to Files.createTempDirectory("e").toString())).second).let { it.done && !it.stateFound })
    }

    /** USB serial (B4): setup gives THIS phone's serial port a login prompt; Remove takes it away again. */
    @Test fun serialLoginPromptIsAddedForThisPhoneAndRemoved() {
        val q = LinkedBlockingQueue<Checkin>()
        val id = KeyManager.generate()
        lateinit var srv: SetupServer
        srv = SetupServer(CoroutineScope(Dispatchers.IO), { LaptopScript.build("127.0.0.1", srv.boundPort, "tok7", id.publicLine, "FQJN\"; rm -rf /;LJ9") }) { q.put(it) }
        val port = srv.start("127.0.0.1", "tok7", 0)
        try {
            val dir = Files.createTempDirectory("lap").toFile(); val udev = File(dir, "rules.d").apply { mkdirs() }; val stateDir = File(dir, "state")
            Runtime.getRuntime().exec(arrayOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", File(dir, "ssh_host_ed25519_key").path)).waitFor()
            val env = mapOf("RODIN_SKIP_INSTALL" to "1", "RODIN_AUTHKEYS_FILE" to File(dir, "ak").path, "RODIN_HOSTKEY_GLOB" to "${dir.path}/ssh_host_*_key.pub",
                "RODIN_STATE_DIR" to stateDir.path, "RODIN_UDEV_DIR" to udev.path)
            val (rc, out) = sh(URL("http://127.0.0.1:$port/s/tok7").readText(), env)
            assertEquals(out, 0, rc)
            val rule = File(udev, "90-lifeline-serial.rules").readText()
            assertTrue(rule, rule.contains("ENV{ID_SERIAL_SHORT}==\"FQJNrmrfLJ9\"") && rule.contains("serial-getty@%k.service") && rule.contains("KERNEL==\"ttyACM[0-9]*\""))
            if (File("/usr/bin/udevadm").exists()) {   // the rule must be valid udev syntax (udevadm 254+ has "verify")
                val v = ProcessBuilder("udevadm", "verify", File(udev, "90-lifeline-serial.rules").path).redirectErrorStream(true).start()
                val vo = v.inputStream.bufferedReader().readText(); assertEquals(vo, 0, v.waitFor())
            }
            assertTrue(File(stateDir, "state").readText().contains("SERIAL_RULE=1"))
            // Remove from this laptop
            val log = File(dir, "log"); val bin = fakeBin(dir, log)
            val (_, rout) = sh(LaptopCleanup.rootScript(), mapOf("LIFELINE_STATE_DIR" to stateDir.path, "LIFELINE_UDEV_DIR" to udev.path, "LIFELINE_NO_STOP" to "1"), bin)
            assertTrue(rout, LaptopCleanup.parse("", rout).serialRemoved)
            assertFalse(File(udev, "90-lifeline-serial.rules").exists())
        } finally { srv.stop() }
        // No serial number known (older phones / kernels): nothing about serial is added.
        assertFalse(LaptopScript.build("1.2.3.4", 1, "t", "ssh-ed25519 AAAA x").contains("PHONE_SERIAL='F"))
    }

    @Test fun manualCommandIsValidShell() {
        val f = File.createTempFile("manual", ".sh"); f.writeText(LaptopCleanup.manualCommand(KeyManager.generate().publicLine))
        for (s in listOf("sh", "bash")) assertEquals(0, ProcessBuilder(s, "-n", f.path).start().waitFor())
    }

    @Test fun deviceStoreMatchesByFingerprint() {
        val st = DeviceStore(Files.createTempDirectory("d").toFile())
        val d = DeviceProfile("1", "Lab PC", "os", "10.0.0.2", 22, "lab", listOf("SHA256:aaa", "SHA256:bbb"), 1, 1)
        st.save(d)
        assertEquals("1", st.find(listOf("SHA256:bbb"), "x", "y")?.id)
        assertNull(st.find(listOf("SHA256:zzz"), "os", "lab"))
        assertEquals("1", st.find(emptyList(), "os", "lab")?.id)
        st.save(d.copy(name = "Renamed")); assertEquals(1, st.all().size); assertEquals("Renamed", st.get("1")!!.name)
        st.delete("1"); assertTrue(st.all().isEmpty())
    }

    @Test fun conversationRoundTripsEveryItemType() {
        val st = ConversationStore(Files.createTempDirectory("c").toFile())
        val chat = listOf(ChatItem.UserMsg("black screen"), ChatItem.AssistantMsg("checking"), ChatItem.ToolCallItem("check_disk_space", "{}"),
            ChatItem.Cmd("df -h", false, ExecResult("/ 99%", "", 0, durationMs = 5)),
            ChatItem.ApprovalItem(ApprovalRequest("p1", "Clean", "rm x", Risk.MODIFYING, "r", "e", "c", true), true), ChatItem.Notice("done"))
        val hist = listOf(Msg.User("black screen"), Msg.Assistant("checking", listOf(ToolCall("c1", "check_disk_space", "{}"))), Msg.Tool("c1", "check_disk_space", "99%"))
        st.save(Conversation("20261011-120000", "dev1", ConversationStore.titleFrom(chat), 1, 2, chat, hist))
        val back = st.load("20261011-120000")!!
        assertEquals(chat, back.chat); assertEquals(hist, back.history); assertEquals("black screen", back.title)
        assertEquals(1, st.list("dev1").size)
        st.reassign("dev1", null); assertNull(st.load("20261011-120000")!!.deviceId)
        assertTrue(ConversationStore.transcript(back, "Lab PC").contains("Lifeline: checking"))
    }

    @Test fun legacyCleanupRemovesOnlyUsbSshUfwRules() {
        val dir = Files.createTempDirectory("lg").toFile(); val log = File(dir, "log")
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "ufw").apply {
            writeText("#!/bin/sh\nif [ \"$1\" = status ]; then printf 'Status: active\\n\\nTo Action From\\n22/tcp on enx5e30cd41d5d7     ALLOW       Anywhere\\n22/tcp                     ALLOW       Anywhere\\n80/tcp on usb0              ALLOW       Anywhere\\n'; else echo \"ufw $*\" >> '${log.path}'; fi\n")
            setExecutable(true)
        }
        val (_, out) = sh(LaptopCleanup.rootScript(), mapOf("LIFELINE_STATE_DIR" to File(dir, "none").path), bin)
        val r = LaptopCleanup.parse("", out)
        assertEquals(out, listOf("enx5e30cd41d5d7"), r.ufwRemoved)
        assertEquals("ufw delete allow in on enx5e30cd41d5d7 to any port 22 proto tcp", log.readText().trim())
    }

    @Test fun oldAuditSessionBecomesReadableConversation() {
        val e = listOf(
            AuditEntry(1000, "user", "check my latest boot log see if it is healthy "),
            AuditEntry(1001, "usage", "tokens in=2104 out=78"),
            AuditEntry(1002, "assistant", "I'll start with read-only diagnostics."),
            AuditEntry(1003, "tool_call", "inspect_boot_errors", "{}"),
            AuditEntry(1004, "command", "journalctl --list-boots --no-pager"),
            AuditEntry(1005, "command", "result: 0 in 1678ms", "IDX BOOT ID", 0),
            AuditEntry(1006, "command", "[sudo] systemctl restart gdm"),
            AuditEntry(1007, "command", "result: timeout in 30000ms", ""),
            AuditEntry(1008, "approval", "p1 APPROVED", "evidence\nrm -rf /var/cache/x", risk = "MODIFYING"),
            AuditEntry(1009, "approval", "forbidden repair blocked", "dd if=/dev/zero of=/dev/sda", risk = "FORBIDDEN"),
            AuditEntry(1010, "error", "step limit reached"),
            AuditEntry(1011, "stop", "User pressed STOP"))
        val c = Migrations.fromAudit("20261010-203246", e)!!
        assertEquals("check my latest boot log see if it is healthy", c.title.trim())
        assertNull(c.deviceId); assertEquals(1000, c.createdAt); assertEquals(1011, c.updatedAt)
        val cmds = c.chat.filterIsInstance<ChatItem.Cmd>()
        assertEquals(listOf("journalctl --list-boots --no-pager", "systemctl restart gdm"), cmds.map { it.command })
        assertEquals(0, cmds[0].result.exitCode); assertEquals(1678, cmds[0].result.durationMs); assertEquals("IDX BOOT ID", cmds[0].result.stdout)
        assertTrue(cmds[1].privileged); assertTrue(cmds[1].result.timedOut)
        val ap = c.chat.filterIsInstance<ChatItem.ApprovalItem>().single(); assertTrue(ap.approved); assertEquals("rm -rf /var/cache/x", ap.req.command)
        assertTrue(c.chat.any { it is ChatItem.Notice && it.text.contains("dd if=/dev/zero") })
        assertEquals(2, c.history.size)
        assertNull("usage-only logs are not imported", Migrations.fromAudit("x", listOf(AuditEntry(1, "usage", "tokens"))))
        // round-trips through the store like a native conversation
        val st = ConversationStore(Files.createTempDirectory("mig").toFile()); st.save(c); assertEquals(c, st.load(c.id))
    }
}
