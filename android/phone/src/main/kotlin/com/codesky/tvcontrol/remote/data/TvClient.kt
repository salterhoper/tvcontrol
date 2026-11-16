package com.codesky.tvcontrol.remote.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
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
        val res = if (method == "GET") {
            http.get("$origin$path") { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            http.post("$origin$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
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
