package com.codesky.tvcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.EngineAutoEnable
import com.codesky.tvcontrol.core.Logging

/**
 * Android TV boxes are usually always on. Coming back after a power cut with the
 * remote already listening is the difference between "works" and "my phone has to
 * find the TV again every time".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Some Android TV builds drop every accessibility service at reboot. When the
        // owner has granted the app WRITE_SECURE_SETTINGS over adb, put the engine back
        // on Android's list before anything else; without the grant this is a no-op.
        if (EngineAutoEnable.tryEnable(context)) {
            Logging.i("Accessibility engine put back on the list after boot")
        }

        val app = context.applicationContext as? TvControlApp ?: return
        if (!app.container.settings.autoStart) {
            Logging.i("Auto start disabled, not starting the server")
            return
        }

        // Android 12+ can refuse a foreground service start from BOOT_COMPLETED on
        // some builds; the user can always open the app, so log instead of crashing.
        runCatching { ControlServerService.start(context) }
            .onFailure { Logging.w("Boot start of the server was refused", it) }
    }
}
