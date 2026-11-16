package com.codesky.tvcontrol.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.codesky.tvcontrol.core.Logging

/**
 * Android NotificationListenerService implementation allowing the app to monitor
 * active MediaSessions (YouTube, SmartTube, Spotify, Netflix, VLC) and control playback.
 */
class TvNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logging.i("TvNotificationListener connected")
        instance = this
        onConnectedCallback?.invoke(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logging.i("TvNotificationListener disconnected")
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        @Volatile
        var instance: TvNotificationListener? = null
            private set

        var onConnectedCallback: ((TvNotificationListener) -> Unit)? = null

        fun componentName(context: Context): ComponentName =
            ComponentName(context, TvNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val cn = componentName(context).flattenToString()
            val current = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            return current.split(':').any { it.trim().equals(cn, ignoreCase = true) }
        }

        fun ensureEnabled(context: Context): Boolean {
            val cn = componentName(context).flattenToString()
            val resolver = context.contentResolver
            return runCatching {
                val current = Settings.Secure.getString(resolver, "enabled_notification_listeners") ?: ""
                val list = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (!list.any { it.equals(cn, ignoreCase = true) }) {
                    list.add(cn)
                    Settings.Secure.putString(resolver, "enabled_notification_listeners", list.joinToString(":"))
                    Logging.i("Added TvNotificationListener to enabled_notification_listeners")
                }
                true
            }.getOrDefault(false)
        }
    }
}
