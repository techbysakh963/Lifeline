package com.rodinrescue.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape

/**
 * Liquid-metal surface. Android 13+ renders an AGSL shader (domain-warped chrome bands with a moving
 * specular highlight); older versions fall back to a slowly rotating, blurred chrome sweep gradient.
 */
private const val LIQUID_METAL_AGSL = """
uniform float2 size;
uniform float time;
half4 main(float2 fragCoord) {
    float2 p = (fragCoord - 0.5 * size) / min(size.x, size.y);
    float t = time * 0.22;
    for (int i = 1; i < 5; i++) {
        float fi = float(i);
        p += float2(0.55 / fi * sin(fi * p.y * 2.2 + t + 0.3 * fi),
                    0.55 / fi * cos(fi * p.x * 2.0 + t * 1.3 + 0.5 * fi));
    }
    float v = sin(p.x * 3.0 + p.y * 2.0 + t * 0.5);
    float b = pow(0.5 + 0.5 * sin(v * 5.0 + 1.0), 1.5);
    float spec = pow(max(0.0, sin(v * 7.0 + 0.6)), 28.0);
    float3 dark = float3(0.07, 0.08, 0.095);
    float3 light = float3(0.93, 0.95, 0.97);
    float3 col = mix(dark, light, b) + spec * 0.55;
    col = mix(col, col * float3(0.97, 0.99, 1.03), 0.5);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private val chromeStops = listOf(
    Color(0xFF1A1D21), Color(0xFFF5F7F9), Color(0xFF7C858F), Color.White,
    Color(0xFF2D3238), Color(0xFFC7CED5), Color(0xFF4A515A), Color(0xFFEEF1F4), Color(0xFF1A1D21)
)

@Composable
private fun rememberSeconds(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    return t
}

@RequiresApi(33)
@Composable
private fun ShaderMetal(modifier: Modifier) {
    val shader = remember { RuntimeShader(LIQUID_METAL_AGSL) }
    val time by rememberSeconds()
    Box(modifier.drawBehind {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time)
        drawRect(ShaderBrush(shader))
    })
}

@Composable
private fun SweepMetal(modifier: Modifier, periodMs: Int) {
    val angle by rememberInfiniteTransition(label = "metal").animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "a")
    Box(modifier.clip(RectangleShape)) {
        Canvas(Modifier.matchParentSize().scale(1.7f).rotate(angle).blur(18.dp)) { drawRect(Brush.sweepGradient(chromeStops)) }
    }
}

@Composable
fun LiquidMetal(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= 33) ShaderMetal(modifier) else SweepMetal(modifier, 26_000)
}

/** Small spinning chrome sphere: the AI's presence. Spins while [active], rests otherwise. */
@Composable
fun MetalOrb(size: Dp = 30.dp, active: Boolean = true) {
    val angle = if (active) rememberInfiniteTransition(label = "orb").animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "a").value else 40f
    Box(Modifier.size(size).clip(CircleShape)) {
        Canvas(Modifier.matchParentSize().scale(1.5f).rotate(angle).blur(2.dp)) { drawRect(Brush.sweepGradient(chromeStops)) }
    }
}

/** Lifeline mark: a ring buoy (chrome, four ink bands) around a terminal prompt. */
@Composable
fun LifelineMark(size: Dp = 30.dp, onDark: Boolean = false) {
    val band = if (onDark) Frost.Ink else Frost.Bg
    val prompt = if (onDark) Color(0xFFE9EDF1) else Frost.Ink
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 120f
        val c = Offset(60f * u, 60f * u)
        val ring = Brush.linearGradient(
            listOf(Color(0xFF5B6470), Frost.Ink, Color(0xFF7C858F)).let { if (onDark) listOf(Color.White, Color(0xFF9BA3AD), Color(0xFF2D3238), Color(0xFFE9EDF1)) else it },
            start = Offset.Zero, end = Offset(this.size.width, this.size.height))
        drawCircle(ring, radius = 40f * u, center = c, style = Stroke(18f * u))
        for (k in 0 until 4) drawArc(band, 45f + 90f * k - 9f, 18f, false, topLeft = Offset(20f * u, 20f * u),
            size = androidx.compose.ui.geometry.Size(80f * u, 80f * u), style = Stroke(19f * u))
        val path = Path().apply { moveTo(50f * u, 51f * u); lineTo(59f * u, 60f * u); lineTo(50f * u, 69f * u); moveTo(63f * u, 70f * u); lineTo(73f * u, 70f * u) }
        drawPath(path, prompt, style = Stroke(5.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
