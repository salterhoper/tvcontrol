package com.codesky.tvcontrol.ai

import com.codesky.tvcontrol.config.LlmDefaults
import com.codesky.tvcontrol.config.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LlmException(message: String) : Exception(message)

/**
 * One narrow capability: turn a system prompt plus a user prompt into a JSON answer.
 * Nothing provider specific leaks past this interface, which is what makes the
 * assistant pluggable (OpenAI-compatible servers, Gemini, Anthropic, or a local
 * Ollama on the LAN).
 */
interface LlmClient {
    val label: String
    suspend fun complete(system: String, user: String, json: Boolean): String
}

object Llm {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * @return null when the assistant has not been configured yet, so callers can
     *         tell the user exactly what to do instead of failing obscurely.
     */
    fun create(settings: SettingsStore, http: HttpClient): LlmClient? {
        val provider = settings.llmProvider
        val baseUrl = settings.llmBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return null

        val apiKey = settings.llmApiKey?.trim().orEmpty()
        val isLocal = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") ||
            baseUrl.startsWith("http://192.168.") || baseUrl.startsWith("http://10.")

        if (apiKey.isEmpty() && !isLocal && provider == LlmDefaults.PROVIDER_OPENAI &&
            baseUrl.contains("api.openai.com")
        ) {
            return null
        }

        return when (provider) {
            LlmDefaults.PROVIDER_GEMINI -> GeminiClient(http, baseUrl, settings.llmModel, apiKey)
            LlmDefaults.PROVIDER_ANTHROPIC -> AnthropicClient(http, baseUrl, settings.llmModel, apiKey)
            else -> OpenAiCompatibleClient(http, baseUrl, settings.llmModel, apiKey)
        }
    }

    fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }
}

class OpenAiCompatibleClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : LlmClient {

    override val label: String = "openai-compatible:$model"

    override suspend fun complete(system: String, user: String, json: Boolean): String {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        },
                    )
                },
            )
            if (json) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        val response = http.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(Llm.json.encodeToString(JsonObject.serializer(), payload))
        }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw LlmException("$model answered ${response.status.value}: ${body.take(280)}")
        }

        val root = runCatching { Llm.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw LlmException("$model sent something that is not JSON")
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("$model returned no message content")
        return content
    }
}

class GeminiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : LlmClient {

    override val label: String = "gemini:$model"

    override suspend fun complete(system: String, user: String, json: Boolean): String {
        val payload = buildJsonObject {
            put(
                "system_instruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray { add(buildJsonObject { put("text", system) }) },
                    )
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray { add(buildJsonObject { put("text", user) }) },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", 0.2)
                    if (json) put("responseMimeType", "application/json")
                },
            )
        }

        val response = http.post("$baseUrl/models/$model:generateContent") {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotEmpty()) header("x-goog-api-key", apiKey)
            setBody(Llm.json.encodeToString(JsonObject.serializer(), payload))
        }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw LlmException("gemini answered ${response.status.value}: ${body.take(280)}")
        }

        val root = runCatching { Llm.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw LlmException("gemini sent something that is not JSON")
        return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("gemini returned no text")
    }
}

class AnthropicClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : LlmClient {

    override val label: String = "anthropic:$model"

    override suspend fun complete(system: String, user: String, json: Boolean): String {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 1500)
            put("temperature", 0.2)
            put("system", system)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", if (json) "$user\n\nAnswer with JSON only." else user)
                        },
                    )
                },
            )
        }

        val response = http.post("$baseUrl/messages") {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotEmpty()) header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(Llm.json.encodeToString(JsonObject.serializer(), payload))
        }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw LlmException("$model answered ${response.status.value}: ${body.take(280)}")
        }

        val root = runCatching { Llm.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw LlmException("$model sent something that is not JSON")
        return root["content"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("$model returned no text")
    }
}

/** Shared helper: strip markdown fences a chatty model may add around its JSON. */
internal fun extractJsonObject(raw: String): JsonObject? {
    val trimmed = raw.trim()
    val candidates = buildList {
        add(trimmed)
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0 until end) add(trimmed.substring(start, end + 1))
        if (trimmed.startsWith("```")) {
            val stripped = trimmed.removePrefix("```").removePrefix("json")
                .removeSuffix("```").trim()
            add(stripped)
        }
    }
    for (candidate in candidates) {
        runCatching { Llm.json.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let { return it }
    }
    return null
}
