package com.rodinrescue.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import com.rodinrescue.ai.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * The AI's eyes: still photos from the phone's rear camera (bound to the rescue screen). [capture] keeps the photo at
 * up to [FULL] px, so [screen] can cut the PC's screen out at full sharpness ([ScreenArea]) before shrinking it for the
 * AI; without an area the whole frame is sent (≤1280 px), as before. Plus a tiny brightness fingerprint for the stuck guard.
 */
class CameraEyes(private val ctx: Context, private val controller: LifecycleCameraController) {
    /** One background thread for the frames [watch] looks at. */
    private val analysis = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "lifeline-frames").apply { isDaemon = true } }

    /** An upright photo at up to [FULL] px on its long side. */
    suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        controller.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val r = runCatching { upright(image) }
                image.close()
                r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            override fun onError(e: ImageCaptureException) { cont.resumeWithException(IllegalStateException("camera: ${e.message}", e)) }
        })
    }

    /** What the AI gets from [photo]: the straightened screen [area] (≤1600 px), or the whole frame (≤1280 px). */
    suspend fun screen(photo: Bitmap, area: ScreenArea?): Screen = withContext(Dispatchers.Default) {
        val img = area?.takeIf { it.valid() }?.let { straighten(photo, it, 1600) } ?: scaled(photo, 1280)
        Screen(jpeg(img), signature(img))
    }

    /**
     * Watches the camera's live frames (low resolution, CameraX analysis) around [action] and returns once [Settle] says
     * the screen has reacted and is still. Frames are compared with each other only, never sent anywhere.
     */
    suspend fun <T> watch(quietMs: Long, maxMs: Long, action: suspend () -> T): T {
        val frames = kotlinx.coroutines.channels.Channel<IntArray>(kotlinx.coroutines.channels.Channel.CONFLATED)
        withContext(Dispatchers.Main) {
            controller.setImageAnalysisAnalyzer(analysis) { img ->
                runCatching { frames.trySend(yGrid(img)) }; img.close()
            }
        }
        try {
            val base = kotlinx.coroutines.withTimeoutOrNull(1000) { frames.receive() }
            // No frames (the camera can't run the extra stream): the old fixed wait, not the 8 s cap on every action.
            if (base == null) { val r = action(); kotlinx.coroutines.delay(1200); return r }
            val result = action()
            val s = Settle(base, System.currentTimeMillis(), quietMs = quietMs, maxMs = maxMs)
            while (!s.feed(kotlinx.coroutines.withTimeoutOrNull(300) { frames.receive() }, System.currentTimeMillis())) Unit
            return result
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { controller.clearImageAnalysisAnalyzer() } }
    }

    /** Whole-frame brightness grid, for [uselessPhoto]. */
    suspend fun grid(photo: Bitmap): IntArray = withContext(Dispatchers.Default) { signature(photo) }

    private fun upright(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val scale = FULL.toFloat() / max(src.width, src.height)
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    companion object {
        /** Long side kept from the camera: sharp enough for small terminal text, ~27 MB as a bitmap. */
        const val FULL = 3000

        /** The [area] of [photo] mapped onto a flat rectangle (a 4-point perspective transform). */
        fun straighten(photo: Bitmap, area: ScreenArea, maxSide: Int): Bitmap {
            val (w, h) = area.outputSize(photo.width, photo.height, maxSide)
            val m = Matrix()
            check(m.setPolyToPoly(area.pixels(photo.width, photo.height), 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)) { "can't straighten this area" }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
        }

        /** The fingerprint grid of a camera frame, from its brightness (Y) plane; sensor orientation (only compared with frames). */
        fun yGrid(img: ImageProxy): IntArray {
            val p = img.planes[0]; val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            val w = ScreenSignature.W; val h = ScreenSignature.H; val k = 4
            return IntArray(w * h) { i ->
                val cx = i % w; val cy = i / w; var sum = 0
                for (dy in 0 until k) for (dx in 0 until k) {
                    val x = ((cx * k + dx) * img.width) / (w * k); val y = ((cy * k + dy) * img.height) / (h * k)
                    sum += buf.get(y * rs + x * ps).toInt() and 0xFF
                }
                sum / (k * k)
            }
        }

        fun scaled(b: Bitmap, maxSide: Int): Bitmap {
            val s = maxSide.toFloat() / max(b.width, b.height)
            return if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        }

        fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()

        /**
         * Mean brightness of each cell of a ScreenSignature.W × H grid. Bilinear scaling straight to 32×18 would sample only
         * a few pixels per cell (noisy), so scale to 4× that first and average each 4×4 block.
         */
        fun signature(b: Bitmap): IntArray {
            val k = 4; val w = ScreenSignature.W; val h = ScreenSignature.H
            val mid = Bitmap.createScaledBitmap(b, w * k, h * k, true)
            val px = IntArray(w * k * h * k).also { mid.getPixels(it, 0, w * k, 0, 0, w * k, h * k) }
            return IntArray(w * h) { i ->
                val cx = i % w; val cy = i / w; var sum = 0.0
                for (dy in 0 until k) for (dx in 0 until k) {
                    val p = px[(cy * k + dy) * w * k + cx * k + dx]
                    sum += 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
                }
                (sum / (k * k)).toInt()
            }
        }
    }
}
