package com.codesky.tvcontrol.remote.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.AssistantRun
import com.codesky.tvcontrol.remote.data.AssistantStep
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState

private val suggestions = listOf(
    "Open YouTube and search for lofi beats",
    "Go to the home screen",
    "Open Netflix",
    "Scroll down",
)

@Composable
fun AssistantScreen(vm: RemoteViewModel, state: UiState) {
    var utterance by rememberSaveable { mutableStateOf("") }
    val run = state.run
    val running = run?.status == "running"

    fun send(text: String) {
        if (text.isBlank()) return
        vm.runAssistant(text)
        utterance = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "Describe what you want. The assistant reads the TV screen and presses the buttons for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = utterance,
            onValueChange = { utterance = it },
            label = { Text("Ask the TV…") },
            trailingIcon = {
                IconButton(onClick = { send(utterance) }, enabled = utterance.isNotBlank() && !running) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send(utterance) }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { s ->
                AssistChip(onClick = { send(s) }, label = { Text(s) }, enabled = !running)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (run != null) {
            RunCard(
                run = run,
                running = running,
                onCancel = { vm.cancelAssistant() },
                onClear = { vm.clearRun() },
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Your request and its steps will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunCard(
    run: AssistantRun,
    running: Boolean,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    run.utterance.ifBlank { "Assistant" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(run.status)
            }

            if (!run.say.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(run.say, style = MaterialTheme.typography.bodyMedium)
            }
            if (!run.error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(run.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            if (run.steps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                run.steps.forEach { StepRow(it) }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    OutlinedButton(onClick = onCancel) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AssistantStep) {
    val (icon, tint) = when (step.status) {
        "ok" -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        "failed" -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        "running" -> Icons.Rounded.HourglassEmpty to MaterialTheme.colorScheme.tertiary
        else -> Icons.Rounded.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = step.status, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(step.detail ?: step.action, style = MaterialTheme.typography.bodyMedium)
            if (!step.note.isNullOrBlank()) {
                Text(step.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color: Color = when (status) {
        "done" -> MaterialTheme.colorScheme.primary
        "failed", "cancelled" -> MaterialTheme.colorScheme.error
        "partial", "needs_input" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f), contentColor = color) {
        Text(
            status.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
