package com.codesky.tvcontrol.control

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.codesky.tvcontrol.core.Logging
import kotlinx.serialization.Serializable

/**
 * Launches other apps on the TV. Only apps that expose a TV or phone launcher icon
 * are considered, which is exactly the set a remote should be able to open.
 */
class AppLauncher(private val context: Context) {

    @Serializable
    data class AppEntry(
        val pkg: String,
        val label: String,
        val leanback: Boolean,
    )

    @Volatile
    private var cache: List<AppEntry> = emptyList()

    @Volatile
    private var cachedAt: Long = 0L

    fun list(force: Boolean = false): List<AppEntry> {
        val fresh = System.currentTimeMillis() - cachedAt < CACHE_MS
        if (!force && fresh && cache.isNotEmpty()) return cache

        val pm = context.packageManager
        val entries = LinkedHashMap<String, AppEntry>()
        for (leanback in listOf(true, false)) {
            val category = if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved: List<ResolveInfo> = runCatching {
                pm.queryIntentActivities(intent, 0)
            }.getOrDefault(emptyList())

            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                val existing = entries[pkg]
                if (existing == null || (leanback && !existing.leanback)) {
                    entries[pkg] = AppEntry(pkg = pkg, label = label, leanback = leanback)
                }
            }
        }

        val sorted = entries.values.sortedBy { it.label.lowercase() }
        cache = sorted
        cachedAt = System.currentTimeMillis()
        return sorted
    }

    /** Finds the best app match for a human string: "netflix", "you tube", "com.netflix.ninja". */
    fun resolve(query: String): AppEntry? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val apps = list()
        return apps.firstOrNull { it.pkg.equals(needle, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase() == needle }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(needle) }
            ?: apps.firstOrNull { it.label.lowercase().contains(needle) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(needle) }
            ?: apps.mapNotNull { app ->
                val distance = editDistance(needle, app.label.lowercase())
                if (distance <= 2) app to distance else null
            }.minByOrNull { it.second }?.first
    }

    fun launch(pkg: String): Boolean {
        val pm = context.packageManager
        val explicit = Intent(Intent.ACTION_MAIN).apply {
            setPackage(pkg)
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (start(explicit)) return true

        val fallback = Intent(Intent.ACTION_MAIN).apply {
            setPackage(pkg)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (start(fallback)) return true

        val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent != null && start(launchIntent)
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.onFailure { Logging.w("Could not start $intent", it) }.getOrDefault(false)

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    previous + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                previous = temp
            }
        }
        return dp[b.length]
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
