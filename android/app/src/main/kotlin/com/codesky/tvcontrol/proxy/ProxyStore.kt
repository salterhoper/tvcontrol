package com.codesky.tvcontrol.proxy

import android.content.Context
import android.content.SharedPreferences
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.security.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * The SOCKS5 servers the owner has saved, which one is in use, and whether the TV's
 * traffic should be going through it. Passwords are encrypted with the keystore, the
 * same way the LLM API key is; the dashboard only ever learns that one is stored.
 */
class ProxyStore(context: Context, private val secure: SecureStore) {

    /** One saved server as stored on disk; [passwordBlob] is the keystore ciphertext. */
    @Serializable
    data class Server(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val username: String? = null,
        val passwordBlob: String? = null,
    )

    /** A server with its password decrypted, handed to the tunnel and never serialised. */
    data class Resolved(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
    ) {
        val hasAuth: Boolean get() = !username.isNullOrEmpty()
        val label: String get() = "$name ($host:$port)"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun servers(): List<Server> = synchronized(lock) {
        val raw = prefs.getString(P_SERVERS, null) ?: return emptyList()
        runCatching { AppJson.decodeFromString(ListSerializer(Server.serializer()), raw) }.getOrDefault(emptyList())
    }

    fun server(id: String): Server? = servers().firstOrNull { it.id == id }

    fun resolve(server: Server): Resolved = Resolved(
        id = server.id,
        name = server.name,
        host = server.host,
        port = server.port,
        username = server.username?.takeIf { it.isNotEmpty() },
        password = secure.decrypt(server.passwordBlob),
    )

    /**
     * Adds a server (no [id]) or edits one. [password]: null keeps the stored one,
     * an empty string removes it, anything else replaces it.
     */
    fun upsert(
        id: String?,
        name: String,
        host: String,
        port: Int,
        username: String?,
        password: String?,
    ): Server = synchronized(lock) {
        val list = servers().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val existing = list.getOrNull(index)
        val blob = when {
            password == null -> existing?.passwordBlob
            password.isEmpty() -> null
            else -> secure.encrypt(password)
        }
        val server = Server(
            id = existing?.id ?: UUID.randomUUID().toString().take(8),
            name = name.ifBlank { host },
            host = host,
            port = port,
            username = username?.trim()?.takeIf { it.isNotEmpty() },
            passwordBlob = blob,
        )
        if (index >= 0) list[index] = server else list += server
        save(list)
        if (activeId() == null) setActive(server.id)
        server
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val list = servers()
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        save(next)
        if (activeId() == id) setActive(next.firstOrNull()?.id)
        true
    }

    fun activeId(): String? = prefs.getString(P_ACTIVE, null)?.takeIf { id -> servers().any { it.id == id } }

    fun active(): Resolved? = activeId()?.let(::server)?.let(::resolve)

    fun setActive(id: String?) {
        prefs.edit().putString(P_ACTIVE, id).apply()
    }

    /** The owner's wish, which the watchdog keeps true after reboots and crashes. */
    var desired: Boolean
        get() = prefs.getBoolean(P_DESIRED, false)
        set(value) = prefs.edit().putBoolean(P_DESIRED, value).apply()

    /**
     * true: if the proxy stops answering, take the tunnel down so the TV keeps working
     * on its own connection. false: keep the tunnel up, so nothing leaks around the proxy.
     */
    var failOpen: Boolean
        get() = prefs.getBoolean(P_FAIL_OPEN, true)
        set(value) = prefs.edit().putBoolean(P_FAIL_OPEN, value).apply()

    var lastNote: String?
        get() = prefs.getString(P_NOTE, null)
        set(value) = prefs.edit().putString(P_NOTE, value).apply()

    private fun save(list: List<Server>) {
        prefs.edit().putString(P_SERVERS, AppJson.encodeToString(ListSerializer(Server.serializer()), list)).apply()
    }

    private companion object {
        const val PREFS = "proxy"
        const val P_SERVERS = "servers"
        const val P_ACTIVE = "active"
        const val P_DESIRED = "desired"
        const val P_FAIL_OPEN = "failOpen"
        const val P_NOTE = "note"
    }
}
