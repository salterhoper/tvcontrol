package com.codesky.tvcontrol.remote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models for the TV's LAN API. The contract lives in docs/API.md; the web
 * dashboard's dashboard/src/api.ts is the sibling of this file. Everything is
 * defaulted/nullable so a slightly newer or older TV never crashes the client
 * (the Json below is configured with ignoreUnknownKeys = true).
 */

/** The keys the TV understands on POST /api/key and the `key` socket message. */
enum class RemoteKey(val wire: String) {
    Up("up"), Down("down"), Left("left"), Right("right"), Ok("ok"),
    Back("back"), Home("home"), Recents("recents"),
    Notifications("notifications"), QuickSettings("quick_settings"),
    ScrollUp("scroll_up"), ScrollDown("scroll_down"),
    ScrollLeft("scroll_left"), ScrollRight("scroll_right"),
    VolumeUp("volume_up"), VolumeDown("volume_down"), Mute("mute"),
    PlayPause("play_pause"), Play("play"), Pause("pause"),
    Next("next"), Previous("previous"), Stop("stop"),
    Rewind("rewind"), FastForward("fast_forward"),
}

@Serializable
data class PingResponse(
    val ok: Boolean = false,
    val name: String = "Android TV",
    val version: String = "",
    val requiresPairing: Boolean = true,
)

@Serializable
data class PairResponse(
    val token: String = "",
    val name: String = "Android TV",
    val version: String = "",
)

@Serializable
data class ErrorBody(
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class EngineState(
    val accessibilityConnected: Boolean = false,
    val canNavigate: Boolean = false,
    val canType: Boolean = false,
    val canVolume: Boolean = true,
    val note: String? = null,
)

@Serializable
data class VolumeResult(
    val ok: Boolean = false,
    val levelPercent: Int? = null,
    val muted: Boolean? = null,
    val note: String? = null,
)

@Serializable
data class FieldInfo(
    val text: String? = null,
    val hint: String? = null,
    val pkg: String? = null,
    val canSubmit: Boolean = false,
)

@Serializable
data class NodeSummary(
    val text: String? = null,
    val desc: String? = null,
    val cls: String? = null,
    val focused: Boolean = false,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val bounds: String? = null,
)

@Serializable
data class ScreenSnapshot(
    val pkg: String? = null,
    val app: String? = null,
    val focus: NodeSummary? = null,
    val field: FieldInfo? = null,
    val labels: List<String> = emptyList(),
    val scrollable: Boolean = false,
    val imeVisible: Boolean = false,
    val takenAt: Long = 0,
)

@Serializable
data class InfoResponse(
    val name: String = "Android TV",
    val version: String = "",
    val port: Int = 0,
    val deviceModel: String = "",
    val androidVersion: String = "",
    val engine: EngineState = EngineState(),
    val volume: VolumeResult = VolumeResult(),
    val screen: ScreenSnapshot? = null,
    val restrictedSettings: Boolean = false,
)

@Serializable
data class AppEntry(
    val pkg: String = "",
    val label: String = "",
    val leanback: Boolean = false,
)

@Serializable
data class AppsResponse(
    val apps: List<AppEntry> = emptyList(),
)

@Serializable
data class ActionResult(
    val ok: Boolean = false,
    val via: String? = null,
    val moved: Boolean? = null,
    val note: String? = null,
)

@Serializable
data class TextResult(
    val ok: Boolean = false,
    val via: String? = null,
    val submitted: Boolean? = null,
    val target: FieldInfo? = null,
    val note: String? = null,
)

@Serializable
data class LaunchOutcome(
    val ok: Boolean = false,
    val pkg: String? = null,
    val label: String? = null,
    val note: String? = null,
)

@Serializable
data class NowPlayingInfo(
    val active: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val isPlaying: Boolean = false,
    val playbackState: String = "",
    val canSeek: Boolean = false,
    val canPlayPause: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrev: Boolean = false,
    val hasArtwork: Boolean = false,
    val artworkBase64: String? = null,
    val lastUpdated: Long = 0,
)

@Serializable
data class MediaControlResult(
    val ok: Boolean = false,
    val action: String = "",
    val message: String? = null,
    val error: String? = null,
    val nowPlaying: NowPlayingInfo? = null,
)

@Serializable
data class AssistantStep(
    val index: Int = 0,
    val action: String = "",
    val detail: String? = null,
    val status: String = "",
    val note: String? = null,
)

@Serializable
data class AssistantRun(
    val id: String = "",
    val utterance: String = "",
    val status: String = "",
    val say: String? = null,
    val steps: List<AssistantStep> = emptyList(),
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class AssistantStartResponse(
    @SerialName("runId") val runId: String = "",
)

/** A WebSocket envelope. `data` is parsed per-type by the caller. */
@Serializable
data class Envelope(
    val v: Int = 1,
    val type: String = "",
    val ts: Long = 0,
    val data: JsonElement? = null,
)

/** A TV found on the LAN by mDNS, or typed in by hand. */
data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
) {
    val address: String get() = "$host:$port"
}

// ------------------------------------------------------------------ settings

@Serializable
data class LlmSettings(
    val provider: String = "openai",
    val baseUrl: String = "",
    val model: String = "",
    val apiKeyConfigured: Boolean = false,
    val providers: List<String> = emptyList(),
)

@Serializable
data class AssistantSettings(
    val mode: String = "plan",
    val maxSteps: Int = 12,
    val stepDelayMs: Int = 400,
)

@Serializable
data class Settings(
    val port: Int = 0,
    val deviceName: String = "",
    val autoStart: Boolean = false,
    val pin: String = "",
    val devices: List<String> = emptyList(),
    val llm: LlmSettings = LlmSettings(),
    val assistant: AssistantSettings = AssistantSettings(),
)

// --------------------------------------------------------------- diagnostics

@Serializable
data class DiagnosticsReport(
    val androidRelease: String = "",
    val sdk: Int = 0,
    val model: String = "",
    val fingerprint: String = "",
    val buildType: String? = null,
    val installer: String? = null,
    val playStoreInstalled: Boolean = false,
    val playServicesInstalled: Boolean = false,
    val googleRemoteServiceInstalled: Boolean = false,
    val remoteServicePortsOpen: List<Int> = emptyList(),
    val remoteServicePortsOpenOnLan: List<Int> = emptyList(),
    val adbDebuggingEnabled: Boolean = false,
    val developmentSettingsEnabled: Boolean = false,
    val restrictedSettings: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val enabledAccessibilityServices: String? = null,
    val serviceListedAsEnabled: Boolean? = null,
    val writeSecureSettingsGranted: Boolean? = null,
    val platformSignedWithTestKeys: Boolean? = null,
    val appSignedAsPlatform: Boolean? = null,
    val verdict: String = "",
    val actions: List<String> = emptyList(),
)

@Serializable
data class EnableResult(
    val ok: Boolean = false,
    val hasPermission: Boolean = false,
    val message: String = "",
)

// ----------------------------------------------------------------- adblock

@Serializable
data class AdBlockPreset(
    val id: String = "",
    val label: String = "",
    val hostname: String = "",
    val note: String = "",
)

@Serializable
data class AdBlockCheck(
    val at: Long = 0,
    val normalResolves: Boolean = false,
    val adBlocked: Boolean = false,
    val note: String = "",
)

@Serializable
data class AdBlockState(
    val enabled: Boolean = false,
    val hostname: String? = null,
    val mode: String? = null,
    val canWrite: Boolean = false,
    val chosenHostname: String = "",
    val previousHostname: String? = null,
    val presets: List<AdBlockPreset> = emptyList(),
    val lastCheck: AdBlockCheck? = null,
    val note: String? = null,
    val verdict: String = "",
    val actions: List<String> = emptyList(),
)

// ------------------------------------------------------------------- proxy

@Serializable
data class ProxyTest(
    val ok: Boolean = false,
    val at: Long = 0,
    val latencyMs: Long? = null,
    val exitIp: String? = null,
    val message: String = "",
)

@Serializable
data class ProxyServer(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val hasPassword: Boolean = false,
    val active: Boolean = false,
    val lastTest: ProxyTest? = null,
)

@Serializable
data class ProxyStats(
    val since: Long = 0,
    val activeConnections: Int = 0,
    val totalConnections: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val dnsQueries: Int = 0,
)

@Serializable
data class ProxyState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val consentNeeded: Boolean = false,
    val failOpen: Boolean = true,
    val activeId: String? = null,
    val servers: List<ProxyServer> = emptyList(),
    val stats: ProxyStats? = null,
    val note: String? = null,
    val verdict: String = "",
    val actions: List<String> = emptyList(),
)

// -------------------------------------------------------------------- root

@Serializable
data class RootCheckItem(
    val id: String = "",
    val label: String = "",
    val found: Boolean? = null,
    val detail: String = "",
)

@Serializable
data class RootReport(
    val level: String = "none",
    val rooted: Boolean = false,
    val appCanUseRoot: Boolean = false,
    val headline: String = "",
    val verdict: String = "",
    val checks: List<RootCheckItem> = emptyList(),
    val actions: List<String> = emptyList(),
    val checkedAt: Long = 0,
)

// --------------------------------------------------------------- bluetooth

@Serializable
data class BluetoothDeviceDto(
    val name: String = "",
    val address: String = "",
    val bonded: Boolean = false,
    val connected: Boolean = false,
    val deviceType: String = "other",
    val majorClass: Int? = null,
    val rssi: Int? = null,
    val bondState: String = "none",
)

@Serializable
data class BluetoothStatusDto(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val scanning: Boolean = false,
    val name: String? = null,
    val address: String? = null,
    val pairedDevices: List<BluetoothDeviceDto> = emptyList(),
    val discoveredDevices: List<BluetoothDeviceDto> = emptyList(),
)

@Serializable
data class BluetoothActionResult(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

// ----------------------------------------------------------- files & sideload

@Serializable
data class FileItem(
    val name: String = "",
    val size: Long = 0,
    val lastModified: Long = 0,
    val isApk: Boolean = false,
    val extension: String = "",
    val formattedSize: String = "",
    val packageName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val path: String = "",
)

@Serializable
data class StorageInfo(
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val usableBytes: Long = 0,
    val formattedFree: String = "",
    val formattedTotal: String = "",
    val freePercent: Int = 0,
    val storagePath: String = "",
    val files: List<FileItem> = emptyList(),
)

@Serializable
data class FileActionResult(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val file: FileItem? = null,
)

/** One message in the assistant chat transcript. */
data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val run: AssistantRun? = null,
)
