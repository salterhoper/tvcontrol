package com.codesky.tvcontrol.server

import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.accessibility.TextMode
import com.codesky.tvcontrol.control.MetricKey
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.core.ServerEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Fan-out of the event bus to every connected dashboard, plus the command channel
 * that comes back the other way. One websocket carries everything: button repeats
 * are chatty, so this is the only channel where latency matters.
 */
class WsHub(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = ConcurrentHashMap<String, WsConnection>()
    private var fanout: Job? = null

    @Synchronized
    private fun ensureFanout() {
        if (fanout?.isActive == true) return
        fanout = scope.launch {
            container.events.events.collect { event -> broadcast(event) }
        }
    }

    fun connectionCount(): Int = connections.size

    suspend fun handle(session: DefaultWebSocketServerSession, token: String? = null) {
        ensureFanout()
        val label = container.auth.deviceLabel(token)
        val connection = WsConnection(
            id = UUID.randomUUID().toString().substring(0, 8),
            session = session,
            json = AppJson,
            deviceLabel = label,
        )
        connections[connection.id] = connection
        container.events.isRemoteConnected.value = true
        container.events.activeDevices.value = connections.values.map { it.deviceLabel }.distinct()
        Logging.i("Dashboard connected (${connection.id} / $label), ${connections.size} online")

        try {
            coroutineScope {
                val writer = launch { connection.pump() }
                try {
                    for (frame in session.incoming) {
                        if (frame !is Frame.Text) continue
                        val message = runCatching {
                            AppJson.decodeFromString(WsClientMessage.serializer(), frame.readText())
                        }.getOrNull() ?: continue
                        dispatch(connection, message)
                    }
                } finally {
                    // The client is gone (or the read loop failed); stop the writer so
                    // this coroutine can finish and the connection is cleaned up.
                    writer.cancel()
                }
            }
        } catch (t: Throwable) {
            Logging.d("Websocket closed: ${t.message}")
        } finally {
            connections.remove(connection.id)
            container.events.isRemoteConnected.value = connections.isNotEmpty()
            container.events.activeDevices.value = connections.values.map { it.deviceLabel }.distinct()
            Logging.i("Dashboard disconnected (${connection.id} / $label), ${connections.size} online")
        }
    }

    private fun broadcast(event: ServerEvent) {
        connections.values.forEach { it.offer(event) }
    }

    /** Sends a snapshot of everything a freshly connected dashboard needs. */
    private fun greet(connection: WsConnection) {
        val settings = container.settings
        connection.offer(
            ServerEvent(
                type = "hello",
                data = buildJsonObject {
                    put("name", settings.deviceName)
                    put("version", com.codesky.tvcontrol.BuildConfig.VERSION_NAME)
                    put("port", settings.port)
                    put("mode", settings.assistantMode)
                },
            ),
        )
        container.events.emit("engine.state", buildJsonObject { put("connected", container.bridge.connected) })
        container.bridge.latest?.let { container.events.emit("screen.update", AppJson.encodeToJsonElement(it)) }
    }

    private suspend fun dispatch(connection: WsConnection, message: WsClientMessage) {
        val data = message.data ?: JsonObject(emptyMap())
        when (message.type) {
            "ping" -> connection.offer(ServerEvent("pong"))
            "hello" -> greet(connection)

            "key" -> {
                val key = MetricKey.fromWire(data.string("key"))
                if (key == null) {
                    connection.offer(ServerEvent("error", buildJsonObject { put("message", "Unknown key") }))
                    return
                }
                val result = container.control.key(key, data.int("repeat") ?: 1)
                connection.offer(
                    ServerEvent(
                        "key.result",
                        buildJsonObject {
                            put("key", key.wire)
                            put("result", AppJson.encodeToJsonElement(result))
                        },
                    ),
                )
            }

            "text" -> {
                val mode = when (data.string("mode")?.lowercase()) {
                    "append" -> TextMode.APPEND
                    "clear" -> TextMode.CLEAR
                    "enter" -> TextMode.ENTER
                    "backspace" -> TextMode.BACKSPACE
                    else -> TextMode.REPLACE
                }
                val result = container.control.text(data.string("text").orEmpty(), mode, data.bool("submit") ?: false)
                connection.offer(ServerEvent("text.result", AppJson.encodeToJsonElement(result)))
            }

            "screen.request" -> {
                val snapshot = container.bridge.snapshot()
                connection.offer(ServerEvent("screen.update", AppJson.encodeToJsonElement(snapshot)))
            }

            "assistant.run" -> {
                val utterance = data.string("utterance")
                if (utterance.isNullOrBlank()) {
                    connection.offer(ServerEvent("error", buildJsonObject { put("message", "Say something first") }))
                } else {
                    val runId = container.assistant.start(
                        utterance = utterance,
                        useContext = data.bool("useScreen") ?: true,
                        dryRun = data.bool("dryRun") ?: false,
                    )
                    connection.offer(ServerEvent("assistant.started", buildJsonObject { put("runId", runId) }))
                }
            }

            "assistant.cancel" -> container.assistant.cancel()

            else -> connection.offer(
                ServerEvent("error", buildJsonObject { put("message", "Unknown message type ${message.type}") }),
            )
        }
    }

    fun stop() {
        fanout?.cancel()
        fanout = null
        connections.values.forEach { it.shutdown(scope) }
        connections.clear()
        container.events.isRemoteConnected.value = false
    }

    class WsConnection(
        val id: String,
        private val session: DefaultWebSocketServerSession,
        private val json: kotlinx.serialization.json.Json,
        val deviceLabel: String = "Dashboard",
    ) {
        private val outbox = Channel<ServerEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        fun offer(event: ServerEvent) {
            outbox.trySend(event)
        }

        suspend fun pump() {
            for (event in outbox) {
                val envelope = WsEnvelope(type = event.type, ts = event.ts, data = event.data)
                session.send(Frame.Text(json.encodeToString(WsEnvelope.serializer(), envelope)))
            }
        }

        fun shutdown(scope: CoroutineScope) {
            outbox.close()
            scope.launch { runCatching { session.close() } }
        }
    }
}

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
