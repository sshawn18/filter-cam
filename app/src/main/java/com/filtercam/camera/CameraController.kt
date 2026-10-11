package com.filtercam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

class CameraController(private val context: Context) {
    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector)
        .build()

    fun bind(owner: LifecycleOwner, provider: Preview.SurfaceProvider, front: Boolean) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cp = future.get()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(provider)
            cp.unbindAll()
            cp.bindToLifecycle(
                owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture(onResult: (Result<Bitmap>) -> Unit) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        val raw = image.toBitmap()
                        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    }
                    image.close()
                    onResult(result)
                }
                override fun onError(e: ImageCaptureException) = onResult(Result.failure(e))
            },
        )
    }
}
