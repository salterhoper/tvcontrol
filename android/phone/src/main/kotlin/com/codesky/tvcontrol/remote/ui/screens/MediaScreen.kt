package com.codesky.tvcontrol.remote.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.NowPlayingInfo
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState
import com.codesky.tvcontrol.remote.ui.components.ControlButton

@Composable
fun MediaScreen(vm: RemoteViewModel, state: UiState) {
    LaunchedEffect(Unit) { vm.refreshNowPlaying() }

    val np = state.nowPlaying
    if (np == null || !np.active) {
        EmptyMedia()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(np, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Text(
            np.title ?: "Playing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!np.artist.isNullOrBlank()) {
            Text(
                np.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!np.appName.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(np.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))

        if (np.canSeek && np.durationMs > 0) {
            SeekBar(np) { pos -> vm.controlMedia("seek", pos) }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Icons.Rounded.SkipPrevious, "Previous", np.canSkipPrev, Modifier.weight(1f)) { vm.controlMedia("previous") }
            TransportButton(Icons.Rounded.FastRewind, "Rewind", true, Modifier.weight(1f)) { vm.controlMedia("rewind") }
            ControlButton(
                onClick = { vm.controlMedia("play_pause") },
                enabled = np.canPlayPause,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1.4f).height(64.dp),
            ) {
                Icon(
                    if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Play or pause",
                    modifier = Modifier.size(34.dp),
                )
            }
            TransportButton(Icons.Rounded.FastForward, "Fast forward", true, Modifier.weight(1f)) { vm.controlMedia("fast_forward") }
            TransportButton(Icons.Rounded.SkipNext, "Next", np.canSkipNext, Modifier.weight(1f)) { vm.controlMedia("next") }
        }
    }
}

@Composable
private fun SeekBar(np: NowPlayingInfo, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val fraction = if (dragging >= 0f) dragging
    else (np.positionMs.toFloat() / np.durationMs.toFloat()).coerceIn(0f, 1f)

    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (dragging >= 0f) {
                    onSeek((dragging * np.durationMs).toLong())
                    dragging = -1f
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs((fraction * np.durationMs).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(np.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Artwork(np: NowPlayingInfo, modifier: Modifier = Modifier) {
    val bitmap = remember(np.artworkBase64) { decodeArtwork(np.artworkBase64) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.aspectRatio(1f),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
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
        modifier = modifier.height(56.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun EmptyMedia() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Nothing is playing", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start something on the TV and the controls show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun decodeArtwork(data: String?): android.graphics.Bitmap? {
    if (data.isNullOrBlank()) return null
    return runCatching {
        val base64 = data.substringAfter(",", data)
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
