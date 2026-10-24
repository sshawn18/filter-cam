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
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import android.view.Display
import androidx.camera.core.Camera
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import com.filtercam.settings.FlashSetting
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ZoomInfo(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f)

class CameraController(private val context: Context) {
    private val _zoom = MutableStateFlow(ZoomInfo())
    val zoom: StateFlow<ZoomInfo> = _zoom

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()
    private val captureSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(captureSelector)
        .build()

    private val captureExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var camera: Camera? = null

    fun bind(owner: LifecycleOwner, provider: Preview.SurfaceProvider, front: Boolean) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cp = future.get()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(provider)
            cp.unbindAll()
            val cam = cp.bindToLifecycle(
                owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture,
            )
            camera = cam
            cam.cameraInfo.zoomState.observe(owner) { z -> _zoom.value = ZoomInfo(z.zoomRatio, z.minZoomRatio, z.maxZoomRatio) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ camera = null; future.get().unbindAll() }, ContextCompat.getMainExecutor(context))
    }

    /** [rotation] is a Surface.ROTATION_* value for how the phone is held; used for the saved photo's orientation. */
    fun setTargetRotation(rotation: Int) { imageCapture.targetRotation = rotation }

    fun setFlash(mode: FlashSetting) {
        imageCapture.flashMode = when (mode) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
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
                        val raw = image.toBitmap()
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
}
