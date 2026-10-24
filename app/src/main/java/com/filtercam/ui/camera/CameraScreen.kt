package com.filtercam.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.filtercam.camera.CameraController
import com.filtercam.frame.Frame
import com.filtercam.frame.cropRect
import com.filtercam.frame.videoOrientationHint
import com.filtercam.frame.videoSize
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.gl.RecordableConfigChooser
import com.filtercam.settings.FlashSetting
import com.filtercam.storage.PhotoSaver
import com.filtercam.ui.FilterViewModel
import com.filtercam.ui.filters.FilterPickerSheet
import com.filtercam.ui.filters.ManageFiltersSheet
import com.filtercam.ui.filters.TuneSheet
import com.filtercam.video.VideoRecorder
import kotlinx.coroutines.delay
import kotlin.math.abs
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()

@Composable
fun CameraScreen(
    vm: FilterViewModel, lastPhoto: ImageBitmap?,
    onOpenGallery: () -> Unit, onOpenSettings: () -> Unit, onPhotoSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val params by vm.params.collectAsState()
    val strength by vm.strength.collectAsState()
    val entries by vm.entries.collectAsState()
    var front by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(vm.settings.flashDefault) }
    var grid by remember { mutableStateOf(vm.settings.gridDefault) }
    var frame by remember { mutableStateOf(Frame.R3_4) }
    var videoMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var recStartMs by remember { mutableLongStateOf(0L) }
    var frameAspect by remember { mutableFloatStateOf(0.75f) }
    var deviceRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    var showFilters by remember { mutableStateOf(false) }
    var showTune by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { GlFilterRenderer(ctx) }
    val controller = remember { CameraController(ctx) }
    val recorder = remember { VideoRecorder(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val zoom by controller.zoom.collectAsState()

    renderer.params = params
    renderer.strength = strength
    SideEffect {
        renderer.onSurfaceRecreated = { controller.bind(owner, renderer.surfaceProvider, front) }
        controller.setFlash(if (front) FlashSetting.OFF else flash)
    }
    LaunchedEffect(front) { controller.bind(owner, renderer.surfaceProvider, front) }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(900); focusPoint = null } }
    LaunchedEffect(recording) {
        while (recording) {
            elapsedSec = ((SystemClock.elapsedRealtime() - recStartMs) / 1000).toInt()
            delay(250)
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        busy = true
        renderer.stopRecording {
            worker.execute {
                val r = recorder.stop()
                main.post {
                    busy = false
                    r.onSuccess { vm.message("Video saved to Movies/FilterCam"); onPhotoSaved() }
                        .onFailure { vm.message(it.message ?: "Recording failed") }
                }
            }
        }
    }

    fun startRecording(withAudio: Boolean) {
        val (w, h) = videoSize(frameAspect)
        runCatching {
            val surface = recorder.start(w, h, withAudio, videoOrientationHint(deviceRotation, front))
            renderer.startRecording(surface, w, h)
            recStartMs = SystemClock.elapsedRealtime()
            elapsedSec = 0
            recording = true
            if (withAudio && !recorder.hasAudio) vm.message("Recording without sound (microphone unavailable)")
            else if (!withAudio) vm.message("Recording without sound (microphone not allowed)")
        }.onFailure { vm.message("Could not start recording: ${it.message}") }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> startRecording(granted) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { stopRecording(); glView?.onPause() }
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.unbind() }
    }

    DisposableEffect(ctx) {
        val listener = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                deviceRotation = r
                controller.setTargetRotation(r)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val p = params; val s = strength; val fa = frameAspect
        val quality = vm.settings.jpegQuality; val mirror = front && vm.settings.mirrorFront
        val original = p.isIdentity() || s == 0f
        val needsCrop = abs(fa - 0.75f) > 0.01f
        if (original && !needsCrop) {
            // No filter and no crop: keep the camera full-resolution JPEG exactly as captured.
            controller.captureOriginal(mirror) { result ->
                main.post {
                    result.onSuccess { vm.message("Saved original to Pictures/FilterCam"); onPhotoSaved() }
                        .onFailure { vm.message("Capture failed: ${it.message}") }
                    busy = false
                }
            }
            return
        }
        controller.capture(mirror) { result ->
            result.onSuccess { bmp ->
                worker.execute {
                    var out: Bitmap? = null
                    try {
                        runCatching {
                            val processed = if (original) bmp else PhotoProcessor().process(bmp, p, s)
                            // A sideways shot is saved landscape, so the frame is turned with it.
                            val target = if (processed.width > processed.height) 1f / fa else fa
                            val r = cropRect(processed.width, processed.height, target)
                            out = if (r.w == processed.width && r.h == processed.height) processed
                            else Bitmap.createBitmap(processed, r.x, r.y, r.w, r.h).also { processed.recycle() }
                            PhotoSaver.save(ctx, out!!, quality)
                        }
                            .onSuccess { main.post { vm.message("Saved to Pictures/FilterCam"); onPhotoSaved() } }
                            .onFailure { main.post { vm.message("Save failed: ${it.message}") } }
                    } finally {
                        out?.recycle(); bmp.recycle(); main.post { busy = false }
                    }
                }
            }.onFailure { main.post { vm.message("Capture failed: ${it.message}"); busy = false } }
        }
    }

    fun onShutter() {
        when {
            !videoMode -> takePhoto()
            recording -> stopRecording()
            busy -> {}
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording(true)
            else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenAspect = maxWidth / maxHeight
        val a = frame.aspect(screenAspect)
        SideEffect { frameAspect = a }
        // Fit the frame inside the screen: tall/narrow frames (Full, Cinema, IMAX) are height-limited, so the left/right edges are trimmed.
        val heightLimited = maxWidth / a > maxHeight
        val vfW = if (heightLimited) maxHeight * a else maxWidth
        val vfH = if (heightLimited) maxHeight else maxWidth / a
        val isFull = vfH > maxHeight - 348.dp

        // Viewfinder, cropped to the chosen frame; the controls float over it.
        Box(
            Modifier.fillMaxSize().padding(top = if (isFull) 0.dp else 60.dp, bottom = if (isFull) 0.dp else 230.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredSize(vfW, vfH)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                controller.focusAt(view.display, o.x, o.y, size.width.toFloat(), size.height.toFloat())
                                focusPoint = o
                            }
                        }
                        .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> controller.zoomBy(z) } },
                    factory = { c ->
                        GLSurfaceView(c).apply {
                            glView = this
                            setEGLContextClientVersion(3)
                            setEGLConfigChooser(RecordableConfigChooser())
                            renderer.glView = this
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                    update = { it.requestRender() },
                )
                if (grid) GridOverlay(Modifier.fillMaxSize())
                focusPoint?.let { FocusRing(it, Modifier.fillMaxSize()) }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).background(Color(0x66000000))) {
            TopBar(
                flash, showFlash = !front, grid = grid, frame = frame, frameEnabled = !recording,
                onFlash = { flash = FlashSetting.values()[(flash.ordinal + 1) % 3] },
                onGrid = { grid = !grid }, onFrame = { frame = it }, onSettings = onOpenSettings,
            )
        }
        if (recording) RecTimer(elapsedSec, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))

        Column(Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)), horizontalAlignment = Alignment.CenterHorizontally) {
            BottomActionRow(zoom, onZoom = controller::setZoomRatio, onTune = { showTune = true }, onFilters = { showFilters = true })
            ModeSwitch(videoMode, enabled = !recording) { videoMode = it }
            ShutterRow(
                lastPhoto, busy, videoMode, recording,
                onGallery = onOpenGallery, onShutter = ::onShutter, onFlip = { front = !front },
            )
        }
    }
    if (showTune) TuneSheet(vm) { showTune = false }
    if (showFilters) FilterPickerSheet(vm, onManage = { showFilters = false; showManage = true }) { showFilters = false }
    if (showManage) ManageFiltersSheet(vm) { showManage = false }
}
