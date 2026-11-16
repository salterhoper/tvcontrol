@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.AdBlockState
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.launch

@Composable
fun AdBlockScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    var ab by remember { mutableStateOf<AdBlockState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun adopt(next: AdBlockState, toastNote: Boolean) {
        ab = next
        host = next.chosenHostname
        custom = next.presets.none { it.hostname == next.chosenHostname }
        if (toastNote) next.note?.let { vm.toast(it) }
    }

    LaunchedEffect(client) {
        loading = true
        runCatching { client?.adblock() }.getOrNull()?.let { adopt(it, toastNote = false) }
        loading = false
    }

    val s = ab
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("Not available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val lastCheck = when {
        s.lastCheck == null -> "never checked"
        !s.lastCheck.normalResolves -> "DNS not answering"
        s.lastCheck.adBlocked -> "ad domains blocked"
        else -> "not blocking"
    }

    DetailColumn {
        SettingsCard("Ad blocking (Private DNS)") {
            Text(s.verdict, style = MaterialTheme.typography.bodyMedium)
            ActionList(s.actions)
            KeyValueRow("Private DNS", if (s.enabled) "on · ${s.hostname ?: "?"}" else "off · network DNS", if (s.enabled) Tone.Ok else Tone.Normal)
            KeyValueRow("Last check", lastCheck, if (s.lastCheck != null && s.enabled) (if (s.lastCheck.adBlocked) Tone.Ok else Tone.Warn) else Tone.Normal)

            if (s.enabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { client?.adblock(true) }.getOrNull()?.let { ab = it; it.lastCheck?.note?.let(vm::toast) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "Checking…" else "Check now") }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { client?.setAdblock(false) }
                                    .onSuccess { if (it != null) adopt(it, toastNote = true) }
                                    .onFailure { vm.toast(it.message ?: "Could not turn off") }
                                busy = false
                            }
                        },
                        enabled = !busy && s.canWrite,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        modifier = Modifier.weight(1f),
                    ) { Text("Turn off") }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.presets.forEach { preset ->
                        FilterChip(
                            selected = !custom && host == preset.hostname,
                            onClick = { custom = false; host = preset.hostname },
                            label = { Text(preset.label) },
                        )
                    }
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom…") })
                }
                if (custom) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Resolver hostname") },
                        placeholder = { Text("dns.adguard-dns.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    s.presets.firstOrNull { it.hostname == host }?.note?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { client?.setAdblock(true, host.trim()) }
                                .onSuccess { if (it != null) adopt(it, toastNote = true) }
                                .onFailure { vm.toast(it.message ?: "Could not turn on") }
                            busy = false
                        }
                    },
                    enabled = !busy && s.canWrite && host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Applying…" else "Turn on") }
            }

            Text(
                "Points the TV's Private DNS at a filtering resolver, so every app using the TV's DNS loses ad and tracker domains. YouTube's in-video ads are not affected. If ordinary names stop resolving, the app reverts automatically.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
