package com.codesky.tvcontrol.remote.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lenient JSON: never trip over fields a newer/older TV adds or drops. */
val RemoteJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/** Strip a scheme and trailing slashes so "http://1.2.3.4:8787/" becomes "1.2.3.4:8787". */
fun normaliseHost(raw: String): String =
    raw.trim()
        .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        .trimEnd('/')
        .substringBefore('#')

class ApiException(
    message: String,
    val status: Int,
    val code: String? = null,
) : Exception(message)

fun buildHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(ContentNegotiation) { json(RemoteJson) }
    install(WebSockets)
    // No engine requestTimeout: it would also cap the lifetime of the long-lived
    // control WebSocket. REST calls that hang are surfaced as reconnects instead.
}

// --------------------------------------------------------------- pairing (no token)

suspend fun pingTv(http: HttpClient, host: String): PingResponse {
    val clean = normaliseHost(host)
    val res = http.get("http://$clean/api/ping")
    val text = res.bodyAsText()
    if (!res.status.isSuccess()) throw res.toApiException(text)
    return RemoteJson.decodeFromString(PingResponse.serializer(), text)
}

suspend fun pairTv(http: HttpClient, host: String, pin: String, device: String): PairResponse {
    val clean = normaliseHost(host)
    val res = http.post("http://$clean/api/pair") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("pin", pin)
            put("device", device)
        })
    }
    val text = res.bodyAsText()
    if (!res.status.isSuccess()) throw res.toApiException(text)
    return RemoteJson.decodeFromString(PairResponse.serializer(), text)
}

private suspend fun HttpResponse.toApiException(text: String): ApiException {
    val body = runCatching { RemoteJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
    return ApiException(body?.message ?: status.description, status.value, body?.error)
}

// ----------------------------------------------------------------- authed client

/**
 * One paired TV. Owns no sockets - REST only; the live channel is [TvSocket].
 * The [http] client is shared and outlives any single TvClient.
 */
class TvClient(
    val host: String,
    val token: String,
    private val http: HttpClient,
) {
    val origin: String get() = "http://$host"

    private suspend fun <T> request(
        path: String,
        method: String,
        body: JsonElement? = null,
        deserialize: (String) -> T,
    ): T {
        val res = http.request("$origin$path") {
            this.method = HttpMethod.parse(method)
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) throw res.toApiException(text)
        return deserialize(text)
    }

    suspend fun info(): InfoResponse =
        request("/api/info", "GET") { RemoteJson.decodeFromString(InfoResponse.serializer(), it) }

    suspend fun apps(refresh: Boolean = false): AppsResponse =
        request("/api/apps${if (refresh) "?refresh=1" else ""}", "GET") {
            RemoteJson.decodeFromString(AppsResponse.serializer(), it)
        }

    suspend fun key(key: RemoteKey, repeat: Int = 1): ActionResult =
        request(
            "/api/key", "POST",
            buildJsonObject { put("key", key.wire); put("repeat", repeat) },
        ) {
            // The TV answers {"key":..., "result":{...}}; pull out the result.
            val obj = RemoteJson.decodeFromString(JsonObject.serializer(), it)
            val result = obj["result"]
            if (result != null) RemoteJson.decodeFromString(ActionResult.serializer(), result.toString())
            else ActionResult(ok = true)
        }

    suspend fun text(text: String, mode: String = "replace", submit: Boolean = false): TextResult =
        request(
            "/api/text", "POST",
            buildJsonObject { put("text", text); put("mode", mode); put("submit", submit) },
        ) { RemoteJson.decodeFromString(TextResult.serializer(), it) }

    suspend fun launchPackage(pkg: String): LaunchOutcome =
        request("/api/launch", "POST", buildJsonObject { put("package", pkg) }) {
            RemoteJson.decodeFromString(LaunchOutcome.serializer(), it)
        }

    suspend fun launchQuery(query: String): LaunchOutcome =
        request("/api/launch", "POST", buildJsonObject { put("query", query) }) {
            RemoteJson.decodeFromString(LaunchOutcome.serializer(), it)
        }

    suspend fun search(query: String, target: String = "auto"): ActionResult =
        request("/api/search", "POST", buildJsonObject { put("query", query); put("target", target) }) {
            RemoteJson.decodeFromString(ActionResult.serializer(), it)
        }

    suspend fun volume(action: String, value: Int? = null): VolumeResult =
        request(
            "/api/volume", "POST",
            buildJsonObject {
                put("action", action)
                if (value != null) put("value", value)
            },
        ) { RemoteJson.decodeFromString(VolumeResult.serializer(), it) }

    suspend fun nowPlaying(): NowPlayingInfo =
        request("/api/media/now-playing", "GET") {
            RemoteJson.decodeFromString(NowPlayingInfo.serializer(), it)
        }

    suspend fun controlMedia(action: String, positionMs: Long? = null): MediaControlResult =
        request(
            "/api/media/control", "POST",
            buildJsonObject {
                put("action", action)
                if (positionMs != null) put("positionMs", positionMs)
            },
        ) { RemoteJson.decodeFromString(MediaControlResult.serializer(), it) }

    suspend fun assist(utterance: String, useScreen: Boolean = true, dryRun: Boolean = false): AssistantStartResponse =
        request(
            "/api/assistant", "POST",
            buildJsonObject {
                put("utterance", utterance)
                put("useScreen", useScreen)
                put("dryRun", dryRun)
            },
        ) { RemoteJson.decodeFromString(AssistantStartResponse.serializer(), it) }

    suspend fun cancelAssistant() {
        request<Unit>("/api/assistant/cancel", "POST", buildJsonObject {}) { }
    }

    // -------- settings & pairing

    suspend fun settings(): Settings =
        request("/api/settings", "GET") { RemoteJson.decodeFromString(Settings.serializer(), it) }

    suspend fun updateSettings(patch: JsonObject): Settings =
        request("/api/settings", "PUT", patch) { RemoteJson.decodeFromString(Settings.serializer(), it) }

    suspend fun rotatePin(): Settings =
        request("/api/settings/pin/rotate", "POST", buildJsonObject {}) { RemoteJson.decodeFromString(Settings.serializer(), it) }

    suspend fun revokeDevices() {
        request<Unit>("/api/settings/devices/revoke", "POST", buildJsonObject {}) { }
    }

    // -------- diagnostics & engine

    suspend fun diagnostics(): DiagnosticsReport =
        request("/api/diagnostics", "GET") { RemoteJson.decodeFromString(DiagnosticsReport.serializer(), it) }

    suspend fun enableEngine(): EnableResult =
        request("/api/engine/enable", "POST", buildJsonObject {}) { RemoteJson.decodeFromString(EnableResult.serializer(), it) }

    // -------- ad blocking

    suspend fun adblock(probe: Boolean = false): AdBlockState =
        request("/api/adblock${if (probe) "?probe=1" else ""}", "GET") { RemoteJson.decodeFromString(AdBlockState.serializer(), it) }

    suspend fun setAdblock(enabled: Boolean, hostname: String? = null): AdBlockState =
        request(
            "/api/adblock", "POST",
            buildJsonObject { put("enabled", enabled); if (hostname != null) put("hostname", hostname) },
        ) { RemoteJson.decodeFromString(AdBlockState.serializer(), it) }

    // -------- proxy

    suspend fun proxy(): ProxyState =
        request("/api/proxy", "GET") { RemoteJson.decodeFromString(ProxyState.serializer(), it) }

    suspend fun setProxyEnabled(enabled: Boolean, id: String? = null): ProxyState =
        request("/api/proxy", "POST", buildJsonObject { put("enabled", enabled); if (id != null) put("id", id) }) {
            RemoteJson.decodeFromString(ProxyState.serializer(), it)
        }

    suspend fun saveProxyServer(server: JsonObject): ProxyState =
        request("/api/proxy/servers", "POST", server) { RemoteJson.decodeFromString(ProxyState.serializer(), it) }

    suspend fun deleteProxyServer(id: String): ProxyState =
        request("/api/proxy/servers/${id.encodeURLParameter()}", "DELETE") { RemoteJson.decodeFromString(ProxyState.serializer(), it) }

    suspend fun testProxyServer(id: String): ProxyState =
        request("/api/proxy/servers/${id.encodeURLParameter()}/test", "POST", buildJsonObject {}) {
            RemoteJson.decodeFromString(ProxyState.serializer(), it)
        }

    suspend fun selectProxy(id: String): ProxyState =
        request("/api/proxy/active", "POST", buildJsonObject { put("id", id) }) { RemoteJson.decodeFromString(ProxyState.serializer(), it) }

    suspend fun setProxyFailOpen(failOpen: Boolean): ProxyState =
        request("/api/proxy/options", "POST", buildJsonObject { put("failOpen", failOpen) }) { RemoteJson.decodeFromString(ProxyState.serializer(), it) }

    // -------- root

    suspend fun rootCheck(): RootReport =
        request("/api/root", "GET") { RemoteJson.decodeFromString(RootReport.serializer(), it) }

    // -------- bluetooth

    suspend fun bluetooth(): BluetoothStatusDto =
        request("/api/bluetooth", "GET") { RemoteJson.decodeFromString(BluetoothStatusDto.serializer(), it) }

    suspend fun setBluetoothState(enabled: Boolean): BluetoothActionResult =
        request("/api/bluetooth/state", "POST", buildJsonObject { put("enabled", enabled) }) {
            RemoteJson.decodeFromString(BluetoothActionResult.serializer(), it)
        }

    suspend fun scanBluetooth(action: String): BluetoothActionResult =
        request("/api/bluetooth/scan", "POST", buildJsonObject { put("action", action) }) {
            RemoteJson.decodeFromString(BluetoothActionResult.serializer(), it)
        }

    /** path is one of pair / connect / disconnect / unpair. */
    suspend fun bluetoothDeviceAction(path: String, address: String): BluetoothActionResult =
        request("/api/bluetooth/$path", "POST", buildJsonObject { put("address", address) }) {
            RemoteJson.decodeFromString(BluetoothActionResult.serializer(), it)
        }

    suspend fun launchTvPairing(): BluetoothActionResult =
        request("/api/bluetooth/launch-pairing", "POST", buildJsonObject {}) {
            RemoteJson.decodeFromString(BluetoothActionResult.serializer(), it)
        }

    // -------- files & sideload

    suspend fun files(): StorageInfo =
        request("/api/files", "GET") { RemoteJson.decodeFromString(StorageInfo.serializer(), it) }

    suspend fun installApk(name: String): FileActionResult =
        request("/api/files/install", "POST", buildJsonObject { put("name", name) }) {
            RemoteJson.decodeFromString(FileActionResult.serializer(), it)
        }

    suspend fun deleteFile(name: String) {
        request<Unit>("/api/files?name=${name.encodeURLParameter()}", "DELETE") { }
    }

    suspend fun uploadFile(bytes: ByteArray, filename: String, install: Boolean): FileActionResult {
        val res = http.post("$origin/api/files/upload${if (install) "?install=1" else ""}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                append(HttpHeaders.ContentType, "application/octet-stream")
                            },
                        )
                    },
                ),
            )
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) throw res.toApiException(text)
        return RemoteJson.decodeFromString(FileActionResult.serializer(), text)
    }

    fun artUrl(): String = "$origin/api/media/art?t=${System.currentTimeMillis()}&token=$token"
}

/**
 * The live control channel. Button repeats are latency sensitive, so they ride
 * this socket rather than REST. Reconnects on its own with a small backoff; the
 * TV drops idle sockets when it sleeps, which is normal and handled.
 */
class TvSocket(
    private val host: String,
    private val token: String,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val onStatus: (SocketStatus) -> Unit,
    private val onEvent: (Envelope) -> Unit,
) {
    enum class SocketStatus { Connecting, Open, Closed }

    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private var loop: Job? = null
    @Volatile private var closedByUs = false

    fun connect() {
        if (loop?.isActive == true) return
        closedByUs = false
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var retry = 0
        while (!closedByUs) {
            onStatus(SocketStatus.Connecting)
            try {
                http.webSocket(urlString = "ws://$host/ws?token=$token") {
                    session = this
                    retry = 0
                    onStatus(SocketStatus.Open)
                    sendRaw("hello", null)
                    val heartbeat = launch {
                        while (isActive) {
                            delay(20_000)
                            runCatching { sendRaw("ping", null) }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val env = runCatching {
                                    RemoteJson.decodeFromString(Envelope.serializer(), frame.readText())
                                }.getOrNull()
                                if (env != null) onEvent(env)
                            }
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } catch (_: Throwable) {
                // fall through to reconnect
            }
            session = null
            onStatus(SocketStatus.Closed)
            if (closedByUs) break
            retry = (retry + 1).coerceAtMost(8)
            val backoff = (400L * (1L shl (retry - 1))).coerceAtMost(6_000L)
            delay(backoff)
        }
    }

    private suspend fun sendRaw(type: String, data: JsonObject?) {
        val envelope = buildJsonObject {
            put("v", 1)
            put("type", type)
            if (data != null) put("data", data) else put("data", buildJsonObject {})
        }
        session?.send(Frame.Text(envelope.toString()))
    }

    /** Fire-and-forget send; silently no-ops when the socket is down. */
    fun send(type: String, data: JsonObject? = null) {
        scope.launch { runCatching { sendRaw(type, data) } }
    }

    val isOpen: Boolean get() = session != null

    fun close() {
        closedByUs = true
        val s = session
        scope.launch { runCatching { s?.close() } }
        loop?.cancel()
        loop = null
        session = null
    }
}

/** Builds the `data` object for a `key` socket message. */
fun keyPayload(key: RemoteKey, repeat: Int): JsonObject = buildJsonObject {
    put("key", key.wire)
    put("repeat", repeat)
}

/** Builds the `data` object for a `text` socket message. */
fun textPayload(text: String, mode: String, submit: Boolean): JsonObject = buildJsonObject {
    put("text", text)
    put("mode", mode)
    put("submit", submit)
}
