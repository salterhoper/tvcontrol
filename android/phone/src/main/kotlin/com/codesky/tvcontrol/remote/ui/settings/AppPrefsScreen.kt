package com.codesky.tvcontrol.remote.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codesky.tvcontrol.remote.ui.RemoteViewModel
import com.codesky.tvcontrol.remote.ui.UiState

@Composable
fun AppPrefsScreen(vm: RemoteViewModel, state: UiState) {
    DetailColumn {
        SettingsCard("Appearance & feedback") {
            ToggleRow("Haptic feedback", "A small tap when you press a control", state.hapticsEnabled, vm::setHaptics)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider()
                ToggleRow("Match system colours", "Use the Material You palette from your wallpaper", state.dynamicColor, vm::setDynamicColor)
            }
        }

        Button(
            onClick = { vm.disconnect() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Disconnect & forget this TV", fontWeight = FontWeight.SemiBold)
        }
    }
}
