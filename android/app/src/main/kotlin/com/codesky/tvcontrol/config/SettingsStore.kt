package com.codesky.tvcontrol.config

import android.content.Context
import android.os.Build
import com.codesky.tvcontrol.security.SecureStore

/**
 * Everything the user can tune, persisted as plain SharedPreferences with the one
 * secret (the LLM API key) wrapped by [SecureStore].
 */
class SettingsStore(context: Context, private val secure: SecureStore) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Android TV"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.take(48)).apply()

    /** Start the LAN server without being asked when the app or the TV boots. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var llmProvider: String
        get() = prefs.getString(KEY_LLM_PROVIDER, LlmDefaults.PROVIDER_OPENAI) ?: LlmDefaults.PROVIDER_OPENAI
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value).apply()

    var llmBaseUrl: String
        get() = prefs.getString(KEY_LLM_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: LlmDefaults.baseUrl(llmProvider)
        set(value) = prefs.edit().putString(KEY_LLM_BASE_URL, value.trim().trimEnd('/')).apply()

    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: LlmDefaults.model(llmProvider)
        set(value) = prefs.edit().putString(KEY_LLM_MODEL, value.trim()).apply()

    /** Plain getter: the model call needs the real thing. Never sent back to clients. */
    var llmApiKey: String?
        get() = secure.decrypt(prefs.getString(KEY_LLM_API_KEY, null))
        set(value) {
            prefs.edit().putString(KEY_LLM_API_KEY, value?.let { secure.encrypt(it) }).apply()
        }

    val llmApiKeyConfigured: Boolean
        get() = llmApiKey.isNullOrBlank().not()

    /** "plan" runs a whole plan in one shot, "agent" re-reads the screen between steps. */
    var assistantMode: String
        get() = prefs.getString(KEY_MODE, MODE_PLAN) ?: MODE_PLAN
        set(value) = prefs.edit().putString(KEY_MODE, if (value == MODE_AGENT) MODE_AGENT else MODE_PLAN).apply()

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 12)
        set(value) = prefs.edit().putInt(KEY_MAX_STEPS, value.coerceIn(1, 40)).apply()

    var stepDelayMs: Int
        get() = prefs.getInt(KEY_STEP_DELAY, 400)
        set(value) = prefs.edit().putInt(KEY_STEP_DELAY, value.coerceIn(0, 3000)).apply()

    companion object {
        const val DEFAULT_PORT = 8787
        const val MODE_PLAN = "plan"
        const val MODE_AGENT = "agent"

        private const val KEY_PORT = "port"
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_AUTO_START = "autoStart"
        private const val KEY_LLM_PROVIDER = "llm.provider"
        private const val KEY_LLM_BASE_URL = "llm.baseUrl"
        private const val KEY_LLM_MODEL = "llm.model"
        private const val KEY_LLM_API_KEY = "llm.apiKey"
        private const val KEY_MODE = "assistant.mode"
        private const val KEY_MAX_STEPS = "assistant.maxSteps"
        private const val KEY_STEP_DELAY = "assistant.stepDelayMs"
    }
}

object LlmDefaults {
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_ANTHROPIC = "anthropic"

    fun baseUrl(provider: String): String = when (provider) {
        PROVIDER_OPENAI -> "https://api.openai.com/v1"
        PROVIDER_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        PROVIDER_ANTHROPIC -> "https://api.anthropic.com/v1"
        else -> ""
    }

    /**
     * Starting points, all editable from the dashboard: the assistant speaks the
     * plain OpenAI-compatible dialect, so any base URL works (OpenAI, Groq,
     * Together, a local Ollama on the LAN, ...).
     */
    fun model(provider: String): String = when (provider) {
        PROVIDER_OPENAI -> "gpt-4o-mini"
        PROVIDER_GEMINI -> "gemini-2.0-flash"
        PROVIDER_ANTHROPIC -> "claude-3-5-haiku-latest"
        else -> ""
    }

    fun knownProviders(): List<String> =
        listOf(PROVIDER_OPENAI, PROVIDER_GEMINI, PROVIDER_ANTHROPIC)
}
