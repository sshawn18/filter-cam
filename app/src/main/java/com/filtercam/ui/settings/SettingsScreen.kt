package com.filtercam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filtercam.settings.FlashSetting
import com.filtercam.ui.FilterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: FilterViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    var grid by remember { mutableStateOf(s.gridDefault) }
    var flash by remember { mutableStateOf(s.flashDefault) }
    var quality by remember { mutableStateOf(s.jpegQuality.toFloat()) }
    var mirror by remember { mutableStateOf(s.mirrorFront) }
    var boost by remember { mutableStateOf(s.detailBoost) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SwitchRow("Grid lines on startup", grid) { grid = it; s.gridDefault = it }
            SwitchRow("Sharpen main-lens photos (1x to 2x)", boost) { boost = it; s.detailBoost = it }
            Text("Adds crisp detail and calmer whites to unfiltered photos on the main lens. Turn off to keep the camera JPEG untouched.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Text("Default flash", color = Color.White, modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashSetting.values().forEach { f ->
                    FilterChip(
                        selected = flash == f, onClick = { flash = f; s.flashDefault = f },
                        label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Text("Photo quality: ${quality.toInt()}%", color = Color.White, modifier = Modifier.padding(top = 16.dp))
            Slider(quality, { quality = it }, valueRange = 70f..100f, onValueChangeFinished = { s.jpegQuality = quality.toInt() })
            SwitchRow("Mirror front-camera photos", mirror) { mirror = it; s.mirrorFront = it }
            Text(
                "When on, selfies are saved mirrored like the preview. When off, they match the stock camera.",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall,
            )
            Text("About", color = Color.White, modifier = Modifier.padding(top = 24.dp))
            Text("FilterCam $version. Photos are saved to Pictures/FilterCam.", color = Color.Gray)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
