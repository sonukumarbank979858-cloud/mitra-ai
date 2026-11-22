package com.sonuclasses.mitra.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** Opens the back camera, takes one still with CameraX and hands the JPEG back to the tool. */
class CameraCaptureActivity : ComponentActivity() {

    companion object {
        @Volatile private var pending: CompletableDeferred<ByteArray?>? = null

        suspend fun capture(ctx: Context): ByteArray? {
            val d = CompletableDeferred<ByteArray?>()
            pending = d
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(ctx, CameraCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val r = withTimeoutOrNull(20_000) { d.await() }
            pending = null
            return r
        }
    }

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF07070F)), contentAlignment = Alignment.Center) {
                Text("📷  देख रहा हूँ…", color = Color.White, fontSize = 22.sp)
            }
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                Handler(Looper.getMainLooper()).postDelayed({ take(ic) }, 900)
            } catch (e: Exception) { done(null) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun take(ic: ImageCapture) {
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = try {
                    val buf = image.planes[0].buffer
                    val arr = ByteArray(buf.remaining()); buf.get(arr)
                    shrink(arr, image.imageInfo.rotationDegrees)
                } catch (e: Exception) { null } finally { image.close() }
                done(bytes)
            }
            override fun onError(exception: ImageCaptureException) { done(null) }
        })
    }

    private fun shrink(jpeg: ByteArray, rotation: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return jpeg
        if (rotation != 0) {
            val m = Matrix(); m.postRotate(rotation.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun done(b: ByteArray?) {
        if (finished) return
        finished = true
        pending?.complete(b)
        finish()
    }

    override fun onDestroy() {
        if (!finished) pending?.complete(null)
        super.onDestroy()
    }
}
