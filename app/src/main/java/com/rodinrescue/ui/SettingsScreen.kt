package com.rodinrescue.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun SettingsScreen(vm: RodinViewModel, back: () -> Unit, openAi: () -> Unit, openStorage: () -> Unit) {
    val ctx = LocalContext.current
    var support by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = 24.dp + LocalNavSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Title("Settings")
        }

        // ── AI instructions (one per rescue mode: different tools, different rules) ──
        PromptCard("AI instructions · USB SSH", "Used when the phone reaches the PC over USB networking (SSH). It tells the AI it is ${Brand.NAME}, how to diagnose, and that it must ask before changing anything.",
            vm.currentPrompt, vm.promptIsDefault, vm::savePrompt, vm::resetPrompt)
        PromptCard("AI instructions · Keyboard rescue", "Used in keyboard rescue: the AI sees the PC's screen through camera photos and types through the USB keyboard (BIOS, GRUB, emergency shells).",
            vm.currentKbPrompt, vm.kbPromptIsDefault, vm::saveKbPrompt, vm::resetKbPrompt)

        // ── Keyboard rescue ──
        FrostCard {
            Text("Keyboard rescue", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.kbAutoPhoto, { vm.updateKbAutoPhoto(it) }, "Take a photo after every action")
            Muted("Off (default): the AI takes a photo on your first message and whenever it needs to check the screen; 📷 attaches one yourself. On: a photo after every key press and command — slower, but the AI never acts on an old view (good for unfamiliar BIOS menus).")
            FrostSwitch(vm.kbPhotoOnce, { vm.updateKbPhotoOnce(it) }, "Send each photo only once (saves tokens)")
            Muted("Normally the newest photo goes with every request so the AI can look at it again. On: each photo is sent once; later the AI relies on what it said about it. Cheaper on long sessions.")
        }

        // ── Conversation ──
        FrostCard {
            Text("Conversation", style = MaterialTheme.typography.titleMedium)
            FrostField("Reply language (blank = same as you write)", vm.replyLanguage, { vm.updateReplyLanguage(it) }, placeholder = "e.g. Arabic, English")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max agent steps per message", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Tag(vm.maxSteps.toString())
            }
            Slider(vm.maxSteps.toFloat(), { vm.updateMaxSteps(it.roundToInt()) }, valueRange = 5f..60f, steps = 10,
                colors = SliderDefaults.colors(thumbColor = Frost.Ink, activeTrackColor = Frost.Ink, inactiveTrackColor = Frost.Line2,
                    activeTickColor = Frost.Pewter, inactiveTickColor = Frost.Pewter))
            Muted("The AI stops after this many tool rounds so a confused model can't loop forever.")
        }

        // ── Security ──
        FrostCard {
            Text("Security", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.autoApprove, { vm.updateAutoApprove(it) }, "Run changes without asking")
            Muted("On: the AI's changes and key presses run at once, without the approval card. Use it only on a PC you know. " +
                "Every change still shows in the chat as Auto-approved and in the audit log. Forbidden commands stay blocked. " +
                "Turns itself off when ${Brand.NAME} restarts.")
            if (vm.autoApprove) {
                FrostSwitch(vm.autoApproveDestructive, { vm.updateAutoApproveDestructive(it) }, "Also deletions and removals")
                Muted("Off (recommended): commands that delete files, wipe or format, or remove packages still ask, with DELETE typed. On: they run too.")
            }
            FrostSwitch(vm.autoCleanup, { vm.updateAutoCleanup(it) }, "Remove access when I disconnect")
            Muted("For one-time repairs: Disconnect also takes this phone's key off the laptop and turns its SSH server back off (if Lifeline turned it on). The laptop is then forgotten.")
            FrostSwitch(vm.appLock, { on ->
                val act = ctx as androidx.fragment.app.FragmentActivity
                when {
                    !on -> AppLock.prompt(act, "Turn off app lock") { vm.updateAppLock(false) }
                    !AppLock.available(act) -> toast(ctx, "Set up a fingerprint or screen lock on the phone first")
                    else -> AppLock.prompt(act, "Turn on app lock") { vm.updateAppLock(true) }
                }
            }, "Lock with fingerprint / screen lock")
            Muted("Asks for your phone's fingerprint, face or PIN when ${Brand.NAME} opens, and after 30 s in the background.")
        }

        // ── App ──
        FrostCard {
            Text("App", style = MaterialTheme.typography.titleMedium)
            FrostSwitch(vm.keepScreenOn, { vm.updateKeepScreenOn(it) }, "Keep screen on during a rescue")
            FrostSwitch(vm.showSplash, { vm.updateShowSplash(it) }, "Show splash screen on launch")
            LinkRow("AI brain", "Provider, subscription bridges, API keys", openAi)
            LinkRow("Storage", "See what ${Brand.NAME} uses and free space", openStorage)
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
            LinkRow("Source code", REPO_URL.removePrefix("https://")) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            Muted("© 2026 techbysakh963 · Apache License 2.0. Copies and derived works must keep this credit and the NOTICE file.")
            Muted("Fonts: Geist, Geist Mono and Unbounded, SIL Open Font License 1.1.", color = Frost.Muted)
        }
    }

    if (support) SupportDialog { support = false }
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
        Muted("$about Safety checks in the app (blocked commands, approvals, typed DELETE) apply no matter what this says.")
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
