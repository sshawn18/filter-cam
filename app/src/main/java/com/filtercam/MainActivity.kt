package com.filtercam

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filtercam.ui.CameraScreen
import com.filtercam.ui.FilterViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var granted by remember { mutableStateOf(false) }
            var asked by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
            if (granted) {
                CameraScreen(viewModel<FilterViewModel>())
            } else if (asked) {
                Column {
                    Text("FilterCam needs camera access to show the live preview and take photos.")
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                }
            }
        }
    }
}
