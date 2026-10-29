package com.rodinrescue.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rodinrescue.R

/** User-visible product name. One place to change it. */
object Brand { const val NAME = "Lifeline" }

/** "Frost" palette: silver ground, white cards, ink type, liquid-metal accents. */
object Frost {
    val Ink = Color(0xFF0B0D10)
    val Ink2 = Color(0xFF1A1D21)
    val Slate = Color(0xFF4B535D)
    val Muted = Color(0xFF6B737D)
    val Pewter = Color(0xFF9BA3AD)
    val Line = Color(0xFFDDE1E6)
    val Line2 = Color(0xFFD3D8DE)
    val Bg = Color(0xFFECEFF2)
    val Card = Color.White
    val Field = Color(0xFFF5F7F9)
    val OnInkMuted = Color(0xFFB7BFC8)
    val Good = Color(0xFF16A34A)
    val GoodBg = Color(0xFFDCFCE7)
    val GoodInk = Color(0xFF166534)
    val Warn = Color(0xFFFCD34D)
    val Bad = Color(0xFF9B1C1C)
    val BadBg = Color(0xFFFDE2E2)
    val BadOnInk = Color(0xFFFCA5A5)
    val Chrome = Brush.verticalGradient(listOf(Color.White, Color(0xFFC9D0D7)))
    val ChromeDiag = Brush.linearGradient(listOf(Color.White, Color(0xFF59626D), Color(0xFFF2F5F8), Color(0xFF2A2F35)))
}

// Static weights cut from the OFL variable fonts (fontTools varLib.instancer). Compose caches a resource font
// per file and ignores per-weight variation settings, so one variable file would render every weight as 400.
val Geist = FontFamily(Font(R.font.geist_regular, FontWeight.Normal), Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold), Font(R.font.geist_bold, FontWeight.Bold))
val GeistMono = FontFamily(Font(R.font.geist_mono_regular, FontWeight.Normal), Font(R.font.geist_mono_medium, FontWeight.Medium))
val Unbounded = FontFamily(Font(R.font.unbounded_semibold, FontWeight.SemiBold), Font(R.font.unbounded_bold, FontWeight.Bold))

private val base = Typography()
private fun TextStyle.g() = copy(fontFamily = Geist)
private val FrostType = Typography(
    displaySmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelSmall = base.labelSmall.g(),
    displayLarge = base.displayLarge.g(), displayMedium = base.displayMedium.g(),
    headlineLarge = base.headlineLarge.g(), headlineMedium = base.headlineMedium.g()
)

@Composable
fun RodinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Frost.Ink, onPrimary = Color.White, secondary = Frost.Slate, onSecondary = Color.White,
            background = Frost.Bg, onBackground = Frost.Ink, surface = Frost.Card, onSurface = Frost.Ink,
            surfaceVariant = Frost.Field, onSurfaceVariant = Frost.Slate, outline = Frost.Line2, outlineVariant = Frost.Line,
            surfaceContainer = Frost.Card, surfaceContainerHigh = Frost.Card, surfaceContainerHighest = Frost.Field,
            error = Frost.Bad, errorContainer = Frost.BadBg, secondaryContainer = Frost.Line, onSecondaryContainer = Frost.Ink
        ),
        typography = FrostType,
        content = content
    )
}
