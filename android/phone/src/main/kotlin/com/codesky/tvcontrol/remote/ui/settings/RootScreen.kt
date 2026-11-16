package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.codesky.tvcontrol.remote.data.RootReport
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.launch

@Composable
fun RootScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    var report by remember { mutableStateOf<RootReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client) {
        loading = true
        report = runCatching { client?.rootCheck() }.getOrNull()
        loading = false
    }

    val r = report
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("No report.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    DetailColumn {
        SettingsCard(r.headline.ifBlank { "Root status" }) {
            Text(r.verdict, style = MaterialTheme.typography.bodyMedium)
            KeyValueRow("Level", r.level, if (r.appCanUseRoot) Tone.Ok else Tone.Normal)
            ActionList(r.actions)
        }

        if (r.checks.isNotEmpty()) {
            SettingsCard("Evidence") {
                r.checks.forEach { c ->
                    Column(Modifier.fillMaxWidth()) {
                        KeyValueRow(
                            c.label,
                            when (c.found) {
                                true -> "found"
                                false -> "no"
                                null -> "unknown"
                            },
                        )
                        if (c.detail.isNotBlank()) {
                            Text(c.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { scope.launch { report = runCatching { client?.rootCheck() }.getOrNull() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Re-check") }
    }
}
