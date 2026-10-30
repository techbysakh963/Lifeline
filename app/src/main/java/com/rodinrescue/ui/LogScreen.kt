package com.rodinrescue.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rodinrescue.RodinViewModel
import com.rodinrescue.data.AuditEntry

/** Kinds that mean something went wrong; the "Problems" filter shows only these. */
internal fun isProblem(e: AuditEntry) = e.kind == "error" || e.kind == "stop" ||
    (e.kind == "approval" && (e.summary.contains("REJECTED") || e.summary.contains("auto-rejected")))

/** One entry as plain text, for copying (the log is redacted when written). */
internal fun logLine(e: AuditEntry): String {
    val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
    return "$t ${e.kind}: ${e.summary}" + (e.exitCode?.let { " (exit $it)" } ?: "") + (if (e.detail.isNotBlank()) "\n${e.detail}" else "")
}

/**
 * The technical log of the open conversation: raw errors, the brain, commands and their output, with times. The chat
 * shows plain-word errors; the details live here, for debugging and for a bug report.
 */
@Composable
fun LogScreen(vm: RodinViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    var problemsOnly by remember { mutableStateOf(false) }
    // Re-read when the chat changes, so a new error shows up while the screen is open.
    val all = remember(vm.chat.size, vm.running) { vm.audit.read(vm.audit.sessionId) }
    val shown = (if (problemsOnly) all.filter(::isProblem) else all).asReversed()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Column(Modifier.weight(1f)) {
                Text("Log", style = MaterialTheme.typography.titleMedium)
                Muted("This conversation, newest first", maxLines = 1)
            }
            CircleIconButton(Icons.Outlined.ContentCopy, "Copy the log", {
                copy(ctx, shown.asReversed().joinToString("\n\n", transform = ::logLine))
                android.widget.Toast.makeText(ctx, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!problemsOnly, { problemsOnly = false }, { Text("All ${all.size}") })
            FilterChip(problemsOnly, { problemsOnly = true }, { Text("Problems ${all.count(::isProblem)}") })
        }
        LazyColumn(Modifier.weight(1f).fadeTop().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + LocalNavSpace.current)) {
            if (shown.isEmpty()) item { Muted(if (problemsOnly) "No problems in this conversation." else "Nothing logged in this conversation yet.") }
            items(shown) { e -> LogRow(e) }
        }
    }
}

@Composable
private fun LogRow(e: AuditEntry) {
    var open by remember { mutableStateOf(false) }
    val bad = isProblem(e)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = e.detail.isNotBlank()) { open = !open }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts)), color = Frost.Muted, size = 11)
            Badge(e.kind, if (bad) Tone.Bad else Tone.Neutral)
            e.exitCode?.let { Mono("exit $it", color = if (it == 0) Frost.Muted else Frost.Bad, size = 11) }
        }
        Muted(e.summary, color = if (bad) Frost.Bad else Frost.Ink, maxLines = if (open) Int.MAX_VALUE else 3)
        if (open) TerminalBlock { Mono(e.detail.take(8_000)) }
        else if (e.detail.isNotBlank()) Muted("Tap for details", color = Frost.Muted)
    }
}
