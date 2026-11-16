package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.ProxyServer
import com.codesky.tvcontrol.remote.data.ProxyState
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class ProxyForm(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "1080",
    val username: String = "",
    val password: String = "",
    val hasStoredPassword: Boolean = false,
)

@Composable
fun ProxyScreen(vm: RemoteViewModel) {
    val client = vm.currentClient
    var state by remember { mutableStateOf<ProxyState?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<ProxyForm?>(null) }
    val scope = rememberCoroutineScope()

    // Poll for live stats and to notice the TV-side VPN consent being accepted.
    LaunchedEffect(client) {
        while (true) {
            runCatching { client?.proxy() }.getOrNull()?.let { state = it }
            delay(3000)
        }
    }

    suspend fun act(label: String, block: suspend () -> ProxyState?) {
        busy = label
        runCatching { block() }
            .onSuccess { if (it != null) { state = it; it.note?.let(vm::toast) } }
            .onFailure { vm.toast(it.message ?: "The TV did not accept that") }
        busy = null
    }

    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val active = s.servers.firstOrNull { it.active }
    val statusText = when {
        s.running -> "On"
        s.paused -> "Paused"
        s.enabled -> "Starting"
        else -> "Off"
    }

    DetailColumn {
        SettingsCard("Route traffic through a proxy (SOCKS5)") {
            KeyValueRow("Status", statusText, if (s.running) Tone.Ok else if (s.enabled) Tone.Warn else Tone.Normal)
            Text(s.verdict, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionList(s.actions)
            if (s.running && s.stats != null) {
                KeyValueRow("Exit", active?.lastTest?.exitIp ?: "${active?.host}:${active?.port}")
                KeyValueRow("Traffic", "↑ ${formatBytes(s.stats.bytesUp)} · ↓ ${formatBytes(s.stats.bytesDown)}")
                KeyValueRow("Connections", "${s.stats.activeConnections} open · ${s.stats.totalConnections} total")
            }
        }

        if (s.servers.isNotEmpty()) {
            SettingsCard("Saved servers") {
                s.servers.forEach { server ->
                    ProxyRow(
                        server = server,
                        busy = busy,
                        onUse = { scope.launch { act("select-${server.id}") { client?.selectProxy(server.id) } } },
                        onTest = { scope.launch { act("test-${server.id}") { client?.testProxyServer(server.id) } } },
                        onEdit = {
                            form = ProxyForm(
                                id = server.id, name = server.name, host = server.host,
                                port = server.port.toString(), username = server.username ?: "",
                                hasStoredPassword = server.hasPassword,
                            )
                        },
                        onDelete = { scope.launch { act("delete-${server.id}") { client?.deleteProxyServer(server.id) } } },
                    )
                }
            }
        }

        val currentForm = form
        if (currentForm != null) {
            ProxyFormCard(
                form = currentForm,
                onChange = { form = it },
                busy = busy == "save",
                onCancel = { form = null },
                onSave = {
                    scope.launch {
                        val patch = buildJsonObject {
                            currentForm.id?.let { put("id", it) }
                            put("name", currentForm.name.trim())
                            put("host", currentForm.host.trim())
                            put("port", currentForm.port.toIntOrNull() ?: 1080)
                            put("username", currentForm.username.trim())
                            // omit password when editing and left blank -> keeps the stored one
                            if (currentForm.id == null || currentForm.password.isNotEmpty()) {
                                put("password", currentForm.password)
                            }
                        }
                        act("save") { client?.saveProxyServer(patch) }
                        form = null
                    }
                },
            )
        } else {
            OutlinedButton(onClick = { form = ProxyForm() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add SOCKS5 server")
            }
        }

        if (s.servers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.enabled) {
                    Button(
                        onClick = { scope.launch { act("toggle") { client?.setProxyEnabled(false) } } },
                        enabled = busy == null,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        modifier = Modifier.weight(1f),
                    ) { Text("Turn routing off") }
                } else {
                    Button(
                        onClick = { scope.launch { act("toggle") { client?.setProxyEnabled(true) } } },
                        enabled = busy == null && active != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy == "toggle") "Starting…" else "Route via ${active?.name ?: "proxy"}") }
                }
            }
            ToggleRow(
                "Kill switch",
                "If the proxy dies, the TV stays offline instead of going direct",
                checked = !s.failOpen,
            ) { killSwitch -> scope.launch { act("options") { client?.setProxyFailOpen(!killSwitch) } } }
        }

        Text(
            "Sends every app on the TV through the selected server using Android's VPN slot (a one-time OK on the TV). Tap “Use” to switch servers; your home network stays direct so this app keeps working.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProxyRow(
    server: ProxyServer,
    busy: String?,
    onUse: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (server.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (server.active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name.ifBlank { server.host }, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${server.host}:${server.port}${server.username?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    server.lastTest?.let { t ->
                        Text(
                            if (t.ok) "${t.latencyMs ?: "?"} ms${t.exitIp?.let { " · $it" } ?: ""}" else t.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete, enabled = busy == null) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUse, enabled = busy == null && !server.active) {
                    Text(if (busy == "select-${server.id}") "Switching…" else if (server.active) "In use" else "Use")
                }
                TextButton(onClick = onTest, enabled = busy == null) {
                    Text(if (busy == "test-${server.id}") "Testing…" else "Test")
                }
            }
        }
    }
}

@Composable
private fun ProxyFormCard(
    form: ProxyForm,
    onChange: (ProxyForm) -> Unit,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var paste by remember { mutableStateOf("") }
    SettingsCard(if (form.id == null) "Add server" else "Edit server") {
        if (form.id == null) {
            OutlinedTextField(
                value = paste,
                onValueChange = { pasted ->
                    paste = pasted
                    parseProxy(pasted)?.let { onChange(form.copy(host = it.first, port = it.second.toString(), username = it.third, password = it.fourth)) }
                },
                label = { Text("Paste socks5://user:pass@host:port (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(form.name, { onChange(form.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(form.host, { onChange(form.copy(host = it)) }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(
                form.port, { onChange(form.copy(port = it.filter(Char::isDigit).take(5))) },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(form.username, { onChange(form.copy(username = it)) }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            form.password, { onChange(form.copy(password = it)) },
            label = { Text(if (form.hasStoredPassword) "Password (stored — type to replace)" else "Password (optional)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = onSave, enabled = !busy && form.host.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text(if (busy) "Saving…" else if (form.id == null) "Add" else "Save")
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** Accepts socks5://user:pass@host:port, host:port:user:pass, or host:port. */
private fun parseProxy(text: String): Quad<String, Int, String, String>? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    val schemeMatch = Regex("^socks5h?://(?:([^:@]+)(?::([^@]*))?@)?([^:/]+)(?::(\\d+))?", RegexOption.IGNORE_CASE).find(raw)
    if (schemeMatch != null) {
        val (user, pass, host, port) = schemeMatch.destructured
        return Quad(host, port.toIntOrNull() ?: 1080, user, pass)
    }
    val parts = raw.split(":")
    if (parts.size == 2 || parts.size == 4) {
        val port = parts[1].toIntOrNull() ?: return null
        return Quad(parts[0], port, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" })
    }
    return null
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return "${"%.1f".format(value)} ${units[unit]}"
}
