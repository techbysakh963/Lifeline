package com.rodinrescue.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rodinrescue.RodinViewModel
import com.rodinrescue.usb.HidController

/**
 * Type on PC: write, paste or dictate (the phone keyboard's mic) a note, check it, tap Send, and it is typed into
 * whatever window has focus on the PC. Needs only the USB keyboard: no AI, no SSH. New lines are typed as Enter.
 */
@Composable
fun VoiceTypeScreen(vm: RodinViewModel, back: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.kbRefresh(); focus.requestFocus() }   // focus opens the phone keyboard
    DisposableEffect(Unit) { vm.kbScreenOpened(); onDispose { vm.kbScreenClosed() } }   // keyboard auto-off
    val on = vm.kbState == HidController.State.ON
    val typing = vm.voiceProgress

    Column(Modifier.fillMaxSize().statusBarsPadding().aboveKeyboard().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back)
            Column(Modifier.weight(1f)) {
                Text("Type on PC", style = MaterialTheme.typography.titleMedium)
                Muted("Write, paste or 🎤 dictate", maxLines = 1)
            }
            if (on) Badge("Keyboard on", Tone.Good, dot = true) else Badge("Keyboard off", Tone.Neutral)
        }
        if (!on) FrostCard {
            Muted("Plug the phone into the PC and turn the USB keyboard on. The text goes to whatever window has focus on the PC.")
            if (vm.kbState == HidController.State.ERROR || vm.kbState == HidController.State.UNSUPPORTED) Muted(vm.kbMessage, color = Frost.Bad)
            InkButton(if (vm.kbBusy) "Turning on…" else "Turn keyboard on", { vm.kbToggle(true) }, Modifier.fillMaxWidth(), enabled = !vm.kbBusy)
        }

        OutlinedTextField(vm.voiceNote, vm::voiceEdit, Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
            enabled = typing == null,
            placeholder = { Text("Write, paste, or tap 🎤 on the phone keyboard and speak. Check it, then Send: it's typed on the PC. A new line is typed as Enter.", color = Frost.Muted) },
            textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Frost.Card, unfocusedContainerColor = Frost.Card, disabledContainerColor = Frost.Card,
                focusedBorderColor = Frost.Ink, unfocusedBorderColor = Frost.Line2, cursorColor = Frost.Ink))

        if (typing != null) {
            LinearProgressIndicator({ if (typing.second == 0) 0f else typing.first / typing.second.toFloat() }, Modifier.fillMaxWidth(), color = Frost.Ink, trackColor = Frost.Line)
            Muted("Typing on the PC… ${typing.first} / ${typing.second}")
        } else if (vm.voiceMessage.isNotBlank()) Muted(vm.voiceMessage, color = if ("Nothing was sent" in vm.voiceMessage || "Stopped:" in vm.voiceMessage) Frost.Bad else Frost.Slate)

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typing != null) StopButton("Stop typing", { vm.voiceStop() }, Modifier.weight(1f))
            else {
                if (vm.voiceCanSkip) InkButton("Send without them", { vm.voiceSendWithoutThem() }, Modifier.weight(1f), enabled = on && !vm.kbBusy)
                else InkButton("Send to PC", { vm.voiceSend() }, Modifier.weight(1f), enabled = on && !vm.kbBusy && vm.voiceNote.isNotEmpty())
                FrostOutlineButton("Clear", { vm.voiceClear() }, enabled = vm.voiceNote.isNotEmpty() || vm.voiceMessage.isNotEmpty())
            }
        }
    }
}
