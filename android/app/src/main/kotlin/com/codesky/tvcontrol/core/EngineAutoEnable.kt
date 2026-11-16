package com.codesky.tvcontrol.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.codesky.tvcontrol.accessibility.TvControlAccessibilityService

/**
 * Puts this app's accessibility service back into the list Android reads at boot.
 *
 * A normal app may not write secure settings; `WRITE_SECURE_SETTINGS` sits behind
 * the "development" protection flag, which is exactly why `pm grant` can hand it
 * over from an adb shell when the owner asks for it. When that has happened this
 * runs at boot, which keeps the engine alive on TV builds that drop accessibility
 * services at reboot - a documented behaviour of some Android TV 14 roms.
 *
 * The write is additive: it never removes anybody else's service, and without the
 * permission it is a no-op that reports false.
 */
object EngineAutoEnable {

    /** The component string Android stores for an accessibility service. */
    fun component(context: Context): String =
        ComponentName(context, TvControlAccessibilityService::class.java).flattenToString()

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** True when the service is on Android's list afterwards, however it got there. */
    fun tryEnable(context: Context): Boolean {
        if (!canWrite(context)) return false
        return runCatching {
            val resolver = context.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val entry = component(context)
            val current = Settings.Secure.getString(resolver, key).orEmpty()
            val entries = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

            val listed = entries.any { existing -> sameComponent(context, existing, entry) }
            if (!listed) {
                entries.add(entry)
                Settings.Secure.putString(resolver, key, entries.joinToString(":"))
            }

            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        }.getOrDefault(false)
    }

    /** Lets ourselves be enabled even if the user is already inside the app. */
    fun isListed(context: Context): Boolean = runCatching {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        current.split(':').any { it.trim().isNotEmpty() && sameComponent(context, it.trim(), component(context)) }
    }.getOrDefault(false)

    /**
     * Android accepts both `pkg/.Class` and `pkg/full.Class`, so an entry matches
     * when the package is ours and the class name ends with our service's name.
     */
    private fun sameComponent(context: Context, candidate: String, entry: String): Boolean {
        if (candidate == entry) return true
        val pkg = candidate.substringBefore('/')
        if (pkg != context.packageName) return false
        return candidate.substringAfter('/', "").endsWith(TvControlAccessibilityService::class.java.simpleName)
    }
}
