package com.rodinrescue.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.rodinrescue.vision.ScreenArea
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rodinrescue.RodinViewModel
import com.rodinrescue.usb.HidController
import com.rodinrescue.vision.CameraEyes

/**
 * Keyboard rescue: for a PC that SSH can't reach (BIOS, GRUB, emergency shell, no network).
 * Rear camera on the PC's screen (the AI's eyes), USB cable = keyboard (its hands); the user approves every change.
 * The phone is either on a stand (the AI takes photos itself; the camera is a small live "eye" in the header, tap to open)
 * or in the user's hand (every look is a photo the user takes on the photo card). On the card the
 * user fits a frame to the PC's screen once: later photos are cut to it and straightened (sharper for the AI).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardRescueScreen(vm: RodinViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { vm.enterKeyboardMode(); if (!granted) ask.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) { vm.kbScreenOpened(); onDispose { vm.kbScreenClosed() } }   // keyboard auto-off

    val controller = remember { LifecycleCameraController(ctx).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS) } }
    DisposableEffect(granted) {
        if (granted) { controller.bindToLifecycle(owner); vm.camera = CameraEyes(ctx, controller) }
        onDispose { vm.camera = null; vm.cardSkip(); controller.unbind() }
    }

    var input by remember { mutableStateOf("") }
    var showCam by remember { mutableStateOf(false) }
    val req = vm.photoRequest
    val state = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.streaming) { if (vm.chat.isNotEmpty()) state.animateScrollToItem(vm.chat.size) }

    @Composable fun Header() = Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { vm.leaveKeyboardScreen(); back() })
        Column(Modifier.weight(1f)) {
            Text("Keyboard rescue", style = MaterialTheme.typography.titleMedium)
            Muted("${vm.diagnosticStatus} · ${vm.usage.promptTokens}/${vm.usage.completionTokens} tokens", maxLines = 1)
        }
        // The eye: a tiny live view of what the camera sees; tap to open the camera panel.
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Frost.Ink)
            .border(1.5.dp, if (granted) Color(0xFF4ADE80) else Frost.Warn, RoundedCornerShape(10.dp))
            .clickable(enabled = granted, role = Role.Button, onClickLabel = "Show the camera") { showCam = !showCam }, contentAlignment = Alignment.Center) {
            if (granted && !showCam && req == null) CameraPreview(controller, fit = false, modifier = Modifier.matchParentSize())
            else Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        AutoApproveChip(vm)
        if (vm.running) StopButton("Stop", { vm.stopAll() })
    }

    // Compact switches; they wrap instead of scrolling, so none is ever hidden (Take control must never be off-screen).
    @Composable fun Switches() = FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vm.kbState == HidController.State.ON) {
            // Take control: the user types (e.g. a password) on the PC through the phone; the AI waits and never sees it.
            Row(Modifier.clip(Pill).background(if (vm.userControl) Frost.Ink else Color(0xFFFFEDD5)).clickable(role = Role.Button) { if (vm.userControl) vm.giveControlBack() else vm.takeControl() }
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (vm.userControl) Color(0xFF4ADE80) else Color(0xFFF97316)))
                Text(if (vm.userControl) "Give back to AI" else "Take control", fontSize = 13.sp, color = if (vm.userControl) Color.White else Frost.Ink)
            }
        } else ChromeButton(if (vm.kbBusy) "Turning on…" else "Keyboard on", { vm.kbToggle(true) }, enabled = !vm.kbBusy)
        Seg(listOf(false to "On a stand", true to "In my hand"), vm.kbHandheld) { vm.updateKbHandheld(it) }
        Seg(listOf(false to "Linux", true to "Windows"), vm.kbWindows) { vm.kbWindows = it }
    }

    /** Camera access, the camera panel, the photo card or the control panel (whichever applies). [camHeight]: the camera's box. */
    @Composable fun Panels(camHeight: androidx.compose.ui.unit.Dp) {
        if (!granted) FrostCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Camera access needed", style = MaterialTheme.typography.titleSmall)
            Muted("The AI sees the PC's screen only through photos from this camera. They're sent to the AI brain you chose and saved with the conversation on this phone.")
            InkButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) })
        } else if (vm.userControl) ControlPanel(vm)
        else if (req != null) PhotoCard(vm, req, controller, camHeight)
        else if (showCam) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(camHeight).clip(RoundedCornerShape(22.dp)).background(Frost.Ink)) {
                CameraPreview(controller, fit = true, modifier = Modifier.matchParentSize())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(if (vm.screenArea == null) "Set the screen area" else "Change the screen area", { vm.setScreenArea(); showCam = false })
                if (vm.screenArea != null) GhostButton("Use the whole photo", { vm.screenArea = null })
            }
        }
    }

    @Composable fun Chat(modifier: Modifier) = LazyColumn(modifier.fadeTop().padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp)) {
        if (vm.chat.isEmpty()) item {
            FrostCard {
                Text("When SSH can't reach the PC", style = MaterialTheme.typography.titleSmall)
                Muted(if (vm.kbHandheld) "Hold the phone toward the PC's screen. Tell the AI what's wrong: when it needs to see, a card opens the camera and you take the photo. Anything that changes the PC waits for your tap."
                    else "Stand the phone so the PC's screen fills the frame (sideways is sharper). Tell the AI what's wrong: it takes photos itself, moves through menus and runs read-only commands; anything that changes the PC waits for your tap. Tap the camera at the top to set the screen area once: the AI then gets sharper photos.")
                if (vm.kbMessage.isNotBlank()) Muted(vm.kbMessage, color = if (vm.kbState == HidController.State.ERROR) Frost.Bad else Frost.Muted)
            }
        }
        items(vm.chat) { ChatRow(it, vm::photoFile) }
        if (vm.streaming.isNotEmpty()) item { AssistantCard(vm.streaming, thinking = true) }
        if (vm.running && vm.streaming.isEmpty() && req == null) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetalOrb(22.dp); Muted("Working…") } }
    }

    @Composable fun Composer() {
        vm.attachedPhoto?.let { p ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoThumb(bytes = p, modifier = Modifier.width(120.dp), height = 68.dp)
                Muted("Photo attached to your next message", Modifier.weight(1f))
                GhostButton("Remove", { vm.clearAttachment() })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 📷: send the screen as it is now with the next message
            Box(Modifier.size(48.dp).clip(CircleShape).background(Frost.Card).border(1.dp, if (vm.attachedPhoto != null) Frost.Ink else Frost.Line2, CircleShape)
                .clickable(enabled = granted && !vm.running && !vm.attaching && req == null, role = Role.Button, onClickLabel = "Attach a photo of the screen") { vm.attachPhoto() },
                contentAlignment = Alignment.Center) {
                if (vm.attaching && req == null) CircularProgressIndicator(Modifier.size(20.dp), color = Frost.Ink, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.PhotoCamera, "Attach a photo of the screen", tint = if (granted) Frost.Ink else Frost.Pewter)
            }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("What's wrong?", color = Frost.Muted) }, maxLines = 4,
                shape = Pill, textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Frost.Card, unfocusedContainerColor = Frost.Card,
                    focusedBorderColor = Frost.Ink, unfocusedBorderColor = Frost.Line2, cursorColor = Frost.Ink))
            val canSend = !vm.running && input.isNotBlank() && granted
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (canSend) Frost.Ink else Frost.Line2)
                .clickable(enabled = canSend, role = Role.Button, onClickLabel = "Send") { vm.sendKeyboard(input); input = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Send", tint = if (canSend) Color.White else Frost.Muted)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().aboveKeyboard()) {
        val h = maxHeight
        if (maxWidth > h) {
            // Sideways (so the bars don't cover the chat): camera and controls on the left, the conversation on the right.
            val camH = (h - 250.dp).coerceAtLeast(140.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.48f).fillMaxHeight().verticalScroll(rememberScrollState())) { Header(); Switches(); Panels(camH) }
                Column(Modifier.weight(0.52f).fillMaxHeight()) { Chat(Modifier.weight(1f)); Composer() }
            }
        } else Column(Modifier.fillMaxSize()) {
            Header(); Switches(); Panels(if (req != null) (h * 0.36f).coerceIn(200.dp, 320.dp) else 200.dp)
            Chat(Modifier.weight(1f)); Composer()
        }
    }
}

/**
 * "Take control": the user types a password or anything private on the PC through the phone's USB
 * keyboard. The text goes straight to the PC: it is not saved, not in the chat or audit log, not sent to the AI, and the
 * field is cleared after typing. The AI waits (its keys and photos pause) until control is given back.
 */
@Composable
private fun ControlPanel(vm: RodinViewModel) {
    var secret by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var enter by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    FrostCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), dark = true, padding = 14.dp) {
        Text("You have the keyboard", style = MaterialTheme.typography.titleSmall, color = Color.White)
        Muted("Type a password or anything private: it goes straight to the PC. Lifeline doesn't save it and the AI never sees it. The AI waits until you give control back.", color = Frost.OnInkMuted)
        OutlinedTextField(secret, { secret = it; msg = null }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Password or text for the PC", color = Frost.OnInkMuted) },
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
            shape = Pill, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            trailingIcon = { Text(if (show) "Hide" else "Show", Modifier.clickable { show = !show }.padding(horizontal = 12.dp), color = Frost.OnInkMuted, fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Frost.OnInkMuted, cursorColor = Color.White))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChromeButton(if (enter) "Type + Enter" else "Type", {
                vm.typeHidden(secret, enter) { err -> msg = err; if (err == null) { secret = ""; sent = true } }
            }, Modifier.weight(1f), enabled = secret.isNotEmpty())
            Text(if (enter) "Enter: on" else "Enter: off", Modifier.clip(Pill).clickable { enter = !enter }.padding(horizontal = 10.dp, vertical = 8.dp), color = Frost.OnInkMuted, fontSize = 13.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("enter" to "Enter", "backspace" to "⌫", "esc" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓", "left" to "←", "right" to "→").forEach { (k, label) ->
                Text(label, Modifier.clip(Pill).background(Color(0x33FFFFFF)).clickable(role = Role.Button) { vm.controlKey(k) }.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp)
            }
        }
        msg?.let { Text(it, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall) }
        if (sent && msg == null) Muted("Typed on the PC. If the PC shows what you typed, the AI's next photo will see it.", color = Frost.OnInkMuted)
        ChromeButton("Give back to AI", { vm.giveControlBack() }, Modifier.fillMaxWidth())
    }
}

/** The live camera. Only one is shown at a time: the controller drives a single preview. [fit] = the whole frame, no cropping. */
@Composable
private fun CameraPreview(controller: LifecycleCameraController, fit: Boolean, modifier: Modifier = Modifier) =
    AndroidView({ c -> PreviewView(c).apply { this.controller = controller; scaleType = if (fit) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER } }, modifier)

/** A two-way pill switch. */
@Composable
private fun <T> Seg(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) =
    Row(Modifier.clip(Pill).background(Frost.Field).padding(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Text(label, Modifier.clip(Pill).background(if (on) Frost.Ink else Color.Transparent).clickable(role = Role.RadioButton) { onPick(v) }
                .padding(horizontal = 12.dp, vertical = 6.dp), color = if (on) Color.White else Frost.Ink, fontSize = 13.sp)
        }
    }

/**
 * The photo card: live camera → the user takes the photo → fits the frame to the PC's screen on that still photo (exact:
 * the corners are in the photo's own pixels) → Send. The frame is kept for the next photos.
 */
@Composable
private fun PhotoCard(vm: RodinViewModel, req: RodinViewModel.PhotoRequest, controller: LifecycleCameraController, camHeight: androidx.compose.ui.unit.Dp) {
    val photo = vm.cardPhoto
    val purpose = remember(vm.chat.size) {
        (vm.chat.lastOrNull { it is com.rodinrescue.ChatItem.ToolCallItem } as? com.rodinrescue.ChatItem.ToolCallItem)
            ?.takeIf { it.name == "look_at_screen" || it.name == "wait_for_screen" }
            ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it.args).let { j -> (j as kotlinx.serialization.json.JsonObject)["purpose"]?.toString()?.trim('"') } }.getOrNull() }
    }
    FrostCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), padding = 14.dp) {
        Text(if (req.forAi) "The AI wants to see the screen" else "Photo of the screen", style = MaterialTheme.typography.titleSmall)
        Muted(if (req.forAi && purpose != null && vm.kbHandheld) "Why: $purpose" else req.why)
        Box(Modifier.fillMaxWidth().height(camHeight).clip(RoundedCornerShape(18.dp)).background(Frost.Ink)) {
            if (photo == null) CameraPreview(controller, fit = true, modifier = Modifier.matchParentSize())
            else AreaEditor(photo, vm.cardArea, { vm.cardArea = it }, Modifier.matchParentSize())
        }
        if (photo == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton(if (vm.cardBusy) "Taking…" else "Take photo", { vm.cardTake() }, Modifier.weight(1f), enabled = !vm.cardBusy, icon = Icons.Outlined.PhotoCamera)
                GhostButton("Skip", { vm.cardSkip() })
            }
            Muted("Sharper: turn the phone sideways, fill the frame with the screen, no light behind you.", color = Frost.Muted)
        } else {
            Muted("Drag the green dots onto the corners of the PC's screen.", color = Frost.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton(if (req.forAi) "Send" else "Use", { vm.cardSend(vm.cardArea) }, Modifier.weight(1f), enabled = !vm.cardBusy && vm.cardArea.valid())
                GhostButton("Whole photo", { vm.cardSend(null) }, enabled = !vm.cardBusy)
                GhostButton("Retake", { vm.cardRetake() })
            }
        }
    }
}

/** The still photo with the screen frame: four corner dots to drag. */
@Composable
private fun AreaEditor(photo: android.graphics.Bitmap, area: ScreenArea, onChange: (ScreenArea) -> Unit, modifier: Modifier) {
    val img = remember(photo) { photo.asImageBitmap() }
    val cur by rememberUpdatedState(area)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier) {
        val bw = constraints.maxWidth.toFloat(); val bh = constraints.maxHeight.toFloat()
        val s = minOf(bw / photo.width, bh / photo.height)
        val dw = photo.width * s; val dh = photo.height * s; val ox = (bw - dw) / 2; val oy = (bh - dh) / 2
        val green = Color(0xFF4ADE80)
        Canvas(Modifier.fillMaxSize().pointerInput(photo, bw, bh) {
            var drag = -1
            detectDragGestures(
                onDragStart = { o ->
                    val pts = cur.corners.map { Offset(ox + it.x * dw, oy + it.y * dh) }
                    val near = pts.withIndex().minByOrNull { (it.value - o).getDistance() }
                    drag = near?.takeIf { (it.value - o).getDistance() < 56.dp.toPx() }?.index ?: -1
                },
                onDragEnd = { drag = -1 }, onDragCancel = { drag = -1 },
                onDrag = { c, _ -> if (drag >= 0) { c.consume(); change(cur.with(drag, ScreenArea.P((c.position.x - ox) / dw, (c.position.y - oy) / dh))) } })
        }) {
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
            val pts = cur.corners.map { Offset(ox + it.x * dw, oy + it.y * dh) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, if (cur.valid()) green else Color(0xFFF87171), style = Stroke(3.dp.toPx()))
            pts.forEach { drawCircle(Color.White, 13.dp.toPx(), it); drawCircle(green, 10.dp.toPx(), it) }
        }
    }
}
