package com.codesky.tvcontrol.control

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.codesky.tvcontrol.core.Logging
import java.net.URLEncoder

/**
 * "Just search for it" shortcuts. Deep links are the fastest and most reliable path
 * on a TV: it beats navigating a UI we do not control. Every entry point is
 * best-effort and reports honestly when nothing can handle it.
 */
class DeepLinks(private val context: Context) {

    sealed interface Outcome {
        data class Handled(val how: String, val target: String?) : Outcome
        data class Unhandled(val reason: String) : Outcome
    }

    /** target: "auto", "youtube", "play". */
    fun search(query: String, target: String): Outcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Outcome.Unhandled("Empty search query")

        return when (target.lowercase()) {
            "youtube" -> openYouTube(trimmed)
            "play" -> openPlayStore(trimmed)
            else -> when {
                isInstalled(YOUTUBE_TV) || isInstalled(YOUTUBE_TV_ALT) -> openYouTube(trimmed)
                else -> openPlayStore(trimmed)
            }
        }
    }

    fun openUrl(url: String): Outcome {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(intent, "browser", null)
            ?: Outcome.Unhandled("No app can open $url")
    }

    private fun openYouTube(query: String): Outcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val web = Uri.parse("https://www.youtube.com/results?search_query=$encoded")

        for (pkg in listOf(YOUTUBE_TV, YOUTUBE_TV_ALT)) {
            if (!isInstalled(pkg)) continue
            val explicit = Intent(Intent.ACTION_VIEW, web)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fire(explicit, "youtube-deeplink", pkg)?.let { return it }
        }

        val any = Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(any, "youtube-link", null)
            ?: Outcome.Unhandled("No app on this TV can open YouTube search results")
    }

    private fun openPlayStore(query: String): Outcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fire(market, "play-market", PLAY_STORE)?.let { return it }

        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/search?q=$encoded"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(web, "play-web", null)
            ?: Outcome.Unhandled("The Play Store is not available on this device")
    }

    /** Google TV's own search, used as a fallback when nothing more specific fits. */
    fun globalSearch(query: String): Outcome {
        val intent = Intent(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(intent, "system-search", null)
            ?: Outcome.Unhandled("This TV has no system search activity")
    }

    private fun fire(intent: Intent, how: String, target: String?): Outcome.Handled? = runCatching {
        context.startActivity(intent)
        Outcome.Handled(how, target ?: intent.`package`)
    }.onFailure { Logging.w("Deep link failed: $intent", it) }.getOrNull()

    private fun isInstalled(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val YOUTUBE_TV = "com.google.android.youtube.tv"
        const val YOUTUBE_TV_ALT = "com.google.android.youtube"
        const val PLAY_STORE = "com.android.vending"
    }
}
