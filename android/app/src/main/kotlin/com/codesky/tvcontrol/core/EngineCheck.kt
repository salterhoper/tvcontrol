package com.codesky.tvcontrol.core

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Why the accessibility engine might refuse to stay switched on.
 *
 * Android 13 added an app-op, `ACCESS_RESTRICTED_SETTINGS`, that is set to "deny"
 * when an app is installed from a source the device does not trust. For those apps
 * the system declines to keep an accessibility service enabled: the user turns the
 * switch on and it snaps straight back off, usually with no message at all on TV
 * builds. Nothing an app can do lifts it - the user (or adb) has to - but the app
 * can at least tell them exactly what they are looking at.
 */
object EngineCheck {

    private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /**
     * True when this TV is refusing accessibility because of the install-source
     * rule. Always false on Android 12 and older (the op does not exist there) and
     * whenever the mode cannot be read.
     */
    fun restrictedSettingsBlocked(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.checkOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_IGNORED
    }.getOrDefault(false)
}
