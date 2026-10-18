package com.filtercam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.filtercam.gallery.GalleryRepository
import com.filtercam.gallery.Photo
import com.filtercam.ui.camera.CameraScreen
import com.filtercam.ui.gallery.GalleryScreen
import com.filtercam.ui.gallery.PhotoViewer
import com.filtercam.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Camera : Screen
    data object Gallery : Screen
    data class Viewer(val index: Int) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(vm: FilterViewModel) {
    val ctx = LocalContext.current
    val repo = remember { GalleryRepository(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var version by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf(emptyList<Photo>()) }
    LaunchedEffect(version) { photos = withContext(Dispatchers.IO) { repo.list() } }
    val lastPhoto by produceState<ImageBitmap?>(null, photos) {
        value = withContext(Dispatchers.IO) { photos.firstOrNull()?.let { repo.thumbnail(it.uri, 160, 160) } }
    }

    BackHandler(screen != Screen.Camera) {
        screen = if (screen is Screen.Viewer) Screen.Gallery else Screen.Camera
    }
    Scaffold(containerColor = Color.Black, snackbarHost = { SnackbarHost(vm.snackbar) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = screen) {
                Screen.Camera -> CameraScreen(
                    vm, lastPhoto,
                    onOpenGallery = { version++; screen = Screen.Gallery },
                    onOpenSettings = { screen = Screen.Settings },
                    onPhotoSaved = { version++ },
                )
                Screen.Gallery -> GalleryScreen(photos, repo, onBack = { screen = Screen.Camera }, onOpen = { screen = Screen.Viewer(it) })
                is Screen.Viewer -> PhotoViewer(photos, s.index, repo, onBack = { screen = Screen.Gallery }, onDelete = { p ->
                    if (repo.delete(p)) {
                        val left = photos - p
                        photos = left
                        version++
                        screen = if (left.isEmpty()) Screen.Gallery else Screen.Viewer(s.index.coerceAtMost(left.size - 1))
                        vm.message("Photo deleted")
                    } else vm.message("Could not delete the photo")
                })
                Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Camera })
            }
        }
    }
}
