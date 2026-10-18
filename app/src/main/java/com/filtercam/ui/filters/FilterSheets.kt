package com.filtercam.ui.filters

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.filtercam.filter.FilterParams
import com.filtercam.ui.DevPanel
import com.filtercam.ui.FilterViewModel
import java.io.File

fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share filter").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
fun NameDialog(
    title: String, initial: String, confirmLabel: String,
    validate: (String) -> String?, exists: (String) -> Boolean,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val error = if (text.isBlank()) null else validate(text)
    val replacing = error == null && text.isNotBlank() && exists(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: if (replacing) "This replaces your existing filter" else "") },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && error == null, onClick = { onConfirm(text.trim()) }) {
                Text(if (replacing) "Replace" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneSheet(vm: FilterViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val params by vm.params.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Tune: ${params.name}", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSave = true }) { Text("Save as new filter") }
                    OutlinedButton(onClick = { shareFile(ctx, vm.exportFile(params), "application/json") }) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                }
                DevPanel(params, vm::setParam)
            }
            SnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (showSave) {
        NameDialog(
            title = "Save as new filter", initial = "", confirmLabel = "Save",
            validate = vm::nameError, exists = vm::nameExists,
            onConfirm = { vm.saveCurrentAs(it); showSave = false }, onDismiss = { showSave = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFiltersSheet(vm: FilterViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val entries by vm.entries.collectAsState()
    var renaming by remember { mutableStateOf<FilterParams?>(null) }
    var deleting by remember { mutableStateOf<FilterParams?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { r ->
                    val buf = CharArray(200_001); val n = r.read(buf)
                    if (n <= 0) "" else String(buf, 0, n)
                }
            }.getOrNull()
            if (text == null) vm.message("Could not read that file") else vm.importFilter(text)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Import filter")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.params.name }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.params.name + if (e.builtIn) "  (built-in)" else "", modifier = Modifier.weight(1f))
                            if (!e.builtIn) {
                                IconButton(onClick = { shareFile(ctx, vm.exportFile(e.params), "application/json") }) { Icon(Icons.Default.Share, "Export") }
                                IconButton(onClick = { renaming = e.params }) { Icon(Icons.Default.Edit, "Rename") }
                                IconButton(onClick = { deleting = e.params }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
            SnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    renaming?.let { p ->
        NameDialog("Rename filter", p.name, "Rename", validate = vm::nameError, exists = { false },
            onConfirm = { vm.renameFilter(p.name, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This removes the filter from this phone. Photos already taken are not affected.") },
            confirmButton = { TextButton(onClick = { vm.deleteFilter(p.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
