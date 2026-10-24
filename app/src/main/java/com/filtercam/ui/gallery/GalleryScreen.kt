package com.filtercam.ui.gallery

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import com.filtercam.gallery.GalleryRepository
import com.filtercam.gallery.Photo
import com.filtercam.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Thumb(photo: Photo, repo: GalleryRepository, w: Int, h: Int, modifier: Modifier, scale: ContentScale) {
    val bmp by produceState<ImageBitmap?>(null, photo.uri) { value = withContext(Dispatchers.IO) { repo.thumbnail(photo.uri, w, h) } }
    Box(modifier.background(Palette.Panel), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) } ?: Text("...", color = Color.Gray)
        if (photo.isVideo && w < 1000) {
            val sec = (photo.durationMs / 1000).toInt()
            Text(
                "▶ %d:%02d".format(sec / 60, sec % 60), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(photos: List<Photo>, repo: GalleryRepository, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = { Text("Gallery") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White),
        )
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos yet. Take one!", color = Color.Gray) }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(photos, key = { _, p -> p.key }) { i, p ->
                    Thumb(p, repo, 320, 320, Modifier.aspectRatio(1f).clickable { onOpen(i) }, ContentScale.Crop)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewer(photos: List<Photo>, startIndex: Int, repo: GalleryRepository, onBack: () -> Unit, onDelete: (Photo) -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var confirm by remember { mutableStateOf(false) }
    val current = photos.getOrNull(pager.currentPage)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = { Text("${pager.currentPage + 1} / ${photos.size}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    current?.let {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = if (it.isVideo) "video/mp4" else "image/jpeg"; putExtra(Intent.EXTRA_STREAM, it.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share photo"))
                    }
                }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
            ),
        )
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            photos.getOrNull(page)?.let {
                if (it.isVideo) VideoPlayer(it.uri) else Thumb(it, repo, 1600, 2133, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
    }
    if (confirm && current != null) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Delete this photo?") },
            text = { Text("It will be removed from your phone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(current) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                val mc = MediaController(ctx)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoURI(uri)
                setOnPreparedListener { it.isLooping = false; seekTo(1) }
            }
        },
    )
}
