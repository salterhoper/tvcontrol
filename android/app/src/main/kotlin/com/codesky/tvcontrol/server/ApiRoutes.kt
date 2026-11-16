package com.codesky.tvcontrol.server

import android.os.Build
import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.BuildConfig
import com.codesky.tvcontrol.accessibility.TextMode
import com.codesky.tvcontrol.config.LlmDefaults
import com.codesky.tvcontrol.control.MetricKey
import com.codesky.tvcontrol.core.AdBlock
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.Diagnostics
import com.codesky.tvcontrol.core.EngineAutoEnable
import com.codesky.tvcontrol.core.EngineCheck
import com.codesky.tvcontrol.core.RootCheck
import com.codesky.tvcontrol.proxy.ProxyTunnel
import com.codesky.tvcontrol.security.AuthStore
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The REST half of the API. Anything that is a one-shot request/response lives here;
 * anything that is chatty (button presses, assistant progress) goes over the
 * websocket instead. The full contract is documented in docs/API.md.
 */
fun Route.apiRoutes(c: AppContainer) {

    get("/api/ping") {
        call.respond(
            PingResponse(
                name = c.settings.deviceName,
                version = BuildConfig.VERSION_NAME,
                requiresPairing = true,
            ),
        )
    }

    post("/api/pair") {
        val request = runCatching { call.receive<PairRequest>() }.getOrNull()
        if (request == null || request.pin.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"pin\":\"123456\"}"))
            return@post
        }
        val devLabel = request.device ?: request.label ?: "Dashboard"
        when (val result = c.auth.pair(request.pin, devLabel)) {
            is AuthStore.Paired -> call.respond(
                PairResponse(
                    token = result.token,
                    name = c.settings.deviceName,
                    version = BuildConfig.VERSION_NAME,
                ),
            )

            is AuthStore.WrongPin -> call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("wrong_pin", "That PIN is not right. ${result.remaining} attempts left this minute."),
            )

            AuthStore.RateLimited -> call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("rate_limited", "Too many wrong PINs. Try again in a minute."),
            )
        }
    }

    // ------------------------------------------------------------- device state

    get("/api/info") {
        if (!call.guard(c)) return@get
        call.respond(
            InfoResponse(
                name = c.settings.deviceName,
                version = BuildConfig.VERSION_NAME,
                port = c.settings.port,
                deviceModel = Build.MODEL ?: "Android TV",
                androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
                engine = c.control.engineState(),
                volume = c.control.volumeState(),
                screen = c.bridge.latest,
                settings = settingsDto(c),
                serverTime = System.currentTimeMillis(),
                restrictedSettings = EngineCheck.restrictedSettingsBlocked(c.appContext),
            ),
        )
    }

    get("/api/screen") {
        if (!call.guard(c)) return@get
        call.respond(c.control.snapshot())
    }

    /**
     * Answers "what can this TV actually do": accessibility state, the install-source
     * block, and whether Google's remote-control service is present. Lets the
     * dashboard explain the situation without anybody running adb.
     */
    get("/api/diagnostics") {
        if (!call.guard(c)) return@get
        call.respond(Diagnostics.collect(c.appContext, c.bridge.connected))
    }

    /**
     * Puts the accessibility service back onto Android's enabled list. Only possible
     * when the owner has granted WRITE_SECURE_SETTINGS from an adb shell; the answer
     * says so plainly instead of pretending a normal install could do it.
     */
    post("/api/engine/enable") {
        if (!call.guard(c)) return@post
        val context = c.appContext
        val hasPermission = EngineAutoEnable.canWrite(context)
        val ok = EngineAutoEnable.tryEnable(context)
        call.respond(
            EnableResult(
                ok = ok,
                hasPermission = hasPermission,
                message = when {
                    ok -> "The service is on Android's enabled list - give it a second and check the engine state."
                    hasPermission -> "Android refused the write."
                    else -> "The app does not hold WRITE_SECURE_SETTINGS yet. Run \"pm grant ${context.packageName} " +
                        "android.permission.WRITE_SECURE_SETTINGS\" from an adb shell first."
                },
            ),
        )
    }

    get("/api/apps") {
        if (!call.guard(c)) return@get
        val refresh = call.request.queryParameters["refresh"]?.let { it == "1" || it == "true" } ?: false
        call.respond(AppsResponse(c.control.apps(refresh).map { AppDto(it.pkg, it.label, it.leanback) }))
    }

    get("/api/volume") {
        if (!call.guard(c)) return@get
        call.respond(c.control.volumeState())
    }

    // ----------------------------------------------------------------- control

    post("/api/key") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<KeyRequest>() }.getOrNull()
        val key = MetricKey.fromWire(request?.key)
        if (request == null || key == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("unknown_key", "Keys: " + MetricKey.entries.joinToString(", ") { it.wire }),
            )
            return@post
        }
        val result = c.control.key(key, request.repeat)
        call.respond(buildJsonObject {
            put("key", key.wire)
            put("result", AppJson.encodeToJsonElement(result))
        })
    }

    post("/api/text") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<TextRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"text\":\"...\"}"))
        val mode = when (request.mode.lowercase()) {
            "append" -> TextMode.APPEND
            "clear" -> TextMode.CLEAR
            "enter" -> TextMode.ENTER
            "backspace" -> TextMode.BACKSPACE
            else -> TextMode.REPLACE
        }
        call.respond(c.control.text(request.text, mode, request.submit))
    }

    post("/api/launch") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<LaunchRequest>() }.getOrNull()
        val target = request?.packageName ?: request?.query
        if (target.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"query\":\"netflix\"} or {\"package\":\"com.netflix.ninja\"}"))
            return@post
        }
        val outcome = c.control.launch(target)
        call.respond(outcome)
    }

    post("/api/search") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<SearchRequest>() }.getOrNull()
        if (request == null || request.query.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"query\":\"blade runner\",\"target\":\"auto|youtube|play\"}"))
            return@post
        }
        call.respond(c.control.search(request.query, request.target))
    }

    post("/api/volume") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<VolumeRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"action\":\"up|down|mute|set\",\"value\":40}"))
            return@post
        }
        call.respond(c.control.volume(request.action, request.value))
    }

    // ---------------------------------------------------------------- settings

    get("/api/settings") {
        if (!call.guard(c)) return@get
        call.respond(settingsDto(c))
    }

    put("/api/settings") {
        if (!call.guard(c)) return@put
        val patch = runCatching { call.receive<SettingsPatch>() }.getOrNull()
        if (patch == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Nothing to change"))
            return@put
        }

        patch.deviceName?.takeIf { it.isNotBlank() }?.let { c.settings.deviceName = it }
        patch.autoStart?.let { c.settings.autoStart = it }
        val portChanged = patch.port != null && patch.port != c.settings.port
        patch.port?.let { c.settings.port = it }

        patch.llm?.let { llm ->
            llm.provider?.takeIf { it in LlmDefaults.knownProviders() }?.let { provider ->
                if (provider != c.settings.llmProvider) {
                    c.settings.llmProvider = provider
                    c.settings.llmBaseUrl = LlmDefaults.baseUrl(provider)
                    c.settings.llmModel = LlmDefaults.model(provider)
                }
            }
            llm.baseUrl?.takeIf { it.isNotBlank() }?.let { c.settings.llmBaseUrl = it }
            llm.model?.takeIf { it.isNotBlank() }?.let { c.settings.llmModel = it }
            llm.apiKey?.let { c.settings.llmApiKey = it.takeIf { key -> key.isNotBlank() } }
        }

        patch.assistant?.let { assistant ->
            assistant.mode?.let { c.settings.assistantMode = it }
            assistant.maxSteps?.let { c.settings.maxSteps = it }
            assistant.stepDelayMs?.let { c.settings.stepDelayMs = it }
        }

        c.events.emit("settings.changed", null)
        if (portChanged) {
            // Restarting the listener from inside a request would drop the connection
            // this answer travels on, so the change applies at the next restart.
            c.events.emit("settings.note", buildJsonObject { put("message", "Port change takes effect after the server restarts.") })
        }
        call.respond(settingsDto(c))
    }

    post("/api/settings/pin/rotate") {
        if (!call.guard(c)) return@post
        c.auth.rotatePin()
        call.respond(settingsDto(c))
    }

    post("/api/settings/devices/revoke") {
        if (!call.guard(c)) return@post
        c.auth.revokeAll()
        call.respond(buildJsonObject { put("ok", true) })
    }

    /** Is the TV rooted, and can an app use it. Read-only apart from one `su -c id`. */
    get("/api/root") {
        if (!call.guard(c)) return@get
        call.respond(RootCheck.collect(c.appContext))
    }

    // -------------------------------------------------------------- ad blocking

    /**
     * System-wide ad blocking by Private DNS. The TV's own setting is the source of
     * truth, so this reads it instead of remembering a flag; `?probe=1` additionally
     * asks the network whether ad domains are really being sunk (a couple of seconds).
     */
    get("/api/adblock") {
        if (!call.guard(c)) return@get
        val probe = call.request.queryParameters["probe"]?.let { it == "1" || it == "true" } ?: false
        call.respond(AdBlock.state(c.appContext, probe))
    }

    /**
     * Turns the filter on or off. Enabling resolves the hostname and opens port 853 on
     * it first, then checks that ordinary names still resolve; if they do not, the
     * previous Private DNS settings are put back and the answer says so.
     */
    post("/api/adblock") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<AdBlockRequest>() }.getOrNull()
        if (request == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("bad_request", "Send {\"enabled\":true,\"hostname\":\"dns.adguard-dns.com\"}"),
            )
            return@post
        }
        call.respond(AdBlock.apply(c.appContext, request.enabled, request.hostname))
    }

    // ------------------------------------------------------------ socks5 proxy

    /** Saved servers, which one is active, and whether the TV is routed through it. */
    get("/api/proxy") {
        if (!call.guard(c)) return@get
        call.respond(ProxyTunnel.state(c.appContext, c.proxies, note = c.proxies.lastNote))
    }

    /**
     * Turns routing on (testing the server first, and asking for the one-time VPN
     * confirmation on the TV if Android needs it) or off.
     */
    post("/api/proxy") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<ProxyEnableRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"enabled\":true} or {\"enabled\":false}"))
            return@post
        }
        call.respond(ProxyTunnel.setEnabled(c.appContext, c.proxies, request.enabled, request.id))
    }

    post("/api/proxy/servers") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<ProxyServerRequest>() }.getOrNull()
        val host = request?.host?.trim().orEmpty()
        if (request == null || host.isEmpty() || host.any { it.isWhitespace() || it == '/' } || request.port !in 1..65535) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("bad_request", "Send {\"name\":\"Home\",\"host\":\"203.0.113.5\",\"port\":1080,\"username\":\"…\",\"password\":\"…\"}"),
            )
            return@post
        }
        if (request.id != null && c.proxies.server(request.id) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No server with that id."))
            return@post
        }
        val saved = c.proxies.upsert(request.id, request.name.trim(), host, request.port, request.username, request.password)
        ProxyTunnel.changed(c.proxies, saved.id)
        call.respond(ProxyTunnel.state(c.appContext, c.proxies, note = "Saved ${saved.name}."))
    }

    delete("/api/proxy/servers/{id}") {
        if (!call.guard(c)) return@delete
        val id = call.parameters["id"].orEmpty()
        call.respond(ProxyTunnel.remove(c.appContext, c.proxies, id))
    }

    /** Connects through the server and reports the exit address; nothing is switched. */
    post("/api/proxy/servers/{id}/test") {
        if (!call.guard(c)) return@post
        val id = call.parameters["id"].orEmpty()
        val result = ProxyTunnel.test(c.proxies, id)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No server with that id."))
            return@post
        }
        call.respond(ProxyTunnel.state(c.appContext, c.proxies, note = result.message))
    }

    /** Switches servers; while routing is on, traffic moves over immediately. */
    post("/api/proxy/active") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<ProxySelectRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"id\":\"…\"}"))
            return@post
        }
        call.respond(ProxyTunnel.select(c.appContext, c.proxies, request.id))
    }

    post("/api/proxy/options") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<ProxyOptionsRequest>() }.getOrNull() ?: ProxyOptionsRequest()
        request.failOpen?.let { c.proxies.failOpen = it }
        call.respond(ProxyTunnel.state(c.appContext, c.proxies))
    }

    // --------------------------------------------------------------- assistant

    post("/api/assistant") {
        if (!call.guard(c)) return@post
        val request = runCatching { call.receive<AssistantRequest>() }.getOrNull()
        if (request == null || request.utterance.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"utterance\":\"open netflix and search for dune\"}"))
            return@post
        }
        val runId = c.assistant.start(request.utterance, request.useScreen, request.dryRun)
        call.respond(RunIdResponse(runId))
    }

    post("/api/assistant/cancel") {
        if (!call.guard(c)) return@post
        c.assistant.cancel()
        call.respond(buildJsonObject { put("ok", true) })
    }

    get("/api/assistant/{id}") {
        if (!call.guard(c)) return@get
        val id = call.parameters["id"]
        val run = id?.let { c.assistant.get(it) }
        if (run == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such run"))
        } else {
            call.respond(run)
        }
    }

    get("/api/health") {
        if (!call.guard(c)) return@get
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("dashboards", c.ws.connectionCount())
                put("accessibility", c.bridge.connected)
            },
        )
    }

    // --------------------------------------------------------------- bluetooth

    get("/api/bluetooth") {
        if (!call.guard(c)) return@get
        call.respond(c.bluetooth.getStatus())
    }

    post("/api/bluetooth/state") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothStateRequest>() }.getOrNull()
        if (req == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"enabled\":true/false}"))
            return@post
        }
        call.respond(c.bluetooth.setState(req.enabled))
    }

    post("/api/bluetooth/scan") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothScanRequest>() }.getOrNull()
        val action = req?.action?.lowercase() ?: "start"
        val result = if (action == "stop") c.bluetooth.stopScan() else c.bluetooth.startScan()
        call.respond(result)
    }

    post("/api/bluetooth/pair") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothDeviceActionRequest>() }.getOrNull()
        if (req == null || req.address.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"address\":\"XX:XX:XX:XX:XX:XX\"}"))
            return@post
        }
        call.respond(c.bluetooth.pair(req.address))
    }

    post("/api/bluetooth/connect") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothDeviceActionRequest>() }.getOrNull()
        if (req == null || req.address.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"address\":\"XX:XX:XX:XX:XX:XX\"}"))
            return@post
        }
        call.respond(c.bluetooth.connect(req.address))
    }

    post("/api/bluetooth/disconnect") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothDeviceActionRequest>() }.getOrNull()
        if (req == null || req.address.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"address\":\"XX:XX:XX:XX:XX:XX\"}"))
            return@post
        }
        call.respond(c.bluetooth.disconnect(req.address))
    }

    post("/api/bluetooth/unpair") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<BluetoothDeviceActionRequest>() }.getOrNull()
        if (req == null || req.address.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"address\":\"XX:XX:XX:XX:XX:XX\"}"))
            return@post
        }
        call.respond(c.bluetooth.unpair(req.address))
    }

    post("/api/bluetooth/launch-pairing") {
        if (!call.guard(c)) return@post
        call.respond(c.bluetooth.launchPairingActivity())
    }

    // ------------------------------------------------------------------ files / sideload

    get("/api/files") {
        if (!call.guard(c)) return@get
        call.respond(c.files.getStorageInfo())
    }

    post("/api/files/upload") {
        if (!call.guard(c)) return@post
        val shouldInstall = call.request.queryParameters["install"]?.let { it == "1" || it == "true" } ?: false
        var savedFile: com.codesky.tvcontrol.files.FileItemDto? = null

        val multipart = runCatching { call.receiveMultipart() }.getOrNull()
        if (multipart != null) {
            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val originalName = part.originalFileName ?: "file_${System.currentTimeMillis()}"
                    part.streamProvider().use { stream ->
                        savedFile = c.files.saveFile(originalName, stream)
                    }
                }
                part.dispose()
            }
        }

        val file = savedFile
        if (file == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("upload_failed", "No file found in upload"))
            return@post
        }

        if (shouldInstall && file.isApk) {
            call.respond(c.files.installApk(file.name))
        } else {
            call.respond(com.codesky.tvcontrol.files.FileActionResponse(ok = true, message = "File uploaded successfully", file = file))
        }
    }

    post("/api/files/install") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<FileActionRequest>() }.getOrNull()
        if (req == null || req.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"name\":\"app.apk\"}"))
            return@post
        }
        call.respond(c.files.installApk(req.name))
    }

    delete("/api/files") {
        if (!call.guard(c)) return@delete
        val queryName = call.request.queryParameters["name"]
        val name = if (!queryName.isNullOrBlank()) {
            queryName
        } else if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > 0) {
            runCatching { call.receive<FileActionRequest>().name }.getOrNull()
        } else {
            null
        }
        if (name.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing file name"))
            return@delete
        }
        val ok = c.files.deleteFile(name)
        call.respond(buildJsonObject { put("ok", ok) })
    }

    post("/api/files/delete") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<FileActionRequest>() }.getOrNull()
        if (req == null || req.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"name\":\"file.ext\"}"))
            return@post
        }
        val ok = c.files.deleteFile(req.name)
        call.respond(buildJsonObject { put("ok", ok) })
    }

    get("/api/files/download") {
        if (!call.guard(c)) return@get
        val name = call.request.queryParameters["name"]
        if (name.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing file name"))
            return@get
        }
        val file = c.files.getFile(name)
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "File not found"))
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
        )
        call.respondFile(file)
    }

    // ------------------------------------------------------------- media / now playing

    get("/api/media/now-playing") {
        if (!call.guard(c)) return@get
        call.respond(c.nowPlaying.getNowPlaying())
    }

    post("/api/media/control") {
        if (!call.guard(c)) return@post
        val req = runCatching { call.receive<MediaControlRequest>() }.getOrNull()
        if (req == null || req.action.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Send {\"action\":\"play|pause|play_pause|next|previous|seek\",\"positionMs\":1234}"))
            return@post
        }
        call.respond(c.nowPlaying.control(req.action, req.positionMs))
    }

    get("/api/media/art") {
        if (!call.guard(c)) return@get
        val bytes = c.nowPlaying.currentArtworkBytes
        if (bytes == null || bytes.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No artwork available"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

private suspend fun ApplicationCall.guard(c: AppContainer): Boolean {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    val token = header.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.queryParameters["token"]?.trim()?.takeIf { it.isNotEmpty() }

    if (token != null && c.auth.verify(token)) return true

    respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(
            "unauthorized",
            "Pair first: POST /api/pair with the PIN shown on the TV screen.",
        ),
    )
    return false
}

internal fun settingsDto(c: AppContainer): SettingsDto = SettingsDto(
    port = c.settings.port,
    deviceName = c.settings.deviceName,
    autoStart = c.settings.autoStart,
    pin = c.auth.pin(),
    devices = c.auth.devices(),
    llm = LlmSettingsDto(
        provider = c.settings.llmProvider,
        baseUrl = c.settings.llmBaseUrl,
        model = c.settings.llmModel,
        apiKeyConfigured = c.settings.llmApiKeyConfigured,
        providers = LlmDefaults.knownProviders(),
    ),
    assistant = AssistantSettingsDto(
        mode = c.settings.assistantMode,
        maxSteps = c.settings.maxSteps,
        stepDelayMs = c.settings.stepDelayMs,
    ),
)
