package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.codesky.tvcontrol.remote.data.DiagnosticsReport
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        report = runCatching { client?.diagnostics() }.getOrNull()
    }

    LaunchedEffect(client) {
        loading = true
        reload()
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
        SettingsCard("Can this TV be controlled?") {
            Text(r.verdict, style = MaterialTheme.typography.bodyMedium)
            ActionList(r.actions)
        }

        SettingsCard("Details") {
            KeyValueRow("Android", "${r.androidRelease} · SDK ${r.sdk}${r.buildType?.let { " · $it" } ?: ""}")
            KeyValueRow("Model", r.model)
            KeyValueRow("Installed by", r.installer ?: "unknown (sideloaded)")
            KeyValueRow("Play Store / services", "${if (r.playStoreInstalled) "yes" else "no"} / ${if (r.playServicesInstalled) "yes" else "no"}")
            KeyValueRow("Google remote service", if (r.googleRemoteServiceInstalled) "installed" else "not found")
            KeyValueRow(
                "Accessibility blocked",
                if (r.restrictedSettings) "yes - install source" else "no",
                if (r.restrictedSettings) Tone.Warn else Tone.Ok,
            )
            KeyValueRow("Engine in Android's list", if (r.serviceListedAsEnabled == true) "yes" else "no")
            KeyValueRow("Engine running", if (r.accessibilityConnected) "yes" else "no", if (r.accessibilityConnected) Tone.Ok else Tone.Normal)
            KeyValueRow("Can write system settings", if (r.writeSecureSettingsGranted == true) "granted" else "not granted")
            KeyValueRow("ADB debugging", if (r.adbDebuggingEnabled) "on" else "off")
            KeyValueRow(
                "Platform test keys",
                if (r.platformSignedWithTestKeys == true) "yes" else "no",
                if (r.platformSignedWithTestKeys == true) Tone.Ok else Tone.Normal,
            )
        }

        if (r.writeSecureSettingsGranted == true && !r.accessibilityConnected) {
            Button(
                onClick = {
                    scope.launch {
                        val res = runCatching { client?.enableEngine() }.getOrNull()
                        vm.toast(res?.message ?: "Could not turn the engine on")
                        reload()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Turn the engine on from here") }
        }

        OutlinedButton(
            onClick = { scope.launch { reload() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Re-check") }
    }
}
