package com.rodinrescue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodinrescue.RodinViewModel

/**
 * Settings › Storage: what Lifeline uses on the phone and how to free it — boot images, conversations and their
 * screen photos, audit logs, the app cache, and the built-in Ubuntu that runs the AI CLIs.
 */
@Composable
fun StorageScreen(vm: RodinViewModel, back: () -> Unit) {
    LaunchedEffect(Unit) { vm.storageRefresh() }
    val r = vm.storage
    val busy = vm.storageBusy != null
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = 24.dp + LocalNavSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Title("Storage")
        }
        vm.storageBusy?.let { Muted(it) }
        if (vm.storageMessage.isNotBlank()) Muted(vm.storageMessage)
        if (r == null) { FrostCard { Muted("Measuring…") }; return@Column }

        // ── Totals ──
        val imagesBytes = r.images.sumOf { it.first.bytes + it.second }
        val convBytes = r.conversations.sumOf { it.second.textBytes + it.second.photoBytes }
        val cliBytes = r.builtin?.get("ubuntu") ?: 0L
        FrostCard {
            Text("Phone storage", style = MaterialTheme.typography.titleMedium)
            val used = r.phoneTotal - r.phoneFree
            LinearProgressIndicator({ if (r.phoneTotal > 0) used / r.phoneTotal.toFloat() else 0f }, Modifier.fillMaxWidth(), color = Frost.Ink, trackColor = Frost.Line)
            Muted("${size(r.phoneFree)} free of ${size(r.phoneTotal)}")
            Total("Boot images", imagesBytes)
            Total("Conversations and photos", convBytes)
            Total("Audit logs", r.auditBytes)
            Total("AI CLIs (built-in Ubuntu)", cliBytes, measuring = r.builtin == null)
            Total("App cache", r.cacheBytes)
        }

        // ── Boot images: collapsed like Conversations ──
        if (r.images.isNotEmpty()) FrostCard {
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Boot images", style = MaterialTheme.typography.titleMedium)
                    Muted("${r.images.size} in Internal storage › Lifeline › Boot · ${size(imagesBytes)}")
                }
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (open) "Hide" else "Show", tint = Frost.Slate)
            }
            if (open) {
                Muted("An image the PC is using can't be deleted until you stop the drive.")
                for ((img, saved) in r.images) Item(img.name, size(img.bytes) + if (img.savedBytes != null) " · saved space uses ${size(saved)} of ${size(img.savedBytes)}" else "") {
                    GhostButton("Delete", { confirm = Confirm("Delete ${img.name}?", "${size(img.bytes)} is freed. You can add it again later.") { vm.storageDeleteBoot(img.path, "The image") } }, enabled = !busy)
                    if (img.savedBytes != null) GhostButton("Delete saved space", { confirm = Confirm("Delete its saved space?", "Everything the live system saved in it is lost.") { vm.storageDeleteBoot(img.savedPath, "The saved space") } }, enabled = !busy)
                }
            }
        }

        // ── Conversations: one row until tapped, so a long list doesn't push the rest of the screen away ──
        FrostCard {
            var open by remember { mutableStateOf(false) }
            val photos = r.conversations.sumOf { it.second.photos }
            Row(Modifier.fillMaxWidth().clickable(enabled = r.conversations.isNotEmpty(), role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Conversations", style = MaterialTheme.typography.titleMedium)
                    Muted(if (r.conversations.isEmpty()) "No saved conversations." else "${r.conversations.size} saved · $photos screen photos (${size(r.conversations.sumOf { it.second.photoBytes })})")
                }
                if (r.conversations.isNotEmpty()) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (open) "Hide" else "Show", tint = Frost.Slate)
            }
            if (open) {
                if (photos > 0) FrostOutlineButton("Delete all screen photos", { confirm = Confirm("Delete all screen photos?", "Every conversation keeps its text; the photos show as \"Photo not kept\".") { vm.storageDeleteAllPhotos() } }, Modifier.fillMaxWidth(), enabled = !busy)
                for ((c, sz) in r.conversations) Item(c.title, "${date(c.updatedAt)} · text ${size(sz.textBytes)}" + if (sz.photos > 0) " · ${sz.photos} photos ${size(sz.photoBytes)}" else "") {
                    if (sz.photos > 0) GhostButton("Delete photos", { vm.storageDeletePhotos(c.id) }, enabled = !busy)
                    GhostButton("Delete", { confirm = Confirm("Delete this conversation?", "\"${c.title}\" and its audit log are removed from the phone.") { vm.storageDeleteConversation(c.id) } }, enabled = !busy)
                }
            }
        }

        // ── Auto-delete ──
        FrostCard {
            Text("Delete old photos and logs", style = MaterialTheme.typography.titleMedium)
            Muted("Screen photos and audit logs older than this are deleted when ${Brand.NAME} starts. Conversations keep their text. Audit logs now: ${size(r.auditBytes)}.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((d, label) in listOf(0 to "Keep", 7 to "7 days", 30 to "30 days", 90 to "90 days")) {
                    val sel = vm.autoDeleteDays == d
                    Text(label, Modifier.weight(1f).clip(Pill).background(if (sel) Frost.Ink else Frost.Field).clickable(enabled = !busy, role = Role.RadioButton) { vm.updateAutoDeleteDays(d) }
                        .padding(vertical = 8.dp), color = if (sel) Color.White else Frost.Ink, fontSize = 13.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }

        // ── AI CLIs ──
        FrostCard {
            Text("AI CLIs (subscription brains)", style = MaterialTheme.typography.titleMedium)
            val bu = r.builtin
            when {
                bu == null -> Muted("Measuring the built-in Ubuntu… (a few seconds)")
                (bu["ubuntu"] ?: 0L) == 0L -> Muted("Not installed. AI brain › Install sets up Claude Code, Codex or Antigravity inside Lifeline.")
                else -> {
                    Muted("The subscription CLIs run in Lifeline's own Ubuntu: ${size(bu["ubuntu"] ?: 0)}, of which your sign-ins are ${size(bu["logins"] ?: 0)}.")
                    GhostButton("Remove built-in Ubuntu…", { confirm = Confirm("Remove built-in Ubuntu?",
                        "Deletes it (${size(bu["ubuntu"] ?: 0)}): Claude Code, Codex, Antigravity and your sign-ins to them. Subscription brains stop working until you install again in AI brain and sign in. API-key brains are not affected.",
                        "Remove") { vm.storageRemoveBuiltin() } }, enabled = !busy)
                }
            }
        }

        // ── Cache ──
        FrostCard {
            Text("App cache", style = MaterialTheme.typography.titleMedium)
            Muted("Temporary files (${size(r.cacheBytes)}). Safe to clear.")
            FrostOutlineButton("Clear cache", { vm.storageClearCache() }, Modifier.fillMaxWidth(), enabled = !busy && r.cacheBytes > 0)
        }
    }

    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
            title = { Text(c.title, style = MaterialTheme.typography.titleLarge) },
            text = { Text(c.body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { InkButton(c.action, { c.run(); confirm = null }) },
            dismissButton = { FrostOutlineButton("Cancel", { confirm = null }) })
    }
}

private class Confirm(val title: String, val body: String, val action: String = "Delete", val run: () -> Unit)

@Composable
private fun Total(label: String, bytes: Long, measuring: Boolean = false) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Tag(if (measuring) "…" else size(bytes))
}

@Composable
private fun Item(title: String, detail: String, actions: @Composable RowScope.() -> Unit) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Field).padding(12.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Muted(detail)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
}

private fun date(t: Long) = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}
