package com.rodinrescue.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import com.rodinrescue.usb.BootLibrary
import com.rodinrescue.usb.BootLibrary.Mode

/**
 * Boot tab: the phone as a bootable USB drive. Images live in a visible folder (Internal storage › Lifeline › Boot);
 * picked ISOs are moved there. Tap one to show it to the PC; live systems can get an optional saved space.
 */
@Composable
fun BootScreen(vm: RodinViewModel) {
    LaunchedEffect(Unit) { vm.bootRefresh() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::bootAdd) }
    val st = vm.bootStatus
    val busy = vm.bootBusy != null

    ScreenColumn {
        ScreenHeader("Boot drive", "Start a PC from your phone")
        if (st != null && !st.supported) {
            FrostCard {
                Text("This phone can't be a USB drive", style = MaterialTheme.typography.titleMedium)
                Muted("Its kernel was built without the USB storage function, so a PC can't boot from it. Everything else in Lifeline works normally.")
            }
            return@ScreenColumn
        }

        RescueSystemCard(vm)

        // The rescue system's drives are shown by its own card above.
        st?.takeIf { it.on && it.image != vm.rescueImage.path }?.let { on ->
            FrostCard(dark = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("The PC sees a USB drive", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Muted(on.image?.substringAfterLast('/') ?: "", color = Frost.OnInkMuted, maxLines = 2)
                        on.image?.let { Muted("In the PC's boot menu: Lifeline - ${com.rodinrescue.usb.RescueDrive.driveName(it.substringAfterLast('/'))}", color = Frost.OnInkMuted, maxLines = 2) }
                        if (on.writable != null) Muted("+ saved space (writable)", color = Frost.OnInkMuted)
                    }
                    Badge("On", Tone.Good, dot = true)
                }
                Muted("Restart the PC and choose the USB drive in its boot menu (often F12, F11, F8 or Esc right after power-on). Stop the drive before unplugging if it's writing to saved space.", color = Frost.OnInkMuted)
                StopButton(if (vm.bootBusy == "Stopping…") "Stopping…" else "Stop the drive", { vm.bootStop() }, Modifier.fillMaxWidth())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton("Add image", { pick.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = !busy, icon = Icons.Outlined.Add)
            FrostOutlineButton("Refresh", { vm.bootRefresh() }, enabled = !busy)
        }
        vm.bootCopy?.let { (d, t) ->
            LinearProgressIndicator({ if (t > 0) d / t.toFloat() else 0f }, Modifier.fillMaxWidth(), color = Frost.Ink, trackColor = Frost.Line)
            Muted("Copying into the library… ${size(d)} of ${size(t)}")
        }
        vm.bootBusy?.takeIf { vm.bootCopy == null }?.let { Muted(it) }
        if (vm.bootMessage.isNotBlank()) Muted(vm.bootMessage)

        SectionLabel("Images · ${BootLibrary.SHOWN_AS}")
        if (vm.bootImages.isEmpty() && st != null) FrostCard {
            Text("No images yet", style = MaterialTheme.typography.titleSmall)
            Muted("Tap Add image and pick an .iso (Kali, Ubuntu, Hiren's, a Windows installer…). From Downloads it's moved, not copied, so it takes no extra space. You can also copy .iso files into ${BootLibrary.SHOWN_AS} from a PC or a file manager.")
        }
        for (img in vm.bootImages) ImageCard(vm, img, active = st?.on == true && st.image == img.path, busy = busy)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ImageCard(vm: RodinViewModel, img: BootLibrary.Image, active: Boolean, busy: Boolean) {
    var useSaved by remember(img.name, img.savedBytes) { mutableStateOf(img.savedBytes != null) }
    var addSaved by remember { mutableStateOf(false) }
    var gb by remember { mutableFloatStateOf(4f) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }   // path to what
    val mode = vm.bootModes[img.name] ?: Mode.DISK

    // One row: name, size and Boot. Tap it for how the PC sees it, saved space and delete.
    var open by remember(img.name) { mutableStateOf(false) }
    FrostCard(padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(img.name.removeSuffix(".iso"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(size(img.bytes) + (if (mode == Mode.CD) " · CD" else "") + (img.savedBytes?.let { " · saved space ${size(it)}" } ?: ""), maxLines = 1)
            }
            if (active) Badge("On the PC", Tone.Good, dot = true)
            else InkButton(if (vm.bootBusy == "Starting…") "…" else "Boot", { vm.bootStart(img, useSaved) }, enabled = !busy)
            Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (open) "Less" else "More", Modifier.size(20.dp), tint = Frost.Slate)
        }
        if (open) {
            // How the PC sees it: detected from the image, can be overridden.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted("Shown as", Modifier.padding(end = 4.dp))
                for ((m, label) in listOf(Mode.DISK to "USB disk", Mode.CD to "CD")) {
                    val sel = mode == m
                    Text(label, Modifier.clip(Pill).background(if (sel) Frost.Ink else Frost.Field).clickable(enabled = !active, role = Role.RadioButton) { vm.bootSetMode(img, m) }
                        .padding(horizontal = 14.dp, vertical = 8.dp), color = if (sel) Color.White else Frost.Ink, fontSize = 13.sp)
                }
            }
            if (mode == Mode.CD) Muted("For ISOs that only work as a CD, like Windows installers. If the PC doesn't offer it, try USB disk.", color = Frost.Muted)

            if (img.savedBytes != null) {
                FrostSwitch(useSaved, { useSaved = it }, "Use saved space (${size(img.savedBytes)})")
            } else if (!addSaved) {
                GhostButton("Add saved space…", { addSaved = true }, enabled = !busy)
            } else {
                Text("Saved space: ${gb.toInt()} GB", style = MaterialTheme.typography.titleSmall)
                Muted("Live systems keep files and settings across reboots. Uses phone storage only as it fills.")
                Slider(gb, { gb = it }, valueRange = 1f..64f, steps = 62)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Create", { vm.bootCreateSaved(img, gb.toInt()); addSaved = false }, Modifier.weight(1f), enabled = !busy)
                    GhostButton("Cancel", { addSaved = false })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GhostButton("Delete image", { confirm = img.path to "the image" }, enabled = !busy && !active)
                if (img.savedBytes != null) GhostButton("Delete saved space", { confirm = img.savedPath to "the saved space" }, enabled = !busy && !active)
            }
        }
    }
    confirm?.let { (path, what) ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Frost.Card, shape = RoundedCornerShape(28.dp),
            title = { Text("Delete $what?", style = MaterialTheme.typography.titleLarge) },
            text = { Text(if (what == "the image") "${img.name} is removed from the phone (${size(img.bytes)})." else "Everything the live system saved in it is lost.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { InkButton("Delete", { vm.bootDelete(path, what.replaceFirstChar { it.uppercase() }); confirm = null }) },
            dismissButton = { FrostOutlineButton("Cancel", { confirm = null }) })
    }
}

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

/**
 * Lifeline's own rescue system (B2): the PC boots a small Lifeline system from the phone and the SSH AI works on it,
 * with nothing to type on the PC. One button starts the drive, the key drive and the USB network, then waits for it.
 */
@Composable
private fun RescueSystemCard(vm: RodinViewModel) {
    LaunchedEffect(Unit) { vm.rescueRefresh(); vm.rescueResume() }
    val ready = vm.rescueImageReady
    FrostCard(dark = vm.rescueBusy || vm.rescueMode) {
        val onInk = vm.rescueBusy || vm.rescueMode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lifeline rescue system", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (onInk) Color.White else Frost.Ink)
            when {
                vm.rescueMode && vm.connected -> Badge("Connected", Tone.Good, dot = true)
                vm.rescueBusy -> Badge("Working", Tone.Warn, dot = true)
                ready -> Badge("Ready", Tone.Good)
            }
        }
        Muted("When Linux or Windows won't start: the PC starts a small Lifeline system from the phone, and the AI repairs it over the cable. Works with Secure Boot.",
            color = if (onInk) Frost.OnInkMuted else Frost.Slate)
        if (vm.rescueStatus.isNotBlank()) Text(vm.rescueStatus, style = MaterialTheme.typography.bodyMedium, color = if (onInk) Color.White else Frost.Ink)
        vm.rescueProgress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth(), color = Frost.Ink) }
        when {
            vm.rescueMode || (vm.rescueBusy && ready) -> StopButton("End the rescue session", { vm.rescueStop() }, Modifier.fillMaxWidth())
            !ready && !com.rodinrescue.usb.RescueSystem.published -> Muted("Not available yet.", color = Frost.Muted)
            !ready -> InkButton(if (vm.rescueBusy) "Downloading…" else "Download (about 450 MB, checked)", { vm.rescueDownload() }, Modifier.fillMaxWidth(), enabled = !vm.rescueBusy)
            else -> InkButton("Start the rescue system", { vm.rescueStart() }, Modifier.fillMaxWidth(), enabled = !vm.rescueBusy && vm.bootBusy == null)
        }
    }
}
