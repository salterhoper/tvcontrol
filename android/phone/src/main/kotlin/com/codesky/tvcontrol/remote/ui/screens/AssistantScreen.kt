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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.AssistantRun
import com.codesky.tvcontrol.remote.data.AssistantStep
import com.codesky.tvcontrol.remote.data.ChatMessage
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Open YouTube and search for lofi beats",
    "Go to the home screen",
    "Turn the volume down",
    "What's on the screen right now?",
)

@Composable
fun AssistantScreen(
    vm: RemoteViewModel,
    state: UiState,
    onOpenSettings: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val llm = state.settings?.llm
    val localModel = llm != null &&
        Regex("localhost|127\\.0\\.0\\.1|192\\.168\\.|http://10\\.").containsMatchIn(llm.baseUrl)
    val configured = llm == null || llm.apiKeyConfigured || localModel // null = settings not loaded yet; don't nag

    fun send(text: String) {
        if (text.isBlank()) return
        vm.sendAssistant(text)
        input = ""
    }

    LaunchedEffect(state.chat.size) {
        if (state.chat.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.chat.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text("Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.chat.isNotEmpty()) {
                TextButton(onClick = { vm.clearChat() }) { Text("New chat") }
            }
        }

        if (!configured) {
            ConfigBanner(onOpenSettings)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.chat.isEmpty()) {
                EmptyState(onPick = { send(it) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.chat, key = { it.id }) { msg -> ChatBubble(msg) }
                }
            }
        }

        // Current-TV context
        val ctx = buildString {
            append(state.screen?.app ?: "TV")
            state.screen?.focus?.text?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        Text(
            "Sees: $ctx",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask the TV to do something…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier.weight(1f),
            )
            if (state.assistantRunning) {
                FilledIconButton(onClick = { vm.cancelAssistant() }) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ConfigBanner(onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "No model is configured. Add a provider, model and API key in Settings - or point the base URL at a model server on your network.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Settings")
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Ask the TV anything", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "The assistant reads the screen, plans button presses and performs them. It only does what you could with the remote.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        SUGGESTIONS.forEach { s ->
            AssistChip(
                onClick = { onPick(s) },
                label = { Text(s) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    if (msg.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(msg.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                AssistantBody(msg.run, msg.text)
            }
        }
    }
}

@Composable
private fun AssistantBody(run: AssistantRun?, text: String) {
    Column(Modifier.padding(14.dp)) {
        val running = run?.status == "running"
        if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        } else if (running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val steps = run?.steps.orEmpty()
        if (steps.isNotEmpty()) {
            Spacer(Modifier.size(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { StepChip(it) }
            }
        }

        if (!run?.error.isNullOrBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(run!!.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (running && steps.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(6.dp))
                Text("Working…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepChip(step: AssistantStep) {
    val (icon, tint) = when (step.status) {
        "ok" -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        "failed" -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        "running" -> Icons.Rounded.HourglassEmpty to MaterialTheme.colorScheme.tertiary
        else -> Icons.Rounded.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = step.status, tint = tint, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.detail ?: step.action,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!step.note.isNullOrBlank()) {
                    Text(step.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

