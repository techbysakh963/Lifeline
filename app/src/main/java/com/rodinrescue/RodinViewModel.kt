package com.rodinrescue

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodinrescue.ai.*
import com.rodinrescue.data.AuditEntry
import com.rodinrescue.data.Conversation
import com.rodinrescue.data.ConversationStore
import com.rodinrescue.data.DeviceProfile
import com.rodinrescue.data.DeviceStore
import com.rodinrescue.root.RootShell
import com.rodinrescue.root.UsbToolkit
import com.rodinrescue.usb.HidController
import com.rodinrescue.usb.HidKeyboard
import com.rodinrescue.usb.VoiceTyper
import com.rodinrescue.usb.BootController
import com.rodinrescue.usb.BootLibrary
import com.rodinrescue.usb.UsbAutoOff
import com.rodinrescue.setup.*
import com.rodinrescue.transport.*
import kotlinx.coroutines.*

@kotlinx.serialization.Serializable
sealed class ChatItem {
    /** [photo]: name of a screen photo the user attached (keyboard rescue), stored with the conversation. */
    @kotlinx.serialization.Serializable data class UserMsg(val text: String, val photo: String? = null) : ChatItem()
    @kotlinx.serialization.Serializable data class AssistantMsg(val text: String) : ChatItem()
    @kotlinx.serialization.Serializable data class ToolCallItem(val name: String, val args: String) : ChatItem()
    @kotlinx.serialization.Serializable data class Cmd(val command: String, val privileged: Boolean, val result: ExecResult) : ChatItem()
    @kotlinx.serialization.Serializable data class ApprovalItem(val req: ApprovalRequest, val approved: Boolean, val auto: Boolean = false) : ChatItem()
    @kotlinx.serialization.Serializable data class Notice(val text: String, val error: Boolean = false) : ChatItem()
    /** What the AI saw: [photo] = file name in the conversation's folder ([text]: OCR from older builds). */
    @kotlinx.serialization.Serializable data class ScreenItem(val text: String = "", val photo: String? = null) : ChatItem()
    @kotlinx.serialization.Serializable data class KeysItem(val label: String, val error: String? = null) : ChatItem()
}

class RodinViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app as RodinApp
    val settings = ctx.settings
    private val secrets = ctx.secrets
    val audit = ctx.audit

    // ---- connection ----
    var host by mutableStateOf(settings.host)
    var port by mutableStateOf(settings.port.toString())
    var user by mutableStateOf(settings.user)
    var useKey by mutableStateOf(settings.useKeyAuth)
    var sshPassword by mutableStateOf(secrets.get("ssh_password") ?: "")
    var sshKey by mutableStateOf(secrets.get("ssh_key") ?: "")
    var keyPassphrase by mutableStateOf("")
    var sudoPassword by mutableStateOf("")   // memory only, never persisted, never sent to the AI
    var connState by mutableStateOf("Disconnected")
    var connected by mutableStateOf(false)
    var targetInfo by mutableStateOf("")
    var usbIfaces by mutableStateOf<List<UsbIface>>(emptyList())
    var candidates by mutableStateOf<List<Candidate>>(emptyList())
    var scanning by mutableStateOf(false)
    var pendingHostKey by mutableStateOf<Pair<String, String>?>(null) // hostPort to fingerprint
    private var transport: SshTransport? = null

    // ---- AI ----
    var provider by mutableStateOf(settings.provider)
    var deepseekKey by mutableStateOf(secrets.get("deepseek_key") ?: "")
    var deepseekModel by mutableStateOf(settings.deepseekModel)
    var deepseekThinking by mutableStateOf(settings.deepseekThinking)
    var openaiKey by mutableStateOf(secrets.get("openai_key") ?: "")
    var openaiModel by mutableStateOf(settings.openaiModel)
    var claudeApiKey by mutableStateOf(secrets.get("claude_api_key") ?: "")
    var claudeApiModel by mutableStateOf(settings.claudeApiModel)
    var geminiKey by mutableStateOf(secrets.get("gemini_key") ?: "")
    var geminiModel by mutableStateOf(settings.geminiModel)
    var providerStatus by mutableStateOf("Not tested")
    var providerBusy by mutableStateOf(false)
    var usage by mutableStateOf(TokenUsage())

    // ---- app settings ----
    var customPrompt by mutableStateOf(settings.systemPrompt); private set
    val promptIsDefault get() = customPrompt == null
    val currentPrompt get() = customPrompt ?: DiagnosticAgent.DEFAULT_SYSTEM_PROMPT
    fun savePrompt(text: String) {
        val v = text.trim().takeIf { it.isNotEmpty() && it != DiagnosticAgent.DEFAULT_SYSTEM_PROMPT }
        customPrompt = v; settings.systemPrompt = v
    }
    fun resetPrompt() { customPrompt = null; settings.systemPrompt = null }
    /** What the agent actually receives: the instructions plus the optional reply-language line. */
    fun effectivePrompt() = currentPrompt + (if (rescueMode) com.rodinrescue.usb.RescueSystem.PROMPT_NOTE else "") + languageLine()
    private fun languageLine() = replyLanguage.trim().let { if (it.isEmpty()) "" else "\n\nAlways reply to the user in $it (commands and file paths stay as they are)." }

    // Keyboard rescue has its own instructions (different tools and rules), editable the same way.
    var customKbPrompt by mutableStateOf(settings.kbSystemPrompt); private set
    val kbPromptIsDefault get() = customKbPrompt == null
    val currentKbPrompt get() = customKbPrompt ?: com.rodinrescue.ai.KeyboardAgent.SYSTEM_PROMPT
    fun saveKbPrompt(text: String) {
        val v = text.trim().takeIf { it.isNotEmpty() && it != com.rodinrescue.ai.KeyboardAgent.SYSTEM_PROMPT }
        customKbPrompt = v; settings.kbSystemPrompt = v
    }
    fun resetKbPrompt() { customKbPrompt = null; settings.kbSystemPrompt = null }
    fun effectiveKbPrompt() = currentKbPrompt + languageLine()
    var kbAutoPhoto by mutableStateOf(settings.kbAutoPhoto); private set
    fun updateKbAutoPhoto(v: Boolean) { kbAutoPhoto = v; settings.kbAutoPhoto = v }
    var kbPhotoOnce by mutableStateOf(settings.kbPhotoOnce); private set
    fun updateKbPhotoOnce(v: Boolean) { kbPhotoOnce = v; settings.kbPhotoOnce = v }
    var replyLanguage by mutableStateOf(settings.replyLanguage); private set
    fun updateReplyLanguage(v: String) { replyLanguage = v; settings.replyLanguage = v }
    var maxSteps by mutableStateOf(settings.maxSteps); private set
    fun updateMaxSteps(v: Int) { maxSteps = v; settings.maxSteps = v }
    var keepScreenOn by mutableStateOf(settings.keepScreenOn); private set
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; settings.keepScreenOn = v }
    var showSplash by mutableStateOf(settings.showSplash); private set
    fun updateShowSplash(v: Boolean) { showSplash = v; settings.showSplash = v }

    // ---- chat ----
    val chat = mutableStateListOf<ChatItem>()
    var running by mutableStateOf(false)
    var streaming by mutableStateOf("")
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private val history = mutableListOf<Msg>()
    private var job: Job? = null
    var diagnosticStatus by mutableStateOf("Idle")

    // ---- terminal ----
    val termLines = mutableStateListOf<String>()
    val cmdHistory = mutableStateListOf<String>()
    private var shell: SshTransport.ShellHandle? = null
    private var shellJob: Job? = null
    var shellOpen by mutableStateOf(false)

    // ---- usb tools ----
    var usbReport by mutableStateOf("")
    var usbBusy by mutableStateOf(false)
    var rootStatus by mutableStateOf("Not checked")

    // ---- one-click setup ----
    var rootOk by mutableStateOf<Boolean?>(null)
    var tetherOk by mutableStateOf(false)
    var vpnActive by mutableStateOf(false)
    var bypassOk by mutableStateOf(false)
    var laptopCommand by mutableStateOf<String?>(null)
    var waitingForLaptop by mutableStateOf(false)
    var setupLog = mutableStateListOf<String>()
    var setupBusy by mutableStateOf(false)
    private val keys = KeyManager(secrets)
    private var setupServer: SetupServer? = null
    private var setupToken = ""
    private val lastIface get() = usbIfaces.firstOrNull()

    private fun slog(m: String) { setupLog.add(m); while (setupLog.size > 40) setupLog.removeAt(0) }

    /** Cheap status poll used by the Home screen. */
    fun refreshSetup() = viewModelScope.launch {
        usbIfaces = UsbNet.detect(); tetherOk = usbIfaces.isNotEmpty()
        vpnActive = NetFix.isVpnActive(ctx)
        val i = lastIface
        bypassOk = i != null && rootOk == true && NetFix.isApplied(i)
    }

    /** "Magisk" / "KernelSU" / "APatch" once known (for labels and hints). */
    var rootManager by mutableStateOf("your root manager"); private set
    fun checkRootStep() = viewModelScope.launch { rootOk = RootShell.available().ok; rootManager = RootShell.managerName() }

    /** Home's pull-to-refresh: root, USB link, VPN rule, saved devices, and whether the SSH link is still alive. */
    var refreshing by mutableStateOf(false); private set
    fun refreshAll() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                rootOk = RootShell.available().ok
                if (!setupBusy && laptopCommand == null) setupLog.clear()   // old setup messages are history after a refresh
                refreshSetup().join()
                loadDevices()
                if (connected) {
                    val t = ensureConnected()
                    if (t == null) { connected = false; connState = "Connection lost" }
                    else runCatching { t.exec("hostname; . /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"; uname -r", 10_000) }
                        .getOrNull()?.takeIf { it.ok }?.let { r -> targetInfo = r.stdout.lines().filter { it.isNotBlank() }.joinToString(" · ") }
                }
            } finally { refreshing = false }
        }
    }

    /** Does every automatable step, in order, and ends with the exact command to run on the laptop. */
    fun oneClickSetup() {
        if (setupBusy) return
        viewModelScope.launch {
            setupBusy = true; setupLog.clear()
            try {
                slog("1/5 Checking root (approve the root prompt if shown)…")
                rootOk = RootShell.available().ok
                if (rootOk != true) { slog("✗ No root. Open $rootManager → Superuser → allow Lifeline, then press again."); return@launch }
                slog("✓ Root OK")
                slog("2/5 USB tethering…")
                if (!TetherAutomation.enable { slog("   $it") }) { slog("✗ Could not switch it on automatically. Do it by hand: Settings → Hotspot & tethering → USB tethering (cable must be plugged into the laptop). Then press this button again."); refreshSetup().join(); return@launch }
                usbIfaces = UsbNet.detect(); tetherOk = usbIfaces.isNotEmpty()
                val i = lastIface ?: run { slog("✗ Tethering on but no USB interface seen yet — wait 5 s and press again."); return@launch }
                slog("✓ USB link ${i.name} ${i.phoneIp}")
                slog("3/5 VPN routing ${if (NetFix.isVpnActive(ctx)) "(VPN is on)" else "(no VPN)"}…")
                val r = NetFix.apply(i)
                if (!r.ok) { slog("✗ Routing fix failed: ${r.output}"); return@launch }
                vpnActive = NetFix.isVpnActive(ctx); bypassOk = true
                slog("✓ USB subnet ${NetFix.subnetOf(i)} bypasses the VPN (only that subnet)")
                slog("4/5 Creating the phone's SSH identity…")
                val id = withContext(Dispatchers.Default) { keys.identity() }
                slog("5/5 Starting the one-time laptop helper…")
                // The phone's USB serial number: the laptop script gives THIS phone's serial port a login prompt.
                val serial = RootShell.run("cat /config/usb_gadget/g1/strings/0x409/serialnumber 2>/dev/null || getprop ro.serialno").output.trim()
                startSetupServer(i, id.publicLine, serial)
            } finally { setupBusy = false }
        }
    }

    private fun startSetupServer(i: UsbIface, pub: String, phoneSerial: String? = null) {
        setupToken = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val srv = SetupServer(viewModelScope, { LaptopScript.build(i.phoneIp, setupServer!!.boundPort, setupToken, pub, phoneSerial.takeIf { settings.serialLogin }) }) { c -> viewModelScope.launch { onLaptopCheckin(c) } }
        setupServer?.stop(); setupServer = srv
        val port = srv.start(i.phoneIp, setupToken)
        laptopCommand = "curl -fsSL http://${i.phoneIp}:$port/s/$setupToken | sudo sh"
        waitingForLaptop = true
        slog("✓ Ready. Run the command below on the laptop (any terminal, even a text-mode console).")
    }

    private suspend fun onLaptopCheckin(c: Checkin) {
        slog("✓ Laptop checked in: ${c.user}@${c.ip} (${c.hostname}), ${c.fingerprints.size} host key(s)")
        host = c.ip; port = c.port.toString(); user = c.user; useKey = true
        sshKey = keys.identity().privatePem
        if (c.fingerprints.isNotEmpty()) { settings.pinHostKey("${c.ip}:${c.port}", c.fingerprints.joinToString(",")); settings.addKnownLaptopFps(c.fingerprints) }
        val now = System.currentTimeMillis()
        val old = deviceStore.find(c.fingerprints, c.user, c.hostname)
        val ch = c.changes
        val d = (old ?: DeviceProfile(DeviceStore.newId(), c.hostname.ifBlank { c.ip }, c.user, c.ip, c.port, c.hostname, c.fingerprints, now, now)).let {
            it.copy(user = c.user, lastIp = c.ip, port = c.port, hostname = c.hostname.ifBlank { it.hostname }, fingerprints = (it.fingerprints + c.fingerprints).distinct(), lastUsed = now,
                // keep the first record of what Lifeline changed; a re-run of setup reports the same original state
                stateRecorded = it.stateRecorded || ch.stateRecorded, installedSshd = it.installedSshd ?: ch.installedSshd,
                sshdWasEnabled = it.sshdWasEnabled ?: ch.sshdWasEnabled, ufwIface = it.ufwIface ?: ch.ufwIface)
        }
        deviceStore.save(d); activeDeviceId = d.id; loadDevices()
        slog("✓ Saved as device “${d.name}”")
        waitingForLaptop = false; laptopCommand = null; setupServer?.stop()
        connect()
    }

    fun cancelLaptopWait() { setupServer?.stop(); waitingForLaptop = false; laptopCommand = null }

    /** After a re-plug the laptop gets a new address: find it and reconnect without touching the laptop again. */
    fun reconnectKnownLaptop() = viewModelScope.launch {
        if (rootOk == true) lastIface?.let { NetFix.apply(it) }
        usbIfaces = UsbNet.detect(); val i = lastIface ?: run { connState = "No USB link"; return@launch }
        scanning = true; val c = usbScan(i, port.toIntOrNull() ?: 22); scanning = false
        val ip = c.firstOrNull()?.ip ?: run { connState = "No SSH server found on the USB link"; return@launch }
        host = ip; if (user.isBlank()) { connState = "Enter user"; return@launch }
        if (useKey && sshKey.isBlank()) sshKey = keys.identity().privatePem
        connect()
    }

    /** Lifeline's built-in Ubuntu (PRoot), one copy per subscription CLI: runs the official CLIs without Termux. */
    val ubuntus = com.rodinrescue.runtime.Ubuntu.Cli.entries.associateWith { com.rodinrescue.runtime.Ubuntu(app, it) }
    private val claude = ClaudeCliProvider(ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE))
    private val deepseek = DeepSeekProvider({ deepseekKey.ifBlank { null } }, { deepseekModel }, { deepseekThinking })
    private val codex = CodexCliProvider(ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CODEX))
    private val geminiCli = AgyCliProvider(ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.AGY))
    val cliProviders: Map<com.rodinrescue.runtime.Ubuntu.Cli, CliProvider> = mapOf(
        com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE to claude, com.rodinrescue.runtime.Ubuntu.Cli.CODEX to codex, com.rodinrescue.runtime.Ubuntu.Cli.AGY to geminiCli)

    // ---- built-in CLIs: model + effort per CLI ("" = the CLI's own default) ----
    val cliModel = mutableStateMapOf<com.rodinrescue.runtime.Ubuntu.Cli, String>().apply { com.rodinrescue.runtime.Ubuntu.Cli.entries.forEach { put(it, settings.cliModel(it.id)) } }
    val cliEffort = mutableStateMapOf<com.rodinrescue.runtime.Ubuntu.Cli, String>().apply { com.rodinrescue.runtime.Ubuntu.Cli.entries.forEach { put(it, settings.cliEffort(it.id)) } }
    val cliModels = mutableStateMapOf<com.rodinrescue.runtime.Ubuntu.Cli, List<CliProvider.ModelChoice>>()
    var cliModelsBusy by mutableStateOf<com.rodinrescue.runtime.Ubuntu.Cli?>(null)
    /** Bumps after each step so the card re-reads what the CLI reported it used. */
    var cliUsedRev by mutableStateOf(0)

    fun setCliModel(cli: com.rodinrescue.runtime.Ubuntu.Cli, v: String) {
        val m = v.trim().takeIf { it.isEmpty() || CliProvider.MODEL_NAME.matches(it) } ?: return
        cliModel[cli] = m; settings.setCliModel(cli.id, m)
        // a model with its own effort list: drop an effort it doesn't support
        val e = cliEffort[cli].orEmpty(); val supported = cliModels[cli]?.firstOrNull { it.id == m }?.efforts.orEmpty()
        if (e.isNotEmpty() && supported.isNotEmpty() && e !in supported) setCliEffort(cli, "")
    }
    fun setCliEffort(cli: com.rodinrescue.runtime.Ubuntu.Cli, v: String) { cliEffort[cli] = v; settings.setCliEffort(cli.id, v) }

    fun refreshCliModels(cli: com.rodinrescue.runtime.Ubuntu.Cli) {
        if (cliModelsBusy != null || !ubuntus.getValue(cli).ready()) return
        viewModelScope.launch {
            cliModelsBusy = cli
            try { cliModels[cli] = cliProviders.getValue(cli).models() } catch (e: Exception) { builtinError = "Couldn't read ${cli.title}'s models: ${e.message}" }
            finally { cliModelsBusy = null }
        }
    }

    init {
        cliProviders.forEach { (cli, p) -> p.model = { cliModel[cli].orEmpty() }; p.effort = { cliEffort[cli].orEmpty() } }
    }

    /** Each subscription CLI in one long session per conversation, Lifeline's tools over MCP (option B). Off = one CLI run per step. */
    val longSession = mutableStateMapOf<com.rodinrescue.runtime.Ubuntu.Cli, Boolean>().apply { com.rodinrescue.runtime.Ubuntu.Cli.entries.forEach { put(it, settings.longSession(it.id)) } }
    fun updateLongSession(cli: com.rodinrescue.runtime.Ubuntu.Cli, v: Boolean) { longSession[cli] = v; settings.setLongSession(cli.id, v); if (!v) cliSessions.getValue(cli).close() }
    val cliSessions: Map<com.rodinrescue.runtime.Ubuntu.Cli, CliSessionProvider> = mapOf(
        com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE to ClaudeSessionProvider(claude, ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE)) { longSession[com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE] == true },
        com.rodinrescue.runtime.Ubuntu.Cli.CODEX to CodexSessionProvider(codex, ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CODEX)) { longSession[com.rodinrescue.runtime.Ubuntu.Cli.CODEX] == true },
        com.rodinrescue.runtime.Ubuntu.Cli.AGY to AgySessionProvider(geminiCli, ubuntus.getValue(com.rodinrescue.runtime.Ubuntu.Cli.AGY)) { longSession[com.rodinrescue.runtime.Ubuntu.Cli.AGY] == true })
    private val openai = OpenAiProvider({ openaiKey.ifBlank { null } }, { openaiModel })
    private val gemini = GeminiProvider({ geminiKey.ifBlank { null } }, { geminiModel })
    private val claudeApi = ClaudeApiProvider({ claudeApiKey.ifBlank { null } }, { claudeApiModel.trim().ifBlank { ClaudeApiProvider.DEFAULT_MODEL } })

    val allProviders: List<AiProvider> get() = listOf(cliSessions.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE), cliSessions.getValue(com.rodinrescue.runtime.Ubuntu.Cli.CODEX), cliSessions.getValue(com.rodinrescue.runtime.Ubuntu.Cli.AGY), claudeApi, deepseek, openai, gemini)

    fun activeProvider(): AiProvider = allProviders.firstOrNull { it.id == provider } ?: deepseek

    fun saveConnection() {
        settings.host = host.trim(); settings.port = port.toIntOrNull() ?: 22; settings.user = user.trim(); settings.useKeyAuth = useKey
        secrets.put("ssh_password", sshPassword.ifBlank { null }); secrets.put("ssh_key", sshKey.ifBlank { null })
    }

    fun saveProviderSettings() {
        settings.provider = provider; settings.deepseekModel = deepseekModel; settings.deepseekThinking = deepseekThinking
        settings.openaiModel = openaiModel; settings.geminiModel = geminiModel; settings.claudeApiModel = claudeApiModel
        secrets.put("deepseek_key", deepseekKey.ifBlank { null })
        // Tokens of the old Termux bridges (removed 2026-10-28): drop them from storage.
        secrets.put("bridge_token", null); secrets.put("codex_bridge_token", null); secrets.put("gemini_bridge_token", null)
        secrets.put("openai_key", openaiKey.ifBlank { null }); secrets.put("gemini_key", geminiKey.ifBlank { null })
        secrets.put("claude_api_key", claudeApiKey.ifBlank { null })
    }

    // ---------- USB networking / SSH ----------
    fun detectUsb() = viewModelScope.launch {
        usbIfaces = UsbNet.detect(); candidates = emptyList()
        val i = usbIfaces.firstOrNull() ?: return@launch
        scanning = true
        candidates = usbScan(i, port.toIntOrNull() ?: 22)
        if (candidates.size == 1 && host.isBlank()) host = candidates[0].ip
        scanning = false
    }

    fun connect() { viewModelScope.launch { connectNow() } }

    /** Connects with the current host/user/credentials; returns true on success. */
    private suspend fun connectNow(): Boolean {
        saveConnection()
        run {
            val p = port.toIntOrNull() ?: 22
            connState = "Connecting…"
            val creds = SshCredentials(host.trim(), p, user.trim(), if (useKey) null else sshPassword, if (useKey) sshKey else null, keyPassphrase.ifBlank { null })
            // The rescue system: trust exactly this session's host key (the phone made it), whatever address the PC got;
            // the laptops' saved pins are left alone.
            val t = SshTransport(creds, rescueHostFp?.let { fp -> object : com.rodinrescue.transport.HostKeyStore {
                override fun pinnedHostKey(hostPort: String) = fp
                override fun pinHostKey(hostPort: String, fingerprint: String) {}
                override fun forgetHostKey(hostPort: String) {}
            } } ?: settings, socketFactory = relayFor(host.trim()))
            try {
                t.connect()
                transport?.close(); transport = t; connected = true; connState = "Connected to ${t.description}"
                serialAi = null; serialAiOn = false   // one target at a time
                val r = t.exec("hostname; . /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"; uname -r", 10_000)
                targetInfo = r.stdout.lines().filter { it.isNotBlank() }.joinToString(" · ")
                if (rescueMode) targetInfo = "Lifeline rescue system · " + targetInfo.substringAfter(" · ", "")
                else rememberDevice(r.stdout.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim(), p)
                if (!setupBusy && laptopCommand == null) setupLog.clear()   // setup finished: the log has done its job
                return true
            } catch (e: HostKeyUnknownException) {
                if (e.fingerprint in settings.knownLaptopFps()) { settings.pinHostKey(e.hostPort, e.fingerprint); return connectNow() }
                connected = false; connState = "Host key not trusted yet"; pendingHostKey = e.hostPort to e.fingerprint
            } catch (e: HostKeyMismatchException) {
                connected = false; connState = "⚠ HOST KEY CHANGED — connection refused. Expected ${e.expected}, got ${e.actual}"
            } catch (e: Exception) {
                connected = false; connState = "Failed: ${e.message}"
            }
        }
        return false
    }

    /**
     * With a VPN on, Android doesn't deliver the USB link's replies to apps (POCO, 2026-10-28): connections to an
     * address on the USB link go through a root relay ([RootRelay]). Without a VPN, or for any other address: direct.
     */
    private fun relayFor(h: String): javax.net.SocketFactory? {
        if (!NetFix.isVpnActive(ctx)) return null
        val onUsb = UsbNet.detect().any { it.phoneIp.substringBeforeLast('.') == h.substringBeforeLast('.') }
        return if (onUsb) RootRelay.socketFactory else null
    }
    /** USB-link scan: as root while a VPN is on (see [relayFor]). */
    private suspend fun usbScan(i: UsbIface, port: Int) = if (NetFix.isVpnActive(ctx)) RootRelay.scan(i, port) else UsbNet.scan(i, port)

    /** The UI flag can outlive a dead link (cable pulled, laptop asleep): reconnect once before using it. */
    private suspend fun ensureConnected(): SshTransport? {
        if (transport?.isConnected == true) return transport
        if (!connected) return null
        connState = "Reconnecting…"
        return if (connectNow()) transport else null
    }

    fun trustPendingHostKey() { pendingHostKey?.let { settings.pinHostKey(it.first, it.second) }; pendingHostKey = null; connect() }
    fun rejectPendingHostKey() { pendingHostKey = null; connState = "Host key rejected" }
    fun probeHostKey() = viewModelScope.launch {
        try { val fp = SshTransport.probeHostKey(host.trim(), port.toIntOrNull() ?: 22); pendingHostKey = "${host.trim()}:${port.toIntOrNull() ?: 22}" to fp }
        catch (e: Exception) { connState = "Probe failed: ${e.message}" }
    }
    fun disconnect() {
        val d = activeDevice()
        if (autoCleanup && connected && d != null && !removing) { askRemove(d); return }   // removal disconnects when done
        closeConnection()
    }
    private fun closeConnection() { stopAll(); closeShell(); transport?.close(); transport = null; connected = false; connState = "Disconnected" }

    // ---------- chat / agent ----------
    fun newDiagnosis() { kbTextOffered = false; screenArea = null; giveControlBack(); controlNote = null; stopAll(); cliSessions.values.forEach { it.close() }; chat.clear(); history.clear(); usage = TokenUsage(); audit.newSession(); conversationDevice = activeDeviceId; diagnosticStatus = "Idle"; kbMode = false; attachedPhoto = null }

    fun send(text: String) {
        if (text.isBlank() || running) return
        // A keyboard-rescue conversation must continue there: the SSH agent has other tools and can't see the screen.
        if (kbMode) { chat.add(ChatItem.Notice("This is a keyboard rescue conversation. Continue it in Keyboard rescue (it needs the camera), or start a new rescue here.", true)); return }
        val ssh = transport?.takeIf { connected }
        val serial = serialAi?.takeIf { it.isConnected }
        val t: CommandTransport = ssh ?: serial ?: run {
            chat.add(ChatItem.Notice("Not connected to a target computer. Open Home and connect over SSH first, or log in over USB tools › USB serial.", true)); return
        }
        saveProviderSettings()
        if (chat.isEmpty()) conversationDevice = activeDeviceId
        chat.add(ChatItem.UserMsg(text)); running = true; diagnosticStatus = "Diagnosing…"; streaming = ""
        persistConversation()
        val agent = DiagnosticAgent(
            provider = activeProvider(), transport = t, audit = audit,
            approvals = { req -> askApproval(req) },
            sudoPassword = { sudoPassword.ifBlank { null } },
            targetIsRoot = if (ssh != null) user.trim() == "root" else serial?.rootUser == true,
            maxSteps = maxSteps,
            systemPrompt = effectivePrompt(),
            onEvent = { ev -> viewModelScope.launch { handle(ev) } }
        )
        job = viewModelScope.launch {
            try { agent.run(history, text) }
            catch (_: CancellationException) { }
            finally { running = false; streaming = ""; pendingApproval = null; approvalDeferred?.complete(false); persistConversation() }
        }
    }

    // ---------- keyboard rescue mode: camera = eyes, USB keyboard = hands ----------
    /** Keyboard rescue's Linux | Windows switch (the user's choice, not the AI's): which rules typed commands follow. */
    var kbWindows by mutableStateOf(false)
    /** Set by the rescue screen while its camera is bound; null = no camera. */
    var camera by mutableStateOf<com.rodinrescue.vision.CameraEyes?>(null)
    /** The AI's eyes while the camera is bound. */
    val eyes: com.rodinrescue.ai.Eyes? get() = camera?.let { kbEyes }
    /** The phone is held in the hand: every look is a photo the user takes on the photo card. Off = on a stand, automatic photos. */
    var kbHandheld by mutableStateOf(settings.kbHandheld); private set
    fun updateKbHandheld(v: Boolean) { kbHandheld = v; settings.kbHandheld = v }
    /** Where the PC's screen is in the photo (set on the photo card); photos are cut to it and straightened. Per rescue. */
    var screenArea by mutableStateOf<com.rodinrescue.vision.ScreenArea?>(null)

    /** The photo card: [forAi] = the AI is waiting for this photo (else: the user's own 📷 or setting the screen area). */
    class PhotoRequest(val why: String, val forAi: Boolean, val reply: CompletableDeferred<com.rodinrescue.ai.Screen?>)
    var photoRequest by mutableStateOf<PhotoRequest?>(null); private set
    /** The card's still photo, while the user fits the screen area on it. */
    var cardPhoto by mutableStateOf<android.graphics.Bitmap?>(null); private set
    var cardArea by mutableStateOf(com.rodinrescue.vision.ScreenArea.DEFAULT)
    var cardBusy by mutableStateOf(false); private set

    private val kbEyes = object : com.rodinrescue.ai.Eyes {
        override suspend fun look() = kbLook()
        /** On a stand the camera watches the screen settle; in the hand there's no live view to watch: a fixed wait. */
        override suspend fun <T> settle(quietMs: Long, maxMs: Long, fallbackMs: Long, action: suspend () -> T): T {
            val cam = camera
            return if (cam == null || kbHandheld) { val r = action(); kotlinx.coroutines.delay(fallbackMs); r } else cam.watch(quietMs, maxMs, action)
        }
    }
    private suspend fun kbLook(): com.rodinrescue.ai.Screen {
        awaitControl()
        val cam = camera ?: throw IllegalStateException("The camera isn't ready.")
        val why = if (kbHandheld) "The AI wants to see the PC's screen." else {
            val photo = cam.capture()
            val bad = com.rodinrescue.vision.uselessPhoto(cam.grid(photo)) ?: return cam.screen(photo, screenArea)
            "I can't use the automatic photo: $bad. Aim the camera at the PC's screen and take one."
        }
        return askPhoto(why, forAi = true) ?: throw IllegalStateException("The user skipped the photo. Ask them what the screen shows, or ask for a photo again later.")
    }

    /** Shows the photo card and waits for the user's photo (null = skipped or stopped). */
    private suspend fun askPhoto(why: String, forAi: Boolean): com.rodinrescue.ai.Screen? {
        photoRequest?.reply?.complete(null)
        val d = CompletableDeferred<com.rodinrescue.ai.Screen?>()
        val before = diagnosticStatus
        cardPhoto = null; cardArea = screenArea ?: com.rodinrescue.vision.ScreenArea.DEFAULT
        photoRequest = PhotoRequest(why, forAi, d)
        if (forAi) diagnosticStatus = "Waiting for your photo"
        return try { d.await() } finally { if (photoRequest?.reply === d) { photoRequest = null; cardPhoto = null }; if (forAi) diagnosticStatus = before }
    }

    fun cardTake() = viewModelScope.launch {
        val cam = camera ?: return@launch
        cardBusy = true
        try { cardPhoto = cam.capture() } catch (e: Exception) { chat.add(ChatItem.Notice("Camera error: ${e.message}", true)) } finally { cardBusy = false }
    }
    fun cardRetake() { cardPhoto = null }
    /** Sends the card's photo cut to [area] (null = the whole photo) and keeps the area for the next photos. */
    fun cardSend(area: com.rodinrescue.vision.ScreenArea?) = viewModelScope.launch {
        val cam = camera ?: return@launch; val photo = cardPhoto ?: return@launch; val req = photoRequest ?: return@launch
        cardBusy = true
        try { screenArea = area?.takeIf { it.valid() }; req.reply.complete(cam.screen(photo, screenArea)) }
        catch (e: Exception) { chat.add(ChatItem.Notice("Couldn't prepare the photo: ${e.message}", true)) } finally { cardBusy = false }
    }
    fun cardSkip() { photoRequest?.reply?.complete(null) }
    /** From the camera panel: take a photo and fit the frame to the PC's screen, for the photos that follow. */
    fun setScreenArea() = viewModelScope.launch { askPhoto("Take a photo and fit the frame to the PC's screen. The next photos are cut to it: sharper for the AI.", forAi = false) }
    /** The current conversation is a keyboard rescue (saved with it as mode = "keyboard"). */
    var kbMode by mutableStateOf(false); private set
    /** Photo the user attached with 📷, sent with their next message. */
    var attachedPhoto by mutableStateOf<ByteArray?>(null); private set
    var attaching by mutableStateOf(false); private set
    /** Newest photo the AI saw (shown on the approval card). */
    var lastSeenPhoto by mutableStateOf<ByteArray?>(null); private set
    private val hands = object : com.rodinrescue.ai.Hands {
        override suspend fun press(combo: String, times: Int) = run { awaitControl(); HidController.press(combo, times).let { if (it.state == HidController.State.ON) null else it.message } }
        override suspend fun type(text: String) = run { awaitControl(); HidController.type(text).let { if (it.state == HidController.State.ON) null else it.message } }
    }

    // ---- "Take control": the user types a password on the PC through the phone; the AI never sees it ----
    /** The user has the keyboard: the AI's keys, typing and photos wait until control is given back. */
    var userControl by mutableStateOf(false); private set
    private var controlBack = CompletableDeferred(Unit)
    /** Told to the AI with its next tool result once control is back. */
    private var controlNote: String? = null
    private suspend fun awaitControl() {
        if (!userControl) return
        val before = diagnosticStatus; diagnosticStatus = "Paused: you have control"
        try { controlBack.await() } finally { diagnosticStatus = before }
    }
    fun takeControl() {
        if (userControl) return
        controlBack = CompletableDeferred(); userControl = true
        audit.append(AuditEntry(System.currentTimeMillis(), "control", "User took control of the PC's keyboard"))
    }
    fun giveControlBack() {
        if (!userControl) return
        userControl = false; controlBack.complete(Unit)
        if (typedHidden) controlNote = "[Lifeline app: the user took control of the PC's keyboard and typed something you can't see (for example a password), " +
            "then gave control back. Look at the screen before you continue.]"
        typedHidden = false
        audit.append(AuditEntry(System.currentTimeMillis(), "control", "User gave control back to the AI"))
    }
    private var typedHidden = false
    /**
     * Types the user's [secret] on the PC. Never saved, logged, shown or sent to the AI; the caller clears its field.
     * Refuses characters the US keyboard can't type, sending nothing.
     */
    fun typeHidden(secret: String, enter: Boolean, done: (String?) -> Unit) = viewModelScope.launch {
        if (!userControl) return@launch done("Take control first.")
        val bad = HidKeyboard.untypable(secret)
        if (bad.isNotEmpty()) return@launch done("These characters can't be typed on a US keyboard: ${bad.joinToString(" ")}. Nothing was sent.")
        val r = HidController.type(if (enter) secret + "\n" else secret)
        if (r.state == HidController.State.ON) { typedHidden = true; done(null) } else done("Couldn't type: ${r.message}")
    }
    /** A single key from the control panel (the user's own press: no approval). */
    fun controlKey(combo: String) = viewModelScope.launch { if (userControl) HidController.press(combo, 1).also { if (it.state == HidController.State.ON) typedHidden = true } }

    /** Opening the screen: a keyboard conversation continues; anything else starts a fresh one (other tools). Keyboard on. */
    fun enterKeyboardMode() {
        if (!kbMode) { newDiagnosis(); kbMode = true }
        if (kbState != HidController.State.ON) kbToggle(true)
    }
    /** Leaving the screen unbinds the camera, so a running rescue can't continue. The conversation stays keyboard mode. */
    fun leaveKeyboardScreen() { if (running) stopAll() }

    fun attachPhoto() = viewModelScope.launch {
        val cam = camera ?: return@launch
        attaching = true
        try {
            val s = if (kbHandheld) askPhoto("Take the photo to attach to your message.", forAi = false) else cam.screen(cam.capture(), screenArea)
            s?.let { attachedPhoto = it.jpeg }
        } catch (ex: Exception) { chat.add(ChatItem.Notice("Camera error: ${ex.message}", true)) } finally { attaching = false }
    }
    fun clearAttachment() { attachedPhoto = null }

    fun photoFile(name: String) = convStore.photoFile(audit.sessionId, name)

    fun sendKeyboard(text: String) {
        if (text.isBlank() || running) return
        val e = eyes ?: run { chat.add(ChatItem.Notice("The camera isn't ready. Allow camera access and aim the phone at the PC's screen.", true)); return }
        if (kbState != HidController.State.ON) { chat.add(ChatItem.Notice("The USB keyboard is off. Turn it on (the PC must be plugged in).", true)); return }
        saveProviderSettings()
        val photo = attachedPhoto; attachedPhoto = null
        chat.add(ChatItem.UserMsg(text, photo?.let { convStore.savePhoto(audit.sessionId, it) })); running = true; diagnosticStatus = "Looking…"; streaming = ""
        persistConversation()
        val agent = com.rodinrescue.ai.KeyboardAgent(
            provider = activeProvider(), eyes = e, hands = hands, audit = audit,
            approvals = { req -> askApproval(req) },
            maxSteps = (maxSteps * 2).coerceIn(20, 120),
            systemPrompt = effectiveKbPrompt(),
            autoPhoto = kbAutoPhoto, smartPhoto = !kbHandheld, photoOnce = kbPhotoOnce,
            text = kbTextMode.takeIf { serialSupported != false },
            windowsShell = { kbWindows },
            interjection = { controlNote.also { controlNote = null } },
            pause = { awaitControl() },
            onEvent = { ev -> viewModelScope.launch { handle(ev) } }
        )
        job = viewModelScope.launch {
            try { agent.run(history, text, photo) }
            catch (_: CancellationException) { }
            finally { running = false; streaming = ""; pendingApproval = null; approvalDeferred?.complete(false); persistConversation() }
        }
    }

    private suspend fun askApproval(req: ApprovalRequest): Boolean {
        // A normally-forbidden command always needs the user's own OVERRIDE on the card, whatever the switches say.
        if (autoApprove && req.risk != com.rodinrescue.security.Risk.FORBIDDEN && (req.risk != com.rodinrescue.security.Risk.DESTRUCTIVE || autoApproveDestructive)) {
            autoApproved += req.id
            audit.append(AuditEntry(System.currentTimeMillis(), "approval", "${req.id} AUTO-APPROVED (Run changes without asking is on)", risk = req.risk.name))
            return true
        }
        val d = CompletableDeferred<Boolean>()
        approvalDeferred = d; pendingApproval = req; diagnosticStatus = "Waiting for your approval"
        return try { d.await() } finally { pendingApproval = null; diagnosticStatus = "Diagnosing…" }
    }

    fun answerApproval(ok: Boolean) { approvalDeferred?.complete(ok) }

    private fun handle(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantText -> if (ev.streaming) streaming += ev.text else { streaming = ""; chat.add(ChatItem.AssistantMsg(ev.text)) }
            is AgentEvent.ToolRequested -> chat.add(ChatItem.ToolCallItem(ev.name, ev.args))
            is AgentEvent.CommandRun -> chat.add(ChatItem.Cmd(ev.command, ev.privileged, ev.result))
            is AgentEvent.Approval -> chat.add(ChatItem.ApprovalItem(ev.req, ev.approved, auto = autoApproved.remove(ev.req.id)))
            is AgentEvent.Blocked -> chat.add(ChatItem.Notice("Blocked: ${ev.command}\n${ev.reason}", true))
            is AgentEvent.Usage -> { usage = ev.total; cliUsedRev++ }
            is AgentEvent.ScreenSeen -> { lastSeenPhoto = ev.jpeg; chat.add(ChatItem.ScreenItem(photo = convStore.savePhoto(audit.sessionId, ev.jpeg))) }
            is AgentEvent.KeysSent -> chat.add(ChatItem.KeysItem(ev.label, ev.error))
            is AgentEvent.Error -> chat.add(ChatItem.Notice(ev.message, true))
            is AgentEvent.Notice -> chat.add(ChatItem.Notice(ev.message))
            is AgentEvent.Finished -> { diagnosticStatus = "Finished (${ev.reason})"; streaming = ""; cliUsedRev++ }
        }
    }

    /** The visible STOP button: cancels the agent job, aborts in-flight SSH commands. */
    fun stopAll() {
        job?.cancel(); transport?.cancelAll(); approvalDeferred?.complete(false); photoRequest?.reply?.complete(null); cliSessions.values.forEach { it.abandonTurn() }
        if (running) { chat.add(ChatItem.Notice("Stopped by user.")); running = false; diagnosticStatus = "Stopped" }
    }

    // ---------- providers ----------
    fun testProvider() = viewModelScope.launch {
        saveProviderSettings(); providerBusy = true; providerStatus = "Testing…"
        providerStatus = try { activeProvider().testConnection() } catch (e: Exception) { "✗ ${e.message}" }
        providerBusy = false
    }

    // ---- built-in subscription CLIs (Claude Code, Codex, agy) in Lifeline's own Ubuntu ----
    var builtinBusy by mutableStateOf(false)
    /** Which CLI the busy step / sign-in belongs to. */
    var builtinCli by mutableStateOf<com.rodinrescue.runtime.Ubuntu.Cli?>(null)
    var builtinStep by mutableStateOf("")
    var builtinFraction by mutableStateOf<Float?>(null)
    var builtinError by mutableStateOf<String?>(null)
    /** The sign-in page (and Codex's one-time code) while a login waits; never logged. */
    var builtinLink by mutableStateOf<com.rodinrescue.runtime.CliLogin.Link?>(null)
    val builtinStatus = mutableStateMapOf<com.rodinrescue.runtime.Ubuntu.Cli, com.rodinrescue.runtime.CliLogin.Status>()
    var builtinRev by mutableStateOf(0)   // bumps when install state changes, so the card re-reads it
    private var builtinJob: Job? = null
    private val logins = ubuntus.mapValues { com.rodinrescue.runtime.CliLogin(it.value) }

    /** [keepRunning]: long work (downloads) that must survive the user leaving the app; quick checks don't need it. */
    private fun builtin(cli: com.rodinrescue.runtime.Ubuntu.Cli, keepRunning: Boolean = false, work: suspend () -> Unit) {
        if (builtinBusy) return
        builtinCli = cli
        builtinJob = viewModelScope.launch {
            builtinBusy = true; builtinError = null
            if (keepRunning) com.rodinrescue.runtime.RuntimeService.hold(ctx, "Setting up ${cli.title}…")
            try { work() } catch (e: CancellationException) { builtinStep = "Stopped."; throw e }
            catch (e: Exception) { builtinError = e.message ?: e.toString(); audit.append(AuditEntry(System.currentTimeMillis(), "error", "built-in ${cli.id}: ${e.message}", "", null, null)) }
            finally { builtinBusy = false; builtinFraction = null; builtinRev++; if (keepRunning) com.rodinrescue.runtime.RuntimeService.release(ctx) }
        }
    }

    /** Installs whatever is missing: Ubuntu, then [cli]. Re-running updates the CLI. */
    fun builtinInstall(cli: com.rodinrescue.runtime.Ubuntu.Cli) = builtin(cli, keepRunning = true) {
        val ubuntu = ubuntus.getValue(cli)
        val setup = com.rodinrescue.runtime.UbuntuSetup(ubuntu)
        val progress = com.rodinrescue.runtime.UbuntuSetup.Progress { step, f -> viewModelScope.launch { builtinStep = step; builtinFraction = f } }
        if (!ubuntu.installed) setup.installUbuntu(progress)
        builtinRev++
        setup.install(progress)
        builtinStatus[cli] = logins.getValue(cli).status()
    }

    fun builtinStop() { builtinJob?.cancel(); logins.values.forEach { it.cancel() }; builtinLink = null }

    fun builtinSignIn(cli: com.rodinrescue.runtime.Ubuntu.Cli) {
        if (!ubuntus.getValue(cli).ready() || builtinBusy) return
        builtinCli = cli; builtinError = null; builtinLink = null; builtinStep = "Starting the official ${cli.title} sign-in…"
        // Stay running while the user is in the browser (Android pauses a backgrounded app; Codex waits online meanwhile).
        com.rodinrescue.runtime.RuntimeService.hold(ctx, "Waiting for your ${cli.title} sign-in…")
        logins.getValue(cli).start(onLink = { link -> viewModelScope.launch {
                builtinLink = link
                builtinStep = if (link.userCode != null) "Open the sign-in page and enter this code there." else "Open the sign-in page, then paste the code it shows here."
            } },
            onDone = { ok, msg -> viewModelScope.launch {
                com.rodinrescue.runtime.RuntimeService.release(ctx)
                builtinLink = null; builtinStep = msg
                if (!ok) builtinError = msg
                val st = runCatching { logins.getValue(cli).status() }.getOrNull()
                if (st != null) builtinStatus[cli] = st
                if (ok && st?.subscription != true) builtinError = "Signed in, but not with a subscription. Lifeline only uses subscription sign-ins here."
            } })
    }

    fun builtinSendCode(code: String) {
        val cli = builtinCli ?: return
        if (logins.getValue(cli).sendCode(code)) builtinStep = "Checking the code…" else builtinError = "No sign-in is waiting for a code: tap Sign in again."
    }

    fun builtinRefresh(cli: com.rodinrescue.runtime.Ubuntu.Cli) = builtin(cli) { if (ubuntus.getValue(cli).ready()) builtinStatus[cli] = logins.getValue(cli).status() }

    fun builtinSignOut(cli: com.rodinrescue.runtime.Ubuntu.Cli) = builtin(cli) { logins.getValue(cli).logout(); builtinStatus[cli] = logins.getValue(cli).status() }

    /** Removes [cli]'s own Ubuntu: the CLI and its sign-in. The other CLIs are not touched. */
    fun builtinRemove(cli: com.rodinrescue.runtime.Ubuntu.Cli) = builtin(cli) {
        builtinStep = "Removing ${cli.title}…"; logins.getValue(cli).cancel()
        withContext(Dispatchers.IO) { com.rodinrescue.runtime.RootfsArchive.deleteTree(ubuntus.getValue(cli).home) }
        builtinStatus.remove(cli); builtinStep = "Removed."
    }

    // ---------- terminal ----------
    fun openShell() {
        if (shellOpen) return
        viewModelScope.launch {
            val t = ensureConnected() ?: run { termLines.add("[not connected]"); return@launch }
            try {
                val h = withContext(Dispatchers.IO) { t.openShell() }
                shell = h; shellOpen = true
                shellJob = launch {
                    try { h.output.collect { appendTerm(it) } } catch (_: Exception) {}
                    shellOpen = false; termLines.add("[shell closed]")
                }
            } catch (e: Exception) { termLines.add("[shell failed: ${e.message}]") }
        }
    }

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\r")
    private fun appendTerm(chunk: String) {
        val clean = ansi.replace(chunk, "")
        val parts = clean.split("\n")
        if (termLines.isEmpty()) termLines.add("")
        termLines[termLines.lastIndex] = termLines.last() + parts.first()
        parts.drop(1).forEach { termLines.add(it) }
        while (termLines.size > 3000) termLines.removeAt(0)
    }

    fun sendShell(line: String) {
        if (line.isNotBlank()) { cmdHistory.remove(line); cmdHistory.add(0, line) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { shell?.send(line + "\n") } }
    }
    fun sendShellRaw(s: String) { viewModelScope.launch(Dispatchers.IO) { runCatching { shell?.send(s) } } }
    fun closeShell() { shellJob?.cancel(); shell?.close(); shell = null; shellOpen = false }
    fun terminalText() = termLines.joinToString("\n")

    // ---------- USB tools ----------
    private val toolkit = UsbToolkit { ctx.assets.open("inspect_device.sh").bufferedReader().readText() }

    fun checkRoot() = viewModelScope.launch {
        val r = RootShell.available()
        rootStatus = if (r.ok) "Root available: ${r.output}" else "No root: ${r.output}"
    }
    fun inspectUsb() = viewModelScope.launch { usbBusy = true; usbReport = "Running read-only probe…"; usbReport = toolkit.inspect().output; usbBusy = false }
    fun enableTether() = viewModelScope.launch { usbBusy = true; usbReport = "Turning on USB tethering via Settings…"; val lines = StringBuilder(); val ok = TetherAutomation.enable { lines.appendLine(it) }; usbReport = lines.toString() + if (ok) "USB tethering is on." else "Could not switch it on automatically — use Settings → Hotspot & tethering → USB tethering."; usbBusy = false }
    fun restoreUsb() = viewModelScope.launch { usbBusy = true; cancelLaptopWait(); NetFix.remove(); bypassOk = false; kbApply(HidController.disable()); val r = toolkit.restoreNormalUsb(); usbReport = "restore default USB → ${if (r.ok) "ok" else "failed"}\n${r.output}\n" + toolkit.currentUsbFunctions().output; usbBusy = false }

    // ---------- USB keyboard (HID) — user-initiated only ----------
    var kbState by mutableStateOf(HidController.State.OFF)
    var kbMessage by mutableStateOf("")
    var kbBusy by mutableStateOf(false)
    var kbText by mutableStateOf("")
    private fun kbApply(r: HidController.Result) { kbState = r.state; kbMessage = r.message }
    private fun kb(block: suspend () -> HidController.Result) = viewModelScope.launch {
        if (kbBusy) return@launch
        kbBusy = true
        try { kbApply(block()) } catch (e: IllegalArgumentException) { kbMessage = e.message ?: "invalid input" } finally { kbBusy = false }
    }
    fun kbRefresh() = kb { HidController.status() }
    fun kbToggle(on: Boolean) = kb { if (on) { rememberUsbMode(); HidController.enable() } else HidController.disable().also { restoreUsbModeLater() } }

    // ---- auto-off (UsbAutoOff): the keyboard must not linger and push file transfer off interface 0 ----
    /** sys.usb.config from before Lifeline added the keyboard or the boot drive (e.g. "mtp,adb"). */
    private var usbModeBefore: String? = null
    private var kbScreensOpen = 0
    private var kbLeftAt = 0L
    private suspend fun usbConfig() = RootShell.run("getprop sys.usb.config").output.trim()
    private suspend fun rememberUsbMode() {
        if (usbModeBefore == null && kbState != HidController.State.ON && bootStatus?.on != true && !serialOn) usbModeBefore = usbConfig()
    }
    /** Once neither the keyboard nor the boot drive is on, put Android's own mode back (file transfer). */
    private fun restoreUsbModeLater() = viewModelScope.launch {
        kotlinx.coroutines.delay(2_000)   // let the rebind settle
        if (kbState == HidController.State.ON || bootStatus?.on == true || serialOn) return@launch
        UsbAutoOff.restoreCommand(usbModeBefore, usbConfig())?.let { RootShell.run(it) }
        usbModeBefore = null
    }
    /** Keyboard screens (USB tab card, Type on PC, keyboard rescue) report when they open and close. */
    fun kbScreenOpened() { kbScreensOpen++ }
    fun kbScreenClosed() { kbScreensOpen = (kbScreensOpen - 1).coerceAtLeast(0); if (kbScreensOpen == 0) kbLeftAt = System.currentTimeMillis() }
    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (kbState == HidController.State.ON && !kbBusy && kbSpamming == null && voiceProgress == null &&
                    UsbAutoOff.shouldTurnOffKeyboard(System.currentTimeMillis(), HidController.lastUseMs, kbScreensOpen, kbLeftAt, agentBusy = running && kbMode)) {
                    kbApply(HidController.disable())
                    kbMessage = "The USB keyboard turned itself off (not in use). Turn it on again when you need it."
                    restoreUsbModeLater()
                }
            }
        }
    }
    fun kbPress(combo: String, times: Int = 1) = kb { HidController.press(combo, times) }
    var kbSpamming by mutableStateOf<String?>(null)
    private var spamJob: kotlinx.coroutines.Job? = null
    /** Taps [combo] every 300 ms for ~8 s: the window when firmware listens for F2 / Del / F12 at power-on. */
    fun kbSpam(combo: String) {
        spamJob?.cancel(); if (kbSpamming == combo) { kbSpamming = null; return }
        kbSpamming = combo
        spamJob = viewModelScope.launch {
            try { repeat(26) { val r = HidController.press(combo); if (r.state != HidController.State.ON) { kbApply(r); return@launch }; kotlinx.coroutines.delay(300) }; kbMessage = "Tapped $combo for 8 s" }
            finally { if (kbSpamming == combo) kbSpamming = null }
        }
    }
    fun kbType(send: Boolean) {
        val t = kbText.ifEmpty { return }
        val bad = HidKeyboard.untypable(t)
        if (bad.isNotEmpty()) { kbMessage = "Can't type ${bad.joinToString(" ")} (US keyboard layout only). Nothing was sent."; return }
        kb { HidController.type(if (send) t + "\n" else t).also { if (it.state == HidController.State.ON) kbText = "" } }
    }

    // ---------- voice typing: dictate a note on the phone, then type it on the PC ----------
    var voiceNote by mutableStateOf("")
    /** (typed, total) while a note is being typed; null otherwise. */
    var voiceProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var voiceMessage by mutableStateOf(""); private set
    /** True after a refusal: the note has characters a US keyboard can't type; "Send without them" is offered. */
    var voiceCanSkip by mutableStateOf(false); private set
    private var voiceStop = false
    fun voiceSend() {
        if (voiceProgress != null || voiceNote.isEmpty()) return
        voiceStop = false; voiceCanSkip = false
        voiceProgress = 0 to voiceNote.length
        viewModelScope.launch {
            val o = try { VoiceTyper.send(voiceNote, { HidController.type(it, rollover = true) }, { voiceStop }) { d, t -> voiceProgress = d to t } }
                    finally { voiceProgress = null }
            when (o) {
                is VoiceTyper.Outcome.Refused -> { voiceMessage = VoiceTyper.refusal(o); voiceCanSkip = true }
                is VoiceTyper.Outcome.Done -> { voiceNote = ""; voiceMessage = "Typed ${o.typed} characters on the PC." }
                is VoiceTyper.Outcome.Stopped -> {
                    voiceNote = o.left
                    voiceMessage = (o.error?.let { "Stopped: $it. " } ?: "Stopped. ") + "Typed ${o.typed}; the rest is still in the note."
                    if (o.error != null) kbRefresh()
                }
            }
        }
    }
    fun voiceStop() { voiceStop = true }
    fun voiceClear() { voiceNote = ""; voiceMessage = ""; voiceCanSkip = false }
    /** Rewrites the note to what a US keyboard can type (shown to the user), then sends it. */
    fun voiceSendWithoutThem() { voiceNote = VoiceTyper.makeTypable(voiceNote); voiceCanSkip = false; voiceMessage = ""; voiceSend() }
    /** Editing the note clears an old refusal. */
    fun voiceEdit(t: String) { voiceNote = t; if (voiceCanSkip) { voiceCanSkip = false; voiceMessage = "" } }

    // ---------- Lifeline rescue system (B2): the PC boots a small Lifeline system from the phone, then SSH rescue ----------
    /** The SSH target is the rescue system (its own AI note, its own host key, not saved as a device). */
    var rescueMode by mutableStateOf(false); private set
    var rescueStatus by mutableStateOf(""); private set
    var rescueBusy by mutableStateOf(false); private set
    var rescueProgress by mutableStateOf<Float?>(null); private set
    /** This session's host key fingerprint (made by the phone, given to the system on the key drive). */
    private var rescueHostFp: String? = null
    private var rescueJob: Job? = null
    /**
     * SSH settings before the rescue session, restored when it ends. Kept on the phone (the key in the secret store), so
     * an app restart during the session doesn't lose them (2026-10-29: an in-memory copy was retaken after a restart,
     * when the settings already pointed at the rescue system). Taken once per session.
     */
    private fun saveBeforeRescue() {
        if (secrets.get("before_rescue") != null) return
        secrets.put("before_rescue", listOf(host, port, user, useKey.toString()).joinToString("\n"))
        secrets.put("before_rescue_key", sshKey)
    }
    private fun restoreBeforeRescue() {
        val v = secrets.get("before_rescue")?.split("\n") ?: return
        if (v.size == 4) { host = v[0]; port = v[1]; user = v[2]; useKey = v[3].toBoolean(); sshKey = secrets.get("before_rescue_key").orEmpty(); saveConnection() }
        secrets.put("before_rescue", null); secrets.put("before_rescue_key", null)
    }
    val rescueImage get() = java.io.File(ctx.filesDir, "rescue/" + com.rodinrescue.usb.RescueSystem.ISO_NAME)
    private val rescueKeyFile get() = java.io.File(ctx.filesDir, "rescue/key.img")
    var rescueImageReady by mutableStateOf(false); private set
    fun rescueRefresh() { rescueImageReady = rescueImage.isFile }

    /** Downloads the rescue system from Lifeline's GitHub release and checks it against the SHA-256 built into the app. */
    fun rescueDownload() {
        if (rescueBusy) return
        rescueJob = viewModelScope.launch {
            rescueBusy = true; com.rodinrescue.runtime.RuntimeService.hold(ctx, "Downloading the rescue system…")
            try {
                rescueStatus = "Downloading the rescue system…"
                runInterruptible(Dispatchers.IO) { com.rodinrescue.usb.RescueSystem.download(rescueImage) { rescueProgress = it } }
                rescueStatus = "Rescue system ready (checked)."
            } catch (e: CancellationException) { throw e } catch (e: Exception) { rescueStatus = "Download failed: ${e.message}" }
            finally { rescueProgress = null; rescueBusy = false; rescueRefresh(); com.rodinrescue.runtime.RuntimeService.release(ctx) }
        }
    }

    /**
     * Shows the rescue system + this session's key drive to the PC, turns on the USB network, and waits (up to 20 min)
     * for the system to start SSH; then connects as root with the phone's key, trusting only the host key it made.
     */
    fun rescueStart() {
        if (rescueBusy || !rescueImage.isFile) return
        rescueJob = viewModelScope.launch {
            // Drives still on from this session: continue it. A new key would lock out a PC that already booted with the
            // old one (2026-10-29: a second Start rewrote the key drive and the running system no longer matched).
            val st = runCatching { BootController.status() }.getOrNull()
            if (st?.on == true && st.image == rescueImage.path && rescueKeyFile.isFile) { rescueJob = null; rescueResume(); return@launch }
            rescueBusy = true
            try {
                if (!RootShell.available().ok) { rescueStatus = "Lifeline needs root for this. Allow it in $rootManager."; return@launch }
                rescueStatus = "Preparing this session's keys…"
                val id = withContext(Dispatchers.Default) { keys.identity() }
                val hk = withContext(Dispatchers.Default) { com.rodinrescue.usb.RescueSystem.newHostKey() }
                withContext(Dispatchers.IO) { rescueKeyFile.parentFile?.mkdirs(); rescueKeyFile.writeBytes(com.rodinrescue.usb.RescueSystem.keyDrive(id.publicLine, hk.privatePem, hk.public)) }
                rememberUsbMode()
                // Drive first, then the network: rebinding the drive drops tethering (found 2026-10-15).
                BootController.startLuns(listOf(com.rodinrescue.usb.RescueDrive.Lun(rescueImage.path, name = "rescue"),
                    com.rodinrescue.usb.RescueDrive.Lun(rescueKeyFile.path, name = "key")))?.let { rescueStatus = it; return@launch }
                bootReadStatus()
                rescueStatus = "Turning on the USB network…"
                if (!TetherAutomation.enable { }) rescueStatus = "Turn on USB tethering by hand (Settings → Hotspot & tethering → USB tethering)."
                rescueStatus = "Now restart the PC and choose “Lifeline - rescue” in its boot menu (often F12, F11, F8 or Esc right after power-on). Waiting for it to start…"
                rescueWait(com.rodinrescue.transport.fingerprintOf(hk.public), id.privatePem)
            } finally { rescueBusy = false }
        }
    }

    /**
     * Opening the app while the rescue drives are still on (the user left the app during the wait): pick the session up
     * again from its key drive and keep waiting / connecting.
     */
    fun rescueResume() {
        if (rescueBusy || rescueMode || !rescueKeyFile.isFile) return
        rescueJob = viewModelScope.launch {
            val st = runCatching { BootController.status() }.getOrNull()
            if (st?.on != true || st.image != rescueImage.path) return@launch
            val pub = withContext(Dispatchers.IO) { com.rodinrescue.usb.RescueSystem.hostPublicOf(rescueKeyFile.readBytes()) } ?: return@launch
            rescueBusy = true
            try {
                rescueStatus = "The rescue drives are on. Waiting for the PC to start the Lifeline rescue system…"
                rescueWait(com.rodinrescue.transport.fingerprintOf(pub), withContext(Dispatchers.Default) { keys.identity() }.privatePem)
            } finally { rescueBusy = false }
        }
    }

    /** Waits (up to 20 min) for the rescue system's SSH on the USB subnet and connects, trusting only [fp]. Keeps the app running meanwhile. */
    private suspend fun rescueWait(fp: String, privatePem: String) {
        rescueHostFp = fp
        saveBeforeRescue()
        com.rodinrescue.runtime.RuntimeService.hold(ctx, "Waiting for the Lifeline rescue system…")
        try {
            val deadline = System.currentTimeMillis() + 20 * 60_000
            while (System.currentTimeMillis() < deadline) {
                val i = UsbNet.detect().firstOrNull()
                if (i != null) {
                    if (NetFix.isVpnActive(ctx)) NetFix.apply(i)
                    // Every SSH server on the USB link is tried; only the one with this session's host key is accepted.
                    for (c in usbScan(i, 22)) {
                        host = c.ip; port = "22"; user = "root"; useKey = true; sshKey = privatePem
                        rescueMode = true
                        if (connectNow()) { rescueStatus = "Connected to the Lifeline rescue system. Tap Start rescue on Home or in the chat."; return }
                        rescueMode = false
                    }
                }
                delay(3000)
            }
            rescueStatus = "The rescue system didn't answer in 20 minutes. Did the PC start from the phone? Try again, or check the PC's boot menu."
        } finally { com.rodinrescue.runtime.RuntimeService.release(ctx) }
    }

    /** Ends the rescue session: disconnects, stops the drives, deletes the key drive, puts the old SSH settings back. */
    fun rescueStop() = viewModelScope.launch {
        rescueJob?.cancel(); rescueBusy = false
        if (rescueMode) { stopAll(); closeConnection() }
        rescueMode = false; rescueHostFp = null
        restoreBeforeRescue()
        BootController.stop(); bootReadStatus(); restoreUsbModeLater()
        withContext(Dispatchers.IO) { rescueKeyFile.delete() }
        rescueStatus = "Rescue session ended. Restart the PC normally."
    }

    // ---------- Boot tab: the phone as a bootable USB drive ----------
    var bootStatus by mutableStateOf<BootController.Status?>(null); private set
    var bootImages by mutableStateOf<List<BootLibrary.Image>>(emptyList()); private set
    /** Detected (or user-chosen) mode per image name. */
    val bootModes = androidx.compose.runtime.mutableStateMapOf<String, BootLibrary.Mode>()
    var bootBusy by mutableStateOf<String?>(null); private set      // what's running, for the UI
    var bootMessage by mutableStateOf(""); private set
    var bootCopy by mutableStateOf<Pair<Long, Long>?>(null); private set

    private fun bootRun(what: String, block: suspend () -> Unit) {
        if (bootBusy != null) return
        viewModelScope.launch { bootBusy = what; try { block() } finally { bootBusy = null } }
    }
    private suspend fun bootReload() {
        bootReadStatus()
        bootImages = BootController.list()
        for (img in bootImages) if (img.name !in bootModes) bootModes[img.name] = BootController.mode(img)
    }
    fun bootRefresh() = bootRun("Loading…") { bootReload() }
    fun bootSetMode(img: BootLibrary.Image, mode: BootLibrary.Mode) { bootModes[img.name] = mode }
    fun bootAdd(uri: android.net.Uri) = bootRun("Adding…") {
        bootMessage = ""
        val err = BootController.add(ctx, uri) { d, t -> bootCopy = d to t }
        bootCopy = null
        bootMessage = err ?: "Added to the library."
        bootReload()
    }
    fun bootStart(img: BootLibrary.Image, withSaved: Boolean) = bootRun("Starting…") {
        bootMessage = ""; rememberUsbMode()
        val err = BootController.start(img, bootModes[img.name] ?: BootLibrary.Mode.DISK, withSaved)
        bootReload()
        bootMessage = err ?: "The PC now sees a USB drive. Restart it and pick the USB drive in its boot menu (often F12, F11, F8 or Esc)."
    }
    fun bootStop() = bootRun("Stopping…") { BootController.stop(); bootReload(); bootMessage = "The PC no longer sees the drive."; restoreUsbModeLater() }
    private suspend fun bootReadStatus() { bootStatus = BootController.status() }
    /** Home's status bar: just the drive state, without listing the library. */
    fun bootStatusRefresh() = viewModelScope.launch { if (bootBusy == null) bootReadStatus() }
    fun bootCreateSaved(img: BootLibrary.Image, gb: Int) = bootRun("Creating saved space…") {
        bootMessage = BootController.createSaved(img, gb) ?: "Saved space ready (${gb} GB). It's used when you boot with it switched on."
        bootReload()
    }
    fun bootDelete(path: String, what: String) = bootRun("Deleting…") {
        bootMessage = if (BootController.delete(path)) "$what deleted." else "Couldn't delete $what."
        bootReload()
    }

    // ---------- USB serial (Epic B4): a serial port to the PC; a login prompt there gives a shell without network ----------
    /** null = not checked yet; false = this kernel has no USB serial function. */
    var serialSupported by mutableStateOf<Boolean?>(null); private set
    var serialOn by mutableStateOf(false); private set
    var serialBusy by mutableStateOf(false); private set
    var serialMessage by mutableStateOf(""); private set
    var serialLines by mutableStateOf<List<String>>(emptyList()); private set
    private val serialTerm = com.rodinrescue.usb.TermText()
    /** The AI's commands over the serial login (set by [serialStartAi]); the terminal shows the same traffic. */
    private var serialAi: com.rodinrescue.transport.SerialTransport? = null
    var serialAiOn by mutableStateOf(false); private set
    private var serialLink: com.rodinrescue.usb.SerialLink? = null
    private var serialScreenOpen = false
    private var serialLeftAt = 0L

    private fun serialApply(out: String): String? {
        val l = out.lines().firstOrNull { it.startsWith("ACM_") }.orEmpty()
        serialSupported = l != "ACM_UNSUPPORTED"
        serialOn = l.startsWith("ACM_ON")
        return l.removePrefix("ACM_ON").trim().takeIf { serialOn && it.isNotEmpty() }
    }
    fun serialRefresh() = viewModelScope.launch {
        val tty = serialApply(RootShell.run(com.rodinrescue.usb.UsbSerial.statusScript(), 15, mountMaster = true).output)
        if (tty != null && serialLink?.isOpen != true) serialOpenLink(tty)
    }
    private fun serialOpenLink(tty: String) {
        serialLink?.close()
        serialLink = com.rodinrescue.usb.SerialLink(tty,
            onText = { t -> serialAi?.onText(t); viewModelScope.launch { serialTerm.feed(t); serialLines = serialTerm.lines } },
            onClosed = { viewModelScope.launch { if (serialOn) serialMessage = "The serial link closed. Turn it off and on again." } }
        ).also { it.open() }
    }
    fun serialToggle(on: Boolean) = viewModelScope.launch {
        if (serialBusy) return@launch
        serialBusy = true
        try {
            if (on) {
                rememberUsbMode()
                val tty = serialApply(RootShell.run(com.rodinrescue.usb.UsbSerial.enableScript(), 30, mountMaster = true).output)
                serialMessage = when {
                    tty != null -> { serialOpenLink(tty); "Serial port on. On a Linux PC it is /dev/ttyACM0. Press Enter to wake its login prompt." }
                    serialSupported == false -> "This phone's kernel has no USB serial function."
                    else -> "Couldn't turn the serial port on. Is the phone plugged into the PC?"
                }
            } else {
                serialAi = null; serialAiOn = false
                serialLink?.close(); serialLink = null
                serialApply(RootShell.run(com.rodinrescue.usb.UsbSerial.disableScript(), 30, mountMaster = true).output)
                serialMessage = "Serial port off."
                restoreUsbModeLater()
            }
        } finally { serialBusy = false }
    }
    /**
     * Hands the logged-in serial shell to the AI for the rescue chat. Refuses at a login or password prompt: the
     * first command would be typed in as a user name. Returns true when the shell answered.
     */
    suspend fun serialStartAi(): Boolean {
        val link = serialLink?.takeIf { serialOn && it.isOpen } ?: run { serialMessage = "Turn the serial port on first."; return false }
        val last = serialTerm.lines.lastOrNull { it.isNotBlank() }.orEmpty().trim().lowercase()
        if (last.endsWith("login:") || last.endsWith("password:")) { serialMessage = "Log in on the PC first (type your user name and password here), then try again."; return false }
        val t = serialAttach(link) ?: return false
        return try {
            serialAiOn = true; connState = "USB serial login (the AI's commands go to the PC's /dev/ttyACM0)"
            if (connected) closeConnection()   // one target at a time: the chat now talks to the serial PC
            newDiagnosis()
            serialMessage = "The AI uses this serial login now${if (t.rootUser) " (as root)" else ""}. Open Rescue chat."
            true
        } catch (e: Exception) { serialAi = null; serialAiOn = false; serialMessage = e.message ?: "No shell answered."; false }
    }
    /** Connects a transport to the shell on the serial login; null (and [serialMessage]) when no shell answered. */
    private suspend fun serialAttach(link: com.rodinrescue.usb.SerialLink): com.rodinrescue.transport.SerialTransport? {
        val t = com.rodinrescue.transport.SerialTransport({ link.write(it) }, { serialOn && link.isOpen })
        serialAi = t
        return try { t.connect(); t } catch (e: Exception) { serialAi = null; serialMessage = e.message ?: "No shell answered."; null }
    }

    /** Keyboard rescue's text mode (TextMode): the serial login the AI starts on the PC, with the user's yes. */
    private var kbTextOffered = false
    private val kbTextMode = object : com.rodinrescue.ai.TextMode {
        override val on get() = serialOn && serialAi?.isConnected == true
        override var offered: Boolean get() = kbTextOffered; set(v) { kbTextOffered = v }
        override suspend fun prepare(): String? {
            if (!serialOn) { serialToggle(true).join(); kotlinx.coroutines.delay(3_000) }   // the rebind re-plugs the keyboard too
            return if (serialOn && serialLink?.isOpen == true) null else serialMessage.ifBlank { "the phone couldn't add a serial port." }
        }
        override suspend fun connect(timeoutMs: Long): String? {
            val t0 = System.currentTimeMillis()
            val prompt = Regex("[$#]\\s*$")
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                val last = serialTerm.lines.lastOrNull { it.isNotBlank() }.orEmpty()
                if (prompt.containsMatchIn(last)) { serialLink?.let { l -> serialAttach(l)?.let { return null } } }
                kotlinx.coroutines.delay(1_000)
            }
            return "no shell appeared on the serial port within ${timeoutMs / 1000} s."
        }
        override suspend fun exec(command: String, timeoutMs: Long) = serialAi?.exec(command, timeoutMs)
            ?: com.rodinrescue.transport.ExecResult("", "text mode is off", null, cancelled = true)
        override suspend fun abandon() { serialAi = null; serialAiOn = false; if (serialOn) serialToggle(false).join() }
    }
    fun serialStopAi() { serialAi = null; serialAiOn = false; serialMessage = "The AI no longer uses the serial login." }

    /** Sends [text] as typed, then Enter (CR, what a terminal sends). */
    fun serialSend(text: String) { serialLink?.write(text + "\r") }
    fun serialRaw(s: String) { serialLink?.write(s) }
    fun serialClear() { serialTerm.clear(); serialLines = emptyList() }
    fun serialText() = serialTerm.text()
    fun serialScreenOpened() { serialScreenOpen = true }
    fun serialScreenClosed() { serialScreenOpen = false; serialLeftAt = System.currentTimeMillis() }
    init {
        // Like the keyboard (UsbAutoOff): a lingering serial port pushes file transfer off interface 0.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val inUse = serialAi != null && (running || kbScreensOpen > 0)
                if (serialOn && !inUse && !serialScreenOpen && serialLeftAt > 0 && System.currentTimeMillis() - serialLeftAt >= UsbAutoOff.LEAVE_GRACE_MS && !serialBusy) {
                    serialToggle(false); serialMessage = "The serial port turned itself off after you left its screen."
                }
            }
        }
    }

    // ---------- Storage & cleanup (Settings › Storage) ----------
    data class StorageReport(
        val phoneFree: Long, val phoneTotal: Long,
        val images: List<Pair<BootLibrary.Image, Long>>,        // image, real bytes used by its saved space (sparse)
        val conversations: List<Pair<com.rodinrescue.data.Conversation, com.rodinrescue.data.Storage.ConvSize>>,
        val auditBytes: Long, val cacheBytes: Long,
        val builtin: Map<String, Long>?                         // built-in Ubuntu: ubuntu / logins; null = still measuring
    )
    var storage by mutableStateOf<StorageReport?>(null); private set
    var storageBusy by mutableStateOf<String?>(null); private set
    var storageMessage by mutableStateOf(""); private set
    var autoDeleteDays by mutableStateOf(settings.autoDeleteDays); private set
    var serialLogin by mutableStateOf(settings.serialLogin); private set
    fun updateSerialLogin(v: Boolean) { serialLogin = v; settings.serialLogin = v }

    private fun storageRun(what: String, block: suspend () -> Unit) {
        if (storageBusy != null) return
        viewModelScope.launch { storageBusy = what; try { block() } finally { storageBusy = null } }
    }
    fun storageRefresh() = storageRun("Measuring…") { storageMeasure() }
    private suspend fun storageMeasure() {
        val st = android.os.StatFs(ctx.filesDir.absolutePath)
        val imgs = BootController.list()
        val saved = imgs.mapNotNull { i -> i.savedPath.takeIf { i.savedBytes != null } }
        val used = if (saved.isEmpty()) emptyMap() else com.rodinrescue.data.Storage.parseUsage(RootShell.run(com.rodinrescue.data.Storage.usageScript(saved), 60, mountMaster = true).output)
        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val convs = convStore.list().associateBy { it.id }
            val sizes = com.rodinrescue.data.Storage.conversations(ctx.filesDir).mapNotNull { sz -> convs[sz.id]?.let { it to sz } }.sortedByDescending { it.second.textBytes + it.second.photoBytes }
            Triple(sizes, com.rodinrescue.data.Storage.sizeOf(java.io.File(ctx.filesDir, "audit")), com.rodinrescue.data.Storage.sizeOf(ctx.cacheDir))
        }
        storage = StorageReport(st.availableBytes, st.totalBytes, imgs.map { it to (used[it.savedPath] ?: 0L) }, local.first, local.second, local.third, null)
        // The built-in Ubuntu has many small files: show the rest first.
        val bu = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rodinrescue.data.Storage.builtin(ubuntus.values.map { it.rootfs }) }
        storage = storage?.copy(builtin = bu)
    }
    private fun freed(b: Long) = when { b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble()); b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble()); else -> "%.0f KB".format(b / 1024.0) }

    fun storageDeletePhotos(id: String) = storageRun("Deleting…") {
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rodinrescue.data.Storage.deletePhotos(ctx.filesDir, id) }
        storageMessage = "Photos deleted (${freed(b)} freed). The conversation's text is kept."; conversationsVersion++; storageMeasure()
    }
    fun storageDeleteAllPhotos() = storageRun("Deleting…") {
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rodinrescue.data.Storage.conversations(ctx.filesDir).sumOf { com.rodinrescue.data.Storage.deletePhotos(ctx.filesDir, it.id) } }
        storageMessage = "All screen photos deleted (${freed(b)} freed)."; conversationsVersion++; storageMeasure()
    }
    fun storageDeleteConversation(id: String) = storageRun("Deleting…") { deleteConversation(id); storageMessage = "Conversation deleted."; storageMeasure() }
    fun storageClearCache() = storageRun("Clearing…") {
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { val n = com.rodinrescue.data.Storage.sizeOf(ctx.cacheDir); ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; n }
        storageMessage = "Cache cleared (${freed(b)})."; storageMeasure()
    }
    fun storageDeleteBoot(path: String, what: String) = storageRun("Deleting…") {
        if (bootStatus?.on == true && (bootStatus?.image == path || bootStatus?.writable == path)) { storageMessage = "Stop the boot drive first: the PC is using it."; return@storageRun }
        storageMessage = if (BootController.delete(path)) "$what deleted." else "Couldn't delete $what."; storageMeasure()
    }
    fun updateAutoDeleteDays(d: Int) = storageRun("Cleaning…") {
        autoDeleteDays = d; settings.autoDeleteDays = d
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.rodinrescue.data.Storage.prune(ctx.filesDir, d) }
        storageMessage = if (d == 0) "Photos and logs are kept." else "Photos and logs older than $d days are deleted" + if (b > 0) " (${freed(b)} freed now)." else "."
        if (b > 0) conversationsVersion++; storageMeasure()
    }
    fun storageRemoveBuiltin() = storageRun("Removing built-in Ubuntu…") {
        logins.values.forEach { it.cancel() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ubuntus.values.forEach { com.rodinrescue.runtime.RootfsArchive.deleteTree(it.home) } }
        builtinStatus.clear(); builtinRev++
        storageMessage = if (ubuntus.values.any { it.installed }) "Couldn't remove all of it." else "Built-in Ubuntu removed. To use a subscription brain again: AI brain › Install (you'll sign in again)."
        storageMeasure()
    }

    // ---------- devices ----------
    private val deviceStore: DeviceStore = ctx.devices
    private val convStore: ConversationStore = ctx.conversations
    var devices by mutableStateOf(deviceStore.all()); private set
    var activeDeviceId by mutableStateOf<String?>(null); private set
    private var conversationDevice: String? = null
    fun loadDevices() { devices = deviceStore.all() }
    /** Home's "Reconnect to last laptop" only makes sense while the phone still trusts a laptop it set up (removed/forgotten ones don't count). */
    val canReconnectLastLaptop get() = user.isNotBlank() && settings.knownLaptopFps().isNotEmpty()

    /** The device list is the source of truth: a laptop that isn't saved (removed, forgotten, pre-profile leftovers) isn't trusted. */
    private fun pruneOrphanTrust() {
        settings.pruneTrust(deviceStore.all().flatMap { it.fingerprints }.toSet())
        if (deviceStore.all().isEmpty() && settings.knownLaptopFps().isEmpty() && host.isBlank()) { settings.user = ""; user = "" }
    }
    fun activeDevice() = activeDeviceId?.let { deviceStore.get(it) }
    fun device(id: String) = deviceStore.get(id)
    fun renameDevice(id: String, name: String) { deviceStore.get(id)?.let { deviceStore.save(it.copy(name = name.trim().take(40).ifBlank { it.name })) }; loadDevices() }
    fun isConnectedTo(id: String) = connected && activeDeviceId == id

    /** After a successful connect: find or create the profile for this laptop and mark it active. */
    private fun rememberDevice(hostname: String, p: Int) {
        val fps = settings.pinnedHostKey("${host.trim()}:$p")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val now = System.currentTimeMillis()
        val d = (activeDeviceId?.let { deviceStore.get(it) }?.takeIf { a -> fps.isEmpty() || a.fingerprints.any { it in fps } }
            ?: deviceStore.find(fps, user.trim(), hostname)
            ?: DeviceProfile(DeviceStore.newId(), hostname.ifBlank { host.trim() }, user.trim(), host.trim(), p, hostname, fps, now, now))
            .let { it.copy(user = user.trim(), lastIp = host.trim(), port = p, hostname = hostname.ifBlank { it.hostname }, fingerprints = (it.fingerprints + fps).distinct(), lastUsed = now, osInfo = targetInfo) }
        deviceStore.save(d); activeDeviceId = d.id; loadDevices()
    }

    /** Connect to a saved device: last address first, then a scan of the USB link if the laptop got a new address. */
    fun connectDevice(id: String) = viewModelScope.launch {
        val d = deviceStore.get(id) ?: return@launch
        if (connected && activeDeviceId != id) closeConnection()
        activeDeviceId = id
        user = d.user; port = d.port.toString(); useKey = true
        sshKey = withContext(Dispatchers.Default) { keys.identity().privatePem }
        if (d.fingerprints.isNotEmpty()) settings.addKnownLaptopFps(d.fingerprints)
        host = d.lastIp; connState = "Connecting to ${d.name}…"
        if (connectNow()) return@launch
        if (rootOk == true) lastIface?.let { NetFix.apply(it) }
        usbIfaces = UsbNet.detect(); val i = lastIface ?: run { connState = "${d.name}: not reachable at ${d.lastIp} and no USB link"; return@launch }
        scanning = true; val cands = usbScan(i, d.port); scanning = false
        for (c in cands) { host = c.ip; if (connectNow()) return@launch }
        connState = "${d.name}: not found on the USB link. Is the cable plugged in and the laptop on?"
    }

    // ---------- remove from laptop ----------
    var removing by mutableStateOf(false); private set
    var removalReport by mutableStateOf<RemovalReport?>(null)
    data class RemovalReport(val deviceName: String, val lines: List<Pair<Boolean, String>>, val manualCommand: String?)

    /** Device whose "Remove" dialog is open (asks for the sudo password, shows errors, keeps the profile on failure). */
    var removePrompt by mutableStateOf<DeviceProfile?>(null); private set
    var removeError by mutableStateOf<String?>(null); private set
    fun askRemove(d: DeviceProfile) { removeError = null; removePrompt = d }
    fun dismissRemove() { if (!removing) { removePrompt = null; removeError = null } }

    /**
     * Undo Lifeline's setup on the connected laptop, then forget it on the phone.
     * Nothing is changed until sudo is VERIFIED: a missing or wrong password leaves the laptop and the profile untouched
     * and the dialog open with the error. Order: root part (ufw + SSH server state recorded at setup; the SSH stop is
     * deferred so this session can finish), then the phone's key, then forget locally.
     * [keyOnly] = the user explicitly chose to skip the admin part.
     */
    fun removeFromLaptop(d: DeviceProfile, password: String, keyOnly: Boolean = false) {
        if (removing) return
        if (transport == null || !isConnectedTo(d.id)) { removePrompt = null; forgetDevice(d, showManual = true); return }
        viewModelScope.launch {
            removing = true; removeError = null; stopAll(); closeShell()
            try {
                val t = ensureConnected() ?: run { removeError = "Lost the connection to ${d.name}. Check the cable and try again — nothing was changed."; return@launch }
                val pub = withContext(Dispatchers.Default) { keys.identity().publicLine }
                val pw = password.ifBlank { null }
                val out = mutableListOf<Pair<Boolean, String>>()
                if (!keyOnly) {
                    val check = if (pw != null) t.exec("sudo -S -p '' true", 15_000, stdin = pw + "\n") else t.exec("sudo -n true", 15_000)
                    if (check.exitCode != 0) {
                        removeError = if (pw == null) "This laptop needs your sudo password to undo the firewall and SSH server changes. Enter it above — nothing was changed."
                                      else "That sudo password was not accepted. Nothing was changed; try again."
                        return@launch
                    }
                    if (pw != null) sudoPassword = pw   // memory only, as on Home
                    val rootCmd = com.rodinrescue.setup.LaptopCleanup.rootScript()
                    val r = if (pw != null) t.exec(com.rodinrescue.security.CommandPolicy.sudoWrap(rootCmd), 30_000, stdin = pw + "\n")
                            else t.exec("sudo -n sh -c " + com.rodinrescue.security.CommandPolicy.shellQuote(rootCmd), 30_000)
                    val res = com.rodinrescue.setup.LaptopCleanup.parse("", r.stdout)
                    if (!res.done) { removeError = "The admin part failed on the laptop: ${(r.stderr.ifBlank { r.stdout }).take(200)}. Nothing else was changed."; return@launch }
                    res.ufwRemoved.forEach { out += true to "Firewall rule for SSH on $it removed" }
                    if (!res.stateFound) {
                        if (res.ufwRemoved.isEmpty()) out += true to "No Lifeline firewall rule found"
                        out += true to "Set up by an older version (no record), so the SSH server was left as it is"
                    } else out += if (res.sshdDisabled) true to (if (res.sshdStopping) "SSH server disabled and stopping (it was off before Lifeline)" else "SSH server will no longer start at boot")
                                  else true to "SSH server left running: it was already on before Lifeline"
                } else out += false to "Admin part skipped (you chose key only): firewall and SSH server were not changed"
                val u = t.exec(com.rodinrescue.setup.LaptopCleanup.userScript(pub), 20_000)
                val ures = com.rodinrescue.setup.LaptopCleanup.parse(u.stdout, "")
                if (!ures.keyRemoved && !ures.keyWasAbsent) { removeError = "Could not edit ~/.ssh/authorized_keys: ${u.stderr.take(200)}"; return@launch }
                out.add(0, true to if (ures.keyRemoved) "Phone's key removed from ~/.ssh/authorized_keys" else "Phone's key was not in authorized_keys")
                closeConnection()
                forgetLocally(d)
                out += true to "“${d.name}” forgotten on this phone. Its conversations are kept."
                removePrompt = null
                removalReport = RemovalReport(d.name, out, null)
            } catch (e: Exception) {
                removeError = "Interrupted: ${e.message}. Check the cable and try again."
            } finally { removing = false }
        }
    }

    /** Forget a device on the phone only; optionally show the command that undoes the laptop side by hand. */
    fun forgetDevice(d: DeviceProfile, showManual: Boolean) {
        if (isConnectedTo(d.id)) closeConnection()
        forgetLocally(d)
        if (showManual) viewModelScope.launch {
            val pub = withContext(Dispatchers.Default) { keys.identity().publicLine }
            removalReport = RemovalReport(d.name, listOf(true to "“${d.name}” forgotten on this phone.",
                false to "The laptop still trusts this phone until you run the command below on it (or reconnect and use Remove)."),
                com.rodinrescue.setup.LaptopCleanup.manualCommand(pub))
        }
    }

    private fun forgetLocally(d: DeviceProfile) {
        settings.forgetHostKeysWith(d.fingerprints); settings.removeKnownLaptopFps(d.fingerprints)
        // The legacy "last laptop" (Home's Reconnect card) is this device: forget it too, with its stale setup log.
        if (settings.user == d.user || host.trim() == d.lastIp) {
            settings.host = ""; settings.user = ""; host = ""; user = ""; sshKey = ""; sshPassword = ""; saveConnection()
        }
        targetInfo = ""; setupLog.clear(); cancelLaptopWait()
        convStore.reassign(d.id, null)
        deviceStore.delete(d.id); if (activeDeviceId == d.id) activeDeviceId = null
        loadDevices(); pruneOrphanTrust()
    }

    /**
     * "Run changes without asking": the AI's changes run without the approval card. Kept in memory
     * only, so it is off again after the app restarts and can't be left on by accident for the next rescue.
     * Forbidden commands stay blocked (checked before any approval); destructive ones (delete, wipe, remove
     * packages) still ask unless [autoApproveDestructive]. Every auto-approval is in the chat and the audit log.
     */
    var autoApprove by mutableStateOf(false); private set
    var autoApproveDestructive by mutableStateOf(false); private set
    fun updateAutoApprove(v: Boolean) { autoApprove = v; if (!v) autoApproveDestructive = false }
    fun updateAutoApproveDestructive(v: Boolean) { autoApproveDestructive = v && autoApprove }
    private val autoApproved = mutableSetOf<String>()

    var autoCleanup by mutableStateOf(settings.autoCleanup); private set
    fun updateAutoCleanup(v: Boolean) { autoCleanup = v; settings.autoCleanup = v }
    var appLock by mutableStateOf(settings.appLock); private set
    fun updateAppLock(v: Boolean) { appLock = v; settings.appLock = v }

    // ---------- conversations ----------
    var conversationsVersion by mutableStateOf(0); private set
    fun conversations(deviceId: String?) = convStore.list().filter { it.deviceId == deviceId }
    fun allConversations() = convStore.list()

    private fun persistConversation() {
        if (chat.isEmpty()) return
        val id = audit.sessionId
        val now = System.currentTimeMillis()
        val old = convStore.load(id)
        convStore.save(Conversation(id, old?.deviceId ?: conversationDevice ?: activeDeviceId, ConversationStore.titleFrom(chat), old?.createdAt ?: now, now,
            chat.toList(), history.toList(), usage, if (kbMode) "keyboard" else "ssh"))
        conversationsVersion++
    }

    /** Load a saved conversation into the chat; the model gets the full earlier history so it can carry on. */
    fun resumeConversation(id: String): Boolean {
        val c = convStore.load(id) ?: return false
        stopAll(); chat.clear(); chat.addAll(c.chat); history.clear(); history.addAll(c.history); usage = c.usage; kbMode = c.mode == "keyboard"; attachedPhoto = null
        audit.resume(c.id); conversationDevice = c.deviceId; diagnosticStatus = "Resumed"
        if (!kbMode && c.deviceId != null && !isConnectedTo(c.deviceId)) chat.add(ChatItem.Notice("Resumed. Connect to ${deviceStore.get(c.deviceId)?.name ?: "this computer"} before sending, so commands run on the right machine."))
        return true
    }
    fun conversationText(id: String) = convStore.load(id)?.let { ConversationStore.transcript(it, it.deviceId?.let { d -> deviceStore.get(d)?.name }) } ?: ""
    fun deleteConversation(id: String) { convStore.delete(id); audit.delete(id); if (audit.sessionId == id) newDiagnosis(); conversationsVersion++ }

    override fun onCleared() { transport?.close(); cliSessions.values.forEach { it.close() }; super.onCleared() }
}
