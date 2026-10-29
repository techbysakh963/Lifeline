package com.rodinrescue.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Frost splash: liquid metal under an ink scrim, the Lifeline mark settling in, a chrome wordmark and the tagline.
 * Continues the dark system splash (values-v31/themes.xml) so the launch has no white flash.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val mark = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
        delay(250)
        text.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(900)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Frost.Ink)) {
        LiquidMetal(Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f + 0.4f * mark.value })
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xE60B0D10), Color(0x800B0D10), Color(0xF20B0D10)))))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.graphicsLayer {
                val s = 0.7f + 0.3f * mark.value
                scaleX = s; scaleY = s; alpha = mark.value; rotationZ = -40f * (1f - mark.value)
            }) { LifelineMark(120.dp, onDark = true) }
            Column(Modifier.graphicsLayer { alpha = text.value; translationY = 24f * (1f - text.value) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Brand.NAME, style = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1.5).sp,
                    brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFC9D0D7), Color(0xFF8A939D), Color(0xFFE9EDF1)))))
                Text("When the PC won't boot,\nyour phone takes over.", fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 19.sp,
                    color = Frost.OnInkMuted, textAlign = TextAlign.Center)
            }
        }
        Text("open-source PC rescue", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
            .graphicsLayer { alpha = text.value * 0.8f }, fontFamily = Geist, fontSize = 12.sp, letterSpacing = 2.sp, color = Frost.Pewter)
    }
}
