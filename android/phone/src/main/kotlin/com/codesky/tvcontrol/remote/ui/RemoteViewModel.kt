package com.codesky.tvcontrol.remote.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codesky.tvcontrol.remote.data.ActionResult
import com.codesky.tvcontrol.remote.data.AppEntry
import com.codesky.tvcontrol.remote.data.ApiException
import com.codesky.tvcontrol.remote.data.AssistantRun
import com.codesky.tvcontrol.remote.data.ChatMessage
import com.codesky.tvcontrol.remote.data.DiscoveredTv
import com.codesky.tvcontrol.remote.data.EngineState
import com.codesky.tvcontrol.remote.data.Envelope
import com.codesky.tvcontrol.remote.data.NowPlayingInfo
import com.codesky.tvcontrol.remote.data.RemoteJson
import com.codesky.tvcontrol.remote.data.RemoteKey
import com.codesky.tvcontrol.remote.data.ScreenSnapshot
import com.codesky.tvcontrol.remote.data.Session
import com.codesky.tvcontrol.remote.data.Settings
import com.codesky.tvcontrol.remote.data.SessionStore
import com.codesky.tvcontrol.remote.data.TextResult
import com.codesky.tvcontrol.remote.data.TvClient
import com.codesky.tvcontrol.remote.data.TvDiscovery
import com.codesky.tvcontrol.remote.data.TvSocket
import com.codesky.tvcontrol.remote.data.buildHttpClient
import com.codesky.tvcontrol.remote.data.keyPayload
import com.codesky.tvcontrol.remote.data.normaliseHost
import com.codesky.tvcontrol.remote.data.pairTv
import com.codesky.tvcontrol.remote.data.pingTv
import com.codesky.tvcontrol.remote.data.textPayload
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

enum class Route { Connect, Remote }

private val TERMINAL_RUN_STATES = setOf("done", "failed", "partial", "cancelled", "needs_input")

data class UiState(
    val route: Route = Route.Connect,
    // connect screen
    val manualHost: String = "",
    val discovered: List<DiscoveredTv> = emptyList(),
    val discovering: Boolean = false,
    val pairingHost: String? = null,
    val pairingTvName: String = "",
    val pairing: Boolean = false,
    val connectError: String? = null,
    val checking: Boolean = false,
    // connected
    val tvName: String = "Android TV",
    val tvVersion: String = "",
    val socket: TvSocket.SocketStatus = TvSocket.SocketStatus.Closed,
    val engine: EngineState? = null,
    val screen: ScreenSnapshot? = null,
    val apps: List<AppEntry> = emptyList(),
    val appsLoading: Boolean = false,
    val nowPlaying: NowPlayingInfo? = null,
    val settings: Settings? = null,
    val chat: List<ChatMessage> = emptyList(),
    val assistantRunning: Boolean = false,
    val volumePercent: Int? = null,
    val muted: Boolean = false,
    // settings
    val hapticsEnabled: Boolean = true,
    val dynamicColor: Boolean = false,
) {
    val connected: Boolean get() = socket == TvSocket.SocketStatus.Open
    val engineReady: Boolean get() = engine?.accessibilityConnected == true
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SessionStore(app)
    private val discovery = TvDiscovery(app)
    private val http: HttpClient = buildHttpClient()

    private var client: TvClient? = null
    private var socket: TvSocket? = null
    private var discoveryJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            manualHost = store.lastHost,
            hapticsEnabled = store.hapticsEnabled,
            dynamicColor = store.dynamicColor,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        store.load()?.let { connect(it) }
    }

    // ------------------------------------------------------------- discovery

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _state.update { it.copy(discovering = true) }
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { list ->
                _state.update { it.copy(discovered = list) }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _state.update { it.copy(discovering = false) }
    }

    // ------------------------------------------------------------- pairing

    fun onManualHostChange(value: String) {
        _state.update { it.copy(manualHost = value, connectError = null) }
    }

    /** Ping a TV to confirm it is reachable, then open the PIN prompt. */
    fun beginPairing(rawHost: String) {
        val host = normaliseHost(rawHost).ifBlank {
            _state.update { it.copy(connectError = "Enter the address shown on the TV, e.g. 192.168.1.42:8787") }
            return
        }
        store.lastHost = host
        _state.update { it.copy(checking = true, connectError = null) }
        viewModelScope.launch {
            try {
                val ping = pingTv(http, host)
                _state.update {
                    it.copy(
                        checking = false,
                        pairingHost = host,
                        pairingTvName = ping.name,
                        connectError = null,
                    )
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        checking = false,
                        connectError = "Could not reach a TV at $host. Check it is on the same Wi-Fi and the app is open.",
                    )
                }
            }
        }
    }

    fun beginPairing(tv: DiscoveredTv) = beginPairing(tv.address)

    fun cancelPairing() {
        _state.update { it.copy(pairingHost = null, pairing = false) }
    }

    fun submitPin(pin: String) {
        val host = _state.value.pairingHost ?: return
        val device = deviceLabel()
        _state.update { it.copy(pairing = true, connectError = null) }
        viewModelScope.launch {
            try {
                val res = pairTv(http, host, pin.trim(), device)
                val session = Session(host, res.token, res.name)
                store.save(session)
                store.deviceName = device
                _state.update { it.copy(pairing = false, pairingHost = null) }
                connect(session)
            } catch (e: ApiException) {
                _state.update {
                    it.copy(pairing = false, connectError = e.message ?: "Pairing failed")
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(pairing = false, connectError = "Pairing failed - is the TV still reachable?")
                }
            }
        }
    }

    private fun deviceLabel(): String {
        store.deviceName.takeIf { it.isNotBlank() }?.let { return it }
        val model = Build.MODEL ?: "Phone"
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Phone" }
    }

    // ------------------------------------------------------------- connection

    private fun connect(session: Session) {
        socket?.close()
        val tvClient = TvClient(session.host, session.token, http)
        client = tvClient
        _state.update {
            it.copy(
                route = Route.Remote,
                tvName = session.name,
                socket = TvSocket.SocketStatus.Connecting,
            )
        }
        stopDiscovery()

        val tvSocket = TvSocket(
            host = session.host,
            token = session.token,
            http = http,
            scope = viewModelScope,
            onStatus = { status -> _state.update { it.copy(socket = status) } },
            onEvent = ::onSocketEvent,
        )
        socket = tvSocket
        tvSocket.connect()

        // Prime from REST so the UI is populated even before the socket opens.
        viewModelScope.launch {
            try {
                val info = tvClient.info()
                _state.update {
                    it.copy(
                        tvName = info.name,
                        tvVersion = info.version,
                        engine = info.engine,
                        screen = info.screen ?: it.screen,
                        volumePercent = info.volume.levelPercent ?: it.volumePercent,
                        muted = info.volume.muted ?: it.muted,
                    )
                }
                loadApps(force = false)
                runCatching { tvClient.nowPlaying() }.getOrNull()?.let { np ->
                    _state.update { it.copy(nowPlaying = np) }
                }
                runCatching { tvClient.settings() }.getOrNull()?.let { s ->
                    _state.update { it.copy(settings = s) }
                }
            } catch (e: ApiException) {
                if (e.status == 401) expireSession() else emit(e.message ?: "The TV refused the request")
            } catch (e: Throwable) {
                emit("Could not load the TV yet - retrying the connection…")
            }
        }
    }

    fun disconnect() {
        socket?.close()
        socket = null
        client = null
        store.clear()
        _state.update {
            UiState(
                route = Route.Connect,
                manualHost = store.lastHost,
                hapticsEnabled = store.hapticsEnabled,
                dynamicColor = store.dynamicColor,
            )
        }
    }

    private fun expireSession() {
        emit("Pairing is no longer valid. Pair with the PIN again.")
        disconnect()
    }

    // ------------------------------------------------------------- controls

    fun pressKey(key: RemoteKey, repeat: Int = 1) {
        val s = socket
        if (s?.isOpen == true) {
            s.send("key", keyPayload(key, repeat))
            return
        }
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.key(key, repeat) }
                .onSuccess { r -> if (!r.ok) emit(r.note ?: "The TV refused that key") }
                .onFailure { guard(it) }
        }
    }

    fun volume(action: String) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.volume(action) }
                .onSuccess { v ->
                    _state.update { it.copy(volumePercent = v.levelPercent ?: it.volumePercent, muted = v.muted ?: it.muted) }
                    if (!v.ok) v.note?.let { emit(it) }
                }
                .onFailure { guard(it) }
        }
    }

    fun setVolume(percent: Int) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.volume("set", percent) }
                .onSuccess { v -> _state.update { it.copy(volumePercent = v.levelPercent ?: percent, muted = v.muted ?: it.muted) } }
                .onFailure { guard(it) }
        }
    }

    fun typeText(text: String, mode: String, submit: Boolean) {
        val s = socket
        val c = client ?: return
        if (s?.isOpen == true && mode != "backspace") {
            s.send("text", textPayload(text, mode, submit))
            return
        }
        viewModelScope.launch {
            runCatching { c.text(text, mode, submit) }
                .onSuccess { r: TextResult ->
                    if (!r.ok) emit(r.note ?: "The TV did not accept the text")
                    else if (r.submitted == true) emit("Typed and sent")
                }
                .onFailure { guard(it) }
        }
    }

    fun launchApp(app: AppEntry) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.launchPackage(app.pkg) }
                .onSuccess { r -> if (!r.ok) emit(r.note ?: "Could not open ${app.label}") else emit("Opened ${r.label ?: app.label}") }
                .onFailure { guard(it) }
        }
    }

    fun search(query: String, target: String) {
        val c = client ?: return
        if (query.isBlank()) return
        viewModelScope.launch {
            runCatching { c.search(query, target) }
                .onSuccess { r: ActionResult -> emit(if (r.ok) "Searching for \"$query\"" else (r.note ?: "Search failed")) }
                .onFailure { guard(it) }
        }
    }

    fun loadApps(force: Boolean = true) {
        val c = client ?: return
        _state.update { it.copy(appsLoading = true) }
        viewModelScope.launch {
            runCatching { c.apps(force) }
                .onSuccess { res -> _state.update { it.copy(apps = res.apps, appsLoading = false) } }
                .onFailure { _state.update { it.copy(appsLoading = false) }; guard(it) }
        }
    }

    fun controlMedia(action: String, positionMs: Long? = null) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.controlMedia(action, positionMs) }
                .onSuccess { r -> r.nowPlaying?.let { np -> _state.update { it.copy(nowPlaying = np) } } }
                .onFailure { guard(it) }
        }
    }

    fun refreshNowPlaying() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.nowPlaying() }.getOrNull()?.let { np -> _state.update { it.copy(nowPlaying = np) } }
        }
    }

    // ------------------------------------------------------------- assistant chat

    private var activeAssistantId: String? = null

    fun sendAssistant(utterance: String) {
        val c = client ?: return
        val text = utterance.trim()
        if (text.isBlank()) return
        val userMsg = ChatMessage(UUID.randomUUID().toString(), fromUser = true, text = text)
        val pendingId = UUID.randomUUID().toString()
        val pending = ChatMessage(
            id = pendingId,
            fromUser = false,
            text = "",
            run = AssistantRun(utterance = text, status = "running"),
        )
        activeAssistantId = pendingId
        _state.update { it.copy(chat = it.chat + userMsg + pending, assistantRunning = true) }
        viewModelScope.launch {
            runCatching { c.assist(text) }
                .onFailure { t ->
                    if (t is ApiException && t.status == 401) expireSession()
                    else failActiveAssistant(t.message ?: "The assistant could not start")
                }
        }
    }

    fun cancelAssistant() {
        val c = client ?: return
        viewModelScope.launch { runCatching { c.cancelAssistant() } }
    }

    fun clearChat() {
        activeAssistantId = null
        _state.update { it.copy(chat = emptyList(), assistantRunning = false) }
    }

    private fun failActiveAssistant(message: String) {
        val id = activeAssistantId ?: return
        activeAssistantId = null
        _state.update { st ->
            st.copy(
                assistantRunning = false,
                chat = st.chat.map {
                    if (it.id == id) it.copy(run = (it.run ?: AssistantRun()).copy(status = "failed", error = message))
                    else it
                },
            )
        }
    }

    fun artUrl(): String? = client?.artUrl()

    // ------------------------------------------------------------- shared access for settings screens

    /** The live client, for the self-contained settings screens. Null when not connected. */
    val currentClient: TvClient? get() = client

    fun toast(message: String) = emit(message)

    fun refreshSettings() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.settings() }.getOrNull()?.let { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun setSettingsState(settings: Settings) {
        _state.update { it.copy(settings = settings) }
    }

    fun setHaptics(enabled: Boolean) {
        store.hapticsEnabled = enabled
        _state.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        store.dynamicColor = enabled
        _state.update { it.copy(dynamicColor = enabled) }
    }

    // ------------------------------------------------------------- socket events

    private fun onSocketEvent(env: Envelope) {
        val data = env.data
        when (env.type) {
            "hello" -> {
                val obj = data?.safeObject()
                _state.update {
                    it.copy(
                        tvName = obj?.get("name")?.stringOrNull() ?: it.tvName,
                        tvVersion = obj?.get("version")?.stringOrNull() ?: it.tvVersion,
                    )
                }
            }
            "engine.state" -> {
                val connected = data?.safeObject()?.get("connected")?.boolOrNull() ?: false
                _state.update {
                    it.copy(
                        engine = (it.engine ?: EngineState()).copy(
                            accessibilityConnected = connected,
                            canNavigate = connected,
                            canType = connected,
                        ),
                    )
                }
            }
            "screen.update", "input.field" -> {
                data?.decode(ScreenSnapshot.serializer())?.let { snap ->
                    _state.update { it.copy(screen = snap) }
                }
            }
            "app.changed" -> {
                val obj = data?.safeObject()
                _state.update {
                    val cur = it.screen ?: ScreenSnapshot()
                    it.copy(
                        screen = cur.copy(
                            pkg = obj?.get("pkg")?.stringOrNull() ?: cur.pkg,
                            app = obj?.get("app")?.stringOrNull() ?: cur.app,
                        ),
                    )
                }
            }
            "now_playing" -> {
                data?.decode(NowPlayingInfo.serializer())?.let { np ->
                    _state.update { it.copy(nowPlaying = np) }
                }
            }
            "assistant.run" -> {
                data?.decode(AssistantRun.serializer())?.let { run ->
                    val id = activeAssistantId
                    _state.update { st ->
                        val chat = if (id != null && st.chat.any { it.id == id }) {
                            st.chat.map {
                                if (it.id == id) it.copy(text = run.say ?: it.text, run = run) else it
                            }
                        } else {
                            // A run we didn't start locally (e.g. from another client) - show it.
                            st.chat + ChatMessage(UUID.randomUUID().toString(), fromUser = false, text = run.say ?: "", run = run)
                        }
                        st.copy(chat = chat)
                    }
                    if (run.status in TERMINAL_RUN_STATES) {
                        activeAssistantId = null
                        _state.update { it.copy(assistantRunning = false) }
                    }
                }
            }
            "key.result" -> {
                val result = data?.safeObject()?.get("result")?.decode(ActionResult.serializer())
                if (result != null && !result.ok) emit(result.note ?: "The TV refused that key")
            }
            "text.result" -> {
                data?.decode(TextResult.serializer())?.let { r ->
                    if (!r.ok) emit(r.note ?: "Could not type on the TV")
                    else if (r.submitted == true) emit("Typed and sent")
                }
            }
            "settings.note" -> {
                data?.safeObject()?.get("message")?.stringOrNull()?.let { emit(it) }
            }
            "error" -> {
                data?.safeObject()?.get("message")?.stringOrNull()?.let { emit(it) }
            }
            else -> Unit
        }
    }

    private fun guard(t: Throwable) {
        if (t is ApiException && t.status == 401) expireSession()
        else emit(t.message ?: "Something went wrong talking to the TV")
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    override fun onCleared() {
        super.onCleared()
        socket?.close()
        discoveryJob?.cancel()
        http.close()
    }

    // ---- JsonElement helpers ----

    private fun kotlinx.serialization.json.JsonElement.safeObject(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.boolOrNull(): Boolean? =
        runCatching { jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()

    private fun <T> kotlinx.serialization.json.JsonElement.decode(
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? = runCatching { RemoteJson.decodeFromJsonElement(serializer, this) }.getOrNull()
}
