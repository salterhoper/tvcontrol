package com.codesky.tvcontrol.control

import android.view.accessibility.AccessibilityNodeInfo
import com.codesky.tvcontrol.accessibility.AccessibilityActionResult
import com.codesky.tvcontrol.accessibility.ActionResult
import com.codesky.tvcontrol.accessibility.ScreenSnapshot
import com.codesky.tvcontrol.accessibility.TextMode
import com.codesky.tvcontrol.accessibility.TextResult
import com.codesky.tvcontrol.accessibility.TvControlAccessibilityService
import com.codesky.tvcontrol.core.Logging

/**
 * Indirection between "the app that wants to press a button" and "the accessibility
 * service that can". Keeps every caller free of null handling and of the fact that
 * the service is owned by the system, not by us.
 */
class AccessibilityBridge {

    @Volatile
    private var service: TvControlAccessibilityService? = null

    @Volatile
    var latest: ScreenSnapshot? = null
        private set

    val connected: Boolean
        get() = service != null

    fun attach(service: TvControlAccessibilityService) {
        this.service = service
        Logging.i("Accessibility engine attached")
    }

    fun detach(service: TvControlAccessibilityService) {
        if (this.service === service) this.service = null
        Logging.i("Accessibility engine detached")
    }

    fun publish(snapshot: ScreenSnapshot) {
        latest = snapshot
    }

    suspend fun nav(direction: Int): ActionResult =
        service?.navigate(direction) ?: offline("Directional navigation")

    suspend fun activate(): ActionResult = service?.activate() ?: offline("Pressing OK")

    suspend fun scroll(direction: Int): ActionResult = service?.scroll(direction) ?: offline("Scrolling")

    suspend fun global(action: Int): ActionResult = service?.global(action) ?: offline("Global actions")

    suspend fun text(text: String, mode: TextMode, submit: Boolean): TextResult =
        service?.inputText(text, mode, submit)
            ?: TextResult(
                ok = false,
                note = "The accessibility engine is not running. Enable TV Control remote in Settings > Accessibility on the TV.",
            )

    suspend fun snapshot(): ScreenSnapshot = service?.readScreen() ?: (latest ?: ScreenSnapshot())

    private fun offline(what: String) = AccessibilityActionResult.offline(what)
}
