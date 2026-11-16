package com.codesky.tvcontrol.server

import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.Logging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

fun Application.tvControlModule(container: AppContainer) {
    val json = AppJson

    install(ContentNegotiation) { json(json) }

    install(WebSockets) {
        masking = false
        maxFrameSize = MAX_FRAME_BYTES
    }

    // The dashboard is normally served from this same origin; CORS exists so that
    // `npm run dev` on a laptop (port 5173) can talk to a TV on the LAN.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            Logging.w("Request failed: ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal", cause.message ?: "Unexpected failure"),
            )
        }
    }

    val assets = StaticAssets(container.appContext)

    routing {
        apiRoutes(container)

        webSocket("/ws") {
            val token = call.request.queryParameters["token"]
            if (!container.auth.verify(token)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pair first"))
                return@webSocket
            }
            container.ws.handle(this, token)
        }

        staticRoutes(assets)
    }
}

/**
 * Everything that is not the API is the dashboard: index.html plus its hashed
 * bundle, straight out of the APK. Unknown paths without a file extension fall back
 * to index.html so client side routing works.
 */
private fun Route.staticRoutes(assets: StaticAssets) {
    get("/{path...}") {
        val path = call.request.path()
        if (path.startsWith("/api") || path.startsWith("/ws")) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such endpoint"))
            return@get
        }

        val looksLikeFile = path.substringAfterLast('/', "").contains('.')
        val bytes = assets.read(path) ?: if (looksLikeFile) null else assets.indexHtml
        if (bytes == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(
                    "not_found",
                    "No dashboard asset for $path. Rebuild the APK so the dashboard is packaged.",
                ),
            )
            return@get
        }

        call.response.headers.append(
            HttpHeaders.CacheControl,
            if (assets.isImmutable(path)) "public, max-age=31536000, immutable" else "no-store",
        )
        call.respondBytes(bytes, ContentType.parse(assets.contentType(if (looksLikeFile) path else "index.html")))
    }
}

private const val MAX_FRAME_BYTES = 4L * 1024 * 1024
