package com.codesky.tvcontrol.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codesky.tvcontrol.remote.ui.RemoteApp
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.theme.TvRemoteTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RemoteViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            TvRemoteTheme(dynamicColor = state.dynamicColor) {
                val windowSizeClass = calculateWindowSizeClass(this)
                RemoteApp(vm = vm, state = state, windowSizeClass = windowSizeClass)
            }
        }
    }
}
