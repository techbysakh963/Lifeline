package com.rodinrescue.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint / face / screen-lock gate (the phone's own lock; nothing is stored by the app). */
object AppLock {
    // STRONG|DEVICE_CREDENTIAL isn't supported on Android 10 (API 29); WEAK|DEVICE_CREDENTIAL is.
    private val authenticators = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun available(a: FragmentActivity) = BiometricManager.from(a).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(a: FragmentActivity, title: String, onSuccess: () -> Unit) {
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(authenticators).build())
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(Frost.Ink)
        .clickable(remember { MutableInteractionSource() }, null) {}) {   // swallow touches to the app underneath
        LiquidMetal(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xD90B0D10)))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LifelineMark(88.dp, onDark = true)
            Text("${Brand.NAME} is locked", fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = androidx.compose.ui.graphics.Color.White)
            Text("Your saved computers and their keys are behind your phone's lock.", fontFamily = Geist, fontSize = 13.sp, color = Frost.OnInkMuted)
            ChromeButton("Unlock", onUnlock, Modifier.fillMaxWidth())
        }
    }
}
