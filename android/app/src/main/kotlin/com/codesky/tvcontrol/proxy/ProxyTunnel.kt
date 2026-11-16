package com.codesky.tvcontrol.proxy

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.ui.VpnConsentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * What the dashboard talks to: the saved SOCKS5 servers, which one is active, and the
 * tunnel ([ProxyVpnService]) that sends the whole TV through it.
 *
 * Safety, as with ad blocking: a dead proxy would take the whole TV offline, so a
 * server is tested before it is switched on or switched to, and (unless the owner
 * asked for a strict kill switch) the watchdog pauses the tunnel when the proxy stops
 * answering and resumes it by itself when it comes back.
 */
object ProxyTunnel {

    @Serializable
    data class ServerView(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val username: String? = null,
        val hasPassword: Boolean = false,
        val active: Boolean = false,
        val lastTest: TestResult? = null,
    )

    @Serializable
    data class Stats(
        val since: Long,
        val activeConnections: Int,
        val totalConnections: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val dnsQueries: Long,
    )

    @Serializable
    data class TestResult(
        val ok: Boolean,
        val at: Long,
        val latencyMs: Long? = null,
        /** The address websites see when the TV goes through this server. */
        val exitIp: String? = null,
        val message: String,
    )

    @Serializable
    data class State(
        /** The owner wants the TV routed through the proxy. */
        val enabled: Boolean,
        /** The tunnel is up right now. */
        val running: Boolean,
        /** Wanted, but paused by the watchdog because the proxy stopped answering. */
        val paused: Boolean = false,
        /** Android still needs the one-time VPN confirmation on the TV screen. */
        val consentNeeded: Boolean,
        val failOpen: Boolean,
        val activeId: String? = null,
        val servers: List<ServerView>,
        val stats: Stats? = null,
        val note: String? = null,
        val verdict: String,
        val actions: List<String> = emptyList(),
    )

    private val tests = ConcurrentHashMap<String, TestResult>()
    @Volatile private var paused = false
    @Volatile private var watchFails = 0

    private const val WATCH_FAILS_BEFORE_PAUSE = 3
    private const val CONNECT_TIMEOUT_MS = 8000

    /** Where the exit-IP probe goes; a plain-HTTP endpoint that answers with the caller's address. */
    private const val PROBE_HOST = "api.ipify.org"
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val IPV6 = Regex("""\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){2,7}\b""")

    // ------------------------------------------------------------------ reading

    fun state(context: Context, store: ProxyStore, note: String? = null): State {
        val consentNeeded = needsConsent(context)
        val service = ProxyVpnService.instance
        val running = service?.running == true
        val activeId = store.activeId()
        val servers = store.servers().map {
            ServerView(
                id = it.id,
                name = it.name,
                host = it.host,
                port = it.port,
                username = it.username,
                hasPassword = it.passwordBlob != null,
                active = it.id == activeId,
                lastTest = tests[it.id],
            )
        }
        val stats = if (running && service != null) {
            Stats(
                since = service.startedAt,
                activeConnections = service.activeConnections,
                totalConnections = service.totalConnections,
                bytesUp = service.bytesUp.get(),
                bytesDown = service.bytesDown.get(),
                dnsQueries = service.dnsQueries.get(),
            )
        } else {
            null
        }
        val active = servers.firstOrNull { it.active }
        val verdict = when {
            servers.isEmpty() -> "No proxy saved yet. Add a SOCKS5 server below, then turn routing on."
            running -> "On: every app on the TV goes through ${active?.name ?: "the proxy"}. Names are resolved by " +
                "the proxy, IPv6 and QUIC are held back so nothing slips around it, and the local network stays direct."
            store.desired && paused -> "Paused: ${active?.name ?: "the proxy"} stopped answering, so the TV went back " +
                "to its own connection. It resumes on its own once the proxy answers again."
            store.desired && consentNeeded -> "Waiting for the VPN confirmation on the TV screen."
            store.desired -> "Starting…"
            else -> "Off. The TV uses its own internet connection."
        }
        val actions = buildList {
            if (consentNeeded) {
                add(
                    "Android asks once, on the TV, before any app may route traffic. Press OK on the prompt with the " +
                        "remote (the dashboard D-pad works). To skip it, from an adb shell: " +
                        "appops set ${context.packageName} ACTIVATE_VPN allow",
                )
            }
            if (!store.failOpen && store.desired && !running && !consentNeeded) {
                add("Kill switch is on: if the proxy is down the TV has no internet until it comes back or you turn routing off.")
            }
        }
        return State(
            enabled = store.desired,
            running = running,
            paused = store.desired && paused && !running,
            consentNeeded = consentNeeded,
            failOpen = store.failOpen,
            activeId = activeId,
            servers = servers,
            stats = stats,
            note = note,
            verdict = verdict,
            actions = actions,
        )
    }

    private fun needsConsent(context: Context): Boolean =
        runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    // ------------------------------------------------------------------ changes

    suspend fun setEnabled(context: Context, store: ProxyStore, enabled: Boolean, id: String?): State =
        withContext(Dispatchers.IO) {
            if (!enabled) {
                store.desired = false
                paused = false
                ProxyVpnService.stop(context)
                return@withContext state(context, store, note = "Routing is off; the TV uses its own connection again.")
            }

            if (id != null) {
                if (store.server(id) == null) return@withContext state(context, store, note = "That server no longer exists.")
                store.setActive(id)
            }
            val proxy = store.active() ?: return@withContext state(context, store, note = "Add a SOCKS5 server first.")

            val test = test(proxy)
            if (!test.ok) {
                return@withContext state(context, store, note = "Not switched on: ${test.message}")
            }

            store.desired = true
            paused = false
            watchFails = 0
            if (needsConsent(context)) {
                askConsent(context)
                return@withContext state(
                    context,
                    store,
                    note = "Confirm the VPN prompt on the TV screen (one time). Routing starts as soon as you press OK.",
                )
            }
            ProxyVpnService.start(context)
            awaitRunning(true)
            val running = ProxyVpnService.running()
            state(
                context,
                store,
                note = if (running) "Routing the TV through ${proxy.name}." else store.lastNote ?: "The tunnel did not start.",
            )
        }

    /** Makes [id] the active server; if the tunnel is up, traffic moves to it right away. */
    suspend fun select(context: Context, store: ProxyStore, id: String): State = withContext(Dispatchers.IO) {
        val server = store.server(id) ?: return@withContext state(context, store, note = "That server no longer exists.")
        val proxy = store.resolve(server)
        if (ProxyVpnService.running()) {
            val test = test(proxy)
            if (!test.ok) return@withContext state(context, store, note = "Stayed on the current server: ${test.message}")
        }
        store.setActive(id)
        ProxyVpnService.instance?.onProxyChanged()
        state(context, store, note = if (ProxyVpnService.running()) "Now routing through ${proxy.name}." else "${proxy.name} selected.")
    }

    fun remove(context: Context, store: ProxyStore, id: String): State {
        val wasActive = store.activeId() == id
        if (!store.remove(id)) return state(context, store, note = "That server no longer exists.")
        tests.remove(id)
        if (wasActive && ProxyVpnService.running()) {
            if (store.active() == null) {
                store.desired = false
                ProxyVpnService.stop(context)
            } else {
                ProxyVpnService.instance?.onProxyChanged()
            }
        }
        return state(context, store, note = "Server removed.")
    }

    fun changed(store: ProxyStore, id: String) {
        tests.remove(id)
        if (store.activeId() == id) ProxyVpnService.instance?.onProxyChanged()
    }

    private fun askConsent(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, VpnConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Logging.w("Could not show the VPN prompt", it) }
    }

    private suspend fun awaitRunning(wanted: Boolean) {
        repeat(20) {
            if (ProxyVpnService.running() == wanted) return
            delay(150)
        }
    }

    // ------------------------------------------------------------------ testing

    suspend fun test(store: ProxyStore, id: String): TestResult? = withContext(Dispatchers.IO) {
        store.server(id)?.let { test(store.resolve(it)) }
    }

    /**
     * Connects to the proxy, logs in, and fetches the exit address through it. While
     * the tunnel is up the probe's socket is protected so it really goes direct to the
     * proxy instead of looping through the tunnel.
     */
    private fun test(proxy: ProxyStore.Resolved): TestResult {
        val started = System.nanoTime()
        val result = runCatching {
            open(proxy, PROBE_HOST, 80).use { socket ->
                val handshakeMs = (System.nanoTime() - started) / 1_000_000
                socket.soTimeout = CONNECT_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write("GET / HTTP/1.1\r\nHost: $PROBE_HOST\r\nUser-Agent: tvcontrol\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val response = Socks5.readSmallResponse(socket.getInputStream())
                val body = response.substringAfter("\r\n\r\n", "").trim()
                val ip = (IPV4.find(body) ?: IPV6.find(body))?.value
                TestResult(
                    ok = true,
                    at = System.currentTimeMillis(),
                    latencyMs = handshakeMs,
                    exitIp = ip,
                    message = if (ip != null) "Works. Websites will see $ip." else "Connected, but the exit address did not come back.",
                )
            }
        }.getOrElse { error ->
            TestResult(ok = false, at = System.currentTimeMillis(), message = readable(error, proxy))
        }
        tests[proxy.id] = result
        return result
    }

    private fun open(proxy: ProxyStore.Resolved, host: String, port: Int): Socket {
        ProxyVpnService.instance?.takeIf { it.running }?.let {
            return it.openThroughProxy(proxy, host, port, CONNECT_TIMEOUT_MS)
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(proxy.host), proxy.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            Socks5.connect(socket, proxy, host, port)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readable(error: Throwable, proxy: ProxyStore.Resolved): String = when (error) {
        is Socks5.Failure -> error.message ?: "The proxy refused."
        is java.net.UnknownHostException -> "Could not resolve ${proxy.host}."
        is java.net.SocketTimeoutException -> "${proxy.host}:${proxy.port} did not answer in time."
        is java.net.ConnectException -> "${proxy.host}:${proxy.port} refused the connection - is that the right port?"
        is java.io.EOFException -> "The proxy hung up during the handshake - is it really SOCKS5?"
        else -> error.message ?: error.javaClass.simpleName
    }

    // ----------------------------------------------------------------- watchdog

    /**
     * Called once a minute by the foreground service. Starts the tunnel when it is wanted
     * but down (after a reboot or a crash), and with fail-open pauses it after
     * [WATCH_FAILS_BEFORE_PAUSE] failed probes in a row, resuming once the proxy answers.
     */
    suspend fun watch(context: Context, store: ProxyStore) = withContext(Dispatchers.IO) {
        if (!store.desired || needsConsent(context)) return@withContext
        val proxy = store.active() ?: return@withContext
        val reachable = reachable(proxy)
        if (ProxyVpnService.running()) {
            if (reachable) {
                watchFails = 0
                return@withContext
            }
            watchFails++
            if (store.failOpen && watchFails >= WATCH_FAILS_BEFORE_PAUSE) {
                Logging.w("Proxy ${proxy.host} stopped answering; pausing the tunnel")
                paused = true
                watchFails = 0
                store.lastNote = "${proxy.name} stopped answering, so routing is paused."
                ProxyVpnService.stop(context)
            }
        } else if (reachable || !store.failOpen) {
            Logging.i("Proxy tunnel wanted but down; starting it")
            paused = false
            ProxyVpnService.start(context)
        }
    }

    /** Can we reach the proxy and does it greet us as SOCKS5. Cheap: no CONNECT. */
    private fun reachable(proxy: ProxyStore.Resolved): Boolean = runCatching {
        val service = ProxyVpnService.instance?.takeIf { it.running }
        val socket = service?.protectedSocket() ?: Socket()
        socket.use {
            val address = service?.proxyEndpoint(proxy) ?: InetSocketAddress(InetAddress.getByName(proxy.host), proxy.port)
            it.connect(address, CONNECT_TIMEOUT_MS)
            it.soTimeout = CONNECT_TIMEOUT_MS
            it.getOutputStream().apply {
                write(if (proxy.hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
                flush()
            }
            it.getInputStream().read() == 5
        }
    }.getOrDefault(false)
}
