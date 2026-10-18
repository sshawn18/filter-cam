package com.filtercam.ui

import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.filtercam.camera.CameraController
import com.filtercam.gl.GlFilterRenderer
import com.filtercam.gl.PhotoProcessor
import com.filtercam.storage.PhotoSaver
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()

@Composable
fun CameraScreen(vm: FilterViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val params by vm.params.collectAsState()
    val strength by vm.strength.collectAsState()
    var front by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { GlFilterRenderer(ctx) }
    val controller = remember { CameraController(ctx) }

    renderer.params = params
    renderer.strength = strength
    SideEffect {
        renderer.onSurfaceRecreated = { controller.bind(owner, renderer.surfaceProvider, front) }
    }
    LaunchedEffect(front) { controller.bind(owner, renderer.surfaceProvider, front) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
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
        val entries by vm.entries.collectAsState()
        LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(entries) { _, e ->
                Text(e.params.name, color = if (e.params.name == params.name) Color.White else Color.Gray,
                    modifier = Modifier.clickable { vm.select(e.params.name) })
            }
        }
        Slider(value = strength, onValueChange = vm::setStrength, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Flip", color = Color.White, modifier = Modifier.clickable { front = !front })
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable {
                // Capture params/strength on the main thread; onResult runs on a background thread.
                if (busy) return@clickable
                busy = true
                val p = params; val s = strength
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                controller.capture { result ->
                    result.onSuccess { bmp ->
                        worker.execute {
                            var out: android.graphics.Bitmap? = null
                            try {
                                runCatching {
                                    out = PhotoProcessor().process(bmp, p, s)
                                    PhotoSaver.save(ctx, out!!)
                                }
                                    .onSuccess { toast(ctx, "Saved to Pictures/FilterCam") }
                                    .onFailure { toast(ctx, "Save failed: ${it.message}") }
                            } finally {
                                out?.recycle()
                                bmp.recycle()
                                main.post { busy = false }
                            }
                        }
                    }.onFailure {
                        toast(ctx, "Capture failed: ${it.message}")
                        main.post { busy = false }
                    }
                }
            })
            Text(if (dev) "Dev ✓" else "Dev", color = Color.White, modifier = Modifier.clickable { dev = !dev })
        }
        if (dev) DevPanel(params, vm::setParam)
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
