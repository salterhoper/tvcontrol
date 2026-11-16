package com.codesky.tvcontrol.remote.data

import android.content.Context

/** A paired TV: where it is and the bearer token that proves we paired. */
data class Session(
    val host: String,
    val token: String,
    val name: String,
)

/**
 * Tiny persistence for the one thing worth keeping between launches - the paired
 * session - plus the haptics preference. Backed by SharedPreferences; the token
 * only ever lived on the LAN, same trust model as the web dashboard's
 * localStorage.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("tvremote.prefs", Context.MODE_PRIVATE)

    fun load(): Session? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (host.isBlank() || token.isBlank()) return null
        return Session(host, token, prefs.getString(KEY_NAME, "Android TV") ?: "Android TV")
    }

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_HOST, session.host)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_NAME, session.name)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_TOKEN)
            .remove(KEY_NAME)
            .apply()
    }

    var lastHost: String
        get() = prefs.getString(KEY_LAST_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_HOST, value).apply() }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_HAPTICS, value).apply() }

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC, false)
        set(value) { prefs.edit().putBoolean(KEY_DYNAMIC, value).apply() }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEVICE, value).apply() }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "name"
        const val KEY_LAST_HOST = "lastHost"
        const val KEY_HAPTICS = "haptics"
        const val KEY_DYNAMIC = "dynamicColor"
        const val KEY_DEVICE = "device"
    }
}
