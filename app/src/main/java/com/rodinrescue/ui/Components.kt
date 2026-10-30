package com.rodinrescue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Frost widget kit. Every screen builds from these so the look stays in one place.

val CardShape = RoundedCornerShape(24.dp)
val Pill = RoundedCornerShape(50)

/** White card on the frost ground; [dark] makes an ink card (used for things that need the user's attention). */
@Composable
fun FrostCard(modifier: Modifier = Modifier, dark: Boolean = false, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val m = if (dark) modifier.fillMaxWidth().shadow(18.dp, CardShape, ambientColor = Frost.Ink, spotColor = Frost.Ink).background(Frost.Ink, CardShape)
            else modifier.fillMaxWidth().background(Frost.Card, CardShape).border(1.dp, Frost.Line, CardShape)
    CompositionLocalProviderOnInk(dark) { Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
private fun CompositionLocalProviderOnInk(dark: Boolean, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else Frost.Ink, content = content)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, color = Frost.Slate)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, color: Color = Frost.Slate, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFFD6DBE0), size: Int = 12) =
    Text(text, modifier, color = color, fontFamily = GeistMono, fontSize = size.sp, lineHeight = (size + 6).sp)

/** Screen title in the display face. */
@Composable
fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, color = Frost.Ink)

// ── Buttons ──
private val btnHeight = 50.dp

@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    Button(onClick, modifier.heightIn(min = btnHeight), enabled = enabled, shape = Pill,
        colors = ButtonDefaults.buttonColors(containerColor = Frost.Ink, contentColor = Color.White, disabledContainerColor = Frost.Line2, disabledContentColor = Frost.Muted),
        contentPadding = PaddingValues(horizontal = 22.dp)) { BtnContent(text, icon) }

/** Chrome pill; for use on ink or liquid-metal surfaces. */
@Composable
fun ChromeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg = if (enabled) Frost.Chrome else Brush.verticalGradient(listOf(Color(0xFFB7BFC8), Color(0xFF9BA3AD)))
    Box(modifier.heightIn(min = btnHeight).clip(Pill).background(bg).clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Frost.Ink else Frost.Slate)
    }
}

@Composable
fun FrostOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onDark: Boolean = false, icon: ImageVector? = null) =
    OutlinedButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = Pill,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (onDark) Color(0xFF3A3F46) else Frost.Line2),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (onDark) Color.Transparent else Frost.Card, contentColor = if (onDark) Color.White else Frost.Ink),
        contentPadding = PaddingValues(horizontal = 18.dp)) { BtnContent(text, icon) }

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    TextButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = Pill, colors = ButtonDefaults.textButtonColors(contentColor = Frost.Slate)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }

@Composable
fun StopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Button(onClick, modifier.heightIn(min = 40.dp), shape = Pill, colors = ButtonDefaults.buttonColors(containerColor = Frost.BadBg, contentColor = Frost.Bad),
        contentPadding = PaddingValues(horizontal = 16.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun BtnContent(text: String, icon: ImageVector?) {
    if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** Round icon button (header actions). */
@Composable
fun CircleIconButton(icon: ImageVector, label: String, onClick: () -> Unit, dark: Boolean = false, size: Dp = 44.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(if (dark) Frost.Ink else Frost.Card).then(if (dark) Modifier else Modifier.border(1.dp, Frost.Line2, CircleShape))
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (dark) Color.White else Frost.Ink)
    }

// ── Badges ──
enum class Tone { Good, Warn, Bad, Neutral, Ink, Chrome }

@Composable
fun Badge(text: String, tone: Tone = Tone.Neutral, dot: Boolean = false) {
    val (bg, fg) = when (tone) {
        Tone.Good -> Frost.GoodBg to Frost.GoodInk
        Tone.Warn -> Frost.Warn to Frost.Ink
        Tone.Bad -> Frost.BadBg to Frost.Bad
        Tone.Neutral -> Color.Transparent to Frost.Slate
        Tone.Ink -> Frost.Ink to Color.White
        Tone.Chrome -> Color.Transparent to Frost.Ink
    }
    var m = Modifier.clip(Pill)
    m = when (tone) {
        Tone.Chrome -> m.background(Frost.Chrome)
        Tone.Neutral -> m.border(1.dp, Frost.Line2, Pill)
        else -> m.background(bg)
    }
    Row(m.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(if (tone == Tone.Good) Frost.Good else fg))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Geist)
    }
}

/** Small mono tag, e.g. a tool name. */
@Composable
fun Tag(text: String, onDark: Boolean = false) =
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(if (onDark) Frost.Ink2 else Frost.Bg).padding(horizontal = 8.dp, vertical = 4.dp),
        fontFamily = GeistMono, fontSize = 11.sp, color = if (onDark) Color(0xFFE3E7EB) else Frost.Ink2)

// ── Inputs ──
@Composable
fun FrostField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false,
               number: Boolean = false, singleLine: Boolean = true, mono: Boolean = false, placeholder: String? = null, maxLines: Int = if (singleLine) 1 else 6, small: Boolean = false) =
    OutlinedTextField(value, onChange, modifier, singleLine = singleLine, maxLines = maxLines,
        label = if (label.isNotEmpty()) ({ Text(label) }) else null,
        placeholder = placeholder?.let { { Text(it, color = Frost.Muted) } },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono || secret) GeistMono else Geist).let { if (small) it.copy(fontSize = 12.sp, lineHeight = 18.sp) else it },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Frost.Field, unfocusedContainerColor = Frost.Field, disabledContainerColor = Frost.Field,
            focusedBorderColor = Frost.Ink, unfocusedBorderColor = Frost.Line2, focusedLabelColor = Frost.Ink, unfocusedLabelColor = Frost.Slate, cursorColor = Frost.Ink),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text))

@Composable
fun FrostSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String) =
    // The whole row toggles (label included), announced as one switch.
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = Frost.Ink, checkedThumbColor = Color.White,
            uncheckedTrackColor = Frost.Line, uncheckedThumbColor = Frost.Pewter, uncheckedBorderColor = Frost.Line2))
    }

// ── Status ──
/** Compact status pill: a check when ready, an amber dot when not, nothing while unknown. Tappable when [onClick] is set. */
@Composable
fun StatusPill(text: String, ok: Boolean?, onClick: (() -> Unit)? = null) {
    var m = Modifier.clip(Pill).background(Frost.Card).border(1.dp, if (ok == false) Frost.Warn else Frost.Line, Pill)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ok) {
            true -> Icon(Icons.Filled.Check, "ready", Modifier.size(14.dp), tint = Frost.Good)
            false -> Box(Modifier.size(7.dp).clip(CircleShape).background(Frost.Warn))
            null -> {}
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Frost.Ink, fontFamily = Geist, maxLines = 1)
    }
}

/** Checklist row with a filled ink check, a spinning orb (in progress) or an empty ring. */
@Composable
fun StepRow(ok: Boolean?, title: String, detail: String, inProgress: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp).size(22.dp), contentAlignment = Alignment.Center) {
            when {
                inProgress -> MetalOrb(22.dp)
                ok == true -> Box(Modifier.size(22.dp).clip(CircleShape).background(Frost.Ink), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Color.White) }
                ok == false -> Box(Modifier.size(22.dp).clip(CircleShape).background(Frost.Warn))
                else -> Box(Modifier.size(22.dp).clip(CircleShape).border(1.dp, Frost.Line2, CircleShape))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted(detail)
        }
    }
}

/** Segmented progress bar: [done] solid segments, then one fading "active" segment. */
@Composable
fun SegmentProgress(total: Int, done: Int, modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val b = when {
                i < done -> Brush.linearGradient(listOf(Frost.Ink, Frost.Ink))
                i == done -> Brush.horizontalGradient(listOf(Frost.Ink, Frost.Pewter))
                else -> Brush.linearGradient(listOf(Frost.Line2, Frost.Line2))
            }
            Box(Modifier.weight(1f).height(4.dp).clip(Pill).background(b))
        }
    }

/** Ink code/terminal block. */
@Composable
fun TerminalBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Frost.Ink).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)

/** Liquid-metal hero card with a dark scrim at the bottom so white text stays readable. */
@Composable
fun LiquidHero(modifier: Modifier = Modifier, height: Dp = 300.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().height(height).shadow(24.dp, shape, ambientColor = Frost.Ink, spotColor = Frost.Ink).clip(shape)) {
        LiquidMetal(Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color(0x000A0C0F), 1f to Color(0xD90A0C0F))))
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Translucent badge for use on the liquid hero. */
@Composable
fun GlassBadge(text: String, dotColor: Color) =
    Row(Modifier.clip(Pill).background(Color(0x990B0D10)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Geist, maxLines = 1)
    }

// ── Navigation ──
/** Height the floating nav bar covers at the bottom; screens pad by this so content can scroll behind the bar. */
val LocalNavSpace = androidx.compose.runtime.compositionLocalOf { 0.dp }
val NavBarHeight = 78.dp

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating ink pill navigation; the selected item gets a white circle. */
@Composable
fun PillNavBar(items: List<NavItem>, selected: String?, onSelect: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.shadow(20.dp, Pill, ambientColor = Frost.Ink, spotColor = Frost.Ink).clip(Pill).background(Frost.Ink).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { it ->
                val sel = it.route == selected
                Box(Modifier.size(48.dp).clip(CircleShape).background(if (sel) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = it.label) { onSelect(it.route) }, contentAlignment = Alignment.Center) {
                    Icon(it.icon, it.label, Modifier.size(22.dp), tint = if (sel) Frost.Ink else Frost.Pewter)
                }
            }
        }
    }
}

/** Header row shared by the tab screens. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Title(title)
            subtitle?.let { Muted(it) }
        }
        actions()
    }

/** Fades the top [height] of a scrolling list to transparent so items slide out softly instead of hitting a hard edge. */
fun Modifier.fadeTop(height: Dp = 20.dp) = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = (height.toPx() / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
