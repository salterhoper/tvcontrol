@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codesky.tvcontrol.remote.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.RemoteKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A press-and-hold button. Fires once on touch, then repeats while held - exactly
 * like leaning on a physical remote's ring, which is how people browse a TV grid.
 */
@Composable
fun RepeatButton(
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDown: () -> Unit = {},
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Surface(
        shape = shape,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
        contentColor = contentColor,
        modifier = modifier.pointerInputRepeat(enabled, onDown, onTrigger, scope),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

private fun Modifier.pointerInputRepeat(
    enabled: Boolean,
    onDown: () -> Unit,
    onTrigger: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
): Modifier = this.then(
    androidx.compose.ui.input.pointer.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onDown()
            val job = scope.launch {
                onTrigger()
                delay(350)
                while (isActive) {
                    onTrigger()
                    delay(90)
                }
            }
            waitForUpOrCancellation()
            job.cancel()
        }
    },
)

/** A single-tap control with a ripple. */
@Composable
fun ControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The hero control. [onKey] sends a key; [onHaptic] fires once per press so a
 * held direction does not buzz on every repeat.
 */
@Composable
fun Dpad(
    enabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DpadRow {
            Spacer(Modifier.weight(1f))
            DirectionCell(Icons.Rounded.KeyboardArrowUp, "Up", enabled, { onKey(RemoteKey.Up) }, onHaptic)
            Spacer(Modifier.weight(1f))
        }
        DpadRow {
            DirectionCell(Icons.Rounded.KeyboardArrowLeft, "Left", enabled, { onKey(RemoteKey.Left) }, onHaptic)
            OkCell(enabled, { onKey(RemoteKey.Ok) }, onHaptic)
            DirectionCell(Icons.Rounded.KeyboardArrowRight, "Right", enabled, { onKey(RemoteKey.Right) }, onHaptic)
        }
        DpadRow {
            Spacer(Modifier.weight(1f))
            DirectionCell(Icons.Rounded.KeyboardArrowDown, "Down", enabled, { onKey(RemoteKey.Down) }, onHaptic)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DpadRow(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DirectionCell(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onTrigger: () -> Unit,
    onHaptic: () -> Unit,
) {
    RepeatButton(
        onTrigger = onTrigger,
        onDown = onHaptic,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.OkCell(
    enabled: Boolean,
    onTrigger: () -> Unit,
    onHaptic: () -> Unit,
) {
    ControlButton(
        onClick = { onHaptic(); onTrigger() },
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
    ) {
        Text("OK", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}
