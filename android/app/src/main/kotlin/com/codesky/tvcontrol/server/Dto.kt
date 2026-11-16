package com.codesky.tvcontrol.server

import com.codesky.tvcontrol.control.ControlHub
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String, val message: String)

/** Unauthenticated probe: lets a browser that just landed on the TV say hello. */
@Serializable
data class PingResponse(
    val ok: Boolean = true,
    val name: String,
    val version: String,
    val requiresPairing: Boolean = true,
)

@Serializable
data class PairRequest(val pin: String, val device: String? = null, val label: String? = null)

@Serializable
data class PairResponse(
    val token: String,
    val name: String,
    val version: String,
)

@Serializable
data class InfoResponse(
    val name: String,
    val version: String,
    val port: Int,
    val deviceModel: String,
    val androidVersion: String,
    val engine: ControlHub.EngineState,
    val volume: ControlHub.VolumeResult,
    val screen: com.codesky.tvcontrol.accessibility.ScreenSnapshot? = null,
    val settings: SettingsDto,
    val serverTime: Long,
    /** True when Android 13+ is blocking accessibility for this install source. */
    val restrictedSettings: Boolean = false,
)

@Serializable
data class KeyRequest(val key: String, val repeat: Int = 1)

@Serializable
data class TextRequest(
    val text: String = "",
    val mode: String = "replace",
    val submit: Boolean = false,
)

@Serializable
data class LaunchRequest(
    @SerialName("package") val packageName: String? = null,
    val query: String? = null,
)

@Serializable
data class SearchRequest(val query: String, val target: String = "auto")

@Serializable
data class VolumeRequest(val action: String, val value: Int? = null)

@Serializable
data class AppDto(val pkg: String, val label: String, val leanback: Boolean)

@Serializable
data class AppsResponse(val apps: List<AppDto>)

@Serializable
data class AssistantRequest(
    val utterance: String,
    val useScreen: Boolean = true,
    val dryRun: Boolean = false,
)

@Serializable
data class RunIdResponse(val runId: String)

/** Result of POST /api/engine/enable - the app switching its own service back on. */
@Serializable
data class EnableResult(
    val ok: Boolean,
    val hasPermission: Boolean,
    val message: String,
)

@Serializable
data class AssistantStepDto(
    val index: Int,
    val action: String,
    val detail: String? = null,
    val status: String,
    val note: String? = null,
)

@Serializable
data class AssistantRunDto(
    val id: String,
    val utterance: String,
    val status: String,
    val say: String? = null,
    val steps: List<AssistantStepDto> = emptyList(),
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
data class LlmSettingsDto(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKeyConfigured: Boolean,
    val providers: List<String>,
)

@Serializable
data class AssistantSettingsDto(
    val mode: String,
    val maxSteps: Int,
    val stepDelayMs: Int,
)

@Serializable
data class SettingsDto(
    val port: Int,
    val deviceName: String,
    val autoStart: Boolean,
    val pin: String,
    val devices: List<String>,
    val llm: LlmSettingsDto,
    val assistant: AssistantSettingsDto,
)

@Serializable
data class LlmPatch(
    val provider: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
)

@Serializable
data class AssistantPatch(
    val mode: String? = null,
    val maxSteps: Int? = null,
    val stepDelayMs: Int? = null,
)

@Serializable
data class SettingsPatch(
    val port: Int? = null,
    val deviceName: String? = null,
    val autoStart: Boolean? = null,
    val llm: LlmPatch? = null,
    val assistant: AssistantPatch? = null,
)

/** POST /api/adblock - the answer is AdBlock.State, which describes the TV's setting. */
@Serializable
data class AdBlockRequest(val enabled: Boolean, val hostname: String? = null)

/** POST /api/proxy - route the TV through the active (or given) SOCKS5 server, or stop. */
@Serializable
data class ProxyEnableRequest(val enabled: Boolean, val id: String? = null)

/** POST /api/proxy/servers - add (no id) or edit a server. password: null keeps, "" clears. */
@Serializable
data class ProxyServerRequest(
    val id: String? = null,
    val name: String = "",
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class ProxySelectRequest(val id: String)

@Serializable
data class ProxyOptionsRequest(val failOpen: Boolean? = null)

@Serializable
data class WsEnvelope(
    val v: Int = 1,
    val type: String,
    val ts: Long = System.currentTimeMillis(),
    val data: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class WsClientMessage(
    val type: String,
    val v: Int? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class BluetoothStateRequest(val enabled: Boolean)

@Serializable
data class BluetoothScanRequest(val action: String = "start")

@Serializable
data class BluetoothDeviceActionRequest(val address: String)

@Serializable
data class FileActionRequest(val name: String)

@Serializable
data class MediaControlRequest(
    val action: String,
    val positionMs: Long? = null,
)
