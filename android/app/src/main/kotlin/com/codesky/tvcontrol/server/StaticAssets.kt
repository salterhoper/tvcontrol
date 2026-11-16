package com.codesky.tvcontrol.server

import android.content.Context
import com.codesky.tvcontrol.core.Logging

/**
 * Serves the web dashboard straight out of the APK. The dashboard build is copied
 * into assets/dashboard by the Gradle build, so there is nothing to install on the
 * phone: open the TV's address, done.
 */
class StaticAssets(
    private val context: Context,
    private val root: String = "dashboard",
) {

    val indexHtml: ByteArray? by lazy { read("index.html") }

    fun read(path: String): ByteArray? {
        val clean = sanitize(path) ?: return null
        return runCatching { context.assets.open("$root/$clean").use { it.readBytes() } }
            .onFailure { Logging.d("Asset miss: $clean") }
            .getOrNull()
    }

    fun contentType(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain; charset=utf-8"
            "webmanifest" -> "application/manifest+json"
            else -> "application/octet-stream"
        }
    }

    /** True when this looks like a hashed build artefact that may be cached for ever. */
    fun isImmutable(path: String): Boolean =
        path.startsWith("assets/") && Regex("""\.[0-9a-fA-F]{8,}\.""").containsMatchIn(path)

    private fun sanitize(path: String): String? {
        val withoutQuery = path.substringBefore('?').substringBefore('#')
        val decoded = runCatching { java.net.URLDecoder.decode(withoutQuery, "UTF-8") }.getOrNull() ?: return null
        val trimmed = decoded.trimStart('/')
        if (trimmed.isEmpty()) return "index.html"
        if (trimmed.contains("..")) return null
        return trimmed
    }
}
