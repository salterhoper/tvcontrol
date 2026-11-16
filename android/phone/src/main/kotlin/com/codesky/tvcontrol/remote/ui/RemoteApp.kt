package com.codesky.tvcontrol.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.data.TvSocket
import com.codesky.tvcontrol.remote.ui.components.KeyboardSheet
import com.codesky.tvcontrol.remote.ui.screens.AppsScreen
import com.codesky.tvcontrol.remote.ui.screens.AssistantScreen
import com.codesky.tvcontrol.remote.ui.screens.ConnectScreen
import com.codesky.tvcontrol.remote.ui.screens.MediaScreen
import com.codesky.tvcontrol.remote.ui.screens.RemotePadScreen
import com.codesky.tvcontrol.remote.ui.settings.SettingsScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    Remote("Remote", Icons.Rounded.SettingsRemote),
    Apps("Apps", Icons.Rounded.Apps),
    Media("Media", Icons.Rounded.PlayCircle),
    Assistant("Assistant", Icons.Rounded.AutoAwesome),
    Settings("Settings", Icons.Rounded.Tune),
}

@Composable
fun RemoteApp(
    vm: RemoteViewModel,
    state: UiState,
    windowSizeClass: WindowSizeClass,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    when (state.route) {
        Route.Connect -> ConnectScreen(vm, state, snackbarHostState)
        Route.Remote -> RemoteHome(vm, state, windowSizeClass, snackbarHostState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteHome(
    vm: RemoteViewModel,
    state: UiState,
    windowSizeClass: WindowSizeClass,
    snackbarHostState: SnackbarHostState,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Remote) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }

    val expanded = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Column {
                        Text(state.tvName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        ConnectionLine(state)
                    }
                },
                actions = {
                    IconButton(onClick = { showKeyboard = true }) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (expanded) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.size(8.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    when (tab) {
                        Tab.Remote -> RemotePadScreen(vm, state)
                        Tab.Apps -> AppsScreen(vm, state)
                        Tab.Media -> MediaScreen(vm, state)
                        Tab.Assistant -> AssistantScreen(vm, state, onOpenSettings = { tab = Tab.Settings })
                        Tab.Settings -> SettingsScreen(vm, state)
                    }
                }
            }
        }
    }

    if (showKeyboard) {
        KeyboardSheet(
            state = state,
            onType = { text, mode, submit -> vm.typeText(text, mode, submit) },
            onDismiss = { showKeyboard = false },
        )
    }
}

@Composable
private fun ConnectionLine(state: UiState) {
    val (color, label) = when {
        state.socket == TvSocket.SocketStatus.Open && state.engineReady ->
            MaterialTheme.colorScheme.primary to "Connected"
        state.socket == TvSocket.SocketStatus.Open ->
            MaterialTheme.colorScheme.tertiary to "Connected · engine off"
        state.socket == TvSocket.SocketStatus.Connecting ->
            MaterialTheme.colorScheme.secondary to "Connecting…"
        else -> MaterialTheme.colorScheme.error to "Reconnecting…"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(color)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(9.dp)) {
        drawCircle(color = color)
    }
}
