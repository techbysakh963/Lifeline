package com.rodinrescue.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rodinrescue.RodinViewModel
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/techbysakh963/Lifeline"

@Composable
fun SettingsScreen(vm: RodinViewModel, back: () -> Unit, openStorage: () -> Unit, openLog: () -> Unit = {}) {
    val ctx = LocalContext.current
    var support by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = 24.dp + LocalNavSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Title("Settings")
        }

        // ── Security ──
        FrostCard {
            Text("Security", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.autoApprove, { vm.updateAutoApprove(it) }, "Run changes without asking")
            Muted("For a PC you know. Changes still show in the chat; blocked commands stay blocked; off again when ${Brand.NAME} restarts.")
            if (vm.autoApprove) {
                FrostSwitch(vm.autoApproveDestructive, { vm.updateAutoApproveDestructive(it) }, "Also deletions and removals")
                Muted("Off (recommended): deleting, wiping and removing packages still ask, with DELETE typed.")
            }
            FrostSwitch(vm.autoCleanup, { vm.updateAutoCleanup(it) }, "Remove access when I disconnect")
            Muted("For one-time repairs: Disconnect also takes this phone's key off the PC and forgets it.")
            FrostSwitch(vm.appLock, { on ->
                val act = ctx as androidx.fragment.app.FragmentActivity
                when {
                    !on -> AppLock.prompt(act, "Turn off app lock") { vm.updateAppLock(false) }
                    !AppLock.available(act) -> toast(ctx, "Set up a fingerprint or screen lock on the phone first")
                    else -> AppLock.prompt(act, "Turn on app lock") { vm.updateAppLock(true) }
                }
            }, "Lock with fingerprint / screen lock")
        }

        // ── Keyboard rescue ──
        FrostCard {
            Text("Keyboard rescue", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.kbAutoPhoto, { vm.updateKbAutoPhoto(it) }, "Take a photo after every action")
            Muted("Slower, but the AI never acts on an old view. Good for unfamiliar BIOS menus.")
            FrostSwitch(vm.kbPhotoOnce, { vm.updateKbPhotoOnce(it) }, "Send each photo only once")
            Muted("Cheaper on long sessions: later steps rely on what the AI said about the photo.")
        }

        // ── Conversation ──
        FrostCard {
            Text("Conversation", style = MaterialTheme.typography.titleMedium)
            FrostField("Reply language (blank = same as you write)", vm.replyLanguage, { vm.updateReplyLanguage(it) }, placeholder = "e.g. Arabic, English")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max AI steps per message", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Tag(vm.maxSteps.toString())
            }
            Slider(vm.maxSteps.toFloat(), { vm.updateMaxSteps(it.roundToInt()) }, valueRange = 5f..60f, steps = 10,
                colors = SliderDefaults.colors(thumbColor = Frost.Ink, activeTrackColor = Frost.Ink, inactiveTrackColor = Frost.Line2,
                    activeTickColor = Frost.Pewter, inactiveTickColor = Frost.Pewter))
        }

        // ── App ──
        FrostCard {
            Text("App", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.keepScreenOn, { vm.updateKeepScreenOn(it) }, "Keep screen on during a rescue")
            FrostSwitch(vm.showSplash, { vm.updateShowSplash(it) }, "Show splash screen on launch")
            LinkRow("Storage", "See what ${Brand.NAME} uses and free space", openStorage)
        }

        // ── Advanced: USB mode and the AI's instructions, collapsed ──
        Row(Modifier.fillMaxWidth().clip(Pill).clickable(role = Role.Button) { advanced = !advanced }.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Frost.Slate)
            Icon(if (advanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Frost.Slate)
        }
        if (advanced) {
            FrostCard {
                Text("USB", style = MaterialTheme.typography.titleMedium)
                FrostOutlineButton("Restore normal USB mode", { vm.restoreUsb() }, Modifier.fillMaxWidth(), enabled = !vm.usbBusy)
                Muted("Turns off tethering, the keyboard, serial and the boot drive, and removes the VPN rule. File transfer comes back.")
                FrostOutlineButton("Check what this phone's USB can do", { vm.inspectUsb() }, Modifier.fillMaxWidth(), enabled = !vm.usbBusy)
                if (vm.usbReport.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FrostOutlineButton("Copy", { copyText(ctx, vm.usbReport); toast(ctx, "Copied") }); FrostOutlineButton("Share", { share(ctx, vm.usbReport) })
                    }
                    TerminalBlock(Modifier.heightIn(max = 360.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) { Mono(vm.usbReport) }
                    }
                }
            }
            // One per rescue mode: different tools, different rules.
            PromptCard("AI instructions · SSH", "What the AI is told over SSH.", vm.currentPrompt, vm.promptIsDefault, vm::savePrompt, vm::resetPrompt)
            PromptCard("AI instructions · Keyboard rescue", "What the AI is told in keyboard rescue.", vm.currentKbPrompt, vm.kbPromptIsDefault, vm::saveKbPrompt, vm::resetKbPrompt)
        }

        // ── Support ──
        FrostCard {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Field).clickable(role = Role.Button) { support = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoffeeMark(28.dp)
                Column(Modifier.weight(1f)) { Text("Buy me a coffee", style = MaterialTheme.typography.titleSmall); Muted("Support ${Brand.NAME} · ${Donate.ASSET} on ${Donate.NETWORK}") }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = Frost.Slate)
            }
        }

        // ── About ──
        FrostCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LifelineMark(40.dp)
                Column(Modifier.weight(1f)) {
                    Text(Brand.NAME, style = MaterialTheme.typography.titleMedium)
                    Muted("Version ${appVersion(ctx)} · open source")
                }
            }
            LinkRow("Log", "Technical details of the open conversation: errors, commands, times", openLog)
            LinkRow("Report a bug", "Opens a form on GitHub with a short diagnostic report") { report = vm.bugReport() }
            LinkRow("Source code", REPO_URL.removePrefix("https://")) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            Muted("© 2026 techbysakh963 · Apache License 2.0. Copies and derived works must keep this credit and the NOTICE file.")
            Muted("Fonts: Geist, Geist Mono and Unbounded, SIL Open Font License 1.1.", color = Frost.Muted)
        }
    }

    if (support) SupportDialog { support = false }
    report?.let { text -> BugReportDialog(text) { report = null } }
}

/** The report is shown in full before anything leaves the phone; GitHub opens with it filled in. */
@Composable
private fun BugReportDialog(text: String, close: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = close, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Report a bug", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("This report goes with it. No keys, passwords, conversations or data from the PC. On GitHub, describe what happened and the steps.")
                TerminalBlock(Modifier.heightIn(max = 280.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { Mono(text) }
                }
                Muted("Needs a free GitHub account. No account? Copy it and post it on the Lifeline XDA thread.", color = Frost.Muted)
            }
        },
        confirmButton = { InkButton("Open GitHub", {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.rodinrescue.report.BugReport.issueUrl(text)))) }; close() }) },
        dismissButton = { FrostOutlineButton("Copy", { copyText(ctx, text); toast(ctx, "Report copied") }) })
}

/** View / edit / save / reset one mode's AI instructions. The app's safety checks apply whatever the text says. */
@Composable
private fun PromptCard(title: String, about: String, current: String, isDefault: Boolean, save: (String) -> Unit, reset: () -> Unit) {
    val ctx = LocalContext.current
    // Draft is keyed on the saved value so Save / Reset refresh the editor.
    var draft by remember(current) { mutableStateOf(current) }
    var confirmReset by remember { mutableStateOf(false) }
    val dirty = draft.trim() != current.trim()
    FrostCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            when {
                dirty -> Badge("Unsaved", Tone.Warn)
                isDefault -> Badge("Default", Tone.Neutral)
                else -> Badge("Edited", Tone.Ink)
            }
        }
        Muted("$about The app's safety checks (blocked commands, approvals, typed DELETE) apply whatever it says.")
        // Fixed height: the field scrolls inside itself, the page scrolls everywhere else.
        FrostField("", draft, { draft = it }, Modifier.fillMaxWidth().height(300.dp), singleLine = false, mono = true, maxLines = Int.MAX_VALUE, small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            InkButton("Save", { save(draft); toast(ctx, "Instructions saved") }, Modifier.weight(1f), enabled = dirty && draft.isNotBlank())
            FrostOutlineButton("Reset", { confirmReset = true }, enabled = !isDefault || dirty, icon = Icons.Outlined.RestartAlt)
            CircleIconButton(Icons.Outlined.ContentCopy, "Copy instructions", { copyText(ctx, draft); toast(ctx, "Copied") })
        }
        if (dirty) GhostButton("Discard changes", { draft = current })
        Muted("Takes effect on the next message you send.", color = Frost.Muted)
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
        title = { Text("Reset these instructions?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Your edited text is replaced with the ${Brand.NAME} default. This can't be undone.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { InkButton("Reset to default", { confirmReset = false; reset(); toast(ctx, "Default instructions restored") }) },
        dismissButton = { FrostOutlineButton("Cancel", { confirmReset = false }) })
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Field).clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Muted(subtitle) }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = Frost.Slate)
    }

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
private fun copyText(ctx: Context, text: String) =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(Brand.NAME, text))
private fun appVersion(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
