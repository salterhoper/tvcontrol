package com.codesky.tvcontrol.control

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.codesky.tvcontrol.accessibility.ActionResult
import com.codesky.tvcontrol.accessibility.ScreenSnapshot
import com.codesky.tvcontrol.accessibility.TextMode
import com.codesky.tvcontrol.accessibility.TextResult
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * One door for everything that can change what is on the TV.
 *
 * Routing rules, in order of what is actually possible on Android TV:
 *  - volume and media transport: always available, handled by the audio stack;
 *  - back/home/recents and text entry: accessibility engine;
 *  - directional movement and OK: accessibility engine, focus based (see the
 *    accessibility service for why this is the honest limit without root).
 */
class ControlHub(
    private val bridge: AccessibilityBridge,
    private val media: MediaKeys,
    private val launcher: AppLauncher,
    private val deepLinks: DeepLinks,
) {

    @Serializable
    data class VolumeResult(
        val ok: Boolean,
        val levelPercent: Int? = null,
        val muted: Boolean? = null,
        val note: String? = null,
    )

    @Serializable
    data class LaunchOutcome(
        val ok: Boolean,
        val pkg: String? = null,
        val label: String? = null,
        val note: String? = null,
    )

    @Serializable
    data class EngineState(
        val accessibilityConnected: Boolean,
        val canNavigate: Boolean,
        val canType: Boolean,
        val canVolume: Boolean,
        val note: String? = null,
    )

    fun engineState(): EngineState {
        val connected = bridge.connected
        return EngineState(
            accessibilityConnected = connected,
            canNavigate = connected,
            canType = connected,
            canVolume = true,
            note = if (connected) {
                null
            } else {
                "Navigation, OK, Back/Home and typing need the accessibility engine: enable \"TV Control remote\" in the TV's Settings > Accessibility. Volume and media keys work regardless."
            },
        )
    }

    suspend fun key(key: MetricKey, repeat: Int = 1): ActionResult {
        val times = repeat.coerceIn(1, 30)
        var last: ActionResult = ActionResult.fail("Nothing to do")
        repeat(times) { index ->
            if (index > 0) delay(REPEAT_DELAY_MS)
            last = single(key)
            if (!last.ok) return last
        }
        return last
    }

    private suspend fun single(key: MetricKey): ActionResult = when (key) {
        MetricKey.UP -> bridge.nav(View.FOCUS_UP)
        MetricKey.DOWN -> bridge.nav(View.FOCUS_DOWN)
        MetricKey.LEFT -> bridge.nav(View.FOCUS_LEFT)
        MetricKey.RIGHT -> bridge.nav(View.FOCUS_RIGHT)
        MetricKey.OK -> bridge.activate()

        MetricKey.BACK -> bridge.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        MetricKey.HOME -> bridge.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        MetricKey.RECENTS -> bridge.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        MetricKey.NOTIFICATIONS ->
            bridge.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        MetricKey.QUICK_SETTINGS ->
            bridge.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

        MetricKey.MENU -> ActionResult.unsupported(
            "A long-press MENU key cannot be injected without root. Use OK on the on-screen options instead.",
        )

        MetricKey.SCROLL_DOWN -> bridge.scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        MetricKey.SCROLL_UP -> bridge.scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        MetricKey.SCROLL_RIGHT -> bridge.scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
        MetricKey.SCROLL_LEFT -> bridge.scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)

        MetricKey.VOLUME_UP -> audioResult(media.volumeUp(), "volume_up")
        MetricKey.VOLUME_DOWN -> audioResult(media.volumeDown(), "volume_down")
        MetricKey.MUTE -> audioResult(media.toggleMute(), "mute")

        MetricKey.PLAY_PAUSE -> transport(media.playPause(), "play_pause")
        MetricKey.PLAY -> transport(media.play(), "play")
        MetricKey.PAUSE -> transport(media.pause(), "pause")
        MetricKey.NEXT -> transport(media.next(), "next")
        MetricKey.PREVIOUS -> transport(media.previous(), "previous")
        MetricKey.STOP -> transport(media.stop(), "stop")
        MetricKey.REWIND -> transport(media.rewind(), "rewind")
        MetricKey.FAST_FORWARD -> transport(media.fastForward(), "fast_forward")
    }

    suspend fun text(text: String, mode: TextMode, submit: Boolean): TextResult =
        bridge.text(text, mode, submit)

    suspend fun snapshot(): ScreenSnapshot = bridge.snapshot()

    suspend fun launch(target: String): LaunchOutcome {
        val app = launcher.resolve(target)
            ?: return LaunchOutcome(ok = false, note = "No installed app matches \"$target\"")
        val started = launcher.launch(app.pkg)
        return LaunchOutcome(
            ok = started,
            pkg = app.pkg,
            label = app.label,
            note = if (started) null else "Android refused to start ${app.label}",
        )
    }

    fun apps(force: Boolean = false): List<AppLauncher.AppEntry> = launcher.list(force)

    fun search(query: String, target: String): ActionResult = when (val outcome = deepLinks.search(query, target)) {
        is DeepLinks.Outcome.Handled ->
            ActionResult.ok(via = outcome.how, note = outcome.target?.let { "opened in $it" })
        is DeepLinks.Outcome.Unhandled -> ActionResult.fail(outcome.reason)
    }

    fun volume(action: String, value: Int?): VolumeResult = when (action.lowercase()) {
        "up" -> volumeResult(media.volumeUp(), "up")
        "down" -> volumeResult(media.volumeDown(), "down")
        "mute" -> volumeResult(media.toggleMute(), "mute")
        "set" -> {
            val level = media.setVolumePercent(value ?: 20)
            VolumeResult(ok = level != null, levelPercent = media.currentVolumePercent(), note = "set to $value%")
        }
        else -> VolumeResult(ok = false, note = "Unknown volume action \"$action\"")
    }

    fun volumeState(): VolumeResult = VolumeResult(
        ok = true,
        levelPercent = media.currentVolumePercent(),
        muted = media.muteState(),
    )

    private fun volumeResult(rawLevel: Int?, via: String): VolumeResult =
        VolumeResult(
            ok = rawLevel != null,
            levelPercent = media.currentVolumePercent(),
            muted = media.muteState(),
            note = if (rawLevel == null) "No audio manager available" else via,
        )

    private fun transport(sent: Boolean, via: String): ActionResult =
        if (sent) ActionResult.ok(via = "media-key") else ActionResult.fail("Could not send $via")

    /** Volume worked if the audio stack returned a level; the dashboard shows level separately. */
    private fun audioResult(level: Int?, via: String): ActionResult =
        if (level != null) ActionResult.ok(via = "audio") else ActionResult.fail("Could not $via: no audio manager")

    private companion object {
        const val REPEAT_DELAY_MS = 60L
    }
}
