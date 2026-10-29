package com.rodinrescue.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rodinrescue.R

/** Donation target. The QR in res/drawable-nodpi/donate_usdt_qr.png encodes exactly this (DonateTest checks every copy). */
object Donate {
    const val ADDRESS = "0xfd64cbc4935dcce5b719e223469a84c83650b010"
    const val ASSET = "USDT"
    const val NETWORK = "Ethereum (ERC-20)"
}

/** Coffee mark: a chrome cup with an ink rim and two steam curls, drawn like [LifelineMark] on a 120-unit grid. */
@Composable
fun CoffeeMark(size: Dp = 22.dp, onDark: Boolean = false) {
    val ink = if (onDark) Color(0xFFE9EDF1) else Frost.Ink
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 120f
        val body = Brush.linearGradient(
            if (onDark) listOf(Color.White, Color(0xFF9BA3AD), Color(0xFF2D3238), Color(0xFFE9EDF1))
            else listOf(Color(0xFF7C858F), Frost.Ink, Color(0xFF5B6470), Frost.Ink2),
            start = Offset(22f * u, 52f * u), end = Offset(84f * u, 104f * u))
        // Cup: flat rim, rounded base.
        val cup = Path().apply {
            moveTo(22f * u, 54f * u); lineTo(86f * u, 54f * u); lineTo(80f * u, 92f * u)
            quadraticBezierTo(78f * u, 104f * u, 66f * u, 104f * u); lineTo(42f * u, 104f * u)
            quadraticBezierTo(30f * u, 104f * u, 28f * u, 92f * u); close()
        }
        drawPath(cup, body)
        // Handle.
        drawArc(body, -80f, 160f, false, topLeft = Offset(74f * u, 60f * u), size = Size(26f * u, 26f * u), style = Stroke(8f * u, cap = StrokeCap.Round))
        // Chrome highlight on the rim.
        drawRoundRect(Frost.Chrome, topLeft = Offset(20f * u, 50f * u), size = Size(68f * u, 8f * u), cornerRadius = CornerRadius(4f * u))
        // Steam.
        for (x in listOf(44f, 62f)) {
            val s = Path().apply { moveTo(x * u, 42f * u); cubicTo((x - 8f) * u, 34f * u, (x + 8f) * u, 26f * u, x * u, 16f * u) }
            drawPath(s, ink, style = Stroke(5.5f * u, cap = StrokeCap.Round))
        }
    }
}

/** Header button with the coffee mark; same shape as [CircleIconButton]. */
@Composable
fun CoffeeButton(onClick: () -> Unit, size: Dp = 44.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(Frost.Card).border(1.dp, Frost.Line2, CircleShape)
        .clickable(role = Role.Button, onClickLabel = "Buy me a coffee", onClick = onClick), contentAlignment = Alignment.Center) {
        CoffeeMark(22.dp)
    }

/** "Buy me a coffee" dialog: QR, address, copy, and a clear network warning. */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Frost.Card).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CoffeeMark(44.dp)
            Text("Buy me a coffee", style = MaterialTheme.typography.headlineSmall, color = Frost.Ink)
            Muted("${Brand.NAME} is free and open source. If it saved your machine, a coffee keeps it going.", Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
            Image(painterResource(R.drawable.donate_usdt_qr), "QR code of the ${Donate.ASSET} address",
                Modifier.size(220.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Frost.Line, RoundedCornerShape(18.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Badge(Donate.ASSET, Tone.Ink); Badge(Donate.NETWORK, Tone.Neutral) }
            Text(Donate.ADDRESS, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Frost.Field).padding(12.dp),
                fontFamily = GeistMono, style = MaterialTheme.typography.bodySmall, color = Frost.Ink, textAlign = TextAlign.Center)
            InkButton("Copy address", {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("${Donate.ASSET} address", Donate.ADDRESS))
                Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth(), icon = Icons.Outlined.ContentCopy)
            Muted("Send only ${Donate.ASSET} on ${Donate.NETWORK}. Other coins or networks can't be recovered.", color = Frost.Bad)
            GhostButton("Close", onDismiss)
        }
    }
}
