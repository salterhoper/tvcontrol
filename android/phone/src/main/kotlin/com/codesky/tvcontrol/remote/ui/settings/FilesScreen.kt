package com.codesky.tvcontrol.remote.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.FileItem
import com.codesky.tvcontrol.remote.data.StorageInfo
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    val context = LocalContext.current
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var installOnUpload by remember { mutableStateOf(true) }
    var busyName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { client?.files() }.getOrNull()?.let { storage = it }
    }
    LaunchedEffect(client) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            val payload = withContext(Dispatchers.IO) { readUri(context, uri) }
            if (payload == null) {
                vm.toast("Could not read that file")
            } else {
                runCatching { client?.uploadFile(payload.second, payload.first, installOnUpload) }
                    .onSuccess { vm.toast(it?.message ?: it?.error ?: "Uploaded ${payload.first}") }
                    .onFailure { vm.toast(it.message ?: "Upload failed") }
                refresh()
            }
            uploading = false
        }
    }

    DetailColumn {
        SettingsCard("Storage") {
            storage?.let { s ->
                KeyValueRow("Free", "${s.formattedFree} of ${s.formattedTotal}")
                LinearProgressIndicator(
                    progress = { ((100 - s.freePercent).coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ToggleRow("Install APKs right after upload", "Launches the TV's installer", installOnUpload) { installOnUpload = it }
            Button(onClick = { picker.launch("*/*") }, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
                if (uploading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Upload a file")
                }
            }
        }

        SettingsCard("On the TV") {
            val files = storage?.files.orEmpty()
            if (files.isEmpty()) {
                Text("No files uploaded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                files.forEach { f ->
                    FileRow(
                        file = f,
                        busy = busyName == f.name,
                        onInstall = {
                            scope.launch {
                                busyName = f.name
                                runCatching { client?.installApk(f.name) }
                                    .onSuccess { vm.toast(it?.message ?: it?.error ?: "Opening installer…") }
                                    .onFailure { vm.toast(it.message ?: "Install failed") }
                                busyName = null
                            }
                        },
                        onDelete = {
                            scope.launch {
                                busyName = f.name
                                runCatching { client?.deleteFile(f.name) }
                                    .onSuccess { vm.toast("Deleted ${f.name}") }
                                    .onFailure { vm.toast(it.message ?: "Delete failed") }
                                refresh()
                                busyName = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: FileItem, busy: Boolean, onInstall: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.isApk) Icons.Rounded.Android else Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(file.appName?.takeIf { it.isNotBlank() } ?: file.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    buildString {
                        append(file.formattedSize)
                        file.appVersion?.takeIf { it.isNotBlank() }?.let { append(" · v").append(it) }
                        if (file.isApk && file.appName != null) append(" · ").append(file.name)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (file.isApk) {
                TextButton(onClick = onInstall, enabled = !busy) { Text(if (busy) "…" else "Install") }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Reads the picked document into memory as (name, bytes). */
private fun readUri(context: Context, uri: Uri): Pair<String, ByteArray>? {
    val name = queryDisplayName(context, uri) ?: "upload.bin"
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
    return name to bytes
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
