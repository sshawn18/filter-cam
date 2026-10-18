package com.filtercam.ui.camera

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.filtercam.camera.CameraController
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.settings.FlashSetting
import com.filtercam.storage.PhotoSaver
import com.filtercam.ui.FilterViewModel
import com.filtercam.ui.filters.ManageFiltersSheet
import com.filtercam.ui.filters.TuneSheet
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
    var showStrength by remember { mutableStateOf(false) }
    var showTune by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { GlFilterRenderer(ctx) }
    val controller = remember { CameraController(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }

    renderer.params = params
    renderer.strength = strength
    SideEffect {
        renderer.onSurfaceRecreated = { controller.bind(owner, renderer.surfaceProvider, front) }
        controller.setFlash(if (front) FlashSetting.OFF else flash)
    }
    LaunchedEffect(front) { controller.bind(owner, renderer.surfaceProvider, front) }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(900); focusPoint = null } }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.unbind() }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val p = params; val s = strength
        val quality = vm.settings.jpegQuality; val mirror = front && vm.settings.mirrorFront
        controller.capture(mirror) { result ->
            result.onSuccess { bmp ->
                worker.execute {
                    var out: Bitmap? = null
                    try {
                        runCatching { out = PhotoProcessor().process(bmp, p, s); PhotoSaver.save(ctx, out!!, quality) }
                            .onSuccess { main.post { vm.message("Saved to Pictures/FilterCam"); onPhotoSaved() } }
                            .onFailure { main.post { vm.message("Save failed: ${it.message}") } }
                    } finally {
                        out?.recycle(); bmp.recycle(); main.post { busy = false }
                    }
                }
            }.onFailure { main.post { vm.message("Capture failed: ${it.message}"); busy = false } }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(
            flash, showFlash = !front, grid = grid,
            onFlash = { flash = FlashSetting.values()[(flash.ordinal + 1) % 3] },
            onGrid = { grid = !grid }, onSettings = onOpenSettings,
        )
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            controller.focusAt(view.display, o.x, o.y, size.width.toFloat(), size.height.toFloat())
                            focusPoint = o
                        }
                    }
                    .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> controller.zoomBy(zoom) } },
                factory = { c ->
                    GLSurfaceView(c).apply {
                        glView = this
                        setEGLContextClientVersion(3)
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
        Spacer(Modifier.weight(1f))
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
        ShutterRow(lastPhoto, busy, onGallery = onOpenGallery, onShutter = ::takePhoto, onFlip = { front = !front })
    }
    if (showTune) TuneSheet(vm) { showTune = false }
    if (showManage) ManageFiltersSheet(vm) { showManage = false }
}
