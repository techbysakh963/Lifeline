package com.rodinrescue.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodinrescue.RodinViewModel
import com.rodinrescue.data.Conversation
import com.rodinrescue.data.DeviceProfile

private fun ago(t: Long) = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
private fun clip(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(Brand.NAME, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
private fun shareText(ctx: Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share conversation"))

@Composable
fun DevicesScreen(vm: RodinViewModel, openChat: () -> Unit, openKbRescue: () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadDevices() }
    val d = selected?.let { id -> vm.devices.firstOrNull { it.id == id } }
    // A resumed conversation reopens where it belongs: keyboard rescue (camera + keyboard) or the SSH chat.
    val openResumed = { if (vm.kbMode) openKbRescue() else openChat() }
    if (d != null) DeviceDetail(vm, d, back = { selected = null }, openChat = openResumed)
    else DeviceList(vm, open = { selected = it }, openChat = openResumed)
}

@Composable
private fun ScrollBody(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = 24.dp + LocalNavSpace.current), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

@Composable
private fun DeviceList(vm: RodinViewModel, open: (String) -> Unit, openChat: () -> Unit) = ScrollBody {
    ScreenHeader("Devices", if (vm.devices.isEmpty()) "Computers and conversations" else "${vm.devices.size} saved computer${if (vm.devices.size == 1) "" else "s"}")
    if (vm.devices.isEmpty()) FrostCard {
        Text("No saved devices yet", style = MaterialTheme.typography.titleSmall)
        Muted("Plug into a laptop and press “Set up everything for SSH” on Home. Each computer you connect to is saved here with its conversations, and can be removed again in one tap.")
    }
    vm.devices.forEach { d ->
        val live = vm.isConnectedTo(d.id)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (live) Frost.Ink else Frost.Card)
            .clickable(role = Role.Button) { open(d.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (live) Color(0xFF1A1D21) else Frost.Bg), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Computer, null, Modifier.size(22.dp), tint = if (live) Color.White else Frost.Ink)
            }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, color = if (live) Color.White else Frost.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.user}@${d.lastIp} · ${ago(d.lastUsed)}", fontSize = 12.sp, fontFamily = Geist, color = if (live) Frost.OnInkMuted else Frost.Slate, maxLines = 1)
            }
            if (live) Badge("Connected", Tone.Chrome) else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = Frost.Slate)
        }
    }
    val loose = remember(vm.conversationsVersion, vm.devices) { vm.conversations(null) }
    if (loose.isNotEmpty()) {
        SectionLabel("Older sessions", Modifier.padding(top = 8.dp))
        loose.forEach { ConversationRow(vm, it, null, openChat) }
    }
    ManualConnection(vm)
}

/** Connecting to a computer by hand (address, user, key or password), for one Lifeline's setup didn't prepare. */
@Composable
private fun ManualConnection(vm: RodinViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Pill).clickable(role = Role.Button) { open = !open }.padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Connect by hand (advanced)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Frost.Slate)
        Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Frost.Slate)
    }
    if (!open) return
    FrostCard {
        FrostField("Computer address", vm.host, { vm.host = it }, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostField("Port", vm.port, { vm.port = it }, Modifier.weight(1f), number = true, mono = true)
            FrostField("User", vm.user, { vm.user = it }, Modifier.weight(2f))
        }
        FrostSwitch(vm.useKey, { vm.useKey = it }, if (vm.useKey) "Private key" else "Password")
        if (vm.useKey) {
            FrostField("Private key (PEM / OpenSSH)", vm.sshKey, { vm.sshKey = it }, singleLine = false, mono = true)
            FrostField("Key passphrase (not stored)", vm.keyPassphrase, { vm.keyPassphrase = it }, secret = true)
        } else FrostField("SSH password", vm.sshPassword, { vm.sshPassword = it }, secret = true)
        val hostId = "${vm.host.trim()}:${vm.port.toIntOrNull() ?: 22}"
        val pinned = vm.settings.pinnedHostKey(hostId)
        Muted(if (pinned != null) "Known host key: $pinned" else "You'll be asked to check the computer's key on the first connection.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Connect", { vm.connect() }, enabled = vm.host.isNotBlank() && vm.user.isNotBlank())
            FrostOutlineButton("Check host key", { vm.probeHostKey() }, enabled = vm.host.isNotBlank())
        }
        if (pinned != null) GhostButton("Forget the host key", { vm.settings.forgetHostKey(hostId); vm.connState = "Host key forgotten" })
        if (vm.connState.isNotBlank()) Muted(vm.connState)
    }
}

@Composable
private fun DeviceDetail(vm: RodinViewModel, d: DeviceProfile, back: () -> Unit, openChat: () -> Unit) {
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val live = vm.isConnectedTo(d.id)
    ScrollBody {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Text(d.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CircleIconButton(Icons.Outlined.Edit, "Rename", { rename = true })
        }

        FrostCard(dark = live) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Connected" else "Not connected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (live) Badge("Live", Tone.Chrome) else Muted("last used ${ago(d.lastUsed)}")
            }
            if (live) {
                FrostOutlineButton("Disconnect", { vm.disconnect() }, Modifier.fillMaxWidth(), onDark = true)
            } else InkButton(if (vm.scanning) "Searching the USB link…" else "Connect", { vm.connectDevice(d.id) }, Modifier.fillMaxWidth(), enabled = !vm.scanning)
            if (!live && vm.activeDeviceId == d.id && vm.connState.isNotBlank()) Muted(vm.connState)
        }

        FrostCard {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            InfoRow("User", d.user); InfoRow("Last address", "${d.lastIp}:${d.port}")
            if (d.hostname.isNotBlank()) InfoRow("Hostname", d.hostname)
            if (d.osInfo.isNotBlank()) InfoRow("System", d.osInfo.split(" · ").drop(1).joinToString(" · ").ifBlank { d.osInfo })
            d.fingerprints.firstOrNull()?.let { InfoRow("Host key", it, mono = true) }
        }

        FrostCard {
            Text("What Lifeline changed on it", style = MaterialTheme.typography.titleMedium)
            ChangeRow(true, "This phone's key is authorized for ${d.user}")
            when (d.installedSshd) { true -> ChangeRow(true, "Lifeline installed the OpenSSH server"); false -> ChangeRow(false, "OpenSSH was already installed"); null -> {} }
            when (d.sshdWasEnabled) { false -> ChangeRow(true, "SSH server turned on by Lifeline (it was off)"); true -> ChangeRow(false, "SSH server was already on"); null -> {} }
            d.ufwIface?.let { ChangeRow(true, "Firewall: SSH allowed on $it only") }
            if (!d.stateRecorded) Muted("Set up with an older version, so the original SSH state wasn't recorded. Remove will take the key away and leave the SSH server as it is.")
            Muted("The SSH server listens on every network the laptop joins while it's on. Remove when you're done with this computer.", color = Frost.Slate)
        }

        FrostCard {
            Text("Remove access", style = MaterialTheme.typography.titleMedium)
            Muted(if (live) "Takes this phone's key off the laptop, removes the firewall rule and turns the SSH server off if Lifeline turned it on. Needs the sudo password (Home) for the last two."
                  else "Connect first to undo everything on the laptop automatically. Or forget it here and run the command shown on the laptop.")
            Button({ vm.askRemove(d) }, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = !vm.removing, shape = Pill,
                colors = ButtonDefaults.buttonColors(containerColor = Frost.BadBg, contentColor = Frost.Bad)) {
                Text(if (vm.removing) "Removing…" else "Remove from this laptop", style = MaterialTheme.typography.labelLarge)
            }
            GhostButton("Forget on this phone only", { confirmForget = true })
        }

        val convs = remember(vm.conversationsVersion, d.id) { vm.conversations(d.id) }
        SectionLabel("Conversations (${convs.size})", Modifier.padding(top = 4.dp))
        if (convs.isEmpty()) Muted("Rescue chats with this computer are saved here automatically.")
        convs.forEach { ConversationRow(vm, it, d.name, openChat) }
    }

    if (rename) {
        var name by remember { mutableStateOf(d.name) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
            title = { Text("Rename device", style = MaterialTheme.typography.titleLarge) },
            text = { FrostField("Name", name, { name = it }) },
            confirmButton = { InkButton("Save", { vm.renameDevice(d.id, name); rename = false }, enabled = name.isNotBlank()) },
            dismissButton = { FrostOutlineButton("Cancel", { rename = false }) })
    }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Forget “${d.name}”?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Removes it from this phone only. The laptop keeps trusting this phone until you run the command shown next. Conversations are kept.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { InkButton("Forget", { confirmForget = false; vm.forgetDevice(d, showManual = true); back() }) },
        dismissButton = { FrostOutlineButton("Cancel", { confirmForget = false }) })
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) =
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(104.dp), fontSize = 13.sp, fontFamily = Geist, color = Frost.Slate)
        Text(value, Modifier.weight(1f), fontSize = if (mono) 12.sp else 13.sp, fontFamily = if (mono) GeistMono else Geist, color = Frost.Ink)
    }

@Composable
private fun ChangeRow(changed: Boolean, text: String) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (changed) Frost.Warn else Frost.Line2))
        Text(text, fontSize = 13.sp, fontFamily = Geist, color = Frost.Ink)
    }

@Composable
private fun ConversationRow(vm: RodinViewModel, c: Conversation, deviceName: String?, openChat: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    FrostCard(padding = 14.dp) {
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val n = c.chat.count { it is com.rodinrescue.ChatItem.UserMsg || it is com.rodinrescue.ChatItem.AssistantMsg }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.mode == "keyboard") Badge("⌨ Keyboard", Tone.Ink)
            Muted("${ago(c.updatedAt)} · $n message${if (n == 1) "" else "s"}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton("Resume", { if (vm.resumeConversation(c.id)) openChat() }, Modifier.weight(1f))
            CircleIconButton(Icons.Outlined.ContentCopy, "Copy conversation", { clip(ctx, vm.conversationText(c.id)) })
            CircleIconButton(Icons.Outlined.Share, "Share conversation", { shareText(ctx, vm.conversationText(c.id)) })
            CircleIconButton(Icons.Outlined.DeleteOutline, "Delete conversation", { confirmDelete = true })
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Delete this conversation?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("“${c.title}” and its audit log are deleted from this phone.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { InkButton("Delete", { confirmDelete = false; vm.deleteConversation(c.id) }) },
        dismissButton = { FrostOutlineButton("Cancel", { confirmDelete = false }) })
}

/**
 * "Remove from this laptop": asks for the sudo password up front and verifies it before anything changes.
 * Errors (missing / wrong password, lost link) are shown here and the device is kept.
 */
@Composable
fun RemoveDialog(vm: RodinViewModel) {
    val d = vm.removePrompt ?: return
    val live = vm.isConnectedTo(d.id)
    var pw by remember(d.id) { mutableStateOf(vm.sudoPassword) }
    AlertDialog(onDismissRequest = { vm.dismissRemove() }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Remove from “${d.name}”?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (live) {
                    Text("Takes this phone's key off the laptop, removes Lifeline's firewall rule and turns the SSH server off if Lifeline turned it on. Your sudo password is checked first; nothing changes if it's wrong.",
                        style = MaterialTheme.typography.bodyMedium)
                    FrostField("sudo password for ${d.user}", pw, { pw = it }, secret = true)
                    Muted("Kept in memory only, never saved or sent to the AI.", color = Frost.Muted)
                } else Text("Not connected, so the laptop can't be cleaned from here. Connect first to undo everything automatically, or forget it now and run the command shown on the laptop.",
                    style = MaterialTheme.typography.bodyMedium)
                vm.removeError?.let { err ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Frost.BadBg).padding(12.dp)) {
                        Text(err, color = Frost.Bad, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (vm.removing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetalOrb(22.dp); Muted("Working on the laptop…") }
                if (live && vm.removeError != null && !vm.removing)
                    GhostButton("Remove the key only (leave firewall & SSH server)", { vm.removeFromLaptop(d, "", keyOnly = true) })
            }
        },
        confirmButton = {
            if (live) InkButton(if (vm.removing) "Removing…" else "Remove", { vm.removeFromLaptop(d, pw) }, enabled = !vm.removing)
            else InkButton("Forget & show command", { vm.dismissRemove(); vm.forgetDevice(d, showManual = true) })
        },
        dismissButton = { FrostOutlineButton("Cancel", { vm.dismissRemove() }, enabled = !vm.removing) })
}

/** Result of "Remove from this laptop" / "Forget": what was undone, plus the by-hand command when needed. */
@Composable
fun RemovalReportDialog(vm: RodinViewModel) {
    val r = vm.removalReport ?: return
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = { vm.removalReport = null }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("“${r.deviceName}”", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                r.lines.forEach { (ok, line) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(if (ok) Frost.Ink else Frost.Warn), contentAlignment = Alignment.Center) {
                            if (ok) Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = Color.White) else Text("!", fontSize = 11.sp, color = Frost.Ink)
                        }
                        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                r.manualCommand?.let { cmd ->
                    Text("Run this on the laptop (any terminal) to finish:", style = MaterialTheme.typography.titleSmall)
                    TerminalBlock(Modifier.heightIn(max = 220.dp)) { Column(Modifier.verticalScroll(rememberScrollState())) { Mono(cmd, size = 11) } }
                    FrostOutlineButton("Copy command", { clip(ctx, cmd) }, icon = Icons.Outlined.ContentCopy)
                }
            }
        },
        confirmButton = { InkButton("Done", { vm.removalReport = null }) })
}
