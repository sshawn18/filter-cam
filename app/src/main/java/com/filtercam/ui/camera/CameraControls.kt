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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.filtercam.frame.Frame
import com.filtercam.settings.FlashSetting
import com.filtercam.ui.theme.Palette

@Composable
fun FrameDropdown(selected: Frame, enabled: Boolean, onSelect: (Frame) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x55000000)).clickable(enabled = enabled) { open = true }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.label, color = if (enabled) Palette.Accent else Color.Gray, fontSize = 14.sp)
            Icon(Icons.Default.ArrowDropDown, "Choose frame", tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Frame.values().forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label, color = if (f == selected) Palette.Accent else Color.Unspecified) },
                    onClick = { open = false; onSelect(f) },
                )
            }
        }
    }
}

@Composable
fun TopBar(
    flash: FlashSetting, showFlash: Boolean, grid: Boolean, night: Boolean, showNight: Boolean, frame: Frame, frameEnabled: Boolean, exposureOn: Boolean,
    onFlash: () -> Unit, onGrid: () -> Unit, onNight: () -> Unit, onExposure: () -> Unit, onFrame: (Frame) -> Unit, onSettings: () -> Unit,
) {
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
        if (showNight) {
            IconButton(onClick = onNight) {
                Icon(Icons.Default.NightsStay, "Night mode", tint = if (night) Palette.Accent else Color.White)
            }
        }
        IconButton(onClick = onExposure) {
            Icon(Icons.Default.WbSunny, "Brightness", tint = if (exposureOn) Palette.Accent else Color.White)
        }
        Spacer(Modifier.width(6.dp))
        FrameDropdown(frame, frameEnabled, onFrame)
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
fun FrameSelector(selected: Frame, enabled: Boolean, onSelect: (Frame) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        Frame.values().forEach { f ->
            val on = f == selected
            Text(
                f.label, fontSize = 13.sp,
                color = if (on) Palette.Accent else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (on) Color(0xFF2A2A2A) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(f) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun ModeSwitch(video: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        listOf(false to "PHOTO", true to "VIDEO").forEach { (isVideo, label) ->
            val on = video == isVideo
            Text(
                label, fontSize = 14.sp,
                color = if (on) Palette.Accent else Color.White.copy(alpha = if (enabled) 0.85f else 0.4f),
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onChange(isVideo) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun RecTimer(elapsedSec: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        Spacer(Modifier.width(8.dp))
        Text("%02d:%02d".format(elapsedSec / 60, elapsedSec % 60), color = Color.White, fontSize = 15.sp)
    }
}

@Composable
fun ShutterRow(
    lastPhoto: ImageBitmap?, busy: Boolean, video: Boolean, recording: Boolean,
    onGallery: () -> Unit, onShutter: () -> Unit, onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel).clickable(enabled = !recording, onClick = onGallery),
            contentAlignment = Alignment.Center,
        ) {
            if (lastPhoto != null) Image(lastPhoto, "Open gallery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PhotoLibrary, "Open gallery", tint = Color.White)
        }
        Box(Modifier.size(80.dp).border(4.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            val inner = when {
                recording -> Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFF3B30))
                video -> Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFFF3B30))
                else -> Modifier.size(64.dp).clip(CircleShape).background(if (busy) Color.Gray else Color.White)
            }
            Box(inner.clickable(enabled = !busy || recording, onClick = onShutter))
        }
        IconButton(onClick = onFlip, enabled = !recording, modifier = Modifier.size(52.dp)) {
            Icon(
                Icons.Default.FlipCameraAndroid, "Flip camera",
                tint = Color.White.copy(alpha = if (recording) 0.3f else 1f), modifier = Modifier.size(30.dp),
            )
        }
    }
}

