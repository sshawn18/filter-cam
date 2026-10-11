package com.filtercam

import android.Manifest
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.filtercam.camera.CameraController
import com.filtercam.filter.FilterRepository
import com.filtercam.gl.GlFilterRenderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val owner = LocalLifecycleOwner.current
            var granted by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
            if (granted) {
                val renderer = remember { GlFilterRenderer(ctx).apply { params = FilterRepository(ctx).loadAll().first() } }
                val controller = remember { CameraController(ctx) }
                renderer.onSurfaceRecreated = { controller.bind(owner, renderer.surfaceProvider, front = false) }
                var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
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
                )
                LaunchedEffect(Unit) { controller.bind(owner, renderer.surfaceProvider, front = false) }
            }
        }
    }
}
