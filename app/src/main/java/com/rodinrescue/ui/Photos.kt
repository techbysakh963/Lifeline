package com.rodinrescue.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bottom padding that keeps an input row above the on-screen keyboard (and above the floating nav bar otherwise). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.aboveKeyboard(navSpace: Dp = LocalNavSpace.current): Modifier =
    if (WindowInsets.isImeVisible) this.imePadding() else this.padding(bottom = navSpace)

private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}

/** A screen photo; tap to see it full screen. [bytes] or [file] (a photo saved with the conversation). */
@Composable
fun PhotoThumb(bytes: ByteArray? = null, file: File? = null, modifier: Modifier = Modifier, height: Dp = 150.dp, label: String? = null) {
    val bmp by produceState<Bitmap?>(null, bytes, file) {
        value = withContext(Dispatchers.IO) { (bytes ?: file?.takeIf { it.exists() }?.readBytes())?.let { decode(it, 640) } }
    }
    var full by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(Frost.Ink).clickable(enabled = bmp != null) { full = true }) {
        bmp?.let { Image(it.asImageBitmap(), "Photo of the PC's screen", Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (file != null && !file.exists()) "Photo not kept" else "…", Modifier.align(Alignment.Center), color = Frost.OnInkMuted)
        if (label != null) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { GlassBadge(label, Color(0xFF4ADE80)) }
    }
    if (full) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        val big by produceState<Bitmap?>(null) { value = withContext(Dispatchers.IO) { (bytes ?: file?.readBytes())?.let { decode(it, 2048) } } }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = false }, contentAlignment = Alignment.Center) {
            big?.let { Image(it.asImageBitmap(), "Photo of the PC's screen", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
    }
}
