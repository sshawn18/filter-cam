package com.filtercam.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.filtercam.settings.FlashSetting
import com.filtercam.ui.theme.Palette

@Composable
fun TopBar(flash: FlashSetting, showFlash: Boolean, grid: Boolean, onFlash: () -> Unit, onGrid: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showFlash) {
            IconButton(onClick = onFlash) {
                Icon(
                    when (flash) { FlashSetting.OFF -> Icons.Default.FlashOff; FlashSetting.ON -> Icons.Default.FlashOn; FlashSetting.AUTO -> Icons.Default.FlashAuto },
                    "Flash: ${flash.name.lowercase()}", tint = if (flash == FlashSetting.OFF) Color.White else Palette.Accent,
                )
            }
        }
        IconButton(onClick = onGrid) {
            Icon(if (grid) Icons.Default.GridOn else Icons.Default.GridOff, "Grid", tint = if (grid) Palette.Accent else Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
    }
}

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
            drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
        }
    }
}

@Composable
fun FocusRing(at: Offset, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCircle(Palette.Accent, radius = 36.dp.toPx(), center = at, style = Stroke(2.dp.toPx())) }
}

@Composable
fun ShutterRow(lastPhoto: ImageBitmap?, busy: Boolean, onGallery: () -> Unit, onShutter: () -> Unit, onFlip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel).clickable(onClick = onGallery),
            contentAlignment = Alignment.Center,
        ) {
            if (lastPhoto != null) Image(lastPhoto, "Open gallery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PhotoLibrary, "Open gallery", tint = Color.White)
        }
        Box(
            Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                .background(if (busy) Color.Gray else Color.White).clickable(enabled = !busy, onClick = onShutter),
        )
        IconButton(onClick = onFlip, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.FlipCameraAndroid, "Flip camera", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}
