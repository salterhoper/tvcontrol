package com.codesky.tvcontrol.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.RemoteKey
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState
import com.codesky.tvcontrol.remote.ui.components.ControlButton
import com.codesky.tvcontrol.remote.ui.components.Dpad
import com.codesky.tvcontrol.remote.ui.rememberHaptic

@Composable
fun RemotePadScreen(
    vm: RemoteViewModel,
    state: UiState,
) {
    val haptic = rememberHaptic(state.hapticsEnabled)
    val navEnabled = state.engineReady

    fun key(k: RemoteKey) {
        haptic()
        vm.pressKey(k)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!navEnabled) {
            EngineOffCard()
            Spacer(Modifier.height(16.dp))
        }

        // D-pad
        Dpad(
            enabled = navEnabled,
            onKey = { vm.pressKey(it) },
            onHaptic = haptic,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        // Back / Home / Recents
        Row(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundControl(Icons.AutoMirrored.Rounded.ArrowBack, "Back", navEnabled, Modifier.weight(1f)) { key(RemoteKey.Back) }
            RoundControl(Icons.Rounded.Home, "Home", navEnabled, Modifier.weight(1f)) { key(RemoteKey.Home) }
            RoundControl(Icons.Rounded.Dashboard, "Recents", navEnabled, Modifier.weight(1f)) { key(RemoteKey.Recents) }
        }

        Spacer(Modifier.height(20.dp))

        // Volume
        VolumeCard(
            level = state.volumePercent,
            muted = state.muted,
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            onDown = { haptic(); vm.volume("down") },
            onUp = { haptic(); vm.volume("up") },
            onMute = { haptic(); vm.volume("mute") },
        )

        Spacer(Modifier.height(16.dp))

        // Transport
        val playing = state.nowPlaying?.isPlaying == true
        Row(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(Icons.Rounded.SkipPrevious, "Previous", true, Modifier.weight(1f)) { key(RemoteKey.Previous) }
            RoundControl(Icons.Rounded.FastRewind, "Rewind", true, Modifier.weight(1f)) { key(RemoteKey.Rewind) }
            ControlButton(
                onClick = { key(RemoteKey.PlayPause) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1.3f).height(60.dp),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Play or pause",
                    modifier = Modifier.size(32.dp),
                )
            }
            RoundControl(Icons.Rounded.FastForward, "Fast forward", true, Modifier.weight(1f)) { key(RemoteKey.FastForward) }
            RoundControl(Icons.Rounded.SkipNext, "Next", true, Modifier.weight(1f)) { key(RemoteKey.Next) }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ControlButton(
        onClick = onClick,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(56.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun VolumeCard(
    level: Int?,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onMute: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundControl(Icons.Rounded.VolumeDown, "Volume down", true, Modifier.weight(1f), onDown)
            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Volume", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (muted) "Muted" else level?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RoundControl(Icons.Rounded.VolumeUp, "Volume up", true, Modifier.weight(1f), onUp)
            RoundControl(Icons.Rounded.VolumeOff, if (muted) "Unmute" else "Mute", true, Modifier.weight(1f), onMute)
        }
    }
}

@Composable
private fun EngineOffCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null)
            Text(
                "The TV's accessibility engine is off, so navigation and typing won't work yet. " +
                    "Turn on \"TV Control remote\" in the TV's Accessibility settings. Volume and playback still work.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
