@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codesky.tvcontrol.remote.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState

enum class SettingsSection(val title: String, val subtitle: String, val icon: ImageVector) {
    Ai("Assistant & model", "Provider, model, API key, behaviour", Icons.Rounded.AutoAwesome),
    AdBlock("Ad blocking", "Private DNS filtering for the whole TV", Icons.Rounded.Block),
    Proxy("Proxy routing", "Route the TV through a SOCKS5 server", Icons.Rounded.Router),
    Bluetooth("Bluetooth", "Audio, gamepads, keyboards", Icons.Rounded.Bluetooth),
    Files("Files & sideload", "Send files and install APKs over Wi-Fi", Icons.Rounded.FolderOpen),
    Device("This TV & pairing", "Name, port, PIN, paired devices", Icons.Rounded.Tv),
    Diagnostics("Diagnostics", "Can this TV be controlled?", Icons.Rounded.MedicalServices),
    Root("Root status", "Is the TV rooted?", Icons.Rounded.Security),
    AppPrefs("App preferences", "Haptics, colours, disconnect", Icons.Rounded.Palette),
}

private val HUB_ORDER = listOf(
    SettingsSection.Ai,
    SettingsSection.AdBlock,
    SettingsSection.Proxy,
    SettingsSection.Bluetooth,
    SettingsSection.Files,
    SettingsSection.Device,
    SettingsSection.Diagnostics,
    SettingsSection.Root,
    SettingsSection.AppPrefs,
)

@Composable
fun SettingsScreen(vm: RemoteViewModel, state: UiState) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    if (section != null) {
        BackHandler { section = null }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader(
            title = section?.title ?: "Settings",
            onBack = if (section != null) ({ section = null }) else null,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                when (section) {
                    null -> Hub(state) { section = it }
                    SettingsSection.Ai -> AiSettingsScreen(vm, state)
                    SettingsSection.AdBlock -> AdBlockScreen(vm)
                    SettingsSection.Proxy -> ProxyScreen(vm)
                    SettingsSection.Bluetooth -> BluetoothScreen(vm)
                    SettingsSection.Files -> FilesScreen(vm)
                    SettingsSection.Device -> DeviceSettingsScreen(vm, state)
                    SettingsSection.Diagnostics -> DiagnosticsScreen(vm)
                    SettingsSection.Root -> RootScreen(vm)
                    SettingsSection.AppPrefs -> AppPrefsScreen(vm, state)
                }
            }
        }
    }
}

@Composable
private fun Hub(state: UiState, onOpen: (SettingsSection) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(state.tvName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${if (state.tvVersion.isNotBlank()) "v${state.tvVersion} · " else ""}${if (state.engineReady) "engine ready" else "engine off"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HUB_ORDER.forEach { s -> HubRow(s, onClick = { onOpen(s) }) }
    }
}

@Composable
private fun HubRow(section: SettingsSection, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(section.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                if (section.subtitle.isNotBlank()) {
                    Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------- shared pieces

@Composable
fun SettingsHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** A titled card container used by every settings detail screen. */
@Composable
fun SettingsCard(title: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, tone: Tone = Tone.Normal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when (tone) {
                Tone.Ok -> MaterialTheme.colorScheme.primary
                Tone.Warn -> MaterialTheme.colorScheme.error
                Tone.Normal -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

enum class Tone { Normal, Ok, Warn }

@Composable
fun ActionList(actions: List<String>) {
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEachIndexed { i, a ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Standard scrollable column padding for detail screens. */
@Composable
fun DetailColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
