package com.codesky.tvcontrol.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing model.
 *
 * The TV shows a six digit PIN. A dashboard exchanges that PIN for a long lived
 * bearer token once, and keeps the token in localStorage from then on. Tokens are
 * stored hashed so a leaked prefs file does not hand out live sessions.
 */
class AuthStore(context: Context, private val secure: SecureStore) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val lock = Any()
    private val recentFailures = ArrayDeque<Long>()

    /** The PIN currently shown on the TV screen. */
    fun pin(): String = synchronized(lock) {
        val existing = prefs.getString(KEY_PIN, null)
        if (existing != null && existing.length == PIN_LENGTH) return existing
        return rotatePin()
    }

    fun rotatePin(): String = synchronized(lock) {
        val next = buildString(PIN_LENGTH) {
            repeat(PIN_LENGTH) { append(random.nextInt(10)) }
        }
        prefs.edit().putString(KEY_PIN, next).apply()
        next
    }

    /**
     * @return the bearer token on success, null on a wrong PIN, [RateLimited] when
     *         too many attempts happened in the last minute.
     */
    fun pair(pinAttempt: String, deviceName: String?): PairResult = synchronized(lock) {
        pruneFailures()
        if (recentFailures.size >= MAX_FAILURES_PER_MINUTE) return RateLimited

        if (pinAttempt.trim() != pin()) {
            recentFailures.addLast(System.currentTimeMillis())
            return WrongPin(remaining = (MAX_FAILURES_PER_MINUTE - recentFailures.size).coerceAtLeast(0))
        }

        recentFailures.clear()
        val token = generateToken()
        val label = deviceName?.trim()?.take(48)?.takeIf { it.isNotEmpty() } ?: "Dashboard"
        prefs.edit()
            .putString(KEY_TOKEN_PREFIX + fingerprint(token), label)
            .apply()
        return Paired(token)
    }

    fun verify(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return prefs.contains(KEY_TOKEN_PREFIX + fingerprint(token))
    }

    fun deviceLabel(token: String?): String {
        if (token.isNullOrBlank()) return "Dashboard"
        return prefs.getString(KEY_TOKEN_PREFIX + fingerprint(token), "Dashboard") ?: "Dashboard"
    }

    fun devices(): List<String> = prefs.all
        .filterKeys { it.startsWith(KEY_TOKEN_PREFIX) }
        .values
        .map { it.toString() }

    fun revokeAll() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(KEY_TOKEN_PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    private fun pruneFailures() {
        val cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS
        while (recentFailures.isNotEmpty() && recentFailures.first() < cutoff) {
            recentFailures.removeFirst()
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    sealed interface PairResult
    data class Paired(val token: String) : PairResult
    data class WrongPin(val remaining: Int) : PairResult
    data object RateLimited : PairResult

    companion object {
        private const val KEY_PIN = "pin"
        private const val KEY_TOKEN_PREFIX = "token."
        private const val PIN_LENGTH = 6
        private const val MAX_FAILURES_PER_MINUTE = 6
        private const val FAILURE_WINDOW_MS = 60_000L
    }
}
