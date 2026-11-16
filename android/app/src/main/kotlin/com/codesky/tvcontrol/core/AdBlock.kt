package com.codesky.tvcontrol.core

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.DnsResolver
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Blocks ads and trackers for every app on the TV by pointing Android's Private DNS
 * (DNS-over-TLS) at a resolver that filters.
 *
 * Why this shape:
 *
 *  - a hosts file would need a writable `/system` (root) and is bypassed by exactly
 *    the same things Private DNS is, with more risk;
 *  - an in-app `VpnService` would own the whole tunnel: a consent dialog on the TV,
 *    a second long-lived service to keep alive (this box force-stops background apps,
 *    see ADR-009), DNS packet plumbing - a project of its own;
 *  - Private DNS is two `Settings.Global` writes, and this app already holds
 *    `WRITE_SECURE_SETTINGS` for the accessibility engine. The change is a platform
 *    setting, so it survives reboots by itself. Phones and laptops on the same
 *    network keep their own DNS.
 *
 * What this cannot do: YouTube's in-video ads. The player fetches ad and video
 * segments from the same `googlevideo.com` hosts, so DNS-level filtering there either
 * does nothing or breaks playback. Those need a different YouTube client (SmartTube)
 * or pressing the player's own skip button - deliberately out of scope.
 *
 * Safety: a wrong or unreachable resolver takes the whole TV's DNS down, so enabling
 * first resolves the hostname and opens a TCP connection to port 853 on it. After the
 * switch it checks that ordinary names still resolve, and if they do not it puts the
 * previous settings back on its own.
 */
object AdBlock {

    /** Where the filter normally points: AdGuard's free public resolver. */
    const val DEFAULT_HOSTNAME = "dns.adguard-dns.com"

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"
    private const val MODE_HOSTNAME = "hostname"
    private const val MODE_OFF = "off"
    private const val DOT_PORT = 853
    private const val PROBE_TIMEOUT_MS = 1500
    private const val DNS_TIMEOUT_MS = 5000L
    private const val APPLY_SETTLE_MS = 1500L
    private const val RESTORE_SETTLE_MS = 1000L
    private const val VERIFY_ATTEMPTS = 3
    private const val WATCH_FAILS_BEFORE_RESTORE = 2

    /** Names that must keep resolving for the TV to work, and names that should sink. */
    private val HEALTHY_HOSTS = listOf("connectivitycheck.gstatic.com", "example.com")
    private val AD_HOSTS = listOf("pagead2.googlesyndication.com", "googleads.g.doubleclick.net")

    private const val PREFS = "adblock"
    private const val P_HOSTNAME = "hostname"
    private const val P_DESIRED = "desired"
    private const val P_HAS_PREVIOUS = "hasPrevious"
    private const val P_PREVIOUS_MODE = "previousMode"
    private const val P_PREVIOUS_SPECIFIER = "previousSpecifier"
    private const val P_LAST_AT = "lastCheckAt"
    private const val P_LAST_NORMAL = "lastCheckNormal"
    private const val P_LAST_ADS = "lastCheckAds"
    private const val P_LAST_NOTE = "lastCheckNote"

    @Serializable
    data class Preset(val id: String, val label: String, val hostname: String, val note: String)

    /** Outcome of one live probe: can the TV still resolve, are ad names being sunk. */
    @Serializable
    data class Check(
        val at: Long,
        val normalResolves: Boolean,
        val adBlocked: Boolean,
        val note: String,
    )

    @Serializable
    data class State(
        val enabled: Boolean,
        /** The resolver the TV is using right now; null when Private DNS is off. */
        val hostname: String? = null,
        /** Raw `private_dns_mode` value, for the curious. */
        val mode: String? = null,
        val canWrite: Boolean = false,
        /** The resolver the app would apply next (remembered between sessions). */
        val chosenHostname: String = DEFAULT_HOSTNAME,
        /** What turning the filter off restores; null means the network's own DNS. */
        val previousHostname: String? = null,
        val presets: List<Preset> = PRESETS,
        val lastCheck: Check? = null,
        /** Result of the last action - success sentence or the reason it was refused. */
        val note: String? = null,
        val verdict: String = "",
        val actions: List<String> = emptyList(),
    )

    val PRESETS = listOf(
        Preset("adguard", "AdGuard DNS", "dns.adguard-dns.com", "Ads, trackers and phishing. Free, no account."),
        Preset("adguard-family", "AdGuard DNS Family", "family.adguard-dns.com", "AdGuard DNS plus adult content."),
        Preset("mullvad", "Mullvad (ads + trackers)", "adblock.dns.mullvad.net", "No logging, ad and tracker lists only."),
        Preset("controld", "Control D (ads + trackers)", "p2.freedns.controld.com", "Free, no account. Blocks ads and trackers."),
        Preset("cleanbrowsing", "CleanBrowsing Security", "security-filter-dns.cleanbrowsing.org", "Malware and phishing only - no ad lists."),
    )

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Reads the TV's setting; only probes the network when the caller asks for it. */
    suspend fun state(context: Context, probe: Boolean = false): State = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val check = if (probe) runCheck().also { store(prefs, it) } else stored(prefs)
        build(context, prefs, check, note = null)
    }

    /**
     * Turns the filter on (with [hostname], or the remembered one) or off. Off restores
     * whatever Private DNS was set to before the app touched it.
     */
    suspend fun apply(context: Context, enabled: Boolean, hostname: String?): State = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        if (!canWrite(context)) {
            return@withContext build(
                context,
                prefs,
                stored(prefs),
                note = "Android did not grant WRITE_SECURE_SETTINGS to this app, so it cannot change Private DNS.",
            )
        }

        if (!enabled) {
            prefs.edit().putBoolean(P_DESIRED, false).apply()
            restore(context, prefs)
            // Probe again after the switch-back: the dashboard then says "not blocking"
            // instead of showing a stale "blocked", and it doubles as proof that the TV
            // resolves on its own DNS again.
            delay(RESTORE_SETTLE_MS)
            val after = runCheck()
            store(prefs, after)
            return@withContext build(
                context,
                prefs,
                after,
                note = "Ad blocking is off; the TV is back on the DNS servers your network hands out.",
            )
        }

        val target = normalise(hostname ?: prefs.getString(P_HOSTNAME, null) ?: DEFAULT_HOSTNAME)
        if (!looksLikeHostname(target)) {
            return@withContext build(
                context,
                prefs,
                stored(prefs),
                note = "\"$target\" is not a resolver hostname. Use something like dns.adguard-dns.com.",
            )
        }

        preflight(target)?.let { reason ->
            return@withContext build(context, prefs, stored(prefs), note = reason)
        }

        rememberPrevious(context, prefs, target)
        val wrote = runCatching {
            val mode = Settings.Global.putString(context.contentResolver, KEY_MODE, MODE_HOSTNAME)
            val specifier = Settings.Global.putString(context.contentResolver, KEY_SPECIFIER, target)
            mode && specifier
        }.getOrDefault(false)
        if (!wrote) {
            restore(context, prefs)
            return@withContext build(context, prefs, stored(prefs), note = "Android refused the settings write.")
        }
        prefs.edit().putString(P_HOSTNAME, target).putBoolean(P_DESIRED, true).apply()

        // Give the platform a moment to switch resolvers, then insist that the TV can
        // still resolve ordinary names. If it cannot, put the previous settings back:
        // a TV whose DNS is dead is worse than a TV showing ads.
        var check: Check? = null
        for (attempt in 0 until VERIFY_ATTEMPTS) {
            delay(if (attempt == 0) APPLY_SETTLE_MS else 2000L)
            check = runCheck()
            if (check.normalResolves) break
        }
        val verified = check
        if (verified == null || !verified.normalResolves) {
            prefs.edit().putBoolean(P_DESIRED, false).apply()
            restore(context, prefs)
            val after = runCheck()
            store(prefs, after)
            return@withContext build(
                context,
                prefs,
                after,
                note = "The resolver did not answer, so the TV was put back on its previous DNS.",
            )
        }

        store(prefs, verified)
        build(context, prefs, verified, note = if (verified.adBlocked) "Ad blocking is on." else "Private DNS is on, but the test ad domain still resolved.")
    }

    // ----------------------------------------------------------------- watchdog

    private var watchFails = 0

    /**
     * Called once a minute by the foreground service while the user wants the filter on.
     *
     * Two things go wrong after a successful switch, and neither raises an event:
     *  - the box's own firmware rewrites `private_dns_*` (it does this to the accessibility
     *    list too), which silently ends the filtering - so a drifted setting is written back;
     *  - the resolver stops answering days later, and with Private DNS in strict mode that
     *    takes the TV's whole network down - so after [WATCH_FAILS_BEFORE_RESTORE] failed
     *    probes in a row the previous settings are restored and the wish is dropped.
     */
    suspend fun watch(context: Context) = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(P_DESIRED, false) || !canWrite(context)) return@withContext
        val target = prefs.getString(P_HOSTNAME, null) ?: return@withContext

        val resolver = context.contentResolver
        val mode = runCatching { Settings.Global.getString(resolver, KEY_MODE) }.getOrNull()
        val specifier = runCatching { Settings.Global.getString(resolver, KEY_SPECIFIER) }.getOrNull()
        if (mode != MODE_HOSTNAME || specifier != target) {
            Logging.w("Private DNS drifted (mode=$mode, specifier=$specifier); writing $target back")
            runCatching {
                Settings.Global.putString(resolver, KEY_MODE, MODE_HOSTNAME)
                Settings.Global.putString(resolver, KEY_SPECIFIER, target)
            }
            watchFails = 0
            return@withContext
        }

        val check = runCheck()
        store(prefs, check)
        if (check.normalResolves) {
            watchFails = 0
            return@withContext
        }
        watchFails++
        if (watchFails >= WATCH_FAILS_BEFORE_RESTORE) {
            Logging.w("Resolver $target stopped answering; restoring the previous DNS settings")
            prefs.edit().putBoolean(P_DESIRED, false).apply()
            restore(context, prefs)
            watchFails = 0
        }
    }

    // ------------------------------------------------------------------ reading

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun build(context: Context, prefs: SharedPreferences, check: Check?, note: String?): State {
        val resolver = context.contentResolver
        val mode = runCatching { Settings.Global.getString(resolver, KEY_MODE) }.getOrNull()
        val specifier = runCatching { Settings.Global.getString(resolver, KEY_SPECIFIER) }.getOrNull()
        val enabled = mode == MODE_HOSTNAME && !specifier.isNullOrBlank()
        val canWrite = canWrite(context)
        val remembered = prefs.getString(P_HOSTNAME, null)?.takeIf { it.isNotBlank() }
        val chosen = remembered ?: specifier?.takeIf { it.isNotBlank() } ?: DEFAULT_HOSTNAME
        val previous = prefs.getString(P_PREVIOUS_SPECIFIER, null)
            ?.takeIf { prefs.getBoolean(P_HAS_PREVIOUS, false) && it.isNotBlank() }

        return State(
            enabled = enabled,
            hostname = specifier?.takeIf { it.isNotBlank() },
            mode = mode,
            canWrite = canWrite,
            chosenHostname = chosen,
            previousHostname = previous,
            lastCheck = check,
            note = note,
            verdict = verdictFor(canWrite, enabled, specifier, check),
            actions = actionsFor(context, canWrite, enabled, check),
        )
    }

    private fun verdictFor(canWrite: Boolean, enabled: Boolean, specifier: String?, check: Check?): String {
        val host = specifier ?: "?"
        if (!enabled) {
            return if (canWrite) {
                "Off. The TV resolves names through the DNS servers your network hands out, so ad and tracker " +
                    "domains are answered normally."
            } else {
                "Ad blocking changes the TV's Private DNS setting, and Android only lets an app do that with the " +
                    "WRITE_SECURE_SETTINGS grant - one adb command, same as the accessibility engine."
            }
        }
        if (!canWrite) {
            return "On ($host), set outside this app. Turning it off or changing the resolver from here needs the " +
                "WRITE_SECURE_SETTINGS grant - one adb command, see below."
        }
        if (check == null) {
            return "On ($host). Press \"Check now\" to see whether ad domains are really being blocked."
        }
        if (!check.normalResolves) {
            return "On ($host), but the TV cannot resolve ordinary names right now - that resolver is not " +
                "answering. Turn ad blocking off or enter a different resolver."
        }
        if (check.adBlocked) {
            return "On ($host): ordinary names resolve and ad domains are being sunk. Every app that uses the TV's " +
                "DNS is covered. YouTube's in-video ads are not - those come from the same servers as the video " +
                "itself."
        }
        return "On ($host): DNS works, but the ad test domains still resolve. That resolver may not filter, or the " +
            "TV is not using it for them."
    }

    private fun actionsFor(context: Context, canWrite: Boolean, enabled: Boolean, check: Check?): List<String> = buildList {
        if (!canWrite) {
            add(
                "From an adb shell:  pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS   " +
                    "- the same grant the engine's self-healing uses.",
            )
        }
        if (enabled && check != null && !check.normalResolves) {
            add("Turn ad blocking off, or enter a resolver hostname that this network can reach.")
        }
        if (enabled && check != null && check.normalResolves && !check.adBlocked) {
            add(
                "Try a resolver with stronger filters (NextDNS or ControlD give you a personal hostname), or live " +
                    "with it: apps that run their own encrypted DNS cannot be filtered this way at all.",
            )
        }
    }

    private fun stored(prefs: SharedPreferences): Check? {
        val at = prefs.getLong(P_LAST_AT, 0L)
        if (at == 0L) return null
        return Check(
            at = at,
            normalResolves = prefs.getBoolean(P_LAST_NORMAL, false),
            adBlocked = prefs.getBoolean(P_LAST_ADS, false),
            note = prefs.getString(P_LAST_NOTE, "").orEmpty(),
        )
    }

    private fun store(prefs: SharedPreferences, check: Check) {
        prefs.edit()
            .putLong(P_LAST_AT, check.at)
            .putBoolean(P_LAST_NORMAL, check.normalResolves)
            .putBoolean(P_LAST_ADS, check.adBlocked)
            .putString(P_LAST_NOTE, check.note)
            .apply()
    }

    // ------------------------------------------------------------------ probing

    /** One live probe: ordinary names resolve, ad names are sunk. */
    private suspend fun runCheck(): Check {
        val normal = HEALTHY_HOSTS.any { resolves(it) }
        // With DNS down nothing can be concluded about filtering: a failed lookup for an
        // ad domain is not evidence that it was sunk.
        val blocked = normal && AD_HOSTS.any { isSunk(it) }
        val note = when {
            !normal -> "No name resolved at all - DNS is not answering."
            blocked -> "Ordinary names resolve; the ad test domains are being blocked."
            else -> "Ordinary names resolve, but the ad test domains resolve too."
        }
        return Check(at = System.currentTimeMillis(), normalResolves = normal, adBlocked = blocked, note = note)
    }

    private suspend fun resolves(host: String): Boolean = lookup(host)?.isNotEmpty() == true

    /**
     * A filtering resolver answers blocked names instead of failing them: NXDOMAIN
     * (which surfaces as an empty answer) or the loopback/wildcard address - AdGuard
     * hands out 127.0.0.1, which is what this TV actually did when tested.
     */
    private suspend fun isSunk(host: String): Boolean {
        val addresses = lookup(host) ?: return false
        if (addresses.isEmpty()) return true
        return addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }
    }

    /**
     * One uncached lookup; null means it could not be answered at all.
     *
     * This deliberately does not go through [InetAddress]: that caches answers inside
     * the process, and this class looks up the same handful of hostnames on every
     * probe, so a cached answer would report "DNS is fine" seconds after the resolver
     * died - the exact thing the check exists to catch. It was observed on the TV: a
     * dead resolver (`ping`: unknown host) and the app still said ordinary names
     * resolved. `DnsResolver` with `FLAG_NO_CACHE_LOOKUP` asks the network every time.
     *
     * Android 10 and older have no uncached resolver call, so there the answer may come
     * from that process's cache and a positive is weaker evidence; the app's own TV is
     * on 14, and the fallback still catches the common case (a resolver that answers).
     */
    private suspend fun lookup(host: String): List<InetAddress>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        }
        return withTimeoutOrNull(DNS_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                DnsResolver.getInstance().query(
                    null,
                    host,
                    DnsResolver.TYPE_A,
                    DnsResolver.FLAG_NO_CACHE_LOOKUP,
                    Executor { it.run() },
                    null,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            continuation.resume(answer)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.resume(null)
                        }
                    },
                )
            }
        }
    }

    /** Null when the resolver looks usable; otherwise the reason to refuse. */
    private suspend fun preflight(hostname: String): String? {
        val addresses = lookup(hostname)
        if (addresses.isNullOrEmpty()) {
            return "\"$hostname\" does not resolve on this network, so switching to it would leave the TV with no DNS."
        }
        val reachable = addresses.any { address ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, DOT_PORT), PROBE_TIMEOUT_MS)
                    true
                }
            }.getOrDefault(false)
        }
        if (!reachable) {
            return "Nothing answers on DNS-over-TLS port $DOT_PORT of \"$hostname\" - this network blocks it, " +
                "and switching would take the TV's DNS down."
        }
        return null
    }

    // --------------------------------------------------------------- the change

    private fun rememberPrevious(context: Context, prefs: SharedPreferences, target: String) {
        // Only the first switch remembers. Re-applying a different resolver must not
        // turn our own previous value into the thing "off" restores.
        if (prefs.getBoolean(P_HAS_PREVIOUS, false)) return
        val resolver = context.contentResolver
        runCatching {
            val mode = Settings.Global.getString(resolver, KEY_MODE)
            val specifier = Settings.Global.getString(resolver, KEY_SPECIFIER)
            // Already pointed at exactly this resolver (set outside the app, or by an
            // earlier install): there is nothing to restore, so "off" must mean the
            // network's own DNS rather than writing the same value straight back.
            if (mode == MODE_HOSTNAME && specifier == target) return
            prefs.edit()
                .putBoolean(P_HAS_PREVIOUS, true)
                .putString(P_PREVIOUS_MODE, mode)
                .putString(P_PREVIOUS_SPECIFIER, specifier)
                .apply()
        }
    }

    private fun restore(context: Context, prefs: SharedPreferences) {
        val resolver = context.contentResolver
        val had = prefs.getBoolean(P_HAS_PREVIOUS, false)
        val mode = prefs.getString(P_PREVIOUS_MODE, null)
        val specifier = prefs.getString(P_PREVIOUS_SPECIFIER, null)
        runCatching {
            if (had && !mode.isNullOrBlank()) {
                Settings.Global.putString(resolver, KEY_MODE, mode)
                Settings.Global.putString(resolver, KEY_SPECIFIER, specifier)
            } else {
                Settings.Global.putString(resolver, KEY_MODE, MODE_OFF)
                Settings.Global.putString(resolver, KEY_SPECIFIER, null)
            }
        }
        prefs.edit()
            .remove(P_HAS_PREVIOUS)
            .remove(P_PREVIOUS_MODE)
            .remove(P_PREVIOUS_SPECIFIER)
            .apply()
    }

    private fun normalise(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://").removeSuffix("/").removeSuffix(".")

    /** A hostname, not a URL, not an address, and no port - Private DNS takes a name. */
    private fun looksLikeHostname(value: String): Boolean =
        value.length in 3..253 && value.contains('.') && value.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                label.first() != '-' && label.last() != '-'
        }
}
