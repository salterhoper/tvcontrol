@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codesky.tvcontrol.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.DiscoveredTv
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState

@Composable
fun ConnectScreen(
    vm: RemoteViewModel,
    state: UiState,
    snackbarHostState: SnackbarHostState,
) {
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(84.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SettingsRemote, contentDescription = null, modifier = Modifier.size(44.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "TV Remote",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Drive your Android TV from your phone. Make sure the TV app is open and you are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(24.dp))

                // ---- Discovered TVs
                DiscoveredSection(state, onPick = vm::beginPairing, onRefresh = {
                    vm.stopDiscovery(); vm.startDiscovery()
                })

                Spacer(Modifier.height(20.dp))

                // ---- Manual entry
                ManualSection(state, vm)

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    state.pairingHost?.let { host ->
        PinDialog(
            tvName = state.pairingTvName,
            host = host,
            pairing = state.pairing,
            error = state.connectError,
            onSubmit = vm::submitPin,
            onDismiss = vm::cancelPairing,
        )
    }
}

@Composable
private fun DiscoveredSection(
    state: UiState,
    onPick: (DiscoveredTv) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Cast,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "TVs on your network",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (state.discovering && state.discovered.isEmpty()) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Search again",
                modifier = Modifier
                    .size(22.dp)
                    .clickableNoRipple(onRefresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    if (state.discovered.isEmpty()) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    if (state.discovering) "Looking for TVs…" else "No TVs found yet. Enter the address below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.discovered.forEach { tv ->
                ElevatedCard(
                    onClick = { onPick(tv) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tv.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Pair", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSection(state: UiState, vm: RemoteViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text("Enter the address", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = state.manualHost,
        onValueChange = vm::onManualHostChange,
        label = { Text("TV address") },
        placeholder = { Text("192.168.1.42:8787") },
        singleLine = true,
        isError = state.connectError != null && state.pairingHost == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.connectError != null && state.pairingHost == null) {
        Text(
            state.connectError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { vm.beginPairing(state.manualHost) },
        enabled = !state.checking && state.manualHost.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (state.checking) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Connect", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PinDialog(
    tvName: String,
    host: String,
    pairing: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!pairing) onDismiss() },
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Pair with $tvName") },
        text = {
            Column {
                Text(
                    "Enter the PIN shown on the TV screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) pin = it.filter(Char::isDigit) },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !pairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(pin) }, enabled = !pairing && pin.length >= 4) {
                if (pairing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Pair")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pairing) { Text("Cancel") }
        },
    )
}

/** A tap target without the material ripple, for small inline affordances. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    androidx.compose.foundation.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    ),
)
