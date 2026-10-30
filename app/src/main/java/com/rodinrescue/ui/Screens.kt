package com.rodinrescue.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rodinrescue.ChatItem
import com.rodinrescue.RodinViewModel
import com.rodinrescue.security.Risk

internal fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("lifeline", text))
}
internal fun share(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Export"))
}

private fun aiReady(vm: RodinViewModel) = when (vm.provider) {
    // Built-in CLIs: installed, and not known to be signed out (the status is read when AI brain opens).
    "claude", "codex", "geminicli" -> com.rodinrescue.runtime.Ubuntu.Cli.of(vm.provider)!!.let { vm.ubuntus.getValue(it).ready() && vm.builtinStatus[it]?.subscription != false }
    "openai" -> vm.openaiKey.isNotBlank(); "gemini" -> vm.geminiKey.isNotBlank(); else -> vm.deepseekKey.isNotBlank()
}

private fun providerShort(vm: RodinViewModel) = when (vm.provider) {
    "claude" -> "Claude"; "codex" -> "ChatGPT"; "geminicli" -> "Gemini"; "openai" -> "OpenAI"; "gemini" -> "Gemini API"; else -> "DeepSeek"
}

/** Scrollable screen body with Frost padding, leaving room for the floating nav bar. */
@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp + LocalNavSpace.current),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

// ───────────────────────────── Home ─────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: RodinViewModel, openKbRescue: () -> Unit, openVoice: () -> Unit, openSerial: () -> Unit, openBoot: () -> Unit, openChat: () -> Unit, openAi: () -> Unit, openSettings: () -> Unit, openDevices: () -> Unit) {
    val ctx = LocalContext.current
    var support by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.checkRootStep(); vm.kbRefresh(); vm.bootStatusRefresh(); vm.rescueResume(); while (true) { vm.refreshSetup(); kotlinx.coroutines.delay(3000) } }
    val ai = aiReady(vm)
    // Pull down to re-check root, the USB link, the VPN rule, saved devices and whether the SSH link is alive.
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(vm.refreshing, { vm.refreshAll() }, Modifier.fillMaxSize(), state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(pull, vm.refreshing, Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = Frost.Card, color = Frost.Ink)
        }) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LifelineMark(30.dp)
            Text(Brand.NAME, Modifier.weight(1f), fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.4).sp)
            CoffeeButton({ support = true })
            CircleIconButton(Icons.Outlined.Settings, "Settings", openSettings)
        }
        if (support) SupportDialog { support = false }

        LiquidHero(height = 210.dp) {
            GlassBadge(
                if (vm.connected) (vm.targetInfo.ifBlank { vm.host }.lines().first().take(34) + " linked") else "Not connected",
                if (vm.connected) Color(0xFF4ADE80) else Frost.Warn)
            Text(if (vm.connected) "Bring it back\nto life." else "Plug in.\nWe'll do the rest.", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            when {
                vm.connected && ai -> ChromeButton("Start rescue", { vm.newDiagnosis(); openChat() }, Modifier.fillMaxWidth())
                vm.connected -> ChromeButton("Set up an AI brain first", openAi, Modifier.fillMaxWidth())
                else -> ChromeButton(if (vm.setupBusy) "Working…" else "Set up everything for SSH", { vm.oneClickSetup() }, Modifier.fillMaxWidth(), enabled = !vm.setupBusy)
            }
        }

        // What SSH rescue needs, as one row. The ways in further down need none of the cable/VPN items.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(if (vm.rootOk == false) "Allow root" else vm.rootManager.takeIf { vm.rootOk == true && it != "your root manager" } ?: "Root", vm.rootOk)
            StatusPill(if (vm.tetherOk) "USB link" else "No cable", vm.tetherOk)
            if (vm.vpnActive) StatusPill(if (vm.bypassOk) "VPN routed" else "VPN", vm.bypassOk)
            StatusPill(if (ai) providerShort(vm) else "Set up AI", ai, onClick = openAi)
        }
        if (vm.connected) Muted(vm.connState + "  ·  ${vm.diagnosticStatus}")
        else if (vm.connState.isNotBlank() && vm.connState != "Disconnected") Muted(vm.connState)

        // Lifeline's USB extras are visible while on, with a one-tap off (they must never linger unnoticed).
        if (vm.kbState == com.rodinrescue.usb.HidController.State.ON) UsbOnBar("USB keyboard is on", "Turns itself off when not in use", "Turn off") { vm.kbToggle(false) }
        if (vm.bootStatus?.on == true) UsbOnBar("Boot drive is on", "The PC sees the phone as a USB drive", "Stop") { vm.bootStop() }
        if (vm.serialOn) UsbOnBar("USB serial is on", "The PC sees a serial port", "Turn off") { vm.serialToggle(false) }

        // The setup card is below the hero: follow it as it runs (the latest step, an error, or the command to paste),
        // so a first-time user sees it working instead of pressing the button again.
        val setupStep = remember { BringIntoViewRequester() }
        var stepHeight by remember { mutableIntStateOf(0) }
        val navSpacePx = with(androidx.compose.ui.platform.LocalDensity.current) { (LocalNavSpace.current + 12.dp).toPx() }
        LaunchedEffect(vm.setupLog.size, vm.setupLog.lastOrNull(), vm.laptopCommand) {
            // Its height plus the floating nav bar's, so the line ends up above the bar, not under it.
            if (!vm.connected && (vm.setupLog.isNotEmpty() || vm.laptopCommand != null)) {
                kotlinx.coroutines.delay(80); setupStep.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, stepHeight + navSpacePx))
            }
        }
        val follow = Modifier.bringIntoViewRequester(setupStep).onSizeChanged { stepHeight = it.height }
        if (!vm.connected && (vm.setupLog.isNotEmpty() || vm.laptopCommand != null)) FrostCard {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            vm.setupLog.forEachIndexed { i, line ->
                Muted(line, if (vm.laptopCommand == null && i == vm.setupLog.lastIndex) follow else Modifier,
                    color = if (line.startsWith("✗")) Frost.Bad else if (line.startsWith("✓")) Frost.GoodInk else Frost.Slate)
            }
            vm.laptopCommand?.let { cmd -> Column(follow, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentProgress(3, 2)
                Text("On the laptop, open a terminal (Ctrl+Alt+F3 works too) and paste:", style = MaterialTheme.typography.titleSmall)
                TerminalBlock { Mono(cmd) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    InkButton("Copy", { copy(ctx, cmd) }, icon = Icons.Outlined.ContentCopy)
                    FrostOutlineButton("Copy (wget)", { copy(ctx, cmd.replace("curl -fsSL", "wget -qO-")) })
                    GhostButton("Cancel", { vm.cancelLaptopWait() })
                }
                StepRow(null, "Waiting for the laptop…", "It turns on SSH if needed, authorizes this phone and reports back.", inProgress = true)
                FrostSwitch(vm.serialLogin, { vm.updateSerialLogin(it) }, "Also allow login over USB serial")
                Muted("A login prompt on this phone's USB serial port, for when the PC's network breaks later. Set it before pasting.", color = Frost.Muted)
            } }
        }

        if (vm.connected) FrostOutlineButton(if (vm.removing) "Removing access…" else if (vm.autoCleanup) "Disconnect & remove access" else "Disconnect", { vm.disconnect() }, Modifier.fillMaxWidth(), enabled = !vm.removing)
        else if (vm.devices.isNotEmpty()) FrostCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                GhostButton("All", openDevices)
            }
            vm.devices.take(3).forEach { d ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Field).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1); Muted("${d.user}@${d.lastIp}", maxLines = 1) }
                    InkButton(if (vm.scanning && vm.activeDeviceId == d.id) "…" else "Connect", { vm.connectDevice(d.id) }, enabled = !vm.scanning)
                }
            }
        }
        else if (vm.canReconnectLastLaptop) FrostCard {
            Text("Set up before?", style = MaterialTheme.typography.titleMedium)
            Muted("After re-plugging, the laptop usually gets a new address. This finds it again.")
            InkButton(if (vm.scanning) "Scanning…" else "Reconnect to last laptop", { vm.reconnectKnownLaptop() }, Modifier.fillMaxWidth(), enabled = vm.user.isNotBlank() && !vm.scanning)
        }

        // When SSH isn't possible. Keyboard ones need the USB keyboard function: hidden on kernels without it.
        val kbPossible = vm.kbState != com.rodinrescue.usb.HidController.State.UNSUPPORTED
        SectionLabel("Other ways in", Modifier.padding(top = 4.dp))
        val ways = buildList<@Composable (Modifier) -> Unit> {
            if (kbPossible) add { m -> WayTile(Icons.Outlined.CameraAlt, "Keyboard rescue", "PC stuck before the OS starts", openKbRescue, m) }
            if (kbPossible) add { m -> WayTile(Icons.Outlined.Keyboard, "Type on PC", "Your phone as its keyboard", openVoice, m) }
            if (vm.serialSupported != false) add { m -> WayTile(Icons.Outlined.Cable, "USB serial", "Log in without network", openSerial, m) }
            add { m -> WayTile(Icons.Outlined.Album, "Boot drive", "Start the PC from the phone", openBoot, m) }
        }
        ways.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    }
}

/** One of Home's "other ways in": icon, name and a few words, the whole tile tappable. */
@Composable
private fun WayTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Frost.Card).border(1.dp, Frost.Line, RoundedCornerShape(20.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(14.dp).heightIn(min = 84.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Frost.Ink), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = Color.White)
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Muted(hint, maxLines = 2)
    }

// ───────────────────────────── Rescue chat ─────────────────────────────
@Composable
fun ChatScreen(vm: RodinViewModel, openKbRescue: () -> Unit = {}, openTerminal: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.streaming) { if (vm.chat.isNotEmpty()) state.animateScrollToItem(vm.chat.size) }
    Column(Modifier.fillMaxSize().statusBarsPadding().aboveKeyboard()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetalOrb(38.dp, active = vm.running)
            Column(Modifier.weight(1f)) {
                Text(vm.targetInfo.ifBlank { "Rescue session" }.lines().first().trimEnd(' ', '·'), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${vm.diagnosticStatus} · ${providerShort(vm)}", maxLines = 1)
            }
            AutoApproveChip(vm)
            if (vm.running) StopButton("Stop", { vm.stopAll() })
            // Terminal moved here from the bottom bar (2026-10-15): a raw shell on the connected PC.
            else CircleIconButton(Icons.Outlined.Terminal, "Terminal", openTerminal)
        }
        LazyColumn(Modifier.weight(1f).fadeTop().padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            if (vm.chat.isEmpty()) item {
                FrostCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetalOrb(30.dp, false); Text("Tell it what you see", style = MaterialTheme.typography.titleSmall) }
                    Muted("e.g. “Black screen after the update, only the cursor shows.” It investigates read-only first and asks before it changes anything.")
                }
            }
            items(vm.chat) { ChatRow(it, vm::photoFile) }
            if (vm.streaming.isNotEmpty()) item { AssistantCard(vm.streaming, thinking = true) }
            if (vm.running && vm.streaming.isEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetalOrb(22.dp); Muted("Working…") } }
        }
        if (vm.kbMode) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Continue in keyboard rescue", openKbRescue, Modifier.weight(1f))
            FrostOutlineButton("New", { vm.newDiagnosis() })
        }
        if (!vm.kbMode) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Tell it what you see…", color = Frost.Muted) }, maxLines = 4,
                shape = Pill, textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Frost.Card, unfocusedContainerColor = Frost.Card,
                    focusedBorderColor = Frost.Ink, unfocusedBorderColor = Frost.Line2, cursorColor = Frost.Ink))
            val canSend = !vm.running && input.isNotBlank()
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (canSend) Frost.Ink else Frost.Line2)
                .clickable(enabled = canSend, role = Role.Button, onClickLabel = "Send") { vm.send(input); input = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Send", tint = if (canSend) Color.White else Frost.Muted)
            }
        }
    }
}

@Composable
internal fun AssistantCard(text: String, thinking: Boolean = false) = FrostCard(padding = 16.dp) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetalOrb(26.dp, active = thinking)
        Text(Brand.NAME, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (!thinking) IconButton(onClick = { copy(ctx, text); android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show() }, Modifier.size(36.dp)) {
            Icon(Icons.Outlined.ContentCopy, "Copy reply", Modifier.size(18.dp), tint = Frost.Slate)
        }
    }
    // Long-press to select any part of the reply.
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ChatRow(item: ChatItem, photo: (String) -> java.io.File = { java.io.File(it) }) {
    val ctx = LocalContext.current
    when (item) {
        is ChatItem.UserMsg -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.photo?.let { PhotoThumb(file = photo(it), modifier = Modifier.fillMaxWidth(0.6f), height = 110.dp) }
            SelectionContainer { Text(item.text, Modifier.fillMaxWidth(0.82f).wrapContentWidth(Alignment.End).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(Frost.Ink)
                .padding(horizontal = 16.dp, vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        }
        is ChatItem.AssistantMsg -> AssistantCard(item.text)
        // One readable line (what the AI says it's doing); tap for the tool and its raw arguments.
        is ChatItem.ToolCallItem -> {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Frost.Pewter))
                    Muted(toolLine(item.name, item.args), Modifier.weight(1f), maxLines = if (open) Int.MAX_VALUE else 2)
                }
                if (open) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tag(item.name); if (item.args.isNotBlank() && item.args != "{}") Muted(item.args, Modifier.weight(1f), color = Frost.Muted) }
            }
        }
        is ChatItem.Notice -> Muted(item.text, color = if (item.error) Frost.Bad else Frost.Slate)
        is ChatItem.KeysItem -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("⌨", if (item.error == null) Tone.Ink else Tone.Bad)
            Mono(item.label + (item.error?.let { " — $it" } ?: ""), Modifier.weight(1f), color = if (item.error == null) Frost.Ink else Frost.Bad, size = 12)
        }
        is ChatItem.ScreenItem -> if (item.photo != null) PhotoThumb(file = photo(item.photo), height = 140.dp, label = "What the AI saw")
        else {
            var open by remember { mutableStateOf(false) }
            TerminalBlock(Modifier.clickable { open = !open }) {
                Mono("◉ screen (text, older version)", color = Frost.OnInkMuted, size = 11)
                val t = item.text.ifBlank { "(no readable text)" }
                Mono(if (open) t else t.lines().take(6).joinToString("\n") + if (t.lines().size > 6) "\n…" else "", color = Color.White)
            }
        }
        is ChatItem.Cmd -> {
            var open by remember { mutableStateOf(false) }
            val r = item.result
            val body = r.stdout + (if (r.stderr.isNotBlank()) "\n[stderr]\n${r.stderr}" else "")
            TerminalBlock(Modifier.clickable { open = !open }) {
                Mono("${if (item.privileged) "# sudo " else "$ "}${item.command}", color = Color.White)
                Mono("exit=${r.exitCode ?: if (r.timedOut) "timeout" else "cancelled"}  ${r.durationMs}ms${if (r.truncated) "  [truncated]" else ""}",
                    color = if (r.ok) Color(0xFF86EFAC) else Frost.BadOnInk, size = 11)
                Mono(if (open) body else body.lines().take(6).joinToString("\n") + if (body.lines().size > 6) "\n… (tap to expand)" else "")
                if (open) TextButton(onClick = { copy(ctx, body) }) { Text("Copy output", color = Frost.OnInkMuted) }
            }
        }
        is ChatItem.ApprovalItem -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(if (item.auto) "Auto-approved" else if (item.approved) "Approved" else "Rejected", if (item.auto) Tone.Warn else if (item.approved) Tone.Good else Tone.Bad)
            Mono(item.req.command.take(160), Modifier.weight(1f), color = Frost.Slate, size = 11)
        }
    }
}

/** What a tool call is for, in words: the AI's own "purpose" when it gave one, otherwise the tool's job. */
internal fun toolLine(name: String, args: String): String {
    Regex("\"purpose\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(args)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?.let { return it.replace("\\\"", "\"").replaceFirstChar { c -> c.uppercase() } }
    return when (name) {
        "get_system_information" -> "Reading system information"
        "check_systemd_services" -> "Checking services"
        "inspect_display_manager" -> "Checking the login screen"
        "read_system_logs" -> "Reading the system logs"
        "run_readonly_command" -> "Looking around"
        "check_disk_space" -> "Checking disk space"
        "check_graphics_drivers" -> "Checking the graphics drivers"
        "inspect_boot_errors" -> "Reading boot errors"
        "request_privileged_command" -> "Asking to run a command as admin"
        "suggest_repair" -> "Proposing a repair"
        "apply_approved_repair" -> "Applying the approved repair"
        "verify_repair" -> "Checking that the fix worked"
        "look_at_screen", "wait_for_screen" -> "Looking at the screen"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** While "Run changes without asking" is on: a small "Auto" pill in the chat header; tap it for what it means and Turn off. */
@Composable
fun AutoApproveChip(vm: RodinViewModel) {
    if (!vm.autoApprove) return
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(RoundedCornerShape(50)).clickable { open = true }) { Badge("Auto", Tone.Warn, dot = true) }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Frost.Card) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (vm.autoApproveDestructive) "The AI's changes run without asking, deletions too." else "The AI's changes run without asking. Deletions still ask.",
                    style = MaterialTheme.typography.bodyMedium)
                InkButton("Turn off", { vm.updateAutoApprove(false); open = false }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Approval as an ink "Proposed fix" sheet, as in the Frost mockup. */
@Composable
fun ApprovalDialog(vm: RodinViewModel) {
    val req = vm.pendingApproval ?: return
    var typed by remember(req.id) { mutableStateOf("") }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        FrostCard(Modifier.padding(16.dp).heightIn(max = 640.dp), dark = true, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetalOrb(30.dp)
                Text(req.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(when (req.risk) { Risk.FORBIDDEN -> "Normally never allowed"; Risk.DESTRUCTIVE -> "Destructive"; else -> "Needs approval" }, if (req.risk >= Risk.DESTRUCTIVE) Tone.Bad else Tone.Warn)
                Badge(when (req.reversible) { true -> "Reversible"; false -> "NOT reversible"; null -> "Reversible: unknown" }, if (req.reversible == false) Tone.Bad else Tone.Chrome)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DarkSection("Why", req.reason)
                // What happens comes before the photo and the command: on a phone the bottom of the card is below the fold.
                DarkSection("Possible consequences", req.consequences)
                if (req.evidence.isNotBlank()) DarkSection("Evidence", req.evidence)
                if (req.id.startsWith("k") || req.id.startsWith("t")) vm.lastSeenPhoto?.let { PhotoThumb(bytes = it, height = 150.dp, label = "Last photo") }
                Text(when { req.id.startsWith("k") -> "Sent to the PC as keystrokes (USB keyboard)"; req.id.startsWith("t") -> "Runs on the PC over USB serial (text mode)"; else -> "Command (runs as root via sudo)" },
                    style = MaterialTheme.typography.labelMedium, color = Frost.OnInkMuted)
                Mono(req.command, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Frost.Ink2).padding(12.dp), color = Color(0xFFE3E7EB))
                // Asked here, where it's needed, instead of on Home. Memory only; never sent to the AI.
                if (!req.id.startsWith("k") && !req.id.startsWith("t") && vm.user.trim() != "root") {
                    OutlinedTextField(vm.sudoPassword, { vm.sudoPassword = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("PC password, if sudo asks (not saved, never sent to the AI)", color = Frost.OnInkMuted) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF3A3F46), cursorColor = Color.White))
                }
                if (req.needsTypedConfirm) {
                    Text(if (req.risk == Risk.FORBIDDEN) "Lifeline normally never does this. Only if YOU asked for exactly this, type OVERRIDE to enable approval."
                         else "This is destructive or irreversible. Type DELETE to enable approval.", color = Frost.BadOnInk, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF3A3F46), cursorColor = Color.White))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FrostOutlineButton("Reject", { vm.answerApproval(false) }, Modifier.weight(1f), onDark = true)
                ChromeButton(if (req.id.startsWith("k")) "Approve & send" else "Approve & run", { vm.answerApproval(true) }, Modifier.weight(1f), enabled = !req.needsTypedConfirm || typed == req.confirmWord)
            }
        }
    }
}

@Composable
private fun DarkSection(label: String, body: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Frost.OnInkMuted)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE3E7EB))
}

@Composable
fun HostKeyDialog(vm: RodinViewModel) {
    val hk = vm.pendingHostKey ?: return
    AlertDialog(
        onDismissRequest = { vm.rejectPendingHostKey() }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Verify SSH host key", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Server ${hk.first} presented:", style = MaterialTheme.typography.bodyMedium)
                TerminalBlock { Mono(hk.second) }
                Muted("Compare with the laptop:\nssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub\nTrust only if they match.")
            }
        },
        confirmButton = { InkButton("Trust & connect", { vm.trustPendingHostKey() }) },
        dismissButton = { FrostOutlineButton("Reject", { vm.rejectPendingHostKey() }) }
    )
}

// ───────────────────────────── Terminal ─────────────────────────────
@Composable
fun TerminalScreen(vm: RodinViewModel) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var histIdx by remember { mutableIntStateOf(-1) }
    val state = rememberLazyListState()
    LaunchedEffect(vm.termLines.size) { if (vm.termLines.isNotEmpty()) state.scrollToItem(vm.termLines.lastIndex) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp).aboveKeyboard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Terminal", if (vm.shellOpen) "Shell open" else if (vm.connected) "Connected — open a shell" else "Not connected")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            InkButton(if (vm.shellOpen) "Shell open" else "Open shell", { vm.openShell() }, enabled = vm.connected && !vm.shellOpen)
            FrostOutlineButton("Close", { vm.closeShell() }, enabled = vm.shellOpen)
            FrostOutlineButton("Ctrl+C", { vm.sendShellRaw("\u0003") }, enabled = vm.shellOpen)
            FrostOutlineButton("Copy", { copy(ctx, vm.terminalText()) })
            FrostOutlineButton("Export", { share(ctx, vm.terminalText()) })
            GhostButton("Clear", { vm.termLines.clear() })
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Frost.Ink).padding(14.dp)) {
            LazyColumn(state = state) { items(vm.termLines.size) { Mono(vm.termLines[it]) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostField("", input, { input = it }, Modifier.weight(1f), mono = true, placeholder = "command")
            CircleIconButton(Icons.Outlined.KeyboardArrowUp, "Previous command", {
                if (vm.cmdHistory.isNotEmpty()) { histIdx = (histIdx + 1).coerceAtMost(vm.cmdHistory.lastIndex); input = vm.cmdHistory[histIdx] }
            })
            InkButton("Run", { vm.sendShell(input); input = ""; histIdx = -1 }, enabled = vm.shellOpen)
        }
        Muted("Your own terminal: commands here skip Lifeline's safety checks. The AI never uses it.", Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun UsbOnBar(title: String, sub: String, action: String, onAction: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Ink).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4ADE80)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Muted(sub, color = Frost.OnInkMuted, maxLines = 1)
        }
        ChromeButton(action, onAction)
    }

/** Drive the PC's keyboard yourself (Type on PC › Keys): keys to tap while it powers on, single keys and combos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualKeys(vm: RodinViewModel) {
    var confirmReboot by remember { mutableStateOf(false) }
    val on = vm.kbState == com.rodinrescue.usb.HidController.State.ON
    val ready = on && !vm.kbBusy
    SectionLabel("Tap repeatedly while the PC powers on")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, hint) in listOf("F2" to "setup", "del" to "setup", "F12" to "boot menu", "esc" to "menu", "F10" to "HP", "F9" to "HP boot")) {
            val active = vm.kbSpamming == k
            KeyChip(if (active) "Stop ${k.uppercase()}" else "${k.replaceFirstChar { it.uppercase() }} · $hint", on, active) { vm.kbSpam(k) }
        }
    }
    SectionLabel("Keys")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in listOf("esc", "F1", "F2", "F5", "F7", "F8", "F10", "F11", "F12", "tab", "enter", "space", "backspace", "del", "up", "down", "left", "right", "pgup", "pgdn", "home", "end"))
            KeyChip(KEY_LABELS[k] ?: k.replaceFirstChar { it.uppercase() }, ready) { vm.kbPress(k) }
    }
    SectionLabel("Combos")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyChip("Ctrl+Alt+F3 · text login", ready) { vm.kbPress("ctrl+alt+f3") }
        KeyChip("Ctrl+Alt+F1", ready) { vm.kbPress("ctrl+alt+f1") }
        KeyChip("Ctrl+C", ready) { vm.kbPress("ctrl+c") }
        KeyChip("Shift+Tab", ready) { vm.kbPress("shift+tab") }
        KeyChip("Ctrl+Alt+Del · reboot", ready) { confirmReboot = true }
    }
    if (vm.kbMessage.isNotBlank()) Muted(vm.kbMessage, color = if (vm.kbState == com.rodinrescue.usb.HidController.State.ERROR) Frost.Bad else Frost.Slate)
    if (confirmReboot) AlertDialog(onDismissRequest = { confirmReboot = false }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Send Ctrl+Alt+Del?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("On most PCs this reboots immediately (in BIOS, GRUB or a text console). Unsaved work on the PC is lost.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { InkButton("Send", { confirmReboot = false; vm.kbPress("ctrl+alt+del") }) },
        dismissButton = { FrostOutlineButton("Cancel", { confirmReboot = false }) })
}

private val KEY_LABELS = mapOf("esc" to "Esc", "del" to "Del", "up" to "↑", "down" to "↓", "left" to "←", "right" to "→",
    "pgup" to "PgUp", "pgdn" to "PgDn", "backspace" to "⌫", "enter" to "Enter ⏎", "tab" to "Tab", "space" to "Space")

@Composable
private fun KeyChip(label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
        .background(if (active) Frost.Ink else Frost.Field)
        .border(1.dp, if (active) Frost.Ink else Frost.Line2, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontFamily = GeistMono, fontSize = 13.sp, color = when { active -> Color.White; enabled -> Frost.Ink; else -> Frost.Pewter })
    }

// ───────────────────────────── AI brain ─────────────────────────────
@Composable
fun ProvidersScreen(vm: RodinViewModel) {
    ScreenColumn {
        ScreenHeader("AI brain", "Your own subscription, or an API key")
        val signedIn = com.rodinrescue.runtime.Ubuntu.Cli.entries.associate { it.id to if (vm.builtinStatus[it]?.subscription == true) "on" else "" }
        SectionLabel("Subscription")
        ProviderChips(vm, listOf("claude" to "Claude", "codex" to "ChatGPT", "geminicli" to "Gemini"), signedIn)
        SectionLabel("API key · pay per use")
        val keys = mapOf("claudeapi" to vm.claudeApiKey, "deepseek" to vm.deepseekKey, "openai" to vm.openaiKey, "gemini" to vm.geminiKey)
        ProviderChips(vm, listOf("claudeapi" to "Claude", "openai" to "OpenAI", "gemini" to "Gemini", "deepseek" to "DeepSeek"), keys)
        when (vm.provider) {
            "claude", "codex", "geminicli" -> BuiltInCliCard(vm, com.rodinrescue.runtime.Ubuntu.Cli.of(vm.provider)!!)
            "claudeapi" -> ClaudeApiCard(vm)
            "openai" -> FrostCard {
                Text("OpenAI API", style = MaterialTheme.typography.titleMedium)
                FrostField("API key (Android Keystore encrypted)", vm.openaiKey, { vm.openaiKey = it }, secret = true)
                FrostField("Model id", vm.openaiModel, { vm.openaiModel = it }, mono = true)
            }
            "gemini" -> FrostCard {
                Text("Gemini API (Google AI Studio)", style = MaterialTheme.typography.titleMedium)
                FrostField("API key (Android Keystore encrypted)", vm.geminiKey, { vm.geminiKey = it }, secret = true)
                FrostField("Model id", vm.geminiModel, { vm.geminiModel = it }, mono = true)
            }
            else -> DeepSeekCard(vm)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton(if (vm.providerBusy) "Testing…" else "Save & test", { vm.testProvider() }, enabled = !vm.providerBusy)
            Muted(vm.providerStatus, Modifier.weight(1f), maxLines = 2)
        }
        val u = vm.usage
        if (u.promptTokens + u.completionTokens > 0) Muted("This session: ${u.promptTokens} in · ${u.completionTokens} out" + if (u.cachedTokens > 0) " · ${u.cachedTokens} cached" else "", color = Frost.Muted)
    }
}

/** The brains as chips: ink when chosen; a check when set up, a dot when chosen but not set up yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChips(vm: RodinViewModel, items: List<Pair<String, String>>, creds: Map<String, String>) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, name) ->
            val sel = vm.provider == id
            val has = creds[id].orEmpty().isNotBlank()
            Row(Modifier.clip(Pill).background(if (sel) Frost.Ink else Frost.Card).border(1.dp, if (sel) Frost.Ink else Frost.Line, Pill)
                .clickable(role = Role.RadioButton) { vm.provider = id; vm.providerStatus = "Not tested" }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (has) Icon(Icons.Filled.Check, "set up", Modifier.size(14.dp), tint = if (sel) Color.White else Frost.Good)
                else if (sel) Box(Modifier.size(7.dp).clip(CircleShape).background(Frost.Warn))
                Text(name, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Frost.Ink)
            }
        }
    }

/** An official subscription CLI inside Lifeline's own Ubuntu (PRoot): install, the user's own sign-in, update, remove. */
@Composable
private fun BuiltInCliCard(vm: RodinViewModel, cli: com.rodinrescue.runtime.Ubuntu.Cli) = FrostCard {
    val ctx = LocalContext.current
    vm.builtinRev   // re-read the install state when it changes
    val u = vm.ubuntus.getValue(cli)
    val ubuntuOk = u.installed; val version = u.version; val cliOk = ubuntuOk && version != null
    val st = vm.builtinStatus[cli]
    val mine = vm.builtinCli == cli
    val account = when (cli) { com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE -> "Claude"; com.rodinrescue.runtime.Ubuntu.Cli.CODEX -> "ChatGPT"; else -> "Google" }
    val size = when (cli) { com.rodinrescue.runtime.Ubuntu.Cli.CLAUDE -> "about 280 MB"; com.rodinrescue.runtime.Ubuntu.Cli.CODEX -> "about 100 MB"; else -> "about 40 MB" }
    LaunchedEffect(cli, cliOk) { if (cliOk && st == null) vm.builtinRefresh(cli) }
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(cli.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        when {
            st?.subscription == true -> Badge("Signed in", Tone.Good, dot = true)
            cliOk -> Badge("Sign in", Tone.Warn)
            else -> Badge("Built in", Tone.Neutral)
        }
        // Update / Sign out / Remove: rarely needed, so behind ⋯ instead of a row of buttons.
        if (!vm.builtinBusy && vm.builtinLink == null && ubuntuOk) Box {
            CircleIconButton(Icons.Outlined.MoreHoriz, "More", { menu = true }, size = 36.dp)
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = Frost.Card) {
                if (cliOk) DropdownMenuItem({ Text("Update ${cli.title}") }, { menu = false; vm.builtinInstall(cli) })
                if (st?.loggedIn == true) DropdownMenuItem({ Text("Sign out") }, { menu = false; vm.builtinSignOut(cli) })
                DropdownMenuItem({ Text("Remove ${cli.title}", color = Frost.Bad) }, { menu = false; vm.builtinRemove(cli) })
            }
        }
    }
    if (cliOk && st?.subscription == true) Muted("${cli.title} $version · $account" + (st.plan?.let { " $it" } ?: "") + " · runs in Lifeline's own Ubuntu; it only answers, Lifeline runs each step with your approval.")
    else {
        Muted("The official ${cli.title}, in Lifeline's own Ubuntu, checked against the publisher's checksum. It only answers; Lifeline runs each step with your approval.")
        StepRow(ubuntuOk, "Ubuntu 24.04", if (ubuntuOk) "Installed" else "About 30 MB download")
        StepRow(cliOk, cli.title + (version?.let { " $it" } ?: ""), if (cliOk) "Installed, checksum checked" else "${size.replaceFirstChar { it.uppercase() }} download")
        StepRow(st?.subscription, "Your $account subscription", when {
            st?.loggedIn == true -> "Signed in without a subscription: not used"
            else -> "You sign in yourself; Lifeline never sees your password" })
    }
    val link = vm.builtinLink
    if (vm.builtinBusy && mine) {
        Text(vm.builtinStep, style = MaterialTheme.typography.bodyMedium)
        vm.builtinFraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        GhostButton("Stop", { vm.builtinStop() })
    } else if (link != null && mine) {
        var code by remember { mutableStateOf("") }
        Muted(vm.builtinStep)
        link.userCode?.let { c -> TerminalBlock { Mono(c) }; FrostOutlineButton("Copy the code", { copy(ctx, c) }, icon = Icons.Outlined.ContentCopy) }
        InkButton("Open the sign-in page", { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url))) } }, Modifier.fillMaxWidth())
        if (link.userCode == null) {
            FrostField("Paste the code from the sign-in page", code, { code = it }, mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrostOutlineButton("Send the code", { vm.builtinSendCode(code); code = "" }, enabled = code.isNotBlank())
                GhostButton("Cancel", { vm.builtinStop() })
            }
        } else {
            StepRow(null, "Waiting for you to finish on OpenAI's page…", "Lifeline notices by itself.", inProgress = true)
            GhostButton("Cancel", { vm.builtinStop() })
        }
    } else when {
        !cliOk -> InkButton(if (ubuntuOk) "Install ${cli.title} ($size)" else "Install (Ubuntu + ${cli.title}, $size + 30 MB)", { vm.builtinInstall(cli) }, Modifier.fillMaxWidth(), enabled = !vm.builtinBusy)
        st?.subscription != true -> InkButton("Sign in to $account", { vm.builtinSignIn(cli) }, Modifier.fillMaxWidth(), enabled = !vm.builtinBusy)
    }
    if (cliOk && st?.subscription == true) CliModelPicker(vm, cli)
    if (cliOk && st?.subscription == true) {
        val on = vm.longSession[cli] == true
        FrostSwitch(on, { vm.updateLongSession(cli, it) }, "Remember the whole rescue")
        vm.cliUsedRev   // re-read after each step
        Muted(vm.cliSessions.getValue(cli).status.takeIf { on && it.isNotEmpty() }
            ?: if (on) "Faster follow-ups, less of your plan used." else "Off: a fresh ${cli.title} run for every step.", color = Frost.Muted)
    }
    if (mine) vm.builtinError?.let { Text(it, color = Frost.Bad, style = MaterialTheme.typography.bodyMedium) }
}

/** Model + effort for one built-in CLI: a compact row ("in use" = what the CLI reported on the last step), pills when open. */
@Composable
private fun CliModelPicker(vm: RodinViewModel, cli: com.rodinrescue.runtime.Ubuntu.Cli) {
    var open by remember { mutableStateOf(false) }
    val p = vm.cliProviders.getValue(cli)
    vm.cliUsedRev   // re-read after each step
    val model = vm.cliModel[cli].orEmpty(); val effort = vm.cliEffort[cli].orEmpty()
    val list = vm.cliModels[cli]
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Frost.Bg).clickable { open = !open; if (open && list == null) vm.refreshCliModels(cli) }
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model.ifEmpty { "Default model" }, fontFamily = GeistMono, fontSize = 14.sp, color = Frost.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val used = listOfNotNull(p.usedModel, p.usedEffort).joinToString(" · ")
            Muted("Effort " + effort.ifEmpty { "default" } + if (used.isNotEmpty()) " · last step: $used" else "", maxLines = 1)
        }
        Text(if (open) "Close" else "Change", fontSize = 13.sp, color = Frost.Ink2)
    }
    if (!open) return
    @Composable fun Pills(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) =
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            items.forEach { (id, label) ->
                val sel = selected == id
                Text(label, Modifier.clip(Pill).background(if (sel) Frost.Ink else Frost.Field).clickable(role = Role.RadioButton) { onPick(id) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), color = if (sel) Color.White else Frost.Ink, fontFamily = GeistMono, fontSize = 13.sp)
            }
        }
    SectionLabel("Model")
    when {
        vm.cliModelsBusy == cli -> Muted("Reading ${cli.title}'s models…")
        else -> Pills(listOf("" to "Default") + list.orEmpty().map { it.id to it.label }, model) { vm.setCliModel(cli, it) }
    }
    var custom by remember(model) { mutableStateOf(if (list.orEmpty().any { it.id == model }) "" else model) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrostField("Other model name", custom, { custom = it }, Modifier.weight(1f), mono = true)
        GhostButton("Use", { vm.setCliModel(cli, custom) }, enabled = custom.isBlank() || com.rodinrescue.ai.CliProvider.MODEL_NAME.matches(custom.trim()))
    }
    GhostButton("Refresh the list", { vm.refreshCliModels(cli) }, enabled = vm.cliModelsBusy == null)
    SectionLabel("Effort")
    val efforts = list.orEmpty().firstOrNull { it.id == model }?.efforts?.takeIf { it.isNotEmpty() } ?: p.efforts
    Pills(listOf("" to "Default") + efforts.map { it to it }, effort) { vm.setCliEffort(cli, it) }
    Muted("Default = whatever ${cli.title} picks for your plan. Higher effort is slower and uses more of your plan's limits.", color = Frost.Muted)
}

@Composable
private fun ClaudeApiCard(vm: RodinViewModel) = FrostCard {
    val ctx = LocalContext.current
    Text("Claude API (Anthropic Console)", style = MaterialTheme.typography.titleMedium)
    Muted("Pay per use with a key from console.anthropic.com (separate from a Claude.ai subscription).")
    FrostField("API key (Android Keystore encrypted)", vm.claudeApiKey, { vm.claudeApiKey = it }, secret = true)
    SectionLabel("Model")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        com.rodinrescue.ai.ClaudeApiProvider.MODELS.forEach { m ->
            val sel = vm.claudeApiModel == m
            Text(m, Modifier.clip(Pill).background(if (sel) Frost.Ink else Frost.Field).clickable(role = Role.RadioButton) { vm.claudeApiModel = m }
                .padding(horizontal = 14.dp, vertical = 10.dp), color = if (sel) Color.White else Frost.Ink, fontFamily = GeistMono, fontSize = 13.sp)
        }
    }
    FrostField("Or custom model id", vm.claudeApiModel, { vm.claudeApiModel = it }, mono = true)
    Muted("Sees screen photos (keyboard rescue). Keeps the whole conversation with prompt caching, so long sessions stay cheap.", color = Frost.Muted)
    GhostButton("Get a key: console.anthropic.com", { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.anthropic.com/settings/keys"))) } })
}

@Composable
private fun DeepSeekCard(vm: RodinViewModel) = FrostCard {
    Text("DeepSeek", style = MaterialTheme.typography.titleMedium)
    FrostField("API key (Android Keystore encrypted)", vm.deepseekKey, { vm.deepseekKey = it }, secret = true)
    SectionLabel("Model")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf("deepseek-flash", "deepseek-v4-pro").forEach { m ->
            val sel = vm.deepseekModel == m
            Text(m, Modifier.clip(Pill).background(if (sel) Frost.Ink else Frost.Field).clickable(role = Role.RadioButton) { vm.deepseekModel = m }
                .padding(horizontal = 14.dp, vertical = 10.dp), color = if (sel) Color.White else Frost.Ink, fontFamily = GeistMono, fontSize = 13.sp)
        }
    }
    FrostField("Or custom model id", vm.deepseekModel, { vm.deepseekModel = it }, mono = true)
    FrostSwitch(vm.deepseekThinking, { vm.deepseekThinking = it }, "Thinking mode (more tokens, slower)")
}
