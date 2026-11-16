package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.BluetoothDeviceDto
import com.codesky.tvcontrol.remote.data.BluetoothStatusDto
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BluetoothScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    var status by remember { mutableStateOf<BluetoothStatusDto?>(null) }
    var busyAddr by remember { mutableStateOf<String?>(null) }
    var powerBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { client?.bluetooth() }.getOrNull()?.let { status = it }
    }

    LaunchedEffect(client) {
        while (true) {
            refresh()
            delay(2500)
        }
    }

    val st = status
    val enabled = st?.enabled == true

    DetailColumn {
        SettingsCard(st?.name?.ifBlank { "TV Bluetooth" } ?: "TV Bluetooth") {
            KeyValueRow("Adapter", if (enabled) "on" else "off", if (enabled) Tone.Ok else Tone.Normal)
            st?.address?.takeIf { it.isNotBlank() }?.let { KeyValueRow("MAC", it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            powerBusy = true
                            val next = !enabled
                            runCatching { client?.setBluetoothState(next) }
                                .onSuccess { if (it?.ok == true) vm.toast(if (next) "Bluetooth on" else "Bluetooth off") else vm.toast(it?.error ?: "Could not change Bluetooth") }
                                .onFailure { vm.toast(it.message ?: "Failed") }
                            refresh()
                            powerBusy = false
                        }
                    },
                    enabled = !powerBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (enabled) "Turn off" else "Turn on") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { client?.launchTvPairing() }
                                .onSuccess { vm.toast(if (it?.ok == true) "Opened TV pairing screen" else (it?.error ?: "Could not open")) }
                                .onFailure { vm.toast(it.message ?: "Failed") }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  On TV")
                }
            }
        }

        val paired = st?.pairedDevices.orEmpty()
        SettingsCard("Paired devices") {
            when {
                !enabled -> Text("Bluetooth is off. Turn it on to manage devices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                paired.isEmpty() -> Text("No paired devices yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> paired.forEach { d ->
                    DeviceRow(
                        device = d,
                        busy = busyAddr == d.address,
                        primaryLabel = if (d.connected) "Disconnect" else "Connect",
                        onPrimary = {
                            scope.launch {
                                busyAddr = d.address
                                val path = if (d.connected) "disconnect" else "connect"
                                runCatching { client?.bluetoothDeviceAction(path, d.address) }
                                    .onSuccess { vm.toast(it?.message ?: it?.error ?: "Done") }
                                    .onFailure { vm.toast(it.message ?: "Failed") }
                                delay(1200); refresh(); busyAddr = null
                            }
                        },
                        onDelete = {
                            scope.launch {
                                busyAddr = d.address
                                runCatching { client?.bluetoothDeviceAction("unpair", d.address) }
                                    .onSuccess { vm.toast("Unpaired ${d.name}") }
                                    .onFailure { vm.toast(it.message ?: "Failed") }
                                refresh(); busyAddr = null
                            }
                        },
                    )
                }
            }
        }

        SettingsCard("Nearby devices") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st?.scanning == true) "Scanning…" else "Not scanning",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        scope.launch {
                            val start = st?.scanning != true
                            runCatching { client?.scanBluetooth(if (start) "start" else "stop") }
                                .onFailure { vm.toast(it.message ?: "Scan failed") }
                            refresh()
                        }
                    },
                    enabled = enabled,
                ) { Text(if (st?.scanning == true) "Stop" else "Scan") }
            }
            val nearby = st?.discoveredDevices.orEmpty()
            if (enabled) {
                if (nearby.isEmpty()) {
                    Text(
                        "Put a headphone, speaker, gamepad or remote into pairing mode, then tap Scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    nearby.forEach { d ->
                        DeviceRow(
                            device = d,
                            busy = busyAddr == d.address,
                            primaryLabel = "Pair",
                            onPrimary = {
                                scope.launch {
                                    busyAddr = d.address
                                    runCatching { client?.bluetoothDeviceAction("pair", d.address) }
                                        .onSuccess { vm.toast(it?.message ?: "Pairing ${d.name}…") }
                                        .onFailure { vm.toast(it.message ?: "Failed") }
                                    delay(2000); refresh(); busyAddr = null
                                }
                            },
                            onDelete = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BluetoothDeviceDto,
    busy: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(device.deviceType), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name.ifBlank { device.address }, fontWeight = FontWeight.Medium)
                Text(
                    device.address + (device.rssi?.let { " · $it dBm" } ?: "") + if (device.connected) " · connected" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPrimary, enabled = !busy) { Text(if (busy) "…" else primaryLabel) }
            if (onDelete != null) {
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Unpair", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "audio" -> Icons.Rounded.Headphones
    "input" -> Icons.Rounded.SportsEsports
    else -> Icons.Rounded.Bluetooth
}
