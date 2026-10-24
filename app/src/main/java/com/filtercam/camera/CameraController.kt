package com.filtercam.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
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
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import android.view.Display
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.filtercam.settings.FlashSetting
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ExposureInfo(val index: Int = 0, val min: Int = 0, val max: Int = 0, val stepEv: Float = 0f)

data class ZoomInfo(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f)

class CameraController(private val context: Context) {
    private val _zoom = MutableStateFlow(ZoomInfo())
    val zoom: StateFlow<ZoomInfo> = _zoom
    private val _exposure = MutableStateFlow(ExposureInfo())
    val exposure: StateFlow<ExposureInfo> = _exposure

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()
    /** Best-quality photo capture. highRes allows the sensor high-resolution mode (up to about 50 MP); otherwise capped at 12 MP. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun buildCapture(highRes: Boolean, hq: Boolean = true): ImageCapture {
        val rs = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(
                ResolutionStrategy(if (highRes) Size(8160, 6120) else Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .apply { if (highRes) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
            .build()
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setResolutionSelector(rs)
        if (hq) {
            // Ask the camera for its best noise reduction and sharpening on the saved photo.
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }
        return builder.build()
    }

    private var imageCapture = buildCapture(highRes = true)
    private var targetRotation = Surface.ROTATION_0
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private val captureExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var camera: Camera? = null

    private var highResFailed = false

    /**
     * Binds preview and photo capture. With useExtensions the phone HDR/Night engine (multi-frame processing) is used
     * when available, which gives much cleaner photos in dim light; otherwise a plain single-frame capture is used.
     */
    fun bind(owner: LifecycleOwner, provider: Preview.SurfaceProvider, front: Boolean, useExtensions: Boolean = false) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cp = future.get()
            val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            fun bindWith(cameraSelector: CameraSelector, ext: Boolean): Camera {
                imageCapture = buildCapture(highRes = !ext && !highResFailed, hq = !ext).also { it.targetRotation = targetRotation; it.flashMode = flashMode }
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider(provider)
                cp.unbindAll()
                return cp.bindToLifecycle(owner, cameraSelector, preview, imageCapture)
            }

            fun attach(cam: Camera) {
                camera = cam
                // Focus and expose on a wide centre area, so the camera does not lock onto only the nearest object.
                runCatching {
                    val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.7f)
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build(),
                    )
                }
                Log.i("FilterCam", "Photo capture resolution: ${imageCapture.resolutionInfo?.resolution}")
                cam.cameraInfo.exposureState.let { ex ->
                    _exposure.value = ExposureInfo(ex.exposureCompensationIndex, ex.exposureCompensationRange.lower, ex.exposureCompensationRange.upper, ex.exposureCompensationStep.toFloat())
                }
                cam.cameraInfo.zoomState.observe(owner) { z -> _zoom.value = ZoomInfo(z.zoomRatio, z.minZoomRatio, z.maxZoomRatio) }
            }

            fun plain() {
                val cam = try {
                    bindWith(base, false)
                } catch (e: IllegalArgumentException) {
                    // High-resolution capture is not supported together with this preview: fall back to 12 MP.
                    Log.w("FilterCam", "High-res capture unavailable, using 12 MP", e)
                    highResFailed = true
                    bindWith(base, false)
                }
                attach(cam)
            }

            if (!useExtensions) { plain(); return@addListener }
            val extFuture = ExtensionsManager.getInstanceAsync(context, cp)
            extFuture.addListener({
                val em = runCatching { extFuture.get() }.getOrNull()
                val mode = em?.let { m ->
                    listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT).firstOrNull { m.isExtensionAvailable(base, it) }
                }
                if (em == null || mode == null) {
                    Log.i("FilterCam", "No camera extensions available, using plain capture")
                    plain()
                    return@addListener
                }
                val cam = try {
                    bindWith(em.getExtensionEnabledCameraSelector(base, mode), true).also { Log.i("FilterCam", "Extension mode in use: $mode") }
                } catch (e: Exception) {
                    Log.w("FilterCam", "Extension bind failed, using plain capture", e)
                    null
                }
                if (cam != null) attach(cam) else plain()
            }, ContextCompat.getMainExecutor(context))
        }, ContextCompat.getMainExecutor(context))
    }

    /** Brightness: exposure compensation in camera steps (negative is darker). */
    fun setExposureIndex(index: Int) {
        val c = camera ?: return
        c.cameraControl.setExposureCompensationIndex(index)
        _exposure.value = _exposure.value.copy(index = index)
    }

    fun unbind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ camera = null; future.get().unbindAll() }, ContextCompat.getMainExecutor(context))
    }

    /** [rotation] is a Surface.ROTATION_* value for how the phone is held; used for the saved photo's orientation. */
    fun setTargetRotation(rotation: Int) { targetRotation = rotation; imageCapture.targetRotation = rotation }

    fun setFlash(mode: FlashSetting) {
        flashMode = when (mode) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
        imageCapture.flashMode = flashMode
    }

    /** Multiplies the current zoom ratio by [factor], clamped to the camera's range. Main thread. */
    fun zoomBy(factor: Float) {
        val c = camera ?: return
        val z = c.cameraInfo.zoomState.value ?: return
        c.cameraControl.setZoomRatio((z.zoomRatio * factor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    /** Jumps to [ratio], clamped to the camera's zoom range. Main thread. */
    fun setZoomRatio(ratio: Float) {
        val c = camera ?: return
        val z = c.cameraInfo.zoomState.value ?: return
        c.cameraControl.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    /** x,y are view pixels inside a view of size w x h that shows the whole preview. */
    fun focusAt(display: Display?, x: Float, y: Float, w: Float, h: Float) {
        val c = camera ?: return
        val d = display ?: return
        val point = DisplayOrientedMeteringPointFactory(d, c.cameraInfo, w, h).createPoint(x, y)
        c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** [onResult] is invoked on a background thread, not the main thread. */
    fun capture(mirror: Boolean = false, onResult: (Result<Bitmap>) -> Unit) {
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        // Decode downsampled so a 50 MP capture never needs more than about 12 MP of memory.
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 13_500_000L) sample *= 2
                        val raw = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
                        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); if (mirror) postScale(-1f, 1f) }
                        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                        if (rotated !== raw) raw.recycle()
                        rotated
                    }
                    image.close()
                    onResult(result)
                }
                override fun onError(e: ImageCaptureException) = onResult(Result.failure(e))
            },
        )
    }

    /** Saves the camera JPEG untouched, straight to Pictures/FilterCam (full capture resolution). */
    fun captureOriginal(mirror: Boolean, onResult: (Result<Uri>) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FilterCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilterCam")
        }
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror })
            .build()
        imageCapture.takePicture(
            options, captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val u = output.savedUri
                    onResult(if (u != null) Result.success(u) else Result.failure(IllegalStateException("Saved, but no file address returned")))
                }
                override fun onError(e: ImageCaptureException) = onResult(Result.failure(e))
            },
        )
    }
}
