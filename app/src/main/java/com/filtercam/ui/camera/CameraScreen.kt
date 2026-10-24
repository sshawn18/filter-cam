package com.filtercam.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
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
import com.filtercam.frame.videoSize
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.gl.RecordableConfigChooser
import com.filtercam.settings.FlashSetting
import com.filtercam.storage.PhotoSaver
import com.filtercam.ui.FilterViewModel
import com.filtercam.ui.filters.ManageFiltersSheet
import com.filtercam.ui.filters.TuneSheet
import com.filtercam.video.VideoRecorder
import kotlinx.coroutines.delay
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
    var uiLandscape by remember { mutableStateOf(false) }
    val activity = ctx as? Activity
    var showStrength by remember { mutableStateOf(false) }
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
    val wideFrame = false // screen stays portrait; wide frames are bands inside the portrait view
    LaunchedEffect(wideFrame) {
        activity?.requestedOrientation =
            if (wideFrame) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
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
            val surface = recorder.start(w, h, withAudio)
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
                controller.setTargetRotation(
                    when (o) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    },
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val p = params; val s = strength; val fa = frameAspect; val land = uiLandscape
        val quality = vm.settings.jpegQuality; val mirror = front && vm.settings.mirrorFront
        controller.capture(mirror) { result ->
            result.onSuccess { bmp ->
                worker.execute {
                    var out: Bitmap? = null
                    try {
                        runCatching {
                            val processed = PhotoProcessor().process(bmp, p, s)
                            // A sideways shot is saved landscape, so the frame is turned with it.
                            val target = if (land) fa else if (processed.width > processed.height) 1f / fa else fa
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
        val landscape = maxWidth > maxHeight
        val screenAspect = maxWidth / maxHeight
        val a = frame.aspect(screenAspect)
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        SideEffect { frameAspect = a; uiLandscape = landscape; renderer.displayRotation = rotation }
        val heightLimited = landscape && maxWidth / a > maxHeight
        val vfW = if (heightLimited) maxHeight * a else maxWidth
        val vfH = if (heightLimited) maxHeight else maxWidth / a
        val isFull = frame == Frame.FULL

        // Viewfinder, cropped to the chosen frame; the controls float over it.
        Box(
            Modifier.fillMaxSize().padding(
                top = if (isFull || landscape) 0.dp else 88.dp,
                bottom = if (isFull || landscape) 0.dp else 260.dp,
            ),
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

        if (landscape) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 120.dp).background(Color(0x66000000)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.42f)) {
                    TopBar(
                        flash, showFlash = !front, grid = grid,
                        onFlash = { flash = FlashSetting.values()[(flash.ordinal + 1) % 3] },
                        onGrid = { grid = !grid }, onSettings = onOpenSettings,
                    )
                }
                Box(Modifier.weight(0.58f)) { FrameSelector(frame, enabled = !recording) { frame = it } }
            }
            if (recording) RecTimer(elapsedSec, Modifier.align(Alignment.TopCenter).padding(top = 58.dp))
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x66000000)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeSwitchVertical(videoMode, enabled = !recording) { videoMode = it }
                ShutterColumn(
                    lastPhoto, busy, videoMode, recording,
                    onGallery = onOpenGallery, onShutter = ::onShutter, onFlip = { front = !front },
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 120.dp).background(Color(0x66000000)), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showStrength) {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength ${(strength * 100).toInt()}%", color = Color.White)
                        Slider(strength, vm::setStrength, Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
                FilterStrip(
                    entries, params.name,
                    onSelect = { name -> if (name == params.name) showStrength = !showStrength else { vm.select(name); showStrength = false } },
                    onTune = { showTune = true }, onManage = { showManage = true },
                )
            }
            ZoomBar(zoom, front, onZoom = controller::setZoomRatio, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, end = 120.dp))
        } else {
        Column(Modifier.align(Alignment.TopCenter).background(Color(0x66000000))) {
            TopBar(
                flash, showFlash = !front, grid = grid,
                onFlash = { flash = FlashSetting.values()[(flash.ordinal + 1) % 3] },
                onGrid = { grid = !grid }, onSettings = onOpenSettings,
            )
            FrameSelector(frame, enabled = !recording) { frame = it }
        }
        if (recording) RecTimer(elapsedSec, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))

        Column(Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showStrength) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Strength ${(strength * 100).toInt()}%", color = Color.White)
                    Slider(strength, vm::setStrength, Modifier.weight(1f).padding(start = 12.dp))
                }
            }
            ZoomBar(zoom, front, onZoom = controller::setZoomRatio, modifier = Modifier.padding(bottom = 4.dp))
            FilterStrip(
                entries, params.name,
                onSelect = { name -> if (name == params.name) showStrength = !showStrength else { vm.select(name); showStrength = false } },
                onTune = { showTune = true }, onManage = { showManage = true },
            )
            ModeSwitch(videoMode, enabled = !recording) { videoMode = it }
            ShutterRow(
                lastPhoto, busy, videoMode, recording,
                onGallery = onOpenGallery, onShutter = ::onShutter, onFlip = { front = !front },
            )
        }
        }
    }
    if (showTune) TuneSheet(vm) { showTune = false }
    if (showManage) ManageFiltersSheet(vm) { showManage = false }
}
