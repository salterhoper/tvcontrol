@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codesky.tvcontrol.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val PROVIDER_DEFAULTS = mapOf(
    "openai" to ("https://api.openai.com/v1" to "gpt-4o-mini"),
    "gemini" to ("https://generativelanguage.googleapis.com/v1beta" to "gemini-2.0-flash"),
    "anthropic" to ("https://api.anthropic.com/v1" to "claude-3-5-haiku-latest"),
)

@Composable
fun AiSettingsScreen(vm: RemoteViewModel, state: UiState) {
    val client = vm.currentClient
    val s = state.settings
    var provider by remember(s) { mutableStateOf(s?.llm?.provider ?: "openai") }
    var baseUrl by remember(s) { mutableStateOf(s?.llm?.baseUrl ?: "") }
    var model by remember(s) { mutableStateOf(s?.llm?.model ?: "") }
    var apiKey by remember(s) { mutableStateOf("") }
    var mode by remember(s) { mutableStateOf(s?.assistant?.mode ?: "plan") }
    var maxSteps by remember(s) { mutableStateOf((s?.assistant?.maxSteps ?: 12).toString()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val providers = s?.llm?.providers?.takeIf { it.isNotEmpty() } ?: listOf("openai", "gemini", "anthropic")
    val keyConfigured = s?.llm?.apiKeyConfigured == true

    DetailColumn {
        SettingsCard("Model provider") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            provider = p
                            PROVIDER_DEFAULTS[p]?.let { (url, m) -> baseUrl = url; model = m }
                        },
                        label = { Text(p) },
                    )
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (keyConfigured) "API key (stored — type to replace)" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Anything that speaks the OpenAI API works, including a local model server (leave the key empty for those).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Behaviour") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "plan", onClick = { mode = "plan" }, label = { Text("Plan (one shot)") })
                FilterChip(selected = mode == "agent", onClick = { mode = "agent" }, label = { Text("Agent (self-corrects)") })
            }
            OutlinedTextField(
                value = maxSteps,
                onValueChange = { maxSteps = it.filter(Char::isDigit).take(2) },
                label = { Text("Max steps") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Plan spends fewer tokens; agent re-reads the screen and corrects itself once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val patch = buildJsonObject {
                        putJsonObject("llm") {
                            put("provider", provider)
                            put("baseUrl", baseUrl.trim())
                            put("model", model.trim())
                            if (apiKey.isNotBlank()) put("apiKey", apiKey.trim())
                        }
                        putJsonObject("assistant") {
                            put("mode", mode)
                            put("maxSteps", maxSteps.toIntOrNull()?.coerceIn(1, 40) ?: 12)
                        }
                    }
                    runCatching { client?.updateSettings(patch) }
                        .onSuccess { if (it != null) { vm.setSettingsState(it); apiKey = ""; vm.toast("Settings saved") } }
                        .onFailure { vm.toast(it.message ?: "Could not save") }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Saving…" else "Save settings") }
    }
}

