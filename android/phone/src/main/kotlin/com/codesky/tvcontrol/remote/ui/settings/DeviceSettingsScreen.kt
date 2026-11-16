package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun DeviceSettingsScreen(vm: RemoteViewModel, state: UiState) {
    val client = vm.currentClient
    val settings = state.settings
    var name by remember(settings) { mutableStateOf(settings?.deviceName ?: "") }
    var port by remember(settings) { mutableStateOf((settings?.port ?: 8787).toString()) }
    var autoStart by remember(settings) { mutableStateOf(settings?.autoStart ?: true) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DetailColumn {
        SettingsCard("This TV") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                label = { Text("Port (applies at next restart)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("Start on boot", "Bring the server up when the TV reboots", autoStart) { autoStart = it }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val patch = buildJsonObject {
                            put("deviceName", name)
                            put("port", port.toIntOrNull() ?: 8787)
                            put("autoStart", autoStart)
                        }
                        runCatching { client?.updateSettings(patch) }
                            .onSuccess { if (it != null) { vm.setSettingsState(it); vm.toast("Settings saved") } }
                            .onFailure { vm.toast(it.message ?: "Could not save") }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Saving…" else "Save") }
        }

        SettingsCard("Security") {
            Text(
                "Paired devices: ${settings?.devices?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { client?.rotatePin() }
                                .onSuccess { if (it != null) { vm.setSettingsState(it); vm.toast("New PIN: ${it.pin}") } }
                                .onFailure { vm.toast(it.message ?: "Could not rotate PIN") }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("New PIN") }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { client?.revokeDevices() }
                            vm.toast("All paired devices were signed out")
                            vm.disconnect()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Sign out all") }
            }
        }
    }
}
