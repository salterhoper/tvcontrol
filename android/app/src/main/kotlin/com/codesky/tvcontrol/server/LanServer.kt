package com.codesky.tvcontrol.server

import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.core.NetInfo
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.ServerSocketChannel

/**
 * The listener itself. Kept deliberately dumb: bind, serve, advertise, report.
 *
 * Starting a server on a TV is defensive work. Ktor binds the socket inside its own
 * coroutine, and anything that escapes from there is an unhandled exception on a
 * worker thread - which on Android means the whole process is killed, accessibility
 * engine included. So the port is probed first, the engine gets an exception handler
 * as a backstop, and "started" is only reported once the socket really accepts.
 */
class LanServer(private val container: AppContainer) {

    data class StartResult(val ok: Boolean, val port: Int, val error: String?)

    private val advertiser = NsdAdvertiser(container.appContext)
    private var engine: EmbeddedServer<*, *>? = null

    /** Last line of defence: turns an engine crash into a log line instead of a dead app. */
    private val engineGuard = CoroutineExceptionHandler { _, error ->
        Logging.e("The server engine failed in the background", error)
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + engineGuard)

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    /**
     * Synchronised on purpose. Boot hands us two start requests (the boot receiver and
     * the accessibility engine) and a second engine racing the first one against the
     * same port is exactly how this used to take the process down.
     */
    @Synchronized
    fun start(port: Int = container.settings.port): StartResult {
        if (isRunning) return StartResult(true, boundPort, null)

        if (!portIsFree(port)) {
            val message = "Port $port is already in use on this TV"
            Logging.w(message)
            return StartResult(false, 0, message)
        }

        return runCatching {
            val server = engineScope.embeddedServer(
                CIO,
                port = port,
                host = "0.0.0.0",
                parentCoroutineContext = engineGuard,
            ) {
                tvControlModule(container)
            }
            server.start(wait = false)

            // Ktor's bind happens in its own coroutine, so a successful start() only
            // means "the engine was asked to listen". Prove the socket is live before
            // telling anyone the dashboard is reachable.
            if (!acceptsConnections(port)) {
                runCatching { server.stop(400, 1500) }
                Logging.w("Port $port did not open, treating the start as failed")
                return@runCatching StartResult(false, 0, "Port $port never opened")
            }

            engine = server
            isRunning = true
            boundPort = port
            advertiser.start(container.settings.deviceName, port)
            Logging.i("LAN server listening on ${NetInfo.baseUrl(port)}")
            emitState()
            container.events.emit(
                "server.url",
                buildJsonObject { put("url", NetInfo.baseUrl(port)) },
            )
            StartResult(true, port, null)
        }
            .onFailure { Logging.e("Could not start the LAN server", it) }
            .getOrElse { StartResult(false, 0, it.message ?: "Could not start the server") }
    }

    private fun acceptsConnections(port: Int): Boolean {
        repeat(50) {
            val connected = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (connected) return true
            Thread.sleep(100)
        }
        return false
    }

    @Synchronized
    fun stop() {
        if (!isRunning && engine == null) return
        runCatching { engine?.stop(400, 1500) }
            .onFailure { Logging.w("Server stop failed", it) }
        engine = null
        isRunning = false
        boundPort = 0
        advertiser.stop()
        emitState()
    }

    private fun emitState() {
        container.events.emit(
            "server.state",
            buildJsonObject {
                put("running", isRunning)
                put("port", boundPort)
                if (isRunning) put("url", NetInfo.baseUrl(boundPort))
            },
        )
    }

    private fun portIsFree(port: Int): Boolean =
        runCatching {
            // A plain ServerSocketChannel, bound exactly the way Ktor's CIO engine
            // binds (no SO_REUSEADDR - unlike plain ServerSocket, which sets it).
            // After a restart the port usually sits in TIME_WAIT for up to a minute,
            // and if that bind failure happened *inside* Ktor instead it would escape
            // its accept coroutine and take the whole process down.
            ServerSocketChannel.open().use { channel ->
                channel.bind(InetSocketAddress("0.0.0.0", port))
            }
            true
        }.getOrDefault(false)
}
