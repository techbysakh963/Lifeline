package com.rodinrescue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rodinrescue.RodinViewModel
import kotlinx.coroutines.launch

/**
 * USB tools › USB serial: a terminal on the PC's serial login (/dev/ttyACM0 on the PC). Works when the PC's OS runs
 * but its network or SSH doesn't. Manual: what you type goes to the PC as-is (no policy checks), like Terminal.
 */
@Composable
fun SerialScreen(vm: RodinViewModel, back: () -> Unit, openChat: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    DisposableEffect(Unit) { vm.serialScreenOpened(); vm.serialRefresh(); onDispose { vm.serialScreenClosed() } }
    var input by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    LaunchedEffect(vm.serialLines.size) { if (vm.serialLines.isNotEmpty()) state.scrollToItem(vm.serialLines.lastIndex) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp).aboveKeyboard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Column(Modifier.weight(1f)) {
                Title("USB serial")
                Muted(when { vm.serialSupported == false -> "Not supported by this phone"; vm.serialOn -> "On · the PC sees a serial port"; else -> "Off" })
            }
            if (vm.serialOn) Badge("On", Tone.Good, dot = true)
        }
        if (vm.serialSupported == false) {
            FrostCard { Muted("This phone's kernel can't act as a USB serial port. Everything else in Lifeline works normally.") }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (vm.serialOn) StopButton(if (vm.serialBusy) "Turning off…" else "Turn off", { vm.serialToggle(false) })
            else InkButton(if (vm.serialBusy) "Turning on…" else "Turn on", { vm.serialToggle(true) }, enabled = !vm.serialBusy)
            FrostOutlineButton("Enter", { vm.serialRaw("\r") }, enabled = vm.serialOn)
            FrostOutlineButton("Ctrl+C", { vm.serialRaw("\u0003") }, enabled = vm.serialOn)
            FrostOutlineButton("Ctrl+D", { vm.serialRaw("\u0004") }, enabled = vm.serialOn)
            FrostOutlineButton("Tab", { vm.serialRaw("\t") }, enabled = vm.serialOn)
            FrostOutlineButton("Copy", { copy(ctx, vm.serialText()) })
            GhostButton("Clear", { vm.serialClear() })
        }
        if (vm.serialMessage.isNotBlank()) Muted(vm.serialMessage)
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Frost.Ink).padding(14.dp)) {
            if (vm.serialLines.all { it.isBlank() }) Muted(
                if (vm.serialOn) "Waiting for the PC… Press Enter. If nothing appears, the PC has no login prompt on its serial port yet: " +
                    "Set up everything adds one (switch \"Also allow login over USB serial\"), keyboard rescue can start one for you, or on the PC run:  sudo systemctl start serial-getty@ttyACM0"
                else "Turn on to give the PC a serial port. With a login prompt there, you get a shell on the PC even when its network or SSH is broken.",
                color = Frost.OnInkMuted)
            LazyColumn(state = state) { items(vm.serialLines.size) { Mono(vm.serialLines[it]) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostField("", input, { input = it }, Modifier.weight(1f), secret = hide, mono = true, placeholder = if (hide) "password" else "type, then Send")
            InkButton("Send", { vm.serialSend(input); input = "" }, enabled = vm.serialOn)
        }
        FrostSwitch(hide, { hide = it }, "Hide what I type (passwords)")
        if (vm.serialAiOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Open Rescue chat", openChat, Modifier.weight(1f))
            FrostOutlineButton("Stop AI here", { vm.serialStopAi() })
        } else InkButton("Let the AI work here", { scope.launch { if (vm.serialStartAi()) openChat() } }, Modifier.fillMaxWidth(), enabled = vm.serialOn)
        Muted("Log in first. The AI then runs its commands in this login, with the same approvals as over SSH; you see them here too.", color = Frost.Muted)
        Muted("Manual: what you type goes to the PC as-is, without Lifeline's safety checks.", Modifier.padding(bottom = 8.dp))
    }
}
